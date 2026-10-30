package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowStage;

/**
 * 请求在 Test Runner 启动前绑定最新 execution，并原子消耗当前工作尝试的调用预算。
 *
 * @param commandId 稳定幂等命令标识
 * @param projectId 项目标识
 * @param owner 当前阶段 Owner
 * @param stage 测试启动时冻结的业务阶段
 * @param testExecutionId 即将启动的 Test Runner execution 标识
 */
public record BeginTestExecutionCommand(
        /** 稳定幂等命令标识。 */ WorkflowCommandId commandId,
        /** 项目标识。 */ String projectId,
        /** 当前阶段 Owner。 */ String owner,
        /** 测试启动时冻结的业务阶段。 */ WorkflowStage stage,
        /** 即将启动的 Test Runner execution 标识。 */ String testExecutionId
) {
}
