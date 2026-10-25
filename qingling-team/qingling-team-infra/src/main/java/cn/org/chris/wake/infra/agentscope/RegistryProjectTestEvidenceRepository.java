package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.gateway.ProjectTestEvidenceRepository;
import cn.org.chris.wake.domain.workflow.ProjectTestEvidence;
import cn.org.chris.wake.domain.workflow.ProjectTestEvidenceStatus;
import cn.org.chris.wake.domain.workflow.WorkflowStage;

import java.util.Objects;
import java.util.Optional;

/**
 * 将现有进程内 Test Runner 注册表适配为应用层只读证据端口。
 */
public final class RegistryProjectTestEvidenceRepository implements ProjectTestEvidenceRepository {

    /** 真实 Test Runner 终态注册表。 */
    private final ProjectTestExecutionRegistry registry;

    /**
     * 创建只读测试证据适配器。
     *
     * @param registry 真实 Test Runner 终态注册表
     */
    public RegistryProjectTestEvidenceRepository(ProjectTestExecutionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
    }

    /**
     * 按项目、Owner、阶段和 execution ID 查询并去除 stdout/stderr。
     *
     * @param projectId 项目标识
     * @param role 当前 Owner
     * @param stage 当前业务阶段
     * @param executionId Test Runner execution 标识
     * @return 完全匹配的安全证据
     */
    @Override
    public Optional<ProjectTestEvidence> find(
            String projectId,
            String role,
            WorkflowStage stage,
            String executionId
    ) {
        return registry.find(projectId, role, stage, executionId)
                .map(recorded -> toEvidence(recorded, projectId, role, stage));
    }

    /**
     * 将 infra 执行结果映射为不含输出正文的领域证据。
     *
     * @param recorded 已绑定上下文的注册记录
     * @param projectId 项目标识
     * @param role 当前 Owner
     * @param stage 当前业务阶段
     * @return 安全测试证据
     */
    private static ProjectTestEvidence toEvidence(
            ProjectTestExecutionRegistry.RecordedExecution recorded,
            String projectId,
            String role,
            WorkflowStage stage
    ) {
        ProjectTestExecution execution = recorded.execution();
        return new ProjectTestEvidence(
                execution.executionId(), projectId, role, stage,
                ProjectTestEvidenceStatus.valueOf(execution.status().name()),
                execution.exitCode(), execution.pytestCollected(), execution.pytestPassed(),
                execution.coveragePercent(), execution.failureType().name(), execution.retryable()
        );
    }
}
