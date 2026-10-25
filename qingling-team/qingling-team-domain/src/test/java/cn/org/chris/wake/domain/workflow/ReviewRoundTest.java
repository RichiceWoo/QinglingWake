package cn.org.chris.wake.domain.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证必要评审、建议评审和 Strict 双 Reviewer 屏障。
 */
class ReviewRoundTest {

    /** 领域测试使用的固定起点。 */
    private static final Instant START = Instant.parse("2026-10-25T00:00:00Z");

    /**
     * REQUIRED_ONLY 必要评审必须阻断到唯一 Reviewer 给出明确结论。
     */
    @Test
    void shouldBlockMandatoryReviewUntilReviewerApproves() {
        ReviewRound pending = requiredRound();
        ReviewRound inProgress = pending.start(START.plusSeconds(1));
        ReviewRound approved = inProgress.submit("qa", ReviewVerdict.APPROVED, START.plusSeconds(2));

        assertThat(pending.blocksWorkflow()).isTrue();
        assertThat(inProgress.status()).isEqualTo(ReviewRoundStatus.IN_PROGRESS);
        assertThat(approved.status()).isEqualTo(ReviewRoundStatus.APPROVED);
        assertThat(approved.blocksWorkflow()).isFalse();
        assertThat(approved.verdicts()).containsEntry("qa", ReviewVerdict.APPROVED);
    }

    /**
     * STRICT_BLOCKING 必须等待两个 Reviewer 全部批准才能收敛。
     */
    @Test
    void shouldWaitForBothStrictReviewers() {
        ReviewRound strict = ReviewRound.create(
                "review-strict", "project-1", "work-1", WorkflowStage.TECH_DESIGN,
                ReviewPolicy.STRICT_BLOCKING, ReviewRequirement.MANDATORY,
                Set.of("pm", "qa"), START, START.plusSeconds(60)
        );

        ReviewRound first = strict.submit("qa", ReviewVerdict.APPROVED, START.plusSeconds(1));
        ReviewRound complete = first.submit("pm", ReviewVerdict.APPROVED, START.plusSeconds(2));

        assertThat(first.status()).isEqualTo(ReviewRoundStatus.IN_PROGRESS);
        assertThat(first.blocksWorkflow()).isTrue();
        assertThat(complete.status()).isEqualTo(ReviewRoundStatus.APPROVED);
        assertThat(complete.verdicts()).hasSize(2);
    }

    /**
     * 任一 Strict Reviewer 要求修订时应立即形成确定终态。
     */
    @Test
    void shouldFinishStrictBarrierOnFirstRequestedChange() {
        ReviewRound strict = ReviewRound.create(
                "review-strict", "project-1", "work-1", WorkflowStage.TECH_DESIGN,
                ReviewPolicy.STRICT_BLOCKING, ReviewRequirement.MANDATORY,
                Set.of("pm", "qa"), START, START.plusSeconds(60)
        );

        ReviewRound rejected = strict.submit(
                "qa", ReviewVerdict.CHANGES_REQUESTED, START.plusSeconds(1)
        );

        assertThat(rejected.status()).isEqualTo(ReviewRoundStatus.CHANGES_REQUESTED);
        assertThat(rejected.blocksWorkflow()).isFalse();
        assertThatThrownBy(() -> rejected.submit("pm", ReviewVerdict.APPROVED, START.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");
    }

    /**
     * ADVISORY_AUDIT 从创建开始就不得持有主流程租约。
     */
    @Test
    void shouldNeverBlockWorkflowForAdvisoryAudit() {
        ReviewRound advisory = ReviewRound.create(
                "review-advisory", "project-1", "work-1", WorkflowStage.CODE_IMPLEMENTATION,
                ReviewPolicy.ADVISORY_AUDIT, ReviewRequirement.ADVISORY,
                Set.of("qa"), START, START.plusSeconds(60)
        );

        ReviewRound requested = advisory.submit(
                "qa", ReviewVerdict.CHANGES_REQUESTED, START.plusSeconds(1)
        );

        assertThat(advisory.blocksWorkflow()).isFalse();
        assertThat(requested.blocksWorkflow()).isFalse();
        assertThat(requested.status()).isEqualTo(ReviewRoundStatus.CHANGES_REQUESTED);
    }

    /**
     * 相同 Reviewer 的相同重复结论必须幂等，冲突结论必须拒绝。
     */
    @Test
    void shouldDeduplicateSameVerdictAndRejectConflictingVerdict() {
        ReviewRound approved = requiredRound()
                .submit("qa", ReviewVerdict.APPROVED, START.plusSeconds(1));

        assertThat(approved.submit("qa", ReviewVerdict.APPROVED, START.plusSeconds(20)))
                .isSameAs(approved);
        assertThatThrownBy(() -> approved.submit(
                "qa", ReviewVerdict.CHANGES_REQUESTED, START.plusSeconds(20)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能修改");
    }

    /**
     * 截止时间到达后只能形成 EXPIRED，迟到 verdict 不得复活轮次。
     */
    @Test
    void shouldExpireAtDeadlineAndRejectLateVerdict() {
        ReviewRound pending = requiredRound();

        assertThatThrownBy(() -> pending.submit(
                "qa", ReviewVerdict.APPROVED, START.plusSeconds(60)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline");

        ReviewRound expired = pending.expire(START.plusSeconds(60));
        assertThat(expired.status()).isEqualTo(ReviewRoundStatus.EXPIRED);
        assertThat(expired.failureReason()).isEqualTo("REVIEW_DEADLINE_EXCEEDED");
        assertThat(expired.expire(START.plusSeconds(90))).isSameAs(expired);
        assertThatThrownBy(() -> expired.start(START.plusSeconds(90)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 策略与 Reviewer 数量不匹配时必须在创建边界失败。
     */
    @Test
    void shouldRejectInvalidPolicyAndReviewerCardinality() {
        assertThatThrownBy(() -> ReviewRound.create(
                "review-invalid", "project-1", "work-1", WorkflowStage.CODE_IMPLEMENTATION,
                ReviewPolicy.STRICT_BLOCKING, ReviewRequirement.MANDATORY,
                Set.of("qa"), START, START.plusSeconds(60)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("双 Reviewer");
        assertThatThrownBy(() -> ReviewRound.create(
                "review-invalid", "project-1", "work-1", WorkflowStage.CODE_IMPLEMENTATION,
                ReviewPolicy.REQUIRED_ONLY, ReviewRequirement.ADVISORY,
                Set.of("qa"), START, START.plusSeconds(60)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必要评审");
        assertThatThrownBy(() -> ReviewRound.create(
                "review-invalid", "project-1", "work-1", WorkflowStage.CODE_IMPLEMENTATION,
                ReviewPolicy.ADVISORY_AUDIT, ReviewRequirement.ADVISORY,
                Set.of("pm", "qa"), START, START.plusSeconds(60)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一个 Reviewer");
    }

    /**
     * 公开持久化构造器必须拒绝与 verdict 集合不一致的伪造状态。
     */
    @Test
    void shouldRejectPersistedStateThatViolatesReviewerBarrier() {
        assertThatThrownBy(() -> new ReviewRound(
                "review-invalid", "project-1", "work-1", WorkflowStage.TECH_DESIGN,
                ReviewPolicy.STRICT_BLOCKING, ReviewRequirement.MANDATORY,
                WorkflowSignal.STAGE_ACCEPTED,
                Set.of("pm", "qa"), Map.of("pm", ReviewVerdict.APPROVED,
                        "qa", ReviewVerdict.APPROVED),
                ReviewRoundStatus.IN_PROGRESS, START, START.plusSeconds(60),
                START.plusSeconds(2), ""
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仍有 Reviewer");
        assertThatThrownBy(() -> new ReviewRound(
                "review-invalid", "project-1", "work-1", WorkflowStage.CODE_IMPLEMENTATION,
                ReviewPolicy.REQUIRED_ONLY, ReviewRequirement.MANDATORY,
                WorkflowSignal.STAGE_ACCEPTED,
                Set.of("qa"), Map.of(), ReviewRoundStatus.APPROVED,
                START, START.plusSeconds(60), START.plusSeconds(2), ""
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全部批准");
    }

    /**
     * 创建一个固定的单 Reviewer 必要评审轮次。
     *
     * @return PENDING 必要评审
     */
    private static ReviewRound requiredRound() {
        return ReviewRound.create(
                "review-1", "project-1", "work-1", WorkflowStage.CODE_IMPLEMENTATION,
                ReviewPolicy.REQUIRED_ONLY, ReviewRequirement.MANDATORY,
                Set.of("qa"), START, START.plusSeconds(60)
        );
    }
}
