package cn.org.chris.wake.app.checkpoint;

import cn.org.chris.wake.domain.checkpoint.HumanInputClassifier;
import cn.org.chris.wake.domain.gateway.CheckpointRepository;
import cn.org.chris.wake.domain.model.PendingCheckpoint;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 checkpoint 应用服务的注册、序列化、分类和幂等解决编排。
 */
class CheckpointServiceTest {

    /**
     * 自动标识、固定时间和 snake_case 序列化结果应保持 Python 契约。
     */
    @Test
    void shouldRegisterAndSerializePendingCheckpoint() {
        InMemoryCheckpointRepository repository = new InMemoryCheckpointRepository();
        CheckpointService service = new CheckpointService(
                repository,
                () -> "ckpt-12345678",
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
        );

        assertThat(service.register("p2p:abc123", "p1", "checkpoint_request", "确认吗？"))
                .isEqualTo("ckpt-12345678");
        assertThat(service.serializePending("p2p:abc123")).singleElement().satisfies(data -> {
            assertThat(data).containsEntry("checkpoint_id", "ckpt-12345678");
            assertThat(data).containsEntry("created_at_ms", 1_700_000_000_000L);
        });
    }

    /**
     * 分类应读取当前路由 pending，resolve 仅首次调用成功。
     */
    @Test
    void shouldClassifyAndResolveIdempotently() {
        InMemoryCheckpointRepository repository = new InMemoryCheckpointRepository();
        CheckpointService service = new CheckpointService(
                repository,
                () -> "ckpt-abcdef12",
                Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC)
        );
        service.register("p2p:abc123", "p1", "checkpoint_request", "确认吗？");

        HumanInputClassifier.Classification classification = service.classify("p2p:abc123", "同意", null);

        assertThat(classification.checkpointId()).isEqualTo("ckpt-abcdef12");
        assertThat(service.resolve(classification.checkpointId())).isTrue();
        assertThat(service.resolve(classification.checkpointId())).isFalse();
        assertThat(service.pending("p2p:abc123")).isEmpty();
    }

    /**
     * 提供应用服务测试所需的确定性内存 Repository。
     */
    private static final class InMemoryCheckpointRepository implements CheckpointRepository {

        /** 按注册顺序保存 checkpoint。 */
        private final List<PendingCheckpoint> checkpoints = new ArrayList<>();

        /** 保存已解决 checkpoint 标识。 */
        private final Set<String> resolved = new HashSet<>();

        /**
         * 追加一条 checkpoint。
         *
         * @param checkpoint 待保存 checkpoint
         */
        @Override
        public void register(PendingCheckpoint checkpoint) {
            checkpoints.add(checkpoint);
        }

        /**
         * 首次解决已注册 checkpoint 时记录标识。
         *
         * @param checkpointId checkpoint 标识
         * @param resolvedAt 解决时间
         * @return 首次解决时为 true
         */
        @Override
        public boolean resolve(String checkpointId, Instant resolvedAt) {
            boolean registered = checkpoints.stream()
                    .anyMatch(checkpoint -> checkpoint.checkpointId().equals(checkpointId));
            return registered && resolved.add(checkpointId);
        }

        /**
         * 返回全部未解决 checkpoint。
         *
         * @return pending 列表
         */
        @Override
        public List<PendingCheckpoint> findPending() {
            return checkpoints.stream()
                    .filter(checkpoint -> !resolved.contains(checkpoint.checkpointId()))
                    .toList();
        }

        /**
         * 返回指定路由未解决 checkpoint。
         *
         * @param routingKey 业务路由键
         * @return 当前路由 pending 列表
         */
        @Override
        public List<PendingCheckpoint> findPending(String routingKey) {
            return findPending().stream()
                    .filter(checkpoint -> checkpoint.routingKey().equals(routingKey))
                    .toList();
        }

        /**
         * 返回创建时间最新的 pending checkpoint。
         *
         * @param routingKey 业务路由键
         * @return 最新 pending 项
         */
        @Override
        public Optional<PendingCheckpoint> findLatestPending(String routingKey) {
            List<PendingCheckpoint> pending = findPending(routingKey);
            return pending.isEmpty() ? Optional.empty() : Optional.of(pending.get(pending.size() - 1));
        }
    }
}
