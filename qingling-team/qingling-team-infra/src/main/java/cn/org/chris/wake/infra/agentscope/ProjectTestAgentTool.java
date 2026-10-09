package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.workspace.WorkspacePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * 以结构化 projectId 包装受控 Docker Test Runner，并返回可分类测试证据。
 */
public final class ProjectTestAgentTool implements AgentTool {

    /** 对外暴露的结构化测试工具名称。 */
    public static final String TOOL_NAME = "run_project_tests";

    /** 同一 JVM 内按项目串行测试执行。 */
    private static final ConcurrentMap<String, Semaphore> PROJECT_PERMITS = new ConcurrentHashMap<>();

    /** 默认轮询 Docker 结果文件的间隔。 */
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);

    /** 默认等待项目测试终态的上限。 */
    private static final Duration DEFAULT_COMPLETION_TIMEOUT = Duration.ofMinutes(5);

    /** 受控 Docker Test Runner。 */
    private final ProjectTestRunner testRunner;

    /** 结构化结果编解码器。 */
    private final ObjectMapper objectMapper;

    /** 结果查询间隔。 */
    private final Duration pollInterval;

    /** 工具等待终态的固定上限。 */
    private final Duration completionTimeout;

    /** 当前工具所属工作流角色。 */
    private final String role;

    /** Java 工作流 incident 上报器。 */
    private final ToolFailureReporter failureReporter;

    /**
     * 创建固定目录的项目测试包装器。
     *
     * @param toolkit 已包含 sandbox_execute_bash 的父 Toolkit
     * @param objectMapper JSON 编解码器
     */
    public ProjectTestAgentTool(Toolkit toolkit, ObjectMapper objectMapper) {
        this(
                new McpProjectTestRunner(toolkit, objectMapper),
                objectMapper,
                DEFAULT_POLL_INTERVAL,
                DEFAULT_COMPLETION_TIMEOUT,
                "rd",
                ToolFailureReporter.NO_OP
        );
    }

    /**
     * 创建接入 Java 工作流 incident 的生产项目测试工具。
     *
     * @param toolkit 已包含可恢复 sandbox_execute_bash 的父 Toolkit
     * @param objectMapper JSON 编解码器
     * @param role rd 或 qa 当前 Owner
     * @param failureReporter 工具故障确定性上报器
     */
    public ProjectTestAgentTool(
            Toolkit toolkit,
            ObjectMapper objectMapper,
            String role,
            ToolFailureReporter failureReporter
    ) {
        this(
                new McpProjectTestRunner(toolkit, objectMapper),
                objectMapper,
                DEFAULT_POLL_INTERVAL,
                DEFAULT_COMPLETION_TIMEOUT,
                role,
                failureReporter
        );
    }

    /**
     * 创建可注入 Test Runner 与时限的包装器，用于无 Docker 单元测试。
     *
     * @param testRunner 可查询 Test Runner
     * @param objectMapper JSON 编解码器
     * @param pollInterval 轮询间隔
     * @param completionTimeout 完成上限
     */
    ProjectTestAgentTool(
            ProjectTestRunner testRunner,
            ObjectMapper objectMapper,
            Duration pollInterval,
            Duration completionTimeout
    ) {
        this(
                testRunner,
                objectMapper,
                pollInterval,
                completionTimeout,
                "rd",
                ToolFailureReporter.NO_OP
        );
    }

    /**
     * 创建可完全注入恢复与 incident 行为的测试包装器。
     *
     * @param testRunner 可查询 Test Runner
     * @param objectMapper JSON 编解码器
     * @param pollInterval 轮询间隔
     * @param completionTimeout 完成上限
     * @param role 当前工作流 Owner
     * @param failureReporter 故障上报器
     */
    ProjectTestAgentTool(
            ProjectTestRunner testRunner,
            ObjectMapper objectMapper,
            Duration pollInterval,
            Duration completionTimeout,
            String role,
            ToolFailureReporter failureReporter
    ) {
        this.testRunner = Objects.requireNonNull(testRunner, "testRunner 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.completionTimeout = requirePositive(completionTimeout, "completionTimeout");
        this.role = Objects.requireNonNull(role, "role 不能为空");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter 不能为空");
    }

    /** 返回结构化工具名。 */
    @Override
    public String getName() {
        return TOOL_NAME;
    }

    /** 返回强调固定目录、可查询 execution ID 与真实 pytest 的工具说明。 */
    @Override
    public String getDescription() {
        return "在固定 /workspace/shared/projects/{projectId}/code 中执行真实 pytest，返回可查询、可分类的结构化证据。";
    }

    /** 返回只接受 project_id 的 JSON Schema，禁止模型提供命令或工作目录。 */
    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "project_id", Map.of("type", "string", "description", "项目 ID")
                ),
                "required", java.util.List.of("project_id"),
                "additionalProperties", false
        );
    }

    /**
     * 为本次调用生成 execution ID，按项目串行等待 Docker 终态并返回统一 JSON。
     *
     * @param param AgentScope 工具调用上下文
     * @return 结构化测试结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String executionId = UUID.randomUUID().toString();
        try {
            String projectId = WorkspacePolicy.validateProjectId(text(param.getInput().get("project_id")));
            Semaphore permit = PROJECT_PERMITS.computeIfAbsent(projectId, ignored -> new Semaphore(1));
            return acquire(permit).flatMap(acquired -> executeWithSingleRecovery(executionId, projectId)
                    .map(this::structuredResult)
                    .onErrorResume(failure -> Mono.just(structuredResult(infrastructureFailure(executionId, false))))
                    .doFinally(ignored -> acquired.release()));
        } catch (RuntimeException exception) {
            return Mono.just(structuredResult(infrastructureFailure(executionId, false)));
        }
    }

    /**
     * 首次基础设施失败时重建连接，并以同一 execution ID 最多恢复一次。
     *
     * @param executionId 稳定执行标识
     * @param projectId 已校验项目标识
     * @return 最终执行结果
     */
    private Mono<ProjectTestExecution> executeWithSingleRecovery(String executionId, String projectId) {
        return executeOnce(executionId, projectId)
                .onErrorResume(firstFailure -> {
                    reportFailure(projectId, executionId, true, 1);
                    return testRunner.recoverConnection().flatMap(recovered -> {
                        if (!recovered) {
                            reportFailure(projectId, executionId, false, 2);
                            return Mono.error(firstFailure);
                        }
                        return executeOnce(executionId, projectId)
                                .onErrorResume(secondFailure -> {
                                    reportFailure(projectId, executionId, false, 2);
                                    return Mono.error(secondFailure);
                                });
                    });
                });
    }

    /**
     * 上报不含命令、输出或凭据的结构化工具 incident；上报失败不覆盖原工具结果。
     *
     * @param projectId 项目标识
     * @param executionId 稳定执行标识
     * @param retryable 是否仍可恢复
     * @param attempt 尝试序号
     */
    private void reportFailure(String projectId, String executionId, boolean retryable, int attempt) {
        try {
            failureReporter.report(
                    projectId,
                    role,
                    TOOL_NAME,
                    executionId,
                    ProjectTestExecution.FailureType.MCP_TRANSPORT.name(),
                    retryable,
                    attempt
            );
        } catch (RuntimeException ignored) {
            // incident 旁路不能把原始工具失败变成另一种未分类异常。
        }
    }

    /**
     * 幂等启动或找回同一个 execution ID，并把可恢复基础设施终态提升为异常。
     *
     * @param executionId 稳定执行标识
     * @param projectId 已校验项目标识
     * @return 非基础设施失败终态
     */
    private Mono<ProjectTestExecution> executeOnce(String executionId, String projectId) {
        return testRunner.start(executionId, projectId)
                .flatMap(started -> awaitTerminal(executionId, started, Instant.now().plus(completionTimeout)))
                .flatMap(execution -> execution.status() == ProjectTestExecution.Status.INFRASTRUCTURE_FAILED
                        && execution.retryable()
                        ? Mono.error(new RecoverableExecutionException())
                        : Mono.just(execution));
    }

    /**
     * 异步取得项目执行许可，避免阻塞 Reactor 调用线程。
     *
     * @param permit 项目级信号量
     * @return 取得许可后的同一信号量
     */
    private static Mono<Semaphore> acquire(Semaphore permit) {
        return Mono.fromCallable(() -> {
                    permit.acquire();
                    return permit;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 轮询 execution ID，直到取得终态或达到 Java 外层固定上限。
     *
     * @param executionId 稳定执行标识
     * @param current 当前快照
     * @param deadline 最晚完成时刻
     * @return 终态执行结果
     */
    private Mono<ProjectTestExecution> awaitTerminal(
            String executionId,
            ProjectTestExecution current,
            Instant deadline
    ) {
        if (current.terminal()) {
            return Mono.just(current);
        }
        if (!Instant.now().isBefore(deadline)) {
            return testRunner.cancel(executionId).map(cancelled -> new ProjectTestExecution(
                    executionId,
                    ProjectTestExecution.Status.TIMED_OUT,
                    cancelled.exitCode(),
                    cancelled.pytestCollected(),
                    cancelled.pytestPassed(),
                    cancelled.coveragePercent(),
                    ProjectTestExecution.FailureType.COMMAND_TIMEOUT,
                    true,
                    cancelled.stdout(),
                    cancelled.stderr()
            ));
        }
        return Mono.delay(pollInterval)
                .then(testRunner.query(executionId))
                .flatMap(next -> awaitTerminal(executionId, next, deadline));
    }

    /**
     * 构造不包含底层异常正文的传输失败结果。
     *
     * @param executionId 本次稳定执行标识
     * @return 可重试的基础设施失败
     */
    private static ProjectTestExecution infrastructureFailure(String executionId, boolean retryable) {
        return new ProjectTestExecution(
                executionId,
                ProjectTestExecution.Status.INFRASTRUCTURE_FAILED,
                null,
                0,
                0,
                -1D,
                ProjectTestExecution.FailureType.MCP_TRANSPORT,
                retryable,
                "",
                "AIO-Sandbox MCP transport unavailable"
        );
    }

    /**
     * 将完整执行结果序列化为稳定字段集合。
     *
     * @param execution 项目测试执行终态
     * @return AgentScope 工具结果
     */
    private ToolResultBlock structuredResult(ProjectTestExecution execution) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("execution_id", execution.executionId());
        evidence.put("status", execution.status().name());
        evidence.put("exit_code", execution.exitCode());
        evidence.put("pytest_collected", execution.pytestCollected());
        evidence.put("pytest_passed", execution.pytestPassed());
        evidence.put("coverage_percent", execution.coveragePercent());
        evidence.put("failure_type", execution.failureType().name());
        evidence.put("retryable", execution.retryable());
        evidence.put("stdout", execution.stdout());
        evidence.put("stderr", execution.stderr());
        try {
            return ToolResultBlock.text(objectMapper.writeValueAsString(evidence))
                    .withState(ToolResultState.SUCCESS);
        } catch (JsonProcessingException exception) {
            return ToolResultBlock.error("run_project_tests 结果序列化失败");
        }
    }

    /**
     * 将必填工具参数转换为非空文本。
     *
     * @param value 原始参数
     * @return 非空文本
     */
    private static String text(Object value) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("project_id 不能为空");
        }
        return value.toString();
    }

    /**
     * 校验持续时间为正数。
     *
     * @param value 待校验持续时间
     * @param name 参数名称
     * @return 原持续时间
     */
    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于零");
        }
        return value;
    }

    /** 表示同一 execution ID 尚可在重建连接后安全找回或幂等启动。 */
    private static final class RecoverableExecutionException extends RuntimeException {

        /** 创建不携带底层工具正文的内部恢复信号。 */
        private RecoverableExecutionException() {
            super("recoverable project test infrastructure failure");
        }
    }

    /** 把结构化工具基础设施失败写入 Java 工作流的窄接口。 */
    @FunctionalInterface
    public interface ToolFailureReporter {

        /** 不执行任何写入的默认实现，用于独立工具单测。 */
        ToolFailureReporter NO_OP = (projectId, role, tool, executionId, failureType, retryable, attempt) -> { };

        /**
         * 上报单次脱敏工具 incident。
         *
         * @param projectId 项目标识
         * @param role 当前阶段 Owner
         * @param tool 工具名称
         * @param executionId 稳定执行标识
         * @param failureType 失败分类
         * @param retryable 是否仍可恢复
         * @param attempt 尝试序号
         */
        void report(
                String projectId,
                String role,
                String tool,
                String executionId,
                String failureType,
                boolean retryable,
                int attempt
        );
    }
}
