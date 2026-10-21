package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 包装 AgentScope 内置任务工具，只提取任务标识和调用语义供运行时诊断使用。
 */
final class TaskSemanticsTrackingTool implements AgentTool {

    /** 允许安装语义跟踪的三个 AgentScope 内置工具名。 */
    private static final Set<String> SUPPORTED_TOOL_NAMES = Set.of(
            "agent_spawn", "agent_send", "task_cancel"
    );

    /** 从 spawn/send 工具的结构化文本响应中提取后台任务标识。 */
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("(?m)^task_id:\\s*(\\S+)\\s*$");

    /** 从 agent_spawn 响应中提取可用于后续 agent_send 的稳定 agent_key。 */
    private static final Pattern AGENT_KEY_PATTERN = Pattern.compile("(?m)^\\s*-?\\s*agent_key:\\s*(\\S+)\\s*$");

    /** 保留全部原始工具行为和 schema 的委托。 */
    private final AgentTool delegate;

    /** 仅保存任务种类与取消来源的共享语义表。 */
    private final TaskSemanticsRegistry registry;

    /**
     * 创建任务语义跟踪包装器。
     *
     * @param delegate AgentScope 原始内置工具
     * @param registry 当前 TaskRepository 的共享语义表
     */
    TaskSemanticsTrackingTool(AgentTool delegate, TaskSemanticsRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        if (!SUPPORTED_TOOL_NAMES.contains(delegate.getName())) {
            throw new IllegalArgumentException("不支持跟踪的 AgentScope 工具: " + delegate.getName());
        }
    }

    /** 返回原始工具名。 */
    @Override
    public String getName() {
        return delegate.getName();
    }

    /** 返回原始工具描述。 */
    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    /** 返回原始参数 schema。 */
    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    /** 返回原始严格模式标记。 */
    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    /** 返回原始输出 schema。 */
    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    /** 返回原始只读标记。 */
    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    /**
     * 委托原始调用，并只登记 taskId、任务种类和模型取消来源。
     *
     * @param param AgentScope 工具调用参数
     * @return 原始工具结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Objects.requireNonNull(param, "param 不能为空");
        registry.observeContext(param.getRuntimeContext());
        if ("task_cancel".equals(getName())) {
            return delegate.callAsync(param).doOnNext(result -> {
                if (result != null
                        && result.getState() != ToolResultState.ERROR
                        && result.getState() != ToolResultState.DENIED
                        && result.getState() != ToolResultState.INTERRUPTED) {
                    recordModelCancellation(param.getInput());
                }
            });
        }
        TaskSemanticsRegistry.TaskKind taskKind = "agent_spawn".equals(getName())
                ? TaskSemanticsRegistry.TaskKind.SPAWN
                : TaskSemanticsRegistry.TaskKind.SEND;
        String agentId = resolveAgentId(param);
        if (agentId == null) {
            return delegate.callAsync(param).doOnNext(result -> recordCreatedTask(
                    result, taskKind, param, null
            ));
        }
        if (taskKind == TaskSemanticsRegistry.TaskKind.SPAWN) {
            registry.recordAgentAliases(
                    param.getRuntimeContext(), agentId, stringInput(param.getInput(), "label")
            );
        }
        TaskSemanticsRegistry.SingleFlightDecision decision = registry.acquire(
                param.getRuntimeContext(), agentId
        );
        if (!decision.acquired()) {
            return Mono.just(deduplicatedResult(param, agentId, decision.activeTaskId()));
        }
        return delegate.callAsync(param)
                .doOnNext(result -> recordCreatedTask(result, taskKind, param, agentId))
                .doFinally(signal -> {
                    if (signal != SignalType.CANCEL) {
                        registry.release(param.getRuntimeContext(), agentId);
                    }
                });
    }

    /**
     * 从 spawn 的 agent_id 或 send 的 label/agent_key 解析逻辑 Sub-Agent 标识。
     *
     * @param param AgentScope 工具调用参数
     * @return 声明式 agentId；无法可靠解析时为 null
     */
    private String resolveAgentId(ToolCallParam param) {
        if ("agent_spawn".equals(getName())) {
            return stringInput(param.getInput(), "agent_id");
        }
        String label = stringInput(param.getInput(), "label");
        if (label != null) {
            String mappedAgentId = resolveStateAgentId(param, label);
            if (mappedAgentId == null) {
                mappedAgentId = registry.resolveAgentId(param.getRuntimeContext(), label);
            }
            return mappedAgentId == null
                    ? registry.resolveUniqueActiveAgentId(param.getRuntimeContext()) : mappedAgentId;
        }
        String agentKey = stringInput(param.getInput(), "agent_key");
        String mappedAgentId = resolveStateAgentId(param, agentKey);
        if (mappedAgentId == null) {
            mappedAgentId = registry.resolveAgentId(param.getRuntimeContext(), agentKey);
        }
        return mappedAgentId == null
                ? registry.resolveUniqueActiveAgentId(param.getRuntimeContext()) : mappedAgentId;
    }

    /**
     * 从 AgentScope 持久化的 spawn registry 精确解析 agent_key 或 label，避免多子代理时猜测归属。
     *
     * @param param 当前工具调用参数
     * @param alias agent_key 或 label
     * @return 声明式 agentId；运行状态不可用或没有匹配项时为 null
     */
    private String resolveStateAgentId(ToolCallParam param, String alias) {
        if (alias == null || alias.isBlank() || param.getRuntimeContext().getAgentState() == null) {
            return null;
        }
        ToolContextState toolState = param.getRuntimeContext().getAgentState().getToolContext();
        if (toolState == null || toolState.getSpawnRegistry() == null) {
            return null;
        }
        ToolContextState.SpawnEntry entry = toolState.getSpawnRegistry().get(alias);
        if (entry == null) {
            entry = toolState.getSpawnRegistry().values().stream()
                    .filter(candidate -> alias.equalsIgnoreCase(candidate.key())
                            || (candidate.label() != null && alias.equalsIgnoreCase(candidate.label())))
                    .findFirst()
                    .orElse(null);
        }
        if (entry == null || entry.agentId() == null || entry.agentId().isBlank()) {
            return null;
        }
        registry.recordAgentAliases(
                param.getRuntimeContext(), entry.agentId(), entry.key(), entry.label()
        );
        return entry.agentId();
    }

    /**
     * 返回成功去重结果，使模型复用既有 RUNNING task 而不创建第二个 continuation。
     *
     * @param param 当前工具调用参数
     * @param agentId 已解析逻辑 agentId
     * @param activeTaskId 已存在后台任务标识；单飞占位尚未产生任务时为空
     * @return 保留原工具调用 id/name 的成功结果
     */
    private ToolResultBlock deduplicatedResult(
            ToolCallParam param,
            String agentId,
            String activeTaskId
    ) {
        String output = activeTaskId == null || activeTaskId.isBlank()
                ? "status: already_running\nagent_id: " + agentId
                        + "\ndeduplicated: true\nUse task_list() to locate the existing task; do not resend."
                : "status: already_running\nagent_id: " + agentId
                        + "\ntask_id: " + activeTaskId
                        + "\ndeduplicated: true\nUse task_output(task_id='" + activeTaskId
                        + "', block=false); do not resend while it is Running.";
        return ToolResultBlock.text(output).withIdAndName(param.getToolUseBlock().getId(), getName());
    }

    /**
     * 从 task_cancel 参数中只读取任务标识，不保留消息正文或其他模型输入。
     *
     * @param input 原始工具参数
     */
    private void recordModelCancellation(Map<String, Object> input) {
        if (input == null) {
            return;
        }
        Object taskId = input.get("task_id");
        if (taskId instanceof String value && !value.isBlank()) {
            registry.recordCancellation(value, TaskSemanticsRegistry.CancelOrigin.MODEL_REQUESTED);
        }
    }

    /**
     * 从已完成工具响应中提取任务标识并登记 spawn/send 种类，不保存响应正文。
     *
     * @param result AgentScope 原始工具响应
     * @param kind 本次调用的任务种类
     */
    private void recordCreatedTask(
            ToolResultBlock result,
            TaskSemanticsRegistry.TaskKind kind,
            ToolCallParam param,
            String agentId
    ) {
        if (result == null
                || result.getOutput() == null
                || result.getState() == ToolResultState.ERROR
                || result.getState() == ToolResultState.DENIED
                || result.getState() == ToolResultState.INTERRUPTED) {
            return;
        }
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                Matcher matcher = TASK_ID_PATTERN.matcher(textBlock.getText());
                String taskId = null;
                if (matcher.find()) {
                    taskId = matcher.group(1);
                    registry.recordTask(taskId, kind);
                }
                String resolvedAgentId = agentId != null || taskId == null
                        ? agentId
                        : registry.resolveTaskAgentId(param.getRuntimeContext(), taskId);
                if (resolvedAgentId != null) {
                    Matcher agentKeyMatcher = AGENT_KEY_PATTERN.matcher(textBlock.getText());
                    String agentKey = agentKeyMatcher.find() ? agentKeyMatcher.group(1) : null;
                    registry.recordAgentAliases(
                            param.getRuntimeContext(),
                            resolvedAgentId,
                            stringInput(param.getInput(), "label"),
                            stringInput(param.getInput(), "agent_key"),
                            agentKey
                    );
                }
                return;
            }
        }
    }

    /**
     * 从工具参数读取非空字符串，拒绝把消息正文或复杂对象写入注册表。
     *
     * @param input 工具参数
     * @param key 目标字段名
     * @return 非空字符串；不存在或类型不符时为 null
     */
    private static String stringInput(Map<String, Object> input, String key) {
        if (input == null) {
            return null;
        }
        Object value = input.get(key);
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }
}
