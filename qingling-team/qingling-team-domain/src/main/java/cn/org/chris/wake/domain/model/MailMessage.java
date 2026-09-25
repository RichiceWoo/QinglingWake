package cn.org.chris.wake.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 表示共享项目邮箱中的一封消息，字段与 Python JSON 契约保持一致。
 *
 * @param id msg-xxxxxxxx 格式的消息标识
 * @param projectId 所属项目标识
 * @param from 发送角色
 * @param to 接收角色
 * @param type 业务消息类型
 * @param subject 邮件主题
 * @param content 字符串或结构化业务内容
 * @param timestamp 创建时间
 * @param status 三态处理状态
 * @param processingSince 开始处理时间
 */
public record MailMessage(
        /** 团队邮件唯一标识。 */ String id,
        /** 所属项目标识。 */ String projectId,
        /** 发送方角色。 */ String from,
        /** 接收方角色。 */ String to,
        /** 业务消息类型。 */ String type,
        /** 邮件主题。 */ String subject,
        /** 字符串或 Map 形式的正文。 */ Object content,
        /** UTC 创建时间。 */ Instant timestamp,
        /** 当前处理状态。 */ MailStatus status,
        /** 进入处理中状态的 UTC 时间。 */ Instant processingSince
) {
    /**
     * 校验邮箱消息的稳定标识及必需业务字段。
     */
    public MailMessage {
        if (id == null || !id.matches("^msg-[0-9a-f]{8}$")) {
            throw new IllegalArgumentException("id 必须符合 msg-[0-9a-f]{8}");
        }
        Objects.requireNonNull(projectId, "projectId 不能为空");
        Objects.requireNonNull(from, "from 不能为空");
        Objects.requireNonNull(to, "to 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(subject, "subject 不能为空");
        Objects.requireNonNull(timestamp, "timestamp 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
    }
}
