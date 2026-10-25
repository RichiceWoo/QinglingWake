package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.workflow.WorkflowCommandId;

/**
 * 请求将唯一 PENDING 工作执行绑定到 mailbox assignment。
 *
 * @param commandId 稳定幂等命令标识
 * @param projectId 项目标识
 * @param executionId 工作执行标识
 * @param assignmentId mailbox assignment 标识
 */
public record ClaimWorkItemCommand(
        /** 稳定幂等命令标识。 */ WorkflowCommandId commandId,
        /** 项目标识。 */ String projectId,
        /** 工作执行标识。 */ String executionId,
        /** mailbox assignment 标识。 */ String assignmentId
) {
}
