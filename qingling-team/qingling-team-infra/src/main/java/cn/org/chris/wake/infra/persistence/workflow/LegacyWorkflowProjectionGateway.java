package cn.org.chris.wake.infra.persistence.workflow;

import cn.org.chris.wake.domain.gateway.CronJobRepository;
import cn.org.chris.wake.domain.gateway.EventRepository;
import cn.org.chris.wake.domain.gateway.MailboxRepository;
import cn.org.chris.wake.domain.gateway.WorkflowProjectionGateway;
import cn.org.chris.wake.domain.model.CronJob;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.model.MailStatus;
import cn.org.chris.wake.domain.workflow.OutboxAction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 使用稳定 action ID 将 runtime v2 outbox 幂等投影到旧事件、邮箱和 Cron 文件。
 */
public final class LegacyWorkflowProjectionGateway implements WorkflowProjectionGateway {

    /** 项目事件存储端口。 */
    private final EventRepository eventRepository;

    /** 角色邮箱存储端口。 */
    private final MailboxRepository mailboxRepository;

    /** Cron wake 存储端口。 */
    private final CronJobRepository cronJobRepository;

    /** 生成一次性 wake 时间的 UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建使用系统 UTC 时钟的旧存储投影器。
     *
     * @param eventRepository 事件仓储
     * @param mailboxRepository 邮箱仓储
     * @param cronJobRepository Cron 仓储
     */
    public LegacyWorkflowProjectionGateway(
            EventRepository eventRepository,
            MailboxRepository mailboxRepository,
            CronJobRepository cronJobRepository
    ) {
        this(eventRepository, mailboxRepository, cronJobRepository, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的旧存储投影器。
     *
     * @param eventRepository 事件仓储
     * @param mailboxRepository 邮箱仓储
     * @param cronJobRepository Cron 仓储
     * @param clock UTC 时钟
     */
    public LegacyWorkflowProjectionGateway(
            EventRepository eventRepository,
            MailboxRepository mailboxRepository,
            CronJobRepository cronJobRepository,
            Clock clock
    ) {
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository 不能为空");
        this.mailboxRepository = Objects.requireNonNull(mailboxRepository, "mailboxRepository 不能为空");
        this.cronJobRepository = Objects.requireNonNull(cronJobRepository, "cronJobRepository 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 按有限动作类型执行稳定标识投影。
     *
     * @param action 待投影 outbox 动作
     */
    @Override
    public void project(OutboxAction action) {
        Objects.requireNonNull(action, "action 不能为空");
        switch (action.type()) {
            case WORKFLOW_EVENT -> projectEvent(action);
            case MAILBOX_MESSAGE -> projectMailbox(action);
            case CRON_WAKE -> projectWake(action);
        }
    }

    /**
     * 将工作流事件按 action ID 追加一次。
     *
     * @param action 事件投影动作
     */
    private void projectEvent(OutboxAction action) {
        String eventAction = requiredAttribute(action, "action");
        Map<String, Object> payload = new LinkedHashMap<>(action.attributes());
        payload.remove("action");
        eventRepository.appendOnce(
                action.target(), action.actionId().value(), "manager", eventAction, payload
        );
    }

    /**
     * 使用 action ID 哈希生成稳定邮件 ID，所有消息类型都能在文件锁内去重。
     *
     * @param action 邮箱投影动作
     */
    private void projectMailbox(OutboxAction action) {
        String type = requiredAttribute(action, "message_type");
        String projectId = requiredAttribute(action, "project_id");
        String subject = action.attributes().getOrDefault(
                "subject", "review_request".equals(type) ? "工作流评审请求" : "工作流任务"
        );
        Map<String, Object> content = new LinkedHashMap<>(action.attributes());
        content.remove("message_type");
        content.remove("project_id");
        content.remove("subject");
        content.put("outbox_action_id", action.actionId().value());
        mailboxRepository.appendOrReuseOpenTask(new MailMessage(
                stableId("msg", action.actionId().value()), projectId, "manager", action.target(),
                type, subject, Map.copyOf(content), action.createdAt(), MailStatus.UNREAD, null
        ));
    }

    /**
     * 使用稳定任务 ID 注册一次性 new_mail wake，重复投影只覆盖或复用同一任务。
     *
     * @param action Cron 投影动作
     */
    private void projectWake(OutboxAction action) {
        String projectId = requiredAttribute(action, "project_id");
        String routingKey = action.attributes().getOrDefault("routing_key", action.target());
        long nowMs = clock.millis();
        long triggerAtMs = Math.max(nowMs, action.createdAt().plusSeconds(1).toEpochMilli());
        CronJob candidate = new CronJob(
                stableId("job", action.actionId().value()),
                "workflow-wake-" + stableId("action", action.actionId().value()),
                true,
                new CronJob.Schedule(CronJob.ScheduleKind.AT, triggerAtMs, null, null, null),
                new CronJob.Payload(routingKey, "__wake__:new_mail:" + projectId),
                new CronJob.State(null, null, null, null),
                nowMs,
                nowMs,
                true
        );
        cronJobRepository.saveWakeIfAbsent(candidate, nowMs);
    }

    /**
     * 读取动作必填属性并拒绝空值。
     *
     * @param action outbox 动作
     * @param name 属性名称
     * @return 非空属性值
     */
    private static String requiredAttribute(OutboxAction action, String name) {
        String value = action.attributes().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("outbox 缺少必填属性: " + name);
        }
        return value;
    }

    /**
     * 将任意合法 action ID 转换为兼容旧存储的短且稳定标识。
     *
     * @param prefix 标识前缀
     * @param value action ID 原值
     * @return 前缀加 8 位十六进制摘要，兼容 msg-[0-9a-f]{8} 协议
     */
    private static String stableId(String prefix, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return prefix + "-" + java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }
}
