package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workflow.OutboxAction;
import cn.org.chris.wake.domain.workflow.OutboxActionId;
import cn.org.chris.wake.domain.workflow.OutboxActionType;
import cn.org.chris.wake.domain.workflow.ReviewRequirement;
import cn.org.chris.wake.domain.workflow.ReviewRound;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 扫描逾期评审并把缺失 Reviewer 确定性收敛为 EXPIRED，必要评审同时阻断所属工作。
 */
public final class ReviewDeadlineWatchdog implements AutoCloseable {

    /** 评审超时写入 WorkExecution 的有限失败类型。 */
    public static final String DEADLINE_FAILURE_TYPE = "REVIEW_DEADLINE_EXCEEDED";

    /** runtime v2 权威仓储。 */
    private final WorkflowRuntimeRepository runtimeRepository;

    /** 可枚举 runtime v2 项目的工作区端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** 评审截止时间判断来源。 */
    private final Clock clock;

    /** 后台扫描线程；未启动或已停止时为空。 */
    private ScheduledExecutorService executor;

    /**
     * 使用系统 UTC 时钟创建评审截止时间看门狗。
     *
     * @param runtimeRepository runtime v2 权威仓储
     * @param workspaceRepository 工作区端口
     */
    public ReviewDeadlineWatchdog(
            WorkflowRuntimeRepository runtimeRepository,
            WorkspaceRepository workspaceRepository
    ) {
        this(runtimeRepository, workspaceRepository, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的评审截止时间看门狗。
     *
     * @param runtimeRepository runtime v2 权威仓储
     * @param workspaceRepository 工作区端口
     * @param clock UTC 时钟
     */
    public ReviewDeadlineWatchdog(
            WorkflowRuntimeRepository runtimeRepository,
            WorkspaceRepository workspaceRepository,
            Clock clock
    ) {
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository, "runtimeRepository 不能为空");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 启动固定延迟扫描；启动时立即执行一轮，重复启动保持幂等。
     *
     * @param interval 扫描间隔
     */
    public synchronized void start(Duration interval) {
        Objects.requireNonNull(interval, "interval 不能为空");
        if (interval.isZero() || interval.isNegative() || interval.toMillis() == 0L) {
            throw new IllegalArgumentException("interval 必须至少为一毫秒");
        }
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qingling-review-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::safeExpireOverdue, 0L, interval.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    /** 停止后台扫描；重复停止保持幂等。 */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** 关闭后台扫描线程。 */
    @Override
    public void close() {
        stop();
    }

    /**
     * 扫描所有 runtime v2 项目，并返回本轮新形成的 EXPIRED 评审数。
     *
     * @return 新过期评审数量
     */
    public int expireOverdue() {
        int expired = 0;
        for (String projectId : workspaceRepository.listProjectIds()) {
            if (runtimeRepository.find(projectId).isEmpty()) {
                continue;
            }
            int[] projectExpired = {0};
            runtimeRepository.update(projectId, state -> {
                Instant clockNow = Instant.now(clock);
                Instant now = clockNow.isBefore(state.updatedAt()) ? state.updatedAt() : clockNow;
                WorkflowRuntimeState changed = state;
                for (ReviewRound review : state.reviewRounds()) {
                    if (review.status().terminal() || now.isBefore(review.deadline())) {
                        continue;
                    }
                    ReviewRound expiredReview = review.expire(now);
                    changed = changed.replaceReviewRound(expiredReview, now);
                    if (review.requirement() == ReviewRequirement.MANDATORY) {
                        WorkExecution execution = changed.findExecution(review.workExecutionId())
                                .orElseThrow(() -> new IllegalStateException("评审关联的 WorkExecution 不存在"));
                        if (execution.status() == WorkExecutionStatus.WAITING_REVIEW
                                && execution.reviewRoundId().equals(review.reviewRoundId())) {
                            changed = changed.replaceExecution(
                                    execution.block(DEADLINE_FAILURE_TYPE, now), now
                            );
                        }
                    }
                    changed = changed.enqueue(timeoutEvent(projectId, review, now), now);
                    projectExpired[0]++;
                }
                return changed;
            });
            expired += projectExpired[0];
        }
        return expired;
    }

    /**
     * 创建仅含有限诊断字段的稳定超时事件，禁止写入评审正文或模型输出。
     */
    private static OutboxAction timeoutEvent(String projectId, ReviewRound review, Instant now) {
        return OutboxAction.pending(
                new OutboxActionId("review-timeout:" + review.reviewRoundId() + ":event"),
                OutboxActionType.WORKFLOW_EVENT,
                projectId,
                Map.of(
                        "action", "review_deadline_exceeded",
                        "review_round_id", review.reviewRoundId(),
                        "execution_id", review.workExecutionId(),
                        "reviewer", String.join(",", review.expectedReviewers()),
                        "deadline", review.deadline().toString(),
                        "requirement", review.requirement().name()
                ),
                now
        );
    }

    /** 保护后台线程，使单项目损坏不会永久终止后续扫描周期。 */
    private void safeExpireOverdue() {
        try {
            expireOverdue();
        } catch (RuntimeException ignored) {
            // 失败项目仍保留权威状态，下一轮扫描可再次尝试并由直接诊断读取。
        }
    }
}
