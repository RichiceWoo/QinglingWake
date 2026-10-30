package cn.org.chris.wake.domain.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 WorkExecution 有限状态、幂等重入和真实进度租约。
 */
class WorkExecutionTest {

    /** 领域测试使用的固定起点，避免依赖系统时钟。 */
    private static final Instant START = Instant.parse("2026-10-25T00:00:00Z");

    /** SIMPLE 不得创建已经被档位定义跳过的 PM、技术设计或独立测试设计执行。 */
    @Test
    void shouldRejectSkippedIndependentDesignStagesForSimpleProfile() {
        for (WorkflowStage stage : List.of(
                WorkflowStage.PRODUCT_DESIGN,
                WorkflowStage.TECH_DESIGN,
                WorkflowStage.QA_TEST_DESIGN
        )) {
            assertThatThrownBy(() -> WorkExecution.create(
                    "work-simple-" + stage.name().toLowerCase(), "project-simple", stage,
                    stage == WorkflowStage.PRODUCT_DESIGN ? "pm" : "rd",
                    WorkflowProfile.SIMPLE, START
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SIMPLE")
                    .hasMessageContaining(stage.name());
        }
    }

    /**
     * 正常执行必须按领取、运行、成功的固定路径收敛。
     */
    @Test
    void shouldCompleteNormalExecutionThroughFiniteStates() {
        WorkExecution pending = execution();
        WorkExecution claimed = pending.claim("msg-00000001", START.plusSeconds(1));
        WorkExecution running = claimed.start("dispatch-1", "session-rd", START.plusSeconds(2));
        WorkExecution succeeded = running.succeed(START.plusSeconds(3));

        assertThat(pending.status()).isEqualTo(WorkExecutionStatus.PENDING);
        assertThat(claimed.status()).isEqualTo(WorkExecutionStatus.CLAIMED);
        assertThat(running.status()).isEqualTo(WorkExecutionStatus.RUNNING);
        assertThat(succeeded.status()).isEqualTo(WorkExecutionStatus.SUCCEEDED);
        assertThat(succeeded.status().terminal()).isTrue();
        assertThat(succeeded.executionId()).isEqualTo(pending.executionId());
    }

    /**
     * 相同领取、启动、关联和终态命令必须返回同一快照，避免重复副作用。
     */
    @Test
    void shouldKeepEquivalentCommandsIdempotent() {
        WorkExecution claimed = execution().claim("msg-00000001", START.plusSeconds(1));
        WorkExecution running = claimed.start("dispatch-1", "session-rd", START.plusSeconds(2));
        WorkExecution linked = running.linkChildTask("task-1", START.plusSeconds(3))
                .linkTestExecution("test-1", START.plusSeconds(4));
        WorkExecution succeeded = linked.succeed(START.plusSeconds(5));

        assertThat(claimed.claim("msg-00000001", START.plusSeconds(99))).isSameAs(claimed);
        assertThat(running.start("dispatch-1", "session-rd", START.plusSeconds(99)))
                .isSameAs(running);
        assertThat(linked.linkChildTask("task-1", START.plusSeconds(99))).isSameAs(linked);
        assertThat(linked.linkTestExecution("test-1", START.plusSeconds(99))).isSameAs(linked);
        assertThat(succeeded.succeed(START.plusSeconds(99))).isSameAs(succeeded);
    }

    /**
     * 恢复与评审修订必须复用 execution ID 并单调增加 attempt。
     */
    @Test
    void shouldReuseExecutionForRecoveryAndReviewRevision() {
        WorkExecution running = runningExecution();
        WorkExecution retry = running.recover("MCP_TRANSPORT", START.plusSeconds(3))
                .retry(START.plusSeconds(4));
        WorkExecution rerun = retry.claim("msg-00000002", START.plusSeconds(5))
                .start("dispatch-2", "session-rd", START.plusSeconds(6));
        WorkExecution revision = rerun.waitForReview("review-1", START.plusSeconds(7))
                .reviseAfterReview("review-1", START.plusSeconds(8));

        assertThat(retry.executionId()).isEqualTo(running.executionId());
        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(revision.executionId()).isEqualTo(running.executionId());
        assertThat(revision.attempt()).isEqualTo(3);
        assertThat(revision.status()).isEqualTo(WorkExecutionStatus.PENDING);
        assertThat(revision.stage()).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(revision.owner()).isEqualTo("rd");
    }

    /**
     * 行政关联和状态切换不得冒充业务进度，只有明确证据可以刷新进度租约。
     */
    @Test
    void shouldRefreshProgressOnlyForExplicitBusinessEvidence() {
        WorkExecution running = runningExecution();
        WorkExecution linked = running.linkChildTask("task-1", START.plusSeconds(20))
                .linkTestExecution("test-1", START.plusSeconds(30));
        WorkExecution progressed = linked.recordProgress(START.plusSeconds(40));

        assertThat(running.lastProgressAt()).isEqualTo(START);
        assertThat(linked.updatedAt()).isEqualTo(START.plusSeconds(30));
        assertThat(linked.lastProgressAt()).isEqualTo(START);
        assertThat(progressed.lastProgressAt()).isEqualTo(START.plusSeconds(40));
        assertThatThrownBy(() -> progressed.recordProgress(START.plusSeconds(39)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能回退");
    }

    /**
     * Test Runner 必须绑定最新 execution、限制单次 attempt 调用次数，并在恢复后重置预算。
     */
    @Test
    void shouldBoundTestInvocationsPerAttemptAndResetBudgetOnRetry() {
        WorkExecution running = runningExecution();
        WorkExecution first = running.beginTestExecution("test-1", 3, START.plusSeconds(3));
        WorkExecution second = first.beginTestExecution("test-2", 3, START.plusSeconds(4));
        WorkExecution third = second.beginTestExecution("test-3", 3, START.plusSeconds(5));

        assertThat(third.testExecutionId()).isEqualTo("test-3");
        assertThat(third.testInvocationCount()).isEqualTo(3);
        assertThat(third.beginTestExecution("test-3", 3, START.plusSeconds(6))).isSameAs(third);
        assertThatThrownBy(() -> third.beginTestExecution("test-4", 3, START.plusSeconds(6)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("调用预算已耗尽");

        WorkExecution retry = third.recover("TEST_RUNNER_COMPLETION_PENDING", START.plusSeconds(6))
                .retry(START.plusSeconds(7));
        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(retry.testExecutionId()).isEmpty();
        assertThat(retry.testInvocationCount()).isZero();
    }

    /**
     * 每一种终态都不得重新领取、运行、恢复或完成为另一终态。
     */
    @Test
    void shouldRejectEveryTransitionOutOfTerminalStates() {
        WorkExecution running = runningExecution();
        List<WorkExecution> terminals = List.of(
                running.succeed(START.plusSeconds(3)),
                running.fail("UNRECOVERABLE", START.plusSeconds(3)),
                running.cancel("SHUTDOWN", START.plusSeconds(3)),
                running.block("RETRY_EXHAUSTED", START.plusSeconds(3))
        );

        for (WorkExecution terminal : terminals) {
            assertThat(terminal.status().terminal()).isTrue();
            assertThatThrownBy(() -> terminal.claim("msg-terminal", START.plusSeconds(4)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> terminal.recover("MCP_TRANSPORT", START.plusSeconds(4)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * 非法跳转、串线评审和不单调时间必须在领域边界直接失败。
     */
    @Test
    void shouldRejectIllegalTransitionsAndCrossRoundReview() {
        WorkExecution pending = execution();
        WorkExecution waiting = runningExecution().waitForReview("review-1", START.plusSeconds(3));

        assertThatThrownBy(() -> pending.succeed(START.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
        assertThatThrownBy(() -> waiting.approveReview("review-old", START.plusSeconds(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于");
        assertThatThrownBy(() -> runningExecution().succeed(START.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("updatedAt");
    }

    /**
     * 状态枚举必须与冻结的完整转换表逐项一致，避免后续静默增加回退路径。
     */
    @Test
    void shouldMatchTheCompleteTransitionTable() {
        Map<WorkExecutionStatus, Set<WorkExecutionStatus>> expected = Map.of(
                WorkExecutionStatus.PENDING,
                EnumSet.of(WorkExecutionStatus.CLAIMED, WorkExecutionStatus.CANCELLED,
                        WorkExecutionStatus.BLOCKED),
                WorkExecutionStatus.CLAIMED,
                EnumSet.of(WorkExecutionStatus.RUNNING, WorkExecutionStatus.RECOVERING,
                        WorkExecutionStatus.FAILED, WorkExecutionStatus.CANCELLED,
                        WorkExecutionStatus.BLOCKED),
                WorkExecutionStatus.RUNNING,
                EnumSet.of(WorkExecutionStatus.WAITING_REVIEW, WorkExecutionStatus.RECOVERING,
                        WorkExecutionStatus.SUCCEEDED, WorkExecutionStatus.FAILED,
                        WorkExecutionStatus.CANCELLED, WorkExecutionStatus.BLOCKED),
                WorkExecutionStatus.WAITING_REVIEW,
                EnumSet.of(WorkExecutionStatus.PENDING, WorkExecutionStatus.SUCCEEDED,
                        WorkExecutionStatus.FAILED, WorkExecutionStatus.CANCELLED,
                        WorkExecutionStatus.BLOCKED),
                WorkExecutionStatus.RECOVERING,
                EnumSet.of(WorkExecutionStatus.PENDING, WorkExecutionStatus.FAILED,
                        WorkExecutionStatus.CANCELLED, WorkExecutionStatus.BLOCKED),
                WorkExecutionStatus.SUCCEEDED, EnumSet.noneOf(WorkExecutionStatus.class),
                WorkExecutionStatus.FAILED, EnumSet.noneOf(WorkExecutionStatus.class),
                WorkExecutionStatus.CANCELLED, EnumSet.noneOf(WorkExecutionStatus.class),
                WorkExecutionStatus.BLOCKED, EnumSet.noneOf(WorkExecutionStatus.class)
        );

        for (WorkExecutionStatus source : WorkExecutionStatus.values()) {
            for (WorkExecutionStatus target : WorkExecutionStatus.values()) {
                assertThat(source.canTransitionTo(target))
                        .as("%s -> %s", source, target)
                        .isEqualTo(expected.get(source).contains(target));
            }
        }
    }

    /**
     * 创建一条固定的待领取代码实现执行。
     *
     * @return PENDING 工作执行
     */
    private static WorkExecution execution() {
        return WorkExecution.create(
                "work-1", "project-1", WorkflowStage.CODE_IMPLEMENTATION,
                "rd", WorkflowProfile.STANDARD, START
        );
    }

    /**
     * 创建一条固定的运行中代码实现执行。
     *
     * @return RUNNING 工作执行
     */
    private static WorkExecution runningExecution() {
        return execution()
                .claim("msg-00000001", START.plusSeconds(1))
                .start("dispatch-1", "session-rd", START.plusSeconds(2));
    }
}
