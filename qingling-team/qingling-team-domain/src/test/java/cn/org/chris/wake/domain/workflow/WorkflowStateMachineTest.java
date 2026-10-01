package cn.org.chris.wake.domain.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证阶段目标、Owner 与任务主题只能由 Java 转换表决定。
 */
class WorkflowStateMachineTest {

    /** 待测确定性状态机。 */
    private final WorkflowStateMachine stateMachine = new WorkflowStateMachine();

    /**
     * 技术方案接受后必须进入 RD 代码实现，不能直接跳到 QA。
     */
    @Test
    void shouldAlwaysEnterCodeImplementationAfterAcceptedTechDesign() {
        WorkflowTransition transition = stateMachine.transition(
                WorkflowStage.TECH_DESIGN, WorkflowSignal.STAGE_ACCEPTED
        );

        assertThat(transition.to()).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(transition.assignee()).isEqualTo("rd");
        assertThat(transition.subject()).isEqualTo("代码实现 (第 1 轮)");
    }

    /**
     * QA 发现缺陷只能进入 RD 修复，修复接受后只能回到 QA 执行。
     */
    @Test
    void shouldCloseQaDefectThroughRdAndReturnToQaExecution() {
        WorkflowTransition defect = stateMachine.transition(
                WorkflowStage.QA_TEST_EXECUTION, WorkflowSignal.QA_DEFECT_FOUND
        );
        WorkflowTransition regression = stateMachine.transition(
                defect.to(), WorkflowSignal.STAGE_ACCEPTED
        );

        assertThat(defect.to()).isEqualTo(WorkflowStage.DEFECT_FIX);
        assertThat(defect.assignee()).isEqualTo("rd");
        assertThat(regression.to()).isEqualTo(WorkflowStage.QA_TEST_EXECUTION);
        assertThat(regression.assignee()).isEqualTo("qa");
    }

    /**
     * 任意不属于当前阶段的信号必须显式失败，不能由模型指定替代目标。
     */
    @Test
    void shouldRejectIllegalStageSignal() {
        assertThatThrownBy(() -> stateMachine.transition(
                WorkflowStage.PRODUCT_DESIGN, WorkflowSignal.DELIVERY_APPROVED
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PRODUCT_DESIGN")
                .hasMessageContaining("DELIVERY_APPROVED");
    }

    /**
     * 需求拒绝必须终止，交付拒绝必须回到 RD 修复后再走 QA 回归。
     */
    @Test
    void shouldDriveHumanCheckpointBranchesInJava() {
        WorkflowTransition cancelled = stateMachine.transition(
                WorkflowStage.REQUIREMENTS_CHECKPOINT, WorkflowSignal.CHECKPOINT_REJECTED
        );
        WorkflowTransition deliveryFix = stateMachine.transition(
                WorkflowStage.DELIVERY_CHECKPOINT, WorkflowSignal.DELIVERY_REJECTED
        );

        assertThat(cancelled.to()).isEqualTo(WorkflowStage.CANCELLED);
        assertThat(cancelled.assignee()).isBlank();
        assertThat(deliveryFix.to()).isEqualTo(WorkflowStage.DEFECT_FIX);
        assertThat(deliveryFix.assignee()).isEqualTo("rd");
    }
}
