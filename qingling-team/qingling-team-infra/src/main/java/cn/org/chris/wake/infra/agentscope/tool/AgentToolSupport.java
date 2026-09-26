package cn.org.chris.wake.infra.agentscope.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 集中维护 AgentScope 工具的 Python 兼容 JSON 响应和项目标识校验。
 */
final class AgentToolSupport {

    /** 与 Python team_tools.py 完全一致的项目标识规则。 */
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    /**
     * 工具辅助类不允许实例化。
     */
    private AgentToolSupport() {
    }

    /**
     * 校验项目标识符合跨语言工具契约。
     *
     * @param projectId 项目标识
     * @return 原项目标识
     */
    static String requireProjectId(String projectId) {
        if (projectId == null || !PROJECT_ID_PATTERN.matcher(projectId).matches()) {
            throw new IllegalArgumentException("invalid project_id: " + String.valueOf(projectId));
        }
        return projectId;
    }

    /**
     * 校验必填文本不为空。
     *
     * @param value 原始文本
     * @param field 参数名称
     * @return 原文本
     */
    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 构造带成功码且保持字段顺序的工具响应。
     *
     * @param values 业务字段
     * @return 含 errcode=0 的可变响应 Map
     */
    static Map<String, Object> successFields(Map<String, ?> values) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("errcode", 0);
        response.putAll(values);
        return response;
    }

    /**
     * 序列化成功响应为 UTF-8 JSON 文本。
     *
     * @param objectMapper JSON 编解码器
     * @param values 业务字段
     * @return Python 兼容 JSON
     */
    static String success(ObjectMapper objectMapper, Map<String, ?> values) {
        return json(objectMapper, successFields(values));
    }

    /**
     * 将异常转成不包含堆栈的 Python 兼容错误 JSON。
     *
     * @param objectMapper JSON 编解码器
     * @param exception 业务异常
     * @return 含 errcode=1 和 errmsg 的 JSON
     */
    static String error(ObjectMapper objectMapper, RuntimeException exception) {
        String message = exception.getMessage();
        return error(objectMapper, message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message);
    }

    /**
     * 构造 Python 兼容错误 JSON。
     *
     * @param objectMapper JSON 编解码器
     * @param message 可供 Agent 修正调用的错误信息
     * @return 含 errcode=1 和 errmsg 的 JSON
     */
    static String error(ObjectMapper objectMapper, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("errcode", 1);
        response.put("errmsg", Objects.requireNonNullElse(message, "unknown error"));
        return json(objectMapper, response);
    }

    /**
     * 将响应对象转换为 JSON，序列化配置错误属于不可恢复的启动缺陷。
     *
     * @param objectMapper JSON 编解码器
     * @param value 响应对象
     * @return JSON 文本
     */
    static String json(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 AgentScope 工具响应失败", exception);
        }
    }
}
