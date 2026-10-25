package cn.org.chris.wake.starter.runtime;

import cn.org.chris.wake.app.workflow.ClaimWorkItemCommand;
import cn.org.chris.wake.app.workflow.CheckpointTransitionResult;
import cn.org.chris.wake.app.workflow.CompleteWorkItemCommand;
import cn.org.chris.wake.app.workflow.CompleteWorkItemResult;
import cn.org.chris.wake.app.workflow.ReviewRoundResult;
import cn.org.chris.wake.app.workflow.StartWorkItemCommand;
import cn.org.chris.wake.app.workflow.SubmitReviewCommand;
import cn.org.chris.wake.app.workflow.WorkCompletionCoordinator;
import cn.org.chris.wake.app.workflow.WorkCompletionDecision;
import cn.org.chris.wake.app.workflow.WorkImpactLevel;
import cn.org.chris.wake.app.workflow.WorkflowOutboxProjector;
import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.workflow.OutboxAction;
import cn.org.chris.wake.domain.workflow.OutboxActionId;
import cn.org.chris.wake.domain.workflow.OutboxActionType;
import cn.org.chris.wake.domain.workflow.ReviewVerdict;
import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import cn.org.chris.wake.domain.workflow.WorkflowSignal;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.infra.agentscope.tool.WorkflowToolCoordinator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 把 AgentScope 最小完成/评审协议适配为 app 层原子 command，并立即投影其 outbox。
 */
public final class CoordinatedWorkflowToolCoordinator implements WorkflowToolCoordinator {

    /** 历史 task_done 中不再作为权威的 Test Runner 复制字段。 */
    private static final List<String> LEGACY_TEST_FIELDS = List.of(
            "status", "exit_code", "pytest_collected", "pytest_passed",
            "coverage_percent", "failure_type", "retryable"
    );

    /** runtime v2 权威仓储。 */
    private final WorkflowRuntimeRepository runtimeRepository;

    /** 完成、领取、启动和评审原子协调器。 */
    private final WorkCompletionCoordinator completionCoordinator;

    /** 完成 command 提交后立即投影旧存储副作用。 */
    private final WorkflowOutboxProjector outboxProjector;

    /** 审计动作时间来源。 */
    private final Clock clock;

    /** 当前运行时是否允许新项目创建 runtime v2 aggregate。 */
    private final boolean runtimeV2Enabled;

    /** 新项目创建时冻结的工作流档位。 */
    private final WorkflowProfile defaultProfile;

    /** 新项目创建时冻结的评审策略。 */
    private final ReviewPolicy reviewPolicy;

    /**
     * 使用系统 UTC 时钟创建工具协调适配器。
     *
     * @param runtimeRepository runtime v2 权威仓储
     * @param completionCoordinator app 层完成协调器
     * @param outboxProjector outbox 投影器
     */
    public CoordinatedWorkflowToolCoordinator(
            WorkflowRuntimeRepository runtimeRepository,
            WorkCompletionCoordinator completionCoordinator,
            WorkflowOutboxProjector outboxProjector
    ) {
        this(
                runtimeRepository, completionCoordinator, outboxProjector, Clock.systemUTC(),
                true, WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
    }

    /**
     * 创建可注入时钟的工具协调适配器。
     *
     * @param runtimeRepository runtime v2 权威仓储
     * @param completionCoordinator app 层完成协调器
     * @param outboxProjector outbox 投影器
     * @param clock UTC 时钟
     */
    CoordinatedWorkflowToolCoordinator(
            WorkflowRuntimeRepository runtimeRepository,
            WorkCompletionCoordinator completionCoordinator,
            WorkflowOutboxProjector outboxProjector,
            Clock clock
    ) {
        this(
                runtimeRepository, completionCoordinator, outboxProjector, clock,
                true, WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
    }

    /**
     * 创建由运行配置冻结启用状态、profile 和 review policy 的生产工具协调器。
     *
     * @param runtimeRepository runtime v2 权威仓储
     * @param completionCoordinator app 层完成协调器
     * @param outboxProjector outbox 投影器
     * @param clock UTC 时钟
     * @param runtimeV2Enabled 是否允许新项目进入 coordinated
     * @param defaultProfile 新项目冻结档位
     * @param reviewPolicy 新项目冻结评审策略
     */
    public CoordinatedWorkflowToolCoordinator(
            WorkflowRuntimeRepository runtimeRepository,
            WorkCompletionCoordinator completionCoordinator,
            WorkflowOutboxProjector outboxProjector,
            Clock clock,
            boolean runtimeV2Enabled,
            WorkflowProfile defaultProfile,
            ReviewPolicy reviewPolicy
    ) {
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository, "runtimeRepository 不能为空");
        this.completionCoordinator = Objects.requireNonNull(
                completionCoordinator, "completionCoordinator 不能为空"
        );
        this.outboxProjector = Objects.requireNonNull(outboxProjector, "outboxProjector 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.runtimeV2Enabled = runtimeV2Enabled;
        this.defaultProfile = Objects.requireNonNull(defaultProfile, "defaultProfile 不能为空");
        this.reviewPolicy = Objects.requireNonNull(reviewPolicy, "reviewPolicy 不能为空");
    }

    /** 返回当前运行配置是否允许新项目创建 runtime v2。 */
    @Override
    public boolean runtimeV2Enabled() {
        return runtimeV2Enabled;
    }

    /** 创建冻结 profile/policy 的 aggregate，随后立即投影 project_created 事件。 */
    @Override
    public Map<String, Object> initializeProject(String projectId, String projectName) {
        if (!runtimeV2Enabled) {
            return Map.of("coordinated", false);
        }
        WorkflowRuntimeState state = completionCoordinator.initializeProject(
                projectId, projectName, defaultProfile, reviewPolicy
        );
        outboxProjector.projectProject(projectId);
        WorkExecution checkpoint = state.executions().stream()
                .filter(execution -> execution.stage() == WorkflowStage.REQUIREMENTS_CHECKPOINT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("需求 checkpoint execution 缺失"));
        return Map.of(
                "coordinated", true,
                "profile", state.profile().name(),
                "review_policy", state.reviewPolicy().name(),
                "execution_id", checkpoint.executionId()
        );
    }

    /** 判断项目是否存在 runtime v2 aggregate。 */
    @Override
    public boolean coordinated(String projectId) {
        return runtimeRepository.find(projectId).isPresent();
    }

    /** 把 checkpoint 有限信号交给 app 原子协调器并立即投影下一 assignment。 */
    @Override
    public Map<String, Object> resolveCheckpoint(String projectId, String signal) {
        if (!coordinated(projectId)) {
            throw new IllegalStateException("项目尚未由 runtime v2 接管");
        }
        WorkflowSignal parsed = parseCheckpointSignal(signal);
        CheckpointTransitionResult result = completionCoordinator.resolveCheckpoint(
                commandId("checkpoint", projectId, parsed.name()), projectId, parsed
        );
        outboxProjector.projectProject(projectId);
        return Map.of(
                "execution_id", result.executionId(),
                "from_stage", result.fromStage().name(),
                "to_stage", result.toStage().name(),
                "next_execution_id", result.nextExecutionId()
        );
    }

    /**
     * 从 task_assign 正文读取 WorkExecution ID，并原子绑定 assignment、dispatch 与 session。
     */
    @Override
    public void claimAndStart(
            String projectId,
            String role,
            String assignmentId,
            Object assignmentContent,
            String dispatchId,
            String agentSessionId
    ) {
        Map<?, ?> values = requireMap(assignmentContent, "task_assign content");
        String executionId = requireText(findValue(values, "execution_id"), "execution_id");
        WorkExecution execution = requireExecution(projectId, executionId);
        if (!execution.owner().equals(role)) {
            throw new IllegalArgumentException("task_assign Owner 与当前角色不一致");
        }
        completionCoordinator.claimWorkItem(new ClaimWorkItemCommand(
                commandId("claim", executionId, assignmentId), projectId, executionId, assignmentId
        ));
        completionCoordinator.startWorkItem(new StartWorkItemCommand(
                commandId("start", executionId, dispatchId), projectId, executionId,
                dispatchId, agentSessionId
        ));
    }

    /**
     * 解析最小 task_done；测试阶段把 execution_id 解释为 Test Runner 引用，当前 WorkExecution 由 Java 唯一定位。
     */
    @Override
    public Map<String, Object> complete(
            String projectId,
            String role,
            String subject,
            Object content
    ) {
        Map<?, ?> values = requireMap(content, "task_done content");
        String submittedExecutionId = requireText(findValue(values, "execution_id"), "execution_id");
        WorkExecution current = resolveCompletionExecution(projectId, role, submittedExecutionId);
        String testExecutionId = "";
        if (requiresTestEvidence(current.stage())) {
            Object explicitTestExecution = findValue(values, "test_execution_id");
            testExecutionId = explicitTestExecution == null
                    ? submittedExecutionId
                    : requireText(explicitTestExecution, "test_execution_id");
        } else if (!current.executionId().equals(submittedExecutionId)) {
            throw new IllegalArgumentException("execution_id 不属于当前 WorkExecution");
        }
        List<String> artifacts = artifacts(values);
        WorkCompletionDecision decision = decision(values, current.stage());
        String rationale = optionalText(findValue(values, "rationale"));
        if (rationale.isBlank()) {
            rationale = requireText(subject, "rationale");
        }
        CompleteWorkItemResult result = completionCoordinator.completeWorkItem(
                new CompleteWorkItemCommand(
                        commandId(
                                "complete", current.executionId(), submittedExecutionId, decision.name()
                        ),
                        projectId,
                        current.executionId(),
                        role,
                        artifacts,
                        decision,
                        rationale,
                        optionalDouble(findValue(values, "self_score"), "self_score"),
                        optionalDouble(findValue(values, "hard_constraints"), "hard_constraints"),
                        impactLevel(values),
                        testExecutionId,
                        optionalBoolean(findValue(values, "advisory_audit_requested"))
                )
        );
        List<String> ignoredLegacyFields = LEGACY_TEST_FIELDS.stream()
                .filter(field -> containsKey(values, field))
                .toList();
        if (!ignoredLegacyFields.isEmpty()) {
            recordLegacyFieldAudit(projectId, current, ignoredLegacyFields);
        }
        outboxProjector.projectProject(projectId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("execution_id", result.executionId());
        response.put("outcome", result.outcome().name());
        response.put("workflow_stage", result.workflowStage().name());
        response.put("next_execution_id", result.nextExecutionId());
        response.put("review_round_id", result.reviewRoundId());
        response.put("detail_code", result.detailCode());
        response.put("ignored_legacy_test_fields", ignoredLegacyFields);
        return Map.copyOf(response);
    }

    /**
     * 解析 review_round_id 与有限 verdict，禁止依赖自由文本主题关联评审。
     */
    @Override
    public Map<String, Object> submitReview(String projectId, String reviewer, Object content) {
        Map<?, ?> values = requireMap(content, "review_done content");
        String reviewRoundId = requireText(findValue(values, "review_round_id"), "review_round_id");
        String rawVerdict = requireText(findValue(values, "verdict"), "verdict")
                .toUpperCase(Locale.ROOT);
        ReviewVerdict verdict = ReviewVerdict.valueOf(rawVerdict);
        ReviewRoundResult result = completionCoordinator.submitReview(new SubmitReviewCommand(
                commandId("review", reviewRoundId, reviewer),
                projectId,
                reviewRoundId,
                reviewer,
                verdict
        ));
        outboxProjector.projectProject(projectId);
        return Map.of(
                "review_round_id", result.reviewRoundId(),
                "review_status", result.reviewStatus().name(),
                "execution_id", result.executionId(),
                "execution_status", result.executionStatus().name(),
                "next_execution_id", result.nextExecutionId()
        );
    }

    /**
     * 记录旧 Test Runner 复制字段已被忽略的有界审计事件，不保存字段值。
     */
    private void recordLegacyFieldAudit(
            String projectId,
            WorkExecution execution,
            List<String> ignoredFields
    ) {
        WorkflowCommandId auditCommandId = commandId("legacy-audit", execution.executionId(), "fields");
        runtimeRepository.execute(projectId, auditCommandId, current -> {
            Instant clockNow = Instant.now(clock);
            Instant now = clockNow.isBefore(current.updatedAt()) ? current.updatedAt() : clockNow;
            OutboxAction audit = OutboxAction.pending(
                    new OutboxActionId(auditCommandId.value() + ":event"),
                    OutboxActionType.WORKFLOW_EVENT,
                    projectId,
                    Map.of(
                            "action", "task_done_legacy_fields_ignored",
                            "execution_id", execution.executionId(),
                            "owner", execution.owner(),
                            "fields", String.join(",", ignoredFields)
                    ),
                    now
            );
            return new WorkflowRuntimeRepository.WorkflowCommandMutation(
                    current.enqueue(audit, now), "AUDITED", execution.executionId()
            );
        });
    }

    /**
     * 解析 task_done 对应的工作执行；已完成重放优先按工作或测试 execution ID 命中原记录。
     */
    private WorkExecution resolveCompletionExecution(
            String projectId,
            String role,
            String submittedExecutionId
    ) {
        List<WorkExecution> owned = runtimeRepository.find(projectId).orElseThrow(
                        () -> new IllegalStateException("项目没有 runtime v2 aggregate")
                ).executions().stream()
                .filter(execution -> execution.owner().equals(role))
                .toList();
        List<WorkExecution> referenced = owned.stream()
                .filter(execution -> execution.executionId().equals(submittedExecutionId)
                        || execution.testExecutionId().equals(submittedExecutionId))
                .toList();
        if (referenced.size() == 1) {
            return referenced.get(0);
        }
        List<WorkExecution> running = owned.stream()
                .filter(execution -> execution.status() == WorkExecutionStatus.RUNNING)
                .toList();
        if (running.size() != 1) {
            throw new IllegalStateException("task_done 必须命中当前 Owner 唯一 WorkExecution");
        }
        return running.get(0);
    }

    /**
     * 要求项目中存在指定 WorkExecution。
     */
    private WorkExecution requireExecution(String projectId, String executionId) {
        return runtimeRepository.find(projectId)
                .flatMap(state -> state.findExecution(executionId))
                .orElseThrow(() -> new IllegalArgumentException("execution_id 不属于当前项目"));
    }

    /**
     * 解析 artifacts 数组，并兼容历史 artifact 单值字段。
     */
    private static List<String> artifacts(Map<?, ?> values) {
        Object raw = findValue(values, "artifacts");
        if (raw == null) {
            raw = findValue(values, "artifact");
        }
        List<String> result = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                result.add(requireText(value, "artifacts"));
            }
        } else if (raw != null) {
            result.add(requireText(raw, "artifacts"));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("artifacts 必须至少包含一个产物路径");
        }
        return List.copyOf(result);
    }

    /**
     * 解析有限完成结论；旧 QA 失败正文可由 defects/status 确定性映射为 DEFECT_FOUND。
     */
    private static WorkCompletionDecision decision(Map<?, ?> values, WorkflowStage stage) {
        String raw = optionalText(findValue(values, "decision"));
        if (!raw.isBlank()) {
            try {
                return WorkCompletionDecision.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalidDecision) {
                throw new IllegalArgumentException(
                        "decision 非法；允许值仅为 ACCEPTED 或 DEFECT_FOUND。"
                                + "PM/RD 正常完成必须使用 ACCEPTED；"
                                + "DEFECT_FOUND 仅用于 QA 真实测试发现缺陷",
                        invalidDecision
                );
            }
        }
        boolean qaDefect = stage == WorkflowStage.QA_TEST_EXECUTION
                && (findValue(values, "defects") != null
                || "TEST_FAILED".equalsIgnoreCase(optionalText(findValue(values, "status"))));
        return qaDefect ? WorkCompletionDecision.DEFECT_FOUND : WorkCompletionDecision.ACCEPTED;
    }

    /** 解析缺省为 LOW 的有限影响等级。 */
    private static WorkImpactLevel impactLevel(Map<?, ?> values) {
        String raw = optionalText(findValue(values, "impact_level"));
        return raw.isBlank() ? WorkImpactLevel.LOW : WorkImpactLevel.valueOf(raw.toUpperCase(Locale.ROOT));
    }

    /** 判断当前阶段是否必须引用真实 Test Runner execution。 */
    private static boolean requiresTestEvidence(WorkflowStage stage) {
        return stage == WorkflowStage.CODE_IMPLEMENTATION
                || stage == WorkflowStage.DEFECT_FIX
                || stage == WorkflowStage.QA_TEST_EXECUTION;
    }

    /** 要求正文是结构化 Map。 */
    private static Map<?, ?> requireMap(Object content, String name) {
        if (!(content instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException(name + " 必须是结构化 JSON");
        }
        return values;
    }

    /** 递归查找首个字段值，兼容历史 evidence/breakdown 嵌套。 */
    private static Object findValue(Map<?, ?> values, String key) {
        if (values.containsKey(key)) {
            return values.get(key);
        }
        for (Object value : values.values()) {
            if (value instanceof Map<?, ?> nested) {
                Object found = findValue(nested, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 递归判断历史正文是否携带指定复制字段，仅审计字段名而不读取字段值。 */
    private static boolean containsKey(Map<?, ?> values, String key) {
        if (values.containsKey(key)) {
            return true;
        }
        for (Object value : values.values()) {
            if (value instanceof Map<?, ?> nested && containsKey(nested, key)) {
                return true;
            }
        }
        return false;
    }

    /** 读取必填文本并去除边界空白。 */
    private static String requireText(Object value, String name) {
        String text = optionalText(value);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text;
    }

    /** 把可空字段转换为去除边界空白的文本。 */
    private static String optionalText(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** 解析 0 到 1 的可选数值。 */
    private static Double optionalDouble(Object value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof Number number
                    ? number.doubleValue()
                    : Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " 必须是数值", failure);
        }
    }

    /** 解析缺省为 false 的可选布尔值。 */
    private static boolean optionalBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text
                && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException("advisory_audit_requested 必须是布尔值");
    }

    /**
     * 将工具小写信号限制为需求或交付 checkpoint 的四种合法结论。
     *
     * @param signal 工具输入信号
     * @return 领域有限信号
     */
    private static WorkflowSignal parseCheckpointSignal(String signal) {
        String normalized = requireText(signal, "signal").toUpperCase(Locale.ROOT);
        WorkflowSignal parsed = WorkflowSignal.valueOf(normalized);
        if (parsed != WorkflowSignal.CHECKPOINT_APPROVED
                && parsed != WorkflowSignal.CHECKPOINT_REJECTED
                && parsed != WorkflowSignal.DELIVERY_APPROVED
                && parsed != WorkflowSignal.DELIVERY_REJECTED) {
            throw new IllegalArgumentException("coordinated advance_workflow 只接受 checkpoint 或交付结论");
        }
        return parsed;
    }

    /**
     * 以业务关键字段摘要生成长度受限的稳定 command ID。
     */
    private static WorkflowCommandId commandId(String operation, String... parts) {
        String source = operation + "|" + String.join("|", parts);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return new WorkflowCommandId(
                    "tool-" + operation + "-" + java.util.HexFormat.of().formatHex(digest, 0, 8)
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }
}
