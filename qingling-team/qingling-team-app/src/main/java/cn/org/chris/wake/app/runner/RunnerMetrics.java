package cn.org.chris.wake.app.runner;

/**
 * 隔离 Runner 与具体 Metrics 实现的应用层观测端口。
 */
public interface RunnerMetrics {

    /**
     * 记录一条已接收入站消息。
     *
     * @param routingType p2p、group、thread、team 或 unknown
     * @param hasAttachment 是否携带附件
     */
    void recordInbound(String routingType, boolean hasAttachment);

    /**
     * 更新指定路由类型的当前排队深度。
     *
     * @param routingType 路由类型
     * @param queueDepth 正在运行和等待中的消息数
     */
    void recordQueueDepth(String routingType, int queueDepth);

    /**
     * 记录不包含异常消息的失败分类。
     *
     * @param component runner、sender 或 attachment
     * @param errorType 异常简单类名
     */
    void recordFailure(String component, String errorType);

    /**
     * 返回无副作用实现，供未开启 Metrics 的运行模式使用。
     *
     * @return 丢弃全部观测事件的实现
     */
    static RunnerMetrics noop() {
        return NoopRunnerMetrics.INSTANCE;
    }

    /**
     * 无指标后端时使用的单例实现。
     */
    enum NoopRunnerMetrics implements RunnerMetrics {
        /** 丢弃全部指标调用的唯一实例。 */
        INSTANCE;

        /**
         * 忽略入站计数。
         *
         * @param routingType 路由类型
         * @param hasAttachment 是否含附件
         */
        @Override
        public void recordInbound(String routingType, boolean hasAttachment) {
            // 未配置指标后端时按设计保持静默。
        }

        /**
         * 忽略队列深度。
         *
         * @param routingType 路由类型
         * @param queueDepth 队列深度
         */
        @Override
        public void recordQueueDepth(String routingType, int queueDepth) {
            // 未配置指标后端时按设计保持静默。
        }

        /**
         * 忽略失败计数。
         *
         * @param component 失败组件
         * @param errorType 异常类型
         */
        @Override
        public void recordFailure(String component, String errorType) {
            // 未配置指标后端时按设计保持静默。
        }
    }
}
