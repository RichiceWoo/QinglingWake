package cn.org.chris.wake.app.runner;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 以 routing key 为粒度串行执行异步任务，不同 key 可由执行器并行调度。
 */
public final class SerialDispatchRegistry {

    /** 每个 routing key 当前排队尾部；尾部始终正常完成以免失败毒化队列。 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    /** 每个 routing key 尚未完成的任务数量。 */
    private final ConcurrentHashMap<String, AtomicInteger> pendingCounts = new ConcurrentHashMap<>();

    /** 启动每个异步任务供应器的执行器。 */
    private final Executor executor;

    /**
     * 使用 JVM 公共异步执行器创建注册表。
     */
    public SerialDispatchRegistry() {
        this(ForkJoinPool.commonPool());
    }

    /**
     * 使用指定执行器创建注册表，便于控制线程池和确定性测试。
     *
     * @param executor 不同 routing key 的并行执行器
     */
    public SerialDispatchRegistry(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
    }

    /**
     * 把任务追加到 key 的尾部；任务异常传给调用方，但后续任务仍会被调度。
     *
     * @param routingKey 串行化键
     * @param task 延迟创建的异步任务
     * @param <T> 任务结果类型
     * @return 当前任务自身的完成结果
     */
    public <T> CompletableFuture<T> submit(
            String routingKey,
            Supplier<CompletableFuture<T>> task
    ) {
        if (routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("routingKey 不能为空");
        }
        Objects.requireNonNull(task, "task 不能为空");
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<Void>> newTail = new AtomicReference<>();
        pendingCounts.computeIfAbsent(routingKey, ignored -> new AtomicInteger()).incrementAndGet();
        tails.compute(routingKey, (ignored, previous) -> {
            CompletableFuture<Void> ready = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous;
            CompletableFuture<Void> tail = ready.thenComposeAsync(
                    unused -> invoke(task, result), executor
            );
            newTail.set(tail);
            return tail;
        });
        CompletableFuture<Void> tail = newTail.get();
        tail.whenComplete((unused, failure) -> {
            tails.remove(routingKey, tail);
            pendingCounts.computeIfPresent(routingKey, (ignored, count) ->
                    count.decrementAndGet() == 0 ? null : count
            );
        });
        return result;
    }

    /**
     * 返回指定 key 尚未完成的任务数。
     *
     * @param routingKey 路由键
     * @return 正在运行和等待中的任务总数
     */
    public int pendingCount(String routingKey) {
        AtomicInteger count = pendingCounts.get(routingKey);
        return count == null ? 0 : count.get();
    }

    /**
     * 等待调用时已经进入队列的全部路由尾部完成，新入站应在调用前停止。
     *
     * @param timeout 最长排空时间
     * @return 全部完成为 true，超时为 false
     */
    public boolean awaitDrained(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout 必须大于零");
        }
        CompletableFuture<?>[] snapshot = tails.values().toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(snapshot).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException timeoutFailure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException impossible) {
            return true;
        }
    }

    /**
     * 安全调用任务供应器，并把业务成功或失败复制给结果 Future；队列尾部总是正常完成。
     *
     * @param task 延迟异步任务
     * @param result 当前任务对外结果
     * @param <T> 结果类型
     * @return 用于串行链接且不会异常完成的尾部
     */
    private static <T> CompletableFuture<Void> invoke(
            Supplier<CompletableFuture<T>> task,
            CompletableFuture<T> result
    ) {
        CompletableFuture<T> execution;
        try {
            execution = Objects.requireNonNull(task.get(), "task Future 不能为空");
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            return CompletableFuture.completedFuture(null);
        }
        return execution.handle((value, failure) -> {
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(unwrap(failure));
            }
            return null;
        });
    }

    /**
     * 去掉 CompletionException 包装，保留原始失败类型供指标分类。
     *
     * @param failure 异步失败
     * @return 最内层可用原因
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
