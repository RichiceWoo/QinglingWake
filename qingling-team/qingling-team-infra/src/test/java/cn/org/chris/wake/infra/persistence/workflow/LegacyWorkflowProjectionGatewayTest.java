package cn.org.chris.wake.infra.persistence.workflow;

import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.workflow.OutboxAction;
import cn.org.chris.wake.domain.workflow.OutboxActionId;
import cn.org.chris.wake.domain.workflow.OutboxActionType;
import cn.org.chris.wake.infra.persistence.cron.FileCronJobRepository;
import cn.org.chris.wake.infra.persistence.event.JsonlEventRepository;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 runtime v2 动作重复投影不会在旧事件、邮箱和 Cron 中产生第二份副作用。
 */
class LegacyWorkflowProjectionGatewayTest {

    /** 临时工作区根目录。 */
    @TempDir
    Path tempDir;

    /** 固定动作创建和投影时间。 */
    private static final Instant NOW = Instant.parse("2026-10-25T02:00:00Z");

    /**
     * 验证事件按 outbox action ID 在文件锁内去重。
     */
    @Test
    void shouldProjectWorkflowEventExactlyOnce() {
        Fixture fixture = fixture();
        OutboxAction action = OutboxAction.pending(
                new OutboxActionId("complete-1:transition"),
                OutboxActionType.WORKFLOW_EVENT,
                "project-1",
                Map.of(
                        "action", "workflow_transitioned",
                        "execution_id", "work-1",
                        "from_stage", "PRODUCT_DESIGN",
                        "to_stage", "TECH_DESIGN"
                ),
                NOW
        );

        fixture.gateway().project(action);
        fixture.gateway().project(action);

        List<Map<String, Object>> events = fixture.events().readAll("project-1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("action")).isEqualTo("workflow_transitioned");
        Map<?, ?> payload = (Map<?, ?>) events.get(0).get("payload");
        assertThat(payload.get("outbox_action_id")).isEqualTo("complete-1:transition");
    }

    /**
     * 验证 review_request 使用稳定邮件 ID 去重且保留 execution 与 round 引用。
     */
    @Test
    void shouldProjectMailboxMessageExactlyOnce() {
        Fixture fixture = fixture();
        OutboxAction action = OutboxAction.pending(
                new OutboxActionId("complete-1:review-qa"),
                OutboxActionType.MAILBOX_MESSAGE,
                "qa",
                Map.of(
                        "message_type", "review_request",
                        "project_id", "project-1",
                        "execution_id", "work-1",
                        "review_round_id", "review-1",
                        "stage", "TECH_DESIGN"
                ),
                NOW
        );

        fixture.gateway().project(action);
        fixture.gateway().project(action);

        List<MailMessage> claimed = fixture.mailbox().claimUnread("project-1", "qa");
        assertThat(claimed).singleElement().satisfies(message -> {
            assertThat(message.id()).startsWith("msg-");
            assertThat(message.type()).isEqualTo("review_request");
            Map<?, ?> content = (Map<?, ?>) message.content();
            assertThat(content.get("outbox_action_id")).isEqualTo("complete-1:review-qa");
            assertThat(content.get("review_round_id")).isEqualTo("review-1");
        });
    }

    /**
     * 验证相同 wake 动作只保留一个稳定 Cron 任务。
     */
    @Test
    void shouldProjectCronWakeExactlyOnce() {
        Fixture fixture = fixture();
        OutboxAction action = OutboxAction.pending(
                new OutboxActionId("complete-1:wake"),
                OutboxActionType.CRON_WAKE,
                "team:rd",
                Map.of(
                        "routing_key", "team:rd",
                        "project_id", "project-1",
                        "execution_id", "work-2"
                ),
                NOW
        );

        fixture.gateway().project(action);
        fixture.gateway().project(action);

        assertThat(fixture.cron().findAll()).singleElement().satisfies(job -> {
            assertThat(job.id()).startsWith("job-");
            assertThat(job.payload().routingKey()).isEqualTo("team:rd");
            assertThat(job.payload().message()).isEqualTo("__wake__:new_mail:project-1");
        });
    }

    /**
     * 创建三个真实文件仓储共享的投影测试夹具。
     *
     * @return 投影夹具
     */
    private Fixture fixture() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        new FileWorkspaceRepository(tempDir).initializeProject("project-1");
        JsonlEventRepository events = new JsonlEventRepository(
                tempDir, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        FileMailboxRepository mailbox = new FileMailboxRepository(
                tempDir, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        FileCronJobRepository cron = new FileCronJobRepository(
                tempDir.resolve("cron/tasks.json"), objectMapper
        );
        LegacyWorkflowProjectionGateway gateway = new LegacyWorkflowProjectionGateway(
                events, mailbox, cron, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new Fixture(gateway, events, mailbox, cron);
    }

    /**
     * 汇总真实投影器和三个可直接断言的文件仓储。
     *
     * @param gateway 旧存储投影器
     * @param events 事件仓储
     * @param mailbox 邮箱仓储
     * @param cron Cron 仓储
     */
    private record Fixture(
            /** 旧存储投影器。 */ LegacyWorkflowProjectionGateway gateway,
            /** 事件文件仓储。 */ JsonlEventRepository events,
            /** 邮箱文件仓储。 */ FileMailboxRepository mailbox,
            /** Cron 文件仓储。 */ FileCronJobRepository cron
    ) {
    }
}
