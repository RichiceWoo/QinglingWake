package cn.org.chris.wake.infra.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 AIO-Sandbox MCP 在 Docker 内实现可查询、可取消的无状态项目测试执行。
 */
public final class McpProjectTestRunner implements ProjectTestRunner {

    /** 底层 AIO-Sandbox Bash 工具名称。 */
    private static final String SANDBOX_TOOL_NAME = "sandbox_execute_bash";

    /** Docker 内持久保存 execution ID 状态的根目录。 */
    private static final String EXECUTION_ROOT = "/workspace/.qingling-test-runs";

    /** 单份 stdout/stderr 允许返回的最大字节数。 */
    private static final int OUTPUT_LIMIT = 32_768;

    /** pytest 进程硬超时秒数，低于 MCP 外层等待上限。 */
    private static final int PROCESS_TIMEOUT_SECONDS = 270;

    /** 查询命令的 MCP 等待上限秒数。 */
    private static final int QUERY_TIMEOUT_SECONDS = 15;

    /** pytest 输出中的 collected 数量。 */
    private static final Pattern COLLECTED_PATTERN = Pattern.compile("collected\\s+(\\d+)\\s+items?");

    /** pytest 简洁摘要中的通过数量。 */
    private static final Pattern PASSED_PATTERN = Pattern.compile("(\\d+)\\s+passed");

    /** pytest-cov TOTAL 行中的覆盖率。 */
    private static final Pattern COVERAGE_PATTERN = Pattern.compile("(?m)^TOTAL\\s+.*?\\s+(\\d+(?:\\.\\d+)?)%\\s*$");

    /** 查询输出中的状态字段。 */
    private static final Pattern STATUS_PATTERN = Pattern.compile("(?m)^__QINGLING_STATUS__=(.+)$");

    /** 查询输出中的退出码字段。 */
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("(?m)^__QINGLING_EXIT_CODE__=(-?\\d+)$");

    /** 查询输出中的 Base64 stdout 字段。 */
    private static final Pattern STDOUT_PATTERN = Pattern.compile("(?m)^__QINGLING_STDOUT_B64__=(.*)$");

    /** 查询输出中的 Base64 stderr 字段。 */
    private static final Pattern STDERR_PATTERN = Pattern.compile("(?m)^__QINGLING_STDERR_B64__=(.*)$");

    /** 常见凭据赋值的脱敏模式。 */
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)(DASHSCOPE_API_KEY|QWEN_API_KEY|authorization|x-[a-z0-9-]*authorization)\\s*[:=]\\s*[^\\s,;]+"
    );

    /** Bearer 鉴权值的脱敏模式。 */
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");

    /** 已注册原始 MCP 工具的父 Toolkit。 */
    private final Toolkit toolkit;

    /** 解析 MCP 包装 JSON 的编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建受控 Docker Test Runner。
     *
     * @param toolkit 已包含 sandbox_execute_bash 的父 Toolkit
     * @param objectMapper JSON 编解码器
     */
    public McpProjectTestRunner(Toolkit toolkit, ObjectMapper objectMapper) {
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        if (!toolkit.getToolNames().contains(SANDBOX_TOOL_NAME)) {
            throw new IllegalStateException("创建 Test Runner 前缺少 sandbox_execute_bash");
        }
    }

    /**
     * 在 execution ID 专属目录中幂等启动固定 pytest 进程组。
     *
     * @param executionId Java 生成的 UUID
     * @param projectId 已校验项目标识
     * @return 启动后的当前快照
     */
    @Override
    public Mono<ProjectTestExecution> start(String executionId, String projectId) {
        validateExecutionId(executionId);
        String runDirectory = runDirectory(executionId);
        String projectDirectory = "/workspace/shared/projects/" + projectId + "/code";
        String command = "set -eu; mkdir -p '" + EXECUTION_ROOT + "'; "
                + "if mkdir '" + runDirectory + "' 2>/dev/null; then "
                + "printf '%s' '" + projectId + "' > '" + runDirectory + "/project_id'; "
                + "printf RUNNING > '" + runDirectory + "/status'; "
                + ": > '" + runDirectory + "/stdout'; : > '" + runDirectory + "/stderr'; "
                + "setsid sh -c \"set +e; cd '" + projectDirectory + "'; "
                + "timeout --signal=TERM --kill-after=5s " + PROCESS_TIMEOUT_SECONDS + "s "
                + "python -m pytest --disable-warnings --cov=. --cov-report=term-missing "
                + ">'" + runDirectory + "/stdout' 2>'" + runDirectory + "/stderr'; "
                + "rc=\\$?; printf '%s' \\\"\\$rc\\\" > '" + runDirectory + "/exit_code.tmp'; "
                + "mv '" + runDirectory + "/exit_code.tmp' '" + runDirectory + "/exit_code'; "
                + "printf FINISHED > '" + runDirectory + "/status'\" >/dev/null 2>&1 & "
                + "printf '%s' \"$!\" > '" + runDirectory + "/runner_pid'; fi; "
                + "printf '__QINGLING_STATUS__=RUNNING\\n'";
        return callSandbox("start-" + executionId, command, QUERY_TIMEOUT_SECONDS)
                .map(ignored -> new ProjectTestExecution(
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
                ));
    }

    /**
     * 查询 Docker execution 目录，并把受限输出转换为统一结构。
     *
     * @param executionId 稳定执行标识
     * @return 当前或终态快照
     */
    @Override
    public Mono<ProjectTestExecution> query(String executionId) {
        validateExecutionId(executionId);
        String runDirectory = runDirectory(executionId);
        String command = "set +e; run='" + runDirectory + "'; limit=" + OUTPUT_LIMIT + "; "
                + "if test ! -d \"$run\"; then status=MISSING; "
                + "elif test -f \"$run/status\"; then status=$(cat \"$run/status\"); "
                + "else status=MISSING; fi; "
                + "if test \"$status\" = RUNNING && test -f \"$run/runner_pid\"; then "
                + "pid=$(cat \"$run/runner_pid\"); kill -0 \"$pid\" 2>/dev/null || status=ORPHANED; fi; "
                + "printf '__QINGLING_STATUS__=%s\\n' \"$status\"; "
                + "if test -f \"$run/exit_code\"; then printf '__QINGLING_EXIT_CODE__=%s\\n' \"$(cat \"$run/exit_code\")\"; fi; "
                + "printf '__QINGLING_STDOUT_B64__='; test -f \"$run/stdout\" && tail -c \"$limit\" \"$run/stdout\" | base64 | tr -d '\\n'; printf '\\n'; "
                + "printf '__QINGLING_STDERR_B64__='; test -f \"$run/stderr\" && tail -c \"$limit\" \"$run/stderr\" | base64 | tr -d '\\n'; printf '\\n'";
        return callSandbox("query-" + executionId, command, QUERY_TIMEOUT_SECONDS)
                .map(output -> parseExecution(executionId, output));
    }

    /**
     * 终止 Test Runner 进程组并写入可查询取消结果。
     *
     * @param executionId 稳定执行标识
     * @return 取消后的快照
     */
    @Override
    public Mono<ProjectTestExecution> cancel(String executionId) {
        validateExecutionId(executionId);
        String runDirectory = runDirectory(executionId);
        String command = "set +e; run='" + runDirectory + "'; "
                + "if test -f \"$run/runner_pid\" && ! test -f \"$run/exit_code\"; then "
                + "pid=$(cat \"$run/runner_pid\"); kill -TERM -- \"-$pid\" 2>/dev/null; sleep 1; "
                + "kill -KILL -- \"-$pid\" 2>/dev/null; printf 143 > \"$run/exit_code\"; printf CANCELLED > \"$run/status\"; fi; "
                + "printf '__QINGLING_STATUS__=CANCELLED\\n'";
        return callSandbox("cancel-" + executionId, command, QUERY_TIMEOUT_SECONDS)
                .then(query(executionId));
    }

    /**
     * 委托可恢复 MCP 工具重建底层 client；普通工具不声称恢复成功。
     *
     * @return 连接与探针均成功时为 true
     */
    @Override
    public Mono<Boolean> recoverConnection() {
        if (toolkit.getTool(SANDBOX_TOOL_NAME) instanceof RecoverableMcpTool recoverable) {
            return recoverable.recoverConnection();
        }
        return Mono.just(false);
    }

    /**
     * 调用原始 MCP Bash，并把 AgentScope 的错误状态恢复为响应式异常。
     *
     * @param callId 不包含凭据的调用标识
     * @param command 仅由本类生成的固定命令
     * @param timeoutSeconds MCP 等待秒数
     * @return Bash 实际输出
     */
    private Mono<String> callSandbox(String callId, String command, int timeoutSeconds) {
        Map<String, Object> input = Map.of(
                "cmd", command,
                "timeout", timeoutSeconds,
                "new_session", false
        );
        ToolUseBlock use = new ToolUseBlock(
                callId,
                SANDBOX_TOOL_NAME,
                input,
                objectMapper.valueToTree(input).toString(),
                Map.of()
        );
        return toolkit.callTool(ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build())
                .flatMap(result -> {
                    if (result.getState() == ToolResultState.ERROR
                            || result.getState() == ToolResultState.INTERRUPTED
                            || result.getState() == ToolResultState.DENIED) {
                        return Mono.error(new SandboxTransportException("AIO-Sandbox MCP 调用失败"));
                    }
                    return Mono.just(unwrapSandboxOutput(outputText(result)));
                });
    }

    /**
     * 解析查询标记与 pytest 摘要，并严格区分业务失败和基础设施失败。
     *
     * @param executionId 稳定执行标识
     * @param output 查询命令输出
     * @return 结构化执行结果
     */
    private ProjectTestExecution parseExecution(String executionId, String output) {
        String rawStatus = match(STATUS_PATTERN, output, "MISSING");
        Integer exitCode = integerMatch(EXIT_CODE_PATTERN, output);
        String stdout = sanitize(decodeBase64(match(STDOUT_PATTERN, output, "")));
        String stderr = sanitize(decodeBase64(match(STDERR_PATTERN, output, "")));
        String combined = stdout + "\n" + stderr;
        int passed = intMatch(PASSED_PATTERN, combined, 0);
        int collected = intMatch(COLLECTED_PATTERN, combined, passed);
        double coverage = doubleMatch(COVERAGE_PATTERN, combined, -1D);
        ProjectTestExecution.Status status;
        ProjectTestExecution.FailureType failureType;
        boolean retryable;
        if ("RUNNING".equals(rawStatus)) {
            status = ProjectTestExecution.Status.RUNNING;
            failureType = ProjectTestExecution.FailureType.NONE;
            retryable = false;
        } else if ("CANCELLED".equals(rawStatus)) {
            status = ProjectTestExecution.Status.CANCELLED;
            failureType = ProjectTestExecution.FailureType.CANCELLED;
            retryable = false;
        } else if (exitCode != null && exitCode == 0) {
            status = ProjectTestExecution.Status.SUCCEEDED;
            failureType = ProjectTestExecution.FailureType.NONE;
            retryable = false;
        } else if (exitCode != null && exitCode == 124) {
            status = ProjectTestExecution.Status.TIMED_OUT;
            failureType = ProjectTestExecution.FailureType.COMMAND_TIMEOUT;
            retryable = true;
        } else if (exitCode != null && exitCode >= 1 && exitCode <= 5) {
            status = ProjectTestExecution.Status.TEST_FAILED;
            failureType = ProjectTestExecution.FailureType.PYTEST_FAILURE;
            retryable = false;
        } else {
            status = ProjectTestExecution.Status.INFRASTRUCTURE_FAILED;
            failureType = ProjectTestExecution.FailureType.RESULT_UNAVAILABLE;
            retryable = true;
        }
        return new ProjectTestExecution(
                executionId, status, exitCode, collected, passed, coverage,
                failureType, retryable, stdout, stderr
        );
    }

    /**
     * 从 AgentScope MCP 文本包装中提取 Bash 的 output 字段。
     *
     * @param text 原始工具文本
     * @return Bash 输出；非 JSON 替身结果原样返回
     */
    private String unwrapSandboxOutput(String text) {
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root != null && root.path("output").isTextual()) {
                return root.path("output").asText();
            }
        } catch (Exception ignored) {
            // 单元测试替身可直接返回命令输出，不需要 JSON 包装。
        }
        return text;
    }

    /**
     * 合并工具结果中的文本块。
     *
     * @param result 原始 AgentScope 工具结果
     * @return 合并文本
     */
    private static String outputText(ToolResultBlock result) {
        StringBuilder output = new StringBuilder();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                output.append(textBlock.getText());
            }
        }
        return output.toString();
    }

    /**
     * 校验 execution ID 只能是 Java 生成的 UUID 文本。
     *
     * @param executionId 待校验标识
     */
    private static void validateExecutionId(String executionId) {
        if (executionId == null || !executionId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("execution_id 非法");
        }
    }

    /**
     * 构造 execution ID 专属 Docker 状态目录。
     *
     * @param executionId 已校验 UUID
     * @return 固定状态目录
     */
    private static String runDirectory(String executionId) {
        return EXECUTION_ROOT + "/" + executionId;
    }

    /**
     * 解码受限 Base64 输出。
     *
     * @param value Base64 文本
     * @return UTF-8 输出；损坏内容返回空文本
     */
    private static String decodeBase64(String value) {
        try {
            return new String(java.util.Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    /**
     * 对 stdout/stderr 执行二次长度限制和常见凭据脱敏。
     *
     * @param value 原始输出
     * @return 可安全进入工具结果和证据的文本
     */
    private static String sanitize(String value) {
        String limited = value.length() <= OUTPUT_LIMIT ? value : value.substring(value.length() - OUTPUT_LIMIT);
        String assignmentsRemoved = SECRET_ASSIGNMENT_PATTERN.matcher(limited).replaceAll("$1=[REDACTED]");
        return BEARER_PATTERN.matcher(assignmentsRemoved).replaceAll("Bearer [REDACTED]");
    }

    /**
     * 返回正则首个分组。
     *
     * @param pattern 字段模式
     * @param value 待解析文本
     * @param fallback 未匹配默认值
     * @return 分组文本
     */
    private static String match(Pattern pattern, String value, String fallback) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).strip() : fallback;
    }

    /**
     * 提取可空整数分组。
     *
     * @param pattern 字段模式
     * @param value 待解析文本
     * @return 匹配整数或 null
     */
    private static Integer integerMatch(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    /**
     * 提取整数正则分组。
     *
     * @param pattern 正则模式
     * @param value 待解析文本
     * @param fallback 未匹配默认值
     * @return 整数结果
     */
    private static int intMatch(Pattern pattern, String value, int fallback) {
        Integer matched = integerMatch(pattern, value);
        return matched == null ? fallback : matched;
    }

    /**
     * 提取浮点正则分组。
     *
     * @param pattern 正则模式
     * @param value 待解析文本
     * @param fallback 未匹配默认值
     * @return 浮点结果
     */
    private static double doubleMatch(Pattern pattern, String value, double fallback) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : fallback;
    }

    /** 表示无法从 AgentScope 普通错误文本中安全恢复细节的 MCP 传输故障。 */
    public static final class SandboxTransportException extends RuntimeException {

        /**
         * 创建不携带底层响应正文的脱敏传输异常。
         *
         * @param message 固定诊断
         */
        public SandboxTransportException(String message) {
            super(message);
        }
    }

    /** 供受控 Test Runner 请求连接重建的最小 MCP 恢复协议。 */
    interface RecoverableMcpTool {

        /**
         * 重建连接并通过无副作用探针后关闭熔断。
         *
         * @return 恢复成功时为 true
         */
        Mono<Boolean> recoverConnection();
    }
}
