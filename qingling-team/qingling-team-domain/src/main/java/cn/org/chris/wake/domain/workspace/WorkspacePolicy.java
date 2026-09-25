package cn.org.chris.wake.domain.workspace;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 集中执行共享工作区的项目标识、相对路径和角色写权限规则。
 */
public final class WorkspacePolicy {

    /** 允许访问共享项目的四个角色。 */
    public static final Set<String> ROLES = Set.of("manager", "pm", "rd", "qa");

    /** 防止项目标识本身产生目录穿越的白名单。 */
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    /** 评审文件必须显式携带 reviewer 和非空 topic。 */
    private static final Pattern REVIEW_PATTERN = Pattern.compile(
            "^reviews/([a-z_]+)/(manager|pm|rd|qa)_([a-z0-9_]+)\\.md$"
    );

    /** 各交付目录唯一允许写入的 Owner 角色。 */
    private static final Map<String, Set<String>> OWNER_BY_PREFIX = Map.of(
            "needs/", Set.of("manager"),
            "design/", Set.of("pm"),
            "tech/", Set.of("rd"),
            "code/", Set.of("rd"),
            "qa/", Set.of("qa")
    );

    /**
     * 工具类不允许实例化。
     */
    private WorkspacePolicy() {
    }

    /**
     * 校验项目标识可安全作为单个目录名。
     *
     * @param projectId 项目标识
     * @return 已校验项目标识
     */
    public static String validateProjectId(String projectId) {
        if (projectId == null || !PROJECT_ID_PATTERN.matcher(projectId).matches()
                || ".".equals(projectId) || "..".equals(projectId)) {
            throw new IllegalArgumentException("非法 projectId: " + projectId);
        }
        return projectId;
    }

    /**
     * 校验所有角色均可读取的安全相对路径。
     *
     * @param role 审计角色
     * @param relativePath 项目内相对路径
     * @return 规范化且仍为相对路径的 Path
     */
    public static Path validateRead(String role, String relativePath) {
        validateRole(role);
        return validateRelativePath(relativePath);
    }

    /**
     * 校验相对路径安全性、专用文件限制和目录 Owner 权限。
     *
     * @param role 写入角色
     * @param relativePath 项目内相对路径
     * @return 规范化且已授权的 Path
     */
    public static Path validateWrite(String role, String relativePath) {
        validateRole(role);
        Path normalized = validateRelativePath(relativePath);
        String portable = normalized.toString().replace((char) 92, '/');
        if ("events.jsonl".equals(portable) || portable.startsWith("mailboxes/")) {
            throw new SecurityException(portable + " 必须通过专用服务写入");
        }
        if (portable.startsWith("reviews/")) {
            validateReview(role, portable);
            return normalized;
        }
        for (Map.Entry<String, Set<String>> entry : OWNER_BY_PREFIX.entrySet()) {
            if (portable.startsWith(entry.getKey())) {
                if (!entry.getValue().contains(role)) {
                    throw new SecurityException(role + " 无权写入 " + portable);
                }
                return normalized;
            }
        }
        throw new SecurityException("未知共享路径前缀: " + portable);
    }

    /**
     * 校验角色属于团队角色白名单。
     *
     * @param role 待校验角色
     */
    private static void validateRole(String role) {
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("非法角色: " + role);
        }
    }

    /**
     * 拒绝绝对路径、反斜杠、空路径和任何 `..` 路径段。
     *
     * @param relativePath 原始相对路径
     * @return 规范化相对路径
     */
    private static Path validateRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf((char) 92) >= 0) {
            throw new SecurityException("路径必须是非空 POSIX 相对路径");
        }
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            throw new SecurityException("不允许绝对路径: " + relativePath);
        }
        for (Path part : path) {
            if ("..".equals(part.toString())) {
                throw new SecurityException("不允许路径穿越: " + relativePath);
            }
        }
        Path normalized = path.normalize();
        if (normalized.getNameCount() == 0 || normalized.startsWith("..")) {
            throw new SecurityException("非法相对路径: " + relativePath);
        }
        return normalized;
    }

    /**
     * 校验评审文件中的 reviewer 与当前角色完全一致。
     *
     * @param role 当前角色
     * @param relativePath 评审相对路径
     */
    private static void validateReview(String role, String relativePath) {
        Matcher matcher = REVIEW_PATTERN.matcher(relativePath);
        if (!matcher.matches()) {
            throw new SecurityException("评审路径必须匹配 reviews/{stage}/{reviewer}_{topic}.md");
        }
        String reviewer = matcher.group(2);
        if (!reviewer.equals(role)) {
            throw new SecurityException(role + " 不能以 " + reviewer + " 身份写评审文件");
        }
    }
}
