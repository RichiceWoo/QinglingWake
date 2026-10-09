package cn.org.chris.wake.infra.agentscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 配置 AIO-Sandbox MCP，并以不泄漏凭据的快照暴露注册状态。
 */
public final class McpSandboxConfiguration implements AutoCloseable {

    /** 只用于同步受控 MCP 输入与 ToolUse content，不序列化凭据或工具输出。 */
    private static final ObjectMapper TOOL_INPUT_MAPPER = new ObjectMapper();

    /** 不需要服务端会话清理的禁用或 stdio 配置使用的空操作。 */
    private static final McpSessionCleaner NO_OP_SESSION_CLEANER = () -> {
    };

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

    /** 每个父 Toolkit 中可重建连接的 MCP 工具包装器。 */
    private final List<CircuitBreakingMcpAgentTool> managedTools = new CopyOnWriteArrayList<>();

    /** 在全部 MCP client 关闭后释放 AIO-Sandbox 服务端 shell 会话。 */
    private final McpSessionCleaner sessionCleaner;

    /** 保证客户端和服务端会话只执行一次关闭。 */
    private boolean closed;

    /**
     * 创建禁用 MCP 的配置，用于本地 smoke 或显式无沙箱场景。
     */
    private McpSandboxConfiguration() {
        this.toolsConfig = null;
        this.toolRegistrar = McpServerRegistrar::register;
        this.sessionCleaner = NO_OP_SESSION_CLEANER;
    }

    /**
     * 创建启用单个 AIO-Sandbox 服务的配置。
     *
     * @param serverName MCP 服务名称
     * @param serverConfig AgentScope MCP 服务配置
     */
    private McpSandboxConfiguration(String serverName, McpServerConfig serverConfig) {
        this(serverName, serverConfig, McpServerRegistrar::register, NO_OP_SESSION_CLEANER);
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
        this(serverName, serverConfig, toolRegistrar, NO_OP_SESSION_CLEANER);
    }

    /**
     * 创建可替换注册器和服务端会话清理器的配置。
     *
     * @param serverName MCP 服务名称
     * @param serverConfig AgentScope MCP 服务配置
     * @param toolRegistrar MCP 工具注册器
     * @param sessionCleaner 服务端 shell 会话清理器
     */
    McpSandboxConfiguration(
            String serverName,
            McpServerConfig serverConfig,
            McpToolRegistrar toolRegistrar,
            McpSessionCleaner sessionCleaner
    ) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("MCP serverName 不能为空");
        }
        ToolsConfig config = new ToolsConfig();
        config.setMcpServers(Map.of(serverName, Objects.requireNonNull(serverConfig, "serverConfig 不能为空")));
        this.toolsConfig = config;
        this.toolRegistrar = Objects.requireNonNull(toolRegistrar, "toolRegistrar 不能为空");
        this.sessionCleaner = Objects.requireNonNull(sessionCleaner, "sessionCleaner 不能为空");
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
        return new McpSandboxConfiguration(
                serverName,
                server,
                McpServerRegistrar::register,
                httpSessionCleaner(url, headers)
        );
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
        installCircuitBreakingTools(toolkit);
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
    private void installCircuitBreakingTools(Toolkit toolkit) {
        enabledToolNames().forEach(toolName -> {
            AgentTool original = toolkit.getTool(toolName);
            if (original == null) {
                return;
            }
            String serverName = serverNameFor(toolName);
            CircuitBreakingMcpAgentTool managed = new CircuitBreakingMcpAgentTool(
                    toolkit, serverName, original
            );
            toolkit.removeToolIfSame(toolName, original);
            toolkit.registerAgentTool(managed);
            managedTools.add(managed);
        });
    }

    /**
     * 查找声明指定白名单工具的 MCP 服务。
     *
     * @param toolName MCP 工具名
     * @return 唯一服务名
     */
    private String serverNameFor(String toolName) {
        return toolsConfig.getMcpServers().entrySet().stream()
                .filter(entry -> entry.getValue().getEnableTools() != null
                        && entry.getValue().getEnableTools().contains(toolName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 MCP 工具所属服务: " + toolName));
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
     * 返回全部角色 MCP 工具的脱敏熔断健康快照。
     *
     * @return 不包含 URL、Header、参数或输出的健康记录
     */
    public List<CircuitHealth> circuitHealth() {
        return managedTools.stream().map(CircuitBreakingMcpAgentTool::health).toList();
    }

    /**
     * 为零模型运行时契约受控打开当前连接并执行真实关闭、重建和探针。
     *
     * <p>该诊断不改变容器数据，也不接受 URL、命令或鉴权参数；任一连接恢复失败即返回 false。</p>
     *
     * @return 至少存在一个受管工具且全部重建成功时为 true
     */
    public boolean exerciseControlledRecovery() {
        if (managedTools.isEmpty()) {
            return false;
        }
        for (CircuitBreakingMcpAgentTool managedTool : managedTools) {
            managedTool.openCircuit();
            Boolean recovered = managedTool.recoverConnection().block(Duration.ofSeconds(30));
            if (!Boolean.TRUE.equals(recovered)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 显式关闭各角色 Toolkit 持有的 MCP client，确保服务端会话和 tmux 资源被释放。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        boolean hadManagedTools = !managedTools.isEmpty();
        Set<Toolkit> closedToolkits = Collections.newSetFromMap(new IdentityHashMap<>());
        RuntimeException firstFailure = null;
        for (CircuitBreakingMcpAgentTool managedTool : managedTools) {
            if (!closedToolkits.add(managedTool.toolkit)) {
                continue;
            }
            try {
                managedTool.closeClient();
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        managedTools.clear();
        if (hadManagedTools) {
            try {
                sessionCleaner.cleanup();
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * 从 AIO-Sandbox MCP 地址构造固定的 shell 会话清理请求。
     *
     * <p>请求只发往原 MCP 地址的同一 scheme 和 authority，不接受模型或运行时
     * 命令参数。AIO-Sandbox 1.11.0 的 MCP schema 未暴露会话关闭工具，但其原生
     * {@code DELETE /v1/shell/sessions} 会关闭受管 shell 及 tmux。</p>
     *
     * @param mcpUrl MCP streamable HTTP 地址
     * @param headers MCP 请求 Header，仅转发到同一 authority
     * @return 受控的服务端会话清理器
     */
    private static McpSessionCleaner httpSessionCleaner(
            String mcpUrl,
            Map<String, String> headers
    ) {
        URI mcpUri = URI.create(mcpUrl);
        if (mcpUri.getScheme() == null || mcpUri.getRawAuthority() == null) {
            throw new IllegalArgumentException("MCP url 必须是绝对 HTTP 地址");
        }
        URI cleanupUri = URI.create(mcpUri.getScheme() + "://" + mcpUri.getRawAuthority()
                + "/v1/shell/sessions");
        Map<String, String> safeHeaders = headers == null ? Map.of() : Map.copyOf(headers);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return () -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(cleanupUri)
                    .timeout(Duration.ofSeconds(10))
                    .DELETE();
            safeHeaders.forEach(request::header);
            try {
                HttpResponse<Void> response = client.send(
                        request.build(), HttpResponse.BodyHandlers.discarding()
                );
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException(
                            "AIO-Sandbox shell 会话清理失败 (HTTP " + response.statusCode() + ")"
                    );
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AIO-Sandbox shell 会话清理被中断", failure);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("AIO-Sandbox shell 会话清理连接失败", failure);
            }
        };
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

    /** 关闭 AIO-Sandbox 服务端受管 shell 会话的受控适配器。 */
    @FunctionalInterface
    interface McpSessionCleaner {

        /** 执行不接受外部参数的固定会话清理。 */
        void cleanup();
    }

    /** 将 MCP 委托工具的完成态归一化，并负责熔断、关闭旧 client 与受控重建。 */
    private final class CircuitBreakingMcpAgentTool
            implements AgentTool, McpProjectTestRunner.RecoverableMcpTool {

        /** 工具所在父 Toolkit，用于关闭和重新注册 MCP client。 */
        private final Toolkit toolkit;

        /** MCP client 名称，不含 URL 或凭据。 */
        private final String serverName;

        /** 当前可用的 AgentScope MCP 委托。 */
        private volatile AgentTool delegate;

        /** 当前熔断状态。 */
        private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);

        /** 当前连续连接失败次数。 */
        private final AtomicInteger consecutiveFailures = new AtomicInteger();

        /** 生命周期内连接失败总数。 */
        private final AtomicInteger totalFailures = new AtomicInteger();

        /** 串行化关闭、注册和探针，避免并发重复重建。 */
        private final ReentrantLock recoveryLock = new ReentrantLock();

        /** 最近一次成功恢复时间；尚未恢复时为 null。 */
        private volatile Instant lastRecoveredAt;

        /**
         * 创建单个可恢复 MCP 工具包装器。
         *
         * @param toolkit 工具所属父 Toolkit
         * @param serverName MCP client 名称
         * @param delegate 初始 AgentScope MCP 工具
         */
        private CircuitBreakingMcpAgentTool(Toolkit toolkit, String serverName, AgentTool delegate) {
            this.toolkit = Objects.requireNonNull(toolkit, "toolkit 不能为空");
            this.serverName = Objects.requireNonNull(serverName, "serverName 不能为空");
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
            if (state.get() != CircuitState.CLOSED) {
                return Mono.just(ToolResultBlock.error("AIO-Sandbox MCP circuit is open"));
            }
            ToolCallParam safeParam = forceStatelessShell(param);
            return delegate.callAsync(safeParam)
                    .map(CircuitBreakingMcpAgentTool::normalizeResult)
                    .map(result -> {
                        if (isConnectionFailure(result)) {
                            openCircuit();
                            return ToolResultBlock.error("AIO-Sandbox MCP connection unavailable");
                        }
                        return result;
                    })
                    .onErrorResume(failure -> {
                        openCircuit();
                        return Mono.just(ToolResultBlock.error("AIO-Sandbox MCP connection unavailable"));
                    })
                    // AgentScope 的外层 ToolExecutor 超时会取消 Mono；取消同样代表当前连接不可继续接收调用。
                    .doOnCancel(this::openCircuit);
        }

        /**
         * 强制通用 Bash 工具使用无状态调用，避免模型参数或服务端默认值遗留 tmux 会话。
         *
         * @param param 原始 AgentScope 工具调用
         * @return Bash 工具的无状态副本；其他 MCP 工具保持原样
         */
        private ToolCallParam forceStatelessShell(ToolCallParam param) {
            Objects.requireNonNull(param, "param 不能为空");
            if (!"sandbox_execute_bash".equals(getName())) {
                return param;
            }
            Map<String, Object> input = new LinkedHashMap<>(param.getInput());
            input.put("new_session", false);
            Map<String, Object> safeInput = Collections.unmodifiableMap(input);
            ToolUseBlock originalUse = Objects.requireNonNull(param.getToolUseBlock(), "toolUseBlock 不能为空");
            ToolUseBlock safeUse = new ToolUseBlock(
                    originalUse.getId(),
                    originalUse.getName(),
                    safeInput,
                    serializeToolInput(safeInput),
                    originalUse.getMetadata(),
                    originalUse.getState()
            );
            return ToolCallParam.builder(param)
                    .toolUseBlock(safeUse)
                    .input(safeInput)
                    .build();
        }

        /**
         * 把实际受控参数写入 ToolUse content，避免观测记录与委托输入不一致。
         *
         * @param input 已强制无状态的工具参数
         * @return JSON 参数正文
         */
        private static String serializeToolInput(Map<String, Object> input) {
            try {
                return TOOL_INPUT_MAPPER.writeValueAsString(input);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("MCP 工具参数序列化失败", exception);
            }
        }

        /**
         * 关闭失效 client、重新注册并通过无副作用探针后重新开放。
         *
         * @return 恢复成功时为 true
         */
        @Override
        public Mono<Boolean> recoverConnection() {
            return Mono.fromCallable(this::recoverBlocking).subscribeOn(Schedulers.boundedElastic());
        }

        /** 关闭当前 Toolkit 的 MCP client，不记录 URL、Header 或服务端异常正文。 */
        private void closeClient() {
            try {
                toolkit.removeMcpClient(serverName).block(Duration.ofSeconds(10));
            } catch (RuntimeException failure) {
                throw new IllegalStateException("AIO-Sandbox MCP client 关闭失败", failure);
            }
        }

        /**
         * 在互斥区内完成真实 client 关闭、注册、探针和委托替换。
         *
         * @return 新连接可用时为 true
         */
        private boolean recoverBlocking() {
            recoveryLock.lock();
            try {
                if (state.get() == CircuitState.CLOSED) {
                    return true;
                }
                state.set(CircuitState.HALF_OPEN);
                toolkit.removeToolIfSame(getName(), this);
                toolkit.removeMcpClient(serverName).block(Duration.ofSeconds(10));
                int statusStart = registrationStatuses.size();
                toolRegistrar.register(toolkit, toolsConfig.getMcpServers(), registrationListener());
                assertSuccessfulRegistration(statusStart, toolkit);
                AgentTool candidate = toolkit.getTool(getName());
                if (candidate == null || candidate == this || !probe(candidate)) {
                    throw new IllegalStateException("MCP 重建探针失败");
                }
                toolkit.removeToolIfSame(getName(), candidate);
                delegate = candidate;
                toolkit.registerAgentTool(this);
                consecutiveFailures.set(0);
                lastRecoveredAt = Instant.now();
                state.set(CircuitState.CLOSED);
                return true;
            } catch (RuntimeException failure) {
                if (toolkit.getTool(getName()) != this) {
                    AgentTool candidate = toolkit.getTool(getName());
                    if (candidate != null) {
                        toolkit.removeToolIfSame(getName(), candidate);
                    }
                    toolkit.registerAgentTool(this);
                }
                state.set(CircuitState.OPEN);
                return false;
            } finally {
                recoveryLock.unlock();
            }
        }

        /**
         * 对新连接执行不改变业务数据的 printf 探针。
         *
         * @param candidate 新注册 MCP 工具
         * @return 探针取得成功终态时为 true
         */
        private boolean probe(AgentTool candidate) {
            ToolUseBlock use = new ToolUseBlock(
                    "mcp-recovery-probe",
                    getName(),
                    Map.of("cmd", "printf qingling-mcp-recovery", "timeout", 5, "new_session", false),
                    "{\"cmd\":\"printf qingling-mcp-recovery\",\"timeout\":5,\"new_session\":false}",
                    Map.of()
            );
            ToolResultBlock result = candidate.callAsync(ToolCallParam.builder()
                            .toolUseBlock(use)
                            .input(use.getInput())
                            .build())
                    .map(CircuitBreakingMcpAgentTool::normalizeResult)
                    .block(Duration.ofSeconds(10));
            return result != null && result.getState() == ToolResultState.SUCCESS;
        }

        /** 将当前连接标记为 OPEN，并累计脱敏计数。 */
        private void openCircuit() {
            consecutiveFailures.incrementAndGet();
            totalFailures.incrementAndGet();
            state.set(CircuitState.OPEN);
        }

        /**
         * 判断 AgentScope 返回是否表示连接层失败。
         *
         * @param result 已归一化结果
         * @return 错误、中断、拒绝或空结果时为 true
         */
        private static boolean isConnectionFailure(ToolResultBlock result) {
            return result == null
                    || result.getState() == ToolResultState.ERROR
                    || result.getState() == ToolResultState.INTERRUPTED
                    || result.getState() == ToolResultState.DENIED;
        }

        /**
         * 生成不含连接参数的健康快照。
         *
         * @return 当前熔断状态
         */
        private CircuitHealth health() {
            return new CircuitHealth(
                    serverName,
                    getName(),
                    state.get(),
                    consecutiveFailures.get(),
                    totalFailures.get(),
                    lastRecoveredAt
            );
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

    /** MCP 连接熔断状态。 */
    public enum CircuitState {
        /** 连接健康并允许调用。 */
        CLOSED,
        /** 已检测到连接失败，新调用立即拒绝。 */
        OPEN,
        /** 正在重建连接并执行唯一健康探针。 */
        HALF_OPEN
    }

    /**
     * MCP 连接健康的脱敏快照。
     *
     * @param serverName MCP 服务逻辑名称
     * @param toolName 工具名称
     * @param state 当前熔断状态
     * @param consecutiveFailures 当前连续失败次数
     * @param totalFailures 生命周期失败总数
     * @param lastRecoveredAt 最近成功恢复时间，可为空
     */
    public record CircuitHealth(
            String serverName,
            String toolName,
            CircuitState state,
            int consecutiveFailures,
            int totalFailures,
            Instant lastRecoveredAt
    ) {
        /** 固化非空标识与状态，避免健康证据不完整。 */
        public CircuitHealth {
            Objects.requireNonNull(serverName, "serverName 不能为空");
            Objects.requireNonNull(toolName, "toolName 不能为空");
            Objects.requireNonNull(state, "state 不能为空");
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
