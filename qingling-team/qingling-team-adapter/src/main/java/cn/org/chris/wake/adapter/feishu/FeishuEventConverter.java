package cn.org.chris.wake.adapter.feishu;

import cn.org.chris.wake.domain.model.Attachment;
import cn.org.chris.wake.domain.model.InboundMessage;
import com.lark.oapi.channel.model.NormalizedMessage;
import com.lark.oapi.channel.model.ResourceDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 将飞书 SDK 归一化消息转换为领域入站消息，并在 adapter 边界执行群白名单策略。
 */
public final class FeishuEventConverter {

    /** 允许接收消息的群聊标识；空集合表示不限制群聊。 */
    private final Set<String> allowedChats;

    /**
     * 创建事件转换器。
     *
     * @param allowedChats 群聊白名单，单聊不受此配置限制
     */
    public FeishuEventConverter(Set<String> allowedChats) {
        this.allowedChats = allowedChats == null ? Set.of() : Set.copyOf(allowedChats);
    }

    /**
     * 转换消息；群聊未命中白名单或关键字段缺失时返回空。
     *
     * @param message 飞书 Channel 归一化消息
     * @return 可交给 Runner 的领域消息
     */
    public Optional<InboundMessage> convert(NormalizedMessage message) {
        Objects.requireNonNull(message, "message 不能为空");
        String chatType = text(message.getChatType());
        String chatId = text(message.getChatId());
        String senderId = text(message.getSenderId());
        String messageId = text(message.getMessageId());
        if (messageId.isBlank() || senderId.isBlank() || !isAllowed(chatId, chatType)) {
            return Optional.empty();
        }
        String routingKey = routingKey(chatType, senderId, chatId, text(message.getThreadId()));
        if (routingKey == null) {
            return Optional.empty();
        }
        String rootId = text(message.getRootId());
        if (rootId.isBlank()) {
            rootId = messageId;
        }
        return Optional.of(new InboundMessage(
                routingKey,
                text(message.getContent()),
                messageId,
                rootId,
                senderId,
                message.getCreateTime(),
                false,
                firstAttachment(message.getResources()),
                Map.of("source", "feishu", "messageType", text(message.getRawContentType()))
        ));
    }

    /**
     * 判断 Bot 入群事件对应群聊是否允许进入业务回调。
     *
     * @param chatId 飞书群聊标识
     * @return 白名单为空或命中时为 true
     */
    public boolean isBotAddedAllowed(String chatId) {
        return allowedChats.isEmpty() || allowedChats.contains(text(chatId));
    }

    /**
     * 单聊始终放行，群聊按配置的白名单过滤。
     */
    private boolean isAllowed(String chatId, String chatType) {
        if ("p2p".equals(chatType)) {
            return true;
        }
        return !chatId.isBlank() && (allowedChats.isEmpty() || allowedChats.contains(chatId));
    }

    /**
     * 按 Python 兼容规则生成 p2p、group 或 thread 路由键。
     */
    private static String routingKey(String chatType, String senderId, String chatId, String threadId) {
        if ("p2p".equals(chatType)) {
            return "p2p:" + senderId;
        }
        if (chatId.isBlank()) {
            return null;
        }
        if (!threadId.isBlank()) {
            return "thread:" + chatId + ":" + threadId;
        }
        return "group:" + chatId;
    }

    /**
     * 从 SDK 资源列表中提取首个 image/file 附件，保持单附件领域契约。
     */
    private static Attachment firstAttachment(List<ResourceDescriptor> resources) {
        if (resources == null) {
            return null;
        }
        for (ResourceDescriptor resource : resources) {
            if (resource == null || !("image".equals(resource.getType()) || "file".equals(resource.getType()))) {
                continue;
            }
            String key = text(resource.getFileKey());
            if (key.isBlank()) {
                continue;
            }
            String fileName = text(resource.getFileName());
            if (fileName.isBlank()) {
                fileName = "image".equals(resource.getType()) ? key + ".jpg" : key;
            }
            return new Attachment(resource.getType(), key, fileName);
        }
        return null;
    }

    /**
     * 将 SDK 可空字符串标准化为空串。
     */
    private static String text(String value) {
        return value == null ? "" : value;
    }
}
