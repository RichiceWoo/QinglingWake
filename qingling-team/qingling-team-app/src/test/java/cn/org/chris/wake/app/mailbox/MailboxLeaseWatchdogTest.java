package cn.org.chris.wake.app.mailbox;

import cn.org.chris.wake.app.runner.SerialDispatchRegistry;
import cn.org.chris.wake.domain.gateway.MailboxRepository;
import cn.org.chris.wake.domain.gateway.RuntimeIncidentGateway;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.model.AgentExecutionException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 mailbox watchdog 仅回收空闲路由的 stale 租约并幂等安排唤醒。
 */
class MailboxLeaseWatchdogTest {

    /**
     * stale 邮件应只在第一次扫描恢复并只安排一次 wake。
     */
    @Test
    void shouldRestoreStaleMailAndScheduleOnlyOneWake() {
        RecordingMailboxRepository mailboxRepository = new RecordingMailboxRepository();
        MailboxService mailboxService = new MailboxService(mailboxRepository);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        SerialDispatchRegistry dispatchRegistry = new SerialDispatchRegistry(Runnable::run);
        AtomicInteger wakes = new AtomicInteger();
        when(workspaceRepository.listProjectIds()).thenReturn(List.of("p1"));
        mailboxRepository.pmRestores.set(1);
        MailboxLeaseWatchdog watchdog = new MailboxLeaseWatchdog(
                mailboxService, workspaceRepository, dispatchRegistry,
                (role, projectId) -> "wake-" + wakes.incrementAndGet(),
                new FixedRuntimeIncidentGateway(false), Duration.ofMinutes(10)
        );

        assertThat(watchdog.tick()).isEqualTo(1);
        assertThat(watchdog.tick()).isZero();

        assertThat(wakes).hasValue(1);
    }

    /**
     * 角色 dispatch 活跃时不得触碰对应 mailbox，任务结束后的下一轮才允许恢复。
     */
    @Test
    void shouldSkipActiveRoleUntilDispatchCompletes() {
        RecordingMailboxRepository mailboxRepository = new RecordingMailboxRepository();
        MailboxService mailboxService = new MailboxService(mailboxRepository);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        SerialDispatchRegistry dispatchRegistry = new SerialDispatchRegistry(Runnable::run);
        CompletableFuture<Void> activeDispatch = new CompletableFuture<>();
        AtomicInteger wakes = new AtomicInteger();
        when(workspaceRepository.listProjectIds()).thenReturn(List.of("p1"));
        mailboxRepository.pmRestores.set(1);
        dispatchRegistry.submit("team:pm", () -> activeDispatch);
        MailboxLeaseWatchdog watchdog = new MailboxLeaseWatchdog(
                mailboxService, workspaceRepository, dispatchRegistry,
                (role, projectId) -> "wake-" + wakes.incrementAndGet(),
                new FixedRuntimeIncidentGateway(false), Duration.ofMinutes(10)
        );

        assertThat(watchdog.tick()).isZero();
        assertThat(mailboxRepository.pmResetCalls).hasValue(0);

        activeDispatch.complete(null);
        assertThat(watchdog.tick()).isEqualTo(1);
        assertThat(mailboxRepository.pmResetCalls).hasValue(1);
        assertThat(wakes).hasValue(1);
    }

    /**
     * 已进入 BLOCKED 的项目不得被 watchdog 恢复 stale 邮件或重新唤醒。
     */
    @Test
    void shouldNotRestoreBlockedProject() {
        RecordingMailboxRepository mailboxRepository = new RecordingMailboxRepository();
        MailboxService mailboxService = new MailboxService(mailboxRepository);
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        SerialDispatchRegistry dispatchRegistry = new SerialDispatchRegistry(Runnable::run);
        AtomicInteger wakes = new AtomicInteger();
        when(workspaceRepository.listProjectIds()).thenReturn(List.of("p1"));
        mailboxRepository.pmRestores.set(1);
        MailboxLeaseWatchdog watchdog = new MailboxLeaseWatchdog(
                mailboxService, workspaceRepository, dispatchRegistry,
                (role, projectId) -> "wake-" + wakes.incrementAndGet(),
                new FixedRuntimeIncidentGateway(true), Duration.ofMinutes(10)
        );

        assertThat(watchdog.tick()).isZero();
        assertThat(mailboxRepository.pmResetCalls).hasValue(0);
        assertThat(wakes).hasValue(0);
    }

    /** 提供固定 BLOCKED 查询结果且拒绝测试意外上报的运行时端口。 */
    private record FixedRuntimeIncidentGateway(
            /** 项目是否已经耗尽恢复预算。 */ boolean blocked
    ) implements RuntimeIncidentGateway {

        /** 本测试只验证查询路径，任何故障上报都属于测试装配错误。 */
        @Override
        public void reportAgentExecutionFailure(
                String projectId,
                String role,
                String occurrenceId,
                AgentExecutionException.FailureType failureType
        ) {
            throw new AssertionError("watchdog 不应上报 Agent 执行故障");
        }

        /** 本测试只验证查询路径，任何无项目故障上报都属于装配错误。 */
        @Override
        public void reportUnscopedAgentExecutionFailure(
                String role,
                String occurrenceId,
                AgentExecutionException.FailureType failureType
        ) {
            throw new AssertionError("watchdog 不应上报无项目 Agent 执行故障");
        }

        /** 返回构造时固定的项目阻断状态。 */
        @Override
        public boolean isRuntimeBlocked(String projectId) {
            return blocked;
        }
    }

    /** 记录 stale 恢复调用的最小 mailbox 仓储。 */
    private static final class RecordingMailboxRepository implements MailboxRepository {

        /** 下一次 PM 扫描应恢复的邮件数。 */
        private final AtomicInteger pmRestores = new AtomicInteger();

        /** PM stale 扫描调用次数。 */
        private final AtomicInteger pmResetCalls = new AtomicInteger();

        /** 测试不使用邮件追加。 */
        @Override
        public MailMessage appendOrReuseOpenTask(MailMessage message) {
            return message;
        }

        /** 测试不使用邮件领取。 */
        @Override
        public List<MailMessage> claimUnread(String projectId, String role) {
            return List.of();
        }

        /** 测试替身不提供按 ID 的邮件查询。 */
        @Override
        public Optional<MailMessage> findById(String projectId, String role, String messageId) {
            return Optional.empty();
        }

        /** 测试替身不提供处理中的任务分派。 */
        @Override
        public List<MailMessage> findInProgressTaskAssignments(String projectId, String role) {
            return List.of();
        }

        /** watchdog 测试不走即时任务释放路径。 */
        @Override
        public int releaseInProgressTaskAssignments(String projectId, String role) {
            return 0;
        }

        /** 测试不使用邮件完成。 */
        @Override
        public void markDone(String projectId, String role, String messageId) {
            // 无需记录。
        }

        /** 按角色记录扫描，并让 PM 的配置恢复数只消费一次。 */
        @Override
        public int resetStale(String projectId, String role, Duration timeout) {
            if (!"pm".equals(role)) {
                return 0;
            }
            pmResetCalls.incrementAndGet();
            return pmRestores.getAndSet(0);
        }
    }
}
