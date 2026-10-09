package cn.org.chris.wake.infra.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证结构化项目测试工具的稳定 execution ID、故障分类与固定超时。
 */
class ProjectTestAgentToolTest {

    /** JSON 结果解析器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * pytest 成功时应返回完整结构化证据，且只向 Runner 传递 project ID。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldReturnSuccessfulStructuredEvidence() throws Exception {
        AtomicReference<String> projectId = new AtomicReference<>();
        ProjectTestExecution success = execution(
                ProjectTestExecution.Status.SUCCEEDED,
                0,
                7,
                7,
                88D,
                ProjectTestExecution.FailureType.NONE,
                false,
                "7 passed",
                ""
        );
        ProjectTestRunner runner = immediateRunner(projectId, success);
        ProjectTestAgentTool tool = tool(runner, Duration.ofSeconds(1));

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(projectId.get()).isEqualTo("demo-project");
        assertThat(evidence.path("execution_id").asText())
                .matches("[0-9a-f-]{36}");
        assertThat(evidence.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(evidence.path("exit_code").asInt()).isZero();
        assertThat(evidence.path("pytest_collected").asInt()).isEqualTo(7);
        assertThat(evidence.path("pytest_passed").asInt()).isEqualTo(7);
        assertThat(evidence.path("coverage_percent").asDouble()).isEqualTo(88D);
        assertThat(evidence.path("failure_type").asText()).isEqualTo("NONE");
        assertThat(evidence.path("retryable").asBoolean()).isFalse();
    }

    /**
     * pytest 非零退出必须分类为业务失败，不能误触发基础设施重试。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldKeepPytestFailureNonRetryable() throws Exception {
        ProjectTestExecution failed = execution(
                ProjectTestExecution.Status.TEST_FAILED,
                1,
                12,
                11,
                91D,
                ProjectTestExecution.FailureType.PYTEST_FAILURE,
                false,
                "11 passed, 1 failed",
                "AssertionError"
        );
        ProjectTestAgentTool tool = tool(immediateRunner(new AtomicReference<>(), failed), Duration.ofSeconds(1));

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(evidence.path("status").asText()).isEqualTo("TEST_FAILED");
        assertThat(evidence.path("failure_type").asText()).isEqualTo("PYTEST_FAILURE");
        assertThat(evidence.path("retryable").asBoolean()).isFalse();
        assertThat(evidence.path("stderr").asText()).contains("AssertionError");
    }

    /**
     * 不具备恢复能力的 MCP 异常必须转换为脱敏、不可继续重试的基础设施失败结构。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldClassifyTransportFailureWithoutLeakingCause() throws Exception {
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 模拟启动阶段携带秘密的底层异常。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                return Mono.error(new IllegalStateException("Authorization: Bearer top-secret"));
            }

            /** 本场景不会进入查询。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.error(new AssertionError("不应查询"));
            }

            /** 本场景不会进入取消。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                return Mono.error(new AssertionError("不应取消"));
            }
        };
        ProjectTestAgentTool tool = tool(runner, Duration.ofSeconds(1));

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(evidence.path("status").asText()).isEqualTo("INFRASTRUCTURE_FAILED");
        assertThat(evidence.path("failure_type").asText()).isEqualTo("MCP_TRANSPORT");
        assertThat(evidence.path("retryable").asBoolean()).isFalse();
        assertThat(evidence.toString()).doesNotContain("top-secret", "Bearer");
    }

    /**
     * Java 外层等待耗尽时必须调用取消并返回命令超时，而不是无限 pending。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldCancelWhenCompletionDeadlineExpires() throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 返回持续运行状态。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                return Mono.just(running(executionId));
            }

            /** 轮询始终返回运行中。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.just(running(executionId));
            }

            /** 记录取消并返回取消终态。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                cancellations.incrementAndGet();
                return Mono.just(new ProjectTestExecution(
                        executionId,
                        ProjectTestExecution.Status.CANCELLED,
                        143,
                        0,
                        0,
                        -1D,
                        ProjectTestExecution.FailureType.CANCELLED,
                        false,
                        "",
                        ""
                ));
            }
        };
        ProjectTestAgentTool tool = new ProjectTestAgentTool(
                runner,
                objectMapper,
                Duration.ofMillis(1),
                Duration.ofMillis(5)
        );

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(cancellations).hasValue(1);
        assertThat(evidence.path("status").asText()).isEqualTo("TIMED_OUT");
        assertThat(evidence.path("failure_type").asText()).isEqualTo("COMMAND_TIMEOUT");
        assertThat(evidence.path("retryable").asBoolean()).isTrue();
    }

    /**
     * 幂等测试执行首次传输失败时应使用同一 execution ID 恢复一次并成功返回。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldRecoverIdempotentExecutionOnceWithSameExecutionId() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        AtomicReference<String> firstExecutionId = new AtomicReference<>();
        List<String> incidents = new ArrayList<>();
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 首次失败，第二次以同一 execution ID 成功。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                firstExecutionId.compareAndSet(null, executionId);
                assertThat(executionId).isEqualTo(firstExecutionId.get());
                if (starts.incrementAndGet() == 1) {
                    return Mono.error(new IllegalStateException("transport lost"));
                }
                return Mono.just(withExecutionId(executionId, execution(
                        ProjectTestExecution.Status.SUCCEEDED,
                        0,
                        3,
                        3,
                        95D,
                        ProjectTestExecution.FailureType.NONE,
                        false,
                        "3 passed",
                        ""
                )));
            }

            /** 成功终态不需要查询。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.error(new AssertionError("不应查询"));
            }

            /** 成功终态不需要取消。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                return Mono.error(new AssertionError("不应取消"));
            }

            /** 记录唯一连接重建。 */
            @Override
            public Mono<Boolean> recoverConnection() {
                recoveries.incrementAndGet();
                return Mono.just(true);
            }
        };
        ProjectTestAgentTool tool = new ProjectTestAgentTool(
                runner,
                objectMapper,
                Duration.ofMillis(1),
                Duration.ofSeconds(1),
                "rd",
                (projectId, role, toolName, executionId, failureType, retryable, attempt) ->
                        incidents.add(projectId + ":" + role + ":" + retryable + ":" + attempt)
        );

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(starts).hasValue(2);
        assertThat(recoveries).hasValue(1);
        assertThat(incidents).containsExactly("demo-project:rd:true:1");
        assertThat(evidence.path("execution_id").asText()).isEqualTo(firstExecutionId.get());
        assertThat(evidence.path("status").asText()).isEqualTo("SUCCEEDED");
    }

    /**
     * 恢复后的第二次同类失败必须停止重试并返回不可重试终态。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldStopAfterSecondInfrastructureFailure() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        List<String> incidents = new ArrayList<>();
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 每次启动均模拟同类连接失败。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                starts.incrementAndGet();
                return Mono.error(new IllegalStateException("transport lost"));
            }

            /** 失败发生在启动阶段，不进入查询。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.error(new AssertionError("不应查询"));
            }

            /** 失败发生在启动阶段，不进入取消。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                return Mono.error(new AssertionError("不应取消"));
            }

            /** 仅允许一次成功重建尝试。 */
            @Override
            public Mono<Boolean> recoverConnection() {
                recoveries.incrementAndGet();
                return Mono.just(true);
            }
        };
        ProjectTestAgentTool tool = new ProjectTestAgentTool(
                runner,
                objectMapper,
                Duration.ofMillis(1),
                Duration.ofSeconds(1),
                "rd",
                (projectId, role, toolName, executionId, failureType, retryable, attempt) ->
                        incidents.add(retryable + ":" + attempt)
        );

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(starts).hasValue(2);
        assertThat(recoveries).hasValue(1);
        assertThat(incidents).containsExactly("true:1", "false:2");
        assertThat(evidence.path("status").asText()).isEqualTo("INFRASTRUCTURE_FAILED");
        assertThat(evidence.path("failure_type").asText()).isEqualTo("MCP_TRANSPORT");
        assertThat(evidence.path("retryable").asBoolean()).isFalse();
    }

    /**
     * 创建立即返回终态的 Runner 替身。
     *
     * @param projectId 捕获项目标识
     * @param terminal 预设终态
     * @return Runner 替身
     */
    private static ProjectTestRunner immediateRunner(
            AtomicReference<String> projectId,
            ProjectTestExecution terminal
    ) {
        return new ProjectTestRunner() {
            /** 捕获项目并绑定工具生成的 execution ID。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String requestedProjectId) {
                projectId.set(requestedProjectId);
                return Mono.just(withExecutionId(executionId, terminal));
            }

            /** 立即终态场景不需要查询。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.just(withExecutionId(executionId, terminal));
            }

            /** 立即终态场景不需要取消。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                return Mono.just(withExecutionId(executionId, terminal));
            }
        };
    }

    /**
     * 创建指定终态的测试结果模板。
     *
     * @return execution ID 尚未绑定的结果
     */
    private static ProjectTestExecution execution(
            ProjectTestExecution.Status status,
            Integer exitCode,
            int collected,
            int passed,
            double coverage,
            ProjectTestExecution.FailureType failureType,
            boolean retryable,
            String stdout,
            String stderr
    ) {
        return new ProjectTestExecution(
                "placeholder", status, exitCode, collected, passed, coverage,
                failureType, retryable, stdout, stderr
        );
    }

    /**
     * 把工具生成的 execution ID 写入预设结果。
     *
     * @param executionId 工具生成标识
     * @param source 预设结果
     * @return 绑定标识后的结果
     */
    private static ProjectTestExecution withExecutionId(String executionId, ProjectTestExecution source) {
        return new ProjectTestExecution(
                executionId,
                source.status(),
                source.exitCode(),
                source.pytestCollected(),
                source.pytestPassed(),
                source.coveragePercent(),
                source.failureType(),
                source.retryable(),
                source.stdout(),
                source.stderr()
        );
    }

    /**
     * 创建使用短轮询间隔的被测工具。
     *
     * @param runner Runner 替身
     * @param timeout 完成上限
     * @return 被测工具
     */
    private ProjectTestAgentTool tool(ProjectTestRunner runner, Duration timeout) {
        return new ProjectTestAgentTool(runner, objectMapper, Duration.ofMillis(1), timeout);
    }

    /**
     * 调用工具并解析唯一文本 JSON。
     *
     * @param tool 被测工具
     * @param projectId 项目标识
     * @return 结构化 JSON
     * @throws Exception JSON 解析失败
     */
    private JsonNode invoke(ProjectTestAgentTool tool, String projectId) throws Exception {
        ToolUseBlock use = new ToolUseBlock(
                "call-1", ProjectTestAgentTool.TOOL_NAME, Map.of("project_id", projectId)
        );
        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                .toolUseBlock(use)
                .input(use.getInput())
                .build()).block();
        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        String text = ((TextBlock) result.getOutput().get(0)).getText();
        return objectMapper.readTree(text);
    }

    /**
     * 创建运行中快照。
     *
     * @param executionId 稳定执行标识
     * @return 运行中结果
     */
    private static ProjectTestExecution running(String executionId) {
        return new ProjectTestExecution(
                executionId,
                ProjectTestExecution.Status.RUNNING,
                null,
                0,
                0,
                -1D,
                ProjectTestExecution.FailureType.NONE,
                false,
                "",
                ""
        );
    }
}
