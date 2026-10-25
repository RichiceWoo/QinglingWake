package cn.org.chris.wake.domain.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 runtime v2 aggregate 的唯一开放执行、outbox 和去重窗口不变量。
 */
class WorkflowRuntimeStateTest {

    /** 固定测试时间起点。 */
    private static final Instant START = Instant.parse("2026-10-25T00:00:00Z");

    /**
     * 同一 stage/owner 只能存在一个非终态执行，旧执行终态后才可创建下一项。
     */
    @Test
    void shouldRejectParallelOpenExecutionForSameStageAndOwner() {
        WorkExecution first = execution("work-1");
        WorkflowRuntimeState state = state().addExecution(first, START.plusSeconds(1));

        assertThatThrownBy(() -> state.addExecution(execution("work-2"), START.plusSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("平行开放");

        WorkExecution terminal = first.cancel("SUPERSEDED", START.plusSeconds(2));
        WorkflowRuntimeState next = state.replaceExecution(terminal, START.plusSeconds(2))
                .addExecution(execution("work-2"), START.plusSeconds(3));
        assertThat(next.executions()).hasSize(2);
    }

    /**
     * pending outbox 必须可查询，成功 ack 后从待重放集合消失。
     */
    @Test
    void shouldKeepPendingOutboxUntilApplied() {
        OutboxAction action = action("action-1");
        WorkflowRuntimeState pending = state().enqueue(action, START.plusSeconds(1));
        OutboxAction applied = action.applied(START.plusSeconds(2));
        WorkflowRuntimeState completed = pending.replaceOutboxAction(applied, START.plusSeconds(2));

        assertThat(pending.pendingOutbox()).containsExactly(action);
        assertThat(completed.pendingOutbox()).isEmpty();
        assertThat(completed.findOutboxAction(action.actionId())).contains(applied);
    }

    /**
     * outbox 与 command 结果引用必须拒绝常见 Key 和鉴权 Header 形态。
     */
    @Test
    void shouldRejectCredentialsBeforeTheyReachPersistence() {
        assertThatThrownBy(() -> OutboxAction.pending(
                new OutboxActionId("action-secret"), OutboxActionType.CRON_WAKE,
                "team:rd", Map.of("authorization", "Bearer hidden-value"), START
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("敏感");
        assertThatThrownBy(() -> new ProcessedWorkflowCommand(
                new WorkflowCommandId("command-secret"), "OK",
                "sk-1234567890abcdef", START
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据");
    }

    /**
     * processed command 去重窗口必须确定性裁剪最旧记录。
     */
    @Test
    void shouldBoundProcessedCommandWindow() {
        WorkflowRuntimeState current = state();
        for (int index = 0; index <= WorkflowRuntimeState.MAX_PROCESSED_COMMANDS; index++) {
            Instant processedAt = START.plusSeconds(index + 1L);
            ProcessedWorkflowCommand command = new ProcessedWorkflowCommand(
                    new WorkflowCommandId("command-" + index), "OK", "work-1", processedAt
            );
            current = current.recordCommand(command, processedAt);
        }

        assertThat(current.processedCommands()).hasSize(WorkflowRuntimeState.MAX_PROCESSED_COMMANDS);
        assertThat(current.findProcessedCommand(new WorkflowCommandId("command-0"))).isEmpty();
        assertThat(current.findProcessedCommand(new WorkflowCommandId("command-512"))).isPresent();
    }

    /**
     * Strict 档位与策略必须成对冻结，同一 execution 也不能并行开放两轮评审。
     */
    @Test
    void shouldRejectInconsistentStrictPolicyAndParallelOpenReviews() {
        assertThatThrownBy(() -> WorkflowRuntimeState.initialize(
                "project-1", WorkflowProfile.STANDARD, ReviewPolicy.STRICT_BLOCKING, START
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同时冻结");

        WorkExecution execution = execution("work-1");
        ReviewRound first = ReviewRound.create(
                "review-1", "project-1", "work-1", WorkflowStage.CODE_IMPLEMENTATION,
                ReviewPolicy.REQUIRED_ONLY, ReviewRequirement.MANDATORY,
                Set.of("qa"), START, START.plusSeconds(60)
        );
        ReviewRound second = ReviewRound.create(
                "review-2", "project-1", "work-1", WorkflowStage.CODE_IMPLEMENTATION,
                ReviewPolicy.REQUIRED_ONLY, ReviewRequirement.MANDATORY,
                Set.of("qa"), START, START.plusSeconds(60)
        );
        WorkflowRuntimeState withFirst = state()
                .addExecution(execution, START.plusSeconds(1))
                .addReviewRound(first, START.plusSeconds(2));

        assertThatThrownBy(() -> withFirst.addReviewRound(second, START.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一个开放");
    }

    /** aggregate 创建后不得用重建快照把既有 WorkExecution 切换到其他 profile。 */
    @Test
    void shouldRejectProfileSwitchWhileProjectIsRunning() {
        WorkflowRuntimeState standard = state().addExecution(
                execution("work-profile"), START.plusSeconds(1)
        );

        assertThatThrownBy(() -> new WorkflowRuntimeState(
                standard.schemaVersion(), standard.projectId(), WorkflowProfile.SIMPLE,
                standard.reviewPolicy(), standard.executions(), standard.reviewRounds(),
                standard.processedCommands(), standard.outboxActions(),
                standard.createdAt(), standard.updatedAt()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");
    }

    /**
     * 创建固定 STANDARD runtime v2 aggregate。
     *
     * @return 空 aggregate
     */
    private static WorkflowRuntimeState state() {
        return WorkflowRuntimeState.initialize(
                "project-1", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY, START
        );
    }

    /**
     * 创建固定代码阶段工作执行。
     *
     * @param executionId 执行标识
     * @return PENDING 工作执行
     */
    private static WorkExecution execution(String executionId) {
        return WorkExecution.create(
                executionId, "project-1", WorkflowStage.CODE_IMPLEMENTATION,
                "rd", WorkflowProfile.STANDARD, START
        );
    }

    /**
     * 创建固定 mailbox outbox 动作。
     *
     * @param actionId 动作标识
     * @return PENDING outbox 动作
     */
    private static OutboxAction action(String actionId) {
        return OutboxAction.pending(
                new OutboxActionId(actionId), OutboxActionType.MAILBOX_MESSAGE,
                "rd", Map.of("message_type", "task_assign", "subject", "代码实现"), START
        );
    }
}
