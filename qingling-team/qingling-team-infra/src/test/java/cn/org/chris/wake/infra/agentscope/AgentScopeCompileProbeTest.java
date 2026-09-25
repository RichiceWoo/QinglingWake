package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固化 AgentScope 2.0.3 正式实现会使用的核心类型和构建 API。
 */
class AgentScopeCompileProbeTest {

    /**
     * 由 JUnit 提供隔离工作区，防止探针污染真实运行目录。
     */
    @TempDir
    Path workspace;

    /**
     * 验证模型、工具、Sub-Agent 与 HarnessAgent 可以在不请求真实模型时完成构建。
     */
    @Test
    void shouldBuildHarnessAgentWithToolkitAndSubagentWithoutCallingModel() {
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey("compile-probe-only")
                .modelName("qwen-plus")
                .stream(false)
                .build();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProbeTools());
        SubagentDeclaration subagent = SubagentDeclaration.builder()
                .name("probe-worker")
                .description("用于验证声明式子智能体 API")
                .inlineAgentsBody("仅执行编译探针任务。")
                .tools(List.of("probe_echo"))
                .persistSession(false)
                .build();

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("compile-probe")
                .description("AgentScope 编译探针")
                .sysPrompt("只用于验证构建 API，不发起模型请求。")
                .model(model)
                .toolkit(toolkit)
                .workspace(workspace)
                .subagent(subagent)
                .disableSessionPersistence()
                .disableToolsConfig()
                .build()) {
            assertThat(agent.getName()).isEqualTo("compile-probe");
            assertThat(agent.getToolkit().getToolNames()).contains("probe_echo");
        }
    }

    /**
     * 验证路由标识可进入 RuntimeContext，且 Agent 返回的 Mono 可转换为领域层采用的 Future。
     */
    @Test
    void shouldCarryRoutingIdentityAndConvertAgentMonoToFuture() {
        RuntimeContext context = RuntimeContext.builder()
                .userId("user-001")
                .sessionId("session-001")
                .put("routingKey", "p2p:user-001")
                .build();
        Mono<Msg> agentResult = Mono.empty();
        CompletableFuture<Msg> future = agentResult.toFuture();

        assertThat(context.getUserId()).isEqualTo("user-001");
        assertThat(context.getSessionId()).isEqualTo("session-001");
        assertThat(context.get("routingKey", String.class)).isEqualTo("p2p:user-001");
        assertThat(future).isCompletedWithValue(null);
    }

    /**
     * 提供最小注解工具，以编译和运行方式验证 Tool 与 ToolParam 的真实包名及注册行为。
     */
    static final class ProbeTools {

        /**
         * 原样返回探针文本，便于断言注册名称而不触发任何外部副作用。
         *
         * @param text 探针输入文本
         * @return 原样输入文本
         */
        @Tool(name = "probe_echo", description = "回显编译探针文本")
        String echo(@ToolParam(name = "text", description = "需要回显的文本") String text) {
            return text;
        }
    }
}
