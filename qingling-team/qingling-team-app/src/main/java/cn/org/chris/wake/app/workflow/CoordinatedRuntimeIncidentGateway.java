package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.gateway.RuntimeIncidentGateway;
import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.model.AgentExecutionException;
import cn.org.chris.wake.domain.workflow.OutboxAction;
import cn.org.chris.wake.domain.workflow.OutboxActionId;
import cn.org.chris.wake.domain.workflow.OutboxActionType;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Runner 类型化故障原子写入 WorkExecution 和恢复 outbox，为有测试证据的代码收敛保留有限续跑，
 * 并让 legacy 项目继续使用旧故障链。
 */
public final class CoordinatedRuntimeIncidentGateway implements RuntimeIncidentGateway {

    /** runtime v2 权威仓储。 */
    private final WorkflowRuntimeRepository runtimeRepository;

    /** 可枚举项目的共享工作区端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** 负责及时投影恢复 assignment 和 wake 的应用服务。 */
    private final WorkflowOutboxProjector outboxProjector;

    /** 没有 v2 aggregate 时使用的 legacy incident 端口。 */
    private final RuntimeIncidentGateway legacyGateway;

    /** incident 业务时间来源。 */
    private final Clock clock;

    /**
     * 使用系统 UTC 时钟创建 coordinated incident 端口。
     *
     * @param runtimeRepository runtime v2 仓储
     * @param workspaceRepository 工作区端口
     * @param outboxProjector outbox 投影器
     * @param legacyGateway legacy incident 端口
     */
    public CoordinatedRuntimeIncidentGateway(
            WorkflowRuntimeRepository runtimeRepository,
            WorkspaceRepository workspaceRepository,
            WorkflowOutboxProjector outboxProjector,
            RuntimeIncidentGateway legacyGateway
    ) {
        this(runtimeRepository, workspaceRepository, outboxProjector, legacyGateway, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的 coordinated incident 端口。
     *
     * @param runtimeRepository runtime v2 仓储
     * @param workspaceRepository 工作区端口
     * @param outboxProjector outbox 投影器
     * @param legacyGateway legacy incident 端口
     * @param clock UTC 时钟
     */
    public CoordinatedRuntimeIncidentGateway(
            WorkflowRuntimeRepository runtimeRepository,
            WorkspaceRepository workspaceRepository,
            WorkflowOutboxProjector outboxProjector,
            RuntimeIncidentGateway legacyGateway,
            Clock clock
    ) {
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository, "runtimeRepository 不能为空");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.outboxProjector = Objects.requireNonNull(outboxProjector, "outboxProjector 不能为空");
        this.legacyGateway = Objects.requireNonNull(legacyGateway, "legacyGateway 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 对 v2 项目保持 stage/owner；一般故障只恢复一次，真实测试进度可额外续跑一次。
     *
     * @param projectId 项目标识
     * @param role 当前 Owner
     * @param occurrenceId 稳定故障发生标识
     * @param failureType Agent 故障分类
     */
    @Override
    public void reportAgentExecutionFailure(
            String projectId,
            String role,
            String occurrenceId,
            AgentExecutionException.FailureType failureType
    ) {
        if (runtimeRepository.find(projectId).isEmpty()) {
            legacyGateway.reportAgentExecutionFailure(projectId, role, occurrenceId, failureType);
            return;
        }
        WorkflowCommandId commandId = incidentCommandId(occurrenceId, failureType);
        runtimeRepository.execute(projectId, commandId, current -> applyIncident(
                current, role, failureType.name(), commandId,
                monotonicNow(current.updatedAt(), Instant.now(clock))
        ));
        outboxProjector.projectProject(projectId);
    }

    /**
     * 无项目故障仅在 runtime v2 Owner 唯一时归因，否则保留 legacy 的安全解析逻辑。
     *
     * @param role 当前角色
     * @param occurrenceId 稳定故障发生标识
     * @param failureType Agent 故障分类
     */
    @Override
    public void reportUnscopedAgentExecutionFailure(
            String role,
            String occurrenceId,
            AgentExecutionException.FailureType failureType
    ) {
        Optional<String> projectId = resolveProjectId(role);
        if (projectId.isPresent()) {
            reportAgentExecutionFailure(projectId.orElseThrow(), role, occurrenceId, failureType);
            return;
        }
        legacyGateway.reportUnscopedAgentExecutionFailure(role, occurrenceId, failureType);
    }

    /**
     * 按唯一开放 WorkExecution Owner 解析 heartbeat 项目，禁止多项目猜测。
     *
     * @param role 团队角色
     * @return 唯一 v2 项目标识
     */
    @Override
    public Optional<String> resolveProjectId(String role) {
        List<String> candidates = workspaceRepository.listProjectIds().stream()
                .filter(projectId -> runtimeRepository.find(projectId)
                        .map(state -> openExecutions(state, role).size() == 1)
                        .orElse(false))
                .toList();
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }
        return legacyGateway.resolveProjectId(role);
    }

    /**
     * v2 项目以 WorkExecution BLOCKED 为准，legacy 项目继续读取旧 incident 投影。
     *
     * @param projectId 项目标识
     * @return 项目是否阻断
     */
    @Override
    public boolean isRuntimeBlocked(String projectId) {
        return runtimeRepository.find(projectId)
                .map(state -> state.executions().stream()
                        .anyMatch(execution -> execution.status() == WorkExecutionStatus.BLOCKED))
                .orElseGet(() -> legacyGateway.isRuntimeBlocked(projectId));
    }

    /**
     * 在 runtime 项目锁内定位唯一开放执行，并按有限预算形成 RECOVERING 或 BLOCKED 状态与 outbox。
     *
     * @param current 锁内 aggregate
     * @param role 当前 Owner
     * @param failureType 有限失败类型
     * @param commandId incident command 标识
     * @param now 业务时间
     * @return 原子 command 变更
     */
    private WorkflowRuntimeRepository.WorkflowCommandMutation applyIncident(
            WorkflowRuntimeState current,
            String role,
            String failureType,
            WorkflowCommandId commandId,
            Instant now
    ) {
        List<WorkExecution> open = openExecutions(current, role);
        if (open.size() != 1) {
            throw new IllegalStateException("v2 incident 必须命中唯一开放 WorkExecution");
        }
        WorkExecution execution = open.get(0);
        // 只有第二回合已真实进入 Test Runner，才允许第三回合完成修复和提交。
        boolean verifiedTestProgress = "TEST_RUNNER_COMPLETION_PENDING".equals(failureType)
                && execution.attempt() == 2;
        boolean retryable = execution.status() != WorkExecutionStatus.RECOVERING
                && (execution.attempt() == 1 || verifiedTestProgress)
                && execution.status() != WorkExecutionStatus.PENDING;
        WorkExecution changedExecution = retryable
                ? execution.recover(failureType, now)
                : execution.block(failureType, now);
        String requiredAction = requiredAction(execution, failureType);
        WorkflowRuntimeState changed = current.replaceExecution(changedExecution, now);
        changed = changed.enqueue(OutboxAction.pending(
                actionId(commandId, "event"), OutboxActionType.WORKFLOW_EVENT,
                current.projectId(), Map.ofEntries(
                        Map.entry("action", "tool_execution_failed"),
                        Map.entry("execution_id", execution.executionId()),
                        Map.entry("failure_type", failureType),
                        Map.entry("failure_fingerprint", failureType),
                        Map.entry("tool", "agentscope_dispatch"),
                        Map.entry("owner", role),
                        Map.entry("required_action", requiredAction),
                        Map.entry("runtime_status", changedExecution.status().name()),
                        Map.entry("stage", execution.stage().name()),
                        Map.entry("attempt", Integer.toString(execution.attempt())),
                        Map.entry("retryable", Boolean.toString(retryable))
                ), now
        ), now);
        if (retryable) {
            changed = changed.enqueue(OutboxAction.pending(
                    actionId(commandId, "mail"), OutboxActionType.MAILBOX_MESSAGE,
                    role, Map.of(
                            "message_type", "task_assign",
                            "project_id", current.projectId(),
                            "execution_id", execution.executionId(),
                            "stage", execution.stage().name(),
                            "failure_type", failureType,
                            "protocol", "agent_execution_failed",
                            "required_action", requiredAction,
                            "subject", "恢复当前阶段执行"
                    ), now
            ), now);
            changed = changed.enqueue(OutboxAction.pending(
                    actionId(commandId, "wake"), OutboxActionType.CRON_WAKE,
                    "team:" + role, Map.of(
                            "routing_key", "team:" + role,
                            "project_id", current.projectId(),
                            "execution_id", execution.executionId()
                    ), now
            ), now);
        }
        return new WorkflowRuntimeRepository.WorkflowCommandMutation(
                changed, changedExecution.status().name(),
                execution.executionId() + "|" + changedExecution.status().name()
        );
    }

    /**
     * 返回指定 Owner 的全部非终态 WorkExecution。
     *
     * @param state runtime aggregate
     * @param role 团队角色
     * @return 开放执行列表
     */
    private static List<WorkExecution> openExecutions(WorkflowRuntimeState state, String role) {
        return state.executions().stream()
                .filter(execution -> execution.owner().equals(role))
                .filter(execution -> !execution.status().terminal())
                .toList();
    }

    /**
     * 根据权威 stage 与 Owner 生成恢复回合必须执行的有限动作。
     *
     * @param execution 当前开放执行
     * @param failureType 当前类型化失败
     * @return 不包含业务正文、模型输出或凭据的稳定动作名
     */
    private static String requiredAction(WorkExecution execution, String failureType) {
        if ("TEST_RUNNER_NOT_INVOKED".equals(failureType)) {
            return "run_project_tests_now_then_fix_or_task_done";
        }
        if ("TEST_RUNNER_COMPLETION_PENDING".equals(failureType)) {
            return "fix_from_test_result_then_rerun_and_task_done";
        }
        if ("rd".equals(execution.owner())
                && (execution.stage() == cn.org.chris.wake.domain.workflow.WorkflowStage.CODE_IMPLEMENTATION
                || execution.stage() == cn.org.chris.wake.domain.workflow.WorkflowStage.DEFECT_FIX)) {
            return "run_project_tests_then_task_done";
        }
        if ("qa".equals(execution.owner())
                && execution.stage() == cn.org.chris.wake.domain.workflow.WorkflowStage.QA_TEST_EXECUTION) {
            return "run_project_tests_then_task_done";
        }
        return "send_task_done";
    }

    /**
     * 由不可信 occurrence 文本生成固定长度安全 command ID。
     *
     * @param occurrenceId 故障发生标识
     * @param failureType 故障分类
     * @return 稳定 incident command ID
     */
    private static WorkflowCommandId incidentCommandId(
            String occurrenceId,
            AgentExecutionException.FailureType failureType
    ) {
        return new WorkflowCommandId("incident:" + digest(
                Objects.toString(occurrenceId, "") + ":" + failureType.name()
        ));
    }

    /**
     * 从 command ID 派生稳定 outbox action ID。
     *
     * @param commandId command 标识
     * @param suffix 动作后缀
     * @return outbox action 标识
     */
    private static OutboxActionId actionId(WorkflowCommandId commandId, String suffix) {
        return new OutboxActionId(commandId.value() + ":" + suffix);
    }

    /**
     * 计算 16 位十六进制 SHA-256 摘要，避免 incident ID 保存外部正文。
     *
     * @param value 待摘要文本
     * @return 固定长度摘要
     */
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    /**
     * 在固定测试时钟或紧邻 command 下保持 aggregate 时间不回退。
     *
     * @param previous aggregate 当前更新时间
     * @param candidate 时钟候选时间
     * @return 不早于 aggregate 的时间
     */
    private static Instant monotonicNow(Instant previous, Instant candidate) {
        return candidate.isBefore(previous) ? previous : candidate;
    }
}
