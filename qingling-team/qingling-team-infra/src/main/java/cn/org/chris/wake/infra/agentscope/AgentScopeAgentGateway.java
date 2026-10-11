package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import cn.org.chris.wake.infra.memory.TurnMemoryIndexer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

/**
 * 将领域 AgentGateway 适配到四角色 AgentScope HarnessAgent。
 */
public final class AgentScopeAgentGateway implements AgentGateway, AutoCloseable {

    /** 生产环境轮询 Sub-Agent 终态的间隔，兼顾快速终止与任务仓库读取开销。 */
    private static final Duration DEFAULT_CHILD_TASK_POLL_INTERVAL = Duration.ofSeconds(1);

    /** 子任务异常终态保持该时长后才终止父回合，给正常父回复保留短暂收尾窗口。 */
    private static final Duration DEFAULT_CHILD_TASK_FAILURE_GRACE = Duration.ofSeconds(2);

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

    /** 检查当前父 session 子任务状态的轮询间隔。 */
    private final Duration childTaskPollInterval;

    /** 子任务失败或取消后允许父 Agent 正常结束的宽限时间。 */
    private final Duration childTaskFailureGrace;

    /** 当前仍在执行的父 Agent 回合，用于取消级联和悬挂 dispatch 快速诊断。 */
    private final ConcurrentHashMap<InvocationKey, ActiveInvocation> activeInvocations = new ConcurrentHashMap<>();

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
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE
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
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE
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
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE
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
                DEFAULT_CHILD_TASK_POLL_INTERVAL,
                DEFAULT_CHILD_TASK_FAILURE_GRACE
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
        Objects.requireNonNull(ignored, "runtimeMode 不能为空");
        if (runtimes == null || runtimes.isEmpty()) {
            throw new IllegalArgumentException("Agent 运行时不能为空");
        }
        this.runtimes = Map.copyOf(runtimes);
        this.memoryIndexer = Objects.requireNonNull(memoryIndexer, "memoryIndexer 不能为空");
        this.childTaskPollInterval = requirePositiveDuration(
                childTaskPollInterval, "childTaskPollInterval"
        );
        this.childTaskFailureGrace = requireNonNegativeDuration(
                childTaskFailureGrace, "childTaskFailureGrace"
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
        ActiveInvocation invocation = new ActiveInvocation(runtime, context, Instant.now());
        activeInvocations.put(invocationKey, invocation);
        AtomicBoolean cancellationPropagated = new AtomicBoolean();
        Mono<AgentReply> parentCall = Mono.defer(() -> runtime.call(request.content(), context))
                .map(message -> {
                    AgentReply reply = toReply(message);
                    scheduleMemoryIndex(request, reply, turnTimestampMs);
                    return reply;
                });
        return Mono.firstWithSignal(parentCall, failWhenAllChildrenEndAbnormally(invocation))
                .doOnError(ignored -> cancelOnce(invocation, cancellationPropagated))
                .doOnCancel(() -> cancelOnce(invocation, cancellationPropagated))
                .doFinally(ignored -> activeInvocations.remove(invocationKey, invocation))
                .toFuture();
    }

    /**
     * 当当前 session 的全部子任务均已结束且至少一个失败或取消时，使仍悬挂的父回合直接失败。
     *
     * @param invocation 当前父回合
     * @return 仅在确认异常终态持续超过宽限期后发出错误的信号
     */
    private Mono<AgentReply> failWhenAllChildrenEndAbnormally(ActiveInvocation invocation) {
        return Flux.interval(childTaskPollInterval)
                .onBackpressureDrop()
                .concatMap(ignored -> {
                    String reason = terminalChildFailureReason(invocation);
                    if (reason == null) {
                        return Mono.<String>empty();
                    }
                    return Mono.delay(childTaskFailureGrace)
                            .filter(ignoredDelay -> reason.equals(terminalChildFailureReason(invocation)))
                            .map(ignoredDelay -> reason);
                })
                .next()
                .flatMap(reason -> Mono.error(new ChildTaskTerminationException(reason)));
    }

    /**
     * 生成不含任务正文和输出的异常终态摘要；仍有运行任务或全部成功时返回空。
     *
     * @param invocation 当前父回合
     * @return 稳定失败摘要，未满足终止条件时为 null
     */
    private static String terminalChildFailureReason(ActiveInvocation invocation) {
        List<ChildTaskDiagnostic> tasks = safeTaskDiagnostics(invocation);
        if (tasks.isEmpty() || tasks.stream().anyMatch(task -> !task.terminal())) {
            return null;
        }
        List<String> failures = tasks.stream()
                .filter(task -> "FAILED".equals(task.status()) || "CANCELLED".equals(task.status()))
                .map(task -> task.agentId() + "=" + task.status())
                .sorted()
                .toList();
        if (failures.isEmpty()) {
            return null;
        }
        return "AgentScope 子任务异常终止，父 dispatch 已结束: " + String.join(",", failures);
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
     * 把活跃回合转换为只含路由和任务状态的安全诊断。
     *
     * @param key 回合唯一键
     * @param invocation 活跃回合
     * @return 脱敏诊断
     */
    private static ActiveExecutionDiagnostic diagnostic(InvocationKey key, ActiveInvocation invocation) {
        RuntimeContext context = invocation.context();
        String routingKey = Objects.toString(context.get(ROUTING_KEY_ATTRIBUTE), "");
        String projectId = Objects.toString(context.get(PROJECT_ID_ATTRIBUTE), "");
        return new ActiveExecutionDiagnostic(
                key.role(),
                key.sessionId(),
                routingKey,
                projectId,
                invocation.startedAt(),
                safeTaskDiagnostics(invocation)
        );
    }

    /**
     * 只执行一次父子取消；取消钩子异常不得覆盖原始失败或阻断 Future 取消。
     *
     * @param invocation 待取消活跃回合
     * @param propagated 幂等标志
     */
    private static void cancelOnce(ActiveInvocation invocation, AtomicBoolean propagated) {
        if (!propagated.compareAndSet(false, true)) {
            return;
        }
        try {
            invocation.runtime().cancel(invocation.context());
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
        runtime.cancel(context);
        runtime.clearContext(userId, sessionId);
    }

    /**
     * 关闭所有角色 Harness 资源。
     */
    @Override
    public void close() {
        activeInvocations.values().forEach(invocation -> {
            try {
                invocation.runtime().cancel(invocation.context());
            } catch (RuntimeException ignored) {
                // 关闭继续处理其他角色，最终仍由 HarnessAgent.close() 关闭任务仓库。
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
                new HarnessAgentRuntime(Objects.requireNonNull(agent, "HarnessAgent 不能为空"))
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
         */
        default void cancel(RuntimeContext context) {
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
            /** 负责会话、模型、Skill 与工具调用的 AgentScope Agent。 */ HarnessAgent agent
    ) implements AgentRuntime {

        /**
         * 直接调用 HarnessAgent，正文不拼接审计历史。
         *
         * @param content 仅本轮正文
         * @param context 路由上下文
         * @return AgentScope 异步消息
         */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            return agent.call(content, context);
        }

        /**
         * 先取消当前 session 的所有未终态子任务，再中断父 Agent，避免父回合取消后子任务继续写文件。
         *
         * @param context 当前父回合上下文
         */
        @Override
        public void cancel(RuntimeContext context) {
            TaskRepository repository = agent.getTaskRepository();
            String parentSessionId = context.getSessionId();
            try {
                for (BackgroundTask task : repository.listTasks(context, parentSessionId, null)) {
                    if (!task.isCompleted()) {
                        try {
                            repository.cancelTask(context, parentSessionId, task.getTaskId());
                        } catch (RuntimeException ignored) {
                            // 单个子任务取消失败不能阻止其余子任务和父 Agent 接收中断。
                        }
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
         * 读取当前父 session 的子任务终态，供悬挂 dispatch 诊断使用。
         *
         * @param context 当前父回合上下文
         * @return 不含任务正文的状态列表
         */
        @Override
        public List<ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            return agent.getTaskRepository().listTasks(context, context.getSessionId(), null).stream()
                    .map(task -> new ChildTaskDiagnostic(
                            task.getTaskId(),
                            task.getAgentId(),
                            task.getTaskStatus().name(),
                            task.isCompleted(),
                            task.getCreatedAt()
                    ))
                    .sorted(Comparator.comparing(ChildTaskDiagnostic::taskId))
                    .toList();
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
            /** 父回合开始时间。 */ Instant startedAt
    ) { }

    /** 父回合因当前 session 的子任务失败或取消而被运行时主动终止。 */
    private static final class ChildTaskTerminationException extends IllegalStateException {

        /**
         * 使用脱敏子任务状态摘要创建异常。
         *
         * @param message 不含任务正文或输出的失败摘要
         */
        private ChildTaskTerminationException(String message) {
            super(message);
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
     * @param childTasks 当前 session 的子任务状态
     */
    public record ActiveExecutionDiagnostic(
            /** 父 Agent 角色。 */ String role,
            /** 父 Agent session 标识。 */ String sessionId,
            /** 对应 dispatch 路由。 */ String routingKey,
            /** 继承到 Sub-Agent 的项目标识。 */ String projectId,
            /** 父回合开始时间。 */ Instant startedAt,
            /** 当前 session 的子任务状态。 */ List<ChildTaskDiagnostic> childTasks
    ) {
        /** 对子任务列表执行不可变复制。 */
        public ActiveExecutionDiagnostic {
            childTasks = List.copyOf(childTasks);
        }
    }

    /**
     * 表示单个 Sub-Agent 后台任务的脱敏诊断。
     *
     * @param taskId AgentScope 任务标识
     * @param agentId 声明式 Sub-Agent 标识
     * @param status AgentScope 任务状态
     * @param terminal 是否已经进入终态
     * @param createdAt 任务创建时间
     */
    public record ChildTaskDiagnostic(
            /** AgentScope 任务标识。 */ String taskId,
            /** 声明式 Sub-Agent 标识。 */ String agentId,
            /** AgentScope 任务状态。 */ String status,
            /** 是否已经进入终态。 */ boolean terminal,
            /** 任务创建时间。 */ Instant createdAt
    ) { }
}
