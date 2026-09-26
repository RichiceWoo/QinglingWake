package cn.org.chris.wake.infra.observability;

import cn.org.chris.wake.domain.gateway.MetricsGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用 Micrometer 复刻 Python Prometheus 指标名称、标签和类型。
 */
public final class MetricsRecorder implements MetricsGateway {

    /** Micrometer 指标注册表。 */
    private final MeterRegistry registry;

    /** 各路由类型的活跃 worker Gauge 值。 */
    private final ConcurrentMap<String, AtomicInteger> activeWorkers = new ConcurrentHashMap<>();

    /** 各路由类型的当前队列 Gauge 值。 */
    private final ConcurrentMap<String, AtomicInteger> queueSizes = new ConcurrentHashMap<>();

    /**
     * 创建指标记录器。
     *
     * @param registry Micrometer 注册表
     */
    public MetricsRecorder(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
    }

    /** {@inheritDoc} */
    @Override
    public void recordFeishuEvent(String eventType, String chatType) {
        counter("xiaopaw_feishu_events", "event_type", label(eventType), "chat_type", label(chatType)).increment();
    }

    /** {@inheritDoc} */
    @Override
    public void recordInbound(String routingType, boolean hasAttachment) {
        counter("xiaopaw_inbound_messages", "routing_key_type", label(routingType),
                "has_attachment", Boolean.toString(hasAttachment)).increment();
    }

    /** {@inheritDoc} */
    @Override
    public void recordWorkerDelta(String routingType, int delta) {
        AtomicInteger gauge = activeWorkers.computeIfAbsent(label(routingType), type ->
                registerGauge("xiaopaw_runner_workers_active", type));
        gauge.updateAndGet(current -> Math.max(0, current + delta));
    }

    /** {@inheritDoc} */
    @Override
    public void recordQueueDepth(String routingType, int queueDepth) {
        AtomicInteger gauge = queueSizes.computeIfAbsent(label(routingType), type ->
                registerGauge("xiaopaw_runner_queue_size", type));
        gauge.set(Math.max(0, queueDepth));
    }

    /** {@inheritDoc} */
    @Override
    public void recordHttpRequest(String path, String method, int statusCode, double durationSeconds) {
        String safePath = label(path);
        String safeMethod = label(method);
        counter("xiaopaw_http_requests", "path", safePath, "method", safeMethod,
                "status_code", Integer.toString(statusCode)).increment();
        Timer.builder("xiaopaw_http_request_duration_seconds")
                .description("TestAPI HTTP request duration")
                .tags("path", safePath, "method", safeMethod)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .register(registry)
                .record((long) (Math.max(0D, durationSeconds) * 1_000_000_000D), TimeUnit.NANOSECONDS);
    }

    /** {@inheritDoc} */
    @Override
    public void recordFailure(String component, String errorType) {
        counter("xiaopaw_errors", "component", label(component), "error_type", label(errorType)).increment();
    }

    /** 注册或获取带标签的 Counter。 */
    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    /** 注册指定路由类型的强引用 Gauge。 */
    private AtomicInteger registerGauge(String name, String routingType) {
        AtomicInteger value = new AtomicInteger();
        Gauge.builder(name, value, AtomicInteger::get)
                .tag("routing_key_type", routingType)
                .strongReference(true)
                .register(registry);
        return value;
    }

    /** 将缺失或空标签归一化为 unknown。 */
    private static String label(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
