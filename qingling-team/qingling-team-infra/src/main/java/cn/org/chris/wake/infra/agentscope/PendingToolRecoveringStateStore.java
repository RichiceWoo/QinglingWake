package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.tool.ToolResultMessageBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 为 AgentScope 2.0.3 声明式子 Agent 补齐未继承的 pending tool 恢复语义。
 *
 * <p>父 Agent 已原生启用恢复，但上游声明式子 Agent 工厂没有复制该开关。本装饰器只在读取
 * {@code agent_state} 时为缺失结果的工具调用补一个确定性错误结果，不执行也不重放原工具。</p>
 */
public final class PendingToolRecoveringStateStore implements AgentStateStore {

    /** 实际持久化 Agent 状态的底层存储。 */
    private final AgentStateStore delegate;

    /**
     * 创建 pending tool 安全恢复装饰器。
     *
     * @param delegate 实际状态存储
     */
    public PendingToolRecoveringStateStore(AgentStateStore delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate 不能为空");
    }

    /** 保存单个状态时保持底层语义不变。 */
    @Override
    public void save(String userId, String sessionId, String key, State state) {
        delegate.save(userId, sessionId, key, state);
    }

    /** 保存状态列表时保持底层语义不变。 */
    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        delegate.save(userId, sessionId, key, states);
    }

    /** 返回底层存储是否支持乐观版本控制。 */
    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    /**
     * 读取带版本状态并应用相同的 pending tool 恢复，不改变原版本号。
     */
    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId,
            String sessionId,
            String key,
            Class<T> stateType
    ) {
        VersionedState<T> versioned = delegate.getVersioned(userId, sessionId, key, stateType);
        if (versioned.isPresent()) {
            recoverPendingToolCalls(versioned.value());
        }
        return versioned;
    }

    /** 按期望版本保存时完整委托底层 CAS 语义。 */
    @Override
    public long saveIfVersion(
            String userId,
            String sessionId,
            String key,
            State state,
            long expectedVersion
    ) {
        return delegate.saveIfVersion(userId, sessionId, key, state, expectedVersion);
    }

    /**
     * 读取状态并在返回前补齐未获得结果的工具调用。
     */
    @Override
    public <T extends State> Optional<T> get(
            String userId,
            String sessionId,
            String key,
            Class<T> stateType
    ) {
        Optional<T> state = delegate.get(userId, sessionId, key, stateType);
        state.ifPresent(PendingToolRecoveringStateStore::recoverPendingToolCalls);
        return state;
    }

    /** 读取状态列表时保持底层语义不变。 */
    @Override
    public <T extends State> List<T> getList(
            String userId,
            String sessionId,
            String key,
            Class<T> stateType
    ) {
        return delegate.getList(userId, sessionId, key, stateType);
    }

    /** 查询 session 是否存在时保持底层语义不变。 */
    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sessionId);
    }

    /** 删除整个 session 时保持底层语义不变。 */
    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sessionId);
    }

    /** 删除单个状态槽时保持底层语义不变。 */
    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, sessionId, key);
    }

    /** 列出 session 时保持底层语义不变。 */
    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    /** 关闭装饰器时同步关闭底层存储。 */
    @Override
    public void close() {
        delegate.close();
    }

    /**
     * 为 AgentState 中只有 tool_use、没有 tool_result 的调用补确定性错误结果。
     *
     * @param state 任意状态；非 AgentState 不处理
     */
    private static void recoverPendingToolCalls(State state) {
        if (!(state instanceof AgentState agentState)) {
            return;
        }
        Set<String> completedIds = new HashSet<>();
        for (Msg message : agentState.getContext()) {
            message.getContentBlocks(ToolResultBlock.class).stream()
                    .map(ToolResultBlock::getId)
                    .forEach(completedIds::add);
        }
        List<ToolUseBlock> pendingCalls = agentState.getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .filter(toolUse -> !completedIds.contains(toolUse.getId()))
                .toList();
        for (ToolUseBlock pendingCall : pendingCalls) {
            ToolResultBlock interrupted = ToolResultBlock.error(
                    pendingCall.getId(),
                    "工具调用在上一轮中断，运行时未重放原调用；请根据当前状态安全续跑"
            );
            agentState.contextMutable().add(ToolResultMessageBuilder.buildToolResultMsg(
                    interrupted,
                    pendingCall,
                    "runtime-pending-tool-recovery"
            ));
        }
    }
}
