package cn.org.chris.wake.infra.agentscope;

import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    }
}
