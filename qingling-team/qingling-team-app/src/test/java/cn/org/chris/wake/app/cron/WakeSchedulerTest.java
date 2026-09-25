package cn.org.chris.wake.app.cron;

import cn.org.chris.wake.domain.gateway.CronJobRepository;
import cn.org.chris.wake.domain.model.CronJob;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 wake 去重、项目标签和四角色 heartbeat 错峰契约。
 */
class WakeSchedulerTest {

    /** 固定测试时钟。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T16:00:00Z"), ZoneOffset.UTC);

    /**
     * 相同 role、project、reason 的未到期 wake 应复用任务标识。
     */
    @Test
    void shouldReuseEquivalentPendingWake() {
        MemoryCronRepository repository = new MemoryCronRepository();
        SequenceIds ids = new SequenceIds();
        WakeScheduler scheduler = new WakeScheduler(repository, ids::next, CLOCK);

        String first = scheduler.scheduleWake("pm", "new_mail", "todo-mvp", Duration.ofSeconds(1));
        String duplicate = scheduler.scheduleWake("pm", "new_mail", "todo-mvp", Duration.ofSeconds(1));
        String anotherProject = scheduler.scheduleWake("pm", "new_mail", "other", Duration.ofSeconds(1));

        assertThat(duplicate).isEqualTo(first);
        assertThat(anotherProject).isNotEqualTo(first);
        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findAll().get(0).payload().message()).isEqualTo("__wake__:new_mail:todo-mvp");
    }

    /**
     * 默认 heartbeat 应使用固定标识、30 秒周期和 0/7/14/21 秒首次错峰。
     */
    @Test
    void shouldRegisterDefaultStaggeredHeartbeats() {
        MemoryCronRepository repository = new MemoryCronRepository();
        WakeScheduler scheduler = new WakeScheduler(repository, () -> "unused", CLOCK);

        scheduler.registerDefaultHeartbeats();

        List<CronJob> jobs = repository.findAll();
        assertThat(jobs).extracting(CronJob::id)
                .containsExactly("heartbeat-manager", "heartbeat-pm", "heartbeat-rd", "heartbeat-qa");
        assertThat(jobs).extracting(job -> job.schedule().everyMs()).containsOnly(30_000L);
        assertThat(jobs).extracting(job -> job.state().nextRunAtMs())
                .containsExactly(CLOCK.millis(), CLOCK.millis() + 7_000L, CLOCK.millis() + 14_000L, CLOCK.millis() + 21_000L);
    }

    /**
     * 为测试提供可变内存 Cron 存储。
     */
    private static final class MemoryCronRepository implements CronJobRepository {

        /** 当前任务列表。 */
        private List<CronJob> jobs = new ArrayList<>();

        /** 递增文件版本。 */
        private long version = 1L;

        /**
         * 返回任务副本。
         *
         * @return 任务副本
         */
        @Override
        public synchronized List<CronJob> findAll() {
            return List.copyOf(jobs);
        }

        /**
         * 替换完整任务列表。
         *
         * @param replacement 新列表
         */
        @Override
        public synchronized void replaceAll(List<CronJob> replacement) {
            jobs = new ArrayList<>(replacement);
            version++;
        }

        /**
         * 按标识覆盖任务。
         *
         * @param job 待保存任务
         */
        @Override
        public synchronized void upsert(CronJob job) {
            jobs.removeIf(existing -> existing.id().equals(job.id()));
            jobs.add(job);
            version++;
        }

        /**
         * 原子复用未到期 wake。
         *
         * @param candidate 候选任务
         * @param nowMs 当前时间
         * @return 复用或新增任务
         */
        @Override
        public synchronized CronJob saveWakeIfAbsent(CronJob candidate, long nowMs) {
            for (CronJob existing : jobs) {
                if (existing.schedule().kind() == CronJob.ScheduleKind.AT
                        && existing.deleteAfterRun()
                        && existing.schedule().atMs() > nowMs
                        && existing.payload().equals(candidate.payload())) {
                    return existing;
                }
            }
            jobs.add(candidate);
            version++;
            return candidate;
        }

        /**
         * 删除指定任务。
         *
         * @param jobId 任务标识
         * @return 是否删除
         */
        @Override
        public synchronized boolean delete(String jobId) {
            boolean removed = jobs.removeIf(job -> job.id().equals(jobId));
            if (removed) {
                version++;
            }
            return removed;
        }

        /**
         * 返回内存组合版本。
         *
         * @return 当前版本
         */
        @Override
        public synchronized Revision revision() {
            return new Revision(true, version, jobs.size());
        }
    }

    /**
     * 生成确定性测试任务标识。
     */
    private static final class SequenceIds {

        /** 下一个序号。 */
        private int next = 1;

        /**
         * 返回递增任务标识。
         *
         * @return 确定性标识
         */
        private String next() {
            return "job-0000000" + next++;
        }
    }
}
