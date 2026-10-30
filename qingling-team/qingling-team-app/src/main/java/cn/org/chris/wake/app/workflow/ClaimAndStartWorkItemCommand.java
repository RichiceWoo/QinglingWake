package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.workflow.WorkflowCommandId;

/**
 * 请求在同一个 runtime command 中领取 assignment 并绑定唯一 dispatch 与角色会话。
 *
 * @param commandId 稳定幂等命令标识
 * @param projectId 项目标识
 * @param executionId 工作执行标识
 * @param assignmentId mailbox assignment 标识
 * @param dispatchId 串行 dispatch 标识
 * @param agentSessionId 角色 session 标识
 */
public record ClaimAndStartWorkItemCommand(
        /** 稳定幂等命令标识。 */ WorkflowCommandId commandId,
        /** 项目标识。 */ String projectId,
        /** 工作执行标识。 */ String executionId,
        /** mailbox assignment 标识。 */ String assignmentId,
        /** 串行 dispatch 标识。 */ String dispatchId,
        /** 角色 session 标识。 */ String agentSessionId
) {
}
