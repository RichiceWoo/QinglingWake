package cn.org.chris.wake.app.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 routing key 串行边界、跨 key 并行和失败恢复。
 */
class SerialDispatchRegistryTest {

    /**
     * 同 key 第二项必须等待第一项，不同 key 不应被第一项阻塞。
     */
    @Test
    void shouldSerializeSameKeyAndAllowDifferentKeysToProgress() {
        SerialDispatchRegistry registry = new SerialDispatchRegistry(Runnable::run);
        CompletableFuture<String> firstGate = new CompletableFuture<>();
        AtomicBoolean secondStarted = new AtomicBoolean();
        AtomicBoolean otherKeyStarted = new AtomicBoolean();

        CompletableFuture<String> first = registry.submit("p2p:u1", () -> firstGate);
        CompletableFuture<String> second = registry.submit("p2p:u1", () -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture("second");
        });
        CompletableFuture<String> other = registry.submit("p2p:u2", () -> {
            otherKeyStarted.set(true);
            return CompletableFuture.completedFuture("other");
        });

        assertThat(secondStarted).isFalse();
        assertThat(otherKeyStarted).isTrue();
        assertThat(other.join()).isEqualTo("other");
        firstGate.complete("first");
        assertThat(first.join()).isEqualTo("first");
        assertThat(second.join()).isEqualTo("second");
        assertThat(secondStarted).isTrue();
    }

    /**
     * 前一项失败只影响自身 Future，后续同 key 任务仍必须执行。
     */
    @Test
    void shouldContinueQueueAfterFailure() {
        SerialDispatchRegistry registry = new SerialDispatchRegistry(Runnable::run);
        List<String> executed = new ArrayList<>();
        CompletableFuture<String> failureGate = new CompletableFuture<>();

        CompletableFuture<String> failed = registry.submit("team:rd", () -> {
            executed.add("failed");
            return failureGate;
        });
        CompletableFuture<String> next = registry.submit("team:rd", () -> {
            executed.add("next");
            return CompletableFuture.completedFuture("ok");
        });

        assertThat(executed).containsExactly("failed");
        failureGate.completeExceptionally(new IllegalStateException("boom"));
        assertThatThrownBy(failed::join).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(next.join()).isEqualTo("ok");
        assertThat(executed).containsExactly("failed", "next");
        assertThat(registry.pendingCount("team:rd")).isZero();
    }

    /**
     * 验证优雅停止可等待调用时已经入队的异步任务完成。
     */
    @Test
    void shouldAwaitQueuedTasksDuringGracefulShutdown() {
        SerialDispatchRegistry registry = new SerialDispatchRegistry(Runnable::run);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        registry.submit("p2p:user", () -> gate);

        CompletableFuture<Boolean> drained = CompletableFuture.supplyAsync(
                () -> registry.awaitDrained(Duration.ofSeconds(2))
        );
        gate.complete(null);

        assertThat(drained.join()).isTrue();
        assertThat(registry.pendingCount("p2p:user")).isZero();
    }
}
