package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.workflow.WorkflowStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
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

    /** Test Runner 调用开始时必须解除同一恢复回合的测试前写入门禁。 */
    @Test
    void shouldRecordTestInvocationBeforeRunnerStarts() {
        CompletionProtocolRegistry registry = new CompletionProtocolRegistry();
        registry.expect(
                "rd", "session-test-first", "demo-project",
                "run_project_tests_now_then_fix_or_task_done", true
        );
        ProjectTestExecution success = execution(
                ProjectTestExecution.Status.SUCCEEDED,
                0,
                2,
                2,
                90D,
                ProjectTestExecution.FailureType.NONE,
                false,
                "2 passed",
                ""
        );
        ProjectTestAgentTool tool = new ProjectTestAgentTool(
                immediateRunner(new AtomicReference<>(), success),
                objectMapper,
                Duration.ofMillis(1),
                Duration.ofSeconds(1),
                "rd",
                ProjectTestAgentTool.ToolFailureReporter.NO_OP,
                (projectId, role) -> WorkflowStage.CODE_IMPLEMENTATION,
                ProjectTestAgentTool.ProjectTestExecutionObserver.NO_OP,
                registry
        );
        ToolUseBlock use = new ToolUseBlock(
                "call-test-first", ProjectTestAgentTool.TOOL_NAME,
                Map.of("project_id", "demo-project")
        );
        RuntimeContext context = RuntimeContext.builder()
                .userId("rd")
                .sessionId("session-test-first")
                .put(AgentScopeAgentGateway.ROLE_ATTRIBUTE, "rd")
                .put(AgentScopeAgentGateway.PROJECT_ID_ATTRIBUTE, "demo-project")
                .build();

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                .toolUseBlock(use)
                .input(use.getInput())
                .runtimeContext(context)
                .build()).block();

        assertThat(result).isNotNull();
        assertThat(registry.pending("rd", "session-test-first"))
                .get().extracting(CompletionProtocolRegistry.Expectation::testInvoked)
                .isEqualTo(true);
        assertThat(registry.codeWriteBlockedUntilTest(
                "rd", "session-test-first", "demo-project"
        )).isFalse();
    }

    /** 测试执行必须绑定调用开始时的阶段，异步完成期间发生迁移也不能改写证据归属。 */
    @Test
    void shouldFreezeWorkflowStageBeforeAsyncExecutionStarts() throws Exception {
        AtomicReference<WorkflowStage> currentStage = new AtomicReference<>(WorkflowStage.CODE_IMPLEMENTATION);
        AtomicReference<WorkflowStage> recordedStage = new AtomicReference<>();
        ProjectTestExecution success = execution(
                ProjectTestExecution.Status.SUCCEEDED,
                0,
                3,
                3,
                90D,
                ProjectTestExecution.FailureType.NONE,
                false,
                "3 passed",
                ""
        );
        ProjectTestRunner delegate = immediateRunner(new AtomicReference<>(), success);
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 启动后模拟工作流并发迁移到 QA。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                currentStage.set(WorkflowStage.QA_TEST_EXECUTION);
                return delegate.start(executionId, projectId);
            }

            /** 委托结果查询。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return delegate.query(executionId);
            }

            /** 委托执行取消。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                return delegate.cancel(executionId);
            }
        };
        ProjectTestAgentTool tool = new ProjectTestAgentTool(
                runner,
                objectMapper,
                Duration.ofMillis(1),
                Duration.ofSeconds(1),
                "rd",
                ProjectTestAgentTool.ToolFailureReporter.NO_OP,
                (projectId, role) -> currentStage.get(),
                (projectId, role, stage, execution) -> recordedStage.set(stage)
        );

        invoke(tool, "demo-project");

        assertThat(recordedStage).hasValue(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(currentStage).hasValue(WorkflowStage.QA_TEST_EXECUTION);
    }

    /** Java 工作流拒绝测试启动时不得触达 Docker，也不得误报为 MCP 传输故障。 */
    @Test
    void shouldRejectInvocationBeforeRunnerStartsWhenWorkflowBudgetIsExhausted() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 记录任何不应发生的 Docker 启动。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                starts.incrementAndGet();
                return Mono.just(running(executionId));
            }

            /** 拒绝发生在启动前，因此不应查询。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.error(new AssertionError("不应查询"));
            }

            /** 拒绝发生在启动前，因此不应取消。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                return Mono.error(new AssertionError("不应取消"));
            }
        };
        ProjectTestAgentTool.ProjectTestExecutionObserver observer =
                new ProjectTestAgentTool.ProjectTestExecutionObserver() {
                    /** 模拟当前 attempt 已耗尽三次调用预算。 */
                    @Override
                    public void begin(
                            String projectId,
                            String role,
                            WorkflowStage stage,
                            String executionId
                    ) {
                        throw new IllegalStateException("当前工作尝试的 Test Runner 调用预算已耗尽");
                    }

                    /** 未启动的执行不得登记为真实终态。 */
                    @Override
                    public void record(
                            String projectId,
                            String role,
                            WorkflowStage stage,
                            ProjectTestExecution execution
                    ) {
                        throw new AssertionError("不应登记");
                    }
                };
        ProjectTestAgentTool tool = new ProjectTestAgentTool(
                runner,
                objectMapper,
                Duration.ofMillis(1),
                Duration.ofSeconds(1),
                "rd",
                ProjectTestAgentTool.ToolFailureReporter.NO_OP,
                (projectId, role) -> WorkflowStage.CODE_IMPLEMENTATION,
                observer
        );

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(starts).hasValue(0);
        assertThat(evidence.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(evidence.path("failure_type").asText()).isEqualTo("INVOCATION_REJECTED");
        assertThat(evidence.path("retryable").asBoolean()).isFalse();
        assertThat(evidence.path("stderr").asText()).doesNotContain("调用预算");
    }

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

    /** runner 进入短暂 FINALIZING 窗口时应继续查询，不得重建连接或写入工具故障。 */
    @Test
    void shouldPollThroughFinalizingWindowWithoutRecovery() throws Exception {
        AtomicInteger queries = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        List<String> incidents = new ArrayList<>();
        ProjectTestExecution running = execution(
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
        ProjectTestExecution failed = execution(
                ProjectTestExecution.Status.TEST_FAILED,
                1,
                27,
                24,
                97D,
                ProjectTestExecution.FailureType.PYTEST_FAILURE,
                false,
                "24 passed, 3 failed",
                "AssertionError"
        );
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 模拟 runner 已启动但仍在原子写入退出码。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                return Mono.just(withExecutionId(executionId, running));
            }

            /** 第一次保持 FINALIZING 映射的 RUNNING，第二次返回真实业务终态。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.just(withExecutionId(
                        executionId,
                        queries.incrementAndGet() == 1 ? running : failed
                ));
            }

            /** 正常收尾不会触发取消。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                return Mono.error(new AssertionError("不应取消"));
            }

            /** 记录错误触发的连接恢复，正常收尾时必须保持为零。 */
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
                        incidents.add(failureType + ":" + retryable + ":" + attempt)
        );

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(queries).hasValue(2);
        assertThat(recoveries).hasValue(0);
        assertThat(incidents).isEmpty();
        assertThat(evidence.path("status").asText()).isEqualTo("TEST_FAILED");
        assertThat(evidence.path("exit_code").asInt()).isEqualTo(1);
        assertThat(evidence.path("pytest_passed").asInt()).isEqualTo(24);
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
        List<String> incidents = new ArrayList<>();
        AtomicReference<ProjectTestExecution> observed = new AtomicReference<>();
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
                Duration.ofMillis(5),
                "rd",
                (projectId, role, toolName, executionId, failureType, retryable, attempt) ->
                        incidents.add(failureType + ":" + retryable + ":" + attempt),
                (projectId, role, stage, execution) -> observed.set(execution)
        );

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(cancellations).hasValue(1);
        assertThat(evidence.path("status").asText()).isEqualTo("TIMED_OUT");
        assertThat(evidence.path("failure_type").asText()).isEqualTo("COMMAND_TIMEOUT");
        assertThat(evidence.path("retryable").asBoolean()).isFalse();
        assertThat(incidents).containsExactly(
                "COMMAND_TIMEOUT:true:1",
                "COMMAND_TIMEOUT:false:2"
        );
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().status()).isEqualTo(ProjectTestExecution.Status.TIMED_OUT);
        assertThat(observed.get().retryable()).isFalse();
    }

    /**
     * 等待上限与 pytest 完成竞态时，应保留取消查询取得的真实业务终态，不能伪造超时。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldKeepTerminalResultFoundWhileCancellingAtDeadline() throws Exception {
        AtomicInteger cancellations = new AtomicInteger();
        List<String> incidents = new ArrayList<>();
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 首次快照仍为运行中。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                return Mono.just(running(executionId));
            }

            /** 上限到达前的最后一次轮询仍返回运行中。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.just(running(executionId));
            }

            /** 模拟取消锁定进程前 pytest 已完成并持久化成功终态。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                cancellations.incrementAndGet();
                return Mono.just(new ProjectTestExecution(
                        executionId,
                        ProjectTestExecution.Status.SUCCEEDED,
                        0,
                        4,
                        4,
                        93D,
                        ProjectTestExecution.FailureType.NONE,
                        false,
                        "4 passed",
                        ""
                ));
            }
        };
        ProjectTestAgentTool tool = new ProjectTestAgentTool(
                runner,
                objectMapper,
                Duration.ofMillis(5),
                Duration.ofMillis(1),
                "rd",
                (projectId, role, toolName, executionId, failureType, retryable, attempt) ->
                        incidents.add(failureType + ":" + retryable + ":" + attempt)
        );

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(cancellations).hasValue(1);
        assertThat(incidents).isEmpty();
        assertThat(evidence.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(evidence.path("exit_code").asInt()).isZero();
        assertThat(evidence.path("pytest_passed").asInt()).isEqualTo(4);
        assertThat(evidence.path("failure_type").asText()).isEqualTo("NONE");
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
        assertThat(incidents).isEmpty();
        assertThat(evidence.path("execution_id").asText()).isEqualTo(firstExecutionId.get());
        assertThat(evidence.path("status").asText()).isEqualTo("SUCCEEDED");
    }

    /**
     * 首次终态查询短暂不可用、恢复后取得 pytest 业务失败时不得留下 RECOVERING incident。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldDiscardTransientUnavailableIncidentAfterRecoveringBusinessFailure() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        List<String> incidents = new ArrayList<>();
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 首次返回可恢复结果缺失，第二次按同一 execution ID 返回 pytest 业务失败。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                if (starts.incrementAndGet() == 1) {
                    return Mono.just(new ProjectTestExecution(
                            executionId,
                            ProjectTestExecution.Status.INFRASTRUCTURE_FAILED,
                            null,
                            0,
                            0,
                            -1D,
                            ProjectTestExecution.FailureType.RESULT_UNAVAILABLE,
                            true,
                            "",
                            ""
                    ));
                }
                return Mono.just(new ProjectTestExecution(
                        executionId,
                        ProjectTestExecution.Status.TEST_FAILED,
                        1,
                        27,
                        24,
                        97D,
                        ProjectTestExecution.FailureType.PYTEST_FAILURE,
                        false,
                        "24 passed, 3 failed",
                        "AssertionError"
                ));
            }

            /** 本替身直接由 start 返回终态，不进入轮询。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.error(new AssertionError("不应查询"));
            }

            /** 本替身不会触发超时取消。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                return Mono.error(new AssertionError("不应取消"));
            }

            /** 模拟连接重建和探针成功。 */
            @Override
            public Mono<Boolean> recoverConnection() {
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
                        incidents.add(failureType + ":" + retryable + ":" + attempt)
        );

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(starts).hasValue(2);
        assertThat(incidents).isEmpty();
        assertThat(evidence.path("status").asText()).isEqualTo("TEST_FAILED");
        assertThat(evidence.path("exit_code").asInt()).isEqualTo(1);
        assertThat(evidence.path("pytest_passed").asInt()).isEqualTo(24);
        assertThat(evidence.path("failure_type").asText()).isEqualTo("PYTEST_FAILURE");
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
     * 重连后同一 execution ID 若已被取消，必须上报恢复耗尽，不能静默返回而遗漏 BLOCKED。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldReportExhaustionWhenRecoveredExecutionIsCancelled() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        List<String> incidents = new ArrayList<>();
        ProjectTestRunner runner = new ProjectTestRunner() {
            /** 首次返回可恢复超时，重连后的幂等查询返回取消终态。 */
            @Override
            public Mono<ProjectTestExecution> start(String executionId, String projectId) {
                if (starts.incrementAndGet() == 1) {
                    return Mono.just(new ProjectTestExecution(
                            executionId,
                            ProjectTestExecution.Status.TIMED_OUT,
                            124,
                            0,
                            0,
                            -1D,
                            ProjectTestExecution.FailureType.COMMAND_TIMEOUT,
                            true,
                            "",
                            ""
                    ));
                }
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

            /** 本替身直接由 start 返回持久终态，不进入轮询。 */
            @Override
            public Mono<ProjectTestExecution> query(String executionId) {
                return Mono.error(new AssertionError("不应查询"));
            }

            /** 本替身直接返回超时终态，不需要额外取消。 */
            @Override
            public Mono<ProjectTestExecution> cancel(String executionId) {
                return Mono.error(new AssertionError("不应取消"));
            }

            /** 允许唯一一次连接重建。 */
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
                        incidents.add(failureType + ":" + retryable + ":" + attempt)
        );

        JsonNode evidence = invoke(tool, "demo-project");

        assertThat(starts).hasValue(2);
        assertThat(recoveries).hasValue(1);
        assertThat(incidents).containsExactly(
                "COMMAND_TIMEOUT:true:1",
                "CANCELLED:false:2"
        );
        assertThat(evidence.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(evidence.path("failure_type").asText()).isEqualTo("CANCELLED");
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
