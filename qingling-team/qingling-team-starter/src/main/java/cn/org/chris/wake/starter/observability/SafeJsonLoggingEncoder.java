package cn.org.chris.wake.starter.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 输出单行 JSON，并对消息敏感值和 MDC 字段实施白名单保护。
 */
public final class SafeJsonLoggingEncoder extends EncoderBase<ILoggingEvent> {

    /** 可安全进入结构化日志的路由追踪字段。 */
    private static final List<String> SAFE_MDC_KEYS = List.of("routing_key", "session_id", "feishu_msg_id");

    /** 匹配常见 key=value、key:value 和 JSON 凭据片段。 */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|app[_-]?secret|access[_-]?token|token|password)"
                    + "(\\s*[=:]\\s*|\\\"\\s*:\\s*\\\")[^\\s,;\\\"}]+"
    );

    /** 无日期时区副作用的 JSON 序列化器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将单条日志事件编码为 UTF-8 JSONL。
     *
     * @param event Logback 日志事件
     * @return 带换行符的 UTF-8 字节
     */
    @Override
    public byte[] encode(ILoggingEvent event) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        payload.put("level", event.getLevel().toString());
        payload.put("logger", event.getLoggerName());
        payload.put("msg", sanitize(event.getFormattedMessage()));
        Map<String, String> mdc = event.getMDCPropertyMap();
        SAFE_MDC_KEYS.stream().filter(mdc::containsKey).forEach(key -> payload.put(key, mdc.get(key)));
        if (event.getThrowableProxy() != null) {
            payload.put("error_type", event.getThrowableProxy().getClassName());
        }
        try {
            return (objectMapper.writeValueAsString(payload) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException failure) {
            addError("结构化日志序列化失败", failure);
            return "{\"level\":\"ERROR\",\"msg\":\"logging serialization failed\"}\n"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 将常见凭据值替换为固定掩码。
     *
     * @param message 原始格式化消息
     * @return 不含凭据原值的消息
     */
    public static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        return SECRET_PATTERN.matcher(message).replaceAll(match -> {
            String token = match.group();
            int separator = Math.max(token.indexOf('='), token.indexOf(':'));
            if (separator < 0) {
                return "***";
            }
            return token.substring(0, separator + 1) + "***";
        });
    }

    /** Logback 关闭 Encoder 时无需写尾部内容。 */
    @Override
    public byte[] footerBytes() {
        return null;
    }

    /** Logback 启动 Encoder 时无需写头部内容。 */
    @Override
    public byte[] headerBytes() {
        return null;
    }
}
