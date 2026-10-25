package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.workflow.WorkflowCommandId;

/**
 * 请求将已领取工作执行绑定到唯一 dispatch 和角色 session。
 *
 * @param commandId 稳定幂等命令标识
 * @param projectId 项目标识
 * @param executionId 工作执行标识
 * @param dispatchId 串行 dispatch 标识
 * @param agentSessionId 角色 session 标识
 */
public record StartWorkItemCommand(
        /** 稳定幂等命令标识。 */ WorkflowCommandId commandId,
        /** 项目标识。 */ String projectId,
        /** 工作执行标识。 */ String executionId,
        /** 串行 dispatch 标识。 */ String dispatchId,
        /** 角色 session 标识。 */ String agentSessionId
) {
}
