package cn.org.chris.wake.infra.persistence.cleanup;

import cn.org.chris.wake.domain.gateway.CleanupExecutor;
import cn.org.chris.wake.domain.model.CleanupPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在规范化 data root 内执行保留期清理与凭证文件安全初始化。
 */
public final class FileCleanupExecutor implements CleanupExecutor {

    /** 目录仅允许属主读写进入。 */
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );

    /** 凭证文件仅允许属主读写。 */
    private static final Set<PosixFilePermission> CREDENTIAL_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    /** 会话标识安全字符白名单。 */
    private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** 规范化且禁止符号链接的 data root。 */
    private final Path dataRoot;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建文件清理执行器并确保 data root 存在且不是符号链接。
     *
     * @param dataRoot 外部数据根目录
     * @param objectMapper JSON 编解码器
     */
    public FileCleanupExecutor(Path dataRoot, ObjectMapper objectMapper) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot 不能为空").toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        try {
            Files.createDirectories(this.dataRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("创建 data root 失败: " + this.dataRoot, exception);
        }
        if (Files.isSymbolicLink(this.dataRoot)) {
            throw new SecurityException("data root 不能是符号链接: " + this.dataRoot);
        }
    }

    /**
     * 对每个目录规则清理直接子项，并清理过期 session JSONL。
     *
     * @param policy 清理策略
     * @param now 当前时间
     * @return 保持策略顺序的删除统计
     */
    @Override
    public Map<String, Integer> sweep(CleanupPolicy policy, Instant now) {
        Objects.requireNonNull(policy, "policy 不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        Map<String, Integer> statistics = new LinkedHashMap<>();
        policy.rules().forEach((pattern, retentionDays) -> {
            Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
            statistics.put(pattern, cleanMatchedDirectories(pattern, cutoff));
        });
        Path sessions = resolveInside("sessions");
        if (Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS)) {
            Instant cutoff = now.minus(policy.sessionJsonlRetentionDays(), ChronoUnit.DAYS);
            statistics.put("sessions/*.jsonl", cleanSessionJsonl(sessions, cutoff));
        }
        return Collections.unmodifiableMap(statistics);
    }

    /**
     * 创建指定会话的 uploads、outputs 与 tmp 目录。
     *
     * @param sessionId 安全会话标识
     */
    @Override
    public void ensureWorkspaceDirectories(String sessionId) {
        if (sessionId == null || !SAFE_SESSION_ID.matcher(sessionId).matches()
                || ".".equals(sessionId) || "..".equals(sessionId)) {
            throw new IllegalArgumentException("非法 sessionId: " + sessionId);
        }
        Path sessionRoot = resolveInside("workspace/sessions/" + sessionId);
        createSafeDirectory(sessionRoot.resolve("uploads"));
        createSafeDirectory(sessionRoot.resolve("outputs"));
        createSafeDirectory(sessionRoot.resolve("tmp"));
    }

    /**
     * 以 0700 目录和 0600 文件权限原子写入飞书凭证。
     *
     * @param appId 飞书应用标识
     * @param appSecret 飞书应用密钥
     */
    @Override
    public void writeFeishuCredentials(String appId, String appSecret) {
        ObjectNode credentials = objectMapper.createObjectNode();
        credentials.put("app_id", requireText(appId, "appId"));
        credentials.put("app_secret", requireText(appSecret, "appSecret"));
        writeCredentialFile("feishu.json", credentials);
    }

    /**
     * API Key 非空时以安全权限原子写入百度凭证。
     *
     * @param apiKey 百度千帆 API Key
     */
    @Override
    public void writeBaiduCredentials(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        ObjectNode credentials = objectMapper.createObjectNode();
        credentials.put("api_key", apiKey);
        writeCredentialFile("baidu.json", credentials);
    }

    /**
     * 扫描 data root 下匹配 glob 的目录并清理其过期直接子项。
     *
     * @param pattern 相对目录 glob
     * @param cutoff 严格早于该时间才删除
     * @return 删除条目数
     */
    private int cleanMatchedDirectories(String pattern, Instant cutoff) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        MutableCount count = new MutableCount();
        try {
            Files.walkFileTree(dataRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new FileVisitor<>() {
                        /**
                         * 拒绝扫描过程中出现的符号链接目录。
                         *
                         * @param directory 当前目录
                         * @param attributes 基础属性
                         * @return 继续扫描
                         */
                        @Override
                        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                            if (Files.isSymbolicLink(directory)) {
                                throw new SecurityException("清理路径不得经过符号链接: " + directory);
                            }
                            Path relative = dataRoot.relativize(directory);
                            if (matcher.matches(relative)) {
                                count.value += cleanDirectChildren(directory, cutoff);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        /**
                         * 普通文件无需在扫描阶段处理。
                         *
                         * @param file 当前文件
                         * @param attributes 基础属性
                         * @return 继续扫描
                         */
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            return FileVisitResult.CONTINUE;
                        }

                        /**
                         * 文件访问失败时保留原始异常。
                         *
                         * @param file 失败路径
                         * @param exception 访问异常
                         * @return 不会返回
                         * @throws IOException 原始访问异常
                         */
                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                            throw exception;
                        }

                        /**
                         * 目录扫描结束后继续。
                         *
                         * @param directory 当前目录
                         * @param exception 扫描异常
                         * @return 继续扫描
                         * @throws IOException 原始扫描异常
                         */
                        @Override
                        public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                            if (exception != null) {
                                throw exception;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
            return count.value;
        } catch (IOException exception) {
            throw new IllegalStateException("扫描清理规则失败: " + pattern, exception);
        }
    }

    /**
     * 删除目标目录内 mtime 严格早于 cutoff 的直接子项，保留目标目录自身。
     *
     * @param directory 规则命中目录
     * @param cutoff 截止时间
     * @return 删除条目数
     */
    private int cleanDirectChildren(Path directory, Instant cutoff) {
        assertInsideRoot(directory);
        int count = 0;
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                Instant modifiedAt = Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toInstant();
                if (modifiedAt.isBefore(cutoff)) {
                    deleteTreeWithoutFollowingLinks(child);
                    count++;
                }
            }
            return count;
        } catch (IOException exception) {
            throw new IllegalStateException("清理目录失败: " + directory, exception);
        }
    }

    /**
     * 删除 sessions 下过期的直接 *.jsonl 文件。
     *
     * @param sessions sessions 目录
     * @param cutoff 截止时间
     * @return 删除文件数
     */
    private int cleanSessionJsonl(Path sessions, Instant cutoff) {
        assertNoSymbolicLinks(sessions);
        int count = 0;
        try (var files = Files.newDirectoryStream(sessions, "*.jsonl")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                    Files.delete(file);
                    count++;
                }
            }
            return count;
        } catch (IOException exception) {
            throw new IllegalStateException("清理 session JSONL 失败: " + sessions, exception);
        }
    }

    /**
     * 递归删除条目但不跟随任何符号链接。
     *
     * @param target 待删除条目
     * @throws IOException 删除失败
     */
    private void deleteTreeWithoutFollowingLinks(Path target) throws IOException {
        assertInsideRoot(target);
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(target);
            return;
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Collections.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 写入 workspace/.config 下的安全 JSON 凭证文件。
     *
     * @param fileName 固定凭证文件名
     * @param credentials JSON 内容
     */
    private void writeCredentialFile(String fileName, ObjectNode credentials) {
        Path configDirectory = resolveInside("workspace/.config");
        createSafeDirectory(configDirectory);
        setPosixPermissions(configDirectory, DIRECTORY_PERMISSIONS);
        Path target = configDirectory.resolve(fileName).normalize();
        assertNoSymbolicLinks(target);
        Path temporary = configDirectory.resolve(fileName + ".tmp");
        assertNoSymbolicLinks(temporary);
        try {
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(credentials);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
            )) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            setPosixPermissions(temporary, CREDENTIAL_PERMISSIONS);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setPosixPermissions(target, CREDENTIAL_PERMISSIONS);
        } catch (IOException exception) {
            throw new IllegalStateException("写入凭证失败: " + target, exception);
        }
    }

    /**
     * 创建目录并在创建前后校验路径不经过符号链接。
     *
     * @param directory 待创建目录
     */
    private void createSafeDirectory(Path directory) {
        assertInsideRoot(directory);
        assertExistingParentsHaveNoSymbolicLinks(directory);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("创建安全目录失败: " + directory, exception);
        }
        assertNoSymbolicLinks(directory);
    }

    /**
     * 解析 data root 内的相对路径并拒绝逃逸。
     *
     * @param relative 相对路径
     * @return 规范化绝对路径
     */
    private Path resolveInside(String relative) {
        Path target = dataRoot.resolve(relative).normalize();
        assertInsideRoot(target);
        return target;
    }

    /**
     * 校验路径规范化后仍位于 data root 内且不等于 root。
     *
     * @param target 待校验路径
     */
    private void assertInsideRoot(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(dataRoot) || normalized.equals(dataRoot)) {
            throw new SecurityException("清理路径不得逃逸或删除 data root: " + target);
        }
    }

    /**
     * 校验目标从 data root 起的所有已存在路径段均不是符号链接。
     *
     * @param target 待校验目标
     */
    private void assertNoSymbolicLinks(Path target) {
        assertInsideRoot(target);
        assertExistingParentsHaveNoSymbolicLinks(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new SecurityException("目标不能是符号链接: " + target);
        }
    }

    /**
     * 校验目标的所有已存在父级路径段不是符号链接。
     *
     * @param target 待校验目标
     */
    private void assertExistingParentsHaveNoSymbolicLinks(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        assertInsideRoot(normalized);
        Path current = dataRoot;
        for (Path part : dataRoot.relativize(normalized)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new SecurityException("路径不得经过符号链接: " + current);
            }
        }
    }

    /**
     * 在支持 POSIX 的文件系统设置权限；其他文件系统保留平台默认权限。
     *
     * @param path 目标路径
     * @param permissions POSIX 权限集合
     */
    private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统无法表达 0700/0600，由平台 ACL 接管。
        } catch (IOException exception) {
            throw new IllegalStateException("设置文件权限失败: " + path, exception);
        }
    }

    /**
     * 校验凭证必需文本。
     *
     * @param value 原始值
     * @param field 字段名
     * @return 已校验文本
     */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 允许匿名文件访问器累加清理数量。
     */
    private static final class MutableCount {

        /** 当前删除条目数。 */
        private int value;
    }
}
