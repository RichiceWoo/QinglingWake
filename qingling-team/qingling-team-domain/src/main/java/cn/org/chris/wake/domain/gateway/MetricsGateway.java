package cn.org.chris.wake.domain.gateway;

/**
 * 隔离业务编排和外部适配器与具体 Metrics 实现的统一观测端口。
 */
public interface MetricsGateway {

    /** 记录飞书 Channel 事件。 */
    void recordFeishuEvent(String eventType, String chatType);

    /** 记录标准化入站消息。 */
    void recordInbound(String routingType, boolean hasAttachment);

    /** 更新当前路由队列深度。 */
    void recordQueueDepth(String routingType, int queueDepth);

    /** 按增量更新活跃 worker 数。 */
    void recordWorkerDelta(String routingType, int delta);

    /** 记录 HTTP 请求状态和耗时。 */
    void recordHttpRequest(String path, String method, int statusCode, double durationSeconds);

    /** 记录不含异常消息和敏感值的错误分类。 */
    void recordFailure(String component, String errorType);

    /** 返回无副作用实现。 */
    static MetricsGateway noop() {
        return NoopMetricsGateway.INSTANCE;
    }

    /** 未配置指标后端时使用的单例实现。 */
    enum NoopMetricsGateway implements MetricsGateway {
        /** 丢弃全部指标事件的唯一实例。 */
        INSTANCE;

        /** 忽略飞书事件。 */
        @Override public void recordFeishuEvent(String eventType, String chatType) { }

        /** 忽略入站消息。 */
        @Override public void recordInbound(String routingType, boolean hasAttachment) { }

        /** 忽略队列深度。 */
        @Override public void recordQueueDepth(String routingType, int queueDepth) { }

        /** 忽略 worker 增量。 */
        @Override public void recordWorkerDelta(String routingType, int delta) { }

        /** 忽略 HTTP 请求。 */
        @Override public void recordHttpRequest(String path, String method, int statusCode, double durationSeconds) { }

        /** 忽略失败分类。 */
        @Override public void recordFailure(String component, String errorType) { }
    }
}
