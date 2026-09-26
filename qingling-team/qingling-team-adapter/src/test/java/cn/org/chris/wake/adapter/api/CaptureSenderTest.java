package cn.org.chris.wake.adapter.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 CaptureSender 的普通回复、Loading 卡片、纯文本和注册生命周期。
 */
class CaptureSenderTest {

    /**
     * 普通发送应按 rootId 完成对应的回复 Future。
     */
    @Test
    void shouldCaptureDirectReply() {
        CaptureSender sender = new CaptureSender();
        CompletableFuture<String> reply = sender.register("msg-1");

        sender.send("p2p:u1", "完成", "msg-1").join();

        assertThat(reply.join()).isEqualTo("完成");
        assertThat(sender.pendingCount()).isZero();
    }

    /**
     * Loading 卡片更新和 Slash 文本都应定位原始消息。
     */
    @Test
    void shouldCaptureCardUpdateAndTextReply() {
        CaptureSender sender = new CaptureSender();
        CompletableFuture<String> cardReply = sender.register("msg-card");
        String cardId = sender.sendThinking("p2p:u1", "msg-card").join();
        sender.updateCard(cardId, "卡片结果").join();

        CompletableFuture<String> textReply = sender.register("msg-text");
        sender.sendText("p2p:u1", "命令结果", "msg-text").join();

        assertThat(cardReply.join()).isEqualTo("卡片结果");
        assertThat(textReply.join()).isEqualTo("命令结果");
    }

    /**
     * 未注册消息不能等待，重复注册应让旧等待者明确失败。
     */
    @Test
    void shouldRejectUnknownAndSupersededRegistration() {
        CaptureSender sender = new CaptureSender();
        CompletableFuture<String> old = sender.register("msg-1");
        sender.register("msg-1");

        assertThatThrownBy(old::join).hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sender.waitForReply("missing", Duration.ofSeconds(1)).join())
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
