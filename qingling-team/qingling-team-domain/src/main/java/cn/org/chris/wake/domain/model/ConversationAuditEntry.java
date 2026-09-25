package cn.org.chris.wake.domain.model;

import java.util.Objects;

/**
 * 表示只用于审计、绝不回灌 Agent prompt 的单条对话记录。
 *
 * @param role user 或 assistant
 * @param content 消息正文
 * @param timestampMs 记录时间
 * @param sourceMessageId 可选来源消息标识
 */
public record ConversationAuditEntry(
        /** 对话角色。 */ String role,
        /** 本轮消息正文。 */ String content,
        /** 审计时间，单位毫秒。 */ long timestampMs,
        /** 飞书或 TestAPI 来源消息标识。 */ String sourceMessageId
) {
    /**
     * 校验审计角色并标准化空正文。
     */
    public ConversationAuditEntry {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("role 必须是 user 或 assistant");
        }
        content = Objects.requireNonNullElse(content, "");
    }
}
