package cn.org.chris.wake.domain.workflow;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 保存已处理 command 的稳定结果引用，用于重复请求返回同一业务结果。
 *
 * @param commandId 稳定命令标识
 * @param outcomeCode 有限结果代码
 * @param resultReference 有界结果引用
 * @param processedAt 首次处理完成时间
 */
public record ProcessedWorkflowCommand(
        /** 稳定命令标识。 */ WorkflowCommandId commandId,
        /** 不包含自由文本或凭据的结果代码。 */ String outcomeCode,
        /** 可用于重建响应的有界结果引用。 */ String resultReference,
        /** 首次处理完成时间。 */ Instant processedAt
) {

    /** 结果引用字符数上限。 */
    private static final int MAX_RESULT_REFERENCE_LENGTH = 512;

    /** 禁止被 command 结果引用持久化的常见凭据模式。 */
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i).*(bearer\\s+\\S+|\\bsk-[a-z0-9_-]{8,}|authorization\\s*[:=]).*"
    );

    /**
     * 校验命令结果只包含有界引用而非请求正文。
     */
    public ProcessedWorkflowCommand {
        Objects.requireNonNull(commandId, "commandId 不能为空");
        Objects.requireNonNull(processedAt, "processedAt 不能为空");
        outcomeCode = requireText(outcomeCode, "outcomeCode");
        resultReference = requireText(resultReference, "resultReference");
        if (resultReference.length() > MAX_RESULT_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("resultReference 超过长度上限");
        }
        if (SENSITIVE_VALUE.matcher(outcomeCode).matches()
                || SENSITIVE_VALUE.matcher(resultReference).matches()) {
            throw new IllegalArgumentException("command 结果引用禁止包含凭据");
        }
    }

    /**
     * 要求字符串非空并去除边界空白。
     *
     * @param value 待校验值
     * @param field 字段名称
     * @return 归一化非空字符串
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
