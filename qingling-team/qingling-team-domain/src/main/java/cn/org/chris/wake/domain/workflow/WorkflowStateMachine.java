package cn.org.chris.wake.domain.workflow;

import java.util.Objects;

/**
 * 以确定性转换表控制 checkpoint、PM、RD、QA 和交付阶段。
 */
public final class WorkflowStateMachine {

    /**
     * 根据当前阶段和有限结论计算唯一合法迁移，模型不能提供目标阶段或收件角色。
     *
     * @param current 当前阶段
     * @param signal 模型或人类产生的有限结论
     * @return 固定的阶段迁移与任务分派
     */
    public WorkflowTransition transition(WorkflowStage current, WorkflowSignal signal) {
        Objects.requireNonNull(current, "current 不能为空");
        Objects.requireNonNull(signal, "signal 不能为空");
        if (signal == WorkflowSignal.STAGE_REVISION_REQUIRED) {
            return revision(current);
        }
        return switch (current) {
            case REQUIREMENTS_CHECKPOINT -> requirementsTransition(signal);
            case PRODUCT_DESIGN -> requireSignal(
                    current, signal, WorkflowSignal.STAGE_ACCEPTED,
                    WorkflowStage.TECH_DESIGN, "rd", "技术方案设计 (第 1 轮)"
            );
            case TECH_DESIGN -> requireSignal(
                    current, signal, WorkflowSignal.STAGE_ACCEPTED,
                    WorkflowStage.CODE_IMPLEMENTATION, "rd", "代码实现 (第 1 轮)"
            );
            case CODE_IMPLEMENTATION -> requireSignal(
                    current, signal, WorkflowSignal.STAGE_ACCEPTED,
                    WorkflowStage.QA_TEST_DESIGN, "qa", "测试设计 (第 1 轮)"
            );
            case QA_TEST_DESIGN -> requireSignal(
                    current, signal, WorkflowSignal.STAGE_ACCEPTED,
                    WorkflowStage.QA_TEST_EXECUTION, "qa", "测试执行 (第 1 轮)"
            );
            case QA_TEST_EXECUTION -> qaTransition(signal);
            case DEFECT_FIX -> requireSignal(
                    current, signal, WorkflowSignal.STAGE_ACCEPTED,
                    WorkflowStage.QA_TEST_EXECUTION, "qa", "测试执行 (回归轮)"
            );
            case DELIVERY_CHECKPOINT -> deliveryTransition(signal);
            case COMPLETED, CANCELLED -> throw illegal(current, signal);
        };
    }

    /**
     * 计算需求 checkpoint 的批准或终止分支。
     *
     * @param signal 人类确认结论
     * @return 产品设计或项目终止迁移
     */
    private static WorkflowTransition requirementsTransition(WorkflowSignal signal) {
        if (signal == WorkflowSignal.CHECKPOINT_APPROVED) {
            return new WorkflowTransition(
                    WorkflowStage.REQUIREMENTS_CHECKPOINT, WorkflowStage.PRODUCT_DESIGN,
                    "pm", "产品设计 (第 1 轮)"
            );
        }
        if (signal == WorkflowSignal.CHECKPOINT_REJECTED) {
            return new WorkflowTransition(
                    WorkflowStage.REQUIREMENTS_CHECKPOINT, WorkflowStage.CANCELLED, "", ""
            );
        }
        throw illegal(WorkflowStage.REQUIREMENTS_CHECKPOINT, signal);
    }

    /**
     * 计算 QA 执行后的通过或缺陷分支。
     *
     * @param signal QA 评审结论
     * @return 交付 checkpoint 或 RD 缺陷修复迁移
     */
    private static WorkflowTransition qaTransition(WorkflowSignal signal) {
        if (signal == WorkflowSignal.STAGE_ACCEPTED) {
            return new WorkflowTransition(
                    WorkflowStage.QA_TEST_EXECUTION, WorkflowStage.DELIVERY_CHECKPOINT, "", ""
            );
        }
        if (signal == WorkflowSignal.QA_DEFECT_FOUND) {
            return new WorkflowTransition(
                    WorkflowStage.QA_TEST_EXECUTION, WorkflowStage.DEFECT_FIX,
                    "rd", "缺陷修复 (第 1 轮)"
            );
        }
        throw illegal(WorkflowStage.QA_TEST_EXECUTION, signal);
    }

    /**
     * 计算交付 checkpoint 的批准或反馈修复分支。
     *
     * @param signal 人类交付结论
     * @return 完成或 RD 修复迁移
     */
    private static WorkflowTransition deliveryTransition(WorkflowSignal signal) {
        if (signal == WorkflowSignal.DELIVERY_APPROVED) {
            return new WorkflowTransition(
                    WorkflowStage.DELIVERY_CHECKPOINT, WorkflowStage.COMPLETED, "", ""
            );
        }
        if (signal == WorkflowSignal.DELIVERY_REJECTED) {
            return new WorkflowTransition(
                    WorkflowStage.DELIVERY_CHECKPOINT, WorkflowStage.DEFECT_FIX,
                    "rd", "交付反馈修复"
            );
        }
        throw illegal(WorkflowStage.DELIVERY_CHECKPOINT, signal);
    }

    /**
     * 将修订结论固定回当前阶段 Owner，禁止模型选择其他角色或跳过阶段。
     *
     * @param current 当前阶段
     * @return 同阶段修订任务
     */
    private static WorkflowTransition revision(WorkflowStage current) {
        return switch (current) {
            case PRODUCT_DESIGN -> new WorkflowTransition(current, current, "pm", "产品设计修订");
            case TECH_DESIGN -> new WorkflowTransition(current, current, "rd", "技术方案修订");
            case CODE_IMPLEMENTATION -> new WorkflowTransition(current, current, "rd", "代码实现修订");
            case QA_TEST_DESIGN -> new WorkflowTransition(current, current, "qa", "测试设计修订");
            case QA_TEST_EXECUTION -> new WorkflowTransition(current, current, "qa", "测试执行修订");
            case DEFECT_FIX -> new WorkflowTransition(current, current, "rd", "缺陷修复修订");
            default -> throw illegal(current, WorkflowSignal.STAGE_REVISION_REQUIRED);
        };
    }

    /**
     * 校验单一合法信号并构造固定迁移。
     *
     * @param current 当前阶段
     * @param actual 实际信号
     * @param expected 唯一合法信号
     * @param next 下一阶段
     * @param assignee 下一任务角色
     * @param subject 下一任务主题
     * @return 固定迁移
     */
    private static WorkflowTransition requireSignal(
            WorkflowStage current,
            WorkflowSignal actual,
            WorkflowSignal expected,
            WorkflowStage next,
            String assignee,
            String subject
    ) {
        if (actual != expected) {
            throw illegal(current, actual);
        }
        return new WorkflowTransition(current, next, assignee, subject);
    }

    /**
     * 创建不包含外部秘密的非法迁移异常。
     *
     * @param current 当前阶段
     * @param signal 非法信号
     * @return 可直接返回工具调用方的异常
     */
    private static IllegalStateException illegal(WorkflowStage current, WorkflowSignal signal) {
        return new IllegalStateException("阶段 " + current + " 不接受信号 " + signal);
    }
}
