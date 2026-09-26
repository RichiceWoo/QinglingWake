package cn.org.chris.wake.starter.runtime;

import cn.org.chris.wake.adapter.feishu.FeishuWebSocketListener;
import cn.org.chris.wake.app.cron.CronService;
import cn.org.chris.wake.app.cron.WakeScheduler;
import cn.org.chris.wake.app.runner.SerialDispatchRegistry;
import cn.org.chris.wake.infra.agentscope.AgentScopeAgentGateway;
import cn.org.chris.wake.starter.config.QinglingTeamProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 Spec 顺序启动 heartbeat/Cron/飞书，并按反向依赖顺序优雅停止运行时。
 */
public final class RuntimeLifecycle implements SmartLifecycle {

    /** heartbeat 与邮件唤醒注册器。 */
    private final WakeScheduler wakeScheduler;

    /** Cron 后台调度器。 */
    private final CronService cronService;

    /** Runner 路由队列注册表。 */
    private final SerialDispatchRegistry dispatchRegistry;

    /** 持有四角色 HarnessAgent 的网关。 */
    private final AgentScopeAgentGateway agentGateway;

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
            SerialDispatchRegistry dispatchRegistry,
            AgentScopeAgentGateway agentGateway,
            ObjectProvider<FeishuWebSocketListener> listenerProvider,
            QinglingTeamProperties properties
    ) {
        this.wakeScheduler = Objects.requireNonNull(wakeScheduler, "wakeScheduler 不能为空");
        this.cronService = Objects.requireNonNull(cronService, "cronService 不能为空");
        this.dispatchRegistry = Objects.requireNonNull(dispatchRegistry, "dispatchRegistry 不能为空");
        this.agentGateway = Objects.requireNonNull(agentGateway, "agentGateway 不能为空");
        this.listenerProvider = Objects.requireNonNull(listenerProvider, "listenerProvider 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
    }

    /** 注册错峰 heartbeat，启动 Cron，最后开放飞书入站。 */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        registerHeartbeats();
        cronService.start(properties.cron().tickInterval());
        FeishuWebSocketListener listener = listenerProvider.getIfAvailable();
        if (listener != null) {
            listener.start().join();
        }
    }

    /** 停止飞书入站、排空 Runner、停止 Cron，最后关闭 AgentScope。 */
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
            if (listener != null) {
                listener.close();
            }
            dispatchRegistry.awaitDrained(properties.agent().timeout());
            cronService.stop();
            agentGateway.close();
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
}
