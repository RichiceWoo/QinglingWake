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
import java.util.Set;
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

    /** 单份 stdout/stderr 摘录允许返回的最大字节数，避免 MCP 响应截断终态元数据。 */
    private static final int OUTPUT_LIMIT = 1_024;

    /** 超长输出首尾各保留的字节数。 */
    private static final int OUTPUT_EDGE_LIMIT = 480;

    /** pytest 收集预检硬超时秒数，避免导入挂起占满正式执行窗口。 */
    private static final int COLLECTION_TIMEOUT_SECONDS = 30;

    /** pytest 正式执行硬超时秒数，预检加执行不超过工具五分钟终态窗口。 */
    private static final int PROCESS_TIMEOUT_SECONDS = 240;

    /** 查询命令的 MCP 等待上限秒数。 */
    private static final int QUERY_TIMEOUT_SECONDS = 15;

    /** runner 退出后等待原子退出码落盘的最大秒数，避免短暂收尾窗口被误判为孤儿进程。 */
    private static final int FINALIZATION_GRACE_SECONDS = 5;

    /** 查询输出中的状态字段。 */
    private static final Pattern STATUS_PATTERN = Pattern.compile("__QINGLING_STATUS__=([^\\r\\n]+)");

    /** 查询输出中的退出码字段。 */
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("__QINGLING_EXIT_CODE__=(-?\\d+)");

    /** 查询输出中的 pytest 收集数量字段。 */
    private static final Pattern PYTEST_COLLECTED_PATTERN = Pattern.compile("__QINGLING_PYTEST_COLLECTED__=(\\d+)");

    /** 查询输出中的 pytest 通过数量字段。 */
    private static final Pattern PYTEST_PASSED_PATTERN = Pattern.compile("__QINGLING_PYTEST_PASSED__=(\\d+)");

    /** 查询输出中的覆盖率字段。 */
    private static final Pattern COVERAGE_PERCENT_PATTERN = Pattern.compile(
            "__QINGLING_COVERAGE_PERCENT__=(-?\\d+(?:\\.\\d+)?)"
    );

    /** 查询输出中的 Base64 stdout 字段。 */
    private static final Pattern STDOUT_PATTERN = Pattern.compile("__QINGLING_STDOUT_B64__=([^\\r\\n]*)");

    /** 查询输出中的 Base64 stderr 字段。 */
    private static final Pattern STDERR_PATTERN = Pattern.compile("__QINGLING_STDERR_B64__=([^\\r\\n]*)");

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

    /** 决定固定 pytest 收集范围的团队角色。 */
    private final String role;

    /**
     * 创建受控 Docker Test Runner。
     *
     * @param toolkit 已包含 sandbox_execute_bash 的父 Toolkit
     * @param objectMapper JSON 编解码器
     */
    public McpProjectTestRunner(Toolkit toolkit, ObjectMapper objectMapper) {
        this(toolkit, objectMapper, "rd");
    }

    /**
     * 创建按 RD 或 QA 固定测试范围运行的受控 Docker Test Runner。
     *
     * @param toolkit 已包含 sandbox_execute_bash 的父 Toolkit
     * @param objectMapper JSON 编解码器
     * @param role rd 执行代码回归，qa 执行独立契约测试
     */
    public McpProjectTestRunner(Toolkit toolkit, ObjectMapper objectMapper, String role) {
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.role = Objects.requireNonNull(role, "role 不能为空");
        if (!Set.of("rd", "qa").contains(role)) {
            throw new IllegalArgumentException("Test Runner 角色只允许 rd 或 qa");
        }
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
        String targetSetup = "qa".equals(role)
                ? "test_targets='../qa/contract_tests/test_contract.py'; "
                : "test_targets='.'; if test -f '../qa/contract_tests/test_contract.py'; then "
                        + "test_targets='. ../qa/contract_tests/test_contract.py'; fi; ";
        String command = "set -eu; mkdir -p '" + EXECUTION_ROOT + "'; "
                + "if mkdir '" + runDirectory + "' 2>/dev/null; then "
                + "printf '%s' '" + projectId + "' > '" + runDirectory + "/project_id'; "
                + "printf RUNNING > '" + runDirectory + "/status'; "
                + ": > '" + runDirectory + "/stdout'; : > '" + runDirectory + "/stderr'; "
                + "setsid sh -c \"set +e; cd '" + projectDirectory + "'; "
                + targetSetup
                + "timeout --signal=TERM --kill-after=5s " + COLLECTION_TIMEOUT_SECONDS + "s "
                + "python -m pytest --collect-only -q --disable-warnings --import-mode=importlib "
                + "\\$test_targets >'" + runDirectory + "/collect_stdout' "
                + "2>'" + runDirectory + "/collect_stderr'; collect_rc=\\$?; "
                + "if test \\$collect_rc -ne 0; then "
                + "cp '" + runDirectory + "/collect_stdout' '" + runDirectory + "/stdout'; "
                + "cp '" + runDirectory + "/collect_stderr' '" + runDirectory + "/stderr'; "
                + "rc=\\$collect_rc; else "
                + "timeout --signal=TERM --kill-after=5s " + PROCESS_TIMEOUT_SECONDS + "s "
                + "python -m pytest --disable-warnings --import-mode=importlib "
                + "--cov=. --cov-report=term-missing --verbose \\$test_targets "
                + ">'" + runDirectory + "/stdout' 2>'" + runDirectory + "/stderr'; rc=\\$?; fi; "
                + "printf '%s' \\\"\\$rc\\\" > '" + runDirectory + "/exit_code.tmp'; "
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
        String command = "set +e; run='" + runDirectory + "'; "
                + "if test ! -d \"$run\"; then status=MISSING; "
                + "elif test -f \"$run/status\"; then status=$(cat \"$run/status\"); "
                + "else status=MISSING; fi; "
                + "exit_code=''; if test -f \"$run/exit_code\"; then exit_code=$(cat \"$run/exit_code\"); fi; "
                + "if test -n \"$exit_code\" && test \"$status\" != CANCELLED; then status=FINISHED; "
                + "elif test \"$status\" = FINISHED; then status=FINALIZING; "
                + "elif test \"$status\" = RUNNING && test -f \"$run/runner_pid\"; then "
                + "pid=$(cat \"$run/runner_pid\"); if ! kill -0 \"$pid\" 2>/dev/null; then "
                + "now=$(date +%s); if test ! -f \"$run/finalizing_since\"; then "
                + "printf '%s' \"$now\" > \"$run/finalizing_since.tmp.$$\"; "
                + "mv \"$run/finalizing_since.tmp.$$\" \"$run/finalizing_since\"; status=FINALIZING; "
                + "else since=$(cat \"$run/finalizing_since\" 2>/dev/null); "
                + "case \"$since\" in ''|*[!0-9]*) status=FINALIZING ;; "
                + "*) if test $((now - since)) -lt " + FINALIZATION_GRACE_SECONDS + "; "
                + "then status=FINALIZING; else status=ORPHANED; fi ;; esac; fi; fi; fi; "
                + "printf '__QINGLING_STATUS__=%s\\n' \"$status\"; "
                + "if test -n \"$exit_code\"; then printf '__QINGLING_EXIT_CODE__=%s\\n' \"$exit_code\"; fi; "
                + "collected=$(grep -Eo 'collected [0-9]+ items?|[0-9]+ tests? collected' "
                + "\"$run/stdout\" 2>/dev/null | tail -n 1 | grep -Eo '[0-9]+' | head -n 1); "
                + "passed=$(grep -Eo '[0-9]+ passed' \"$run/stdout\" 2>/dev/null "
                + "| tail -n 1 | cut -d ' ' -f 1); "
                + "coverage=$(grep -E '^TOTAL[[:space:]]' \"$run/stdout\" 2>/dev/null "
                + "| tail -n 1 | grep -Eo '[0-9]+(\\.[0-9]+)?%' | tail -n 1 | tr -d '%'); "
                + "printf '__QINGLING_PYTEST_COLLECTED__=%s\\n' \"${collected:-0}\"; "
                + "printf '__QINGLING_PYTEST_PASSED__=%s\\n' \"${passed:-0}\"; "
                + "printf '__QINGLING_COVERAGE_PERCENT__=%s\\n' \"${coverage:--1}\"";
        return callSandbox("query-metadata-" + executionId, command, QUERY_TIMEOUT_SECONDS)
                .map(output -> parseExecutionMetadata(executionId, output))
                .flatMap(metadata -> metadata.status() == ProjectTestExecution.Status.SUCCEEDED
                                || metadata.status() == ProjectTestExecution.Status.TEST_FAILED
                                || metadata.status() == ProjectTestExecution.Status.TIMED_OUT
                                || metadata.status() == ProjectTestExecution.Status.CANCELLED
                        ? attachOutput(executionId, metadata)
                        : Mono.just(metadata));
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
     * <p>执行状态通过受控目录文件协调，不依赖 tmux 会话状态；生产包装层会把这些短命令与
     * 其他角色的 Bash 调用统一纳入跨角色单飞，避免共享 OpenHands PS1 协议被并发写入。</p>
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
     * 解析小型终态元数据，并严格区分业务失败和基础设施失败。
     *
     * @param executionId 稳定执行标识
     * @param output 查询命令输出
     * @return 结构化执行结果
     */
    private ProjectTestExecution parseExecutionMetadata(String executionId, String output) {
        String rawStatus = match(STATUS_PATTERN, output, "MISSING");
        Integer exitCode = integerMatch(EXIT_CODE_PATTERN, output);
        int collected = intMatch(PYTEST_COLLECTED_PATTERN, output, 0);
        int passed = intMatch(PYTEST_PASSED_PATTERN, output, 0);
        double coverage = doubleMatch(COVERAGE_PERCENT_PATTERN, output, -1D);
        ProjectTestExecution.Status status;
        ProjectTestExecution.FailureType failureType;
        boolean retryable;
        if ("RUNNING".equals(rawStatus) || "FINALIZING".equals(rawStatus)) {
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
                failureType, retryable, "", ""
        );
    }

    /**
     * 在终态元数据已经可靠取得后，再分别读取 stdout/stderr 的受限首尾摘录。
     *
     * <p>输出查询失败不会覆盖已经持久化的 pytest 终态，避免把 exit_code=1 的业务失败
     * 再次误判为 MCP 基础设施故障。</p>
     *
     * @param executionId 稳定执行标识
     * @param metadata 已解析的终态元数据
     * @return 携带受限输出的同一终态；摘录不可用时保留空输出
     */
    private Mono<ProjectTestExecution> attachOutput(
            String executionId,
            ProjectTestExecution metadata
    ) {
        return readOutputExcerpt(executionId, "stdout", STDOUT_PATTERN)
                .flatMap(stdout -> readOutputExcerpt(executionId, "stderr", STDERR_PATTERN)
                        .map(stderr -> withOutput(metadata, stdout, stderr)))
                .onErrorReturn(metadata);
    }

    /**
     * 读取单个输出文件的受限首尾摘录，确保任一 MCP 响应都不会携带大段 pytest 正文。
     *
     * @param executionId 稳定执行标识
     * @param stream 仅允许 stdout 或 stderr
     * @param outputPattern 对应输出标记的解析模式
     * @return 解码并脱敏后的摘录
     */
    private Mono<String> readOutputExcerpt(
            String executionId,
            String stream,
            Pattern outputPattern
    ) {
        if (!Set.of("stdout", "stderr").contains(stream)) {
            return Mono.error(new IllegalArgumentException("Test Runner 输出类型非法"));
        }
        String file = runDirectory(executionId) + "/" + stream;
        String marker = "stdout".equals(stream)
                ? "__QINGLING_STDOUT_B64__="
                : "__QINGLING_STDERR_B64__=";
        String command = "set +e; file='" + file + "'; printf '" + marker + "'; "
                + "if test -f \"$file\"; then size=$(wc -c < \"$file\" | tr -d ' '); "
                + "if test \"${size:-0}\" -le " + OUTPUT_LIMIT + "; then cat \"$file\"; "
                + "else head -c " + OUTPUT_EDGE_LIMIT + " \"$file\"; "
                + "printf '\\n... [output truncated] ...\\n'; "
                + "tail -c " + OUTPUT_EDGE_LIMIT + " \"$file\"; fi; fi | base64 | tr -d '\\n'; "
                + "printf '\\n'";
        return callSandbox("query-" + stream + "-" + executionId, command, QUERY_TIMEOUT_SECONDS)
                .map(output -> sanitize(decodeBase64(match(outputPattern, output, ""))));
    }

    /**
     * 把受限输出附加到已经分类的终态，不允许输出内容反向改变状态或退出码。
     *
     * @param metadata 已可靠解析的终态元数据
     * @param stdout 标准输出摘录
     * @param stderr 标准错误摘录
     * @return 完整结构化终态
     */
    private static ProjectTestExecution withOutput(
            ProjectTestExecution metadata,
            String stdout,
            String stderr
    ) {
        return new ProjectTestExecution(
                metadata.executionId(),
                metadata.status(),
                metadata.exitCode(),
                metadata.pytestCollected(),
                metadata.pytestPassed(),
                metadata.coveragePercent(),
                metadata.failureType(),
                metadata.retryable(),
                stdout,
                stderr
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
