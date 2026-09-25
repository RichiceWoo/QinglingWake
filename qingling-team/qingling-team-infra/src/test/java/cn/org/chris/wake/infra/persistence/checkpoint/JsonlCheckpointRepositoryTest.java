package cn.org.chris.wake.infra.persistence.checkpoint;

import cn.org.chris.wake.domain.model.PendingCheckpoint;
import cn.org.chris.wake.domain.quality.SelfScoreCalculator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实临时文件验证 checkpoint JSONL、并发、幂等和 Python golden 契约。
 */
class JsonlCheckpointRepositoryTest {

    /** 每项测试独立使用的临时目录。 */
    @TempDir
    Path temporaryDirectory;

    /**
     * Java 注册、resolve marker 和 self-score 读取结果必须匹配 Python golden。
     *
     * @throws Exception fixture 或文件读取失败
     */
    @Test
    void shouldMatchPythonGoldenContracts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> expectedCheckpointLines;
        Map<String, Object> selfScoreFixture;
        try (InputStream checkpointFixture = Objects.requireNonNull(
                getClass().getResourceAsStream("/golden/task6/checkpoints.jsonl"), "checkpoint fixture 不存在"
        ); InputStream scoreFixture = Objects.requireNonNull(
                getClass().getResourceAsStream("/golden/task6/self-score.json"), "self-score fixture 不存在"
        )) {
            expectedCheckpointLines = new ArrayList<>();
            for (String line : new String(checkpointFixture.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) {
                    expectedCheckpointLines.add(mapper.readTree(line));
                }
            }
            selfScoreFixture = mapper.readValue(scoreFixture, new TypeReference<>() { });
        }

        Path pendingPath = temporaryDirectory.resolve("shared/feishu_bridge/pending.jsonl");
        JsonlCheckpointRepository repository = new JsonlCheckpointRepository(pendingPath, mapper);
        repository.register(new PendingCheckpoint(
                "ckpt-12345678", "p2p:abc123", "p1", "checkpoint_request", "确认吗？", 1_700_000_000_000L
        ));
        assertThat(repository.resolve("ckpt-12345678", Instant.ofEpochMilli(1_700_000_001_000L))).isTrue();

        List<String> actualLines = Files.readAllLines(pendingPath, StandardCharsets.UTF_8);
        assertThat(mapper.readTree(actualLines.get(0))).isEqualTo(expectedCheckpointLines.get(0));
        assertThat(mapper.readTree(actualLines.get(1))).isEqualTo(expectedCheckpointLines.get(1));
        SelfScoreCalculator.Extraction extraction = SelfScoreCalculator.extract(selfScoreFixture, null);
        assertThat(extraction.score()).isEqualTo(0.85);
        assertThat(extraction.breakdown()).containsEntry("hard_constraints", 1.0);
    }

    /**
     * 解决不存在或已解决 checkpoint 应返回 false，损坏行不得阻断 pending 恢复。
     *
     * @throws Exception 测试文件写入失败
     */
    @Test
    void shouldResolveIdempotentlyAndSkipCorruptLines() throws Exception {
        Path pendingPath = temporaryDirectory.resolve("pending-idempotent.jsonl");
        JsonlCheckpointRepository repository = new JsonlCheckpointRepository(pendingPath, new ObjectMapper());
        repository.register(checkpoint("ckpt-11111111", "rk-a", 100L));
        repository.register(checkpoint("ckpt-22222222", "rk-b", 200L));
        Files.writeString(pendingPath, "{broken-json\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertThat(repository.findPending()).hasSize(2);
        assertThat(repository.findPending("rk-a")).extracting(PendingCheckpoint::checkpointId)
                .containsExactly("ckpt-11111111");
        assertThat(repository.findLatestPending("rk-b")).hasValueSatisfying(
                checkpoint -> assertThat(checkpoint.checkpointId()).isEqualTo("ckpt-22222222")
        );
        assertThat(repository.resolve("ckpt-missing", Instant.ofEpochMilli(300L))).isFalse();
        assertThat(repository.resolve("ckpt-11111111", Instant.ofEpochMilli(300L))).isTrue();
        assertThat(repository.resolve("ckpt-11111111", Instant.ofEpochMilli(400L))).isFalse();
        assertThat(repository.findPending("rk-a")).isEmpty();
    }

    /**
     * 同进程并发注册不得丢失 JSONL 记录或产生无法解析的行。
     *
     * @throws Exception 并发任务执行失败
     */
    @Test
    void shouldPreserveAllConcurrentRegistrations() throws Exception {
        JsonlCheckpointRepository repository = new JsonlCheckpointRepository(
                temporaryDirectory.resolve("pending-concurrent.jsonl"), new ObjectMapper()
        );
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> registrations = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int current = index;
                registrations.add(() -> {
                    repository.register(checkpoint(String.format("ckpt-%08x", current), "rk", current));
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(registrations);
            assertThat(futures).allSatisfy(future -> assertThat(future.get()).isNull());
        } finally {
            executor.shutdownNow();
        }

        assertThat(repository.findPending()).hasSize(20);
    }

    /**
     * 直接指向符号链接的 pending 文件必须拒绝访问。
     *
     * @throws Exception 符号链接创建失败
     */
    @Test
    void shouldRejectSymbolicLinkCheckpointFile() throws Exception {
        Path outside = temporaryDirectory.resolve("outside.jsonl");
        Files.writeString(outside, "", StandardCharsets.UTF_8);
        Path link = temporaryDirectory.resolve("pending-link.jsonl");
        Files.createSymbolicLink(link, outside);
        JsonlCheckpointRepository repository = new JsonlCheckpointRepository(link, new ObjectMapper());

        assertThatThrownBy(repository::findPending).isInstanceOf(SecurityException.class);
    }

    /**
     * 构造文件 Repository 测试使用的 checkpoint。
     *
     * @param checkpointId checkpoint 标识
     * @param routingKey 业务路由键
     * @param createdAtMs 创建时间
     * @return 测试 checkpoint
     */
    private static PendingCheckpoint checkpoint(String checkpointId, String routingKey, long createdAtMs) {
        return new PendingCheckpoint(
                checkpointId, routingKey, "p1", "checkpoint_request", "q?", createdAtMs
        );
    }
}
