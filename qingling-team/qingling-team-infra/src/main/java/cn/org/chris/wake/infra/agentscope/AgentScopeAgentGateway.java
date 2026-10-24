package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.model.AgentExecutionException;
import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import cn.org.chris.wake.infra.memory.TurnMemoryIndexer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 将领域 AgentGateway 适配到四角色 AgentScope HarnessAgent。
 */
public final class AgentScopeAgentGateway implements AgentGateway, AutoCloseable {

    /** 生产环境轮询 Sub-Agent 终态的间隔，兼顾快速终止与任务仓库读取开销。 */
    private static final Duration DEFAULT_CHILD_TASK_POLL_INTERVAL = Duration.ofSeconds(1);

    /** 子任务异常终态保持该时长后才终止父回合，给正常父回复保留短暂收尾窗口。 */
    private static final Duration DEFAULT_CHILD_TASK_FAILURE_GRACE = Duration.ofSeconds(2);

    /** 父回合或 RUNNING 子任务缺少真实进度时允许保持的默认最长租约。 */
    public static final Duration DEFAULT_CHILD_TASK_STALL_TIMEOUT = Duration.ofMinutes(4).plusSeconds(30);

    /** RuntimeContext 中保存目标角色的键。 */
    public static final String ROLE_ATTRIBUTE = "role";

    /** RuntimeContext 中保存附件 workspace 相对路径的键。 */
    public static final String ATTACHMENT_PATHS_ATTRIBUTE = "attachmentPaths";

    /** RuntimeContext 中保存团队项目标识的键，父 Agent 和本地 Sub-Agent 共享该值。 */
    public static final String PROJECT_ID_ATTRIBUTE = "projectId";

    /** RuntimeContext 中保存外部或团队 dispatch 路由的键。 */
    public static final String ROUTING_KEY_ATTRIBUTE = "routingKey";

    /** 按角色绑定的可调用 Agent 运行时。 */
    private final Map<String, AgentRuntime> runtimes;

    /** Agent 回复后触发的可选非阻塞记忆索引器。 */
    private final TurnMemoryIndexer memoryIndexer;

    /** 提供按角色 MCP 熔断状态和一次受控连接重建，不重放原 Shell 命令。 */
    private final McpSandboxConfiguration sandboxConfiguration;

    /** 与角色模型装饰器共享且仅保存活动时间戳的注册表。 */
    private final ModelActivityRegistry modelActivityRegistry;

    /** 检查当前父 session 子任务状态的轮询间隔。 */
    private final Duration childTaskPollInterval;

    /** 子任务失败或取消后允许父 Agent 正常结束的宽限时间。 */
    private final Duration childTaskFailureGrace;

    /** RUNNING 子任务真实进度租约的最大静默时长。 */
    private final Duration childTaskStallTimeout;

    /** 当前仍在执行的父 Agent 回合，用于取消级联和悬挂 dispatch 快速诊断。 */
    private final ConcurrentHashMap<InvocationKey, ActiveInvocation> activeInvocations = new ConcurrentHashMap<>();

    /** 同角色并发结束前最后一次不可归因模型活动时间，防止并发结束后跨 session 续租。 */
    private final ConcurrentHashMap<String, Instant> ambiguousModelActivityThrough = new ConcurrentHashMap<>();

    /**
     * 以实际 HarnessAgent 映射创建领域 Gateway。
     *
     * @param agents 四角色 HarnessAgent
     */
    public AgentScopeAgentGateway(Map<String, HarnessAgent> agents) {
        this(
                adaptAgents(agents),
                RuntimeMode.PRODUCTION,
                noOpMemoryIndexer(),
                McpSandboxConfiguration.disabled(),
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE,
                DEFAULT_CHILD_TASK_STALL_TIMEOUT
        );
    }

    /**
     * 以实际 HarnessAgent 映射和 pgvector 索引器创建领域 Gateway。
     *
     * @param agents 四角色 HarnessAgent
     * @param memoryIndexer 非阻塞记忆索引入口
     */
    public AgentScopeAgentGateway(Map<String, HarnessAgent> agents, TurnMemoryIndexer memoryIndexer) {
        this(
                adaptAgents(agents),
                RuntimeMode.PRODUCTION,
                memoryIndexer,
                McpSandboxConfiguration.disabled(),
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE,
                DEFAULT_CHILD_TASK_STALL_TIMEOUT
        );
    }

    /**
     * 以实际 HarnessAgent、记忆索引器和 MCP 健康边界创建生产 Gateway。
     *
     * @param agents 四角色 HarnessAgent
     * @param memoryIndexer 非阻塞记忆索引入口
     * @param sandboxConfiguration AIO-Sandbox 熔断与连接恢复边界
     */
    public AgentScopeAgentGateway(
            Map<String, HarnessAgent> agents,
            TurnMemoryIndexer memoryIndexer,
            McpSandboxConfiguration sandboxConfiguration
    ) {
        this(
                adaptAgents(agents),
                RuntimeMode.PRODUCTION,
                memoryIndexer,
                sandboxConfiguration,
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE,
                DEFAULT_CHILD_TASK_STALL_TIMEOUT
        );
    }

    /**
     * 以共享模型活动注册表创建生产 Gateway，使流式生成能够续租唯一活跃父回合。
     *
     * @param agents 四角色 HarnessAgent
     * @param memoryIndexer 非阻塞记忆索引入口
     * @param sandboxConfiguration AIO-Sandbox 熔断与连接恢复边界
     * @param modelActivityRegistry 与角色模型装饰器共享的活动注册表
     */
    public AgentScopeAgentGateway(
            Map<String, HarnessAgent> agents,
            TurnMemoryIndexer memoryIndexer,
            McpSandboxConfiguration sandboxConfiguration,
            ModelActivityRegistry modelActivityRegistry
    ) {
        this(
                adaptAgents(agents),
                RuntimeMode.PRODUCTION,
                memoryIndexer,
                sandboxConfiguration,
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE,
                DEFAULT_CHILD_TASK_STALL_TIMEOUT,
                modelActivityRegistry
        );
    }

    /**
     * 使用可观测运行时创建测试适配器，不启动真实模型。
     *
     * @param runtimes 测试运行时
     * @param ignored 区分生产构造器的内部标记
     */
    AgentScopeAgentGateway(Map<String, AgentRuntime> runtimes, RuntimeMode ignored) {
        this(
                runtimes,
                ignored,
                noOpMemoryIndexer(),
                McpSandboxConfiguration.disabled(),
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE,
                DEFAULT_CHILD_TASK_STALL_TIMEOUT
        );
    }

    /**
     * 使用可观测运行时和记忆索引器创建测试适配器。
     *
     * @param runtimes 测试运行时
     * @param ignored 区分生产构造器的内部标记
     * @param memoryIndexer 非阻塞记忆索引入口
     */
    AgentScopeAgentGateway(
            Map<String, AgentRuntime> runtimes,
            RuntimeMode ignored,
            TurnMemoryIndexer memoryIndexer
    ) {
        this(
                runtimes,
                ignored,
                memoryIndexer,
                McpSandboxConfiguration.disabled(),
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE,
                DEFAULT_CHILD_TASK_STALL_TIMEOUT
        );
    }

    /**
     * 使用可观测运行时及可调轮询时序创建测试适配器。
     *
     * @param runtimes 测试运行时
     * @param ignored 区分生产构造器的内部标记
     * @param memoryIndexer 非阻塞记忆索引入口
     * @param childTaskPollInterval 子任务状态轮询间隔
     * @param childTaskFailureGrace 异常终态宽限时间
     */
    AgentScopeAgentGateway(
            Map<String, AgentRuntime> runtimes,
            RuntimeMode ignored,
            TurnMemoryIndexer memoryIndexer,
            Duration childTaskPollInterval,
            Duration childTaskFailureGrace
    ) {
        this(
                runtimes,
                ignored,
                memoryIndexer,
                McpSandboxConfiguration.disabled(),
                childTaskPollInterval,
                childTaskFailureGrace,
                DEFAULT_CHILD_TASK_STALL_TIMEOUT
        );
    }

    /**
     * 使用可观测运行时及完整可调子任务时序创建测试适配器。
     *
     * @param runtimes 测试运行时
     * @param ignored 区分生产构造器的内部标记
     * @param memoryIndexer 非阻塞记忆索引入口
     * @param childTaskPollInterval 子任务状态轮询间隔
     * @param childTaskFailureGrace 异常终态宽限时间
     * @param childTaskStallTimeout RUNNING 子任务真实进度租约
     */
    AgentScopeAgentGateway(
            Map<String, AgentRuntime> runtimes,
            RuntimeMode ignored,
            TurnMemoryIndexer memoryIndexer,
            Duration childTaskPollInterval,
            Duration childTaskFailureGrace,
            Duration childTaskStallTimeout
    ) {
        this(
                runtimes,
                ignored,
                memoryIndexer,
                McpSandboxConfiguration.disabled(),
                childTaskPollInterval,
                childTaskFailureGrace,
                childTaskStallTimeout,
                new ModelActivityRegistry()
        );
    }

    /**
     * 使用可观测运行时、MCP 健康边界及完整时序创建测试适配器。
     *
     * @param runtimes 测试运行时
     * @param ignored 区分生产构造器的内部标记
     * @param memoryIndexer 非阻塞记忆索引入口
     * @param sandboxConfiguration MCP 熔断与恢复边界
     * @param childTaskPollInterval 子任务状态轮询间隔
     * @param childTaskFailureGrace 异常终态宽限时间
     * @param childTaskStallTimeout RUNNING 子任务真实进度租约
     */
    AgentScopeAgentGateway(
            Map<String, AgentRuntime> runtimes,
            RuntimeMode ignored,
            TurnMemoryIndexer memoryIndexer,
            McpSandboxConfiguration sandboxConfiguration,
            Duration childTaskPollInterval,
            Duration childTaskFailureGrace,
            Duration childTaskStallTimeout
    ) {
        this(
                runtimes,
                ignored,
                memoryIndexer,
                sandboxConfiguration,
                childTaskPollInterval,
                childTaskFailureGrace,
                childTaskStallTimeout,
                new ModelActivityRegistry()
        );
    }

    /**
     * 使用共享模型活动注册表与完整可调时序创建测试适配器。
     *
     * @param runtimes 测试运行时
     * @param ignored 区分生产构造器的内部标记
     * @param memoryIndexer 非阻塞记忆索引入口
     * @param sandboxConfiguration MCP 熔断与恢复边界
     * @param childTaskPollInterval 子任务状态轮询间隔
     * @param childTaskFailureGrace 异常终态宽限时间
     * @param childTaskStallTimeout 父子任务真实进度租约
     * @param modelActivityRegistry 角色模型活动注册表
     */
    AgentScopeAgentGateway(
            Map<String, AgentRuntime> runtimes,
            RuntimeMode ignored,
            TurnMemoryIndexer memoryIndexer,
            McpSandboxConfiguration sandboxConfiguration,
            Duration childTaskPollInterval,
            Duration childTaskFailureGrace,
            Duration childTaskStallTimeout,
            ModelActivityRegistry modelActivityRegistry
    ) {
        Objects.requireNonNull(ignored, "runtimeMode 不能为空");
        if (runtimes == null || runtimes.isEmpty()) {
            throw new IllegalArgumentException("Agent 运行时不能为空");
        }
        this.runtimes = Map.copyOf(runtimes);
        this.memoryIndexer = Objects.requireNonNull(memoryIndexer, "memoryIndexer 不能为空");
        this.sandboxConfiguration = Objects.requireNonNull(
                sandboxConfiguration, "sandboxConfiguration 不能为空"
        );
        this.modelActivityRegistry = Objects.requireNonNull(
                modelActivityRegistry, "modelActivityRegistry 不能为空"
        );
        this.childTaskPollInterval = requirePositiveDuration(
                childTaskPollInterval, "childTaskPollInterval"
        );
        this.childTaskFailureGrace = requireNonNegativeDuration(
                childTaskFailureGrace, "childTaskFailureGrace"
        );
        this.childTaskStallTimeout = requirePositiveDuration(
                childTaskStallTimeout, "childTaskStallTimeout"
        );
    }

    /**
     * 仅把本轮正文发送给 Harness，并在适配层将 Mono 转换为 CompletableFuture。
     *
     * @param request 单轮 Agent 请求
     * @return 领域层异步回复
     */
    @Override
    public CompletableFuture<AgentReply> execute(AgentRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        long turnTimestampMs = System.currentTimeMillis();
        AgentRuntime runtime = requireRuntime(request.role());
        RuntimeContext context = RuntimeContext.builder()
                .userId(request.userId())
                .sessionId(request.sessionId())
                .putAll(request.attributes())
                .put(ROLE_ATTRIBUTE, request.role())
                .put(ATTACHMENT_PATHS_ATTRIBUTE, request.attachmentPaths())
                .build();
        InvocationKey invocationKey = new InvocationKey(request.role(), request.sessionId());
        ActiveInvocation invocation = new ActiveInvocation(
                runtime,
                context,
                Instant.now(),
                sandboxConfiguration.totalConnectionFailures(request.role())
        );
        activeInvocations.put(invocationKey, invocation);
        AtomicBoolean cancellationPropagated = new AtomicBoolean();
        Mono<AgentReply> parentCall = Mono.defer(() -> runtime.call(request.content(), context))
                .map(AgentScopeAgentGateway::toReply)
                .flatMap(reply -> awaitChildrenSettled(invocation)
                        .then(Mono.fromCallable(() -> ensureChildrenSucceeded(invocation, reply))))
                .flatMap(reply -> terminateWhenMcpOutcomeUnknown(invocation).thenReturn(reply))
                .doOnNext(reply -> scheduleMemoryIndex(request, reply, turnTimestampMs));
        return Mono.firstWithSignal(parentCall, failWhenExecutionCannotProgress(invocation))
                .doOnError(ignored -> cancelOnce(
                        invocation,
                        cancellationPropagated,
                        TaskSemanticsRegistry.CancelOrigin.RUNTIME_PARENT_FAILURE
                ))
                .doOnCancel(() -> cancelOnce(
                        invocation,
                        cancellationPropagated,
                        TaskSemanticsRegistry.CancelOrigin.RUNTIME_PARENT_FAILURE
                ))
                .doOnSuccess(ignored -> removeActiveInvocation(invocationKey, invocation))
                .doOnError(ignored -> removeActiveInvocation(invocationKey, invocation))
                .doOnCancel(() -> removeActiveInvocation(invocationKey, invocation))
                .toFuture();
    }

    /**
     * 幂等移除父回合，并在同角色仍有其他回合时封存当前角色不可归因的模型活动截止时间。
     *
     * @param invocationKey 待移除回合键
     * @param invocation 待移除回合实例
     */
    private void removeActiveInvocation(InvocationKey invocationKey, ActiveInvocation invocation) {
        if (!activeInvocations.remove(invocationKey, invocation)) {
            return;
        }
        if (activeInvocationCount(invocationKey.role()) == 0) {
            ambiguousModelActivityThrough.remove(invocationKey.role());
            return;
        }
        modelActivityRegistry.lastActivity(invocationKey.role()).ifPresent(activityAt ->
                ambiguousModelActivityThrough.merge(
                        invocationKey.role(), activityAt,
                        (existing, candidate) -> existing.isAfter(candidate) ? existing : candidate
                )
        );
    }

    /**
     * 父 Agent 已返回时继续持有 dispatch，直到当前逻辑子任务全部进入终态。
     *
     * @param invocation 当前父回合
     * @return 子任务全部终态后的完成信号
     */
    private Mono<Void> awaitChildrenSettled(ActiveInvocation invocation) {
        if (childrenSettled(invocation)) {
            return Mono.empty();
        }
        return Flux.interval(childTaskPollInterval)
                .onBackpressureDrop()
                .filter(ignored -> childrenSettled(invocation))
                .next()
                .then();
    }

    /**
     * 判断每个逻辑子 Agent 是否只有唯一最新任务且均已终态；没有子任务时视为已收敛。
     *
     * @param invocation 当前父回合
     * @return 子任务已全部收敛时为 true
     */
    private static boolean childrenSettled(ActiveInvocation invocation) {
        List<ChildTaskDiagnostic> diagnostics = safeTaskDiagnostics(invocation);
        if (!duplicateNonTerminalTasks(diagnostics).isEmpty()) {
            return false;
        }
        return latestTasksByAgent(diagnostics).stream()
                .allMatch(ChildTaskDiagnostic::terminal);
    }

    /**
     * 子任务收敛后拒绝 FAILED/CANCELLED 终态，避免父回复抢先结束 dispatch。
     *
     * @param invocation 当前父回合
     * @param reply 父 Agent 已生成的回复
     * @return 子任务全部成功时的原回复
     */
    private static AgentReply ensureChildrenSucceeded(ActiveInvocation invocation, AgentReply reply) {
        AgentExecutionException failure = childFailureAfterSettlement(invocation);
        if (failure != null) {
            throw failure;
        }
        return reply;
    }

    /**
     * 在子任务已经终态后生成稳定失败，复用统一故障分类且不套用 RUNNING 租约判断。
     *
     * @param invocation 当前父回合
     * @return FAILED/CANCELLED 类型化失败；全部成功时为 null
     */
    private static AgentExecutionException childFailureAfterSettlement(ActiveInvocation invocation) {
        List<ChildTaskDiagnostic> tasks = latestTasksByAgent(safeTaskDiagnostics(invocation));
        List<ChildTaskDiagnostic> failedTasks = tasks.stream()
                .filter(task -> "FAILED".equals(task.status()) || "CANCELLED".equals(task.status()))
                .toList();
        if (failedTasks.isEmpty()) {
            return null;
        }
        AgentExecutionException.FailureType failureType = failedTasks.stream()
                .anyMatch(task -> "FAILED".equals(task.status()))
                ? AgentExecutionException.FailureType.CHILD_FAILED
                : AgentExecutionException.FailureType.CHILD_CANCELLED;
        List<String> failures = failedTasks.stream()
                .map(task -> task.agentId() + "=" + task.status()
                        + "[kind=" + task.taskKind() + ",cancel_origin=" + task.cancelOrigin() + "]")
                .sorted()
                .toList();
        return new AgentExecutionException(
                failureType,
                "AgentScope 子任务异常终止，父 dispatch 已结束: " + String.join(",", failures)
        );
    }

    /**
     * 当父回合或子任务异常终态、真实进度租约过期时，使悬挂 dispatch 直接失败。
     *
     * @param invocation 当前父回合
     * @return 仅在确认异常终态持续超过宽限期后发出错误的信号
     */
    private Mono<AgentReply> failWhenExecutionCannotProgress(ActiveInvocation invocation) {
        return Flux.interval(childTaskPollInterval)
                .onBackpressureDrop()
                .concatMap(ignored -> recoverInfrastructureOrDiagnose(invocation))
                .concatMap(failure -> failure.failureType()
                        == AgentExecutionException.FailureType.MCP_CONNECTION_UNAVAILABLE
                        ? Mono.just(failure)
                        : Mono.delay(childTaskFailureGrace)
                                .flatMap(ignoredDelay -> Mono.justOrEmpty(
                                        executionFailure(invocation, Instant.now())
                                ))
                                .filter(rechecked -> sameFailure(failure, rechecked)))
                .next()
                .flatMap(failure -> Mono.error(failure));
    }

    /**
     * 首次观察到当前角色 MCP 熔断时重建连接但不重放命令，随后立即终止结果不可判定的父回合。
     *
     * @param invocation 当前父回合
     * @return 本轮稳定故障；成功恢复或仍健康时为空
     */
    private Mono<AgentExecutionException> recoverInfrastructureOrDiagnose(ActiveInvocation invocation) {
        String role = Objects.toString(invocation.context().get(ROLE_ATTRIBUTE), "");
        if (!sandboxConfiguration.hasUnavailableConnection(role)) {
            return Mono.justOrEmpty(executionFailure(invocation, Instant.now()));
        }
        if (!invocation.infrastructureRecoveryAttempted().compareAndSet(false, true)) {
            return Mono.just(mcpConnectionFailure(role));
        }
        return sandboxConfiguration.recoverUnavailableConnections(role)
                .onErrorReturn(false)
                // 连接恢复不能证明原始非幂等命令是否执行，必须结束本轮并由 incident 唤醒 Owner。
                .map(recovered -> mcpConnectionFailure(role));
    }

    /**
     * 在父回合成功出口核对连接失败计数，封住父回复与熔断轮询并发时遗漏工具故障的窗口。
     *
     * @param invocation 当前父回合
     * @return 没有新增连接失败时完成；否则完成一次受控重建后发出类型化错误
     */
    private Mono<Void> terminateWhenMcpOutcomeUnknown(ActiveInvocation invocation) {
        String role = Objects.toString(invocation.context().get(ROLE_ATTRIBUTE), "");
        int currentFailures = sandboxConfiguration.totalConnectionFailures(role);
        if (currentFailures <= invocation.initialConnectionFailures()) {
            return Mono.empty();
        }
        invocation.infrastructureRecoveryAttempted().compareAndSet(false, true);
        return sandboxConfiguration.recoverUnavailableConnections(role)
                .onErrorReturn(false)
                .then(Mono.error(mcpConnectionFailure(role)));
    }

    /**
     * 使用父 Agent 日志和子任务快照生成重复 continuation、停滞或异常终态的类型化故障。
     *
     * @param invocation 当前父回合
     * @param now 当前诊断时间
     * @return 稳定失败异常，未满足终止条件时为 null
     */
    private AgentExecutionException executionFailure(ActiveInvocation invocation, Instant now) {
        String role = Objects.toString(invocation.context().get(ROLE_ATTRIBUTE), "");
        if (invocation.infrastructureRecoveryAttempted().get()
                && sandboxConfiguration.hasUnavailableConnection(role)) {
            return mcpConnectionFailure(role);
        }
        List<ChildTaskDiagnostic> diagnostics = safeTaskDiagnostics(invocation);
        List<ChildTaskDiagnostic> duplicateTasks = duplicateNonTerminalTasks(diagnostics);
        if (!duplicateTasks.isEmpty()) {
            List<String> failures = duplicateTasks.stream()
                    .map(task -> task.agentId() + "=" + task.taskId() + "[status=" + task.status() + "]")
                    .sorted()
                    .toList();
            return new AgentExecutionException(
                    AgentExecutionException.FailureType.DUPLICATE_CHILD_TASK,
                    "AgentScope continuation 单飞失效，父 dispatch 已结束: "
                            + String.join(",", failures)
            );
        }
        List<ChildTaskDiagnostic> latestTasks = latestTasksByAgent(diagnostics);
        List<ChildTaskDiagnostic> stalledTasks = latestTasks.stream()
                .filter(task -> "RUNNING".equals(task.status()))
                .filter(task -> !task.terminal())
                .filter(task -> Duration.between(task.lastProgressAt(), now)
                        .compareTo(childTaskStallTimeout) >= 0)
                .toList();
        if (!stalledTasks.isEmpty()) {
            List<String> failures = stalledTasks.stream()
                    .map(task -> task.agentId() + "=RUNNING"
                            + "[task_id=" + task.taskId()
                            + ",last_progress_at=" + task.lastProgressAt() + "]")
                    .sorted()
                    .toList();
            return new AgentExecutionException(
                    AgentExecutionException.FailureType.CHILD_STALLED,
                    "AgentScope 子任务真实进度租约过期，父 dispatch 已结束: "
                            + String.join(",", failures)
            );
        }
        if (latestTasks.stream().anyMatch(task -> !task.terminal())) {
            return null;
        }
        List<ChildTaskDiagnostic> failedTasks = latestTasks.stream()
                .filter(task -> "FAILED".equals(task.status()) || "CANCELLED".equals(task.status()))
                .toList();
        if (failedTasks.isEmpty()) {
            Instant parentLastProgressAt = parentProgress(invocation).effectiveProgressAt();
            Instant lastProgressAt = latestTasks.stream()
                    .map(ChildTaskDiagnostic::lastProgressAt)
                    .filter(progressAt -> progressAt.isAfter(parentLastProgressAt))
                    .max(Comparator.naturalOrder())
                    .orElse(parentLastProgressAt);
            if (Duration.between(lastProgressAt, now).compareTo(childTaskStallTimeout) >= 0) {
                return new AgentExecutionException(
                        AgentExecutionException.FailureType.PARENT_STALLED,
                        "AgentScope 父回合真实进度租约过期，当前没有运行中子任务"
                                + "[last_progress_at=" + lastProgressAt + "]"
                );
            }
            return null;
        }
        AgentExecutionException.FailureType failureType = failedTasks.stream()
                .anyMatch(task -> "FAILED".equals(task.status()))
                ? AgentExecutionException.FailureType.CHILD_FAILED
                : AgentExecutionException.FailureType.CHILD_CANCELLED;
        List<String> failures = failedTasks.stream()
                .map(task -> task.agentId() + "=" + task.status()
                        + "[kind=" + task.taskKind() + ",cancel_origin=" + task.cancelOrigin() + "]")
                .sorted()
                .toList();
        return new AgentExecutionException(
                failureType,
                "AgentScope 子任务异常终止，父 dispatch 已结束: " + String.join(",", failures)
        );
    }

    /**
     * 创建不含 URL、命令、输出或鉴权信息的稳定 MCP 连接故障。
     *
     * @param role 当前父 Agent 角色
     * @return 可交给 Runner incident 边界的类型化异常
     */
    private static AgentExecutionException mcpConnectionFailure(String role) {
        return new AgentExecutionException(
                AgentExecutionException.FailureType.MCP_CONNECTION_UNAVAILABLE,
                "AIO-Sandbox MCP 调用结果不可判定，已尝试一次受控连接重建，父 dispatch 已结束[role="
                        + role + "]"
        );
    }

    /**
     * 按逻辑 agentId 选择创建时间最新的任务，使 send continuation 覆盖旧 spawn 终态。
     *
     * @param tasks 当前 session 的全部任务诊断
     * @return 每个 agentId 唯一且按 agentId 排序的最新任务
     */
    private static List<ChildTaskDiagnostic> latestTasksByAgent(List<ChildTaskDiagnostic> tasks) {
        Map<String, ChildTaskDiagnostic> latestByAgent = new LinkedHashMap<>();
        Comparator<ChildTaskDiagnostic> recency = Comparator
                .comparing(ChildTaskDiagnostic::createdAt)
                .thenComparing(ChildTaskDiagnostic::taskId);
        for (ChildTaskDiagnostic task : tasks) {
            latestByAgent.merge(
                    task.agentId(),
                    task,
                    (current, candidate) -> recency.compare(candidate, current) > 0 ? candidate : current
            );
        }
        return latestByAgent.values().stream()
                .sorted(Comparator.comparing(ChildTaskDiagnostic::agentId))
                .toList();
    }

    /**
     * 返回同一逻辑子 Agent 下全部重复未终态任务，供父回合在短宽限后主动失败并级联取消。
     *
     * @param tasks 当前 session 的全部任务诊断
     * @return 仅包含所属 agentId 出现至少两个未终态任务的稳定排序列表
     */
    private static List<ChildTaskDiagnostic> duplicateNonTerminalTasks(List<ChildTaskDiagnostic> tasks) {
        Map<String, List<ChildTaskDiagnostic>> activeByAgent = tasks.stream()
                .filter(task -> !task.terminal())
                .collect(java.util.stream.Collectors.groupingBy(
                        ChildTaskDiagnostic::agentId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));
        return activeByAgent.values().stream()
                .filter(activeTasks -> activeTasks.size() > 1)
                .flatMap(List::stream)
                .sorted(Comparator.comparing(ChildTaskDiagnostic::agentId)
                        .thenComparing(ChildTaskDiagnostic::createdAt)
                        .thenComparing(ChildTaskDiagnostic::taskId))
                .toList();
    }

    /**
     * 比较宽限期前后的稳定故障分类与摘要，避免状态变化后误杀父回合。
     *
     * @param expected 宽限期前故障
     * @param actual 宽限期后故障
     * @return 两次诊断仍指向同一故障时为 true
     */
    private static boolean sameFailure(
            AgentExecutionException expected,
            AgentExecutionException actual
    ) {
        return actual != null
                && expected.failureType() == actual.failureType()
                && Objects.equals(expected.getMessage(), actual.getMessage());
    }

    /**
     * 安全读取子任务诊断；诊断链自身失败时不应误杀仍在工作的父回合。
     *
     * @param invocation 当前父回合
     * @return 子任务诊断，读取失败时为空列表
     */
    private static List<ChildTaskDiagnostic> safeTaskDiagnostics(ActiveInvocation invocation) {
        try {
            List<ChildTaskDiagnostic> diagnostics = invocation.runtime().taskDiagnostics(invocation.context());
            return diagnostics == null ? List.of() : List.copyOf(diagnostics);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /**
     * 返回当前父回合及其 Sub-Agent 任务状态，供 E2E 把悬挂 dispatch 直接归因到 AgentScope。
     *
     * @return 不含提示词、输出和凭据的不可变诊断列表
     */
    public List<ActiveExecutionDiagnostic> activeExecutionDiagnostics() {
        return activeInvocations.entrySet().stream()
                .map(entry -> diagnostic(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ActiveExecutionDiagnostic::routingKey)
                        .thenComparing(ActiveExecutionDiagnostic::role))
                .toList();
    }

    /**
     * 查询项目 Owner 是否仍有活跃父回合，使 mailbox watchdog 不会仅凭路由瞬时空闲回收租约。
     *
     * @param projectId 项目标识
     * @param role 团队角色
     * @return 命中活跃父回合时为 true
     */
    @Override
    public boolean hasActiveExecution(String projectId, String role) {
        String checkedProjectId = Objects.requireNonNullElse(projectId, "");
        String checkedRole = Objects.requireNonNullElse(role, "");
        return activeInvocations.entrySet().stream().anyMatch(entry ->
                checkedRole.equals(entry.getKey().role())
                        && checkedProjectId.equals(Objects.toString(
                        entry.getValue().context().get(PROJECT_ID_ATTRIBUTE), ""
                ))
        );
    }

    /**
     * 把活跃回合转换为只含路由和任务状态的安全诊断。
     *
     * @param key 回合唯一键
     * @param invocation 活跃回合
     * @return 脱敏诊断
     */
    private ActiveExecutionDiagnostic diagnostic(InvocationKey key, ActiveInvocation invocation) {
        RuntimeContext context = invocation.context();
        String routingKey = Objects.toString(context.get(ROUTING_KEY_ATTRIBUTE), "");
        String projectId = Objects.toString(context.get(PROJECT_ID_ATTRIBUTE), "");
        ParentProgressSnapshot parentProgress = parentProgress(invocation);
        return new ActiveExecutionDiagnostic(
                key.role(),
                key.sessionId(),
                routingKey,
                projectId,
                invocation.startedAt(),
                parentProgress.effectiveProgressAt(),
                parentProgress.sessionProgressAt(),
                parentProgress.modelActivityAt(),
                parentProgress.source(),
                safeTaskDiagnostics(invocation)
        );
    }

    /**
     * 安全读取父 Agent 自身会话日志进度，诊断读取失败时回退到回合开始时间。
     *
     * @param invocation 当前父回合
     * @return 不早于回合开始时间的最后父进度
     */
    private ParentProgressSnapshot parentProgress(ActiveInvocation invocation) {
        Instant sessionProgressAt = invocation.startedAt();
        String source = "INVOCATION_START";
        try {
            Instant progressAt = invocation.runtime().parentProgressAt(invocation.context());
            if (progressAt != null && progressAt.isAfter(sessionProgressAt)) {
                sessionProgressAt = progressAt;
                source = "SESSION_STATE";
            }
        } catch (RuntimeException ignored) {
            // 诊断旁路失败不得改变正在执行的父回合。
        }
        String role = Objects.toString(invocation.context().get(ROLE_ATTRIBUTE), "");
        Instant modelActivityAt = null;
        Instant effectiveProgressAt = sessionProgressAt;
        if (activeInvocationCount(role) == 1) {
            Instant candidateActivityAt = modelActivityRegistry.lastActivity(role).orElse(Instant.EPOCH);
            Instant ambiguousThrough = ambiguousModelActivityThrough.getOrDefault(role, Instant.EPOCH);
            if (candidateActivityAt.isAfter(ambiguousThrough)
                    && candidateActivityAt.isAfter(invocation.startedAt())) {
                modelActivityAt = candidateActivityAt;
                if (candidateActivityAt.isAfter(effectiveProgressAt)) {
                    effectiveProgressAt = candidateActivityAt;
                    source = "MODEL_STREAM";
                }
            }
        }
        return new ParentProgressSnapshot(sessionProgressAt, modelActivityAt, effectiveProgressAt, source);
    }

    /**
     * 统计指定角色当前活跃父回合数量，只有唯一回合才能安全归因角色级模型活动。
     *
     * @param role 团队角色
     * @return 当前活跃父回合数量
     */
    private long activeInvocationCount(String role) {
        return activeInvocations.keySet().stream()
                .filter(key -> key.role().equals(role))
                .count();
    }

    /**
     * 返回父子任务停滞判定使用的真实进度租约，供 E2E Driver 派生更宽诊断阈值。
     *
     * @return 正值停滞租约
     */
    public Duration childTaskStallTimeout() {
        return childTaskStallTimeout;
    }

    /**
     * 只执行一次父子取消；取消钩子异常不得覆盖原始失败或阻断 Future 取消。
     *
     * @param invocation 待取消活跃回合
     * @param propagated 幂等标志
     */
    private static void cancelOnce(
            ActiveInvocation invocation,
            AtomicBoolean propagated,
            TaskSemanticsRegistry.CancelOrigin origin
    ) {
        if (!propagated.compareAndSet(false, true)) {
            return;
        }
        try {
            invocation.runtime().cancel(invocation.context(), origin);
        } catch (RuntimeException ignored) {
            // 原始调用失败或取消必须保持主因，清理失败由诊断快照和关闭流程兜底。
        }
    }

    /**
     * 触发记忆索引；即使调度器同步失败也不得影响主 Agent 回复。
     */
    private void scheduleMemoryIndex(AgentRequest request, AgentReply reply, long turnTimestampMs) {
        String routingKey = Objects.toString(request.attributes().get("routingKey"), request.userId());
        try {
            memoryIndexer.schedule(
                    request.sessionId(), routingKey, request.content(), reply.content(), turnTimestampMs
            );
        } catch (RuntimeException ignored) {
            // 记忆是可选旁路，任何同步调度失败都不能改变主回复语义。
        }
    }

    /**
     * 提供默认空实现，使未配置 memory.db_dsn 的构造路径完全不产生副作用。
     */
    private static TurnMemoryIndexer noOpMemoryIndexer() {
        return (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { };
    }

    /**
     * 清理 Harness 中指定 userId/sessionId 的状态，不影响应用层路由索引。
     *
     * @param role 目标角色
     * @param userId 用户标识
     * @param sessionId 会话标识
     */
    @Override
    public void clearSession(String role, String userId, String sessionId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        AgentRuntime runtime = requireRuntime(role);
        RuntimeContext context = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        runtime.cancel(context, TaskSemanticsRegistry.CancelOrigin.SESSION_CLEAR);
        runtime.clearContext(userId, sessionId);
    }

    /**
     * 关闭所有角色 Harness 资源。
     */
    @Override
    public void close() {
        activeInvocations.values().forEach(invocation -> {
            try {
                invocation.runtime().cancel(
                        invocation.context(),
                        TaskSemanticsRegistry.CancelOrigin.SHUTDOWN
                );
            } catch (RuntimeException ignored) {
                // 关闭继续处理其他角色，最终仍由 HarnessAgent.close() 关闭任务仓库。
            }
        });
        runtimes.values().forEach(runtime -> {
            try {
                runtime.cancelAll(TaskSemanticsRegistry.CancelOrigin.SHUTDOWN);
            } catch (RuntimeException ignored) {
                // 已脱离活跃 dispatch 的任务仍尽力终态化；单个角色失败不阻止其他角色清理。
            }
        });
        activeInvocations.clear();
        runtimes.values().forEach(AgentRuntime::close);
    }

    /**
     * 将生产 HarnessAgent 包装成窄运行时，保留真实 call 与 clearContext 调用。
     *
     * @param agents 角色 Agent 映射
     * @return 运行时映射
     */
    private static Map<String, AgentRuntime> adaptAgents(Map<String, HarnessAgent> agents) {
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("HarnessAgent 映射不能为空");
        }
        Map<String, AgentRuntime> adapted = new LinkedHashMap<>();
        agents.forEach((role, agent) -> adapted.put(
                role,
                new HarnessAgentRuntime(
                        Objects.requireNonNull(agent, "HarnessAgent 不能为空"),
                        TaskSemanticsRegistry.forRepository(agent.getTaskRepository())
                )
        ));
        return adapted;
    }

    /**
     * 获取目标角色运行时。
     *
     * @param role 角色名称
     * @return 对应运行时
     */
    private AgentRuntime requireRuntime(String role) {
        requireText(role, "role");
        AgentRuntime runtime = runtimes.get(role);
        if (runtime == null) {
            throw new IllegalArgumentException("未知团队角色: " + role);
        }
        return runtime;
    }

    /**
     * 将 AgentScope 消息转换为稳定领域结果，并兼容两种 skillsCalled 元数据键。
     *
     * @param message AgentScope 最终消息
     * @return 领域回复
     */
    private static AgentReply toReply(Msg message) {
        if (message == null) {
            return new AgentReply("", List.of());
        }
        Object skillsValue = message.getMetadata().get("skillsCalled");
        if (skillsValue == null) {
            skillsValue = message.getMetadata().get("skills_called");
        }
        return new AgentReply(message.getTextContent(), stringList(skillsValue));
    }

    /**
     * 将可选集合元数据安全转换为字符串列表，忽略空名称和未知类型。
     *
     * @param value AgentScope 消息元数据值
     * @return Skill 名称列表
     */
    private static List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : collection) {
            if (item != null && !item.toString().isBlank()) {
                values.add(item.toString());
            }
        }
        return List.copyOf(values);
    }

    /**
     * 验证路由标识非空。
     *
     * @param value 待验证文本
     * @param name 参数名
     */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    /**
     * 验证轮询时长为正数。
     *
     * @param value 待验证时长
     * @param name 配置名称
     * @return 已验证时长
     */
    private static Duration requirePositiveDuration(Duration value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于零");
        }
        return value;
    }

    /**
     * 验证宽限时长不为负数。
     *
     * @param value 待验证时长
     * @param name 配置名称
     * @return 已验证时长
     */
    private static Duration requireNonNegativeDuration(Duration value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " 不能为负数");
        }
        return value;
    }

    /**
     * 隔离 HarnessAgent 的最小异步调用面，支持无真实模型 smoke 测试。
     */
    interface AgentRuntime {

        /**
         * 执行单轮调用。
         *
         * @param content 仅本轮正文
         * @param context 路由上下文
         * @return AgentScope 异步消息
         */
        Mono<Msg> call(String content, RuntimeContext context);

        /**
         * 中断父 Agent 并取消当前 session 尚未结束的全部 Sub-Agent 任务。
         *
         * @param context 当前父回合上下文
         * @param origin 本次取消由父失败、会话清理或关闭流程触发
         */
        default void cancel(
                RuntimeContext context,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
            // 无真实 Harness 的测试替身默认无需清理后台任务。
        }

        /**
         * 返回当前 session 的 Sub-Agent 状态，禁止携带任务正文或模型输出。
         *
         * @param context 当前父回合上下文
         * @return 脱敏子任务状态
         */
        default List<ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            return List.of();
        }

        /**
         * 返回父 Agent 自身会话日志的最后真实变化时间，不使用 heartbeat 或轮询时间续租。
         *
         * @param context 当前父回合上下文
         * @return 最后真实进度时间；无法读取时为纪元时间
         */
        default Instant parentProgressAt(RuntimeContext context) {
            return Instant.EPOCH;
        }

        /**
         * 终态化运行时曾观察到的全部父会话任务，覆盖已脱离 activeInvocations 的后台任务。
         *
         * @param origin 本次全局取消来源
         */
        default void cancelAll(TaskSemanticsRegistry.CancelOrigin origin) {
            // 无真实 Harness 的测试替身默认没有持久化后台任务。
        }

        /**
         * 清除指定 Harness 会话。
         *
         * @param userId 用户标识
         * @param sessionId 会话标识
         */
        void clearContext(String userId, String sessionId);

        /**
         * 关闭运行时资源。
         */
        void close();
    }

    /**
     * 区分测试运行时构造器与生产 HarnessAgent 构造器的内部标记。
     */
    enum RuntimeMode {
        /** 生产和测试都使用相同转换逻辑，仅用于解决泛型擦除。 */
        PRODUCTION
    }

    /**
     * 将真实 HarnessAgent 委托给窄运行时接口。
     *
     * @param agent 实际 HarnessAgent
     */
    private record HarnessAgentRuntime(
            /** 负责会话、模型、Skill 与工具调用的 AgentScope Agent。 */ HarnessAgent agent,
            /** 保存子任务种类与取消来源的有界共享语义表。 */ TaskSemanticsRegistry semanticsRegistry
    ) implements AgentRuntime {

        /** 校验真实 Agent 与任务语义表均已装配。 */
        private HarnessAgentRuntime {
            Objects.requireNonNull(agent, "agent 不能为空");
            Objects.requireNonNull(semanticsRegistry, "semanticsRegistry 不能为空");
        }

        /**
         * 直接调用 HarnessAgent，正文不拼接审计历史。
         *
         * @param content 仅本轮正文
         * @param context 路由上下文
         * @return AgentScope 异步消息
         */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            semanticsRegistry.observeContext(context);
            return agent.call(content, context);
        }

        /**
         * 先取消当前 session 的所有未终态子任务，再中断父 Agent，避免父回合取消后子任务继续写文件。
         *
         * @param context 当前父回合上下文
         * @param origin 本次运行时取消来源
         */
        @Override
        public void cancel(
                RuntimeContext context,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
            semanticsRegistry.observeContext(context);
            TaskRepository repository = agent.getTaskRepository();
            String parentSessionId = context.getSessionId();
            try {
                for (BackgroundTask task : repository.listTasks(context, parentSessionId, null)) {
                    if (!task.isCompleted()) {
                        try {
                            semanticsRegistry.recordCancellation(task.getTaskId(), origin);
                            repository.cancelTask(context, parentSessionId, task.getTaskId());
                        } catch (RuntimeException ignored) {
                            persistTerminalFallback(context, parentSessionId, task, origin);
                        }
                        persistTerminalFallback(context, parentSessionId, task, origin);
                    }
                }
            } catch (RuntimeException ignored) {
                // 任务仓库查询失败时仍必须继续中断父 Agent。
            }
            try {
                agent.interrupt(context);
            } catch (RuntimeException ignored) {
                // 取消是幂等清理路径，父 Agent 已终态时 interrupt 失败无需覆盖原始结果。
            }
        }

        /**
         * 终态化该角色曾观察到的全部父会话，避免已脱离 activeInvocations 的任务在关闭后仍为 RUNNING。
         *
         * @param origin 本次全局取消来源
         */
        @Override
        public void cancelAll(TaskSemanticsRegistry.CancelOrigin origin) {
            for (RuntimeContext context : semanticsRegistry.knownContexts()) {
                cancel(context, origin);
            }
        }

        /**
         * 读取当前父 session 的子任务终态，供悬挂 dispatch 诊断使用。
         *
         * @param context 当前父回合上下文
         * @return 不含任务正文的状态列表
         */
        @Override
        public List<ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            semanticsRegistry.observeContext(context);
            return agent.getTaskRepository().listTasks(context, context.getSessionId(), null).stream()
                    .map(task -> {
                        TaskSemanticsRegistry.TaskSemantics semantics =
                                semanticsRegistry.semantics(task.getTaskId());
                        TaskProgressEvidence progressEvidence = taskProgressEvidence(context, task);
                        TaskSemanticsRegistry.ProgressLease progressLease = semanticsRegistry.observeProgress(
                                task.getTaskId(),
                                progressEvidence.fingerprint(),
                                progressEvidence.evidenceAt(),
                                Instant.now()
                        );
                        return new ChildTaskDiagnostic(
                                task.getTaskId(),
                                task.getAgentId(),
                                task.getTaskStatus().name(),
                                task.isCompleted(),
                                task.getCreatedAt(),
                                semantics.kind().name(),
                                semantics.cancelOrigin().name(),
                                progressLease.lastProgressAt()
                        );
                    })
                    .sorted(Comparator.comparing(ChildTaskDiagnostic::taskId))
                    .toList();
        }

        /**
         * 读取父角色自身 session 日志的最新修改时间，作为独立于子任务的进度租约。
         *
         * @param context 当前父回合上下文
         * @return 父角色会话日志最后修改时间
         */
        @Override
        public Instant parentProgressAt(RuntimeContext context) {
            return sessionLogEvidence(context, agent.getName()).lastModifiedAt();
        }

        /**
         * 若 AgentScope cancelTask 没有把持久化记录写成终态，则直接以 CANCELLED 做幂等兜底。
         *
         * @param context 父 Agent 运行上下文
         * @param parentSessionId 父 session 标识
         * @param task 待终态化后台任务
         * @param origin 取消来源
         */
        private void persistTerminalFallback(
                RuntimeContext context,
                String parentSessionId,
                BackgroundTask task,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
            try {
                TaskRecord record = agent.getWorkspaceManager().readTaskRecord(
                        context,
                        agent.getName(),
                        parentSessionId,
                        task.getTaskId()
                ).orElse(null);
                if (record == null || (record.getStatus() != null && record.getStatus().isTerminal())) {
                    return;
                }
                semanticsRegistry.recordCancellation(task.getTaskId(), origin);
                record.setCancelRequested(true);
                record.setStatus(TaskStatus.CANCELLED);
                record.setErrorMessage("runtime_terminated:" + origin.name());
                record.touch();
                agent.getWorkspaceManager().writeTaskRecord(
                        context,
                        agent.getName(),
                        parentSessionId,
                        record
                );
            } catch (RuntimeException ignored) {
                // 关闭继续处理其他任务；无法落盘时保留原始运行时异常，不写入不可信终态。
            }
        }

        /**
         * 组合 TaskRecord 稳定字段与子 Agent 会话日志元数据，不使用会被 heartbeat 刷新的 lastUpdatedAt。
         *
         * @param context 父 Agent 运行上下文
         * @param task 当前后台任务
         * @return 不含正文和输出的真实进度证据
         */
        private TaskProgressEvidence taskProgressEvidence(RuntimeContext context, BackgroundTask task) {
            TaskRecord record = agent.getWorkspaceManager().readTaskRecord(
                    context,
                    agent.getName(),
                    context.getSessionId(),
                    task.getTaskId()
            ).orElse(null);
            SessionLogEvidence logEvidence = sessionLogEvidence(context, task.getAgentId());
            Instant evidenceAt = task.getCreatedAt();
            if (record != null && record.getCreatedAt() != null && record.getCreatedAt().isAfter(evidenceAt)) {
                evidenceAt = record.getCreatedAt();
            }
            if (logEvidence.lastModifiedAt().isAfter(evidenceAt)) {
                evidenceAt = logEvidence.lastModifiedAt();
            }
            String fingerprint = task.getTaskStatus().name()
                    + "|completed=" + task.isCompleted()
                    + "|record_status=" + (record == null || record.getStatus() == null
                    ? "UNKNOWN" : record.getStatus().name())
                    + "|event_seq=" + (record == null ? 0L : record.getLastEventSeq())
                    + "|awaiting_confirm=" + (record != null && record.isAwaitingConfirm())
                    + "|pending_confirms=" + (record == null || record.getPendingConfirms() == null
                    ? 0 : record.getPendingConfirms().size())
                    + "|log_files=" + logEvidence.fileCount()
                    + "|log_bytes=" + logEvidence.totalBytes()
                    + "|log_mtime=" + logEvidence.lastModifiedAt().toEpochMilli();
            return new TaskProgressEvidence(fingerprint, evidenceAt);
        }

        /**
         * 读取指定 Sub-Agent 会话日志的文件数、总字节数和最新修改时间作为真实活动证据。
         *
         * @param context 父 Agent 运行上下文
         * @param agentId 声明式 Sub-Agent 标识
         * @return 会话日志元数据；目录不可用时返回零值
         */
        private SessionLogEvidence sessionLogEvidence(RuntimeContext context, String agentId) {
            Path sessionDirectory = agent.getWorkspaceManager().getSessionDir(context, agentId);
            if (!Files.isDirectory(sessionDirectory)) {
                return SessionLogEvidence.empty();
            }
            long totalBytes = 0L;
            long fileCount = 0L;
            Instant lastModifiedAt = Instant.EPOCH;
            try (Stream<Path> files = Files.list(sessionDirectory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .toList()) {
                    fileCount++;
                    totalBytes += Files.size(file);
                    Instant modifiedAt = Files.getLastModifiedTime(file).toInstant();
                    if (modifiedAt.isAfter(lastModifiedAt)) {
                        lastModifiedAt = modifiedAt;
                    }
                }
                return new SessionLogEvidence(fileCount, totalBytes, lastModifiedAt);
            } catch (IOException ignored) {
                return SessionLogEvidence.empty();
            }
        }

        /** 表示一次不含正文的稳定任务进度指纹及其真实证据时间。 */
        private record TaskProgressEvidence(
                /** 状态、事件序号与会话日志元数据组成的稳定指纹。 */ String fingerprint,
                /** 最后真实事件或日志活动时间。 */ Instant evidenceAt
        ) { }

        /** 表示 Sub-Agent 会话日志目录的聚合元数据。 */
        private record SessionLogEvidence(
                /** 当前日志文件数量。 */ long fileCount,
                /** 当前全部日志文件总字节数。 */ long totalBytes,
                /** 当前日志文件的最新修改时间。 */ Instant lastModifiedAt
        ) {
            /** 返回日志目录不存在或不可读时的稳定零值。 */
            private static SessionLogEvidence empty() {
                return new SessionLogEvidence(0L, 0L, Instant.EPOCH);
            }
        }

        /**
         * 委托 Harness 清理指定会话状态。
         *
         * @param userId 用户标识
         * @param sessionId 会话标识
         */
        @Override
        public void clearContext(String userId, String sessionId) {
            agent.clearContext(userId, sessionId);
        }

        /**
         * 释放 Harness 持有的资源。
         */
        @Override
        public void close() {
            agent.close();
        }
    }

    /** 作为活跃回合 Map 键的角色和 session 组合。 */
    private record InvocationKey(
            /** 父 Agent 角色。 */ String role,
            /** 父 Agent session 标识。 */ String sessionId
    ) { }

    /** 保存父回合取消和诊断所需的最小运行时上下文。 */
    private record ActiveInvocation(
            /** 负责本回合的角色运行时。 */ AgentRuntime runtime,
            /** 会被本地 Sub-Agent 继承的执行上下文。 */ RuntimeContext context,
            /** 父回合开始时间。 */ Instant startedAt,
            /** 父回合开始前该角色累计的 MCP 连接失败数。 */ int initialConnectionFailures,
            /** 当前父回合是否已经消耗唯一一次 MCP 连接重建机会。 */ AtomicBoolean infrastructureRecoveryAttempted
    ) {
        /** 为新父回合创建尚未消耗 MCP 恢复预算的诊断状态。 */
        private ActiveInvocation(
                AgentRuntime runtime,
                RuntimeContext context,
                Instant startedAt,
                int initialConnectionFailures
        ) {
            this(runtime, context, startedAt, initialConnectionFailures, new AtomicBoolean());
        }
    }

    /**
     * 表示活跃父 Agent 回合的脱敏诊断。
     *
     * @param role 父 Agent 角色
     * @param sessionId 父 Agent session 标识
     * @param routingKey 对应 dispatch 路由
     * @param projectId 继承到 Sub-Agent 的项目标识
     * @param startedAt 父回合开始时间
     * @param parentLastProgressAt 父回合判定租约使用的有效进度时间
     * @param parentSessionProgressAt 父 Agent 会话状态最后真实变化时间
     * @param parentModelActivityAt 可安全归因到该回合的模型流式活动时间
     * @param parentProgressSource 有效进度来自回合开始、会话状态或模型流
     * @param childTasks 当前 session 的子任务状态
     */
    public record ActiveExecutionDiagnostic(
            /** 父 Agent 角色。 */ String role,
            /** 父 Agent session 标识。 */ String sessionId,
            /** 对应 dispatch 路由。 */ String routingKey,
            /** 继承到 Sub-Agent 的项目标识。 */ String projectId,
            /** 父回合开始时间。 */ Instant startedAt,
            /** 父回合租约使用的最后有效进度时间。 */ Instant parentLastProgressAt,
            /** 父 Agent 会话状态最后真实变化时间。 */ Instant parentSessionProgressAt,
            /** 唯一活跃回合可归因的模型流式活动时间。 */ Instant parentModelActivityAt,
            /** 最后有效进度来源。 */ String parentProgressSource,
            /** 当前 session 的子任务状态。 */ List<ChildTaskDiagnostic> childTasks
    ) {
        /**
         * 兼容只提供单一父进度时间的测试诊断构造方式。
         */
        public ActiveExecutionDiagnostic(
                String role,
                String sessionId,
                String routingKey,
                String projectId,
                Instant startedAt,
                Instant parentLastProgressAt,
                List<ChildTaskDiagnostic> childTasks
        ) {
            this(
                    role,
                    sessionId,
                    routingKey,
                    projectId,
                    startedAt,
                    parentLastProgressAt,
                    parentLastProgressAt,
                    null,
                    "SESSION_STATE",
                    childTasks
            );
        }

        /** 校验进度时间并对子任务列表执行不可变复制。 */
        public ActiveExecutionDiagnostic {
            Objects.requireNonNull(startedAt, "startedAt 不能为空");
            Objects.requireNonNull(parentLastProgressAt, "parentLastProgressAt 不能为空");
            Objects.requireNonNull(parentSessionProgressAt, "parentSessionProgressAt 不能为空");
            Objects.requireNonNull(parentProgressSource, "parentProgressSource 不能为空");
            childTasks = List.copyOf(childTasks);
        }
    }

    /**
     * 汇总父回合各类进度证据，保证租约判定与对外诊断使用同一份结果。
     *
     * @param sessionProgressAt 会话状态进度
     * @param modelActivityAt 可空模型流活动
     * @param effectiveProgressAt 租约采用的较晚时间
     * @param source 有效进度来源
     */
    private record ParentProgressSnapshot(
            /** 会话状态最后变化时间。 */ Instant sessionProgressAt,
            /** 可安全归因的模型活动时间。 */ Instant modelActivityAt,
            /** 判定租约使用的有效时间。 */ Instant effectiveProgressAt,
            /** 有效时间来源。 */ String source
    ) {
    }

    /**
     * 表示单个 Sub-Agent 后台任务的脱敏诊断。
     *
     * @param taskId AgentScope 任务标识
     * @param agentId 声明式 Sub-Agent 标识
     * @param status AgentScope 任务状态
     * @param terminal 是否已经进入终态
     * @param createdAt 任务创建时间
     * @param taskKind 首次 spawn、后续 send 或未知历史任务
     * @param cancelOrigin 取消请求来源；未取消时为 NONE
     * @param lastProgressAt 最后稳定状态、事件序号或会话日志发生真实变化的时间
     */
    public record ChildTaskDiagnostic(
            /** AgentScope 任务标识。 */ String taskId,
            /** 声明式 Sub-Agent 标识。 */ String agentId,
            /** AgentScope 任务状态。 */ String status,
            /** 是否已经进入终态。 */ boolean terminal,
            /** 任务创建时间。 */ Instant createdAt,
            /** 任务是首次 spawn、后续 send 或未观测历史任务。 */ String taskKind,
            /** 模型或运行时发起取消的来源。 */ String cancelOrigin,
            /** 最后真实进度时间；不会被 task_output 轮询或 heartbeat 刷新。 */ Instant lastProgressAt
    ) {
        /** 校验时间字段完整，避免停滞诊断将缺失值误判为当前进展。 */
        public ChildTaskDiagnostic {
            Objects.requireNonNull(createdAt, "createdAt 不能为空");
            Objects.requireNonNull(lastProgressAt, "lastProgressAt 不能为空");
        }
    }
}
