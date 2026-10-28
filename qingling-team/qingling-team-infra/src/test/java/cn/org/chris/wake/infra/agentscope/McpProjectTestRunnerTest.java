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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Docker Test Runner 的固定命令、结果找回、分类和脱敏。
 */
class McpProjectTestRunnerTest {

    /** 稳定测试 execution ID。 */
    private static final String EXECUTION_ID = "123e4567-e89b-12d3-a456-426614174000";

    /** 启动命令必须固定目录、同时覆盖 RD 自测与验收契约，并且不得创建持久 tmux 会话。 */
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
                .contains("test_targets='. ../acceptance/test_todo_contract.py'")
                .contains("timeout --signal=TERM --kill-after=5s 30s")
                .contains("python -m pytest --collect-only -q --disable-warnings --import-mode=importlib")
                .contains("timeout --signal=TERM --kill-after=5s 240s")
                .contains("python -m pytest --disable-warnings --import-mode=importlib")
                .contains("--cov=. --cov-report=term-missing")
                .doesNotContain("/tmp/code", "/workspace/rd/code");
    }

    /**
     * QA Runner 必须始终执行 Harness 固定契约，可附加 QA 用例但不能用 RD 自测冒充验收。
     */
    @Test
    void shouldRestrictQaRunnerToIndependentContractSuite() {
        AtomicReference<Map<String, Object>> input = new AtomicReference<>();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new FakeSandboxTool(input, "__QINGLING_STATUS__=RUNNING\n"));
        McpProjectTestRunner runner = new McpProjectTestRunner(toolkit, new ObjectMapper(), "qa");

        runner.start(EXECUTION_ID, "demo-project").block();

        assertThat(input.get().get("cmd").toString())
                .contains("test_targets='../acceptance/test_todo_contract.py'")
                .contains("test_targets=\\\"\\$test_targets ../qa/contract_tests/test_contract.py\\\"")
                .doesNotContain("test_targets='.'");
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
                + "__QINGLING_PYTEST_COLLECTED__=7\n"
                + "__QINGLING_PYTEST_PASSED__=7\n"
                + "__QINGLING_COVERAGE_PERCENT__=88\n"
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
                + "__QINGLING_PYTEST_COLLECTED__=2\n"
                + "__QINGLING_PYTEST_PASSED__=1\n"
                + "__QINGLING_COVERAGE_PERCENT__=73\n"
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
     * 大段输出查询即使不再携带终态标记，也必须保留元数据查询取得的 exit_code=1 业务失败。
     */
    @Test
    void shouldKeepCompletedPytestFailureWhenOutputResponseHasNoTerminalMetadata() {
        AtomicInteger calls = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new AgentTool() {
            /** 返回真实 MCP 工具名。 */
            @Override
            public String getName() {
                return "sandbox_execute_bash";
            }

            /** 返回测试替身说明。 */
            @Override
            public String getDescription() {
                return "按调用阶段返回终态元数据或输出摘录";
            }

            /** 返回接受任意对象的最小参数 Schema。 */
            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            /**
             * 分别模拟不被截断的终态元数据与不含终态字段的输出响应。
             *
             * @param param 原始调用参数
             * @return 对应查询阶段的模拟 MCP 结果
             */
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                calls.incrementAndGet();
                String callId = param.getToolUseBlock().getId();
                if (callId.startsWith("query-metadata-")) {
                    return Mono.just(ToolResultBlock.text(
                            "__QINGLING_STATUS__=FINISHED\n"
                                    + "__QINGLING_EXIT_CODE__=1\n"
                                    + "__QINGLING_PYTEST_COLLECTED__=32\n"
                                    + "__QINGLING_PYTEST_PASSED__=11\n"
                                    + "__QINGLING_COVERAGE_PERCENT__=72\n"
                    ));
                }
                if (callId.startsWith("query-stdout-")) {
                    return Mono.just(ToolResultBlock.text(
                            "__QINGLING_STDOUT_B64__="
                                    + base64("collected 32 items\n21 failed, 11 passed\nTOTAL 72%")
                                    + "\n"
                    ));
                }
                return Mono.just(ToolResultBlock.text(
                        "__QINGLING_STDERR_B64__=" + base64("AssertionError") + "\n"
                ));
            }
        });
        McpProjectTestRunner runner = new McpProjectTestRunner(toolkit, new ObjectMapper());

        ProjectTestExecution execution = runner.query(EXECUTION_ID).block();

        assertThat(execution).isNotNull();
        assertThat(calls).hasValue(3);
        assertThat(execution.status()).isEqualTo(ProjectTestExecution.Status.TEST_FAILED);
        assertThat(execution.exitCode()).isEqualTo(1);
        assertThat(execution.pytestCollected()).isEqualTo(32);
        assertThat(execution.pytestPassed()).isEqualTo(11);
        assertThat(execution.coveragePercent()).isEqualTo(72D);
        assertThat(execution.failureType()).isEqualTo(ProjectTestExecution.FailureType.PYTEST_FAILURE);
        assertThat(execution.retryable()).isFalse();
        assertThat(execution.stdout()).contains("21 failed, 11 passed");
        assertThat(execution.stderr()).contains("AssertionError");
    }

    /** FINISHED 状态先于原子退出码可见时必须继续轮询，不能制造 RESULT_UNAVAILABLE。 */
    @Test
    void shouldTreatFinishedWithoutExitCodeAsFinalizing() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Map<String, Object>> input = new AtomicReference<>();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new FakeSandboxTool(
                input,
                "__QINGLING_STATUS__=FINALIZING\n"
                        + "__QINGLING_PYTEST_COLLECTED__=32\n"
                        + "__QINGLING_PYTEST_PASSED__=11\n"
                        + "__QINGLING_COVERAGE_PERCENT__=72\n",
                calls
        ));
        McpProjectTestRunner runner = new McpProjectTestRunner(toolkit, new ObjectMapper());

        ProjectTestExecution execution = runner.query(EXECUTION_ID).block();

        assertThat(execution).isNotNull();
        assertThat(calls).hasValue(1);
        assertThat(execution.status()).isEqualTo(ProjectTestExecution.Status.RUNNING);
        assertThat(execution.exitCode()).isNull();
        assertThat(execution.failureType()).isEqualTo(ProjectTestExecution.FailureType.NONE);
        assertThat(input.get().get("cmd").toString())
                .contains("elif test \"$status\" = FINISHED; then status=FINALIZING")
                .contains("finalizing_since")
                .contains("test $((now - since)) -lt 5")
                .contains("else status=ORPHANED");
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

        /** 记录调用次数；不需要计数时为空。 */
        private final AtomicInteger calls;

        /**
         * 创建沙盒工具替身。
         *
         * @param input 参数捕获器
         * @param commandOutput 固定 Bash 输出
         */
        private FakeSandboxTool(AtomicReference<Map<String, Object>> input, String commandOutput) {
            this(input, commandOutput, null);
        }

        /**
         * 创建可记录调用次数的沙盒工具替身。
         *
         * @param input 参数捕获器
         * @param commandOutput 固定 Bash 输出
         * @param calls 可空调用计数器
         */
        private FakeSandboxTool(
                AtomicReference<Map<String, Object>> input,
                String commandOutput,
                AtomicInteger calls
        ) {
            this.input = input;
            this.commandOutput = commandOutput;
            this.calls = calls;
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
            if (calls != null) {
                calls.incrementAndGet();
            }
            input.set(param.getInput());
            return Mono.just(ToolResultBlock.text(commandOutput));
        }
    }
}
