package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 将领域 AgentGateway 适配到四角色 AgentScope HarnessAgent。
 */
public final class AgentScopeAgentGateway implements AgentGateway, AutoCloseable {

    /** RuntimeContext 中保存目标角色的键。 */
    public static final String ROLE_ATTRIBUTE = "role";

    /** RuntimeContext 中保存附件 workspace 相对路径的键。 */
    public static final String ATTACHMENT_PATHS_ATTRIBUTE = "attachmentPaths";

    /** 按角色绑定的可调用 Agent 运行时。 */
    private final Map<String, AgentRuntime> runtimes;

    /**
     * 以实际 HarnessAgent 映射创建领域 Gateway。
     *
     * @param agents 四角色 HarnessAgent
     */
    public AgentScopeAgentGateway(Map<String, HarnessAgent> agents) {
        this(adaptAgents(agents), RuntimeMode.PRODUCTION);
    }

    /**
     * 使用可观测运行时创建测试适配器，不启动真实模型。
     *
     * @param runtimes 测试运行时
     * @param ignored 区分生产构造器的内部标记
     */
    AgentScopeAgentGateway(Map<String, AgentRuntime> runtimes, RuntimeMode ignored) {
        Objects.requireNonNull(ignored, "runtimeMode 不能为空");
        if (runtimes == null || runtimes.isEmpty()) {
            throw new IllegalArgumentException("Agent 运行时不能为空");
        }
        this.runtimes = Map.copyOf(runtimes);
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
        AgentRuntime runtime = requireRuntime(request.role());
        RuntimeContext context = RuntimeContext.builder()
                .userId(request.userId())
                .sessionId(request.sessionId())
                .putAll(request.attributes())
                .put(ROLE_ATTRIBUTE, request.role())
                .put(ATTACHMENT_PATHS_ATTRIBUTE, request.attachmentPaths())
                .build();
        return runtime.call(request.content(), context)
                .map(AgentScopeAgentGateway::toReply)
                .toFuture();
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
        requireRuntime(role).clearContext(userId, sessionId);
    }

    /**
     * 关闭所有角色 Harness 资源。
     */
    @Override
    public void close() {
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
}
