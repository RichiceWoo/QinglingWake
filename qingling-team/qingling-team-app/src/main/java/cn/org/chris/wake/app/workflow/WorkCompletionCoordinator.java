package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.gateway.ProjectTestEvidenceRepository;
import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workflow.OutboxAction;
import cn.org.chris.wake.domain.workflow.OutboxActionId;
import cn.org.chris.wake.domain.workflow.OutboxActionType;
import cn.org.chris.wake.domain.workflow.ProjectTestEvidence;
import cn.org.chris.wake.domain.workflow.ProjectTestEvidenceStatus;
import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.ReviewRequirement;
import cn.org.chris.wake.domain.workflow.ReviewRound;
import cn.org.chris.wake.domain.workflow.ReviewRoundStatus;
import cn.org.chris.wake.domain.workflow.ReviewVerdict;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import cn.org.chris.wake.domain.workflow.WorkflowSignal;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.domain.workflow.WorkflowStateMachine;
import cn.org.chris.wake.domain.workflow.WorkflowTransition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 在一个 runtime v2 command 内完成证据校验、终态、评审决策和下一工作项创建。
 */
public final class WorkCompletionCoordinator {

    /** runtime v2 唯一权威仓储。 */
    private final WorkflowRuntimeRepository runtimeRepository;

    /** 真实 Test Runner 证据查询端口。 */
    private final ProjectTestEvidenceRepository testEvidenceRepository;

    /** 共享产物读取端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** Java 固定阶段转换表。 */
    private final WorkflowStateMachine stateMachine;

    /** 下一 WorkExecution 标识生成器。 */
    private final Supplier<String> executionIdSupplier;

    /** 下一 ReviewRound 标识生成器。 */
    private final Supplier<String> reviewRoundIdSupplier;

    /** 业务时间来源。 */
    private final Clock clock;

    /** 阻断式评审的有界业务截止时间。 */
    private final Duration reviewTimeout;

    /**
     * 使用随机标识和系统 UTC 时钟创建完成协调器。
     *
     * @param runtimeRepository runtime v2 仓储
     * @param testEvidenceRepository 真实测试证据端口
     * @param workspaceRepository 共享产物端口
     * @param reviewTimeout 评审业务超时
     */
    public WorkCompletionCoordinator(
            WorkflowRuntimeRepository runtimeRepository,
            ProjectTestEvidenceRepository testEvidenceRepository,
            WorkspaceRepository workspaceRepository,
            Duration reviewTimeout
    ) {
        this(
                runtimeRepository,
                testEvidenceRepository,
                workspaceRepository,
                () -> "work-" + UUID.randomUUID(),
                () -> "review-" + UUID.randomUUID(),
                Clock.systemUTC(),
                reviewTimeout
        );
    }

    /**
     * 幂等创建冻结 profile/policy 的 runtime v2 aggregate 和需求 checkpoint 执行。
     *
     * @param projectId 项目标识
     * @param projectName 有界项目名称
     * @param profile 创建时冻结档位
     * @param reviewPolicy 创建时冻结评审策略
     * @return 已创建或已存在且配置一致的 aggregate
     */
    public WorkflowRuntimeState initializeProject(
            String projectId,
            String projectName,
            WorkflowProfile profile,
            ReviewPolicy reviewPolicy
    ) {
        String checkedProjectId = requireText(projectId, "projectId");
        String checkedProjectName = requireText(projectName, "projectName");
        Objects.requireNonNull(profile, "profile 不能为空");
        Objects.requireNonNull(reviewPolicy, "reviewPolicy 不能为空");
        WorkflowRuntimeState existing = runtimeRepository.find(checkedProjectId).orElse(null);
        if (existing != null) {
            if (existing.profile() != profile || existing.reviewPolicy() != reviewPolicy) {
                throw new IllegalStateException("项目已冻结为其他 workflow profile 或 review policy");
            }
            return existing;
        }
        Instant now = Instant.now(clock);
        WorkExecution checkpoint = WorkExecution.create(
                requireText(executionIdSupplier.get(), "executionId"),
                checkedProjectId,
                WorkflowStage.REQUIREMENTS_CHECKPOINT,
                "manager",
                profile,
                now
        );
        WorkflowRuntimeState initial = WorkflowRuntimeState.initialize(
                checkedProjectId, profile, reviewPolicy, now
        ).addExecution(checkpoint, now);
        OutboxAction created = OutboxAction.pending(
                actionId("project:" + checkedProjectId, "created"),
                OutboxActionType.WORKFLOW_EVENT,
                checkedProjectId,
                Map.of(
                        "action", "project_created",
                        "project_id", checkedProjectId,
                        "project_name", checkedProjectName,
                        "workflow_version", Integer.toString(WorkflowRuntimeState.CURRENT_SCHEMA_VERSION),
                        "profile", profile.name(),
                        "review_policy", reviewPolicy.name(),
                        "execution_id", checkpoint.executionId()
                ),
                now
        );
        return runtimeRepository.create(initial.enqueue(created, now));
    }

    /**
     * 原子处理需求或交付 checkpoint，由冻结 profile 决定下一阶段并终结当前 checkpoint 执行。
     *
     * @param commandId 稳定幂等命令标识
     * @param projectId 项目标识
     * @param signal checkpoint 批准或拒绝结论
     * @return 稳定 checkpoint 迁移结果
     */
    public CheckpointTransitionResult resolveCheckpoint(
            WorkflowCommandId commandId,
            String projectId,
            WorkflowSignal signal
    ) {
        Objects.requireNonNull(commandId, "commandId 不能为空");
        String checkedProjectId = requireText(projectId, "projectId");
        Objects.requireNonNull(signal, "signal 不能为空");
        WorkflowRuntimeRepository.WorkflowCommandReceipt receipt = runtimeRepository.execute(
                checkedProjectId,
                commandId,
                current -> {
                    CheckpointMutation mutation = applyCheckpoint(
                            current, commandId, signal, monotonicNow(current)
                    );
                    return new WorkflowRuntimeRepository.WorkflowCommandMutation(
                            mutation.state(), "CHECKPOINT_RESOLVED", encode(mutation.result())
                    );
                }
        );
        return decodeCheckpoint(receipt.command().resultReference());
    }

    /**
     * 使用可测试标识生成器和时钟创建完成协调器。
     *
     * @param runtimeRepository runtime v2 仓储
     * @param testEvidenceRepository 真实测试证据端口
     * @param workspaceRepository 共享产物端口
     * @param executionIdSupplier 工作执行标识生成器
     * @param reviewRoundIdSupplier 评审轮次标识生成器
     * @param clock UTC 时钟
     * @param reviewTimeout 评审业务超时
     */
    public WorkCompletionCoordinator(
            WorkflowRuntimeRepository runtimeRepository,
            ProjectTestEvidenceRepository testEvidenceRepository,
            WorkspaceRepository workspaceRepository,
            Supplier<String> executionIdSupplier,
            Supplier<String> reviewRoundIdSupplier,
            Clock clock,
            Duration reviewTimeout
    ) {
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository, "runtimeRepository 不能为空");
        this.testEvidenceRepository = Objects.requireNonNull(
                testEvidenceRepository, "testEvidenceRepository 不能为空"
        );
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.stateMachine = new WorkflowStateMachine();
        this.executionIdSupplier = Objects.requireNonNull(executionIdSupplier, "executionIdSupplier 不能为空");
        this.reviewRoundIdSupplier = Objects.requireNonNull(
                reviewRoundIdSupplier, "reviewRoundIdSupplier 不能为空"
        );
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.reviewTimeout = Objects.requireNonNull(reviewTimeout, "reviewTimeout 不能为空");
        if (reviewTimeout.isZero() || reviewTimeout.isNegative()) {
            throw new IllegalArgumentException("reviewTimeout 必须为正数");
        }
    }

    /**
     * 原子完成当前工作，并返回可由 processed command 重建的稳定结果。
     *
     * @param command 最小完成命令
     * @return 稳定完成结果
     */
    public CompleteWorkItemResult completeWorkItem(CompleteWorkItemCommand command) {
        Objects.requireNonNull(command, "command 不能为空");
        WorkflowRuntimeRepository.WorkflowCommandReceipt receipt = runtimeRepository.execute(
                command.projectId(), command.commandId(),
                current -> {
                    CompletionMutation mutation = applyCompletion(
                            current, command, monotonicNow(current)
                    );
                    return new WorkflowRuntimeRepository.WorkflowCommandMutation(
                            mutation.state(), mutation.result().outcome().name(), encode(mutation.result())
                    );
                }
        );
        return decodeCompletion(receipt.command().outcomeCode(), receipt.command().resultReference());
    }

    /**
     * 原子领取唯一 PENDING 工作项；恢复 assignment 会先复用原 execution 开启下一次尝试。
     *
     * @param command 领取命令
     * @return 已领取工作执行
     */
    public WorkExecution claimWorkItem(ClaimWorkItemCommand command) {
        Objects.requireNonNull(command, "command 不能为空");
        requireText(command.projectId(), "projectId");
        requireText(command.executionId(), "executionId");
        requireText(command.assignmentId(), "assignmentId");
        WorkflowRuntimeRepository.WorkflowCommandReceipt receipt = runtimeRepository.execute(
                command.projectId(), Objects.requireNonNull(command.commandId(), "commandId 不能为空"),
                current -> {
                    Instant now = monotonicNow(current);
                    WorkExecution execution = requireExecution(current, command.executionId());
                    WorkExecution claimable = execution.status() == WorkExecutionStatus.RECOVERING
                            ? execution.retry(now)
                            : execution;
                    WorkExecution claimed = claimable.claim(command.assignmentId(), now);
                    return new WorkflowRuntimeRepository.WorkflowCommandMutation(
                            current.replaceExecution(claimed, now), "CLAIMED", claimed.executionId()
                    );
                }
        );
        return requireExecution(receipt.state(), receipt.command().resultReference());
    }

    /**
     * 原子启动已领取工作项并绑定 dispatch 与 Agent session。
     *
     * @param command 启动命令
     * @return 运行中工作执行
     */
    public WorkExecution startWorkItem(StartWorkItemCommand command) {
        Objects.requireNonNull(command, "command 不能为空");
        requireText(command.projectId(), "projectId");
        requireText(command.executionId(), "executionId");
        requireText(command.dispatchId(), "dispatchId");
        requireText(command.agentSessionId(), "agentSessionId");
        WorkflowRuntimeRepository.WorkflowCommandReceipt receipt = runtimeRepository.execute(
                command.projectId(), Objects.requireNonNull(command.commandId(), "commandId 不能为空"),
                current -> {
                    Instant now = monotonicNow(current);
                    WorkExecution running = requireExecution(current, command.executionId())
                            .start(command.dispatchId(), command.agentSessionId(), now);
                    return new WorkflowRuntimeRepository.WorkflowCommandMutation(
                            current.replaceExecution(running, now), "RUNNING", running.executionId()
                    );
                }
        );
        return requireExecution(receipt.state(), receipt.command().resultReference());
    }

    /**
     * 按 review round ID 原子提交 Reviewer 结论并确定推进、修订或继续等待。
     *
     * @param command 评审提交命令
     * @return 评审与工作执行权威结果
     */
    public ReviewRoundResult submitReview(SubmitReviewCommand command) {
        Objects.requireNonNull(command, "command 不能为空");
        requireText(command.projectId(), "projectId");
        requireText(command.reviewRoundId(), "reviewRoundId");
        requireText(command.reviewer(), "reviewer");
        Objects.requireNonNull(command.verdict(), "verdict 不能为空");
        WorkflowRuntimeRepository.WorkflowCommandReceipt receipt = runtimeRepository.execute(
                command.projectId(), Objects.requireNonNull(command.commandId(), "commandId 不能为空"),
                current -> {
                    ReviewMutation mutation = applyReview(current, command, monotonicNow(current));
                    return new WorkflowRuntimeRepository.WorkflowCommandMutation(
                            mutation.state(), mutation.result().reviewStatus().name(), encode(mutation.result())
                    );
                }
        );
        return decodeReview(receipt.command().resultReference());
    }

    /**
     * 返回不早于 aggregate 更新时间的业务时间，避免投影或文件时钟细粒度导致状态回退。
     */
    private Instant monotonicNow(WorkflowRuntimeState current) {
        Instant clockNow = Instant.now(clock);
        return clockNow.isBefore(current.updatedAt()) ? current.updatedAt() : clockNow;
    }

    /**
     * 根据锁内最新 aggregate 应用一次工作完成命令。
     *
     * @param current 锁内最新 aggregate
     * @param command 完成命令
     * @param now 业务时间
     * @return 新 aggregate 与稳定结果
     */
    private CompletionMutation applyCompletion(
            WorkflowRuntimeState current,
            CompleteWorkItemCommand command,
            Instant now
    ) {
        WorkExecution execution = requireExecution(current, command.executionId());
        requireCompletionContext(current, execution, command);
        validateArtifacts(execution, command.artifacts());
        WorkflowSignal signal = completionSignal(execution.stage(), command.decision());
        TestDecision testDecision = evaluateTestEvidence(execution, command, now);
        if (testDecision.outcome() != null) {
            WorkflowRuntimeState changed = current.replaceExecution(testDecision.execution(), now);
            changed = enqueueEvent(
                    changed, command.commandId(), testDecision.outcome().name().toLowerCase(),
                    execution, now
            );
            CompleteWorkItemResult result = new CompleteWorkItemResult(
                    execution.executionId(), testDecision.outcome(), execution.stage(),
                    "", "", testDecision.detailCode()
            );
            return new CompletionMutation(changed, result);
        }
        WorkExecution evidencedExecution = testDecision.execution();
        WorkflowRuntimeState evidencedState = current.replaceExecution(evidencedExecution, now);
        if (requiresBlockingReview(current.reviewPolicy(), command)) {
            String reviewRoundId = requireText(reviewRoundIdSupplier.get(), "reviewRoundId");
            ReviewRound review = newReviewRound(
                    current, evidencedExecution, reviewRoundId,
                    ReviewRequirement.MANDATORY, signal, now
            );
            WorkExecution waiting = evidencedExecution.waitForReview(reviewRoundId, now);
            WorkflowRuntimeState waitingState = evidencedState
                    .replaceExecution(waiting, now)
                    .addReviewRound(review, now);
            waitingState = enqueueReviewRequests(waitingState, command.commandId(), review, now);
            CompleteWorkItemResult result = new CompleteWorkItemResult(
                    execution.executionId(), WorkCompletionOutcome.WAITING_REVIEW,
                    execution.stage(), "", reviewRoundId, "MANDATORY_REVIEW"
            );
            return new CompletionMutation(waitingState, result);
        }

        String advisoryReviewId = "";
        WorkflowRuntimeState stateBeforeAdvance = evidencedState;
        if (current.reviewPolicy() == ReviewPolicy.ADVISORY_AUDIT
                && command.advisoryAuditRequested()) {
            advisoryReviewId = requireText(reviewRoundIdSupplier.get(), "reviewRoundId");
            ReviewRound advisory = newReviewRound(
                    current, evidencedExecution, advisoryReviewId,
                    ReviewRequirement.ADVISORY, signal, now
            );
            stateBeforeAdvance = stateBeforeAdvance.addReviewRound(advisory, now);
            stateBeforeAdvance = enqueueReviewRequests(
                    stateBeforeAdvance, command.commandId(), advisory, now
            );
        }
        WorkExecution succeeded = evidencedExecution.succeed(now);
        WorkflowRuntimeState succeededState = stateBeforeAdvance.replaceExecution(succeeded, now);
        AdvanceMutation advance = advance(
                succeededState, succeeded, signal, command.commandId().value(), now
        );
        CompleteWorkItemResult result = new CompleteWorkItemResult(
                execution.executionId(), WorkCompletionOutcome.COMPLETED,
                advance.stage(), advance.nextExecutionId(), advisoryReviewId, "ADVANCED"
        );
        return new CompletionMutation(advance.state(), result);
    }

    /**
     * 根据锁内最新 aggregate 终结唯一开放 checkpoint 并创建下一执行。
     *
     * @param current 锁内最新 aggregate
     * @param commandId 稳定命令标识
     * @param signal checkpoint 有限结论
     * @param now 单调业务时间
     * @return 新 aggregate 与稳定结果
     */
    private CheckpointMutation applyCheckpoint(
            WorkflowRuntimeState current,
            WorkflowCommandId commandId,
            WorkflowSignal signal,
            Instant now
    ) {
        WorkExecution checkpoint = current.executions().stream()
                .filter(execution -> !execution.status().terminal())
                .filter(execution -> execution.owner().equals("manager"))
                .filter(execution -> execution.stage() == WorkflowStage.REQUIREMENTS_CHECKPOINT
                        || execution.stage() == WorkflowStage.DELIVERY_CHECKPOINT)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("当前项目不存在开放 checkpoint"));
        WorkExecution running = checkpoint;
        if (running.status() == WorkExecutionStatus.PENDING) {
            running = running.claim("checkpoint:" + commandId.value(), now);
        }
        if (running.status() == WorkExecutionStatus.CLAIMED) {
            running = running.start(
                    "checkpoint:" + commandId.value(),
                    "checkpoint:" + current.projectId(),
                    now
            );
        }
        if (running.status() != WorkExecutionStatus.RUNNING) {
            throw new IllegalStateException("checkpoint 状态不允许处理: " + running.status());
        }
        WorkflowTransition transition = stateMachine.transition(
                current.profile(), running.stage(), signal
        );
        WorkExecution terminal = transition.to() == WorkflowStage.CANCELLED
                ? running.cancel("CHECKPOINT_REJECTED", now)
                : running.succeed(now);
        WorkflowRuntimeState changed = current.replaceExecution(terminal, now);
        changed = enqueueTransitionEvent(changed, commandId.value(), terminal, transition, now);
        AdvanceMutation advance = createNextExecution(
                changed, transition, commandId.value(), now
        );
        return new CheckpointMutation(
                advance.state(),
                new CheckpointTransitionResult(
                        terminal.executionId(), transition.from(), transition.to(),
                        advance.nextExecutionId()
                )
        );
    }

    /**
     * 根据锁内最新 aggregate 应用一次 Reviewer 结论。
     *
     * @param current 锁内最新 aggregate
     * @param command 评审提交命令
     * @param now 业务时间
     * @return 新 aggregate 与稳定评审结果
     */
    private ReviewMutation applyReview(
            WorkflowRuntimeState current,
            SubmitReviewCommand command,
            Instant now
    ) {
        ReviewRound review = current.reviewRounds().stream()
                .filter(item -> item.reviewRoundId().equals(command.reviewRoundId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "reviewRoundId 不属于当前项目: " + command.reviewRoundId()
                ));
        ReviewRound updated = review.submit(command.reviewer(), command.verdict(), now);
        WorkExecution execution = requireExecution(current, review.workExecutionId());
        if (updated == review) {
            return new ReviewMutation(current, new ReviewRoundResult(
                    review.reviewRoundId(), review.status(), execution.executionId(),
                    execution.status(), ""
            ));
        }
        WorkflowRuntimeState changed = current.replaceReviewRound(updated, now);
        if (!updated.status().terminal() || updated.requirement() == ReviewRequirement.ADVISORY) {
            return new ReviewMutation(changed, new ReviewRoundResult(
                    updated.reviewRoundId(), updated.status(), execution.executionId(),
                    execution.status(), ""
            ));
        }
        if (updated.status() == ReviewRoundStatus.APPROVED) {
            WorkExecution succeeded = execution.approveReview(review.reviewRoundId(), now);
            changed = changed.replaceExecution(succeeded, now);
            AdvanceMutation advance = advance(
                    changed, succeeded, review.completionSignal(), command.commandId().value(), now
            );
            return new ReviewMutation(advance.state(), new ReviewRoundResult(
                    updated.reviewRoundId(), updated.status(), execution.executionId(),
                    WorkExecutionStatus.SUCCEEDED, advance.nextExecutionId()
            ));
        }
        if (updated.status() == ReviewRoundStatus.CHANGES_REQUESTED) {
            WorkExecution revision = execution.reviseAfterReview(review.reviewRoundId(), now);
            changed = changed.replaceExecution(revision, now);
            changed = enqueueRevision(changed, command.commandId(), revision, now);
            return new ReviewMutation(changed, new ReviewRoundResult(
                    updated.reviewRoundId(), updated.status(), execution.executionId(),
                    revision.status(), ""
            ));
        }
        throw new IllegalStateException("评审终态需要由超时或取消协调器处理: " + updated.status());
    }

    /**
     * 计算并提交 Java 固定阶段迁移、下一 WorkExecution 和投影 outbox。
     *
     * @param state 当前 aggregate
     * @param completed 已完成工作执行
     * @param signal 已验证有限结论
     * @param actionPrefix 稳定 action ID 前缀
     * @param now 业务时间
     * @return 推进后的 aggregate、阶段与下一执行标识
     */
    private AdvanceMutation advance(
            WorkflowRuntimeState state,
            WorkExecution completed,
            WorkflowSignal signal,
            String actionPrefix,
            Instant now
    ) {
        WorkflowTransition transition = stateMachine.transition(
                state.profile(), completed.stage(), signal
        );
        WorkflowRuntimeState changed = enqueueTransitionEvent(
                state, actionPrefix, completed, transition, now
        );
        return createNextExecution(changed, transition, actionPrefix, now);
    }

    /**
     * 为普通业务 Owner 或交付 checkpoint 的 Manager 创建唯一下一执行及投影任务。
     *
     * @param state 已写入迁移事件的 aggregate
     * @param transition Java 固定迁移
     * @param actionPrefix 稳定 action ID 前缀
     * @param now 业务时间
     * @return 包含下一执行的推进结果
     */
    private AdvanceMutation createNextExecution(
            WorkflowRuntimeState state,
            WorkflowTransition transition,
            String actionPrefix,
            Instant now
    ) {
        String owner = transition.assignee();
        String subject = transition.subject();
        if (transition.to() == WorkflowStage.DELIVERY_CHECKPOINT) {
            owner = "manager";
            subject = "交付确认";
        }
        if (owner.isBlank()) {
            return new AdvanceMutation(state, transition.to(), "");
        }
        String nextExecutionId = requireText(executionIdSupplier.get(), "executionId");
        WorkExecution next = WorkExecution.create(
                nextExecutionId, state.projectId(), transition.to(),
                owner, state.profile(), now
        );
        WorkflowRuntimeState changed = state.addExecution(next, now);
        changed = enqueueAssignment(changed, actionPrefix, next, subject, now);
        return new AdvanceMutation(changed, transition.to(), nextExecutionId);
    }

    /**
     * 从真实 Test Runner 账本区分业务失败、基础设施失败与成功证据。
     *
     * @param execution 当前工作执行
     * @param command 完成命令
     * @param now 业务时间
     * @return 证据绑定后的执行与可选短路结果
     */
    private TestDecision evaluateTestEvidence(
            WorkExecution execution,
            CompleteWorkItemCommand command,
            Instant now
    ) {
        if (!requiresTestEvidence(execution.stage())) {
            if (!command.testExecutionId().isBlank()) {
                throw new IllegalArgumentException("当前阶段不接受 testExecutionId");
            }
            return new TestDecision(execution, null, "");
        }
        if (command.testExecutionId().isBlank()) {
            throw new IllegalStateException("当前阶段完成必须引用真实 testExecutionId");
        }
        ProjectTestEvidence evidence = testEvidenceRepository.find(
                        execution.projectId(), execution.owner(), execution.stage(), command.testExecutionId()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "testExecutionId 不属于当前项目、Owner 和阶段"
                ));
        if (!evidence.status().terminal()) {
            throw new IllegalStateException("Test Runner 尚未终态");
        }
        WorkExecution linked = execution.linkTestExecution(evidence.executionId(), now).recordProgress(now);
        if (evidence.status() == ProjectTestEvidenceStatus.INFRASTRUCTURE_FAILED
                || evidence.status() == ProjectTestEvidenceStatus.TIMED_OUT
                || evidence.status() == ProjectTestEvidenceStatus.CANCELLED) {
            if (evidence.retryable()) {
                return new TestDecision(
                        linked.recover(evidence.failureType(), now),
                        WorkCompletionOutcome.RECOVERING,
                        evidence.failureType()
                );
            }
            return new TestDecision(
                    linked.block(evidence.failureType(), now),
                    WorkCompletionOutcome.BLOCKED,
                    evidence.failureType()
            );
        }
        if (evidence.status() == ProjectTestEvidenceStatus.TEST_FAILED) {
            if (execution.stage() == WorkflowStage.QA_TEST_EXECUTION
                    && command.decision() == WorkCompletionDecision.DEFECT_FOUND) {
                return new TestDecision(linked, null, "PYTEST_FAILURE_CONFIRMED");
            }
            if (execution.stage() == WorkflowStage.CODE_IMPLEMENTATION
                    || execution.stage() == WorkflowStage.DEFECT_FIX) {
                return new TestDecision(
                        linked, WorkCompletionOutcome.TEST_FAILED, evidence.failureType()
                );
            }
            throw new IllegalStateException("失败的 QA 测试不能提交 ACCEPTED");
        }
        validateSuccessfulTestEvidence(evidence);
        if (execution.stage() == WorkflowStage.QA_TEST_EXECUTION
                && command.decision() == WorkCompletionDecision.DEFECT_FOUND) {
            throw new IllegalStateException("QA 缺陷结论缺少真实 pytest 失败");
        }
        return new TestDecision(linked, null, "TEST_SUCCEEDED");
    }

    /**
     * 校验成功证据具有 exit code、pytest 数量和覆盖率终态。
     *
     * @param evidence 真实测试证据
     */
    private static void validateSuccessfulTestEvidence(ProjectTestEvidence evidence) {
        if (evidence.status() != ProjectTestEvidenceStatus.SUCCEEDED
                || evidence.exitCode() == null
                || evidence.exitCode() != 0
                || evidence.pytestCollected() <= 0
                || evidence.pytestPassed() != evidence.pytestCollected()
                || evidence.coveragePercent() < 0D
                || evidence.coveragePercent() > 100D) {
            throw new IllegalStateException("真实 Test Runner 成功证据不完整");
        }
    }

    /**
     * 校验完成命令绑定当前项目、Owner、冻结 profile 和 RUNNING 状态。
     *
     * @param state 当前 aggregate
     * @param execution 当前工作执行
     * @param command 完成命令
     */
    private static void requireCompletionContext(
            WorkflowRuntimeState state,
            WorkExecution execution,
            CompleteWorkItemCommand command
    ) {
        if (!state.projectId().equals(command.projectId())
                || !execution.owner().equals(command.owner())
                || execution.profile() != state.profile()) {
            throw new IllegalArgumentException("完成命令与当前项目、Owner 或 profile 不一致");
        }
        if (execution.status() != WorkExecutionStatus.RUNNING) {
            throw new IllegalStateException("只有 RUNNING WorkExecution 可以完成: " + execution.status());
        }
    }

    /**
     * 校验当前阶段固定产物既被引用又可以由 Owner 读取且非空。
     *
     * @param execution 当前工作执行
     * @param artifacts 完成命令引用的产物路径
     */
    private void validateArtifacts(WorkExecution execution, List<String> artifacts) {
        String requiredArtifact = requiredArtifact(execution.stage());
        if (!artifacts.contains(requiredArtifact)) {
            throw new IllegalStateException("完成命令缺少阶段必需产物引用: " + requiredArtifact);
        }
        String content;
        try {
            content = workspaceRepository.read(
                    execution.projectId(), execution.owner(), requiredArtifact
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException("阶段必需产物不可读: " + requiredArtifact, exception);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("阶段必需产物为空: " + requiredArtifact);
        }
    }

    /**
     * 返回当前阶段唯一必需核心产物路径。
     *
     * @param stage 当前业务阶段
     * @return 核心产物路径
     */
    private static String requiredArtifact(WorkflowStage stage) {
        return switch (stage) {
            case PRODUCT_DESIGN -> "design/product_spec.md";
            case TECH_DESIGN -> "tech/tech_design.md";
            case CODE_IMPLEMENTATION, DEFECT_FIX -> "code/main.py";
            case QA_TEST_DESIGN -> "qa/test_plan.md";
            case QA_TEST_EXECUTION -> "qa/test_report.md";
            default -> throw new IllegalStateException("当前阶段不接受工作完成命令: " + stage);
        };
    }

    /**
     * 将有限完成 decision 映射为当前阶段可接受的 WorkflowSignal。
     *
     * @param stage 当前业务阶段
     * @param decision 有限完成结论
     * @return Java 状态机信号
     */
    private static WorkflowSignal completionSignal(
            WorkflowStage stage,
            WorkCompletionDecision decision
    ) {
        if (decision == WorkCompletionDecision.DEFECT_FOUND) {
            if (stage != WorkflowStage.QA_TEST_EXECUTION) {
                throw new IllegalArgumentException("只有 QA_TEST_EXECUTION 可以提交 DEFECT_FOUND");
            }
            return WorkflowSignal.QA_DEFECT_FOUND;
        }
        return WorkflowSignal.STAGE_ACCEPTED;
    }

    /**
     * 判断当前阶段是否必须绑定真实 Test Runner 终态。
     *
     * @param stage 当前业务阶段
     * @return 代码、缺陷修复或 QA 执行阶段返回 true
     */
    private static boolean requiresTestEvidence(WorkflowStage stage) {
        return stage == WorkflowStage.CODE_IMPLEMENTATION
                || stage == WorkflowStage.DEFECT_FIX
                || stage == WorkflowStage.QA_TEST_EXECUTION;
    }

    /**
     * 根据冻结 policy 与确定风险阈值决定是否插入阻断式评审。
     *
     * @param policy 冻结评审策略
     * @param command 完成命令
     * @return 是否必须阻断
     */
    private static boolean requiresBlockingReview(
            ReviewPolicy policy,
            CompleteWorkItemCommand command
    ) {
        if (policy == ReviewPolicy.STRICT_BLOCKING) {
            return true;
        }
        return command.impactLevel() == WorkImpactLevel.HIGH
                || command.impactLevel() == WorkImpactLevel.CRITICAL
                || command.selfScore() != null && command.selfScore() < 0.70D
                || command.hardConstraints() != null && command.hardConstraints() < 0.80D;
    }

    /**
     * 创建与 policy 对应的单 Reviewer 或 Strict 双 Reviewer 轮次。
     *
     * @param state 当前 aggregate
     * @param execution 当前工作执行
     * @param reviewRoundId 新评审轮次标识
     * @param requirement 必要或建议属性
     * @param signal 批准后恢复的有限结论
     * @param now 创建时间
     * @return 新 ReviewRound
     */
    private ReviewRound newReviewRound(
            WorkflowRuntimeState state,
            WorkExecution execution,
            String reviewRoundId,
            ReviewRequirement requirement,
            WorkflowSignal signal,
            Instant now
    ) {
        Set<String> reviewers = state.reviewPolicy() == ReviewPolicy.STRICT_BLOCKING
                ? strictReviewers(execution.owner())
                : Set.of(singleReviewer(execution.owner()));
        return ReviewRound.create(
                reviewRoundId, state.projectId(), execution.executionId(), execution.stage(),
                state.reviewPolicy(), requirement, signal, reviewers, now, now.plus(reviewTimeout)
        );
    }

    /**
     * 为非 Strict 工作确定唯一且不等于 Owner 的 Reviewer。
     *
     * @param owner 当前 Owner
     * @return 确定 Reviewer
     */
    private static String singleReviewer(String owner) {
        return switch (owner) {
            case "pm" -> "rd";
            case "rd" -> "qa";
            case "qa" -> "pm";
            default -> throw new IllegalArgumentException("未知工作 Owner: " + owner);
        };
    }

    /**
     * 为 Strict 工作返回除当前 Owner 外的两个确定 Reviewer。
     *
     * @param owner 当前 Owner
     * @return 双 Reviewer 集合
     */
    private static Set<String> strictReviewers(String owner) {
        return switch (owner) {
            case "pm" -> Set.of("rd", "qa");
            case "rd" -> Set.of("pm", "qa");
            case "qa" -> Set.of("pm", "rd");
            default -> throw new IllegalArgumentException("未知工作 Owner: " + owner);
        };
    }

    /**
     * 添加单条工作流事件 outbox。
     *
     * @param state 当前 aggregate
     * @param commandId 稳定 command ID
     * @param action 事件动作
     * @param execution 当前执行
     * @param now 创建时间
     * @return 包含事件动作的 aggregate
     */
    private static WorkflowRuntimeState enqueueEvent(
            WorkflowRuntimeState state,
            WorkflowCommandId commandId,
            String action,
            WorkExecution execution,
            Instant now
    ) {
        OutboxAction event = OutboxAction.pending(
                actionId(commandId.value(), "event"), OutboxActionType.WORKFLOW_EVENT,
                state.projectId(), Map.of(
                        "action", action,
                        "execution_id", execution.executionId(),
                        "stage", execution.stage().name(),
                        "owner", execution.owner()
                ), now
        );
        return state.enqueue(event, now);
    }

    /**
     * 添加阶段迁移事件 outbox。
     *
     * @param state 当前 aggregate
     * @param actionPrefix 稳定 action ID 前缀
     * @param completed 已完成执行
     * @param transition Java 阶段迁移
     * @param now 创建时间
     * @return 包含事件动作的 aggregate
     */
    private static WorkflowRuntimeState enqueueTransitionEvent(
            WorkflowRuntimeState state,
            String actionPrefix,
            WorkExecution completed,
            WorkflowTransition transition,
            Instant now
    ) {
        OutboxAction event = OutboxAction.pending(
                actionId(actionPrefix, "transition"), OutboxActionType.WORKFLOW_EVENT,
                state.projectId(), Map.of(
                        "action", "workflow_transitioned",
                        "execution_id", completed.executionId(),
                        "from_stage", transition.from().name(),
                        "to_stage", transition.to().name()
                ), now
        );
        return state.enqueue(event, now);
    }

    /**
     * 为下一执行添加 mailbox assignment 与 Cron wake 两项原子 outbox。
     *
     * @param state 当前 aggregate
     * @param actionPrefix 稳定 action ID 前缀
     * @param next 下一工作执行
     * @param subject 固定任务主题
     * @param now 创建时间
     * @return 包含 assignment 和 wake 的 aggregate
     */
    private static WorkflowRuntimeState enqueueAssignment(
            WorkflowRuntimeState state,
            String actionPrefix,
            WorkExecution next,
            String subject,
            Instant now
    ) {
        OutboxAction mail = OutboxAction.pending(
                actionId(actionPrefix, "mail"), OutboxActionType.MAILBOX_MESSAGE,
                next.owner(), Map.of(
                        "message_type", "task_assign",
                        "project_id", state.projectId(),
                        "execution_id", next.executionId(),
                        "stage", next.stage().name(),
                        "subject", subject
                ), now
        );
        OutboxAction wake = OutboxAction.pending(
                actionId(actionPrefix, "wake"), OutboxActionType.CRON_WAKE,
                "team:" + next.owner(), Map.of(
                        "routing_key", "team:" + next.owner(),
                        "project_id", state.projectId(),
                        "execution_id", next.executionId()
                ), now
        );
        return state.enqueue(mail, now).enqueue(wake, now);
    }

    /**
     * 为评审轮次中的每个 Reviewer 添加唯一请求 outbox。
     *
     * @param state 当前 aggregate
     * @param commandId 稳定 command ID
     * @param review 评审轮次
     * @param now 创建时间
     * @return 包含评审请求的 aggregate
     */
    private static WorkflowRuntimeState enqueueReviewRequests(
            WorkflowRuntimeState state,
            WorkflowCommandId commandId,
            ReviewRound review,
            Instant now
    ) {
        WorkflowRuntimeState changed = state;
        for (String reviewer : review.expectedReviewers()) {
            OutboxAction request = OutboxAction.pending(
                    actionId(commandId.value(), "review-" + reviewer),
                    OutboxActionType.MAILBOX_MESSAGE,
                    reviewer,
                    Map.of(
                            "message_type", "review_request",
                            "project_id", state.projectId(),
                            "review_round_id", review.reviewRoundId(),
                            "execution_id", review.workExecutionId(),
                            "stage", review.stage().name()
                    ),
                    now
            );
            changed = changed.enqueue(request, now);
        }
        return changed;
    }

    /**
     * 为同一 execution 的评审修订添加事件、assignment 和 wake。
     *
     * @param state 当前 aggregate
     * @param commandId 稳定评审 command ID
     * @param revision PENDING 修订执行
     * @param now 创建时间
     * @return 包含修订投影的 aggregate
     */
    private static WorkflowRuntimeState enqueueRevision(
            WorkflowRuntimeState state,
            WorkflowCommandId commandId,
            WorkExecution revision,
            Instant now
    ) {
        WorkflowRuntimeState changed = enqueueEvent(
                state, commandId, "review_changes_requested", revision, now
        );
        return enqueueAssignment(changed, commandId.value(), revision, "评审意见修订", now);
    }

    /**
     * 构造长度受限且可重复计算的 outbox action ID。
     *
     * @param prefix command 或评审命令前缀
     * @param suffix 动作类型后缀
     * @return 稳定 action ID
     */
    private static OutboxActionId actionId(String prefix, String suffix) {
        return new OutboxActionId(prefix + ":" + suffix);
    }

    /**
     * 从 aggregate 要求存在指定 execution。
     *
     * @param state 当前 aggregate
     * @param executionId execution 标识
     * @return 工作执行
     */
    private static WorkExecution requireExecution(
            WorkflowRuntimeState state,
            String executionId
    ) {
        return state.findExecution(requireText(executionId, "executionId"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "executionId 不属于当前项目: " + executionId
                ));
    }

    /**
     * 编码稳定 checkpoint 结果供重复 command 重建。
     *
     * @param result checkpoint 结果
     * @return 无自由文本的定界引用
     */
    private static String encode(CheckpointTransitionResult result) {
        return String.join(
                "|",
                result.executionId(),
                result.fromStage().name(),
                result.toStage().name(),
                result.nextExecutionId()
        );
    }

    /**
     * 从 processed command 结果引用重建 checkpoint 结果。
     *
     * @param reference 定界结果引用
     * @return 稳定 checkpoint 结果
     */
    private static CheckpointTransitionResult decodeCheckpoint(String reference) {
        String[] parts = reference.split("\\|", -1);
        if (parts.length != 4) {
            throw new IllegalStateException("checkpoint 结果引用格式损坏");
        }
        return new CheckpointTransitionResult(
                parts[0], WorkflowStage.valueOf(parts[1]),
                WorkflowStage.valueOf(parts[2]), parts[3]
        );
    }

    /**
     * 编码稳定完成结果供重复 command 重建。
     *
     * @param result 完成结果
     * @return 无自由文本的定界引用
     */
    private static String encode(CompleteWorkItemResult result) {
        return String.join(
                "|",
                result.executionId(),
                result.workflowStage().name(),
                result.nextExecutionId(),
                result.reviewRoundId(),
                result.detailCode()
        );
    }

    /**
     * 从 processed command 结果引用重建完成结果。
     *
     * @param outcomeCode 有限结果代码
     * @param reference 定界结果引用
     * @return 稳定完成结果
     */
    private static CompleteWorkItemResult decodeCompletion(String outcomeCode, String reference) {
        String[] parts = reference.split("\\|", -1);
        if (parts.length != 5) {
            throw new IllegalStateException("完成结果引用格式损坏");
        }
        return new CompleteWorkItemResult(
                parts[0], WorkCompletionOutcome.valueOf(outcomeCode),
                WorkflowStage.valueOf(parts[1]), parts[2], parts[3], parts[4]
        );
    }

    /**
     * 编码稳定评审结果供重复 command 重建。
     *
     * @param result 评审结果
     * @return 无自由文本的定界引用
     */
    private static String encode(ReviewRoundResult result) {
        return String.join(
                "|",
                result.reviewRoundId(),
                result.reviewStatus().name(),
                result.executionId(),
                result.executionStatus().name(),
                result.nextExecutionId()
        );
    }

    /**
     * 从 processed command 结果引用重建评审结果。
     *
     * @param reference 定界结果引用
     * @return 稳定评审结果
     */
    private static ReviewRoundResult decodeReview(String reference) {
        String[] parts = reference.split("\\|", -1);
        if (parts.length != 5) {
            throw new IllegalStateException("评审结果引用格式损坏");
        }
        return new ReviewRoundResult(
                parts[0], ReviewRoundStatus.valueOf(parts[1]), parts[2],
                WorkExecutionStatus.valueOf(parts[3]), parts[4]
        );
    }

    /**
     * 要求字符串非空并去除边界空白。
     *
     * @param value 待校验值
     * @param field 字段名称
     * @return 归一化非空字符串
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    /**
     * 保存完成命令产生的新 aggregate 与稳定结果。
     *
     * @param state 新 aggregate
     * @param result 稳定完成结果
     */
    private record CompletionMutation(
            /** 新 aggregate。 */ WorkflowRuntimeState state,
            /** 稳定完成结果。 */ CompleteWorkItemResult result
    ) {
    }

    /**
     * 保存 checkpoint 命令产生的新 aggregate 与稳定结果。
     *
     * @param state 新 aggregate
     * @param result 稳定 checkpoint 结果
     */
    private record CheckpointMutation(
            /** 新 aggregate。 */ WorkflowRuntimeState state,
            /** 稳定 checkpoint 结果。 */ CheckpointTransitionResult result
    ) {
    }

    /**
     * 保存评审命令产生的新 aggregate 与稳定结果。
     *
     * @param state 新 aggregate
     * @param result 稳定评审结果
     */
    private record ReviewMutation(
            /** 新 aggregate。 */ WorkflowRuntimeState state,
            /** 稳定评审结果。 */ ReviewRoundResult result
    ) {
    }

    /**
     * 保存阶段推进后的 aggregate、权威阶段和下一执行标识。
     *
     * @param state 推进后 aggregate
     * @param stage 推进后阶段
     * @param nextExecutionId 下一执行标识
     */
    private record AdvanceMutation(
            /** 推进后 aggregate。 */ WorkflowRuntimeState state,
            /** 推进后权威阶段。 */ WorkflowStage stage,
            /** 下一执行标识，可为空。 */ String nextExecutionId
    ) {
    }

    /**
     * 保存测试证据绑定后的执行和可选短路结果。
     *
     * @param execution 绑定证据后的执行
     * @param outcome 业务失败或基础设施失败短路结果
     * @param detailCode 有界详情代码
     */
    private record TestDecision(
            /** 绑定证据后的执行。 */ WorkExecution execution,
            /** 可选短路结果，为空时继续正常协调。 */ WorkCompletionOutcome outcome,
            /** 有界详情代码。 */ String detailCode
    ) {
    }
}
