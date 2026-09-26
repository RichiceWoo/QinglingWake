package cn.org.chris.wake.infra.agentscope;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import io.agentscope.harness.agent.tools.ToolsConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /** 线程安全的脱敏注册状态快照。 */
    private final List<RegistrationStatus> registrationStatuses = new CopyOnWriteArrayList<>();

    /**
     * 创建禁用 MCP 的配置，用于本地 smoke 或显式无沙箱场景。
     */
    private McpSandboxConfiguration() {
        this.toolsConfig = null;
    }

    /**
     * 创建启用单个 AIO-Sandbox 服务的配置。
     *
     * @param serverName MCP 服务名称
     * @param serverConfig AgentScope MCP 服务配置
     */
    private McpSandboxConfiguration(String serverName, McpServerConfig serverConfig) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("MCP serverName 不能为空");
        }
        ToolsConfig config = new ToolsConfig();
        config.setMcpServers(Map.of(serverName, Objects.requireNonNull(serverConfig, "serverConfig 不能为空")));
        this.toolsConfig = config;
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
     * 将 MCP 配置和状态监听器应用到 Harness builder。
     *
     * @param builder 待配置的角色 Agent builder
     * @return 同一 builder，便于继续链式构建
     */
    public HarnessAgent.Builder applyTo(HarnessAgent.Builder builder) {
        Objects.requireNonNull(builder, "builder 不能为空");
        if (toolsConfig == null) {
            return builder.disableToolsConfig();
        }
        return builder.toolsConfig(toolsConfig)
                .mcpServerRegistrationListener(registrationListener());
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
