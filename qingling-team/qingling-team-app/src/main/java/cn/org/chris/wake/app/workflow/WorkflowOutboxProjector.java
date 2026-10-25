package cn.org.chris.wake.app.workflow;

import cn.org.chris.wake.domain.gateway.WorkflowProjectionGateway;
import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.workflow.OutboxAction;
import cn.org.chris.wake.domain.workflow.OutboxActionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 有界重放 runtime v2 pending outbox，并在每次副作用后原子确认动作状态。
 */
public final class WorkflowOutboxProjector implements AutoCloseable {

    /** 单个动作默认最多投影次数。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** runtime v2 权威仓储。 */
    private final WorkflowRuntimeRepository runtimeRepository;

    /** 可枚举项目的共享工作区端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** 对旧存储提供 action ID 幂等保证的投影端口。 */
    private final WorkflowProjectionGateway projectionGateway;

    /** 投影状态更新时间来源。 */
    private final Clock clock;

    /** 每项动作的有限尝试上限。 */
    private final int maxAttempts;

    /** 后台重放线程；未启动或已停止时为空。 */
    private ScheduledExecutorService executor;

    /**
     * 使用默认三次尝试和系统 UTC 时钟创建投影器。
     *
     * @param runtimeRepository runtime v2 仓储
     * @param workspaceRepository 工作区端口
     * @param projectionGateway 幂等投影端口
     */
    public WorkflowOutboxProjector(
            WorkflowRuntimeRepository runtimeRepository,
            WorkspaceRepository workspaceRepository,
            WorkflowProjectionGateway projectionGateway
    ) {
        this(runtimeRepository, workspaceRepository, projectionGateway, Clock.systemUTC(), DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * 创建可测试时钟和尝试预算的投影器。
     *
     * @param runtimeRepository runtime v2 仓储
     * @param workspaceRepository 工作区端口
     * @param projectionGateway 幂等投影端口
     * @param clock UTC 时钟
     * @param maxAttempts 单动作最大尝试次数
     */
    public WorkflowOutboxProjector(
            WorkflowRuntimeRepository runtimeRepository,
            WorkspaceRepository workspaceRepository,
            WorkflowProjectionGateway projectionGateway,
            Clock clock,
            int maxAttempts
    ) {
        this.runtimeRepository = Objects.requireNonNull(runtimeRepository, "runtimeRepository 不能为空");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.projectionGateway = Objects.requireNonNull(projectionGateway, "projectionGateway 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须大于零");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * 扫描全部已有项目并有限重放 pending outbox。
     *
     * @return 本轮成功确认的动作数
     */
    public int projectAllPending() {
        int applied = 0;
        for (String projectId : workspaceRepository.listProjectIds()) {
            applied += projectProject(projectId);
        }
        return applied;
    }

    /**
     * 启动固定延迟 pending outbox 重放；启动时立即执行一轮，重复启动保持幂等。
     *
     * @param interval 重放扫描间隔
     */
    public synchronized void start(java.time.Duration interval) {
        Objects.requireNonNull(interval, "interval 不能为空");
        if (interval.isZero() || interval.isNegative() || interval.toMillis() == 0L) {
            throw new IllegalArgumentException("interval 必须至少为一毫秒");
        }
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qingling-workflow-outbox");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                this::safeProjectAll, 0L, interval.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    /** 停止后台 outbox 重放；重复停止保持幂等。 */
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** 关闭后台 outbox 重放线程。 */
    @Override
    public void close() {
        stop();
    }

    /**
     * 按创建顺序投影单个项目；失败动作会记录类型并停止该项目后续动作以保持顺序。
     *
     * @param projectId 项目标识
     * @return 本轮成功确认的动作数
     */
    public int projectProject(String projectId) {
        WorkflowRuntimeState snapshot = runtimeRepository.find(projectId).orElse(null);
        if (snapshot == null) {
            return 0;
        }
        int applied = 0;
        for (OutboxAction candidate : snapshot.pendingOutbox()) {
            OutboxAction current = runtimeRepository.find(projectId)
                    .flatMap(state -> state.findOutboxAction(candidate.actionId()))
                    .orElseThrow(() -> new IllegalStateException("outbox action 在投影期间消失"));
            if (current.status() == OutboxActionStatus.APPLIED) {
                continue;
            }
            if (current.attempt() >= maxAttempts) {
                break;
            }
            try {
                projectionGateway.project(current);
                confirm(projectId, current, true, "");
                applied++;
            } catch (RuntimeException failure) {
                confirm(projectId, current, false, failure.getClass().getSimpleName());
                break;
            }
        }
        return applied;
    }

    /**
     * 在 runtime 项目锁内确认投影成功或记录一次脱敏失败。
     *
     * @param projectId 项目标识
     * @param attempted 本次尝试的动作快照
     * @param success 是否投影成功
     * @param failureType 脱敏失败类型
     */
    private void confirm(
            String projectId,
            OutboxAction attempted,
            boolean success,
            String failureType
    ) {
        runtimeRepository.update(projectId, state -> {
            OutboxAction latest = state.findOutboxAction(attempted.actionId())
                    .orElseThrow(() -> new IllegalStateException("outbox action 不存在"));
            if (latest.status() == OutboxActionStatus.APPLIED) {
                return state;
            }
            Instant clockNow = Instant.now(clock);
            Instant now = clockNow.isBefore(state.updatedAt()) ? state.updatedAt() : clockNow;
            OutboxAction updated = success ? latest.applied(now) : latest.failed(failureType, now);
            return state.replaceOutboxAction(updated, now);
        });
    }

    /** 保护后台线程，使单项目损坏不会永久终止后续重放周期。 */
    private void safeProjectAll() {
        try {
            projectAllPending();
        } catch (RuntimeException ignored) {
            // 动作失败已由 action attempt/failureType 呈现，下一轮继续扫描其他可处理项目。
        }
    }
}
