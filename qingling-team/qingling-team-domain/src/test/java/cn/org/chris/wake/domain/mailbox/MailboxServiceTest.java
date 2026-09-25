package cn.org.chris.wake.domain.mailbox;

import cn.org.chris.wake.domain.gateway.MailboxRepository;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.model.MailStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证邮箱领域服务与 Python 角色、类型和初始状态契约一致。
 */
class MailboxServiceTest {

    /**
     * 合法邮件应以固定 ID、UTC 时间和 unread 状态交给 Repository。
     */
    @Test
    void shouldCreateUnreadMessageWithStableContract() {
        RecordingMailboxRepository repository = new RecordingMailboxRepository();
        MailboxService service = new MailboxService(
                repository,
                () -> "msg-1a2b3c4d",
                Clock.fixed(Instant.parse("2026-09-25T01:02:03Z"), ZoneOffset.UTC)
        );

        String messageId = service.send("proj-A", "pm", "manager", "task_assign", "产品设计", "请处理");

        assertThat(messageId).isEqualTo("msg-1a2b3c4d");
        assertThat(repository.appendedMessage).isEqualTo(new MailMessage(
                "msg-1a2b3c4d", "proj-A", "manager", "pm", "task_assign", "产品设计", "请处理",
                Instant.parse("2026-09-25T01:02:03Z"), MailStatus.UNREAD, null
        ));
    }

    /**
     * 非法收件角色、发件角色和业务类型必须在持久化前拒绝。
     */
    @Test
    void shouldRejectInvalidParticipantsAndType() {
        RecordingMailboxRepository repository = new RecordingMailboxRepository();
        MailboxService service = new MailboxService(repository, () -> "msg-deadbeef", Clock.systemUTC());

        assertThatThrownBy(() -> service.send("p1", "stranger", "manager", "task_assign", "s", "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.send("p1", "pm", "stranger", "task_assign", "s", "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.send("p1", "pm", "manager", "unknown", "s", "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.appendedMessage).isNull();
    }

    /**
     * stale 恢复必须要求正超时时间并携带 projectId 调用 Repository。
     */
    @Test
    void shouldValidateTimeoutAndDelegateResetStale() {
        RecordingMailboxRepository repository = new RecordingMailboxRepository();
        MailboxService service = new MailboxService(repository);

        assertThatThrownBy(() -> service.resetStale("p1", "pm", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        service.resetStale("p1", "pm", Duration.ofMinutes(10));

        assertThat(repository.resetProjectId).isEqualTo("p1");
        assertThat(repository.resetRole).isEqualTo("pm");
        assertThat(repository.resetTimeout).isEqualTo(Duration.ofMinutes(10));
    }

    /**
     * 记录领域服务调用参数，避免测试依赖运行时字节码代理。
     */
    private static final class RecordingMailboxRepository implements MailboxRepository {

        /** 最近追加的邮件。 */
        private MailMessage appendedMessage;

        /** 最近执行 stale 恢复的项目标识。 */
        private String resetProjectId;

        /** 最近执行 stale 恢复的角色。 */
        private String resetRole;

        /** 最近执行 stale 恢复的超时时间。 */
        private Duration resetTimeout;

        /**
         * 记录追加的邮件。
         *
         * @param message 待保存邮件
         */
        @Override
        public void append(MailMessage message) {
            appendedMessage = message;
        }

        /**
         * 本测试替身不提供待领取邮件。
         *
         * @param projectId 所属项目标识
         * @param role 目标角色
         * @return 空邮件列表
         */
        @Override
        public List<MailMessage> claimUnread(String projectId, String role) {
            return List.of();
        }

        /**
         * 本测试场景不需要记录完成状态。
         *
         * @param projectId 所属项目标识
         * @param role 邮箱角色
         * @param messageId 邮件标识
         */
        @Override
        public void markDone(String projectId, String role, String messageId) {
            // 测试替身无需持久化状态。
        }

        /**
         * 记录 stale 恢复调用参数。
         *
         * @param projectId 所属项目标识
         * @param role 邮箱角色
         * @param timeout 处理超时时间
         * @return 固定恢复数量
         */
        @Override
        public int resetStale(String projectId, String role, Duration timeout) {
            resetProjectId = projectId;
            resetRole = role;
            resetTimeout = timeout;
            return 0;
        }
    }
}
