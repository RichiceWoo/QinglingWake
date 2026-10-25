package cn.org.chris.wake.domain.workflow;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 保存一项可幂等重放且不包含凭据的旧存储投影动作。
 *
 * @param actionId 稳定动作标识
 * @param type 有限投影类型
 * @param target 有界目标标识
 * @param attributes 有界且不含敏感字段的投影参数
 * @param status 当前投影状态
 * @param attempt 投影尝试次数
 * @param createdAt 创建时间
 * @param updatedAt 最近状态更新时间
 * @param appliedAt 成功投影时间
 * @param lastFailureType 最近一次脱敏失败类型
 */
public record OutboxAction(
        /** 稳定动作标识。 */ OutboxActionId actionId,
        /** 有限投影类型。 */ OutboxActionType type,
        /** 有界投影目标标识。 */ String target,
        /** 有界且不含秘密的投影参数。 */ Map<String, String> attributes,
        /** 当前投影状态。 */ OutboxActionStatus status,
        /** 已执行的投影尝试次数。 */ int attempt,
        /** 动作创建时间。 */ Instant createdAt,
        /** 最近状态更新时间。 */ Instant updatedAt,
        /** 成功投影时间，未完成时为空。 */ Instant appliedAt,
        /** 最近一次脱敏失败类型。 */ String lastFailureType
) {

    /** 属性总字符数上限，避免 outbox 保存完整模型输出。 */
    private static final int MAX_ATTRIBUTE_CHARACTERS = 8_192;

    /** 单个属性值字符数上限。 */
    private static final int MAX_ATTRIBUTE_VALUE_CHARACTERS = 4_096;

    /** 属性数量上限。 */
    private static final int MAX_ATTRIBUTES = 24;

    /** 禁止落盘的字段名模式。 */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(authorization|api[-_]?key|mcp.*header|credential|secret|token|prompt|model[-_]?output|stdout|stderr).*"
    );

    /** 禁止落盘的常见凭据值模式。 */
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i).*(bearer\\s+\\S+|\\bsk-[a-z0-9_-]{8,}).*"
    );

    /**
     * 归一化参数并校验状态、时间和敏感数据边界。
     */
    public OutboxAction {
        Objects.requireNonNull(actionId, "actionId 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
        target = requireText(target, "target");
        attributes = safeAttributes(attributes);
        lastFailureType = lastFailureType == null ? "" : lastFailureType.trim();
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不能为负数");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
        }
        if (status == OutboxActionStatus.PENDING && appliedAt != null) {
            throw new IllegalArgumentException("PENDING action 不能包含 appliedAt");
        }
        if (status == OutboxActionStatus.APPLIED
                && (appliedAt == null || appliedAt.isBefore(createdAt) || appliedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException("APPLIED action 必须包含单调 appliedAt");
        }
    }

    /**
     * 创建一项等待投影的动作。
     *
     * @param actionId 稳定动作标识
     * @param type 投影类型
     * @param target 投影目标
     * @param attributes 有界参数
     * @param now 创建时间
     * @return PENDING 动作
     */
    public static OutboxAction pending(
            OutboxActionId actionId,
            OutboxActionType type,
            String target,
            Map<String, String> attributes,
            Instant now
    ) {
        return new OutboxAction(
                actionId, type, target, attributes, OutboxActionStatus.PENDING,
                0, now, now, null, ""
        );
    }

    /**
     * 记录一次脱敏失败并保持动作可重放。
     *
     * @param failureType 脱敏失败类型
     * @param now 失败时间
     * @return 增加尝试次数的 PENDING 动作
     */
    public OutboxAction failed(String failureType, Instant now) {
        if (status == OutboxActionStatus.APPLIED) {
            throw new IllegalStateException("APPLIED action 不能重新失败");
        }
        String requiredFailure = requireText(failureType, "failureType");
        Instant requiredNow = requireMonotonic(now);
        return new OutboxAction(
                actionId, type, target, attributes, status, attempt + 1,
                createdAt, requiredNow, null, requiredFailure
        );
    }

    /**
     * 将成功投影持久化为终态；重复确认保持幂等。
     *
     * @param now 投影成功时间
     * @return APPLIED 动作
     */
    public OutboxAction applied(Instant now) {
        if (status == OutboxActionStatus.APPLIED) {
            return this;
        }
        Instant requiredNow = requireMonotonic(now);
        return new OutboxAction(
                actionId, type, target, attributes, OutboxActionStatus.APPLIED,
                attempt + 1, createdAt, requiredNow, requiredNow, lastFailureType
        );
    }

    /**
     * 校验时间非空且不早于当前更新时间。
     *
     * @param now 新状态时间
     * @return 单调时间
     */
    private Instant requireMonotonic(Instant now) {
        Instant requiredNow = Objects.requireNonNull(now, "操作时间不能为空");
        if (requiredNow.isBefore(updatedAt)) {
            throw new IllegalArgumentException("outbox action 时间不能回退");
        }
        return requiredNow;
    }

    /**
     * 归一化并冻结安全属性，拒绝 prompt、输出和凭据形态。
     *
     * @param source 原始属性
     * @return 不可变有序安全属性
     */
    private static Map<String, String> safeAttributes(Map<String, String> source) {
        Objects.requireNonNull(source, "attributes 不能为空");
        if (source.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("outbox attributes 数量超过上限");
        }
        TreeMap<String, String> normalized = new TreeMap<>();
        int totalCharacters = 0;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = requireText(entry.getKey(), "attribute key");
            String value = Objects.requireNonNull(entry.getValue(), "attribute value 不能为空").trim();
            if (SENSITIVE_KEY.matcher(key).matches() || SENSITIVE_VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("outbox attributes 禁止包含敏感字段或凭据");
            }
            if (value.length() > MAX_ATTRIBUTE_VALUE_CHARACTERS) {
                throw new IllegalArgumentException("outbox attribute value 超过上限");
            }
            totalCharacters += key.length() + value.length();
            normalized.put(key, value);
        }
        if (totalCharacters > MAX_ATTRIBUTE_CHARACTERS) {
            throw new IllegalArgumentException("outbox attributes 总长度超过上限");
        }
        return Collections.unmodifiableMap(normalized);
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
