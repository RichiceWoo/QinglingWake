package cn.org.chris.wake.domain.workflow;

import java.time.Instant;
import java.util.Objects;

/**
 * 保存一个项目阶段中唯一可执行工作项及其运行时关联标识。
 *
 * @param executionId 稳定工作执行标识，重试和 continuation 不得更换
 * @param projectId 所属项目标识
 * @param stage 当前业务阶段
 * @param owner 当前阶段唯一负责角色
 * @param profile 项目创建时冻结的工作流档位
 * @param status 当前有限状态
 * @param attempt 同一 execution 的执行尝试序号
 * @param createdAt 创建时间
 * @param updatedAt 最近状态或关联变更时间
 * @param lastProgressAt 最近一次明确业务证据时间
 * @param assignmentId 当前 mailbox assignment 标识
 * @param dispatchId 当前串行 dispatch 标识
 * @param agentSessionId 当前角色会话标识
 * @param childTaskId 当前关联的 AgentScope 子任务标识
 * @param testExecutionId 当前关联的 Test Runner execution 标识
 * @param reviewRoundId 最近一次关联的评审轮次标识
 * @param failureType 有界且脱敏的最近失败类型
 */
public record WorkExecution(
        /** 稳定工作执行标识。 */ String executionId,
        /** 所属项目标识。 */ String projectId,
        /** 当前业务阶段。 */ WorkflowStage stage,
        /** 当前阶段唯一负责角色。 */ String owner,
        /** 项目创建时冻结的工作流档位。 */ WorkflowProfile profile,
        /** 当前有限状态。 */ WorkExecutionStatus status,
        /** 同一执行的当前尝试序号。 */ int attempt,
        /** 工作执行创建时间。 */ Instant createdAt,
        /** 最近状态或关联变更时间。 */ Instant updatedAt,
        /** 最近一次明确业务证据时间。 */ Instant lastProgressAt,
        /** 当前 mailbox assignment 标识。 */ String assignmentId,
        /** 当前串行 dispatch 标识。 */ String dispatchId,
        /** 当前角色会话标识。 */ String agentSessionId,
        /** 当前 AgentScope 子任务标识。 */ String childTaskId,
        /** 当前 Test Runner execution 标识。 */ String testExecutionId,
        /** 最近一次评审轮次标识。 */ String reviewRoundId,
        /** 最近失败类型，不包含异常正文或凭据。 */ String failureType
) {

    /**
     * 归一化可选关联字段并校验工作执行的不变量。
     */
    public WorkExecution {
        executionId = requireText(executionId, "executionId");
        projectId = requireText(projectId, "projectId");
        owner = requireText(owner, "owner");
        Objects.requireNonNull(stage, "stage 不能为空");
        Objects.requireNonNull(profile, "profile 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        Objects.requireNonNull(lastProgressAt, "lastProgressAt 不能为空");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 必须从 1 开始");
        }
        if (updatedAt.isBefore(createdAt) || lastProgressAt.isBefore(createdAt)
                || lastProgressAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("工作执行时间必须单调且 lastProgressAt 不晚于 updatedAt");
        }
        if (stage == WorkflowStage.COMPLETED || stage == WorkflowStage.CANCELLED) {
            throw new IllegalArgumentException("终态项目阶段不能创建 WorkExecution");
        }
        if (profile == WorkflowProfile.SIMPLE
                && (stage == WorkflowStage.PRODUCT_DESIGN
                || stage == WorkflowStage.TECH_DESIGN
                || stage == WorkflowStage.QA_TEST_DESIGN)) {
            throw new IllegalArgumentException("SIMPLE 档位不允许独立设计阶段: " + stage);
        }
        assignmentId = normalize(assignmentId);
        dispatchId = normalize(dispatchId);
        agentSessionId = normalize(agentSessionId);
        childTaskId = normalize(childTaskId);
        testExecutionId = normalize(testExecutionId);
        reviewRoundId = normalize(reviewRoundId);
        failureType = normalize(failureType);
        if (status == WorkExecutionStatus.CLAIMED && assignmentId.isBlank()) {
            throw new IllegalArgumentException("CLAIMED 状态必须关联 assignmentId");
        }
        if (status == WorkExecutionStatus.RUNNING
                && (assignmentId.isBlank() || dispatchId.isBlank() || agentSessionId.isBlank())) {
            throw new IllegalArgumentException("RUNNING 状态必须关联 assignment、dispatch 和 agent session");
        }
        if (status == WorkExecutionStatus.WAITING_REVIEW && reviewRoundId.isBlank()) {
            throw new IllegalArgumentException("WAITING_REVIEW 状态必须关联 reviewRoundId");
        }
        if ((status == WorkExecutionStatus.RECOVERING || status == WorkExecutionStatus.FAILED
                || status == WorkExecutionStatus.BLOCKED) && failureType.isBlank()) {
            throw new IllegalArgumentException(status + " 状态必须记录 failureType");
        }
    }

    /**
     * 创建一个等待唯一 Owner 领取的工作执行。
     *
     * @param executionId 稳定执行标识
     * @param projectId 项目标识
     * @param stage 业务阶段
     * @param owner 负责角色
     * @param profile 冻结档位
     * @param now 创建时间
     * @return 初始 PENDING 工作执行
     */
    public static WorkExecution create(
            String executionId,
            String projectId,
            WorkflowStage stage,
            String owner,
            WorkflowProfile profile,
            Instant now
    ) {
        return new WorkExecution(
                executionId, projectId, stage, owner, profile, WorkExecutionStatus.PENDING, 1,
                now, now, now, "", "", "", "", "", "", ""
        );
    }

    /**
     * 将待领取执行绑定到唯一 mailbox assignment；相同重复领取保持幂等。
     *
     * @param newAssignmentId assignment 标识
     * @param now 领取时间
     * @return 已领取执行
     */
    public WorkExecution claim(String newAssignmentId, Instant now) {
        String requiredAssignment = requireText(newAssignmentId, "assignmentId");
        if (status == WorkExecutionStatus.CLAIMED && assignmentId.equals(requiredAssignment)) {
            return this;
        }
        requireTransition(WorkExecutionStatus.CLAIMED);
        return copy(WorkExecutionStatus.CLAIMED, attempt, requireTime(now), lastProgressAt,
                requiredAssignment, "", "", "", testExecutionId, reviewRoundId, "");
    }

    /**
     * 启动唯一角色 dispatch；相同关联重复启动保持幂等。
     *
     * @param newDispatchId dispatch 标识
     * @param newAgentSessionId 角色会话标识
     * @param now 启动时间
     * @return 运行中的执行
     */
    public WorkExecution start(String newDispatchId, String newAgentSessionId, Instant now) {
        String requiredDispatch = requireText(newDispatchId, "dispatchId");
        String requiredSession = requireText(newAgentSessionId, "agentSessionId");
        if (status == WorkExecutionStatus.RUNNING
                && dispatchId.equals(requiredDispatch)
                && agentSessionId.equals(requiredSession)) {
            return this;
        }
        requireTransition(WorkExecutionStatus.RUNNING);
        return copy(WorkExecutionStatus.RUNNING, attempt, requireTime(now), lastProgressAt,
                assignmentId, requiredDispatch, requiredSession, childTaskId,
                testExecutionId, reviewRoundId, "");
    }

    /**
     * 关联当前 continuation 的子任务，但不把行政关联变化当成业务进度。
     *
     * @param newChildTaskId 子任务标识
     * @param now 关联时间
     * @return 保持 RUNNING 的同一执行
     */
    public WorkExecution linkChildTask(String newChildTaskId, Instant now) {
        requireStatus(WorkExecutionStatus.RUNNING);
        String requiredChild = requireText(newChildTaskId, "childTaskId");
        if (childTaskId.equals(requiredChild)) {
            return this;
        }
        return copy(status, attempt, requireTime(now), lastProgressAt, assignmentId, dispatchId,
                agentSessionId, requiredChild, testExecutionId, reviewRoundId, failureType);
    }

    /**
     * 关联真实 Test Runner execution，但不采信模型复制的测试终态。
     *
     * @param newTestExecutionId Test Runner execution 标识
     * @param now 关联时间
     * @return 保持 RUNNING 的同一执行
     */
    public WorkExecution linkTestExecution(String newTestExecutionId, Instant now) {
        requireStatus(WorkExecutionStatus.RUNNING);
        String requiredTest = requireText(newTestExecutionId, "testExecutionId");
        if (testExecutionId.equals(requiredTest)) {
            return this;
        }
        return copy(status, attempt, requireTime(now), lastProgressAt, assignmentId, dispatchId,
                agentSessionId, childTaskId, requiredTest, reviewRoundId, failureType);
    }

    /**
     * 仅在收到明确业务证据时刷新进度租约。
     *
     * @param evidenceAt 业务证据发生时间
     * @return 刷新进度后的同一执行
     */
    public WorkExecution recordProgress(Instant evidenceAt) {
        requireStatus(WorkExecutionStatus.RUNNING);
        Instant requiredEvidence = requireTime(evidenceAt);
        if (requiredEvidence.isBefore(lastProgressAt)) {
            throw new IllegalArgumentException("业务进度时间不能回退");
        }
        if (requiredEvidence.equals(lastProgressAt) && !requiredEvidence.isAfter(updatedAt)) {
            return this;
        }
        Instant nextUpdatedAt = requiredEvidence.isAfter(updatedAt) ? requiredEvidence : updatedAt;
        return copy(status, attempt, nextUpdatedAt, requiredEvidence, assignmentId, dispatchId,
                agentSessionId, childTaskId, testExecutionId, reviewRoundId, failureType);
    }

    /**
     * 将已完成产物交给阻断式评审，保持当前业务阶段不变。
     *
     * @param newReviewRoundId 评审轮次标识
     * @param now 创建评审时间
     * @return 等待评审的同一执行
     */
    public WorkExecution waitForReview(String newReviewRoundId, Instant now) {
        String requiredReview = requireText(newReviewRoundId, "reviewRoundId");
        if (status == WorkExecutionStatus.WAITING_REVIEW && reviewRoundId.equals(requiredReview)) {
            return this;
        }
        requireTransition(WorkExecutionStatus.WAITING_REVIEW);
        return copy(WorkExecutionStatus.WAITING_REVIEW, attempt, requireTime(now), lastProgressAt,
                assignmentId, dispatchId, agentSessionId, childTaskId,
                testExecutionId, requiredReview, "");
    }

    /**
     * 在阻断式评审批准后完成工作执行。
     *
     * @param completedReviewRoundId 已批准的评审轮次标识
     * @param now 完成时间
     * @return 成功终态执行
     */
    public WorkExecution approveReview(String completedReviewRoundId, Instant now) {
        requireReviewRound(completedReviewRoundId);
        requireTransition(WorkExecutionStatus.SUCCEEDED);
        return copy(WorkExecutionStatus.SUCCEEDED, attempt, requireTime(now), lastProgressAt,
                assignmentId, dispatchId, agentSessionId, childTaskId,
                testExecutionId, reviewRoundId, "");
    }

    /**
     * 在阻断式评审要求修改后复用原 execution 创建下一次尝试。
     *
     * @param completedReviewRoundId 已结束的评审轮次标识
     * @param now 修订尝试创建时间
     * @return 同一 execution ID 的 PENDING 修订执行
     */
    public WorkExecution reviseAfterReview(String completedReviewRoundId, Instant now) {
        requireReviewRound(completedReviewRoundId);
        requireTransition(WorkExecutionStatus.PENDING);
        return copy(WorkExecutionStatus.PENDING, attempt + 1, requireTime(now), lastProgressAt,
                "", "", "", "", "", reviewRoundId, "");
    }

    /**
     * 将首次基础设施故障记录为可恢复状态，不创建新的 execution。
     *
     * @param newFailureType 脱敏失败类型
     * @param now 故障识别时间
     * @return 恢复中的同一执行
     */
    public WorkExecution recover(String newFailureType, Instant now) {
        String requiredFailure = requireText(newFailureType, "failureType");
        if (status == WorkExecutionStatus.RECOVERING && failureType.equals(requiredFailure)) {
            return this;
        }
        requireTransition(WorkExecutionStatus.RECOVERING);
        return copy(WorkExecutionStatus.RECOVERING, attempt, requireTime(now), lastProgressAt,
                assignmentId, dispatchId, agentSessionId, childTaskId,
                testExecutionId, reviewRoundId, requiredFailure);
    }

    /**
     * 在有限恢复预算内复用 execution ID 开始下一次领取。
     *
     * @param now 恢复重试创建时间
     * @return 同一 execution ID 的 PENDING 重试执行
     */
    public WorkExecution retry(Instant now) {
        requireTransition(WorkExecutionStatus.PENDING);
        return copy(WorkExecutionStatus.PENDING, attempt + 1, requireTime(now), lastProgressAt,
                "", "", "", "", "", reviewRoundId, failureType);
    }

    /**
     * 不经过评审直接完成无需阻断评审的工作执行。
     *
     * @param now 完成时间
     * @return 成功终态执行
     */
    public WorkExecution succeed(Instant now) {
        if (status == WorkExecutionStatus.SUCCEEDED) {
            return this;
        }
        requireTransition(WorkExecutionStatus.SUCCEEDED);
        return copy(WorkExecutionStatus.SUCCEEDED, attempt, requireTime(now), lastProgressAt,
                assignmentId, dispatchId, agentSessionId, childTaskId,
                testExecutionId, reviewRoundId, "");
    }

    /**
     * 将不可恢复错误持久化为失败终态。
     *
     * @param newFailureType 脱敏失败类型
     * @param now 失败时间
     * @return 失败终态执行
     */
    public WorkExecution fail(String newFailureType, Instant now) {
        return terminate(WorkExecutionStatus.FAILED, newFailureType, now);
    }

    /**
     * 将恢复预算耗尽持久化为阻断终态。
     *
     * @param newFailureType 脱敏失败类型
     * @param now 阻断时间
     * @return 阻断终态执行
     */
    public WorkExecution block(String newFailureType, Instant now) {
        return terminate(WorkExecutionStatus.BLOCKED, newFailureType, now);
    }

    /**
     * 将显式关闭或 shutdown 持久化为取消终态。
     *
     * @param reason 脱敏取消原因
     * @param now 取消时间
     * @return 取消终态执行
     */
    public WorkExecution cancel(String reason, Instant now) {
        String normalizedReason = requireText(reason, "reason");
        if (status == WorkExecutionStatus.CANCELLED && failureType.equals(normalizedReason)) {
            return this;
        }
        requireTransition(WorkExecutionStatus.CANCELLED);
        return copy(WorkExecutionStatus.CANCELLED, attempt, requireTime(now), lastProgressAt,
                assignmentId, dispatchId, agentSessionId, childTaskId,
                testExecutionId, reviewRoundId, normalizedReason);
    }

    /**
     * 创建失败或阻断终态，并使相同重复命令保持幂等。
     *
     * @param target 失败类目标状态
     * @param newFailureType 脱敏失败类型
     * @param now 终止时间
     * @return 指定失败类终态执行
     */
    private WorkExecution terminate(
            WorkExecutionStatus target,
            String newFailureType,
            Instant now
    ) {
        String requiredFailure = requireText(newFailureType, "failureType");
        if (status == target && failureType.equals(requiredFailure)) {
            return this;
        }
        requireTransition(target);
        return copy(target, attempt, requireTime(now), lastProgressAt, assignmentId, dispatchId,
                agentSessionId, childTaskId, testExecutionId, reviewRoundId, requiredFailure);
    }

    /**
     * 校验当前状态必须等于调用方所需状态。
     *
     * @param expected 调用所需状态
     */
    private void requireStatus(WorkExecutionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("状态 " + status + " 不允许执行仅限 " + expected + " 的操作");
        }
    }

    /**
     * 校验目标状态位于固定转换表中。
     *
     * @param target 目标状态
     */
    private void requireTransition(WorkExecutionStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("WorkExecution 不允许从 " + status + " 转换到 " + target);
        }
    }

    /**
     * 校验评审回包属于当前等待的评审轮次。
     *
     * @param completedReviewRoundId 回包携带的评审轮次标识
     */
    private void requireReviewRound(String completedReviewRoundId) {
        requireStatus(WorkExecutionStatus.WAITING_REVIEW);
        String requiredReview = requireText(completedReviewRoundId, "reviewRoundId");
        if (!reviewRoundId.equals(requiredReview)) {
            throw new IllegalArgumentException("评审回包不属于当前 reviewRoundId");
        }
    }

    /**
     * 在保持领域身份与阶段不变的前提下创建下一状态快照。
     *
     * @param nextStatus 下一状态
     * @param nextAttempt 下一尝试序号
     * @param nextUpdatedAt 下一更新时间
     * @param nextProgressAt 下一业务进度时间
     * @param nextAssignmentId 下一 assignment 标识
     * @param nextDispatchId 下一 dispatch 标识
     * @param nextAgentSessionId 下一会话标识
     * @param nextChildTaskId 下一子任务标识
     * @param nextTestExecutionId 下一测试执行标识
     * @param nextReviewRoundId 下一评审轮次标识
     * @param nextFailureType 下一失败类型
     * @return 不可变的下一状态快照
     */
    private WorkExecution copy(
            WorkExecutionStatus nextStatus,
            int nextAttempt,
            Instant nextUpdatedAt,
            Instant nextProgressAt,
            String nextAssignmentId,
            String nextDispatchId,
            String nextAgentSessionId,
            String nextChildTaskId,
            String nextTestExecutionId,
            String nextReviewRoundId,
            String nextFailureType
    ) {
        if (nextUpdatedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("updatedAt 不能回退");
        }
        return new WorkExecution(
                executionId, projectId, stage, owner, profile, nextStatus, nextAttempt,
                createdAt, nextUpdatedAt, nextProgressAt, nextAssignmentId, nextDispatchId,
                nextAgentSessionId, nextChildTaskId, nextTestExecutionId,
                nextReviewRoundId, nextFailureType
        );
    }

    /**
     * 要求字符串非空并去除边界空白。
     *
     * @param value 待校验值
     * @param field 字段名称
     * @return 归一化后的非空字符串
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    /**
     * 将可选字符串归一化为空串或无边界空白的值。
     *
     * @param value 可选字符串
     * @return 归一化值
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 校验操作时间非空。
     *
     * @param value 操作时间
     * @return 非空时间
     */
    private static Instant requireTime(Instant value) {
        return Objects.requireNonNull(value, "操作时间不能为空");
    }
}
