package cn.org.chris.wake.domain.workflow;

/**
 * 描述 Java 状态机决定的阶段迁移及下一项唯一任务。
 *
 * @param from 迁移前阶段
 * @param to 迁移后阶段
 * @param assignee 下一任务角色；无需分派时为空字符串
 * @param subject 下一任务固定主题；无需分派时为空字符串
 */
public record WorkflowTransition(
        WorkflowStage from,
        WorkflowStage to,
        String assignee,
        String subject
) {
}
