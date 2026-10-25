package cn.org.chris.wake.domain.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * 验证 runtime v2 档位、评审策略和幂等标识的稳定协议。
 */
class WorkflowProtocolTest {

    /** 验证工作流档位和协调模式保留规范约定的全部稳定枚举值。 */
    @Test
    void shouldExposeStableWorkflowProtocolValues() {
        assertThat(WorkflowProfile.values()).containsExactly(
                WorkflowProfile.SIMPLE, WorkflowProfile.STANDARD, WorkflowProfile.STRICT
        );
        assertThat(ReviewPolicy.values()).containsExactly(
                ReviewPolicy.REQUIRED_ONLY, ReviewPolicy.ADVISORY_AUDIT, ReviewPolicy.STRICT_BLOCKING
        );
        assertThat(WorkflowCoordinationMode.values()).containsExactly(
                WorkflowCoordinationMode.LEGACY, WorkflowCoordinationMode.COORDINATED
        );
    }

    /** 验证命令和 outbox 标识接受有界的稳定安全字符。 */
    @Test
    void shouldAcceptSafeWorkflowIdentifiers() {
        WorkflowCommandId commandId = new WorkflowCommandId("task_done:todo-mvp:msg-123");
        OutboxActionId actionId = new OutboxActionId("task_done:todo-mvp:msg-123:event:0");

        assertThat(commandId.value()).isEqualTo("task_done:todo-mvp:msg-123");
        assertThat(actionId.value()).isEqualTo("task_done:todo-mvp:msg-123:event:0");
    }

    /** 验证标识构造时裁剪边界空白，避免同一命令产生视觉相同的不同键。 */
    @Test
    void shouldNormalizeBoundaryWhitespace() {
        assertThat(new WorkflowCommandId("  command-1  ").value()).isEqualTo("command-1");
        assertThat(new OutboxActionId("  action-1  ").value()).isEqualTo("action-1");
    }

    /** 验证空值、路径字符和无界文本不能进入工作流去重账本。 */
    @Test
    void shouldRejectUnsafeWorkflowIdentifiers() {
        assertThatNullPointerException().isThrownBy(() -> new WorkflowCommandId(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkflowCommandId("../escape"));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkflowCommandId("x".repeat(161)));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxActionId("mail/action"));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxActionId("x".repeat(193)));
    }
}
