package cn.org.chris.wake.app.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;

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

    /**
     * 停止接纳后新任务必须明确失败，恢复接纳后才允许再次提交。
     */
    @Test
    void shouldRejectNewTasksAfterAdmissionStops() {
        SerialDispatchRegistry registry = new SerialDispatchRegistry(Runnable::run);
        registry.stopAccepting();

        CompletableFuture<String> rejected = registry.submit(
                "team:rd", () -> CompletableFuture.completedFuture("unexpected")
        );

        assertThatThrownBy(rejected::join).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(registry.isAccepting()).isFalse();
        registry.startAccepting();
        assertThat(registry.submit("team:rd", () -> CompletableFuture.completedFuture("ok")).join())
                .isEqualTo("ok");
    }

    /**
     * 排空超时后的统一取消必须传播到活跃 Future，并阻止排队任务启动。
     */
    @Test
    void shouldCancelRunningAndQueuedTasksAfterDrainTimeout() {
        SerialDispatchRegistry registry = new SerialDispatchRegistry(Runnable::run);
        CompletableFuture<String> active = new CompletableFuture<>();
        AtomicBoolean queuedStarted = new AtomicBoolean();
        CompletableFuture<String> first = registry.submit("team:qa", () -> active);
        CompletableFuture<String> queued = registry.submit("team:qa", () -> {
            queuedStarted.set(true);
            return CompletableFuture.completedFuture("unexpected");
        });
        registry.stopAccepting();

        assertThat(registry.awaitDrained(Duration.ofMillis(5))).isFalse();
        assertThat(registry.cancelOutstanding()).isEqualTo(2);

        assertThat(active).isCancelled();
        assertThat(first).isCancelled();
        assertThat(queued).isCancelled();
        assertThat(queuedStarted).isFalse();
        assertThatThrownBy(first::join).isInstanceOf(CancellationException.class);
        assertThat(registry.awaitDrained(Duration.ofSeconds(1))).isTrue();
    }
}
