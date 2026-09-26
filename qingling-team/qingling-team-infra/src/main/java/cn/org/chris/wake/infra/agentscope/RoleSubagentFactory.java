package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.mailbox.MailboxService;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 从外部角色 workspace 安全加载声明式 Sub-Agent。
 */
public final class RoleSubagentFactory {

    /** 规范化后的团队 workspace 根目录。 */
    private final Path workspaceRoot;

    /**
     * 创建角色 Sub-Agent 工厂。
     *
     * @param workspaceRoot 已完成模板初始化的外部 workspace 根目录
     */
    public RoleSubagentFactory(Path workspaceRoot) {
        Path normalizedRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空")
                .toAbsolutePath().normalize();
        requireDirectory(normalizedRoot, "团队 workspace 根目录不存在");
        this.workspaceRoot = realPath(normalizedRoot);
    }

    /**
     * 加载指定角色 subagents 目录中的全部声明，并拒绝重复名称。
     *
     * @param role manager、pm、rd 或 qa
     * @return 不可变的声明列表
     */
    public List<SubagentDeclaration> create(String role) {
        requireRole(role);
        Path roleRoot = resolveRoleRoot(role);
        Path subagentsRoot = roleRoot.resolve("subagents").normalize();
        requireDirectory(subagentsRoot, "角色 Sub-Agent 目录不存在");
        rejectSymbolicLinks(workspaceRoot, subagentsRoot);
        List<SubagentDeclaration> declarations = List.copyOf(
                AgentSpecLoader.loadFromDirectory(subagentsRoot, roleRoot)
        );
        validateUniqueNames(role, declarations);
        return declarations;
    }

    /**
     * 返回经过边界和符号链接校验的角色 workspace。
     *
     * @param role manager、pm、rd 或 qa
     * @return 角色 workspace 绝对路径
     */
    public Path roleWorkspace(String role) {
        requireRole(role);
        return resolveRoleRoot(role);
    }

    /**
     * 解析角色目录并确保其未逃逸团队 workspace。
     *
     * @param role 已校验角色
     * @return 安全角色目录
     */
    private Path resolveRoleRoot(String role) {
        Path roleRoot = workspaceRoot.resolve(role).normalize();
        if (!roleRoot.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("角色 workspace 越界: " + role);
        }
        requireDirectory(roleRoot, "角色 workspace 不存在");
        rejectSymbolicLinks(workspaceRoot, roleRoot);
        return roleRoot;
    }

    /**
     * 验证角色名称属于固定团队角色集合。
     *
     * @param role 待验证角色
     */
    private static void requireRole(String role) {
        if (!MailboxService.TEAM_ROLES.contains(role)) {
            throw new IllegalArgumentException("未知团队角色: " + role);
        }
    }

    /**
     * 验证目录真实存在且不是符号链接。
     *
     * @param directory 待验证目录
     * @param message 失败信息
     */
    private static void requireDirectory(Path directory, String message) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(message + ": " + directory);
        }
    }

    /**
     * 拒绝从文件系统根到目标路径任一层出现符号链接，防止模板目录逃逸。
     *
     * @param target 待验证路径
     */
    private static void rejectSymbolicLinks(Path trustedRoot, Path target) {
        Path current = trustedRoot;
        for (Path segment : trustedRoot.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("角色 workspace 不允许符号链接: " + current);
            }
        }
    }

    /**
     * 解析系统级父目录符号链接，后续只检查可信 workspace 内部路径。
     *
     * @param path 已存在目录
     * @return 真实绝对路径
     */
    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("无法解析团队 workspace: " + path, exception);
        }
    }

    /**
     * 确保每个声明名称非空且在同一角色内唯一。
     *
     * @param role 角色名称
     * @param declarations 已加载声明
     */
    private static void validateUniqueNames(String role, List<SubagentDeclaration> declarations) {
        Set<String> names = new HashSet<>();
        for (SubagentDeclaration declaration : declarations) {
            String name = declaration.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("Sub-Agent 名称不能为空: " + role);
            }
            if (!names.add(name)) {
                throw new IllegalStateException("Sub-Agent 名称重复: " + role + "/" + name);
            }
        }
    }
}
