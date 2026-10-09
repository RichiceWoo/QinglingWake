package cn.org.chris.wake.starter.runtime;

import cn.org.chris.wake.adapter.feishu.FeishuWebSocketListener;
import cn.org.chris.wake.app.cron.CronService;
import cn.org.chris.wake.app.cron.WakeScheduler;
import cn.org.chris.wake.app.mailbox.MailboxLeaseWatchdog;
import cn.org.chris.wake.app.runner.SerialDispatchRegistry;
import cn.org.chris.wake.infra.agentscope.AgentScopeAgentGateway;
import cn.org.chris.wake.infra.agentscope.McpSandboxConfiguration;
import cn.org.chris.wake.starter.config.QinglingTeamProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 按 Spec 顺序启动 heartbeat/Cron/飞书，并按反向依赖顺序优雅停止运行时。
 */
public final class RuntimeLifecycle implements SmartLifecycle {

    /** heartbeat 与邮件唤醒注册器。 */
    private final WakeScheduler wakeScheduler;

    /** Cron 后台调度器。 */
    private final CronService cronService;

    /** mailbox stale 租约后台恢复器。 */
    private final MailboxLeaseWatchdog mailboxLeaseWatchdog;

    /** Runner 路由队列注册表。 */
    private final SerialDispatchRegistry dispatchRegistry;

    /** 持有四角色 HarnessAgent 的网关。 */
    private final AgentScopeAgentGateway agentGateway;

    /** 持有各角色 MCP client 并负责显式释放服务端会话的配置。 */
    private final McpSandboxConfiguration sandboxConfiguration;

    /** 飞书关闭模式下返回空的 Listener 提供器。 */
    private final ObjectProvider<FeishuWebSocketListener> listenerProvider;

    /** 已校验的 Cron 和 Agent 生命周期配置。 */
    private final QinglingTeamProperties properties;

    /** 防止 Spring 重复 start/stop 导致资源重复关闭。 */
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 创建运行时生命周期协调器。
     */
    public RuntimeLifecycle(
            WakeScheduler wakeScheduler,
            CronService cronService,
            MailboxLeaseWatchdog mailboxLeaseWatchdog,
            SerialDispatchRegistry dispatchRegistry,
            AgentScopeAgentGateway agentGateway,
            McpSandboxConfiguration sandboxConfiguration,
            ObjectProvider<FeishuWebSocketListener> listenerProvider,
            QinglingTeamProperties properties
    ) {
        this.wakeScheduler = Objects.requireNonNull(wakeScheduler, "wakeScheduler 不能为空");
        this.cronService = Objects.requireNonNull(cronService, "cronService 不能为空");
        this.mailboxLeaseWatchdog = Objects.requireNonNull(
                mailboxLeaseWatchdog, "mailboxLeaseWatchdog 不能为空"
        );
        this.dispatchRegistry = Objects.requireNonNull(dispatchRegistry, "dispatchRegistry 不能为空");
        this.agentGateway = Objects.requireNonNull(agentGateway, "agentGateway 不能为空");
        this.sandboxConfiguration = Objects.requireNonNull(
                sandboxConfiguration, "sandboxConfiguration 不能为空"
        );
        this.listenerProvider = Objects.requireNonNull(listenerProvider, "listenerProvider 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    /** 注册错峰 heartbeat，启动 Cron，最后开放飞书入站。 */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        dispatchRegistry.startAccepting();
        wakeScheduler.startAccepting();
        registerHeartbeats();
        mailboxLeaseWatchdog.start(MailboxLeaseWatchdog.DEFAULT_SCAN_INTERVAL);
        cronService.start(properties.cron().tickInterval());
        FeishuWebSocketListener listener = listenerProvider.getIfAvailable();
        if (listener != null) {
            listener.start().join();
        }
    }

    /** 停止入站和任务生产、禁止新 dispatch，再有限排空或取消，最后关闭 AgentScope。 */
    @Override
    public void stop() {
        stop(() -> { });
    }

    /**
     * 执行同步优雅停止，并保证 Spring 回调最终执行。
     *
     * @param callback Spring 容器停止回调
     */
    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback 不能为空");
        if (!running.compareAndSet(true, false)) {
            callback.run();
            return;
        }
        try {
            FeishuWebSocketListener listener = listenerProvider.getIfAvailable();
            shutdownInOrder(
                    () -> closeListener(listener),
                    mailboxLeaseWatchdog::stop,
                    wakeScheduler::stopAccepting,
                    cronService::stop,
                    dispatchRegistry::stopAccepting,
                    () -> dispatchRegistry.awaitDrained(properties.agent().timeout()),
                    dispatchRegistry::cancelOutstanding,
                    this::closeAgentsAndMcp
            );
        } finally {
            callback.run();
        }
    }

    /** 返回当前运行状态。 */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** 允许 Spring 在上下文刷新时自动启动完整运行时。 */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /** 以最高阶段最后启动、最先停止，避免 Controller 先于入站关闭销毁。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** 按配置周期与错峰值注册四角色 heartbeat。 */
    private void registerHeartbeats() {
        List<String> roles = properties.team().roles();
        List<Long> stagger = properties.cron().heartbeatStaggerSeconds();
        Duration interval = properties.cron().heartbeatInterval();
        for (int index = 0; index < roles.size(); index++) {
            wakeScheduler.scheduleHeartbeat(roles.get(index), interval, Duration.ofSeconds(stagger.get(index)));
        }
    }

    /**
     * 按可靠关闭顺序执行各阶段；前序阶段失败时仍尽力关闭后续资源。
     *
     * @param closeInbound 关闭新入站动作
     * @param stopWatchdog 停止 mailbox watchdog 动作
     * @param stopWake 停止接纳一次性 wake 动作
     * @param stopCron 停止 Cron 和一次性 wake 投递动作
     * @param stopAccepting 停止 dispatch 接纳动作
     * @param awaitDrained 有限排空动作
     * @param cancelOutstanding 排空超时后的取消动作
     * @param closeAgents 关闭 MCP 与 Agent 动作
     */
    static void shutdownInOrder(
            Runnable closeInbound,
            Runnable stopWatchdog,
            Runnable stopWake,
            Runnable stopCron,
            Runnable stopAccepting,
            BooleanSupplier awaitDrained,
            Runnable cancelOutstanding,
            Runnable closeAgents
    ) {
        RuntimeException failure = null;
        failure = runShutdownStep(closeInbound, failure);
        failure = runShutdownStep(stopWatchdog, failure);
        failure = runShutdownStep(stopWake, failure);
        failure = runShutdownStep(stopCron, failure);
        failure = runShutdownStep(stopAccepting, failure);
        boolean drained = false;
        try {
            drained = awaitDrained.getAsBoolean();
        } catch (RuntimeException stepFailure) {
            failure = preserveFirst(failure, stepFailure);
        }
        if (!drained) {
            failure = runShutdownStep(cancelOutstanding, failure);
        }
        failure = runShutdownStep(closeAgents, failure);
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 关闭可选 Listener；无飞书模式下保持空操作。
     *
     * @param listener 可为空的飞书 Listener
     */
    private static void closeListener(FeishuWebSocketListener listener) {
        if (listener != null) {
            listener.close();
        }
    }

    /** 先关闭 Agent，再显式移除 MCP client；任一步失败都继续释放剩余资源。 */
    private void closeAgentsAndMcp() {
        RuntimeException failure = runShutdownStep(agentGateway::close, null);
        failure = runShutdownStep(sandboxConfiguration::close, failure);
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 执行单个关闭步骤并保留首个异常，保证后续资源仍被关闭。
     *
     * @param action 关闭动作
     * @param previous 之前捕获的异常
     * @return 应继续保留的首个异常
     */
    private static RuntimeException runShutdownStep(Runnable action, RuntimeException previous) {
        try {
            action.run();
            return previous;
        } catch (RuntimeException stepFailure) {
            return preserveFirst(previous, stepFailure);
        }
    }

    /**
     * 保留首个关闭异常，并把后续异常作为 suppressed 证据附加。
     *
     * @param previous 首个异常，可为空
     * @param current 当前异常
     * @return 首个异常
     */
    private static RuntimeException preserveFirst(RuntimeException previous, RuntimeException current) {
        if (previous == null) {
            return current;
        }
        previous.addSuppressed(current);
        return previous;
    }
}
