package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.gateway.ProjectTestEvidenceRepository;
import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workflow.ProcessedWorkflowCommand;
import cn.org.chris.wake.domain.workflow.ProjectTestEvidence;
import cn.org.chris.wake.domain.workflow.ProjectTestEvidenceStatus;
import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.ReviewRoundStatus;
import cn.org.chris.wake.domain.workflow.ReviewVerdict;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证完成命令只依据 Java 状态机、真实产物和 Test Runner 证据推进。
 */
class WorkCompletionCoordinatorTest {

    /** 测试聚合创建时间。 */
    private static final Instant CREATED_AT = Instant.parse("2026-10-25T00:00:00Z");

    /** 所有命令共享的确定业务时间。 */
    private static final Instant COMMAND_AT = CREATED_AT.plusSeconds(10);

    /** 测试项目标识。 */
    private static final String PROJECT_ID = "project-1";

    /** 初始工作执行标识。 */
    private static final String EXECUTION_ID = "work-current";

    /** 需求 checkpoint 必须按冻结 profile 分别进入 PM 或直接进入 RD，重复结论保持幂等。 */
    @Test
    void shouldResolveRequirementCheckpointFromFrozenProfile() {
        InMemoryWorkflowRuntimeRepository standardRuntime = checkpointRuntime(WorkflowProfile.STANDARD);
        WorkCompletionCoordinator standard = coordinator(
                standardRuntime, noTestEvidence(), readableWorkspace("manager")
        );
        WorkflowCommandId standardCommand = new WorkflowCommandId("checkpoint-standard");

        CheckpointTransitionResult first = standard.resolveCheckpoint(
                standardCommand, PROJECT_ID, cn.org.chris.wake.domain.workflow.WorkflowSignal.CHECKPOINT_APPROVED
        );
        CheckpointTransitionResult repeated = standard.resolveCheckpoint(
                standardCommand, PROJECT_ID, cn.org.chris.wake.domain.workflow.WorkflowSignal.CHECKPOINT_APPROVED
        );

        assertThat(repeated).isEqualTo(first);
        assertThat(first.toStage()).isEqualTo(WorkflowStage.PRODUCT_DESIGN);
        assertThat(standardRuntime.state().findExecution(first.nextExecutionId()).orElseThrow())
                .satisfies(next -> {
                    assertThat(next.owner()).isEqualTo("pm");
                    assertThat(next.profile()).isEqualTo(WorkflowProfile.STANDARD);
                });

        InMemoryWorkflowRuntimeRepository simpleRuntime = checkpointRuntime(WorkflowProfile.SIMPLE);
        CheckpointTransitionResult simple = coordinator(
                simpleRuntime, noTestEvidence(), readableWorkspace("manager")
        ).resolveCheckpoint(
                new WorkflowCommandId("checkpoint-simple"), PROJECT_ID,
                cn.org.chris.wake.domain.workflow.WorkflowSignal.CHECKPOINT_APPROVED
        );

        assertThat(simple.toStage()).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(simpleRuntime.state().findExecution(simple.nextExecutionId()).orElseThrow().owner())
                .isEqualTo("rd");
        assertThat(simpleRuntime.state().executions()).noneMatch(
                execution -> execution.owner().equals("pm")
        );
    }

    /** 新项目初始化必须冻结 profile/policy、创建唯一需求 checkpoint，并拒绝运行中换档。 */
    @Test
    void shouldInitializeProjectOnceAndRejectProfileSwitch() {
        InMemoryWorkflowRuntimeRepository runtime = new InMemoryWorkflowRuntimeRepository();
        AtomicInteger sequence = new AtomicInteger();
        WorkCompletionCoordinator coordinator = new WorkCompletionCoordinator(
                runtime, noTestEvidence(), readableWorkspace("manager"),
                () -> "work-initial-" + sequence.incrementAndGet(),
                () -> "review-unused", Clock.fixed(COMMAND_AT, ZoneOffset.UTC), Duration.ofMinutes(10)
        );

        WorkflowRuntimeState first = coordinator.initializeProject(
                PROJECT_ID, "确定性项目", WorkflowProfile.SIMPLE, ReviewPolicy.REQUIRED_ONLY
        );
        WorkflowRuntimeState repeated = coordinator.initializeProject(
                PROJECT_ID, "确定性项目", WorkflowProfile.SIMPLE, ReviewPolicy.REQUIRED_ONLY
        );

        assertThat(repeated).isEqualTo(first);
        assertThat(sequence).hasValue(1);
        assertThat(first.profile()).isEqualTo(WorkflowProfile.SIMPLE);
        assertThat(first.reviewPolicy()).isEqualTo(ReviewPolicy.REQUIRED_ONLY);
        assertThat(first.executions()).singleElement().satisfies(execution -> {
            assertThat(execution.stage()).isEqualTo(WorkflowStage.REQUIREMENTS_CHECKPOINT);
            assertThat(execution.owner()).isEqualTo("manager");
            assertThat(execution.status()).isEqualTo(WorkExecutionStatus.PENDING);
        });
        assertThat(first.outboxActions()).singleElement().satisfies(action ->
                assertThat(action.attributes())
                        .containsEntry("action", "project_created")
                        .containsEntry("profile", "SIMPLE")
                        .containsEntry("review_policy", "REQUIRED_ONLY")
        );
        assertThatThrownBy(() -> coordinator.initializeProject(
                PROJECT_ID, "确定性项目", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已冻结");
    }

    /**
     * 验证普通完成命令原子创建唯一下一执行，重复 command 不产生第二份事实。
     */
    @Test
    void shouldAdvanceOnceAndReturnStableResultForDuplicateCommand() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.PRODUCT_DESIGN, "pm", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        WorkCompletionCoordinator coordinator = coordinator(runtime, noTestEvidence(), readableWorkspace("pm"));
        CompleteWorkItemCommand command = completion(
                "complete-product", WorkflowStage.PRODUCT_DESIGN, "pm", "", WorkCompletionDecision.ACCEPTED,
                WorkImpactLevel.LOW, 0.95D, 0.95D, false
        );

        CompleteWorkItemResult first = coordinator.completeWorkItem(command);
        int executionCount = runtime.state().executions().size();
        int outboxCount = runtime.state().outboxActions().size();
        CompleteWorkItemResult repeated = coordinator.completeWorkItem(command);

        assertThat(repeated).isEqualTo(first);
        assertThat(first.outcome()).isEqualTo(WorkCompletionOutcome.COMPLETED);
        assertThat(first.workflowStage()).isEqualTo(WorkflowStage.TECH_DESIGN);
        assertThat(runtime.state().executions()).hasSize(executionCount).hasSize(2);
        assertThat(runtime.state().outboxActions()).hasSize(outboxCount).hasSize(3);
        assertThat(runtime.state().processedCommands()).hasSize(1);
        assertThat(runtime.state().findExecution(EXECUTION_ID).orElseThrow().status())
                .isEqualTo(WorkExecutionStatus.SUCCEEDED);
    }

    /**
     * 验证代码阶段只能凭真实 pytest 成功终态进入 QA 测试设计。
     */
    @Test
    void shouldAdvanceCodeOnlyWithSuccessfulTestEvidence() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        ProjectTestEvidence evidence = evidence(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", ProjectTestEvidenceStatus.SUCCEEDED,
                0, 12, 12, 86.5D, "NONE", false
        );
        WorkCompletionCoordinator coordinator = coordinator(
                runtime, repositoryOf(evidence), readableWorkspace("rd")
        );

        CompleteWorkItemResult result = coordinator.completeWorkItem(completion(
                "complete-code", WorkflowStage.CODE_IMPLEMENTATION, "rd", evidence.executionId(),
                WorkCompletionDecision.ACCEPTED, WorkImpactLevel.LOW, 0.90D, 0.90D, false
        ));

        assertThat(result.workflowStage()).isEqualTo(WorkflowStage.QA_TEST_DESIGN);
        assertThat(runtime.state().findExecution(EXECUTION_ID).orElseThrow().testExecutionId())
                .isEqualTo(evidence.executionId());
        assertThat(runtime.state().executions()).anySatisfy(next -> {
            assertThat(next.stage()).isEqualTo(WorkflowStage.QA_TEST_DESIGN);
            assertThat(next.owner()).isEqualTo("qa");
        });
    }

    /** SIMPLE 的 RD 真实测试通过后必须直接创建 QA 测试验收，不得补建独立测试设计回合。 */
    @Test
    void shouldAdvanceSimpleCodeDirectlyToQaExecution() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.CODE_IMPLEMENTATION, "rd",
                WorkflowProfile.SIMPLE, ReviewPolicy.REQUIRED_ONLY
        );
        ProjectTestEvidence evidence = evidence(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", ProjectTestEvidenceStatus.SUCCEEDED,
                0, 8, 8, 81D, "NONE", false
        );

        CompleteWorkItemResult result = coordinator(
                runtime, repositoryOf(evidence), readableWorkspace("rd")
        ).completeWorkItem(completion(
                "complete-simple-code", WorkflowStage.CODE_IMPLEMENTATION, "rd",
                evidence.executionId(), WorkCompletionDecision.ACCEPTED,
                WorkImpactLevel.LOW, 0.9D, 0.9D, false
        ));

        assertThat(result.workflowStage()).isEqualTo(WorkflowStage.QA_TEST_EXECUTION);
        assertThat(runtime.state().executions()).anySatisfy(next -> {
            assertThat(next.stage()).isEqualTo(WorkflowStage.QA_TEST_EXECUTION);
            assertThat(next.owner()).isEqualTo("qa");
            assertThat(next.profile()).isEqualTo(WorkflowProfile.SIMPLE);
        });
        assertThat(runtime.state().executions()).noneMatch(
                next -> next.stage() == WorkflowStage.QA_TEST_DESIGN
        );
    }

    /**
     * 验证 pytest 业务失败保持 RD 当前执行运行，不伪装成基础设施事故或下游任务。
     */
    @Test
    void shouldKeepRdRunningWhenPytestBusinessAssertionsFail() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        ProjectTestEvidence evidence = evidence(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", ProjectTestEvidenceStatus.TEST_FAILED,
                1, 12, 11, 83D, "PYTEST_ASSERTION_FAILED", false
        );
        WorkCompletionCoordinator coordinator = coordinator(
                runtime, repositoryOf(evidence), readableWorkspace("rd")
        );

        CompleteWorkItemResult result = coordinator.completeWorkItem(completion(
                "code-test-failed", WorkflowStage.CODE_IMPLEMENTATION, "rd", evidence.executionId(),
                WorkCompletionDecision.ACCEPTED, WorkImpactLevel.LOW, 0.90D, 0.90D, false
        ));

        WorkExecution execution = runtime.state().findExecution(EXECUTION_ID).orElseThrow();
        assertThat(result.outcome()).isEqualTo(WorkCompletionOutcome.TEST_FAILED);
        assertThat(execution.status()).isEqualTo(WorkExecutionStatus.RUNNING);
        assertThat(execution.testExecutionId()).isEqualTo(evidence.executionId());
        assertThat(runtime.state().executions()).hasSize(1);
    }

    /**
     * 验证 Test Runner 基础设施失败按 retryable 明确分流为 RECOVERING 或 BLOCKED。
     */
    @Test
    void shouldSeparateRetryableAndExhaustedInfrastructureFailures() {
        InMemoryWorkflowRuntimeRepository recoveringRuntime = runtime(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        ProjectTestEvidence retryable = evidence(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", ProjectTestEvidenceStatus.TIMED_OUT,
                null, 0, 0, -1D, "RUNNER_TIMEOUT", true
        );
        CompleteWorkItemResult recovering = coordinator(
                recoveringRuntime, repositoryOf(retryable), readableWorkspace("rd")
        ).completeWorkItem(completion(
                "retryable-infra", WorkflowStage.CODE_IMPLEMENTATION, "rd", retryable.executionId(),
                WorkCompletionDecision.ACCEPTED, WorkImpactLevel.LOW, 0.9D, 0.9D, false
        ));

        InMemoryWorkflowRuntimeRepository blockedRuntime = runtime(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        ProjectTestEvidence exhausted = evidence(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", ProjectTestEvidenceStatus.INFRASTRUCTURE_FAILED,
                null, 0, 0, -1D, "MCP_CONNECTION_FAILED", false
        );
        CompleteWorkItemResult blocked = coordinator(
                blockedRuntime, repositoryOf(exhausted), readableWorkspace("rd")
        ).completeWorkItem(completion(
                "exhausted-infra", WorkflowStage.CODE_IMPLEMENTATION, "rd", exhausted.executionId(),
                WorkCompletionDecision.ACCEPTED, WorkImpactLevel.LOW, 0.9D, 0.9D, false
        ));

        assertThat(recovering.outcome()).isEqualTo(WorkCompletionOutcome.RECOVERING);
        assertThat(recoveringRuntime.state().findExecution(EXECUTION_ID).orElseThrow().status())
                .isEqualTo(WorkExecutionStatus.RECOVERING);
        assertThat(blocked.outcome()).isEqualTo(WorkCompletionOutcome.BLOCKED);
        assertThat(blockedRuntime.state().findExecution(EXECUTION_ID).orElseThrow().status())
                .isEqualTo(WorkExecutionStatus.BLOCKED);
        assertThat(blockedRuntime.state().executions()).hasSize(1);
    }

    /**
     * 验证 QA 只有引用真实 pytest 失败时才能确定性创建 RD 缺陷修复执行。
     */
    @Test
    void shouldCreateDefectFixFromQaFailureEvidence() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.QA_TEST_EXECUTION, "qa", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        ProjectTestEvidence evidence = evidence(
                WorkflowStage.QA_TEST_EXECUTION, "qa", ProjectTestEvidenceStatus.TEST_FAILED,
                1, 20, 18, 91D, "PYTEST_ASSERTION_FAILED", false
        );

        CompleteWorkItemResult result = coordinator(
                runtime, repositoryOf(evidence), readableWorkspace("qa")
        ).completeWorkItem(completion(
                "qa-defect", WorkflowStage.QA_TEST_EXECUTION, "qa", evidence.executionId(),
                WorkCompletionDecision.DEFECT_FOUND, WorkImpactLevel.LOW, 0.9D, 0.9D, false
        ));

        assertThat(result.workflowStage()).isEqualTo(WorkflowStage.DEFECT_FIX);
        assertThat(runtime.state().executions()).anySatisfy(next -> {
            assertThat(next.stage()).isEqualTo(WorkflowStage.DEFECT_FIX);
            assertThat(next.owner()).isEqualTo("rd");
        });
    }

    /**
     * 验证高风险完成先停在阻断评审，批准后才产生唯一下一执行。
     */
    @Test
    void shouldBlockHighRiskWorkUntilMandatoryReviewApproves() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.PRODUCT_DESIGN, "pm", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        WorkCompletionCoordinator coordinator = coordinator(runtime, noTestEvidence(), readableWorkspace("pm"));
        CompleteWorkItemResult waiting = coordinator.completeWorkItem(completion(
                "high-risk", WorkflowStage.PRODUCT_DESIGN, "pm", "", WorkCompletionDecision.ACCEPTED,
                WorkImpactLevel.HIGH, 0.95D, 0.95D, false
        ));

        ReviewRoundResult approved = coordinator.submitReview(new SubmitReviewCommand(
                new WorkflowCommandId("review-approve"), PROJECT_ID, waiting.reviewRoundId(),
                "rd", ReviewVerdict.APPROVED
        ));

        assertThat(waiting.outcome()).isEqualTo(WorkCompletionOutcome.WAITING_REVIEW);
        assertThat(approved.reviewStatus()).isEqualTo(ReviewRoundStatus.APPROVED);
        assertThat(approved.executionStatus()).isEqualTo(WorkExecutionStatus.SUCCEEDED);
        assertThat(runtime.state().executions()).hasSize(2);
        assertThat(runtime.state().findExecution(EXECUTION_ID).orElseThrow().status())
                .isEqualTo(WorkExecutionStatus.SUCCEEDED);
    }

    /**
     * 验证 Strict 双 Reviewer 屏障在第一票后不推进，第二票后只推进一次。
     */
    @Test
    void shouldWaitForBothStrictReviewers() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.TECH_DESIGN, "rd", WorkflowProfile.STRICT, ReviewPolicy.STRICT_BLOCKING
        );
        WorkCompletionCoordinator coordinator = coordinator(runtime, noTestEvidence(), readableWorkspace("rd"));
        CompleteWorkItemResult waiting = coordinator.completeWorkItem(completion(
                "strict-work", WorkflowStage.TECH_DESIGN, "rd", "", WorkCompletionDecision.ACCEPTED,
                WorkImpactLevel.LOW, 0.99D, 0.99D, false
        ));

        ReviewRoundResult first = coordinator.submitReview(new SubmitReviewCommand(
                new WorkflowCommandId("strict-pm"), PROJECT_ID, waiting.reviewRoundId(),
                "pm", ReviewVerdict.APPROVED
        ));
        ReviewRoundResult second = coordinator.submitReview(new SubmitReviewCommand(
                new WorkflowCommandId("strict-qa"), PROJECT_ID, waiting.reviewRoundId(),
                "qa", ReviewVerdict.APPROVED
        ));

        assertThat(first.reviewStatus()).isEqualTo(ReviewRoundStatus.IN_PROGRESS);
        assertThat(first.executionStatus()).isEqualTo(WorkExecutionStatus.WAITING_REVIEW);
        assertThat(second.reviewStatus()).isEqualTo(ReviewRoundStatus.APPROVED);
        assertThat(runtime.state().executions()).hasSize(2);
        assertThat(runtime.state().executions()).anyMatch(
                item -> item.stage() == WorkflowStage.CODE_IMPLEMENTATION && item.owner().equals("rd")
        );
    }

    /**
     * 验证要求修订时复用原 execution ID 和递增 attempt，不创建平行工作项。
     */
    @Test
    void shouldReuseExecutionForRequestedRevision() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.PRODUCT_DESIGN, "pm", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        WorkCompletionCoordinator coordinator = coordinator(runtime, noTestEvidence(), readableWorkspace("pm"));
        CompleteWorkItemResult waiting = coordinator.completeWorkItem(completion(
                "reviewed-work", WorkflowStage.PRODUCT_DESIGN, "pm", "", WorkCompletionDecision.ACCEPTED,
                WorkImpactLevel.HIGH, 0.9D, 0.9D, false
        ));

        ReviewRoundResult revision = coordinator.submitReview(new SubmitReviewCommand(
                new WorkflowCommandId("request-revision"), PROJECT_ID, waiting.reviewRoundId(),
                "rd", ReviewVerdict.CHANGES_REQUESTED
        ));

        WorkExecution execution = runtime.state().findExecution(EXECUTION_ID).orElseThrow();
        assertThat(revision.reviewStatus()).isEqualTo(ReviewRoundStatus.CHANGES_REQUESTED);
        assertThat(execution.status()).isEqualTo(WorkExecutionStatus.PENDING);
        assertThat(execution.attempt()).isEqualTo(2);
        assertThat(runtime.state().executions()).hasSize(1);
        assertThat(runtime.state().outboxActions()).hasSize(4);
    }

    /**
     * 验证阻断评审缺少 Reviewer 回包时形成 EXPIRED/BLOCKED，并只写一次稳定超时事件。
     */
    @Test
    void shouldExpireMandatoryReviewAndBlockWaitingExecution() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.PRODUCT_DESIGN, "pm", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        WorkCompletionCoordinator coordinator = coordinator(runtime, noTestEvidence(), readableWorkspace("pm"));
        CompleteWorkItemResult waiting = coordinator.completeWorkItem(completion(
                "review-timeout-work", WorkflowStage.PRODUCT_DESIGN, "pm", "",
                WorkCompletionDecision.ACCEPTED, WorkImpactLevel.HIGH, 0.95D, 0.95D, false
        ));
        WorkspaceRepository projects = mock(WorkspaceRepository.class);
        when(projects.listProjectIds()).thenReturn(List.of(PROJECT_ID));
        ReviewDeadlineWatchdog watchdog = new ReviewDeadlineWatchdog(
                runtime, projects,
                Clock.fixed(COMMAND_AT.plus(Duration.ofMinutes(10)), ZoneOffset.UTC)
        );

        int first = watchdog.expireOverdue();
        int repeated = watchdog.expireOverdue();

        assertThat(first).isEqualTo(1);
        assertThat(repeated).isZero();
        assertThat(runtime.state().reviewRounds()).singleElement().satisfies(review -> {
            assertThat(review.reviewRoundId()).isEqualTo(waiting.reviewRoundId());
            assertThat(review.status()).isEqualTo(ReviewRoundStatus.EXPIRED);
            assertThat(review.failureReason()).isEqualTo(ReviewDeadlineWatchdog.DEADLINE_FAILURE_TYPE);
        });
        assertThat(runtime.state().findExecution(EXECUTION_ID).orElseThrow()).satisfies(execution -> {
            assertThat(execution.status()).isEqualTo(WorkExecutionStatus.BLOCKED);
            assertThat(execution.failureType()).isEqualTo(ReviewDeadlineWatchdog.DEADLINE_FAILURE_TYPE);
        });
        assertThat(runtime.state().outboxActions()).filteredOn(action ->
                "review_deadline_exceeded".equals(action.attributes().get("action"))
        ).hasSize(1);
    }

    /**
     * 验证建议评审逾期只关闭 ReviewRound，不回退已推进的主 WorkExecution。
     */
    @Test
    void shouldExpireAdvisoryReviewWithoutBlockingSucceededExecution() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.PRODUCT_DESIGN, "pm", WorkflowProfile.STANDARD, ReviewPolicy.ADVISORY_AUDIT
        );
        WorkCompletionCoordinator coordinator = coordinator(runtime, noTestEvidence(), readableWorkspace("pm"));
        coordinator.completeWorkItem(completion(
                "advisory-timeout-work", WorkflowStage.PRODUCT_DESIGN, "pm", "",
                WorkCompletionDecision.ACCEPTED, WorkImpactLevel.LOW, 0.95D, 0.95D, true
        ));
        WorkspaceRepository projects = mock(WorkspaceRepository.class);
        when(projects.listProjectIds()).thenReturn(List.of(PROJECT_ID));
        ReviewDeadlineWatchdog watchdog = new ReviewDeadlineWatchdog(
                runtime, projects,
                Clock.fixed(COMMAND_AT.plus(Duration.ofMinutes(10)), ZoneOffset.UTC)
        );

        assertThat(watchdog.expireOverdue()).isEqualTo(1);

        assertThat(runtime.state().reviewRounds()).singleElement()
                .satisfies(review -> assertThat(review.status()).isEqualTo(ReviewRoundStatus.EXPIRED));
        assertThat(runtime.state().findExecution(EXECUTION_ID).orElseThrow().status())
                .isEqualTo(WorkExecutionStatus.SUCCEEDED);
        assertThat(runtime.state().executions()).hasSize(2);
    }

    /**
     * 验证建议审计记录 ReviewRound 但不持有主流程推进权。
     */
    @Test
    void shouldAdvanceWhileAdvisoryAuditRemainsOpen() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.PRODUCT_DESIGN, "pm", WorkflowProfile.STANDARD, ReviewPolicy.ADVISORY_AUDIT
        );

        CompleteWorkItemResult result = coordinator(
                runtime, noTestEvidence(), readableWorkspace("pm")
        ).completeWorkItem(completion(
                "advisory-work", WorkflowStage.PRODUCT_DESIGN, "pm", "", WorkCompletionDecision.ACCEPTED,
                WorkImpactLevel.LOW, 0.95D, 0.95D, true
        ));

        assertThat(result.outcome()).isEqualTo(WorkCompletionOutcome.COMPLETED);
        assertThat(result.reviewRoundId()).isNotBlank();
        assertThat(runtime.state().reviewRounds()).singleElement().satisfies(review -> {
            assertThat(review.status()).isEqualTo(ReviewRoundStatus.PENDING);
            assertThat(review.blocksWorkflow()).isFalse();
        });
        assertThat(runtime.state().executions()).hasSize(2);
    }

    /**
     * 验证产物不可读时整个 command 回滚，不留下 processed command、终态或 outbox。
     */
    @Test
    void shouldRejectUnreadableArtifactWithoutPartialCommit() {
        InMemoryWorkflowRuntimeRepository runtime = runtime(
                WorkflowStage.PRODUCT_DESIGN, "pm", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );
        WorkspaceRepository workspace = mock(WorkspaceRepository.class);
        when(workspace.read(PROJECT_ID, "pm", artifactFor(WorkflowStage.PRODUCT_DESIGN)))
                .thenThrow(new IllegalStateException("missing"));

        assertThatThrownBy(() -> coordinator(runtime, noTestEvidence(), workspace).completeWorkItem(completion(
                "missing-artifact", WorkflowStage.PRODUCT_DESIGN, "pm", "", WorkCompletionDecision.ACCEPTED,
                WorkImpactLevel.LOW, 0.9D, 0.9D, false
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("阶段必需产物不可读");

        assertThat(runtime.state().findExecution(EXECUTION_ID).orElseThrow().status())
                .isEqualTo(WorkExecutionStatus.RUNNING);
        assertThat(runtime.state().processedCommands()).isEmpty();
        assertThat(runtime.state().outboxActions()).isEmpty();
    }

    /**
     * 验证领取和启动命令都由稳定 command ID 去重且只绑定一组运行时关联。
     */
    @Test
    void shouldClaimAndStartPendingExecutionIdempotently() {
        WorkflowRuntimeState initial = WorkflowRuntimeState.initialize(
                PROJECT_ID, WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY, CREATED_AT
        ).addExecution(WorkExecution.create(
                EXECUTION_ID, PROJECT_ID, WorkflowStage.PRODUCT_DESIGN,
                "pm", WorkflowProfile.STANDARD, CREATED_AT
        ), CREATED_AT);
        InMemoryWorkflowRuntimeRepository runtime = new InMemoryWorkflowRuntimeRepository(initial);
        WorkCompletionCoordinator coordinator = coordinator(runtime, noTestEvidence(), readableWorkspace("pm"));
        ClaimWorkItemCommand claim = new ClaimWorkItemCommand(
                new WorkflowCommandId("claim-current"), PROJECT_ID, EXECUTION_ID, "assignment-current"
        );
        StartWorkItemCommand start = new StartWorkItemCommand(
                new WorkflowCommandId("start-current"), PROJECT_ID, EXECUTION_ID,
                "dispatch-current", "session-current"
        );

        WorkExecution firstClaim = coordinator.claimWorkItem(claim);
        WorkExecution repeatedClaim = coordinator.claimWorkItem(claim);
        WorkExecution firstStart = coordinator.startWorkItem(start);
        WorkExecution repeatedStart = coordinator.startWorkItem(start);

        assertThat(repeatedClaim).isEqualTo(firstClaim);
        assertThat(repeatedStart).isEqualTo(firstStart);
        assertThat(firstStart.status()).isEqualTo(WorkExecutionStatus.RUNNING);
        assertThat(firstStart.assignmentId()).isEqualTo("assignment-current");
        assertThat(firstStart.dispatchId()).isEqualTo("dispatch-current");
        assertThat(firstStart.agentSessionId()).isEqualTo("session-current");
        assertThat(runtime.state().processedCommands()).hasSize(2);
    }

    /**
     * 验证恢复 assignment 复用原 execution ID、递增尝试次数并绑定新的领取标识。
     */
    @Test
    void shouldRetryRecoveringExecutionBeforeClaimingRecoveryAssignment() {
        WorkExecution recovering = WorkExecution.create(
                        EXECUTION_ID, PROJECT_ID, WorkflowStage.CODE_IMPLEMENTATION,
                        "rd", WorkflowProfile.STANDARD, CREATED_AT
                )
                .claim("assignment-first", CREATED_AT.plusSeconds(1))
                .start("dispatch-first", "session-first", CREATED_AT.plusSeconds(2))
                .recover("MCP_TRANSPORT", CREATED_AT.plusSeconds(5));
        WorkflowRuntimeState initial = WorkflowRuntimeState.initialize(
                PROJECT_ID, WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY, CREATED_AT
        ).addExecution(recovering, CREATED_AT.plusSeconds(5));
        InMemoryWorkflowRuntimeRepository runtime = new InMemoryWorkflowRuntimeRepository(initial);
        WorkCompletionCoordinator coordinator = coordinator(runtime, noTestEvidence(), readableWorkspace("rd"));
        ClaimWorkItemCommand command = new ClaimWorkItemCommand(
                new WorkflowCommandId("claim-recovery"), PROJECT_ID, EXECUTION_ID, "assignment-recovery"
        );

        WorkExecution claimed = coordinator.claimWorkItem(command);
        WorkExecution repeated = coordinator.claimWorkItem(command);

        assertThat(repeated).isEqualTo(claimed);
        assertThat(claimed.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(claimed.status()).isEqualTo(WorkExecutionStatus.CLAIMED);
        assertThat(claimed.attempt()).isEqualTo(2);
        assertThat(claimed.assignmentId()).isEqualTo("assignment-recovery");
        assertThat(claimed.dispatchId()).isEmpty();
        assertThat(claimed.agentSessionId()).isEmpty();
        assertThat(runtime.state().processedCommands()).hasSize(1);
    }

    /**
     * 创建已领取并运行的初始 aggregate 仓储。
     *
     * @param stage 当前阶段
     * @param owner 当前 Owner
     * @param profile 冻结档位
     * @param reviewPolicy 冻结评审策略
     * @return 内存 runtime 仓储
     */
    private static InMemoryWorkflowRuntimeRepository runtime(
            WorkflowStage stage,
            String owner,
            WorkflowProfile profile,
            ReviewPolicy reviewPolicy
    ) {
        WorkflowRuntimeState state = WorkflowRuntimeState.initialize(
                PROJECT_ID, profile, reviewPolicy, CREATED_AT
        );
        WorkExecution running = WorkExecution.create(
                        EXECUTION_ID, PROJECT_ID, stage, owner, profile, CREATED_AT
                )
                .claim("assignment-current", CREATED_AT.plusSeconds(1))
                .start("dispatch-current", "session-current", CREATED_AT.plusSeconds(2));
        return new InMemoryWorkflowRuntimeRepository(
                state.addExecution(running, CREATED_AT.plusSeconds(2))
        );
    }

    /**
     * 创建只含需求 checkpoint 的 profile 冻结 aggregate。
     *
     * @param profile 待验证档位
     * @return 内存 runtime 仓储
     */
    private static InMemoryWorkflowRuntimeRepository checkpointRuntime(WorkflowProfile profile) {
        WorkflowRuntimeState state = WorkflowRuntimeState.initialize(
                PROJECT_ID, profile, ReviewPolicy.REQUIRED_ONLY, CREATED_AT
        ).addExecution(WorkExecution.create(
                EXECUTION_ID, PROJECT_ID, WorkflowStage.REQUIREMENTS_CHECKPOINT,
                "manager", profile, CREATED_AT
        ), CREATED_AT);
        return new InMemoryWorkflowRuntimeRepository(state);
    }

    /**
     * 创建使用确定标识与固定时钟的完成协调器。
     *
     * @param runtime 内存 runtime 仓储
     * @param evidenceRepository Test Runner 证据仓储
     * @param workspace 产物仓储
     * @return 可重复断言的完成协调器
     */
    private static WorkCompletionCoordinator coordinator(
            WorkflowRuntimeRepository runtime,
            ProjectTestEvidenceRepository evidenceRepository,
            WorkspaceRepository workspace
    ) {
        AtomicInteger executionSequence = new AtomicInteger();
        AtomicInteger reviewSequence = new AtomicInteger();
        return new WorkCompletionCoordinator(
                runtime, evidenceRepository, workspace,
                () -> "work-next-" + executionSequence.incrementAndGet(),
                () -> "review-next-" + reviewSequence.incrementAndGet(),
                Clock.fixed(COMMAND_AT, ZoneOffset.UTC), Duration.ofMinutes(10)
        );
    }

    /**
     * 创建覆盖指定阶段的最小完成命令。
     *
     * @param commandId 稳定命令标识
     * @param stage 当前阶段
     * @param owner 当前 Owner
     * @param testExecutionId 可选测试执行标识
     * @param decision 有限完成结论
     * @param impactLevel 影响等级
     * @param selfScore 自评分
     * @param hardConstraints 硬约束得分
     * @param advisoryAuditRequested 是否请求建议审计
     * @return 完成命令
     */
    private static CompleteWorkItemCommand completion(
            String commandId,
            WorkflowStage stage,
            String owner,
            String testExecutionId,
            WorkCompletionDecision decision,
            WorkImpactLevel impactLevel,
            Double selfScore,
            Double hardConstraints,
            boolean advisoryAuditRequested
    ) {
        return new CompleteWorkItemCommand(
                new WorkflowCommandId(commandId), PROJECT_ID, EXECUTION_ID, owner,
                List.of(artifactFor(stage)), decision, "阶段产物已完成",
                selfScore, hardConstraints, impactLevel, testExecutionId, advisoryAuditRequested
        );
    }

    /**
     * 创建归属于当前项目与阶段的真实测试证据。
     *
     * @param stage 测试阶段
     * @param role 发起角色
     * @param status 测试状态
     * @param exitCode 退出码
     * @param collected 收集数
     * @param passed 通过数
     * @param coverage 覆盖率
     * @param failureType 失败类型
     * @param retryable 是否可恢复
     * @return 测试证据
     */
    private static ProjectTestEvidence evidence(
            WorkflowStage stage,
            String role,
            ProjectTestEvidenceStatus status,
            Integer exitCode,
            int collected,
            int passed,
            double coverage,
            String failureType,
            boolean retryable
    ) {
        return new ProjectTestEvidence(
                "test-current", PROJECT_ID, role, stage, status, exitCode,
                collected, passed, coverage, failureType, retryable
        );
    }

    /**
     * 创建只返回指定测试证据的查询端口。
     *
     * @param evidence 测试证据
     * @return 有严格身份检查的查询端口
     */
    private static ProjectTestEvidenceRepository repositoryOf(ProjectTestEvidence evidence) {
        return (projectId, role, stage, executionId) -> evidence.projectId().equals(projectId)
                && evidence.role().equals(role)
                && evidence.stage() == stage
                && evidence.executionId().equals(executionId)
                ? Optional.of(evidence)
                : Optional.empty();
    }

    /**
     * 创建永远找不到测试证据的查询端口。
     *
     * @return 空测试证据仓储
     */
    private static ProjectTestEvidenceRepository noTestEvidence() {
        return (projectId, role, stage, executionId) -> Optional.empty();
    }

    /**
     * 创建只允许指定 Owner 读取当前阶段核心产物的仓储桩。
     *
     * @param owner 允许读取的 Owner
     * @return 产物仓储桩
     */
    private static WorkspaceRepository readableWorkspace(String owner) {
        WorkspaceRepository workspace = mock(WorkspaceRepository.class);
        for (WorkflowStage stage : List.of(
                WorkflowStage.PRODUCT_DESIGN,
                WorkflowStage.TECH_DESIGN,
                WorkflowStage.CODE_IMPLEMENTATION,
                WorkflowStage.QA_TEST_DESIGN,
                WorkflowStage.QA_TEST_EXECUTION,
                WorkflowStage.DEFECT_FIX
        )) {
            when(workspace.read(PROJECT_ID, owner, artifactFor(stage))).thenReturn("已完成产物");
        }
        return workspace;
    }

    /**
     * 返回每个可完成阶段的唯一核心产物路径。
     *
     * @param stage 工作流阶段
     * @return 核心产物相对路径
     */
    private static String artifactFor(WorkflowStage stage) {
        return switch (stage) {
            case PRODUCT_DESIGN -> "design/product_spec.md";
            case TECH_DESIGN -> "tech/tech_design.md";
            case CODE_IMPLEMENTATION, DEFECT_FIX -> "code/main.py";
            case QA_TEST_DESIGN -> "qa/test_plan.md";
            case QA_TEST_EXECUTION -> "qa/test_report.md";
            default -> throw new IllegalArgumentException("阶段没有完成产物: " + stage);
        };
    }

    /**
     * 以同步临界区模拟生产仓储的 command 去重和原子提交语义。
     */
    private static final class InMemoryWorkflowRuntimeRepository implements WorkflowRuntimeRepository {

        /** 当前唯一 aggregate 快照。 */
        private WorkflowRuntimeState state;

        /** 创建尚无 aggregate 的内存仓储，用于验证项目初始化入口。 */
        private InMemoryWorkflowRuntimeRepository() {
            this.state = null;
        }

        /**
         * 使用既有 aggregate 创建内存仓储。
         *
         * @param initial 初始 aggregate
         */
        private InMemoryWorkflowRuntimeRepository(WorkflowRuntimeState initial) {
            this.state = initial;
        }

        /**
         * 返回当前 aggregate 供断言使用。
         *
         * @return 当前 aggregate
         */
        private synchronized WorkflowRuntimeState state() {
            return state;
        }

        /**
         * 查询匹配项目的当前 aggregate。
         *
         * @param projectId 项目标识
         * @return 匹配时返回 aggregate
         */
        @Override
        public synchronized Optional<WorkflowRuntimeState> find(String projectId) {
            return state != null && state.projectId().equals(projectId) ? Optional.of(state) : Optional.empty();
        }

        /**
         * 只允许首次创建 aggregate，模拟生产仓储的 create-if-absent 语义。
         *
         * @param initial 待创建 aggregate
         * @return 首次创建后的 aggregate
         */
        @Override
        public synchronized WorkflowRuntimeState create(WorkflowRuntimeState initial) {
            if (state != null) {
                throw new IllegalStateException("测试 aggregate 已存在");
            }
            state = initial;
            return state;
        }

        /**
         * 在同步临界区执行一次 command，并对相同 command ID 返回首次结果。
         *
         * @param projectId 项目标识
         * @param commandId 稳定命令标识
         * @param command 首次状态变换
         * @return 首次或重复命令回执
         */
        @Override
        public synchronized WorkflowCommandReceipt execute(
                String projectId,
                WorkflowCommandId commandId,
                WorkflowCommand command
        ) {
            if (!state.projectId().equals(projectId)) {
                throw new IllegalArgumentException("项目不存在");
            }
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
         * 在同步临界区应用不带 command ID 的内部状态更新。
         *
         * @param projectId 项目标识
         * @param update 内部状态变换
         * @return 更新后的 aggregate
         */
        @Override
        public synchronized WorkflowRuntimeState update(
                String projectId,
                WorkflowRuntimeUpdate update
        ) {
            if (!state.projectId().equals(projectId)) {
                throw new IllegalArgumentException("项目不存在");
            }
            state = update.apply(state);
            return state;
        }
    }
}
