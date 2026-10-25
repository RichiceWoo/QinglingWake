package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.workflow.WorkflowStage;

/**
 * 返回 Java 已提交的稳定完成结果，不暴露模型正文或运行时秘密。
 *
 * @param executionId 原工作执行标识
 * @param outcome 有限协调结果
 * @param workflowStage 命令完成后的权威业务阶段
 * @param nextExecutionId 自动创建的下一工作执行标识
 * @param reviewRoundId 自动创建的评审轮次标识
 * @param detailCode 有界结果详情代码
 */
public record CompleteWorkItemResult(
        /** 原工作执行标识。 */ String executionId,
        /** 有限协调结果。 */ WorkCompletionOutcome outcome,
        /** 命令完成后的权威业务阶段。 */ WorkflowStage workflowStage,
        /** 自动创建的下一工作执行标识，可为空。 */ String nextExecutionId,
        /** 自动创建的评审轮次标识，可为空。 */ String reviewRoundId,
        /** 有界结果详情代码。 */ String detailCode
) {
}
