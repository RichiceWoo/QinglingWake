package cn.org.chris.wake.infra.agentscope;

import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 验证 AIO-Sandbox MCP 配置和脱敏注册状态观测。
 */
class McpSandboxConfigurationTest {

    /**
     * stdio 配置必须保留执行参数和工具白名单，且不建立真实 MCP 连接。
     */
    @Test
    void shouldCreateStdioConfigurationWithoutConnecting() {
        McpSandboxConfiguration configuration = McpSandboxConfiguration.stdio(
                "aio-sandbox",
                "python",
                List.of("-m", "aio_sandbox.mcp"),
                Map.of("SANDBOX_TOKEN", "secret"),
                List.of("sandbox_execute_bash"),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10)
        );

        McpServerConfig server = configuration.toolsConfig().orElseThrow()
                .getMcpServers().get("aio-sandbox");

        assertThat(configuration.enabled()).isTrue();
        assertThat(server.getTransport()).isEqualTo(McpSandboxConfiguration.STDIO_TRANSPORT);
        assertThat(server.getCommand()).isEqualTo("python");
        assertThat(server.getArgs()).containsExactly("-m", "aio_sandbox.mcp");
        assertThat(server.getEnableTools()).containsExactly("sandbox_execute_bash");
    }

    /**
     * 成功、失败、跳过状态均应可观测，失败诊断不得包含异常消息中的 Token。
     */
    @Test
    void shouldObserveRegistrationStatusWithRedactedFailure() {
        McpSandboxConfiguration configuration = McpSandboxConfiguration.streamableHttp(
                "aio-sandbox",
                "http://127.0.0.1:18080/mcp",
                Map.of("Authorization", "Bearer top-secret"),
                Map.of(),
                List.of("sandbox_execute_bash"),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10)
        );

        configuration.registrationListener().onCompleted(
                McpServerRegistrationResult.success("aio-sandbox", "streamable-http")
        );
        configuration.registrationListener().onCompleted(
                McpServerRegistrationResult.failed(
                        "aio-sandbox", "streamable-http", new IllegalStateException("Bearer top-secret")
                )
        );
        configuration.registrationListener().onCompleted(
                McpServerRegistrationResult.skipped(
                        "optional", "stdio", new RuntimeException("password=hidden")
                )
        );

        assertThat(configuration.registrationStatuses())
                .extracting(McpSandboxConfiguration.RegistrationStatus::status)
                .containsExactly(
                        McpServerRegistrationResult.Status.SUCCESS,
                        McpServerRegistrationResult.Status.FAILED,
                        McpServerRegistrationResult.Status.SKIPPED
                );
        assertThat(configuration.registrationStatuses().get(1).diagnostic())
                .isEqualTo("registration failed (IllegalStateException)")
                .doesNotContain("top-secret", "Bearer");
        assertThat(configuration.registrationStatuses().get(2).diagnostic())
                .doesNotContain("password", "hidden");
    }

    /**
     * 禁用配置不得暴露 ToolsConfig，供无真实 MCP smoke 使用。
     */
    @Test
    void shouldSupportDisabledMode() {
        McpSandboxConfiguration configuration = McpSandboxConfiguration.disabled();

        assertThat(configuration.enabled()).isFalse();
        assertThat(configuration.toolsConfig()).isEmpty();
        assertThat(configuration.registrationStatuses()).isEmpty();
        assertThat(configuration.calledToolNames()).isEmpty();
    }

    /**
     * 工具调用观测只保留工具名，供真实 E2E 证明沙盒不是仅完成注册。
     */
    @Test
    void shouldObserveCompletedToolCallName() {
        McpSandboxConfiguration configuration = McpSandboxConfiguration.streamableHttp(
                "aio-sandbox",
                "http://127.0.0.1:18080/mcp",
                Map.of(),
                Map.of(),
                List.of("sandbox_execute_bash"),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10)
        );
        PostActingEvent event = new PostActingEvent(
                mock(Agent.class),
                mock(Toolkit.class),
                new ToolUseBlock("call-1", "sandbox_execute_bash", Map.of("cmd", "secret command")),
                ToolResultBlock.text("ok")
        );

        configuration.toolCallObserver().onEvent(event).block();

        assertThat(configuration.calledToolNames()).containsExactly("sandbox_execute_bash");
    }

    /**
     * MCP Mono 已结束且带输出时必须把 AgentScope 2.0.3 的伪 RUNNING 归一化为 SUCCESS。
     */
    @Test
    void shouldNormalizeCompletedRunningMcpResultToSuccess() {
        McpServerConfig server = new McpServerConfig();
        server.setTransport(McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT);
        server.setEnableTools(List.of("sandbox_execute_bash"));
        McpSandboxConfiguration configuration = new McpSandboxConfiguration(
                "aio-sandbox",
                server,
                (toolkit, servers, listener) -> {
                    toolkit.registerAgentTool(new RunningSandboxTool());
                    listener.onCompleted(McpServerRegistrationResult.success(
                            "aio-sandbox", McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT
                    ));
                }
        );
        Toolkit toolkit = new Toolkit();
        configuration.applyTo(HarnessAgent.builder(), toolkit);
        ToolUseBlock use = new ToolUseBlock(
                "call-running", "sandbox_execute_bash", Map.of("cmd", "pwd")
        );

        ToolResultBlock result = toolkit.getTool("sandbox_execute_bash").callAsync(ToolCallParam.builder()
                .toolUseBlock(use)
                .input(use.getInput())
                .agent(mock(Agent.class))
                .runtimeContext(RuntimeContext.builder().userId("user").sessionId("session").build())
                .build()).block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(result.getOutput().toString()).contains("completed-output");
    }

    /**
     * 注册器报告成功但未提供白名单工具时必须阻止启动，避免把缺失工具留到昂贵 E2E 才暴露。
     */
    @Test
    void shouldFailFastWhenRequiredSandboxToolIsMissing() {
        McpServerConfig server = new McpServerConfig();
        server.setTransport(McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT);
        server.setEnableTools(List.of("sandbox_execute_bash"));
        McpSandboxConfiguration configuration = new McpSandboxConfiguration(
                "aio-sandbox",
                server,
                (toolkit, servers, listener) -> listener.onCompleted(
                        McpServerRegistrationResult.success(
                                "aio-sandbox", McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT
                        )
                )
        );

        assertThatThrownBy(() -> configuration.applyTo(HarnessAgent.builder(), new Toolkit()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox_execute_bash");
    }

    /**
     * MCP 注册失败时必须使用脱敏异常阻止启动，不把底层异常中的凭据传播给调用方。
     */
    @Test
    void shouldFailFastWithRedactedMessageWhenRegistrationFails() {
        McpServerConfig server = new McpServerConfig();
        server.setTransport(McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT);
        server.setEnableTools(List.of("sandbox_execute_bash"));
        McpSandboxConfiguration configuration = new McpSandboxConfiguration(
                "aio-sandbox",
                server,
                (toolkit, servers, listener) -> listener.onCompleted(
                        McpServerRegistrationResult.failed(
                                "aio-sandbox",
                                McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT,
                                new IllegalStateException("Authorization=Bearer top-secret")
                        )
                )
        );

        assertThatThrownBy(() -> configuration.applyTo(HarnessAgent.builder(), new Toolkit()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AIO-Sandbox MCP 注册失败，已阻止启动缺少执行工具的 Agent")
                .hasMessageNotContaining("top-secret")
                .hasMessageNotContaining("Bearer")
                .hasMessageNotContaining("Authorization");
    }

    /** 返回已完成但错误标记为 RUNNING 的模拟 MCP 工具。 */
    private static final class RunningSandboxTool implements AgentTool {

        /** 返回模拟沙盒工具名。 */
        @Override
        public String getName() {
            return "sandbox_execute_bash";
        }

        /** 返回模拟沙盒工具描述。 */
        @Override
        public String getDescription() {
            return "模拟已完成 MCP 工具";
        }

        /** 返回最小参数 schema。 */
        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object");
        }

        /** 返回带输出但仍标记 RUNNING 的结果。 */
        @Override
        public reactor.core.publisher.Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return reactor.core.publisher.Mono.just(
                    ToolResultBlock.text("completed-output").withState(ToolResultState.RUNNING)
            );
        }
    }
}
