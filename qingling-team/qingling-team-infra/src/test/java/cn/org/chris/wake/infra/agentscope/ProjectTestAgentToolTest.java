package cn.org.chris.wake.infra.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证结构化项目测试工具固定路径并返回 Java 可校验的测试证据。
 */
class ProjectTestAgentToolTest {

    /** JSON 结果解析器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 模型只能提供 project_id，包装器应固定 cwd、命令和结构化证据字段。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldFixProjectDirectoryAndReturnStructuredEvidence() throws Exception {
        AtomicReference<Map<String, Object>> delegatedInput = new AtomicReference<>();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new FakeSandboxTool(delegatedInput));
        ProjectTestAgentTool tool = new ProjectTestAgentTool(toolkit, objectMapper);
        ToolUseBlock use = new ToolUseBlock(
                "call-1", ProjectTestAgentTool.TOOL_NAME, Map.of("project_id", "demo-project")
        );

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                .toolUseBlock(use)
                .input(use.getInput())
                .build()).block();

        String text = ((TextBlock) result.getOutput().get(0)).getText();
        JsonNode evidence = objectMapper.readTree(text);
        assertThat(delegatedInput.get()).withFailMessage(text).isNotNull();
        assertThat(delegatedInput.get().get("cmd").toString())
                .contains("cd '/workspace/shared/projects/demo-project/code'")
                .doesNotContain("/tmp/code", "/workspace/rd/code");
        assertThat(delegatedInput.get()).containsEntry("timeout", 300);
        assertThat(evidence.path("exit_code").asInt()).isZero();
        assertThat(evidence.path("pytest_collected").asInt()).isEqualTo(7);
        assertThat(evidence.path("pytest_passed").asInt()).isEqualTo(7);
        assertThat(evidence.path("coverage_percent").asDouble()).isEqualTo(88D);
    }

    /**
     * 模拟已由 MCP 注册的原始沙盒工具。
     */
    private static final class FakeSandboxTool implements AgentTool {

        /** 捕获包装器下发的固定参数。 */
        private final AtomicReference<Map<String, Object>> delegatedInput;

        /**
         * 创建原始工具替身。
         *
         * @param delegatedInput 参数捕获器
         */
        private FakeSandboxTool(AtomicReference<Map<String, Object>> delegatedInput) {
            this.delegatedInput = delegatedInput;
        }

        /** 返回真实 MCP 工具名。 */
        @Override
        public String getName() {
            return "sandbox_execute_bash";
        }

        /** 返回测试工具说明。 */
        @Override
        public String getDescription() {
            return "测试替身";
        }

        /** 返回接受任意对象的最小参数 Schema。 */
        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object");
        }

        /**
         * 捕获固定命令并返回可解析的 pytest 输出。
         *
         * @param param 原始调用参数
         * @return 模拟 MCP 结果
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            delegatedInput.set(param.getInput());
            return Mono.just(ToolResultBlock.text("""
                    collected 7 items
                    ....... 7 passed in 0.20s
                    TOTAL 100 12 88%
                    __QINGLING_EXIT_CODE__=0
                    """));
        }
    }
}
