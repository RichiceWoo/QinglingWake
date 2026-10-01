package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.workspace.WorkspacePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 以结构化 projectId 包装原始沙盒 Shell，并固定唯一容器代码目录与 pytest 命令。
 */
public final class ProjectTestAgentTool implements AgentTool {

    /** 对外暴露的结构化测试工具名称。 */
    public static final String TOOL_NAME = "run_project_tests";

    /** 底层 AIO-Sandbox MCP Bash 工具名称。 */
    private static final String SANDBOX_TOOL_NAME = "sandbox_execute_bash";

    /** Shell 输出中的真实退出码标记。 */
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("__QINGLING_EXIT_CODE__=(\\d+)");

    /** pytest 输出中的 collected 数量。 */
    private static final Pattern COLLECTED_PATTERN = Pattern.compile("collected\\s+(\\d+)\\s+items?");

    /** pytest 简洁摘要中的通过数量。 */
    private static final Pattern PASSED_PATTERN = Pattern.compile("(\\d+)\\s+passed");

    /** pytest-cov TOTAL 行中的覆盖率。 */
    private static final Pattern COVERAGE_PATTERN = Pattern.compile("(?m)^TOTAL\\s+.*?\\s+(\\d+(?:\\.\\d+)?)%\\s*$");

    /** 已注册原始 MCP 工具的父 Toolkit。 */
    private final Toolkit toolkit;

    /** 结构化结果编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建固定目录的项目测试包装器。
     *
     * @param toolkit 已包含 sandbox_execute_bash 的父 Toolkit
     * @param objectMapper JSON 编解码器
     */
    public ProjectTestAgentTool(Toolkit toolkit, ObjectMapper objectMapper) {
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        if (!toolkit.getToolNames().contains(SANDBOX_TOOL_NAME)) {
            throw new IllegalStateException("注册 run_project_tests 前缺少 sandbox_execute_bash");
        }
    }

    /** 返回结构化工具名。 */
    @Override
    public String getName() {
        return TOOL_NAME;
    }

    /** 返回强调固定目录与真实 pytest 的工具说明。 */
    @Override
    public String getDescription() {
        return "在固定 /workspace/shared/projects/{projectId}/code 中执行真实 pytest 与覆盖率，返回结构化证据。";
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
     * 校验 projectId 后调用原始 MCP 工具，并把文本输出归一为证据 JSON。
     *
     * @param param AgentScope 工具调用上下文
     * @return 结构化测试结果
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            String projectId = WorkspacePolicy.validateProjectId(text(param.getInput().get("project_id")));
            String command = command(projectId);
            ToolUseBlock toolUse = new ToolUseBlock(
                    "project-tests-" + UUID.randomUUID(),
                    SANDBOX_TOOL_NAME,
                    Map.of("cmd", command, "timeout", 300),
                    "{\"cmd\":\"fixed-project-test-command\",\"timeout\":300}",
                    Map.of()
            );
            ToolCallParam.Builder delegatedBuilder = ToolCallParam.builder()
                    .toolUseBlock(toolUse)
                    .input(toolUse.getInput());
            if (param.getAgent() != null) {
                delegatedBuilder.agent(param.getAgent());
            }
            if (param.getRuntimeContext() != null) {
                delegatedBuilder.runtimeContext(param.getRuntimeContext());
            }
            if (param.getContext() != null) {
                delegatedBuilder.context(param.getContext());
            }
            if (param.getEmitter() != null) {
                delegatedBuilder.emitter(param.getEmitter());
            }
            ToolCallParam delegated = delegatedBuilder.build();
            return toolkit.callTool(delegated)
                    .map(result -> structuredResult(projectId, result))
                    .onErrorResume(exception -> Mono.just(ToolResultBlock.error(
                            "run_project_tests 调用沙盒失败（" + exception.getClass().getSimpleName() + "）"
                    )));
        } catch (RuntimeException exception) {
            return Mono.just(ToolResultBlock.error(exception.getMessage()));
        }
    }

    /**
     * 生成不接受外部 cwd/command 的固定测试命令，并用标记保留真实退出码。
     *
     * @param projectId 已验证项目标识
     * @return 只在唯一共享代码目录运行的命令
     */
    private static String command(String projectId) {
        String directory = "/workspace/shared/projects/" + projectId + "/code";
        return "set +e; cd '" + directory + "' && test -d . && "
                + "python -m pytest --disable-warnings --cov=. --cov-report=term-missing; "
                + "rc=$?; printf '\\n__QINGLING_EXIT_CODE__=%s\\n' \"$rc\"; exit 0";
    }

    /**
     * 从 MCP 文本中提取退出码、pytest 数量和覆盖率，并保留原始输出供审计。
     *
     * @param projectId 项目标识
     * @param result 原始 MCP 结果
     * @return 结构化 AgentScope 工具结果
     */
    private ToolResultBlock structuredResult(String projectId, ToolResultBlock result) {
        String output = outputText(result);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("project_id", projectId);
        evidence.put("working_directory", "/workspace/shared/projects/" + projectId + "/code");
        evidence.put("exit_code", intMatch(EXIT_CODE_PATTERN, output, -1));
        int passed = intMatch(PASSED_PATTERN, output, 0);
        evidence.put("pytest_passed", passed);
        evidence.put("pytest_collected", intMatch(COLLECTED_PATTERN, output, passed));
        evidence.put("coverage_percent", doubleMatch(COVERAGE_PATTERN, output, -1D));
        evidence.put("raw_output", output);
        try {
            return ToolResultBlock.text(objectMapper.writeValueAsString(evidence));
        } catch (JsonProcessingException exception) {
            return ToolResultBlock.error("run_project_tests 结果序列化失败");
        }
    }

    /**
     * 合并原始工具结果中的文本块。
     *
     * @param result 原始工具结果
     * @return 可解析文本
     */
    private static String outputText(ToolResultBlock result) {
        StringBuilder output = new StringBuilder();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                output.append(textBlock.getText());
            } else {
                output.append(block);
            }
        }
        return output.toString();
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
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
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
}
