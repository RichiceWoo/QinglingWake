package cn.org.chris.wake.infra.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将类路径中的 AgentScope workspace 模板安全、幂等地初始化到外部可写目录。
 */
public final class WorkspaceTemplateInitializer {

    /** 当前模板结构版本。 */
    private static final int TEMPLATE_VERSION = 1;

    /** 默认类路径模板根目录。 */
    private static final String DEFAULT_RESOURCE_ROOT = "workspace-template";

    /** 初始化器维护的模板状态文件名。 */
    private static final String STATE_FILE = ".qingling-template-state.json";

    /** 跨进程串行化模板初始化的锁文件名。 */
    private static final String LOCK_FILE = ".qingling-template-state.lock";

    /** 规范化后的外部 workspace 根目录。 */
    private final Path workspaceRoot;

    /** JSON 状态编解码器。 */
    private final ObjectMapper objectMapper;

    /** 模板资源所在的类路径根目录。 */
    private final String resourceRoot;

    /** 读取模板资源的类加载器。 */
    private final ClassLoader classLoader;

    /**
     * 使用默认 workspace-template 类路径目录创建初始化器。
     *
     * @param workspaceRoot 外部可写 workspace 根目录
     * @param objectMapper JSON 状态编解码器
     */
    public WorkspaceTemplateInitializer(Path workspaceRoot, ObjectMapper objectMapper) {
        this(workspaceRoot, objectMapper, DEFAULT_RESOURCE_ROOT, contextClassLoader());
    }

    /**
     * 使用指定类路径目录和类加载器创建初始化器，便于独立打包与契约测试。
     *
     * @param workspaceRoot 外部可写 workspace 根目录
     * @param objectMapper JSON 状态编解码器
     * @param resourceRoot 模板资源根目录
     * @param classLoader 模板类加载器
     */
    public WorkspaceTemplateInitializer(
            Path workspaceRoot,
            ObjectMapper objectMapper,
            String resourceRoot,
            ClassLoader classLoader
    ) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot 不能为空")
                .toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.resourceRoot = normalizeResourceRoot(resourceRoot);
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader 不能为空");
        initializeRoot();
    }

    /**
     * 应用模板清单；只升级仍等于上次模板版本的 managed 文件，永不覆盖 seed 或用户改动。
     *
     * @return 本次初始化统计
     */
    public InitializationReport initialize() {
        Path lockPath = workspaceRoot.resolve(LOCK_FILE);
        assertSafePath(lockPath);
        try (FileChannel lockChannel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE
        ); FileLock ignored = lockChannel.lock()) {
            assertSafePath(lockPath);
            TemplateState previousState = readState();
            Map<String, String> nextHashes = new LinkedHashMap<>(previousState.hashes());
            MutableReport report = new MutableReport();
            for (ManifestEntry entry : readManifest()) {
                applyEntry(entry, previousState.hashes(), nextHashes, report);
            }
            writeState(nextHashes);
            return report.toImmutable();
        } catch (IOException exception) {
            throw new IllegalStateException("初始化 AgentScope workspace 模板失败: " + workspaceRoot, exception);
        }
    }

    /**
     * 根据清单策略创建、升级或保留单个模板文件。
     *
     * @param entry 模板清单项
     * @param previousHashes 上一次成功应用的模板摘要
     * @param nextHashes 本次待保存的模板摘要
     * @param report 可变统计
     * @throws IOException 文件读写失败
     */
    private void applyEntry(
            ManifestEntry entry,
            Map<String, String> previousHashes,
            Map<String, String> nextHashes,
            MutableReport report
    ) throws IOException {
        byte[] template = readResourceBytes(entry.relativePath());
        String templateHash = sha256(template);
        Path target = resolveTarget(entry.relativePath());
        assertSafePath(target);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            createParentDirectories(target);
            writeAtomically(target, template);
            if (entry.policy() == Policy.MANAGED) {
                nextHashes.put(entry.relativePath(), templateHash);
            }
            report.created++;
            return;
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("模板目标不是普通文件: " + target);
        }
        String actualHash = sha256(Files.readAllBytes(target));
        if (entry.policy() == Policy.SEED) {
            report.preserved++;
            return;
        }
        if (actualHash.equals(templateHash)) {
            nextHashes.put(entry.relativePath(), templateHash);
            report.unchanged++;
            return;
        }
        String previousHash = previousHashes.get(entry.relativePath());
        if (previousHash != null && actualHash.equals(previousHash)) {
            writeAtomically(target, template);
            nextHashes.put(entry.relativePath(), templateHash);
            report.updated++;
            return;
        }
        report.preserved++;
    }

    /**
     * 从 file-manifest.txt 读取确定性模板清单。
     *
     * @return 按声明顺序排列的清单项
     * @throws IOException 清单读取失败
     */
    private List<ManifestEntry> readManifest() throws IOException {
        String manifest = new String(readResourceBytes("file-manifest.txt"), StandardCharsets.UTF_8);
        List<ManifestEntry> entries = new ArrayList<>();
        for (String rawLine : manifest.lines().toList()) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("非法模板清单行: " + rawLine);
            }
            entries.add(new ManifestEntry(Policy.parse(parts[0]), normalizeRelativePath(parts[1])));
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("模板清单不能为空: " + resourceRoot);
        }
        return List.copyOf(entries);
    }

    /**
     * 读取上一次由初始化器成功写入的 managed 文件摘要。
     *
     * @return 已持久化状态，不存在时返回空状态
     * @throws IOException 状态读取失败
     */
    private TemplateState readState() throws IOException {
        Path statePath = workspaceRoot.resolve(STATE_FILE);
        assertSafePath(statePath);
        if (!Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)) {
            return new TemplateState(0, Map.of());
        }
        JsonNode root = objectMapper.readTree(statePath.toFile());
        JsonNode hashesNode = root.path("hashes");
        if (!root.isObject() || !hashesNode.isObject()) {
            throw new IllegalStateException("模板状态文件格式非法: " + statePath);
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        hashesNode.fields().forEachRemaining(field ->
                hashes.put(normalizeRelativePath(field.getKey()), field.getValue().asText()));
        return new TemplateState(root.path("version").asInt(0), Map.copyOf(hashes));
    }

    /**
     * 原子保存当前 managed 文件对应的模板摘要。
     *
     * @param hashes 当前模板摘要
     * @throws IOException 状态写入失败
     */
    private void writeState(Map<String, String> hashes) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", TEMPLATE_VERSION);
        ObjectNode hashesNode = root.putObject("hashes");
        hashes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> hashesNode.put(entry.getKey(), entry.getValue()));
        writeAtomically(workspaceRoot.resolve(STATE_FILE), objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(root));
    }

    /**
     * 从类路径读取模板资源。
     *
     * @param relativePath 资源根目录下的相对路径
     * @return 完整资源字节
     * @throws IOException 资源读取失败
     */
    private byte[] readResourceBytes(String relativePath) throws IOException {
        String resourceName = resourceRoot + "/" + normalizeRelativePath(relativePath);
        try (InputStream input = classLoader.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("模板资源不存在: " + resourceName);
            }
            return input.readAllBytes();
        }
    }

    /**
     * 创建缺失父目录，并在创建前后拒绝符号链接路径。
     *
     * @param target 待写入文件
     * @throws IOException 目录创建失败
     */
    private void createParentDirectories(Path target) throws IOException {
        Path parent = target.getParent();
        assertSafePath(parent);
        Files.createDirectories(parent);
        assertSafePath(parent);
    }

    /**
     * 使用同目录临时文件与原子移动写入完整字节。
     *
     * @param target 目标文件
     * @param content 文件内容
     * @throws IOException 写入失败
     */
    private void writeAtomically(Path target, byte[] content) throws IOException {
        assertSafePath(target);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        assertSafePath(temporary);
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            channel.write(ByteBuffer.wrap(content));
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 将清单相对路径解析到 workspace 内并验证包含关系。
     *
     * @param relativePath 清单相对路径
     * @return 规范化目标路径
     */
    private Path resolveTarget(String relativePath) {
        Path target = workspaceRoot.resolve(normalizeRelativePath(relativePath)).normalize();
        if (!target.startsWith(workspaceRoot) || target.equals(workspaceRoot)) {
            throw new SecurityException("模板路径越出 workspace: " + relativePath);
        }
        return target;
    }

    /**
     * 拒绝 workspace 根目录到目标路径之间任一已存在的符号链接。
     *
     * @param target 待验证路径
     */
    private void assertSafePath(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new SecurityException("模板路径越出 workspace: " + normalized);
        }
        Path current = workspaceRoot;
        if (Files.isSymbolicLink(current)) {
            throw new SecurityException("workspace 根目录不能是符号链接: " + current);
        }
        for (Path segment : workspaceRoot.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new SecurityException("模板路径不得经过符号链接: " + current);
            }
        }
    }

    /**
     * 创建并验证外部 workspace 根目录。
     */
    private void initializeRoot() {
        try {
            Files.createDirectories(workspaceRoot);
            assertSafePath(workspaceRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("创建 workspace 根目录失败: " + workspaceRoot, exception);
        }
    }

    /**
     * 计算文件内容的 SHA-256 十六进制摘要。
     *
     * @param content 文件字节
     * @return 小写十六进制摘要
     */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /**
     * 校验并规范化类路径资源根目录。
     *
     * @param value 原始资源根目录
     * @return 无首尾斜杠的资源根目录
     */
    private static String normalizeResourceRoot(String value) {
        String normalized = Objects.requireNonNull(value, "resourceRoot 不能为空").trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank() || normalized.contains("..")) {
            throw new IllegalArgumentException("非法模板资源根目录: " + value);
        }
        return normalized;
    }

    /**
     * 校验清单路径只能是使用正斜杠的安全相对文件路径。
     *
     * @param value 原始路径
     * @return 规范化资源路径
     */
    private static String normalizeRelativePath(String value) {
        String normalized = Objects.requireNonNull(value, "relativePath 不能为空").trim().replace('\\', '/');
        Path path = Path.of(normalized).normalize();
        if (normalized.isBlank() || path.isAbsolute() || normalized.startsWith("/")
                || normalized.equals("..") || normalized.startsWith("../") || normalized.contains("/../")
                || normalized.endsWith("/..") || normalized.contains("//")) {
            throw new SecurityException("非法模板相对路径: " + value);
        }
        return normalized;
    }

    /**
     * 获取线程上下文类加载器，缺失时回退到初始化器类加载器。
     *
     * @return 可用类加载器
     */
    private static ClassLoader contextClassLoader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context == null ? WorkspaceTemplateInitializer.class.getClassLoader() : context;
    }

    /** 模板文件更新策略。 */
    private enum Policy {
        /** 允许初始化器在文件未被用户修改时升级。 */
        MANAGED,
        /** 仅在文件缺失时写入初始内容。 */
        SEED;

        /**
         * 解析清单中的策略名称。
         *
         * @param value 清单策略文本
         * @return 对应策略
         */
        private static Policy parse(String value) {
            return switch (value.trim()) {
                case "managed" -> MANAGED;
                case "seed" -> SEED;
                default -> throw new IllegalStateException("未知模板策略: " + value);
            };
        }
    }

    /** 单个模板清单项。 */
    private record ManifestEntry(
            /** 文件更新策略。 */
            Policy policy,
            /** 模板根目录内相对路径。 */
            String relativePath
    ) {
    }

    /** 上一次初始化器状态。 */
    private record TemplateState(
            /** 上一次模板版本。 */
            int version,
            /** managed 文件对应的模板摘要。 */
            Map<String, String> hashes
    ) {
    }

    /** 初始化过程中的可变统计。 */
    private static final class MutableReport {

        /** 新建文件数。 */
        private int created;

        /** 安全升级文件数。 */
        private int updated;

        /** 因 seed 或用户改动而保留的文件数。 */
        private int preserved;

        /** 已与当前模板一致的文件数。 */
        private int unchanged;

        /**
         * 生成对外不可变统计。
         *
         * @return 初始化结果
         */
        private InitializationReport toImmutable() {
            return new InitializationReport(TEMPLATE_VERSION, created, updated, preserved, unchanged);
        }
    }

    /**
     * 单次模板初始化统计。
     *
     * @param templateVersion 当前模板版本
     * @param created 新建文件数
     * @param updated 安全升级文件数
     * @param preserved 保留 seed 或用户改动文件数
     * @param unchanged 已与当前模板一致文件数
     */
    public record InitializationReport(
            int templateVersion,
            int created,
            int updated,
            int preserved,
            int unchanged
    ) {
    }
}
