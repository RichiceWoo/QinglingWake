package cn.org.chris.wake.starter.runtime;

import cn.org.chris.wake.adapter.api.CaptureSender;
import cn.org.chris.wake.domain.gateway.SenderGateway;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 将 test 路由发送到 CaptureSender，其余路由发送到可选飞书 Sender。
 */
public final class RoutingSenderGateway implements SenderGateway {

    /** TestAPI 路由约定前缀。 */
    private static final String TEST_ROUTE_PREFIX = "test:";

    /** CaptureSender 生成的 Loading 卡片前缀。 */
    private static final String TEST_CARD_PREFIX = "test-card-thinking-";

    /** TestAPI 专用捕获 Sender。 */
    private final CaptureSender captureSender;

    /** 飞书关闭时为空的生产 Sender。 */
    private final SenderGateway feishuSender;

    /**
     * 创建按路由分发的统一 Sender。
     *
     * @param captureSender TestAPI 捕获 Sender
     * @param feishuSender 可空飞书 Sender
     */
    public RoutingSenderGateway(CaptureSender captureSender, SenderGateway feishuSender) {
        this.captureSender = Objects.requireNonNull(captureSender, "captureSender 不能为空");
        this.feishuSender = feishuSender;
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> send(String routingKey, String content, String rootId) {
        return sender(routingKey).send(routingKey, content, rootId);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<String> sendThinking(String routingKey, String rootId) {
        return sender(routingKey).sendThinking(routingKey, rootId);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> updateCard(String cardMessageId, String content) {
        if (cardMessageId != null && cardMessageId.startsWith(TEST_CARD_PREFIX)) {
            return captureSender.updateCard(cardMessageId, content);
        }
        return feishuSender == null
                ? CompletableFuture.completedFuture(null)
                : feishuSender.updateCard(cardMessageId, content);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> sendText(String routingKey, String content, String rootId) {
        return sender(routingKey).sendText(routingKey, content, rootId);
    }

    /** 为 test 路由选择捕获端口，无飞书模式下其余外部路由也安全降级为捕获端口。 */
    private SenderGateway sender(String routingKey) {
        if (routingKey != null && routingKey.startsWith(TEST_ROUTE_PREFIX)) {
            return captureSender;
        }
        return feishuSender == null ? captureSender : feishuSender;
    }
}
