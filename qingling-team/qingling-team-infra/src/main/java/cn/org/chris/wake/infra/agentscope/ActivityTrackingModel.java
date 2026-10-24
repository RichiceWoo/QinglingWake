package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/**
 * 为单个团队角色记录模型订阅和流式分片活动的纯透传模型装饰器。
 */
final class ActivityTrackingModel implements Model {

    /** 保持原始模型请求、响应和元数据语义的实际模型。 */
    private final Model delegate;

    /** 仅保存角色活动时间戳的共享注册表。 */
    private final ModelActivityRegistry activityRegistry;

    /** 当前装饰器独占的团队角色。 */
    private final String role;

    /**
     * 为指定角色包装模型，不改变 Spring 中原始 Model Bean 的具体类型。
     *
     * @param delegate 原始 AgentScope 模型
     * @param activityRegistry 与 Gateway 共享的活动注册表
     * @param role 当前 Agent 所属角色
     */
    ActivityTrackingModel(Model delegate, ModelActivityRegistry activityRegistry, String role) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
        this.activityRegistry = Objects.requireNonNull(activityRegistry, "activityRegistry 不能为空");
        this.role = Objects.requireNonNull(role, "role 不能为空");
    }

    /**
     * 在订阅边界和每个响应分片刷新活动时间，并原样透传模型流。
     *
     * @param messages 当前模型消息
     * @param tools 当前工具 schema
     * @param generateOptions 生成选项
     * @return 未改写内容、顺序或终态信号的响应流
     */
    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions generateOptions
    ) {
        return Flux.defer(() -> {
            activityRegistry.recordActivity(role);
            return delegate.stream(messages, tools, generateOptions)
                    .doOnNext(ignored -> activityRegistry.recordActivity(role));
        });
    }

    /** 返回底层模型名称，不改变可观测模型身份。 */
    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    /** 返回底层模型是否原生支持结构化输出。 */
    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    /** 返回底层模型是否支持工具与原生结构化输出并用。 */
    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    /** 返回底层模型上下文窗口大小。 */
    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
