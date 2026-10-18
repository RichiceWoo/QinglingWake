package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 补充 AgentScope TaskRepository 未持久化的任务语义、真实进度租约和单飞状态，不保存任务正文或输出。
 */
final class TaskSemanticsRegistry {

    /** 每个任务仓库最多保留的诊断条目数，避免长生命周期 Agent 无界占用内存。 */
    private static final int MAX_ENTRIES = 1_024;

    /** 按任务仓库弱引用共享语义表，使工具包装器与 Gateway 可读取同一份元数据。 */
    private static final Map<TaskRepository, TaskSemanticsRegistry> REGISTRIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 当前 AgentScope 后台任务仓库，用于读取未终态任务并实现单飞。 */
    private final TaskRepository repository;

    /** 按写入顺序保存的有界任务语义，不包含提示词、消息正文或任务结果。 */
    private final LinkedHashMap<String, TaskSemantics> semanticsByTaskId = new LinkedHashMap<>();

    /** 按任务保存稳定事件指纹对应的最后真实进展时间。 */
    private final LinkedHashMap<String, ProgressLease> progressByTaskId = new LinkedHashMap<>();

    /** 保存父 session 内 label/agent_key 到声明式 agentId 的映射。 */
    private final LinkedHashMap<AgentAliasKey, String> agentAliases = new LinkedHashMap<>();

    /** 保存已经进入工具调用但尚未登记后台任务的 agent/session 单飞占位。 */
    private final Set<AgentSessionKey> continuationReservations = new LinkedHashSet<>();

    /** 保存曾创建或观察后台任务的最小父会话，用于 shutdown 终态协调。 */
    private final LinkedHashMap<ParentSessionKey, ParentSession> parentSessions = new LinkedHashMap<>();

    /** 限制实例只能通过任务仓库共享入口创建。 */
    private TaskSemanticsRegistry(TaskRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository 不能为空");
    }

    /**
     * 返回指定任务仓库共享的语义表。
     *
     * @param repository AgentScope 任务仓库
     * @return 与该仓库绑定的共享语义表
     */
    static TaskSemanticsRegistry forRepository(TaskRepository repository) {
        Objects.requireNonNull(repository, "repository 不能为空");
        synchronized (REGISTRIES) {
            return REGISTRIES.computeIfAbsent(repository, TaskSemanticsRegistry::new);
        }
    }

    /**
     * 记录 agent_spawn 或 agent_send 创建的任务种类，并保留此前已登记的取消来源。
     *
     * @param taskId AgentScope 后台任务标识
     * @param kind 后台任务种类
     */
    synchronized void recordTask(String taskId, TaskKind kind) {
        String safeTaskId = requireTaskId(taskId);
        TaskSemantics previous = semanticsByTaskId.getOrDefault(safeTaskId, TaskSemantics.unknown());
        semanticsByTaskId.put(safeTaskId, new TaskSemantics(
                Objects.requireNonNull(kind, "kind 不能为空"),
                previous.cancelOrigin()
        ));
        trimToLimit();
    }

    /**
     * 记录取消请求来源；模型显式请求优先级最高，运行时清理不得覆盖该证据。
     *
     * @param taskId AgentScope 后台任务标识
     * @param origin 取消请求来源
     */
    synchronized void recordCancellation(String taskId, CancelOrigin origin) {
        String safeTaskId = requireTaskId(taskId);
        CancelOrigin safeOrigin = Objects.requireNonNull(origin, "origin 不能为空");
        TaskSemantics previous = semanticsByTaskId.getOrDefault(safeTaskId, TaskSemantics.unknown());
        CancelOrigin resolvedOrigin = resolveCancelOrigin(previous.cancelOrigin(), safeOrigin);
        semanticsByTaskId.put(safeTaskId, new TaskSemantics(previous.kind(), resolvedOrigin));
        trimToLimit();
    }

    /**
     * 查询任务语义；未被包装器观察到的历史任务返回 UNKNOWN/NONE。
     *
     * @param taskId AgentScope 后台任务标识
     * @return 不含正文和结果的任务语义
     */
    synchronized TaskSemantics semantics(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return TaskSemantics.unknown();
        }
        return semanticsByTaskId.getOrDefault(taskId, TaskSemantics.unknown());
    }

    /**
     * 保存父会话的最小路由信息，供 Gateway 关闭时遍历并终态化孤立任务。
     *
     * @param context 当前父 Agent 运行上下文
     */
    synchronized void observeContext(RuntimeContext context) {
        RuntimeContext safeContext = Objects.requireNonNull(context, "context 不能为空");
        String userId = Objects.toString(safeContext.getUserId(), "");
        String sessionId = Objects.toString(safeContext.getSessionId(), "");
        if (userId.isBlank() || sessionId.isBlank()) {
            return;
        }
        ParentSession session = new ParentSession(userId, sessionId);
        parentSessions.put(new ParentSessionKey(userId, sessionId), session);
        trimToLimit();
    }

    /**
     * 返回不含自定义属性与凭据的父会话上下文快照。
     *
     * @return 按用户和 session 排序的最小 RuntimeContext
     */
    synchronized List<RuntimeContext> knownContexts() {
        return parentSessions.values().stream()
                .sorted(Comparator.comparing(ParentSession::userId).thenComparing(ParentSession::sessionId))
                .map(session -> RuntimeContext.builder()
                        .userId(session.userId())
                        .sessionId(session.sessionId())
                        .build())
                .toList();
    }

    /**
     * 登记 agent_spawn 已知的 label 或 agent_key，使后续 agent_send 可解析到同一逻辑 agent。
     *
     * @param context 当前父 Agent 运行上下文
     * @param agentId 声明式 Sub-Agent 标识
     * @param aliases label 或 agent_key，可为空
     */
    synchronized void recordAgentAliases(RuntimeContext context, String agentId, String... aliases) {
        observeContext(context);
        String safeAgentId = requireText(agentId, "agentId");
        if (aliases == null) {
            return;
        }
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            agentAliases.put(aliasKey(context, alias), safeAgentId);
        }
        trimToLimit();
    }

    /**
     * 解析当前父 session 中 label 或 agent_key 对应的声明式 agentId。
     *
     * @param context 当前父 Agent 运行上下文
     * @param alias label 或 agent_key
     * @return 已知 agentId；未知时为 null
     */
    synchronized String resolveAgentId(RuntimeContext context, String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        return agentAliases.get(aliasKey(context, alias));
    }

    /**
     * 当当前父 session 只有一个 RUNNING 逻辑子代理时返回其声明式标识，用于补齐未知 agent_key。
     *
     * @param context 当前父 Agent 运行上下文
     * @return 唯一活跃 agentId；不存在或存在多个候选时为 null
     */
    synchronized String resolveUniqueActiveAgentId(RuntimeContext context) {
        String parentSessionId = requireText(context.getSessionId(), "sessionId");
        List<String> activeAgentIds = repository.listTasks(context, parentSessionId, null).stream()
                .filter(task -> !task.isCompleted())
                .map(BackgroundTask::getAgentId)
                .filter(Objects::nonNull)
                .distinct()
                .limit(2)
                .toList();
        return activeAgentIds.size() == 1 ? activeAgentIds.get(0) : null;
    }

    /**
     * 通过刚创建的 taskId 反查声明式 agentId，使后续 agent_key 调用可以进入单飞判定。
     *
     * @param context 当前父 Agent 运行上下文
     * @param taskId AgentScope 后台任务标识
     * @return 任务所属 agentId；任务尚不可见时为 null
     */
    synchronized String resolveTaskAgentId(RuntimeContext context, String taskId) {
        String parentSessionId = requireText(context.getSessionId(), "sessionId");
        String safeTaskId = requireTaskId(taskId);
        return repository.listTasks(context, parentSessionId, null).stream()
                .filter(task -> safeTaskId.equals(task.getTaskId()))
                .map(BackgroundTask::getAgentId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * 原子申请同一 agent/session 的 continuation 执行权；已有 RUNNING 任务时返回其 taskId。
     *
     * @param context 当前父 Agent 运行上下文
     * @param agentId 声明式 Sub-Agent 标识
     * @return 单飞决策
     */
    synchronized SingleFlightDecision acquire(RuntimeContext context, String agentId) {
        observeContext(context);
        String safeAgentId = requireText(agentId, "agentId");
        BackgroundTask activeTask = latestActiveTask(context, safeAgentId);
        if (activeTask != null) {
            return SingleFlightDecision.duplicate(activeTask.getTaskId());
        }
        AgentSessionKey key = agentSessionKey(context, safeAgentId);
        if (!continuationReservations.add(key)) {
            return SingleFlightDecision.duplicate("");
        }
        return SingleFlightDecision.granted();
    }

    /**
     * 释放尚未登记后台任务的单飞占位；已创建任务仍由 TaskRepository 状态继续防重。
     *
     * @param context 当前父 Agent 运行上下文
     * @param agentId 声明式 Sub-Agent 标识
     */
    synchronized void release(RuntimeContext context, String agentId) {
        continuationReservations.remove(agentSessionKey(context, requireText(agentId, "agentId")));
    }

    /**
     * 用稳定事件指纹续租；首次观察使用真实证据时间而不是轮询时间，避免心跳伪装进展。
     *
     * @param taskId AgentScope 后台任务标识
     * @param fingerprint 不含正文的状态、事件序号与日志元数据指纹
     * @param evidenceAt 最后真实日志或事件发生时间
     * @param observedAt 本次观察时间
     * @return 当前任务真实进度租约
     */
    synchronized ProgressLease observeProgress(
            String taskId,
            String fingerprint,
            Instant evidenceAt,
            Instant observedAt
    ) {
        String safeTaskId = requireTaskId(taskId);
        String safeFingerprint = Objects.requireNonNullElse(fingerprint, "");
        Instant safeObservedAt = Objects.requireNonNull(observedAt, "observedAt 不能为空");
        Instant safeEvidenceAt = evidenceAt == null ? safeObservedAt : evidenceAt;
        if (safeEvidenceAt.isAfter(safeObservedAt)) {
            safeEvidenceAt = safeObservedAt;
        }
        ProgressLease previous = progressByTaskId.get(safeTaskId);
        ProgressLease current;
        if (previous == null) {
            current = new ProgressLease(safeFingerprint, safeEvidenceAt);
        } else if (!previous.fingerprint().equals(safeFingerprint)) {
            current = new ProgressLease(safeFingerprint, safeObservedAt);
        } else {
            current = previous;
        }
        progressByTaskId.put(safeTaskId, current);
        trimToLimit();
        return current;
    }

    /**
     * 查找指定 agent/session 创建时间最新的未终态任务。
     *
     * @param context 当前父 Agent 运行上下文
     * @param agentId 声明式 Sub-Agent 标识
     * @return 最新未终态任务；不存在时为 null
     */
    private BackgroundTask latestActiveTask(RuntimeContext context, String agentId) {
        String parentSessionId = requireText(context.getSessionId(), "sessionId");
        return repository.listTasks(context, parentSessionId, null).stream()
                .filter(task -> agentId.equals(task.getAgentId()))
                .filter(task -> !task.isCompleted())
                .max(Comparator.comparing(BackgroundTask::getCreatedAt)
                        .thenComparing(BackgroundTask::getTaskId))
                .orElse(null);
    }

    /**
     * 解析取消来源优先级，确保模型显式取消不会被父失败、清理或关闭路径覆盖。
     *
     * @param current 已登记来源
     * @param requested 新请求来源
     * @return 应保留的取消来源
     */
    private static CancelOrigin resolveCancelOrigin(CancelOrigin current, CancelOrigin requested) {
        if (current == CancelOrigin.MODEL_REQUESTED || requested == CancelOrigin.MODEL_REQUESTED) {
            return CancelOrigin.MODEL_REQUESTED;
        }
        return current == CancelOrigin.NONE ? requested : current;
    }

    /** 删除最早写入的超限条目，使诊断辅助状态保持固定上界。 */
    private void trimToLimit() {
        trimMap(semanticsByTaskId);
        trimMap(progressByTaskId);
        trimMap(agentAliases);
        trimMap(parentSessions);
        while (continuationReservations.size() > MAX_ENTRIES) {
            continuationReservations.remove(continuationReservations.iterator().next());
        }
    }

    /**
     * 删除有序 Map 最早写入的超限条目。
     *
     * @param values 待限制的有序 Map
     */
    private static void trimMap(LinkedHashMap<?, ?> values) {
        while (values.size() > MAX_ENTRIES) {
            values.remove(values.keySet().iterator().next());
        }
    }

    /** 创建父 session 内不区分大小写的 agent 别名键。 */
    private static AgentAliasKey aliasKey(RuntimeContext context, String alias) {
        ParentSessionKey parent = parentSessionKey(context);
        return new AgentAliasKey(parent.userId(), parent.sessionId(), alias.trim().toLowerCase(Locale.ROOT));
    }

    /** 创建父 session 与逻辑 agent 的单飞复合键。 */
    private static AgentSessionKey agentSessionKey(RuntimeContext context, String agentId) {
        ParentSessionKey parent = parentSessionKey(context);
        return new AgentSessionKey(parent.userId(), parent.sessionId(), agentId);
    }

    /** 从运行上下文提取父会话键。 */
    private static ParentSessionKey parentSessionKey(RuntimeContext context) {
        RuntimeContext safeContext = Objects.requireNonNull(context, "context 不能为空");
        return new ParentSessionKey(
                requireText(safeContext.getUserId(), "userId"),
                requireText(safeContext.getSessionId(), "sessionId")
        );
    }

    /** 校验任务标识非空并返回原值。 */
    private static String requireTaskId(String taskId) {
        return requireText(taskId, "taskId");
    }

    /** 校验文本非空并返回原值。 */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    /** 表示共享 TaskRepository 中一次调用任务的业务语义。 */
    record TaskSemantics(
            /** 区分首次 spawn、后续 send 与未观测到的历史任务。 */ TaskKind kind,
            /** 标识最近保留的取消请求来源。 */ CancelOrigin cancelOrigin
    ) {
        /** 校验语义字段完整，避免诊断输出出现不稳定空值。 */
        TaskSemantics {
            Objects.requireNonNull(kind, "kind 不能为空");
            Objects.requireNonNull(cancelOrigin, "cancelOrigin 不能为空");
        }

        /** 返回未被本适配层观测到的默认语义。 */
        private static TaskSemantics unknown() {
            return new TaskSemantics(TaskKind.UNKNOWN, CancelOrigin.NONE);
        }
    }

    /** 表示一次稳定进度指纹对应的最后真实续租时间。 */
    record ProgressLease(
            /** 不含任务正文和输出的稳定进度指纹。 */ String fingerprint,
            /** 最后观察到真实状态、事件或会话日志变化的时间。 */ Instant lastProgressAt
    ) { }

    /** 表示 continuation 工具调用是否取得当前 agent/session 的唯一执行权。 */
    record SingleFlightDecision(
            /** 当前调用是否可以继续委托 AgentScope 原始工具。 */ boolean acquired,
            /** 已存在 RUNNING 任务的标识；仅占位冲突时为空。 */ String activeTaskId
    ) {
        /** 返回允许执行的单飞决策。 */
        private static SingleFlightDecision granted() {
            return new SingleFlightDecision(true, "");
        }

        /** 返回复用既有任务或占位的去重决策。 */
        private static SingleFlightDecision duplicate(String activeTaskId) {
            return new SingleFlightDecision(false, Objects.requireNonNullElse(activeTaskId, ""));
        }
    }

    /** 标识父用户与 session 的最小持久化路由。 */
    private record ParentSession(
            /** 父 Agent 用户标识。 */ String userId,
            /** 父 Agent session 标识。 */ String sessionId
    ) { }

    /** 作为父会话快照 Map 键的用户与 session 组合。 */
    private record ParentSessionKey(
            /** 父 Agent 用户标识。 */ String userId,
            /** 父 Agent session 标识。 */ String sessionId
    ) { }

    /** 作为别名 Map 键的父会话与标准化别名组合。 */
    private record AgentAliasKey(
            /** 父 Agent 用户标识。 */ String userId,
            /** 父 Agent session 标识。 */ String sessionId,
            /** 小写 label 或 agent_key。 */ String alias
    ) { }

    /** 作为 continuation 单飞键的父会话与声明式 agent 组合。 */
    private record AgentSessionKey(
            /** 父 Agent 用户标识。 */ String userId,
            /** 父 Agent session 标识。 */ String sessionId,
            /** 声明式 Sub-Agent 标识。 */ String agentId
    ) { }

    /** 区分真正创建子 Agent 的任务与投递后续消息的 continuation 任务。 */
    enum TaskKind {
        /** agent_spawn 创建并首次执行子 Agent 的任务。 */
        SPAWN,
        /** agent_send 向既有子 Agent 投递后续消息所创建的任务。 */
        SEND,
        /** 包装器启用前创建或无法可靠分类的历史任务。 */
        UNKNOWN
    }

    /** 标识 task_cancel 或运行时清理路径的取消请求来源。 */
    enum CancelOrigin {
        /** 尚未观察到取消请求。 */
        NONE,
        /** 父模型通过 task_cancel 显式请求取消。 */
        MODEL_REQUESTED,
        /** 父执行失败或 Future 被取消后由 Gateway 请求清理。 */
        RUNTIME_PARENT_FAILURE,
        /** clearSession 清理会话时由 Gateway 请求取消。 */
        SESSION_CLEAR,
        /** Gateway 关闭运行时时请求取消。 */
        SHUTDOWN
    }
}
