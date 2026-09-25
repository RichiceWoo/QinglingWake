package cn.org.chris.wake.app.cron;

import cn.org.chris.wake.domain.gateway.CronJobRepository;
import cn.org.chris.wake.domain.model.CronJob;
import cn.org.chris.wake.domain.model.InboundMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 at/every/cron、热重载和一次性去重执行。
 */
class CronServiceTest {

    /** 测试基准时间。 */
    private static final long NOW_MS = Instant.parse("2026-09-26T00:00:00Z").toEpochMilli();

    /** 固定测试时钟。 */
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW_MS), ZoneOffset.UTC);

    /**
     * AT 任务到期后只执行一次并从存储删除。
     */
    @Test
    void shouldFireAtJobOnlyOnce() {
        MemoryCronRepository repository = new MemoryCronRepository(List.of(atJob("at-1", NOW_MS)));
        List<InboundMessage> messages = new ArrayList<>();
        CronService service = new CronService(
                repository,
                message -> {
                    messages.add(message);
                    return CompletableFuture.completedFuture(null);
                },
                () -> "cron_fixed",
                CLOCK
        );

        service.tick(NOW_MS);
        service.tick(NOW_MS + 1L);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).cron()).isTrue();
        assertThat(messages.get(0).senderId()).isEqualTo("cron");
        assertThat(repository.findAll()).isEmpty();
    }

    /**
     * EVERY 任务应以统一触发时间推进下次执行，并避免同一 tick 重复触发。
     */
    @Test
    void shouldAdvanceEveryJobFromFireTime() {
        CronJob every = new CronJob(
                "heartbeat-pm", "heartbeat-pm", true,
                new CronJob.Schedule(CronJob.ScheduleKind.EVERY, null, 30_000L, null, null),
                new CronJob.Payload("team:pm", "__wake__:heartbeat"),
                new CronJob.State(NOW_MS, null, null, null),
                NOW_MS, NOW_MS, false
        );
        MemoryCronRepository repository = new MemoryCronRepository(List.of(every));
        List<InboundMessage> messages = new ArrayList<>();
        CronService service = new CronService(repository, message -> {
            messages.add(message);
            return CompletableFuture.completedFuture(null);
        }, () -> "cron_every", CLOCK);

        service.tick(NOW_MS);
        service.tick(NOW_MS);

        assertThat(messages).hasSize(1);
        assertThat(repository.findAll().get(0).state().nextRunAtMs()).isEqualTo(NOW_MS + 30_000L);
        assertThat(repository.findAll().get(0).state().lastStatus()).isEqualTo("ok");
    }

    /**
     * 同角色业务 AT wake 与 heartbeat 同时到期时应只投递业务 wake，并推进 heartbeat。
     */
    @Test
    void shouldSuppressHeartbeatWhenBusinessWakeIsDueForSameRole() {
        CronJob heartbeat = new CronJob(
                "heartbeat-pm", "heartbeat-pm", true,
                new CronJob.Schedule(CronJob.ScheduleKind.EVERY, null, 30_000L, null, null),
                new CronJob.Payload("team:pm", "__wake__:heartbeat"),
                new CronJob.State(NOW_MS, null, null, null),
                NOW_MS, NOW_MS, false
        );
        MemoryCronRepository repository = new MemoryCronRepository(List.of(heartbeat, atJob("mail", NOW_MS)));
        List<InboundMessage> messages = new ArrayList<>();
        CronService service = new CronService(repository, message -> {
            messages.add(message);
            return CompletableFuture.completedFuture(null);
        }, () -> "cron_dedup", CLOCK);

        service.tick(NOW_MS);

        assertThat(messages).singleElement()
                .extracting(InboundMessage::content)
                .isEqualTo("__wake__:new_mail:demo");
        assertThat(repository.findAll()).singleElement().satisfies(job -> {
            assertThat(job.id()).isEqualTo("heartbeat-pm");
            assertThat(job.state().nextRunAtMs()).isEqualTo(NOW_MS + 30_000L);
        });
    }

    /**
     * cron 表达式应按 Asia/Shanghai 时区计算下一次九点触发时间。
     */
    @Test
    void shouldCalculateNextCronInConfiguredTimezone() {
        CronJob cron = new CronJob(
                "daily", "daily", true,
                new CronJob.Schedule(CronJob.ScheduleKind.CRON, null, null, "0 9 * * *", "Asia/Shanghai"),
                new CronJob.Payload("team:manager", "daily"),
                new CronJob.State(null, null, null, null),
                NOW_MS, NOW_MS, false
        );
        MemoryCronRepository repository = new MemoryCronRepository(List.of(cron));
        CronService service = new CronService(
                repository,
                message -> CompletableFuture.completedFuture(null),
                () -> "cron_daily",
                CLOCK
        );

        service.tick(NOW_MS);

        assertThat(service.currentJobs().get(0).state().nextRunAtMs())
                .isEqualTo(Instant.parse("2026-09-26T01:00:00Z").toEpochMilli());
    }

    /**
     * 即使 mtime 不变，只要 size 变化也应在下一 tick 热重载。
     */
    @Test
    void shouldReloadWhenOnlySizeChanges() {
        MemoryCronRepository repository = new MemoryCronRepository(List.of());
        List<InboundMessage> messages = new ArrayList<>();
        CronService service = new CronService(repository, message -> {
            messages.add(message);
            return CompletableFuture.completedFuture(null);
        }, () -> "cron_hot", CLOCK);
        service.tick(NOW_MS - 1L);

        repository.externalReplaceKeepingMtime(List.of(atJob("external", NOW_MS)));
        service.tick(NOW_MS);

        assertThat(messages).hasSize(1);
    }

    /**
     * 构造到期即删除的 AT 测试任务。
     *
     * @param id 任务标识
     * @param atMs 触发时间
     * @return AT 任务
     */
    private static CronJob atJob(String id, long atMs) {
        return new CronJob(
                id, id, true,
                new CronJob.Schedule(CronJob.ScheduleKind.AT, atMs, null, null, null),
                new CronJob.Payload("team:pm", "__wake__:new_mail:demo"),
                new CronJob.State(null, null, null, null),
                NOW_MS, NOW_MS, true
        );
    }

    /**
     * 为 CronService 提供可控制 mtime/size 的内存存储。
     */
    private static final class MemoryCronRepository implements CronJobRepository {

        /** 当前任务。 */
        private List<CronJob> jobs;

        /** 模拟 mtime。 */
        private long modifiedAt = 1L;

        /** 模拟 size。 */
        private long size;

        /**
         * 使用初始任务创建存储。
         *
         * @param initial 初始任务
         */
        private MemoryCronRepository(List<CronJob> initial) {
            this.jobs = new ArrayList<>(initial);
            this.size = initial.size();
        }

        /**
         * 返回任务快照。
         *
         * @return 任务快照
         */
        @Override
        public synchronized List<CronJob> findAll() {
            return List.copyOf(jobs);
        }

        /**
         * 替换全部任务并推进版本。
         *
         * @param replacement 新任务
         */
        @Override
        public synchronized void replaceAll(List<CronJob> replacement) {
            jobs = new ArrayList<>(replacement);
            modifiedAt++;
            size = replacement.size();
        }

        /**
         * 按标识保存任务。
         *
         * @param job 待保存任务
         */
        @Override
        public synchronized void upsert(CronJob job) {
            jobs.removeIf(existing -> existing.id().equals(job.id()));
            jobs.add(job);
            modifiedAt++;
            size = jobs.size();
        }

        /**
         * 追加候选 wake，测试不需要额外去重分支。
         *
         * @param candidate 候选任务
         * @param nowMs 当前时间
         * @return 候选任务
         */
        @Override
        public synchronized CronJob saveWakeIfAbsent(CronJob candidate, long nowMs) {
            upsert(candidate);
            return candidate;
        }

        /**
         * 删除任务。
         *
         * @param jobId 任务标识
         * @return 是否删除
         */
        @Override
        public synchronized boolean delete(String jobId) {
            boolean removed = jobs.removeIf(job -> job.id().equals(jobId));
            if (removed) {
                modifiedAt++;
                size = jobs.size();
            }
            return removed;
        }

        /**
         * 返回模拟组合版本。
         *
         * @return 当前版本
         */
        @Override
        public synchronized Revision revision() {
            return new Revision(true, modifiedAt, size);
        }

        /**
         * 模拟同一 mtime tick 内外部写入不同大小内容。
         *
         * @param replacement 外部任务
         */
        private synchronized void externalReplaceKeepingMtime(List<CronJob> replacement) {
            jobs = new ArrayList<>(replacement);
            size = replacement.size() + 100L;
        }
    }
}
