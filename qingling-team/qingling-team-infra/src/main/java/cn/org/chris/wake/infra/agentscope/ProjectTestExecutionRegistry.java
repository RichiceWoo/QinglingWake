package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.domain.workspace.WorkspacePolicy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 在单次运行时内保存受控 Test Runner 的真实终态，供完成证据门禁按 execution ID 核验。
 */
public final class ProjectTestExecutionRegistry {

    /** 防止长期运行进程因历史测试证据无限增长而占用内存。 */
    private static final int MAX_ENTRIES = 2_048;

    /** 按写入顺序保存 execution ID 与不可变执行快照。 */
    private final Map<String, RecordedExecution> executions = new LinkedHashMap<>();

    /**
     * 登记当前项目、角色及测试启动时工作流阶段对应的真实测试终态。
     *
     * @param projectId 项目标识
     * @param role 执行测试的团队角色
     * @param stage 测试启动时冻结的 Java 工作流阶段
     * @param execution Test Runner 返回的终态
     */
    public synchronized void record(
            String projectId,
            String role,
            WorkflowStage stage,
            ProjectTestExecution execution
    ) {
        String checkedProjectId = WorkspacePolicy.validateProjectId(projectId);
        String checkedRole = Objects.requireNonNull(role, "role 不能为空")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!WorkspacePolicy.ROLES.contains(checkedRole)) {
            throw new IllegalArgumentException("未知团队角色: " + role);
        }
        WorkflowStage checkedStage = Objects.requireNonNull(stage, "stage 不能为空");
        ProjectTestExecution checkedExecution = Objects.requireNonNull(execution, "execution 不能为空");
        if (!checkedExecution.terminal()) {
            throw new IllegalArgumentException("只能登记终态测试执行");
        }
        executions.put(checkedExecution.executionId(), new RecordedExecution(
                checkedProjectId,
                checkedRole,
                checkedStage,
                checkedExecution,
                Instant.now()
        ));
        trimOldestEntries();
    }

    /**
     * 按 execution ID 查找执行，并同时约束项目、角色和执行启动阶段。
     *
     * @param projectId 当前项目
     * @param role 当前任务 Owner
     * @param stage 待匹配的测试启动阶段
     * @param executionId 待核验执行标识
     * @return 完全匹配的真实执行快照
     */
    public synchronized Optional<RecordedExecution> find(
            String projectId,
            String role,
            WorkflowStage stage,
            String executionId
    ) {
        RecordedExecution recorded = executions.get(executionId);
        if (recorded == null
                || !recorded.projectId().equals(projectId)
                || !recorded.role().equals(role)
                || recorded.stage() != stage) {
            return Optional.empty();
        }
        return Optional.of(recorded);
    }

    /** 删除最早写入的超额证据，保留最近的有限执行窗口。 */
    private void trimOldestEntries() {
        while (executions.size() > MAX_ENTRIES) {
            String oldestExecutionId = executions.keySet().iterator().next();
            executions.remove(oldestExecutionId);
        }
    }

    /**
     * 表示一条与项目、角色和阶段绑定的不可伪造运行时测试证据。
     *
     * @param projectId 项目标识
     * @param role 执行角色
     * @param stage 测试启动时冻结的阶段
     * @param execution Test Runner 终态
     * @param recordedAt Java 运行时登记时间
     */
    public record RecordedExecution(
            String projectId,
            String role,
            WorkflowStage stage,
            ProjectTestExecution execution,
            Instant recordedAt
    ) {
    }
}
