package cn.org.chris.wake.adapter.feishu;

import cn.org.chris.wake.domain.model.InboundMessage;
import com.lark.oapi.channel.ChannelEventHandler;
import com.lark.oapi.channel.model.BotAddedEvent;
import com.lark.oapi.channel.model.NormalizedMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Listener 的消息投递、Bot 入群、重连状态与关闭契约。
 */
class FeishuWebSocketListenerTest {

    /**
     * 注册的 SDK 事件应投递到 Runner，并准确反映连接与重连状态。
     */
    @Test
    void shouldDispatchEventsAndTrackConnectionState() {
        FakeChannel channel = new FakeChannel();
        List<InboundMessage> inboundMessages = new ArrayList<>();
        List<String> botAdded = new ArrayList<>();
        FeishuWebSocketListener listener = new FeishuWebSocketListener(
                channel,
                new FeishuEventConverter(java.util.Set.of("oc_allowed")),
                message -> {
                    inboundMessages.add(message);
                    return CompletableFuture.completedFuture(null);
                },
                (chatId, name) -> botAdded.add(chatId + ":" + name)
        );

        listener.start().join();
        channel.emit("message", new NormalizedMessage(
                "om_1", "oc_allowed", "group", "ou_user", "用户", "hello", "text",
                List.of(), List.of(), false, false, "", "", "", 100L, null
        ));
        channel.emit("botAdded", new BotAddedEvent("oc_allowed", "ou_operator", "研发群", null));
        channel.emit("botAdded", new BotAddedEvent("oc_blocked", "ou_operator", "禁用群", null));
        channel.emit("reconnecting", new Object());

        assertThat(inboundMessages).extracting(InboundMessage::content).containsExactly("hello");
        assertThat(botAdded).containsExactly("oc_allowed:研发群");
        assertThat(listener.state()).isEqualTo(FeishuWebSocketListener.ConnectionState.RECONNECTING);

        channel.emit("reconnected", new Object());
        assertThat(listener.state()).isEqualTo(FeishuWebSocketListener.ConnectionState.CONNECTED);

        listener.close();
        assertThat(listener.state()).isEqualTo(FeishuWebSocketListener.ConnectionState.STOPPED);
        assertThat(channel.disconnected).isTrue();
    }

    /**
     * 首次连接失败时应暴露 FAILED 状态而不记录任何凭据。
     */
    @Test
    void shouldExposeFailedConnectionState() {
        FakeChannel channel = new FakeChannel();
        channel.connectResult = CompletableFuture.failedFuture(new IllegalStateException("secret-value"));
        FeishuWebSocketListener listener = new FeishuWebSocketListener(
                channel, new FeishuEventConverter(java.util.Set.of()),
                ignored -> CompletableFuture.completedFuture(null), null
        );

        assertThat(listener.start()).isCompletedExceptionally();
        assertThat(listener.state()).isEqualTo(FeishuWebSocketListener.ConnectionState.FAILED);
    }

    /**
     * 用内存事件表模拟官方 Channel，不建立真实网络连接。
     */
    private static final class FakeChannel implements FeishuWebSocketListener.ChannelPort {

        /** 事件名称到处理器的注册表。 */
        private final Map<String, ChannelEventHandler<?>> handlers = new HashMap<>();

        /** 可由测试覆盖的连接结果。 */
        private CompletableFuture<Void> connectResult = CompletableFuture.completedFuture(null);

        /** 是否已调用断开连接。 */
        private boolean disconnected;

        /**
         * 保存监听器注册的事件处理器。
         */
        @Override
        public <T> void on(String eventName, Class<T> eventType, ChannelEventHandler<T> handler) {
            handlers.put(eventName, handler);
        }

        /**
         * 返回测试预设的连接结果。
         */
        @Override
        public CompletableFuture<Void> connect() {
            return connectResult;
        }

        /**
         * 标记连接已关闭。
         */
        @Override
        public CompletableFuture<Void> disconnect() {
            disconnected = true;
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 同步触发指定 Channel 事件。
         */
        @SuppressWarnings("unchecked")
        private <T> void emit(String eventName, T event) {
            ((ChannelEventHandler<T>) handlers.get(eventName)).handle(event);
        }
    }
}
