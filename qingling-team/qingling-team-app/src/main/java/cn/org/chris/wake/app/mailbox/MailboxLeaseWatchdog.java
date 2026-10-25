package cn.org.chris.wake.app.mailbox;

import cn.org.chris.wake.app.cron.WakeScheduler;
import cn.org.chris.wake.app.runner.RoutingKeyResolver;
import cn.org.chris.wake.app.runner.SerialDispatchRegistry;
import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.gateway.RuntimeIncidentGateway;
import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 周期恢复没有活跃角色 dispatch 或父 Agent 回合的超时 mailbox 租约，并为恢复邮件安排唯一唤醒。
 */
public final class MailboxLeaseWatchdog implements AutoCloseable {

    /** 默认 mailbox 处理租约上限。 */
    public static final Duration DEFAULT_LEASE_TIMEOUT = Duration.ofMinutes(10);

    /** 默认 watchdog 扫描间隔。 */
    public static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofSeconds(30);

    /** 团队邮箱领域服务。 */
    private final MailboxService mailboxService;

    /** 提供当前共享项目清单。 */
    private final WorkspaceRepository workspaceRepository;

    /** 提供路由空闲检查与接纳互斥。 */
    private final SerialDispatchRegistry dispatchRegistry;

    /** 为恢复出的 unread 邮件注册幂等唤醒。 */
    private final MailWakeScheduler wakeScheduler;

    /** 查询项目是否已因恢复耗尽进入 BLOCKED，防止 watchdog 重新激活。 */
    private final RuntimeIncidentGateway runtimeIncidentGateway;

    /** 查询真实父 Agent 回合是否仍活跃，避免路由快照竞态误回收租约。 */
    private final AgentGateway agentGateway;

    /** 可选 runtime v2 仓储；存在 aggregate 时 mailbox 必须服从 WorkExecution 状态。 */
    private final WorkflowRuntimeRepository workflowRuntimeRepository;

    /** mailbox 处理租约时长。 */
    private final Duration leaseTimeout;

    /** 后台扫描线程；未启动或已停止时为空。 */
    private ScheduledExecutorService executor;

    /**
     * 使用默认租约时长创建 watchdog。
     *
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享项目仓储
     * @param dispatchRegistry 路由串行注册表
     * @param wakeScheduler 唤醒调度器
     * @param runtimeIncidentGateway 运行时阻断查询端口
     * @param agentGateway 活跃父 Agent 查询端口
     */
    public MailboxLeaseWatchdog(
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            SerialDispatchRegistry dispatchRegistry,
            WakeScheduler wakeScheduler,
            RuntimeIncidentGateway runtimeIncidentGateway,
            AgentGateway agentGateway
    ) {
        this(
                mailboxService, workspaceRepository, dispatchRegistry,
                wakeScheduler::scheduleMailWake, runtimeIncidentGateway, agentGateway,
                null, DEFAULT_LEASE_TIMEOUT
        );
    }

    /**
     * 使用 runtime v2 权威状态和默认租约创建生产 watchdog。
     *
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享项目仓储
     * @param dispatchRegistry 路由串行注册表
     * @param wakeScheduler 唤醒调度器
     * @param runtimeIncidentGateway 运行时阻断查询端口
     * @param agentGateway 活跃父 Agent 查询端口
     * @param workflowRuntimeRepository runtime v2 仓储
     */
    public MailboxLeaseWatchdog(
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            SerialDispatchRegistry dispatchRegistry,
            WakeScheduler wakeScheduler,
            RuntimeIncidentGateway runtimeIncidentGateway,
            AgentGateway agentGateway,
            WorkflowRuntimeRepository workflowRuntimeRepository
    ) {
        this(
                mailboxService, workspaceRepository, dispatchRegistry,
                wakeScheduler::scheduleMailWake, runtimeIncidentGateway, agentGateway,
                workflowRuntimeRepository, DEFAULT_LEASE_TIMEOUT
        );
    }

    /**
     * 使用可测试租约时长创建 watchdog。
     *
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享项目仓储
     * @param dispatchRegistry 路由串行注册表
     * @param wakeScheduler 邮件唤醒端口
     * @param runtimeIncidentGateway 运行时阻断查询端口
     * @param agentGateway 活跃父 Agent 查询端口
     * @param leaseTimeout mailbox 处理租约时长
     */
    public MailboxLeaseWatchdog(
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            SerialDispatchRegistry dispatchRegistry,
            MailWakeScheduler wakeScheduler,
            RuntimeIncidentGateway runtimeIncidentGateway,
            AgentGateway agentGateway,
            Duration leaseTimeout
    ) {
        this(
                mailboxService, workspaceRepository, dispatchRegistry, wakeScheduler,
                runtimeIncidentGateway, agentGateway, null, leaseTimeout
        );
    }

    /**
     * 使用可选 runtime v2 仓储和可测试租约创建 watchdog。
     *
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享项目仓储
     * @param dispatchRegistry 路由串行注册表
     * @param wakeScheduler 邮件唤醒端口
     * @param runtimeIncidentGateway 运行时阻断查询端口
     * @param agentGateway 活跃父 Agent 查询端口
     * @param workflowRuntimeRepository runtime v2 仓储；为空时保持 legacy 行为
     * @param leaseTimeout mailbox 处理租约时长
     */
    public MailboxLeaseWatchdog(
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            SerialDispatchRegistry dispatchRegistry,
            MailWakeScheduler wakeScheduler,
            RuntimeIncidentGateway runtimeIncidentGateway,
            AgentGateway agentGateway,
            WorkflowRuntimeRepository workflowRuntimeRepository,
            Duration leaseTimeout
    ) {
        this.mailboxService = Objects.requireNonNull(mailboxService, "mailboxService 不能为空");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.dispatchRegistry = Objects.requireNonNull(dispatchRegistry, "dispatchRegistry 不能为空");
        this.wakeScheduler = Objects.requireNonNull(wakeScheduler, "wakeScheduler 不能为空");
        this.runtimeIncidentGateway = Objects.requireNonNull(
                runtimeIncidentGateway, "runtimeIncidentGateway 不能为空"
        );
        this.agentGateway = Objects.requireNonNull(agentGateway, "agentGateway 不能为空");
        this.workflowRuntimeRepository = workflowRuntimeRepository;
        this.leaseTimeout = requirePositive(leaseTimeout, "leaseTimeout");
    }

    /**
     * 启动固定延迟后台扫描；重复启动保持幂等。
     *
     * @param scanInterval 扫描间隔
     */
    public synchronized void start(Duration scanInterval) {
        Duration checkedInterval = requirePositive(scanInterval, "scanInterval");
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qingling-mailbox-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::safeTick, 0L, checkedInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 同步扫描全部项目与角色，返回本轮恢复为 unread 的邮件总数。
     *
     * @return 本轮恢复邮件数
     */
    public int tick() {
        int restored = 0;
        for (String projectId : workspaceRepository.listProjectIds()) {
            for (String role : MailboxService.TEAM_ROLES) {
                String routingKey = RoutingKeyResolver.TEAM_PREFIX + role;
                Optional<Integer> reset = dispatchRegistry.executeIfIdle(
                        routingKey,
                        () -> restoreAndWake(projectId, role)
                );
                restored += reset.orElse(0);
            }
        }
        return restored;
    }

    /** 停止后台扫描；重复停止保持幂等。 */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** 关闭 watchdog 后台扫描。 */
    @Override
    public void close() {
        stop();
    }

    /**
     * 恢复单个项目角色的 stale 邮件，并仅在确有恢复时安排邮件唤醒。
     *
     * @param projectId 项目标识
     * @param role 团队角色
     * @return 恢复邮件数
     */
    private int restoreAndWake(String projectId, String role) {
        if (runtimeIncidentGateway.isRuntimeBlocked(projectId)) {
            return 0;
        }
        if (agentGateway.hasActiveExecution(projectId, role)) {
            return 0;
        }
        if (!runtimeAllowsLeaseRecovery(projectId, role)) {
            return 0;
        }
        int restored = mailboxService.resetStale(projectId, role, leaseTimeout);
        if (restored > 0) {
            wakeScheduler.schedule(role, projectId);
        }
        return restored;
    }

    /**
     * v2 项目仅在唯一 Owner 执行等待领取、已领取或恢复中时允许回收 mailbox lease。
     *
     * <p>RUNNING、WAITING_REVIEW 和终态不能被旧 mailbox 租约反向覆盖；没有 v2 aggregate
     * 的项目继续使用 legacy 行为。</p>
     *
     * @param projectId 项目标识
     * @param role 邮箱角色
     * @return 是否允许旧 mailbox 恢复
     */
    private boolean runtimeAllowsLeaseRecovery(String projectId, String role) {
        if (workflowRuntimeRepository == null) {
            return true;
        }
        return workflowRuntimeRepository.find(projectId)
                .map(state -> {
                    List<WorkExecutionStatus> statuses = state.executions().stream()
                            .filter(execution -> execution.owner().equals(role))
                            .filter(execution -> !execution.status().terminal())
                            .map(execution -> execution.status())
                            .toList();
                    return statuses.size() == 1 && (statuses.get(0) == WorkExecutionStatus.PENDING
                            || statuses.get(0) == WorkExecutionStatus.CLAIMED
                            || statuses.get(0) == WorkExecutionStatus.RECOVERING);
                })
                .orElse(true);
    }

    /** 保护后台线程，单个损坏项目不得终止后续扫描。 */
    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException ignored) {
            // 下一轮继续扫描；具体存储异常由对应仓储日志和健康检查呈现。
        }
    }

    /**
     * 校验持续时间为至少一毫秒的正值。
     *
     * @param duration 待校验持续时间
     * @param name 参数名称
     * @return 原持续时间
     */
    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " 不能为空");
        if (duration.isZero() || duration.isNegative() || duration.toMillis() == 0L) {
            throw new IllegalArgumentException(name + " 必须至少为一毫秒");
        }
        return duration;
    }

    /** 只暴露 watchdog 所需的邮件唤醒能力，避免绑定具体 Cron 实现。 */
    @FunctionalInterface
    public interface MailWakeScheduler {

        /**
         * 为恢复出 unread 邮件的项目角色安排唤醒。
         *
         * @param role 团队角色
         * @param projectId 项目标识
         * @return 新建或复用的唤醒任务标识
         */
        String schedule(String role, String projectId);
    }
}
