package cn.org.chris.wake.domain.model;

import java.util.Map;
import java.util.Objects;

/**
 * 表示从飞书、TestAPI 或内部唤醒源进入系统的标准化消息。
 *
 * @param routingKey 会话路由键
 * @param content 纯文本内容
 * @param msgId 来源消息标识
 * @param rootId 话题根消息标识
 * @param senderId 发送者标识
 * @param timestampMs 创建时间的毫秒时间戳
 * @param cron 是否由定时任务触发
 * @param attachment 可选附件
 * @param meta 唤醒原因等扩展元数据
 */
public record InboundMessage(
        /** 会话路由键。 */ String routingKey,
        /** 纯文本消息内容。 */ String content,
        /** 来源消息唯一标识。 */ String msgId,
        /** 话题根消息标识。 */ String rootId,
        /** 发送者 open_id 或内部角色标识。 */ String senderId,
        /** 消息创建时间，单位毫秒。 */ long timestampMs,
        /** 是否为定时任务注入的消息。 */ boolean cron,
        /** 消息携带的可选附件。 */ Attachment attachment,
        /** wake_reason 等只读扩展信息。 */ Map<String, Object> meta
) {
    /**
     * 标准化可空文本和元数据，并保证关键路由字段存在。
     */
    public InboundMessage {
        Objects.requireNonNull(routingKey, "routingKey 不能为空");
        Objects.requireNonNull(msgId, "msgId 不能为空");
        Objects.requireNonNull(senderId, "senderId 不能为空");
        content = content == null ? "" : content;
        rootId = rootId == null ? "" : rootId;
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }
}
