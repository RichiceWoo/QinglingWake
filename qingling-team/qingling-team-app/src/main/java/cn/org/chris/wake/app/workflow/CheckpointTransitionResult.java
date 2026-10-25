package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.workflow.WorkflowStage;

/**
 * 返回人类需求或交付 checkpoint 的权威迁移结果。
 *
 * @param executionId 已终结的 checkpoint WorkExecution 标识
 * @param fromStage checkpoint 原阶段
 * @param toStage Java 状态机决定的目标阶段
 * @param nextExecutionId 可选下一 WorkExecution 标识
 */
public record CheckpointTransitionResult(
        /** 已终结的 checkpoint WorkExecution 标识。 */ String executionId,
        /** checkpoint 原阶段。 */ WorkflowStage fromStage,
        /** Java 状态机决定的目标阶段。 */ WorkflowStage toStage,
        /** 可选下一 WorkExecution 标识。 */ String nextExecutionId
) {
}
