package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.workflow.ReviewVerdict;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;

/**
 * 以 review round ID 提交一个 Reviewer 的有限结论。
 *
 * @param commandId 稳定幂等命令标识
 * @param projectId 项目标识
 * @param reviewRoundId 评审轮次标识
 * @param reviewer Reviewer 角色
 * @param verdict 有限评审结论
 */
public record SubmitReviewCommand(
        /** 稳定幂等命令标识。 */ WorkflowCommandId commandId,
        /** 项目标识。 */ String projectId,
        /** 评审轮次标识。 */ String reviewRoundId,
        /** Reviewer 角色。 */ String reviewer,
        /** 有限评审结论。 */ ReviewVerdict verdict
) {
}
