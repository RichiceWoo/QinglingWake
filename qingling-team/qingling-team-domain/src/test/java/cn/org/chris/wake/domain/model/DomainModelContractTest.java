package cn.org.chris.wake.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证从 Python dataclass 迁移而来的稳定字段与核心边界。
 */
class DomainModelContractTest {

    /**
     * 验证入站消息保留 Python fixture 所需字段并把可变 Map 转为只读快照。
     */
    @Test
    void shouldKeepInboundMessageContract() {
        InboundMessage message = new InboundMessage(
                "p2p:ou_001",
                "你好",
                "om_001",
                "",
                "ou_001",
                1_700_000_000_000L,
                false,
                new Attachment("file", "file-key", "需求.md"),
                Map.of("wake_reason", "feishu")
        );

        assertThat(message.routingKey()).isEqualTo("p2p:ou_001");
        assertThat(message.attachment().fileName()).isEqualTo("需求.md");
        assertThat(message.meta()).containsEntry("wake_reason", "feishu");
    }

    /**
     * 验证邮箱 id 契约和三态枚举可以表达 Python 邮箱记录。
     */
    @Test
    void shouldValidateMailIdentifierAndStatus() {
        MailMessage message = new MailMessage(
                "msg-1a2b3c4d",
                "project-001",
                "manager",
                "pm",
                "task_assign",
                "编写需求",
                Map.of("priority", "P0"),
                Instant.parse("2026-09-25T00:00:00Z"),
                MailStatus.UNREAD,
                null
        );

        assertThat(message.status()).isEqualTo(MailStatus.UNREAD);
        assertThatThrownBy(() -> new MailMessage(
                "bad-id", "p", "manager", "pm", "task_assign", "s", "c",
                Instant.EPOCH, MailStatus.UNREAD, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 验证三类 cron 规则各自要求正确的参数。
     */
    @Test
    void shouldValidateCronScheduleByKind() {
        CronJob.Schedule at = new CronJob.Schedule(CronJob.ScheduleKind.AT, 1000L, null, null, null);
        CronJob.Schedule every = new CronJob.Schedule(CronJob.ScheduleKind.EVERY, null, 30_000L, null, null);
        CronJob.Schedule cron = new CronJob.Schedule(
                CronJob.ScheduleKind.CRON, null, null, "0 0 * * *", "Asia/Shanghai"
        );

        assertThat(at.atMs()).isEqualTo(1000L);
        assertThat(every.everyMs()).isEqualTo(30_000L);
        assertThat(cron.timezone()).isEqualTo("Asia/Shanghai");
        assertThatThrownBy(() -> new CronJob.Schedule(
                CronJob.ScheduleKind.EVERY, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
