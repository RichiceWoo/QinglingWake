package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.harness.agent.tools.McpServerRegistrationListener;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固化 MCP 客户端配置入口与 Harness 注册结果的可观测契约。
 */
class AgentScopeMcpProbeTest {

    /**
     * 验证 stdio 与 streamable HTTP 两类 MCP builder 可配置且无需建立真实连接。
     */
    @Test
    void shouldConfigureSupportedMcpTransportsWithoutConnecting() {
        McpClientBuilder stdio = McpClientBuilder.create("sandbox-stdio")
                .stdioTransport("aio-sandbox", "mcp")
                .timeout(Duration.ofSeconds(30));
        McpClientBuilder http = McpClientBuilder.create("sandbox-http")
                .streamableHttpTransport("http://127.0.0.1:18080/mcp")
                .header("Authorization", "Bearer redacted")
                .initializationTimeout(Duration.ofSeconds(10));

        assertThat(stdio).isNotNull();
        assertThat(http).isNotNull();
    }

    /**
     * 验证成功、失败和跳过三种注册状态均可通过监听器统一采集。
     */
    @Test
    void shouldObserveMcpRegistrationSuccessAndFailure() {
        List<McpServerRegistrationResult> observed = new ArrayList<>();
        McpServerRegistrationListener listener = observed::add;
        RuntimeException failure = new RuntimeException("connection failed");

        listener.onCompleted(McpServerRegistrationResult.success("sandbox", "stdio"));
        listener.onCompleted(McpServerRegistrationResult.failed("sandbox", "stdio", failure));
        listener.onCompleted(McpServerRegistrationResult.skipped("optional", "http", failure));

        assertThat(observed)
                .extracting(McpServerRegistrationResult::status)
                .containsExactly(
                        McpServerRegistrationResult.Status.SUCCESS,
                        McpServerRegistrationResult.Status.FAILED,
                        McpServerRegistrationResult.Status.SKIPPED
                );
        assertThat(observed.get(1).cause()).isSameAs(failure);
    }
}
