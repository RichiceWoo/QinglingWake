package cn.org.chris.wake.domain.gateway;

import java.util.concurrent.CompletableFuture;

/**
 * 定义飞书 Sender 与测试 CaptureSender 共同遵循的出站端口。
 */
public interface SenderGateway {

    /**
     * 发送富文本或卡片回复。
     *
     * @param routingKey 目标路由键
     * @param content 回复正文
     * @param rootId 可选话题根标识
     * @return 发送完成信号
     */
    CompletableFuture<Void> send(String routingKey, String content, String rootId);

    /**
     * 发送 Loading 卡片并返回可更新的消息标识。
     *
     * @param routingKey 目标路由键
     * @param rootId 可选话题根标识
     * @return 卡片消息标识；无法创建时可为空
     */
    CompletableFuture<String> sendThinking(String routingKey, String rootId);

    /**
     * 使用最终内容更新已有卡片。
     *
     * @param cardMessageId 卡片消息标识
     * @param content 最终回复正文
     * @return 更新完成信号
     */
    CompletableFuture<Void> updateCard(String cardMessageId, String content);

    /**
     * 以纯文本方式发送降级回复。
     *
     * @param routingKey 目标路由键
     * @param content 回复正文
     * @param rootId 可选话题根标识
     * @return 发送完成信号
     */
    CompletableFuture<Void> sendText(String routingKey, String content, String rootId);
}
