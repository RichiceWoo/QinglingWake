package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.gateway.RuntimeIncidentGateway;
import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.model.AgentExecutionException;
import cn.org.chris.wake.domain.workflow.OutboxActionStatus;
import cn.org.chris.wake.domain.workflow.OutboxActionType;
import cn.org.chris.wake.domain.workflow.ProcessedWorkflowCommand;
import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 coordinated incident 保持 stage/owner、只恢复一次并由同一 outbox 链投影。
 */
class CoordinatedRuntimeIncidentGatewayTest {

    /** 固定 incident 时间。 */
    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");

    /** 验证 Owner 提前退出首次唤醒原角色续跑，恢复回合再次提前退出后明确 BLOCKED。 */
    @Test
    void shouldRecoverOnceThenBlockSameWorkExecution() {
        InMemoryRuntimeRepository runtime = new InMemoryRuntimeRepository(runningState());
        WorkspaceRepository workspace = mock(WorkspaceRepository.class);
        when(workspace.listProjectIds()).thenReturn(List.of("project-1"));
        List<String> visibleActions = new ArrayList<>();
        WorkflowOutboxProjector projector = new WorkflowOutboxProjector(
                runtime, workspace,
                action -> visibleActions.add(action.actionId().value()),
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), 3
        );
        CoordinatedRuntimeIncidentGateway gateway = new CoordinatedRuntimeIncidentGateway(
                runtime, workspace, projector, RuntimeIncidentGateway.noop(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        gateway.reportAgentExecutionFailure(
                "project-1", "rd", "dispatch-1",
                AgentExecutionException.FailureType.OWNER_EXITED_WITH_RUNNING_EXECUTION
        );
        WorkExecution recovering = runtime.state().findExecution("work-1").orElseThrow();
        runtime.update("project-1", current -> {
            Instant retryAt = NOW.plusSeconds(2);
            WorkExecution continued = current.findExecution("work-1").orElseThrow()
                    .retry(retryAt)
                    .claim("assignment-2", retryAt)
                    .start("dispatch-2", "session-1", retryAt);
            return current.replaceExecution(continued, retryAt);
        });
        gateway.reportAgentExecutionFailure(
                "project-1", "rd", "dispatch-2",
                AgentExecutionException.FailureType.OWNER_EXITED_WITH_RUNNING_EXECUTION
        );
        gateway.reportAgentExecutionFailure(
                "project-1", "rd", "dispatch-2",
                AgentExecutionException.FailureType.OWNER_EXITED_WITH_RUNNING_EXECUTION
        );
        WorkExecution blocked = runtime.state().findExecution("work-1").orElseThrow();

        assertThat(recovering.status()).isEqualTo(WorkExecutionStatus.RECOVERING);
        assertThat(recovering.stage()).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(recovering.owner()).isEqualTo("rd");
        assertThat(blocked.status()).isEqualTo(WorkExecutionStatus.BLOCKED);
        assertThat(blocked.attempt()).isEqualTo(2);
        assertThat(blocked.failureType()).isEqualTo("OWNER_EXITED_WITH_RUNNING_EXECUTION");
        assertThat(blocked.executionId()).isEqualTo(recovering.executionId());
        assertThat(blocked.stage()).isEqualTo(recovering.stage());
        assertThat(runtime.state().executions()).hasSize(1);
        assertThat(runtime.state().processedCommands()).hasSize(2);
        assertThat(runtime.state().outboxActions()).hasSize(4)
                .allMatch(action -> action.status() == OutboxActionStatus.APPLIED);
        assertThat(runtime.state().outboxActions()).anySatisfy(action -> {
            assertThat(action.type()).isEqualTo(OutboxActionType.MAILBOX_MESSAGE);
            assertThat(action.target()).isEqualTo("rd");
            assertThat(action.attributes()).containsEntry(
                    "failure_type", "OWNER_EXITED_WITH_RUNNING_EXECUTION"
            );
            assertThat(action.attributes()).containsEntry("protocol", "agent_execution_failed");
            assertThat(action.attributes()).containsEntry(
                    "required_action", "run_project_tests_then_task_done"
            );
        });
        assertThat(runtime.state().outboxActions()).anySatisfy(action -> {
            assertThat(action.type()).isEqualTo(OutboxActionType.CRON_WAKE);
            assertThat(action.target()).isEqualTo("team:rd");
        });
        assertThat(visibleActions).hasSize(4);
        assertThat(gateway.isRuntimeBlocked("project-1")).isTrue();
    }

    /** 测试前退出只恢复一次；取得真实测试进度后可再续跑一轮，但第三轮仍必须 BLOCKED。 */
    @Test
    void shouldGrantOneAdditionalContinuationOnlyAfterVerifiedTestProgress() {
        InMemoryRuntimeRepository runtime = new InMemoryRuntimeRepository(runningState());
        WorkspaceRepository workspace = mock(WorkspaceRepository.class);
        when(workspace.listProjectIds()).thenReturn(List.of("project-1"));
        WorkflowOutboxProjector projector = new WorkflowOutboxProjector(
                runtime, workspace, action -> { },
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), 3
        );
        CoordinatedRuntimeIncidentGateway gateway = new CoordinatedRuntimeIncidentGateway(
                runtime, workspace, projector, RuntimeIncidentGateway.noop(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        gateway.reportAgentExecutionFailure(
                "project-1", "rd", "dispatch-pretest",
                AgentExecutionException.FailureType.TEST_RUNNER_NOT_INVOKED
        );
        continueExecution(runtime, 2, "assignment-2", "dispatch-test", NOW.plusSeconds(2));
        gateway.reportAgentExecutionFailure(
                "project-1", "rd", "dispatch-test",
                AgentExecutionException.FailureType.TEST_RUNNER_COMPLETION_PENDING
        );
        WorkExecution secondRecovery = runtime.state().findExecution("work-1").orElseThrow();
        continueExecution(runtime, 3, "assignment-3", "dispatch-final", NOW.plusSeconds(3));
        gateway.reportAgentExecutionFailure(
                "project-1", "rd", "dispatch-final",
                AgentExecutionException.FailureType.TEST_RUNNER_COMPLETION_PENDING
        );
        WorkExecution blocked = runtime.state().findExecution("work-1").orElseThrow();

        assertThat(secondRecovery.status()).isEqualTo(WorkExecutionStatus.RECOVERING);
        assertThat(secondRecovery.attempt()).isEqualTo(2);
        assertThat(blocked.status()).isEqualTo(WorkExecutionStatus.BLOCKED);
        assertThat(blocked.attempt()).isEqualTo(3);
        assertThat(runtime.state().outboxActions()).anySatisfy(action ->
                assertThat(action.attributes()).containsEntry(
                        "required_action", "run_project_tests_now_then_fix_or_task_done"
                )
        );
        assertThat(runtime.state().outboxActions()).anySatisfy(action ->
                assertThat(action.attributes()).containsEntry(
                        "required_action", "fix_from_test_result_then_rerun_and_task_done"
                )
        );
    }

    /** 测试优先恢复回合若仍未调用 Test Runner，第二次同类失败必须直接 BLOCKED。 */
    @Test
    void shouldBlockSecondPretestExitWithoutGrantingThirdTurn() {
        InMemoryRuntimeRepository runtime = new InMemoryRuntimeRepository(runningState());
        WorkspaceRepository workspace = mock(WorkspaceRepository.class);
        when(workspace.listProjectIds()).thenReturn(List.of("project-1"));
        CoordinatedRuntimeIncidentGateway gateway = new CoordinatedRuntimeIncidentGateway(
                runtime,
                workspace,
                new WorkflowOutboxProjector(
                        runtime, workspace, action -> { },
                        Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), 3
                ),
                RuntimeIncidentGateway.noop(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        gateway.reportAgentExecutionFailure(
                "project-1", "rd", "dispatch-pretest-1",
                AgentExecutionException.FailureType.TEST_RUNNER_NOT_INVOKED
        );
        continueExecution(runtime, 2, "assignment-2", "dispatch-pretest-2", NOW.plusSeconds(2));
        gateway.reportAgentExecutionFailure(
                "project-1", "rd", "dispatch-pretest-2",
                AgentExecutionException.FailureType.TEST_RUNNER_NOT_INVOKED
        );

        WorkExecution blocked = runtime.state().findExecution("work-1").orElseThrow();
        assertThat(blocked.status()).isEqualTo(WorkExecutionStatus.BLOCKED);
        assertThat(blocked.attempt()).isEqualTo(2);
        assertThat(blocked.failureType()).isEqualTo("TEST_RUNNER_NOT_INVOKED");
    }

    /**
     * 将恢复中的 execution 重新领取并启动，断言 attempt 与预期一致。
     *
     * @param runtime 内存 runtime 仓储
     * @param expectedAttempt 续跑后的预期尝试序号
     * @param assignmentId 新 assignment 标识
     * @param dispatchId 新 dispatch 标识
     * @param retryAt 续跑时间
     */
    private static void continueExecution(
            InMemoryRuntimeRepository runtime,
            int expectedAttempt,
            String assignmentId,
            String dispatchId,
            Instant retryAt
    ) {
        runtime.update("project-1", current -> {
            WorkExecution continued = current.findExecution("work-1").orElseThrow()
                    .retry(retryAt)
                    .claim(assignmentId, retryAt)
                    .start(dispatchId, "session-1", retryAt);
            assertThat(continued.attempt()).isEqualTo(expectedAttempt);
            return current.replaceExecution(continued, retryAt);
        });
    }

    /**
     * 创建 CODE_IMPLEMENTATION 阶段的唯一运行中 RD 执行。
     *
     * @return runtime aggregate
     */
    private static WorkflowRuntimeState runningState() {
        WorkflowRuntimeState state = WorkflowRuntimeState.initialize(
                "project-1", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY, NOW.minusSeconds(5)
        );
        WorkExecution execution = WorkExecution.create(
                        "work-1", "project-1", WorkflowStage.CODE_IMPLEMENTATION,
                        "rd", WorkflowProfile.STANDARD, NOW.minusSeconds(5)
                )
                .claim("assignment-1", NOW.minusSeconds(4))
                .start("dispatch-1", "session-1", NOW.minusSeconds(3));
        return state.addExecution(execution, NOW.minusSeconds(3));
    }

    /**
     * 提供同步 command 去重与内部更新语义的内存 runtime 仓储。
     */
    private static final class InMemoryRuntimeRepository implements WorkflowRuntimeRepository {

        /** 当前唯一 aggregate。 */
        private WorkflowRuntimeState state;

        /**
         * 创建内存 runtime 仓储。
         *
         * @param initial 初始 aggregate
         */
        private InMemoryRuntimeRepository(WorkflowRuntimeState initial) {
            this.state = initial;
        }

        /**
         * 返回当前 aggregate 供断言。
         *
         * @return 当前 aggregate
         */
        private synchronized WorkflowRuntimeState state() {
            return state;
        }

        /**
         * 查询匹配项目 aggregate。
         *
         * @param projectId 项目标识
         * @return 可选 aggregate
         */
        @Override
        public synchronized Optional<WorkflowRuntimeState> find(String projectId) {
            return state.projectId().equals(projectId) ? Optional.of(state) : Optional.empty();
        }

        /**
         * 测试已提供初始 aggregate，禁止再次创建。
         *
         * @param initial 待创建 aggregate
         * @return 不会正常返回
         */
        @Override
        public WorkflowRuntimeState create(WorkflowRuntimeState initial) {
            throw new IllegalStateException("aggregate 已存在");
        }

        /**
         * 在同步临界区执行 command，并对重复 command 返回首次处理结果。
         *
         * @param projectId 项目标识
         * @param commandId command 标识
         * @param command 首次状态变换
         * @return command 回执
         */
        @Override
        public synchronized WorkflowCommandReceipt execute(
                String projectId,
                WorkflowCommandId commandId,
                WorkflowCommand command
        ) {
            Optional<ProcessedWorkflowCommand> existing = state.findProcessedCommand(commandId);
            if (existing.isPresent()) {
                return new WorkflowCommandReceipt(existing.orElseThrow(), state, false);
            }
            WorkflowCommandMutation mutation = command.apply(state);
            ProcessedWorkflowCommand processed = new ProcessedWorkflowCommand(
                    commandId, mutation.outcomeCode(), mutation.resultReference(), mutation.state().updatedAt()
            );
            state = mutation.state().recordCommand(processed, mutation.state().updatedAt());
            return new WorkflowCommandReceipt(processed, state, true);
        }

        /**
         * 在同步临界区提交内部 outbox 状态更新。
         *
         * @param projectId 项目标识
         * @param update 状态变换
         * @return 更新后的 aggregate
         */
        @Override
        public synchronized WorkflowRuntimeState update(
                String projectId,
                WorkflowRuntimeUpdate update
        ) {
            state = update.apply(state);
            return state;
        }
    }
}
