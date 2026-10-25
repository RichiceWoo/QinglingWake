package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.workflow.ProjectTestEvidence;
import cn.org.chris.wake.domain.workflow.WorkflowStage;

import java.util.Optional;

/**
 * 定义按 execution ID、项目、角色和阶段查询真实测试证据的端口。
 */
public interface ProjectTestEvidenceRepository {

    /**
     * 查询完全匹配当前工作执行上下文的真实 Test Runner 证据。
     *
     * @param projectId 项目标识
     * @param role 当前 Owner
     * @param stage 当前业务阶段
     * @param executionId Test Runner execution 标识
     * @return 完全匹配时返回证据
     */
    Optional<ProjectTestEvidence> find(
            String projectId,
            String role,
            WorkflowStage stage,
            String executionId
    );
}
