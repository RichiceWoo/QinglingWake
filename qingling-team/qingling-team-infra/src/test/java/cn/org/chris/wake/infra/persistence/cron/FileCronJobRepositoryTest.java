package cn.org.chris.wake.infra.persistence.cron;

import cn.org.chris.wake.domain.model.CronJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Python tasks.json 兼容、原子更新与并发 wake 去重。
 */
class FileCronJobRepositoryTest {

    /** JUnit 临时目录。 */
    @TempDir
    Path temporaryDirectory;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 旧数组格式应可读取，回写后转换为 version=1 对象格式。
     *
     * @throws IOException fixture 复制或读取失败
     */
    @Test
    void shouldReadLegacyPythonFixtureAndWriteVersionedStore() throws IOException {
        Path tasks = temporaryDirectory.resolve("cron/tasks.json");
        Files.createDirectories(tasks.getParent());
        try (var source = getClass().getResourceAsStream("/golden/task7/tasks-legacy.json")) {
            assertThat(source).isNotNull();
            Files.copy(source, tasks);
        }
        FileCronJobRepository repository = new FileCronJobRepository(tasks, objectMapper);

        List<CronJob> loaded = repository.findAll();
        repository.replaceAll(loaded);

        assertThat(loaded).singleElement().satisfies(job -> {
            assertThat(job.id()).isEqualTo("job-python01");
            assertThat(job.payload().routingKey()).isEqualTo("team:pm");
            assertThat(job.payload().message()).isEqualTo("__wake__:new_mail:todo-mvp");
        });
        JsonNode root = objectMapper.readTree(tasks.toFile());
        assertThat(root.path("version").asInt()).isEqualTo(1);
        assertThat(root.path("jobs").get(0).path("schedule").path("kind").asText()).isEqualTo("at");
    }

    /**
     * 并发保存相同 wake 时应只留下一个任务并让所有调用复用同一标识。
     *
     * @throws Exception 并发执行失败
     */
    @Test
    void shouldDeduplicateEquivalentWakeUnderConcurrentWriters() throws Exception {
        FileCronJobRepository repository = new FileCronJobRepository(
                temporaryDirectory.resolve("cron/tasks.json"), objectMapper
        );
        long nowMs = 1_000L;
        List<Callable<String>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int sequence = index;
            calls.add(() -> repository.saveWakeIfAbsent(wake("job-" + sequence, nowMs + 1_000L), nowMs).id());
        }

        List<String> returnedIds;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            returnedIds = executor.invokeAll(calls).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        } finally {
            executor.shutdownNow();
        }

        assertThat(returnedIds).containsOnly(returnedIds.get(0));
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.revision().exists()).isTrue();
        assertThat(repository.revision().size()).isPositive();
    }

    /**
     * upsert 应替换固定 heartbeat 标识，delete 应报告实际变化。
     */
    @Test
    void shouldReplaceHeartbeatAndDeleteIdempotently() {
        FileCronJobRepository repository = new FileCronJobRepository(
                temporaryDirectory.resolve("cron/tasks.json"), objectMapper
        );
        repository.upsert(heartbeat(30_000L));
        repository.upsert(heartbeat(60_000L));

        assertThat(repository.findAll()).singleElement()
                .extracting(job -> job.schedule().everyMs())
                .isEqualTo(60_000L);
        assertThat(repository.delete("heartbeat-pm")).isTrue();
        assertThat(repository.delete("heartbeat-pm")).isFalse();
    }

    /**
     * 构造同一语义的一次性 wake。
     *
     * @param id 任务标识
     * @param atMs 触发时间
     * @return wake 任务
     */
    private static CronJob wake(String id, long atMs) {
        return new CronJob(
                id, "wake-pm-new_mail", true,
                new CronJob.Schedule(CronJob.ScheduleKind.AT, atMs, null, null, null),
                new CronJob.Payload("team:pm", "__wake__:new_mail:demo"),
                new CronJob.State(null, null, null, null),
                1_000L, 1_000L, true
        );
    }

    /**
     * 构造可替换的 heartbeat。
     *
     * @param intervalMs 周期毫秒数
     * @return heartbeat 任务
     */
    private static CronJob heartbeat(long intervalMs) {
        return new CronJob(
                "heartbeat-pm", "heartbeat-pm", true,
                new CronJob.Schedule(CronJob.ScheduleKind.EVERY, null, intervalMs, null, null),
                new CronJob.Payload("team:pm", "__wake__:heartbeat"),
                new CronJob.State(1_000L, null, null, null),
                1_000L, 1_000L, false
        );
    }
}
