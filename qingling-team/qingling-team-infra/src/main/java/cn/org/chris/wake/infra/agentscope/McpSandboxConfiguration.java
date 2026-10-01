package cn.org.chris.wake.infra.agentscope;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 配置 AIO-Sandbox MCP，并以不泄漏凭据的快照暴露注册状态。
 */
public final class McpSandboxConfiguration {

    /** AgentScope tools.json 使用的 stdio 传输名称。 */
    public static final String STDIO_TRANSPORT = "stdio";

    /** AgentScope tools.json 使用的 streamable HTTP 传输名称。 */
    public static final String STREAMABLE_HTTP_TRANSPORT = "streamable-http";

    /** 禁用 MCP 时为空，启用时只包含 AIO-Sandbox 服务。 */
    private final ToolsConfig toolsConfig;

    /** 将 MCP 工具直接注册到父 Toolkit 的适配器，便于声明式 Sub-Agent 复制并按白名单继承。 */
    private final McpToolRegistrar toolRegistrar;

    /** 线程安全的脱敏注册状态快照。 */
    private final List<RegistrationStatus> registrationStatuses = new CopyOnWriteArrayList<>();

    /** AgentScope 实际完成的工具调用名称，用于 E2E 区分注册与真实调用。 */
    private final List<String> calledToolNames = new CopyOnWriteArrayList<>();

    /**
     * 创建禁用 MCP 的配置，用于本地 smoke 或显式无沙箱场景。
     */
    private McpSandboxConfiguration() {
        this.toolsConfig = null;
        this.toolRegistrar = McpServerRegistrar::register;
    }

    /**
     * 创建启用单个 AIO-Sandbox 服务的配置。
     *
     * @param serverName MCP 服务名称
     * @param serverConfig AgentScope MCP 服务配置
     */
    private McpSandboxConfiguration(String serverName, McpServerConfig serverConfig) {
        this(serverName, serverConfig, McpServerRegistrar::register);
    }

    /**
     * 创建可替换 MCP 注册器的配置，用于不访问外网的工具继承回归测试。
     *
     * @param serverName MCP 服务名称
     * @param serverConfig AgentScope MCP 服务配置
     * @param toolRegistrar MCP 工具注册器
     */
    McpSandboxConfiguration(
            String serverName,
            McpServerConfig serverConfig,
            McpToolRegistrar toolRegistrar
    ) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("MCP serverName 不能为空");
        }
        ToolsConfig config = new ToolsConfig();
        config.setMcpServers(Map.of(serverName, Objects.requireNonNull(serverConfig, "serverConfig 不能为空")));
        this.toolsConfig = config;
        this.toolRegistrar = Objects.requireNonNull(toolRegistrar, "toolRegistrar 不能为空");
    }

    /**
     * 返回无外部连接配置，构建 HarnessAgent 时会关闭 tools.json 自动发现。
     *
     * @return 禁用 MCP 的配置
     */
    public static McpSandboxConfiguration disabled() {
        return new McpSandboxConfiguration();
    }

    /**
     * 创建通过本地进程 stdio 连接 AIO-Sandbox 的配置。
     *
     * @param serverName MCP 服务名称
     * @param command 启动命令
     * @param args 启动参数
     * @param environment 子进程环境变量
     * @param enabledTools 允许注册的 MCP 工具名
     * @param timeout 单次调用超时
     * @param initializationTimeout 初始化超时
     * @return 启用 stdio MCP 的配置
     */
    public static McpSandboxConfiguration stdio(
            String serverName,
            String command,
            List<String> args,
            Map<String, String> environment,
            List<String> enabledTools,
            Duration timeout,
            Duration initializationTimeout
    ) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("MCP command 不能为空");
        }
        McpServerConfig server = baseServerConfig(
                STDIO_TRANSPORT, enabledTools, timeout, initializationTimeout
        );
        server.setCommand(command);
        server.setArgs(args == null ? List.of() : List.copyOf(args));
        server.setEnv(environment == null ? Map.of() : Map.copyOf(environment));
        return new McpSandboxConfiguration(serverName, server);
    }

    /**
     * 创建通过 streamable HTTP 连接 AIO-Sandbox 的配置。
     *
     * @param serverName MCP 服务名称
     * @param url MCP 服务地址
     * @param headers 请求 Header，可能包含凭据且不会进入状态快照
     * @param queryParams 查询参数
     * @param enabledTools 允许注册的 MCP 工具名
     * @param timeout 单次调用超时
     * @param initializationTimeout 初始化超时
     * @return 启用 HTTP MCP 的配置
     */
    public static McpSandboxConfiguration streamableHttp(
            String serverName,
            String url,
            Map<String, String> headers,
            Map<String, String> queryParams,
            List<String> enabledTools,
            Duration timeout,
            Duration initializationTimeout
    ) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("MCP url 不能为空");
        }
        McpServerConfig server = baseServerConfig(
                STREAMABLE_HTTP_TRANSPORT, enabledTools, timeout, initializationTimeout
        );
        server.setUrl(url);
        server.setHeaders(headers == null ? Map.of() : Map.copyOf(headers));
        server.setQueryParams(queryParams == null ? Map.of() : Map.copyOf(queryParams));
        return new McpSandboxConfiguration(serverName, server);
    }

    /**
     * 先把 MCP 工具注册到父 Toolkit，再关闭 Harness 的延迟 ToolsConfig 注册。
     *
     * <p>AgentScope 2.0.3 的声明式 Sub-Agent 只复制父 Toolkit，不复制 builder 的
     * ToolsConfig。提前注册后，子代理才能按 Markdown 中的 tools 白名单继承 MCP 工具。</p>
     *
     * @param builder 待配置的角色 Agent builder
     * @param toolkit 该角色将交给 Harness 的父 Toolkit
     * @return 同一 builder，便于继续链式构建
     */
    public synchronized HarnessAgent.Builder applyTo(HarnessAgent.Builder builder, Toolkit toolkit) {
        Objects.requireNonNull(builder, "builder 不能为空");
        Objects.requireNonNull(toolkit, "toolkit 不能为空");
        if (toolsConfig == null) {
            return builder.disableToolsConfig();
        }
        int statusStart = registrationStatuses.size();
        toolRegistrar.register(toolkit, toolsConfig.getMcpServers(), registrationListener());
        assertSuccessfulRegistration(statusStart, toolkit);
        normalizeCompletedMcpResults(toolkit);
        return builder.disableToolsConfig().hook(toolCallObserver());
    }

    /**
     * 包装白名单 MCP 工具，把已完成且有输出的伪 RUNNING 结果归一化为 SUCCESS。
     *
     * <p>AgentScope 2.0.3 的 MCP 适配器可能在 Mono 已完成后仍返回 RUNNING；若直接交给
     * Harness，声明式子代理会留下永不闭合的 pending tool。</p>
     *
     * @param toolkit 已完成 MCP 注册的父 Toolkit
     */
    private void normalizeCompletedMcpResults(Toolkit toolkit) {
        enabledToolNames().forEach(toolName -> {
            AgentTool original = toolkit.getTool(toolName);
            if (original == null) {
                return;
            }
            toolkit.removeToolIfSame(toolName, original);
            toolkit.registerAgentTool(new CompletedMcpAgentTool(original));
        });
    }

    /**
     * 汇总全部 MCP 服务的工具白名单并保持配置顺序。
     *
     * @return 不重复的 MCP 工具名
     */
    private Set<String> enabledToolNames() {
        Set<String> names = new LinkedHashSet<>();
        toolsConfig.getMcpServers().values().forEach(server -> {
            if (server.getEnableTools() != null) {
                names.addAll(server.getEnableTools());
            }
        });
        return names;
    }

    /**
     * 验证本轮每个 MCP 服务都完成注册，且配置白名单中的工具实际存在于父 Toolkit。
     *
     * @param statusStart 本轮注册前的状态数量
     * @param toolkit 已完成注册的父 Toolkit
     */
    private void assertSuccessfulRegistration(int statusStart, Toolkit toolkit) {
        List<RegistrationStatus> currentStatuses = registrationStatuses
                .subList(statusStart, registrationStatuses.size());
        int expectedServers = toolsConfig.getMcpServers().size();
        boolean allSuccessful = currentStatuses.size() == expectedServers
                && currentStatuses.stream().allMatch(status ->
                status.status() == McpServerRegistrationResult.Status.SUCCESS);
        if (!allSuccessful) {
            throw new IllegalStateException("AIO-Sandbox MCP 注册失败，已阻止启动缺少执行工具的 Agent");
        }
        Set<String> requiredTools = new LinkedHashSet<>();
        toolsConfig.getMcpServers().values().forEach(server -> {
            if (server.getEnableTools() != null) {
                requiredTools.addAll(server.getEnableTools());
            }
        });
        Set<String> missingTools = new LinkedHashSet<>(requiredTools);
        missingTools.removeAll(toolkit.getToolNames());
        if (!missingTools.isEmpty()) {
            throw new IllegalStateException("AIO-Sandbox MCP 缺少必需工具: " + missingTools);
        }
    }

    /**
     * 返回启用状态，供启动装配和健康检查使用。
     *
     * @return 已配置 AIO-Sandbox 时为 true
     */
    public boolean enabled() {
        return toolsConfig != null;
    }

    /**
     * 返回 AgentScope MCP 原始配置的只读 Optional；调用方不得记录其中凭据。
     *
     * @return MCP 工具配置
     */
    public Optional<ToolsConfig> toolsConfig() {
        return Optional.ofNullable(toolsConfig);
    }

    /**
     * 返回用于 Harness 注册回调的监听器。
     *
     * @return 脱敏采集监听器
     */
    public McpServerRegistrationListener registrationListener() {
        return this::recordStatus;
    }

    /**
     * 返回当前注册状态的不可变快照。
     *
     * @return 按完成顺序排列的状态
     */
    public List<RegistrationStatus> registrationStatuses() {
        return List.copyOf(registrationStatuses);
    }

    /**
     * 返回 AgentScope 已完成工具调用的名称快照，不记录参数和返回值以避免泄密。
     *
     * @return 按调用完成顺序排列的工具名
     */
    public List<String> calledToolNames() {
        return List.copyOf(calledToolNames);
    }

    /**
     * 在工具执行完成后只采集工具名；参数、命令正文和结果均不进入观测数据。
     *
     * @return AgentScope 工具调用观测 Hook
     */
    Hook toolCallObserver() {
        return new Hook() {
            /** 记录成功进入 PostActing 阶段的工具名并原样返回事件。 */
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostActingEvent actingEvent) {
                    calledToolNames.add(actingEvent.getToolUse().getName());
                }
                return Mono.just(event);
            }
        };
    }

    /** 将 AgentScope MCP 服务配置同步注册到指定 Toolkit。 */
    @FunctionalInterface
    interface McpToolRegistrar {

        /**
         * 注册服务工具并逐服务回调脱敏状态。
         *
         * @param toolkit 目标 Toolkit
         * @param servers MCP 服务配置
         * @param listener 注册状态监听器
         */
        void register(
                Toolkit toolkit,
                Map<String, McpServerConfig> servers,
                McpServerRegistrationListener listener
        );
    }

    /** 将 MCP 委托工具的已完成结果状态归一化，其他 schema 与只读属性保持不变。 */
    private static final class CompletedMcpAgentTool implements AgentTool {

        /** AgentScope 原始 MCP 工具。 */
        private final AgentTool delegate;

        /** 创建单个 MCP 工具包装器。 */
        private CompletedMcpAgentTool(AgentTool delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate 不能为空");
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
         * 委托真实 MCP 调用，并在 Mono 完成后修正带有效输出的伪 RUNNING 状态。
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return delegate.callAsync(param).map(CompletedMcpAgentTool::normalizeResult);
        }

        /** 已完成且存在输出时才修正状态，空输出的真正 pending 结果保持不变。 */
        private static ToolResultBlock normalizeResult(ToolResultBlock result) {
            if (result != null
                    && result.getState() == ToolResultState.RUNNING
                    && result.getOutput() != null
                    && !result.getOutput().isEmpty()) {
                return result.withState(ToolResultState.SUCCESS);
            }
            return result;
        }
    }

    /**
     * 构造两种传输共享的 MCP 参数。
     *
     * @param transport 传输名称
     * @param enabledTools 允许工具名
     * @param timeout 调用超时
     * @param initializationTimeout 初始化超时
     * @return MCP 服务配置
     */
    private static McpServerConfig baseServerConfig(
            String transport,
            List<String> enabledTools,
            Duration timeout,
            Duration initializationTimeout
    ) {
        McpServerConfig server = new McpServerConfig();
        server.setTransport(transport);
        server.setEnableTools(enabledTools == null ? List.of() : List.copyOf(enabledTools));
        server.setTimeout(requirePositive(timeout, "timeout"));
        server.setInitializationTimeout(requirePositive(initializationTimeout, "initializationTimeout"));
        return server;
    }

    /**
     * 拒绝空值、零值和负数超时。
     *
     * @param duration 待验证时长
     * @param name 参数名称
     * @return 已验证时长
     */
    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " 不能为空");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于零");
        }
        return duration;
    }

    /**
     * 将 AgentScope 注册结果转换为不含异常消息和配置秘密的状态。
     *
     * @param result AgentScope 原始结果
     */
    private void recordStatus(McpServerRegistrationResult result) {
        Objects.requireNonNull(result, "result 不能为空");
        String diagnostic = result.cause() == null
                ? ""
                : "registration failed (" + result.cause().getClass().getSimpleName() + ")";
        registrationStatuses.add(new RegistrationStatus(
                result.serverName(),
                result.transport(),
                result.status(),
                result.completedAt(),
                diagnostic
        ));
    }

    /**
     * MCP 注册结果的脱敏观测模型。
     *
     * @param serverName 服务名称
     * @param transport 传输类型
     * @param status AgentScope 注册状态
     * @param completedAt 完成时间
     * @param diagnostic 不包含异常消息或连接凭据的诊断
     */
    public record RegistrationStatus(
            /** MCP 服务名称。 */ String serverName,
            /** MCP 传输类型。 */ String transport,
            /** 成功、失败或跳过状态。 */ McpServerRegistrationResult.Status status,
            /** AgentScope 记录的完成时间。 */ Instant completedAt,
            /** 仅包含异常类型的脱敏诊断。 */ String diagnostic
    ) {
        /**
         * 固化状态快照的必填字段，避免健康检查接收不完整记录。
         */
        public RegistrationStatus {
            Objects.requireNonNull(serverName, "serverName 不能为空");
            Objects.requireNonNull(transport, "transport 不能为空");
            Objects.requireNonNull(status, "status 不能为空");
            Objects.requireNonNull(completedAt, "completedAt 不能为空");
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }
}
