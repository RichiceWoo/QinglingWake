package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workflow.OutboxAction;
import cn.org.chris.wake.domain.workflow.OutboxActionId;
import cn.org.chris.wake.domain.workflow.OutboxActionStatus;
import cn.org.chris.wake.domain.workflow.OutboxActionType;
import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 outbox 投影在崩溃窗口、失败预算和 legacy 项目缺失场景下确定性收敛。
 */
class WorkflowOutboxProjectorTest {

    /** 固定投影时间。 */
    private static final Instant NOW = Instant.parse("2026-10-25T01:00:00Z");

    /**
     * 验证副作用已发生但确认前失败时，重放仍只产生一个可见副作用。
     */
    @Test
    void shouldReplayCrashWindowWithoutDuplicatingVisibleSideEffect() {
        InMemoryRuntimeRepository runtime = new InMemoryRuntimeRepository(stateWithActions(1));
        WorkspaceRepository workspace = projects("project-1");
        Set<OutboxActionId> visibleActions = new HashSet<>();
        AtomicBoolean failAfterFirstSideEffect = new AtomicBoolean(true);
        WorkflowOutboxProjector projector = new WorkflowOutboxProjector(
                runtime,
                workspace,
                action -> {
                    visibleActions.add(action.actionId());
                    if (failAfterFirstSideEffect.compareAndSet(true, false)) {
                        throw new IllegalStateException("crash-after-side-effect");
                    }
                },
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
                3
        );

        int first = projector.projectAllPending();
        int second = projector.projectAllPending();

        OutboxAction stored = runtime.state().outboxActions().get(0);
        assertThat(first).isZero();
        assertThat(second).isEqualTo(1);
        assertThat(visibleActions).hasSize(1);
        assertThat(stored.status()).isEqualTo(OutboxActionStatus.APPLIED);
        assertThat(stored.attempt()).isEqualTo(2);
        assertThat(stored.lastFailureType()).isEqualTo("IllegalStateException");
    }

    /**
     * 验证前序动作失败时保持项目内顺序，并在预算耗尽后停止自动重放。
     */
    @Test
    void shouldStopProjectOrderAndRespectFiniteFailureBudget() {
        InMemoryRuntimeRepository runtime = new InMemoryRuntimeRepository(stateWithActions(2));
        Set<OutboxActionId> attempted = new HashSet<>();
        WorkflowOutboxProjector projector = new WorkflowOutboxProjector(
                runtime,
                projects("project-1"),
                action -> {
                    attempted.add(action.actionId());
                    throw new IllegalArgumentException("projection-failed");
                },
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC),
                2
        );

        projector.projectAllPending();
        projector.projectAllPending();
        projector.projectAllPending();

        assertThat(attempted).containsExactly(new OutboxActionId("action-1"));
        assertThat(runtime.state().outboxActions().get(0).attempt()).isEqualTo(2);
        assertThat(runtime.state().outboxActions().get(1).attempt()).isZero();
        assertThat(runtime.state().pendingOutbox()).hasSize(2);
    }

    /**
     * 验证旧项目没有 runtime v2 aggregate 时扫描保持只读且无失败。
     */
    @Test
    void shouldIgnoreLegacyProjectWithoutRuntimeV2() {
        InMemoryRuntimeRepository runtime = new InMemoryRuntimeRepository(null);
        WorkflowOutboxProjector projector = new WorkflowOutboxProjector(
                runtime,
                projects("legacy-project"),
                action -> {
                    throw new AssertionError("legacy 项目不应产生投影");
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                1
        );

        assertThat(projector.projectAllPending()).isZero();
        assertThat(runtime.state()).isNull();
    }

    /**
     * 创建包含指定数量 pending 事件的 runtime aggregate。
     *
     * @param count 动作数量
     * @return runtime aggregate
     */
    private static WorkflowRuntimeState stateWithActions(int count) {
        WorkflowRuntimeState state = WorkflowRuntimeState.initialize(
                "project-1", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY, NOW
        );
        for (int index = 1; index <= count; index++) {
            state = state.enqueue(OutboxAction.pending(
                    new OutboxActionId("action-" + index),
                    OutboxActionType.WORKFLOW_EVENT,
                    "project-1",
                    java.util.Map.of("action", "workflow_transitioned"),
                    NOW
            ), NOW);
        }
        return state;
    }

    /**
     * 创建只返回固定项目清单的工作区桩。
     *
     * @param projectIds 项目标识
     * @return 工作区端口
     */
    private static WorkspaceRepository projects(String... projectIds) {
        WorkspaceRepository workspace = mock(WorkspaceRepository.class);
        when(workspace.listProjectIds()).thenReturn(List.of(projectIds));
        return workspace;
    }

    /**
     * 以同步临界区模拟 runtime v2 原子更新。
     */
    private static final class InMemoryRuntimeRepository implements WorkflowRuntimeRepository {

        /** 当前可为空的 aggregate。 */
        private WorkflowRuntimeState state;

        /**
         * 创建内存 runtime 仓储。
         *
         * @param initial 初始 aggregate，可为空
         */
        private InMemoryRuntimeRepository(WorkflowRuntimeState initial) {
            this.state = initial;
        }

        /**
         * 返回当前 aggregate 供断言。
         *
         * @return 当前 aggregate，可为空
         */
        private synchronized WorkflowRuntimeState state() {
            return state;
        }

        /**
         * 查询匹配项目的 aggregate。
         *
         * @param projectId 项目标识
         * @return 可选 aggregate
         */
        @Override
        public synchronized Optional<WorkflowRuntimeState> find(String projectId) {
            return state != null && state.projectId().equals(projectId)
                    ? Optional.of(state)
                    : Optional.empty();
        }

        /**
         * 显式创建当前尚不存在的 aggregate。
         *
         * @param initial 初始 aggregate
         * @return 已保存 aggregate
         */
        @Override
        public synchronized WorkflowRuntimeState create(WorkflowRuntimeState initial) {
            if (state != null) {
                throw new IllegalStateException("aggregate 已存在");
            }
            state = initial;
            return state;
        }

        /**
         * 本测试不执行带 command ID 的业务命令。
         *
         * @param projectId 项目标识
         * @param commandId 命令标识
         * @param command 状态变换
         * @return 不会正常返回
         */
        @Override
        public WorkflowCommandReceipt execute(
                String projectId,
                WorkflowCommandId commandId,
                WorkflowCommand command
        ) {
            throw new UnsupportedOperationException("测试不使用 command");
        }

        /**
         * 在同步临界区提交内部投影状态更新。
         *
         * @param projectId 项目标识
         * @param update 状态变换
         * @return 新 aggregate
         */
        @Override
        public synchronized WorkflowRuntimeState update(
                String projectId,
                WorkflowRuntimeUpdate update
        ) {
            if (state == null || !state.projectId().equals(projectId)) {
                throw new IllegalArgumentException("aggregate 不存在");
            }
            state = update.apply(state);
            return state;
        }
    }
}
