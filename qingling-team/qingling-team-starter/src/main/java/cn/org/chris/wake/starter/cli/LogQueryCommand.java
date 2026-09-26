package cn.org.chris.wake.starter.cli;

import cn.org.chris.wake.app.observability.LogQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 将 Python log_query 子命令和选项转换为 LogQueryService 调用并输出 JSON。
 */
public final class LogQueryCommand {

    /** 日志查询应用服务。 */
    private final LogQueryService service;

    /** CLI JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建日志查询命令。
     *
     * @param service 日志查询服务
     * @param objectMapper JSON 序列化器
     */
    public LogQueryCommand(LogQueryService service, ObjectMapper objectMapper) {
        this.service = Objects.requireNonNull(service, "service 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 解析参数并把两空格缩进 JSON 写入标准输出。
     *
     * @param arguments 不含程序名的 CLI 参数
     * @param standardOutput 标准输出
     * @param errorOutput 错误输出
     * @return 成功为 0，参数或序列化错误为 2
     */
    public int execute(String[] arguments, PrintStream standardOutput, PrintStream errorOutput) {
        Objects.requireNonNull(arguments, "arguments 不能为空");
        Objects.requireNonNull(standardOutput, "standardOutput 不能为空");
        Objects.requireNonNull(errorOutput, "errorOutput 不能为空");
        try {
            Map<String, String> options = options(arguments);
            String command = requireCommand(arguments);
            Path logsRoot = Path.of(options.getOrDefault("logs-root", "workspace"));
            int days = Integer.parseInt(options.getOrDefault("days", "7"));
            Object result = switch (command) {
                case "stats" -> service.stats(logsRoot, required(options, "agent-id"), days);
                case "tasks" -> service.tasks(
                        logsRoot,
                        required(options, "agent-id"),
                        days,
                        options.get("sort"),
                        options.containsKey("limit") ? Integer.valueOf(options.get("limit")) : null
                );
                case "l1" -> service.l1(logsRoot, days, options.get("keyword"));
                case "all-agents" -> service.allAgents(logsRoot, days);
                case "steps" -> service.steps(
                        Path.of(required(options, "session-raw")),
                        required(options, "task-id"),
                        options.containsKey("only-failed")
                );
                default -> throw new IllegalArgumentException("未知子命令: " + command);
            };
            standardOutput.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            return 0;
        } catch (IllegalArgumentException | IOException failure) {
            errorOutput.println(failure.getMessage());
            return 2;
        }
    }

    /** 提取首个子命令并拒绝空参数。 */
    private static String requireCommand(String[] arguments) {
        if (arguments.length == 0 || arguments[0].startsWith("--")) {
            throw new IllegalArgumentException("缺少子命令");
        }
        return arguments[0];
    }

    /** 解析 GNU 风格选项，only-failed 作为无值开关。 */
    private static Map<String, String> options(String[] arguments) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int index = 1; index < arguments.length; index++) {
            String token = arguments[index];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("无法识别参数: " + token);
            }
            String name = token.substring(2);
            if ("only-failed".equals(name)) {
                result.put(name, "true");
                continue;
            }
            if (index + 1 >= arguments.length || arguments[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("选项缺少值: " + token);
            }
            result.put(name, arguments[++index]);
        }
        return result;
    }

    /** 返回必填选项值。 */
    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填选项: --" + name);
        }
        return value;
    }
}
