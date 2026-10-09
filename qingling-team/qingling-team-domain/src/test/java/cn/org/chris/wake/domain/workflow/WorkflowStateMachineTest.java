package cn.org.chris.wake.domain.workflow;

import org.junit.jupiter.api.Test;

import java.util.Map;

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

    /**
     * Manager 答复澄清后必须保持当前阶段，并重新分派给该阶段原 Owner。
     */
    @Test
    void shouldResumeOriginalOwnerInCurrentStageAfterClarification() {
        Map<WorkflowStage, String> expectedOwners = Map.of(
                WorkflowStage.PRODUCT_DESIGN, "pm",
                WorkflowStage.TECH_DESIGN, "rd",
                WorkflowStage.CODE_IMPLEMENTATION, "rd",
                WorkflowStage.QA_TEST_DESIGN, "qa",
                WorkflowStage.QA_TEST_EXECUTION, "qa",
                WorkflowStage.DEFECT_FIX, "rd"
        );

        expectedOwners.forEach((stage, owner) -> {
            WorkflowTransition transition = stateMachine.transition(
                    stage, WorkflowSignal.CLARIFICATION_ANSWERED
            );
            assertThat(transition.from()).isEqualTo(stage);
            assertThat(transition.to()).isEqualTo(stage);
            assertThat(transition.assignee()).isEqualTo(owner);
            assertThat(transition.subject()).startsWith("继续");
        });
    }

    /**
     * checkpoint 与终态没有可恢复的阶段 Owner，必须拒绝澄清恢复信号。
     */
    @Test
    void shouldRejectClarificationResumeWithoutActionableOwner() {
        assertThatThrownBy(() -> stateMachine.transition(
                WorkflowStage.REQUIREMENTS_CHECKPOINT, WorkflowSignal.CLARIFICATION_ANSWERED
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stateMachine.transition(
                WorkflowStage.DELIVERY_CHECKPOINT, WorkflowSignal.CLARIFICATION_ANSWERED
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> stateMachine.transition(
                WorkflowStage.COMPLETED, WorkflowSignal.CLARIFICATION_ANSWERED
        )).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 工具故障必须保持业务阶段与原 Owner，恢复耗尽后不得生成下游任务。
     */
    @Test
    void shouldKeepStageOwnerAndBlockAfterToolRetryExhaustion() {
        WorkflowTransition recover = stateMachine.transition(
                WorkflowStage.CODE_IMPLEMENTATION, WorkflowSignal.TOOL_EXECUTION_FAILED
        );
        WorkflowTransition blocked = stateMachine.transition(
                WorkflowStage.CODE_IMPLEMENTATION, WorkflowSignal.TOOL_RETRY_EXHAUSTED
        );

        assertThat(recover.from()).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(recover.to()).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(recover.assignee()).isEqualTo("rd");
        assertThat(blocked.from()).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(blocked.to()).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(blocked.assignee()).isBlank();
    }
}
