package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 shutdown 不会把 runtime v2 非终态工作遗留为假 RUNNING。
 */
class WorkflowShutdownCoordinatorTest {

    /**
     * 验证运行中执行在 shutdown 时持久化为 CANCELLED，既有成功终态保持不变。
     */
    @Test
    void shouldCancelEveryNonTerminalExecutionAndPreserveTerminalOnShutdown() {
        Instant now = Instant.parse("2026-10-25T04:00:00Z");
        WorkflowRuntimeState initial = WorkflowRuntimeState.initialize(
                "project-1", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY, now.minusSeconds(10)
        );
        WorkExecution running = WorkExecution.create(
                        "work-running", "project-1", WorkflowStage.CODE_IMPLEMENTATION,
                        "rd", WorkflowProfile.STANDARD, now.minusSeconds(10)
                )
                .claim("assignment-1", now.minusSeconds(9))
                .start("dispatch-1", "session-1", now.minusSeconds(8));
        WorkExecution succeeded = WorkExecution.create(
                        "work-succeeded", "project-1", WorkflowStage.PRODUCT_DESIGN,
                        "pm", WorkflowProfile.STANDARD, now.minusSeconds(10)
                )
                .claim("assignment-2", now.minusSeconds(9))
                .start("dispatch-2", "session-2", now.minusSeconds(8))
                .succeed(now.minusSeconds(7));
        AtomicReference<WorkflowRuntimeState> state = new AtomicReference<>(
                initial.addExecution(running, now.minusSeconds(8))
                        .addExecution(succeeded, now.minusSeconds(7))
        );
        WorkflowRuntimeRepository runtime = mock(WorkflowRuntimeRepository.class);
        WorkspaceRepository workspace = mock(WorkspaceRepository.class);
        when(workspace.listProjectIds()).thenReturn(List.of("project-1", "legacy-project"));
        when(runtime.find("project-1")).thenAnswer(ignored -> Optional.of(state.get()));
        when(runtime.find("legacy-project")).thenReturn(Optional.empty());
        when(runtime.update(eq("project-1"), any())).thenAnswer(invocation -> {
            WorkflowRuntimeRepository.WorkflowRuntimeUpdate update = invocation.getArgument(1);
            WorkflowRuntimeState changed = update.apply(state.get());
            state.set(changed);
            return changed;
        });
        WorkflowShutdownCoordinator coordinator = new WorkflowShutdownCoordinator(
                runtime, workspace, Clock.fixed(now, ZoneOffset.UTC)
        );

        int cancelled = coordinator.cancelNonTerminal();

        assertThat(cancelled).isEqualTo(1);
        assertThat(state.get().findExecution("work-running").orElseThrow().status())
                .isEqualTo(WorkExecutionStatus.CANCELLED);
        assertThat(state.get().findExecution("work-running").orElseThrow().failureType())
                .isEqualTo(WorkflowShutdownCoordinator.SHUTDOWN_REASON);
        assertThat(state.get().findExecution("work-succeeded").orElseThrow().status())
                .isEqualTo(WorkExecutionStatus.SUCCEEDED);
    }
}
