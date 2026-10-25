package cn.org.chris.wake.domain.workflow;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 保存一次确定性评审的预期 Reviewer、截止时间和已收结论。
 *
 * @param reviewRoundId 稳定评审轮次标识
 * @param projectId 所属项目标识
 * @param workExecutionId 所属工作执行标识
 * @param stage 所属业务阶段
 * @param policy 创建本轮评审的固定策略
 * @param requirement 本轮是否阻断主流程
 * @param completionSignal 评审批准后恢复的原始有限完成结论
 * @param expectedReviewers 固定预期 Reviewer 集合
 * @param verdicts 已接收且按 Reviewer 去重的结论
 * @param status 当前评审状态
 * @param createdAt 创建时间
 * @param deadline 明确业务截止时间
 * @param updatedAt 最近状态更新时间
 * @param failureReason 超时或取消时的有界原因
 */
public record ReviewRound(
        /** 稳定评审轮次标识。 */ String reviewRoundId,
        /** 所属项目标识。 */ String projectId,
        /** 所属工作执行标识。 */ String workExecutionId,
        /** 所属业务阶段。 */ WorkflowStage stage,
        /** 创建本轮评审的固定策略。 */ ReviewPolicy policy,
        /** 本轮必要或建议属性。 */ ReviewRequirement requirement,
        /** 评审批准后恢复的原始有限完成结论。 */ WorkflowSignal completionSignal,
        /** 固定预期 Reviewer 集合。 */ Set<String> expectedReviewers,
        /** 已按 Reviewer 去重的有限结论。 */ Map<String, ReviewVerdict> verdicts,
        /** 当前评审有限状态。 */ ReviewRoundStatus status,
        /** 评审创建时间。 */ Instant createdAt,
        /** 评审明确截止时间。 */ Instant deadline,
        /** 最近状态更新时间。 */ Instant updatedAt,
        /** 超时或取消原因，不包含自由文本正文。 */ String failureReason
) {

    /**
     * 归一化集合顺序并校验评审策略、屏障和时间不变量。
     */
    public ReviewRound {
        reviewRoundId = requireText(reviewRoundId, "reviewRoundId");
        projectId = requireText(projectId, "projectId");
        workExecutionId = requireText(workExecutionId, "workExecutionId");
        Objects.requireNonNull(stage, "stage 不能为空");
        Objects.requireNonNull(policy, "policy 不能为空");
        Objects.requireNonNull(requirement, "requirement 不能为空");
        Objects.requireNonNull(completionSignal, "completionSignal 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(deadline, "deadline 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        if (!deadline.isAfter(createdAt)) {
            throw new IllegalArgumentException("deadline 必须晚于 createdAt");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
        }
        expectedReviewers = immutableReviewers(expectedReviewers);
        verdicts = immutableVerdicts(verdicts, expectedReviewers);
        failureReason = failureReason == null ? "" : failureReason.trim();
        validatePolicy(policy, requirement, expectedReviewers.size());
        validateStatus(status, expectedReviewers, verdicts, failureReason);
    }

    /**
     * 创建尚未开始的确定性评审轮次。
     *
     * @param reviewRoundId 评审轮次标识
     * @param projectId 项目标识
     * @param workExecutionId 工作执行标识
     * @param stage 业务阶段
     * @param policy 固定评审策略
     * @param requirement 必要或建议属性
     * @param expectedReviewers 固定 Reviewer 集合
     * @param createdAt 创建时间
     * @param deadline 截止时间
     * @return PENDING 评审轮次
     */
    public static ReviewRound create(
            String reviewRoundId,
            String projectId,
            String workExecutionId,
            WorkflowStage stage,
            ReviewPolicy policy,
            ReviewRequirement requirement,
            Set<String> expectedReviewers,
            Instant createdAt,
            Instant deadline
    ) {
        return create(
                reviewRoundId, projectId, workExecutionId, stage, policy, requirement,
                WorkflowSignal.STAGE_ACCEPTED, expectedReviewers, createdAt, deadline
        );
    }

    /**
     * 使用显式原始完成结论创建尚未开始的确定性评审轮次。
     *
     * @param reviewRoundId 评审轮次标识
     * @param projectId 项目标识
     * @param workExecutionId 工作执行标识
     * @param stage 业务阶段
     * @param policy 固定评审策略
     * @param requirement 必要或建议属性
     * @param completionSignal 评审批准后恢复的有限结论
     * @param expectedReviewers 固定 Reviewer 集合
     * @param createdAt 创建时间
     * @param deadline 截止时间
     * @return PENDING 评审轮次
     */
    public static ReviewRound create(
            String reviewRoundId,
            String projectId,
            String workExecutionId,
            WorkflowStage stage,
            ReviewPolicy policy,
            ReviewRequirement requirement,
            WorkflowSignal completionSignal,
            Set<String> expectedReviewers,
            Instant createdAt,
            Instant deadline
    ) {
        return new ReviewRound(
                reviewRoundId, projectId, workExecutionId, stage, policy, requirement,
                completionSignal,
                expectedReviewers, Map.of(), ReviewRoundStatus.PENDING,
                createdAt, deadline, createdAt, ""
        );
    }

    /**
     * 标记 Reviewer 已开始处理；重复开始保持幂等。
     *
     * @param now 开始时间
     * @return IN_PROGRESS 评审轮次
     */
    public ReviewRound start(Instant now) {
        if (status == ReviewRoundStatus.IN_PROGRESS) {
            return this;
        }
        requireOpen(ReviewRoundStatus.PENDING);
        Instant requiredNow = requireTime(now);
        requireBeforeDeadline(requiredNow);
        return copy(verdicts, ReviewRoundStatus.IN_PROGRESS, requiredNow, "");
    }

    /**
     * 接收一个 Reviewer 的有限结论并按固定屏障计算终态。
     *
     * @param reviewer Reviewer 角色
     * @param verdict 有限结论
     * @param now 提交时间
     * @return 更新后的评审轮次
     */
    public ReviewRound submit(String reviewer, ReviewVerdict verdict, Instant now) {
        String requiredReviewer = requireText(reviewer, "reviewer");
        Objects.requireNonNull(verdict, "verdict 不能为空");
        if (!expectedReviewers.contains(requiredReviewer)) {
            throw new IllegalArgumentException("Reviewer 不属于当前评审轮次: " + requiredReviewer);
        }
        ReviewVerdict existing = verdicts.get(requiredReviewer);
        if (existing != null) {
            if (existing == verdict) {
                return this;
            }
            throw new IllegalStateException("同一 Reviewer 不能修改已提交结论");
        }
        if (status.terminal()) {
            throw new IllegalStateException("终态 ReviewRound 不接受新结论: " + status);
        }
        Instant requiredNow = requireTime(now);
        requireBeforeDeadline(requiredNow);
        Map<String, ReviewVerdict> nextVerdicts = new TreeMap<>(verdicts);
        nextVerdicts.put(requiredReviewer, verdict);
        ReviewRoundStatus nextStatus;
        if (verdict == ReviewVerdict.CHANGES_REQUESTED) {
            nextStatus = ReviewRoundStatus.CHANGES_REQUESTED;
        } else if (nextVerdicts.size() == expectedReviewers.size()) {
            nextStatus = ReviewRoundStatus.APPROVED;
        } else {
            nextStatus = ReviewRoundStatus.IN_PROGRESS;
        }
        return copy(nextVerdicts, nextStatus, requiredNow, "");
    }

    /**
     * 在业务截止时间到达后形成明确超时终态。
     *
     * @param now 当前时间
     * @return EXPIRED 评审轮次
     */
    public ReviewRound expire(Instant now) {
        if (status == ReviewRoundStatus.EXPIRED) {
            return this;
        }
        if (status.terminal()) {
            throw new IllegalStateException("终态 ReviewRound 不能改为 EXPIRED: " + status);
        }
        Instant requiredNow = requireTime(now);
        if (requiredNow.isBefore(deadline)) {
            throw new IllegalStateException("评审 deadline 尚未到达");
        }
        return copy(verdicts, ReviewRoundStatus.EXPIRED, requiredNow, "REVIEW_DEADLINE_EXCEEDED");
    }

    /**
     * 随所属工作执行关闭评审，并记录有界取消原因。
     *
     * @param reason 取消原因类型
     * @param now 取消时间
     * @return CANCELLED 评审轮次
     */
    public ReviewRound cancel(String reason, Instant now) {
        String requiredReason = requireText(reason, "reason");
        if (status == ReviewRoundStatus.CANCELLED && failureReason.equals(requiredReason)) {
            return this;
        }
        if (status.terminal()) {
            throw new IllegalStateException("终态 ReviewRound 不能取消: " + status);
        }
        return copy(verdicts, ReviewRoundStatus.CANCELLED, requireTime(now), requiredReason);
    }

    /**
     * 判断本轮尚未收敛时是否必须阻断主流程。
     *
     * @return 必要评审且尚未终态时返回 true
     */
    public boolean blocksWorkflow() {
        return requirement == ReviewRequirement.MANDATORY && !status.terminal();
    }

    /**
     * 校验开始操作只发生在指定开放状态。
     *
     * @param expected 允许开始的状态
     */
    private void requireOpen(ReviewRoundStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("ReviewRound 状态 " + status + " 不允许当前操作");
        }
    }

    /**
     * 校验 Reviewer 回包发生在明确截止时间之前。
     *
     * @param now 回包时间
     */
    private void requireBeforeDeadline(Instant now) {
        if (!now.isBefore(deadline)) {
            throw new IllegalStateException("评审已到 deadline，必须先形成 EXPIRED 终态");
        }
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("评审时间不能回退");
        }
    }

    /**
     * 创建保持领域身份不变的下一评审快照。
     *
     * @param nextVerdicts 下一结论集合
     * @param nextStatus 下一状态
     * @param nextUpdatedAt 下一更新时间
     * @param nextFailureReason 下一失败原因
     * @return 不可变的下一评审快照
     */
    private ReviewRound copy(
            Map<String, ReviewVerdict> nextVerdicts,
            ReviewRoundStatus nextStatus,
            Instant nextUpdatedAt,
            String nextFailureReason
    ) {
        if (nextUpdatedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("updatedAt 不能回退");
        }
        return new ReviewRound(
                reviewRoundId, projectId, workExecutionId, stage, policy, requirement,
                completionSignal,
                expectedReviewers, nextVerdicts, nextStatus,
                createdAt, deadline, nextUpdatedAt, nextFailureReason
        );
    }

    /**
     * 校验 policy、必要性和 Reviewer 数量之间的固定组合。
     *
     * @param policy 固定评审策略
     * @param requirement 必要或建议属性
     * @param reviewerCount Reviewer 数量
     */
    private static void validatePolicy(
            ReviewPolicy policy,
            ReviewRequirement requirement,
            int reviewerCount
    ) {
        if (reviewerCount < 1) {
            throw new IllegalArgumentException("expectedReviewers 不能为空");
        }
        if (policy == ReviewPolicy.STRICT_BLOCKING) {
            if (requirement != ReviewRequirement.MANDATORY || reviewerCount != 2) {
                throw new IllegalArgumentException("STRICT_BLOCKING 必须是双 Reviewer 必要评审");
            }
            return;
        }
        if (reviewerCount != 1) {
            throw new IllegalArgumentException("非 STRICT 评审最多且必须有一个 Reviewer");
        }
        if (policy == ReviewPolicy.REQUIRED_ONLY && requirement != ReviewRequirement.MANDATORY) {
            throw new IllegalArgumentException("REQUIRED_ONLY 只能创建必要评审");
        }
        // ADVISORY_AUDIT 仍阻断必要风险，同时允许创建不阻断主流程的审计轮次。
    }

    /**
     * 校验状态与已收结论、失败原因一致。
     *
     * @param status 当前状态
     * @param expectedReviewers 预期 Reviewer 集合
     * @param verdicts 已收结论
     * @param failureReason 失败原因
     */
    private static void validateStatus(
            ReviewRoundStatus status,
            Set<String> expectedReviewers,
            Map<String, ReviewVerdict> verdicts,
            String failureReason
    ) {
        if (status == ReviewRoundStatus.PENDING && !verdicts.isEmpty()) {
            throw new IllegalArgumentException("PENDING 状态不能已有 verdict");
        }
        if (status == ReviewRoundStatus.IN_PROGRESS
                && (verdicts.containsValue(ReviewVerdict.CHANGES_REQUESTED)
                || verdicts.size() == expectedReviewers.size())) {
            throw new IllegalArgumentException("IN_PROGRESS 状态必须仍有 Reviewer 未提交批准结论");
        }
        if (status == ReviewRoundStatus.APPROVED
                && (verdicts.size() != expectedReviewers.size()
                || verdicts.containsValue(ReviewVerdict.CHANGES_REQUESTED))) {
            throw new IllegalArgumentException("APPROVED 状态必须收到全部批准结论");
        }
        if (status == ReviewRoundStatus.CHANGES_REQUESTED
                && !verdicts.containsValue(ReviewVerdict.CHANGES_REQUESTED)) {
            throw new IllegalArgumentException("CHANGES_REQUESTED 状态必须包含修订结论");
        }
        if ((status == ReviewRoundStatus.EXPIRED || status == ReviewRoundStatus.CANCELLED)
                && failureReason.isBlank()) {
            throw new IllegalArgumentException(status + " 状态必须记录 failureReason");
        }
        if (status != ReviewRoundStatus.EXPIRED && status != ReviewRoundStatus.CANCELLED
                && !failureReason.isBlank()) {
            throw new IllegalArgumentException(status + " 状态不能记录 failureReason");
        }
    }

    /**
     * 归一化并冻结 Reviewer 集合，保证序列化顺序稳定。
     *
     * @param reviewers 原始 Reviewer 集合
     * @return 不可变有序集合
     */
    private static Set<String> immutableReviewers(Set<String> reviewers) {
        Objects.requireNonNull(reviewers, "expectedReviewers 不能为空");
        TreeSet<String> normalized = new TreeSet<>();
        for (String reviewer : reviewers) {
            normalized.add(requireText(reviewer, "reviewer"));
        }
        return Collections.unmodifiableSet(normalized);
    }

    /**
     * 归一化并冻结 verdict Map，同时拒绝未知 Reviewer。
     *
     * @param source 原始结论 Map
     * @param expectedReviewers 固定 Reviewer 集合
     * @return 不可变有序结论 Map
     */
    private static Map<String, ReviewVerdict> immutableVerdicts(
            Map<String, ReviewVerdict> source,
            Set<String> expectedReviewers
    ) {
        Objects.requireNonNull(source, "verdicts 不能为空");
        TreeMap<String, ReviewVerdict> normalized = new TreeMap<>();
        source.forEach((reviewer, verdict) -> {
            String requiredReviewer = requireText(reviewer, "reviewer");
            if (!expectedReviewers.contains(requiredReviewer)) {
                throw new IllegalArgumentException("verdict 包含未知 Reviewer: " + requiredReviewer);
            }
            normalized.put(requiredReviewer, Objects.requireNonNull(verdict, "verdict 不能为空"));
        });
        return Collections.unmodifiableMap(normalized);
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
     * 校验操作时间非空。
     *
     * @param value 操作时间
     * @return 非空时间
     */
    private static Instant requireTime(Instant value) {
        return Objects.requireNonNull(value, "操作时间不能为空");
    }
}
