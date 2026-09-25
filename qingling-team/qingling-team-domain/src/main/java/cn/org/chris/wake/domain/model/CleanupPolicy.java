package cn.org.chris.wake.domain.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 描述相对 data root 的文件保留规则。
 *
 * @param rules 目录 glob 到保留天数的映射
 * @param sessionJsonlRetentionDays sessions/*.jsonl 保留天数
 */
public record CleanupPolicy(
        /** 目录 glob 与保留天数。 */ Map<String, Integer> rules,
        /** 会话 JSONL 文件保留天数。 */ int sessionJsonlRetentionDays
) {
    /**
     * 复制规则并拒绝绝对路径、父目录逃逸和非法保留期。
     */
    public CleanupPolicy {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不能为空");
        }
        Map<String, Integer> checked = new LinkedHashMap<>();
        rules.forEach((pattern, days) -> {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("清理 pattern 不能为空");
            }
            Path path = Path.of(pattern);
            if (path.isAbsolute() || path.normalize().startsWith("..")) {
                throw new IllegalArgumentException("清理 pattern 不得逃逸 data root: " + pattern);
            }
            if (days == null || days < 0) {
                throw new IllegalArgumentException("保留天数不得为负数: " + pattern);
            }
            checked.put(pattern, days);
        });
        if (sessionJsonlRetentionDays < 0) {
            throw new IllegalArgumentException("sessionJsonlRetentionDays 不得为负数");
        }
        rules = Collections.unmodifiableMap(checked);
    }

    /**
     * 返回与 Python CleanupPolicy 一致的默认规则。
     *
     * @return 默认清理策略
     */
    public static CleanupPolicy defaults() {
        Map<String, Integer> rules = new LinkedHashMap<>();
        rules.put("workspace/sessions/*/tmp", 1);
        rules.put("workspace/sessions/*/uploads", 7);
        rules.put("workspace/sessions/*/outputs", 30);
        rules.put("traces", 30);
        return new CleanupPolicy(rules, 365);
    }
}
