package cn.org.chris.wake.domain.mailbox;

import cn.org.chris.wake.domain.gateway.MailboxRepository;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.model.MailStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 执行团队邮箱角色、消息类型和三态流转的领域校验。
 */
public final class MailboxService {

    /** 允许收发团队邮件的四个智能体角色。 */
    public static final Set<String> TEAM_ROLES = Set.of("manager", "pm", "rd", "qa");

    /** 仅允许作为发送方出现的人类桥接角色。 */
    public static final Set<String> EXTRA_SENDERS = Set.of("human", "feishu_bridge");

    /** 与 Python 附录 B 保持一致的消息类型白名单。 */
    public static final Set<String> MESSAGE_TYPES = Set.of(
            "task_assign", "task_done", "review_request", "review_done",
            "clarification_request", "clarification_answer", "error_alert", "checkpoint_response",
            "retro_trigger", "retro_report", "retro_approved", "retro_rejected",
            "retro_applied", "retro_apply_failed"
    );

    /** 团队邮箱持久化端口。 */
    private final MailboxRepository repository;

    /** 生成可测试的 msg-xxxxxxxx 标识。 */
    private final Supplier<String> messageIdSupplier;

    /** 提供可测试的 UTC 时间。 */
    private final Clock clock;

    /**
     * 创建使用随机 UUID 和系统 UTC 时钟的邮箱服务。
     *
     * @param repository 邮箱持久化端口
     */
    public MailboxService(MailboxRepository repository) {
        this(repository, MailboxService::randomMessageId, Clock.systemUTC());
    }

    /**
     * 创建可注入 ID 和时钟的邮箱服务。
     *
     * @param repository 邮箱持久化端口
     * @param messageIdSupplier 消息标识生成器
     * @param clock UTC 时钟
     */
    public MailboxService(MailboxRepository repository, Supplier<String> messageIdSupplier, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
        this.messageIdSupplier = Objects.requireNonNull(messageIdSupplier, "messageIdSupplier 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 校验参与方与消息类型后写入一封 unread 邮件。
     *
     * @param projectId 所属项目
     * @param to 接收角色
     * @param from 发送角色
     * @param type 业务消息类型
     * @param subject 邮件主题
     * @param content 字符串或结构化正文
     * @return 新邮件标识
     */
    public String send(String projectId, String to, String from, String type, String subject, Object content) {
        validateRole(to, false);
        validateRole(from, true);
        if (!MESSAGE_TYPES.contains(type)) {
            throw new IllegalArgumentException("invalid type: " + type);
        }
        MailMessage message = new MailMessage(
                messageIdSupplier.get(),
                requireText(projectId, "projectId"),
                from,
                to,
                type,
                requireText(subject, "subject"),
                content,
                clock.instant(),
                MailStatus.UNREAD,
                null
        );
        repository.append(message);
        return message.id();
    }

    /**
     * 原子领取指定角色的全部 unread 邮件。
     *
     * @param projectId 所属项目
     * @param role 接收角色
     * @return 已进入 in_progress 的快照
     */
    public List<MailMessage> readInbox(String projectId, String role) {
        validateRole(role, false);
        return repository.claimUnread(requireText(projectId, "projectId"), role);
    }

    /**
     * 将已领取邮件从 in_progress 转为 done。
     *
     * @param projectId 所属项目
     * @param role 接收角色
     * @param messageId 邮件标识
     */
    public void markDone(String projectId, String role, String messageId) {
        validateRole(role, false);
        repository.markDone(requireText(projectId, "projectId"), role, requireText(messageId, "messageId"));
    }

    /**
     * 恢复处理时间严格超过 timeout 的 in_progress 邮件。
     *
     * @param projectId 所属项目
     * @param role 接收角色
     * @param timeout 处理超时时间，必须为正
     * @return 恢复为 unread 的数量
     */
    public int resetStale(String projectId, String role, Duration timeout) {
        validateRole(role, false);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须为正数");
        }
        return repository.resetStale(requireText(projectId, "projectId"), role, timeout);
    }

    /**
     * 校验角色是否可作为收件方或包含桥接角色的发件方。
     *
     * @param role 待校验角色
     * @param allowExtraSender 是否接受 human 和 feishu_bridge
     */
    private static void validateRole(String role, boolean allowExtraSender) {
        boolean valid = TEAM_ROLES.contains(role) || allowExtraSender && EXTRA_SENDERS.contains(role);
        if (!valid) {
            throw new IllegalArgumentException("invalid role: " + role);
        }
    }

    /**
     * 校验业务文本不为空。
     *
     * @param value 待校验文本
     * @param field 字段名称
     * @return 原文本
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 生成与 Python 契约一致的八位十六进制邮件标识。
     *
     * @return msg-xxxxxxxx 格式标识
     */
    private static String randomMessageId() {
        return "msg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
