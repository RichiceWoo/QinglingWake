package cn.org.chris.wake.infra.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Docker Test Runner 的固定命令、结果找回、分类和脱敏。
 */
class McpProjectTestRunnerTest {

    /** 稳定测试 execution ID。 */
    private static final String EXECUTION_ID = "123e4567-e89b-12d3-a456-426614174000";

    /**
     * 启动命令必须固定项目目录、内部超时和 execution 状态目录，且不得创建持久 tmux 会话。
     */
    @Test
    void shouldLaunchIdempotentControlledDockerExecution() {
        AtomicReference<Map<String, Object>> input = new AtomicReference<>();
        McpProjectTestRunner runner = runner(input, "__QINGLING_STATUS__=RUNNING\n");

        ProjectTestExecution execution = runner.start(EXECUTION_ID, "demo-project").block();

        assertThat(execution).isNotNull();
        assertThat(input.get()).containsEntry("new_session", false);
        assertThat(input.get().get("cmd").toString())
                .contains("mkdir '/workspace/.qingling-test-runs/" + EXECUTION_ID + "'")
                .contains("cd '/workspace/shared/projects/demo-project/code'")
                .contains("timeout --signal=TERM --kill-after=5s 270s")
                .contains("python -m pytest --disable-warnings --cov=. --cov-report=term-missing")
                .doesNotContain("/tmp/code", "/workspace/rd/code");
    }

    /**
     * 响应丢失后按 execution ID 查询应恢复原终态，并脱敏受限输出。
     */
    @Test
    void shouldRecoverTerminalResultByExecutionIdAndRedactSecrets() {
        String stdout = "collected 7 items\n....... 7 passed\nTOTAL 100 12 88%\n"
                + "DASHSCOPE_API_KEY=top-secret";
        String stderr = "Authorization=secret-value Bearer hidden-token";
        String response = "__QINGLING_STATUS__=FINISHED\n"
                + "__QINGLING_EXIT_CODE__=0\n"
                + "__QINGLING_STDOUT_B64__=" + base64(stdout) + "\n"
                + "__QINGLING_STDERR_B64__=" + base64(stderr) + "\n";
        McpProjectTestRunner runner = runner(new AtomicReference<>(), response);

        ProjectTestExecution execution = runner.query(EXECUTION_ID).block();

        assertThat(execution).isNotNull();
        assertThat(execution.executionId()).isEqualTo(EXECUTION_ID);
        assertThat(execution.status()).isEqualTo(ProjectTestExecution.Status.SUCCEEDED);
        assertThat(execution.exitCode()).isZero();
        assertThat(execution.pytestCollected()).isEqualTo(7);
        assertThat(execution.pytestPassed()).isEqualTo(7);
        assertThat(execution.coveragePercent()).isEqualTo(88D);
        assertThat(execution.stdout()).contains("DASHSCOPE_API_KEY=[REDACTED]").doesNotContain("top-secret");
        assertThat(execution.stderr()).contains("Bearer [REDACTED]").doesNotContain("hidden-token", "secret-value");
    }

    /**
     * pytest 的标准退出码必须保留为不可重试业务失败。
     */
    @Test
    void shouldClassifyPytestFailureSeparatelyFromInfrastructure() {
        String response = "__QINGLING_STATUS__=FINISHED\n"
                + "__QINGLING_EXIT_CODE__=1\n"
                + "__QINGLING_STDOUT_B64__=" + base64("collected 2 items\n1 passed, 1 failed") + "\n"
                + "__QINGLING_STDERR_B64__=" + base64("AssertionError") + "\n";
        McpProjectTestRunner runner = runner(new AtomicReference<>(), response);

        ProjectTestExecution execution = runner.query(EXECUTION_ID).block();

        assertThat(execution).isNotNull();
        assertThat(execution.status()).isEqualTo(ProjectTestExecution.Status.TEST_FAILED);
        assertThat(execution.failureType()).isEqualTo(ProjectTestExecution.FailureType.PYTEST_FAILURE);
        assertThat(execution.retryable()).isFalse();
    }

    /**
     * 创建返回固定 Bash 输出的 MCP Runner。
     *
     * @param input 捕获最后一次 MCP 参数
     * @param commandOutput Bash 输出
     * @return 被测 Runner
     */
    private static McpProjectTestRunner runner(
            AtomicReference<Map<String, Object>> input,
            String commandOutput
    ) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new FakeSandboxTool(input, commandOutput));
        return new McpProjectTestRunner(toolkit, new ObjectMapper());
    }

    /**
     * 编码查询协议中的受限输出。
     *
     * @param value 原始文本
     * @return Base64 文本
     */
    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 模拟已由 MCP 注册的原始沙盒工具。 */
    private static final class FakeSandboxTool implements AgentTool {

        /** 捕获最后一次调用参数。 */
        private final AtomicReference<Map<String, Object>> input;

        /** 每次调用返回的固定 Bash 输出。 */
        private final String commandOutput;

        /**
         * 创建沙盒工具替身。
         *
         * @param input 参数捕获器
         * @param commandOutput 固定 Bash 输出
         */
        private FakeSandboxTool(AtomicReference<Map<String, Object>> input, String commandOutput) {
            this.input = input;
            this.commandOutput = commandOutput;
        }

        /** 返回真实 MCP 工具名。 */
        @Override
        public String getName() {
            return "sandbox_execute_bash";
        }

        /** 返回测试替身说明。 */
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
         * 捕获受控命令并返回固定查询协议。
         *
         * @param param 原始调用参数
         * @return 模拟 MCP 结果
         */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            input.set(param.getInput());
            return Mono.just(ToolResultBlock.text(commandOutput));
        }
    }
}
