package cn.org.chris.wake.adapter.feishu;

import cn.org.chris.wake.domain.gateway.MetricsGateway;
import cn.org.chris.wake.domain.gateway.CronDispatchGateway;
import com.lark.oapi.channel.ChannelEventHandler;
import com.lark.oapi.channel.ChannelSubscription;
import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.LarkChannelFactory;
import com.lark.oapi.channel.config.LarkChannelOptions;
import com.lark.oapi.channel.model.BotAddedEvent;
import com.lark.oapi.channel.model.NormalizedMessage;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 维护飞书 WebSocket Channel 生命周期，并把消息与 Bot 入群事件交给应用层。
 */
public final class FeishuWebSocketListener implements AutoCloseable {

    /** 监听器日志，仅记录事件类型和异常类型，不记录凭据或消息正文。 */
    private static final Logger LOGGER = Logger.getLogger(FeishuWebSocketListener.class.getName());

    /** 飞书 Channel 的最小可测试边界。 */
    private final ChannelPort channel;

    /** 飞书事件到领域消息的转换器。 */
    private final FeishuEventConverter converter;

    /** Runner 暴露的异步消息投递端口。 */
    private final CronDispatchGateway dispatcher;

    /** Bot 入群后的可选业务回调，参数依次为 chatId 与群名称。 */
    private final BiConsumer<String, String> botAddedHandler;

    /** 飞书事件和错误指标端口。 */
    private final MetricsGateway metrics;

    /** 当前 WebSocket 连接状态，供运行期健康检查读取。 */
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.STOPPED);

    /**
     * 使用可替换 Channel 端口创建监听器。
     *
     * @param channel SDK Channel 适配端口
     * @param converter 入站事件转换器
     * @param dispatcher Runner 投递端口
     * @param botAddedHandler Bot 入群回调，可为 null
     */
    public FeishuWebSocketListener(
            ChannelPort channel,
            FeishuEventConverter converter,
            CronDispatchGateway dispatcher,
            BiConsumer<String, String> botAddedHandler
    ) {
        this(channel, converter, dispatcher, botAddedHandler, MetricsGateway.noop());
    }

    /**
     * 使用可替换 Channel 和指标端口创建监听器。
     */
    public FeishuWebSocketListener(
            ChannelPort channel,
            FeishuEventConverter converter,
            CronDispatchGateway dispatcher,
            BiConsumer<String, String> botAddedHandler,
            MetricsGateway metrics
    ) {
        this.channel = Objects.requireNonNull(channel, "channel 不能为空");
        this.converter = Objects.requireNonNull(converter, "converter 不能为空");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        this.botAddedHandler = botAddedHandler == null ? (chatId, name) -> { } : botAddedHandler;
        this.metrics = Objects.requireNonNull(metrics, "metrics 不能为空");
        registerHandlers();
    }

    /**
     * 使用官方 oapi-sdk 创建 WebSocket 监听器；SDK 自身负责断线重连。
     *
     * @param appId 飞书应用标识
     * @param appSecret 飞书应用密钥
     * @param allowedChats 群聊白名单，空集合表示不限制
     * @param dispatcher Runner 投递端口
     * @param botAddedHandler Bot 入群回调，可为 null
     * @return 已注册事件处理器但尚未连接的监听器
     */
    public static FeishuWebSocketListener create(
            String appId,
            String appSecret,
            Set<String> allowedChats,
            CronDispatchGateway dispatcher,
            BiConsumer<String, String> botAddedHandler
    ) {
        return create(appId, appSecret, allowedChats, dispatcher, botAddedHandler, MetricsGateway.noop());
    }

    /**
     * 使用官方 SDK 和指定指标端口创建 WebSocket 监听器。
     */
    public static FeishuWebSocketListener create(
            String appId,
            String appSecret,
            Set<String> allowedChats,
            CronDispatchGateway dispatcher,
            BiConsumer<String, String> botAddedHandler,
            MetricsGateway metrics
    ) {
        requireCredential(appId, "appId");
        requireCredential(appSecret, "appSecret");
        Set<String> whitelist = allowedChats == null ? Set.of() : Set.copyOf(allowedChats);
        LarkChannelOptions.PolicyConfig policy = new LarkChannelOptions.PolicyConfig();
        policy.setGroupAllowlist(whitelist.stream().toList());
        policy.setDmMode("open");
        policy.setRequireMention(false);
        LarkChannel channel = LarkChannelFactory.createLarkChannel(
                LarkChannelOptions.newBuilder(appId, appSecret)
                        .transport("websocket")
                        .policy(policy)
                        .source("qingling-team")
                        .build()
        );
        return new FeishuWebSocketListener(
                new SdkChannelPort(channel),
                new FeishuEventConverter(whitelist),
                dispatcher,
                botAddedHandler,
                metrics
        );
    }

    /**
     * 建立 WebSocket 长连接，并在连接完成或失败时更新健康状态。
     *
     * @return 连接建立完成信号
     */
    public CompletableFuture<Void> start() {
        state.set(ConnectionState.CONNECTING);
        return channel.connect().whenComplete((unused, failure) -> {
            if (failure == null) {
                state.set(ConnectionState.CONNECTED);
            } else {
                state.set(ConnectionState.FAILED);
                metrics.recordFailure("feishu", failure.getClass().getSimpleName());
                LOGGER.log(Level.WARNING, "飞书 WebSocket 连接失败，异常类型={0}", failure.getClass().getSimpleName());
            }
        });
    }

    /**
     * 返回当前连接状态，用于健康检查和重连可观测性。
     */
    public ConnectionState state() {
        return state.get();
    }

    /**
     * 停止接收入站事件并释放 SDK 连接资源。
     */
    @Override
    public void close() {
        state.set(ConnectionState.STOPPED);
        channel.disconnect().join();
    }

    /**
     * 注册消息、Bot 入群和重连状态处理器。
     */
    private void registerHandlers() {
        channel.on("message", NormalizedMessage.class, this::handleMessage);
        channel.on("botAdded", BotAddedEvent.class, this::handleBotAdded);
        channel.on("reconnecting", Object.class, ignored -> state.set(ConnectionState.RECONNECTING));
        channel.on("reconnected", Object.class, ignored -> state.set(ConnectionState.CONNECTED));
    }

    /**
     * 转换并异步投递消息，失败日志只保留异常类型。
     */
    private void handleMessage(NormalizedMessage message) {
        metrics.recordFeishuEvent("message", message == null ? null : message.getChatType());
        converter.convert(message).ifPresent(inbound -> dispatcher.dispatch(inbound)
                .whenComplete((unused, failure) -> {
                    if (failure != null) {
                        metrics.recordFailure("feishu", failure.getClass().getSimpleName());
                        LOGGER.log(Level.WARNING, "飞书消息投递失败，异常类型={0}", failure.getClass().getSimpleName());
                    }
                }));
    }

    /**
     * 对白名单内群聊触发 Bot 入群业务回调。
     */
    private void handleBotAdded(BotAddedEvent event) {
        metrics.recordFeishuEvent("botAdded", "group");
        if (event != null && converter.isBotAddedAllowed(event.getChatId())) {
            botAddedHandler.accept(event.getChatId(), event.getChatName());
        }
    }

    /**
     * 校验凭据存在，但不把凭据内容带入异常消息。
     */
    private static void requireCredential(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 未配置");
        }
    }

    /** 描述监听器连接和自动重连阶段。 */
    public enum ConnectionState {
        /** 尚未启动或已停止。 */
        STOPPED,
        /** 正在建立首次连接。 */
        CONNECTING,
        /** WebSocket 已连接。 */
        CONNECTED,
        /** SDK 正在执行断线重连。 */
        RECONNECTING,
        /** 首次连接失败。 */
        FAILED
    }

    /**
     * 抽象 SDK Channel，允许契约测试无网络触发事件和连接状态。
     */
    public interface ChannelPort {

        /** 注册指定类型的 Channel 事件处理器。 */
        <T> void on(String eventName, Class<T> eventType, ChannelEventHandler<T> handler);

        /** 启动 WebSocket 连接。 */
        CompletableFuture<Void> connect();

        /** 关闭 WebSocket 连接。 */
        CompletableFuture<Void> disconnect();
    }

    /**
     * 官方 LarkChannel 到最小端口的薄适配器。
     */
    private static final class SdkChannelPort implements ChannelPort {

        /** 官方飞书高层 Channel。 */
        private final LarkChannel delegate;

        /**
         * 创建官方 Channel 端口。
         */
        private SdkChannelPort(LarkChannel delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        }

        /**
         * 把类型安全处理器注册到 SDK 事件总线。
         */
        @Override
        public <T> void on(String eventName, Class<T> eventType, ChannelEventHandler<T> handler) {
            ChannelSubscription ignored = delegate.on(eventName, handler);
        }

        /**
         * 连接 SDK Channel，丢弃机器人身份返回值。
         */
        @Override
        public CompletableFuture<Void> connect() {
            return delegate.connect().thenApply(ignored -> null);
        }

        /**
         * 断开 SDK Channel。
         */
        @Override
        public CompletableFuture<Void> disconnect() {
            return delegate.disconnect();
        }
    }
}
