package cn.org.chris.wake.starter.runtime;

import cn.org.chris.wake.app.cron.CronService;
import cn.org.chris.wake.domain.gateway.CronJobRepository;
import cn.org.chris.wake.domain.model.CronJob;
import cn.org.chris.wake.domain.workflow.WorkflowCoordinationMode;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 验证运行时停止先关闭生产者，再排空或取消消费者，最后关闭 Agent/MCP。
 */
class RuntimeLifecycleTest {

    /** coordinated SIMPLE 只排除 PM，STANDARD/STRICT 与 legacy 仍保留四角色 heartbeat。 */
    @Test
    void shouldExcludePmHeartbeatOnlyForCoordinatedSimpleProfile() {
        assertThat(RuntimeLifecycle.heartbeatEnabled(
                WorkflowCoordinationMode.COORDINATED, WorkflowProfile.SIMPLE, "pm"
        )).isFalse();
        assertThat(RuntimeLifecycle.heartbeatEnabled(
                WorkflowCoordinationMode.COORDINATED, WorkflowProfile.SIMPLE, "rd"
        )).isTrue();
        assertThat(RuntimeLifecycle.heartbeatEnabled(
                WorkflowCoordinationMode.COORDINATED, WorkflowProfile.STANDARD, "pm"
        )).isTrue();
        assertThat(RuntimeLifecycle.heartbeatEnabled(
                WorkflowCoordinationMode.LEGACY, WorkflowProfile.SIMPLE, "pm"
        )).isTrue();
    }

    /** 排空成功时不得执行取消，Agent 必须最后关闭。 */
    @Test
    void shouldStopProducersBeforeSuccessfulDrain() {
        List<String> order = new ArrayList<>();

        RuntimeLifecycle.shutdownInOrder(
                () -> order.add("inbound"),
                () -> order.add("review-watchdog"),
                () -> order.add("watchdog"),
                () -> order.add("wake"),
                () -> order.add("cron"),
                () -> order.add("admission"),
                () -> {
                    order.add("drain");
                    return true;
                },
                () -> order.add("cancel"),
                () -> order.add("workflow"),
                () -> order.add("agents")
        );

        assertThat(order).containsExactly(
                "inbound", "review-watchdog", "watchdog", "wake", "cron", "admission",
                "drain", "workflow", "agents"
        );
    }

    /** 排空超时时必须先取消任务，再关闭 Agent/MCP。 */
    @Test
    void shouldCancelOutstandingTasksBeforeClosingAgentsWhenDrainTimesOut() {
        List<String> order = new ArrayList<>();

        RuntimeLifecycle.shutdownInOrder(
                () -> order.add("inbound"),
                () -> order.add("review-watchdog"),
                () -> order.add("watchdog"),
                () -> order.add("wake"),
                () -> order.add("cron"),
                () -> order.add("admission"),
                () -> {
                    order.add("drain");
                    return false;
                },
                () -> order.add("cancel"),
                () -> order.add("workflow"),
                () -> order.add("agents")
        );

        assertThat(order).containsExactly(
                "inbound", "review-watchdog", "watchdog", "wake", "cron", "admission",
                "drain", "cancel", "workflow", "agents"
        );
    }

    /** 未完成的 Cron dispatch 被停止后，生命周期必须继续取消、终态化并关闭 Agent。 */
    @Test
    void shouldContinueShutdownAfterCancellingStuckCronDispatch() throws Exception {
        long nowMs = Instant.parse("2026-10-26T00:00:00Z").toEpochMilli();
        CompletableFuture<Void> neverCompletes = new CompletableFuture<>();
        CountDownLatch dispatchStarted = new CountDownLatch(1);
        CronService cronService = new CronService(
                new SingleJobCronRepository(nowMs),
                message -> {
                    dispatchStarted.countDown();
                    return neverCompletes;
                }
        );
        cronService.start(Duration.ofMillis(10));
        assertThat(dispatchStarted.await(2, TimeUnit.SECONDS)).isTrue();
        List<String> order = new ArrayList<>();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> RuntimeLifecycle.shutdownInOrder(
                () -> order.add("inbound"),
                () -> order.add("review-watchdog"),
                () -> order.add("watchdog"),
                () -> order.add("wake"),
                () -> {
                    cronService.stop();
                    order.add("cron");
                },
                () -> order.add("admission"),
                () -> false,
                () -> {
                    neverCompletes.cancel(true);
                    order.add("cancel");
                },
                () -> order.add("workflow"),
                () -> order.add("agents")
        ));

        assertThat(order).containsExactly(
                "inbound", "review-watchdog", "watchdog", "wake", "cron", "admission",
                "cancel", "workflow", "agents"
        );
        assertThat(neverCompletes).isCancelled();
    }

    /** 为生命周期并发测试提供单个已到期任务的最小 Cron 仓储。 */
    private static final class SingleJobCronRepository implements CronJobRepository {

        /** 当前仓储快照。 */
        private List<CronJob> jobs;

        /** 每次写入递增的版本号。 */
        private long revision = 1L;

        /**
         * 创建包含单个已到期唤醒的仓储。
         *
         * @param nowMs 当前测试时间
         */
        private SingleJobCronRepository(long nowMs) {
            jobs = new ArrayList<>(List.of(new CronJob(
                    "stuck", "stuck", true,
                    new CronJob.Schedule(CronJob.ScheduleKind.AT, nowMs, null, null, null),
                    new CronJob.Payload("team:rd", "__wake__:new_mail:demo"),
                    new CronJob.State(null, null, null, null),
                    nowMs, nowMs, true
            )));
        }

        /** 返回当前任务快照。 */
        @Override
        public synchronized List<CronJob> findAll() {
            return List.copyOf(jobs);
        }

        /** 替换当前任务快照。 */
        @Override
        public synchronized void replaceAll(List<CronJob> replacement) {
            jobs = new ArrayList<>(replacement);
            revision++;
        }

        /** 合并测试不产生并发新增任务，直接替换快照。 */
        @Override
        public synchronized boolean replaceAllPreservingNewJobs(
                List<CronJob> replacement,
                Set<String> originalJobIds
        ) {
            replaceAll(replacement);
            return false;
        }

        /** 保存测试任务。 */
        @Override
        public synchronized void upsert(CronJob job) {
            jobs.removeIf(existing -> existing.id().equals(job.id()));
            jobs.add(job);
            revision++;
        }

        /** 保存一次性唤醒并返回同一任务。 */
        @Override
        public synchronized CronJob saveWakeIfAbsent(CronJob candidate, long nowMs) {
            upsert(candidate);
            return candidate;
        }

        /** 删除指定任务。 */
        @Override
        public synchronized boolean delete(String jobId) {
            boolean removed = jobs.removeIf(job -> job.id().equals(jobId));
            if (removed) {
                revision++;
            }
            return removed;
        }

        /** 返回当前内存版本。 */
        @Override
        public synchronized Revision revision() {
            return new Revision(true, revision, jobs.size());
        }
    }
}
