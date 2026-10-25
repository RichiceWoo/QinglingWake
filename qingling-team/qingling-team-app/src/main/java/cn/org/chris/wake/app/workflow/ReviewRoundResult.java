package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.workflow.ReviewRoundStatus;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;

/**
 * 返回评审提交后 ReviewRound 与所属 WorkExecution 的权威状态。
 *
 * @param reviewRoundId 评审轮次标识
 * @param reviewStatus 评审状态
 * @param executionId 所属工作执行标识
 * @param executionStatus 工作执行状态
 * @param nextExecutionId 批准后自动创建的下一执行标识
 */
public record ReviewRoundResult(
        /** 评审轮次标识。 */ String reviewRoundId,
        /** 评审权威状态。 */ ReviewRoundStatus reviewStatus,
        /** 所属工作执行标识。 */ String executionId,
        /** 工作执行权威状态。 */ WorkExecutionStatus executionStatus,
        /** 批准后自动创建的下一执行标识，可为空。 */ String nextExecutionId
) {
}
