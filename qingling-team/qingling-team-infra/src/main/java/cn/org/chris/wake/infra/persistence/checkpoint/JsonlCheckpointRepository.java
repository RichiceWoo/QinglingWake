package cn.org.chris.wake.infra.persistence.checkpoint;

import cn.org.chris.wake.domain.gateway.CheckpointRepository;
import cn.org.chris.wake.domain.model.PendingCheckpoint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用 append-only JSONL 注册 checkpoint，并以独立 resolve marker 实现幂等解决。
 */
public final class JsonlCheckpointRepository implements CheckpointRepository {

    /** 避免同一 JVM 对同一锁文件并发加锁造成重叠锁异常。 */
    private static final ConcurrentMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    /** pending checkpoint JSONL 的规范化绝对路径。 */
    private final Path pendingPath;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建指向 `shared/feishu_bridge/pending.jsonl` 等外部路径的 Repository。
     *
     * @param pendingPath pending JSONL 文件路径
     * @param objectMapper JSON 编解码器
     */
    public JsonlCheckpointRepository(Path pendingPath, ObjectMapper objectMapper) {
        this.pendingPath = Objects.requireNonNull(pendingPath, "pendingPath 不能为空").toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 在文件锁内追加含 resolved_at_ms 空值的注册记录并强制刷盘。
     *
     * @param checkpoint 待保存 checkpoint
     */
    @Override
    public void register(PendingCheckpoint checkpoint) {
        withLock(() -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("checkpoint_id", checkpoint.checkpointId());
            entry.put("routing_key", checkpoint.routingKey());
            entry.put("project_id", checkpoint.projectId());
            entry.put("kind", checkpoint.kind());
            entry.put("question", checkpoint.question());
            entry.put("created_at_ms", checkpoint.createdAtMs());
            entry.put("resolved_at_ms", null);
            appendLine(entry);
            return null;
        });
    }

    /**
     * 检查注册记录和既有 marker 后，首次解决时追加新 marker。
     *
     * @param checkpointId checkpoint 标识
     * @param resolvedAt 解决时间
     * @return 首次解决时为 true
     */
    @Override
    public boolean resolve(String checkpointId, Instant resolvedAt) {
        return withLock(() -> {
            boolean registered = false;
            boolean alreadyResolved = false;
            for (Map<String, Object> entry : readValidEntries()) {
                if (!checkpointId.equals(String.valueOf(entry.get("checkpoint_id")))) {
                    continue;
                }
                if (entry.containsKey("routing_key")) {
                    registered = true;
                } else if (isTruthyTimestamp(entry.get("resolved_at_ms"))) {
                    alreadyResolved = true;
                }
            }
            if (!registered || alreadyResolved) {
                return false;
            }
            appendLine(Map.of(
                    "checkpoint_id", checkpointId,
                    "resolved_at_ms", resolvedAt.toEpochMilli()
            ));
            return true;
        });
    }

    /**
     * 返回全部未被 marker 解决的合法注册记录。
     *
     * @return 按注册顺序排列的 pending 列表
     */
    @Override
    public List<PendingCheckpoint> findPending() {
        return withLock(this::readPendingWithoutLock);
    }

    /**
     * 返回指定路由的未解决 checkpoint。
     *
     * @param routingKey 业务路由键
     * @return 当前路由 pending 列表
     */
    @Override
    public List<PendingCheckpoint> findPending(String routingKey) {
        return findPending().stream()
                .filter(checkpoint -> checkpoint.routingKey().equals(routingKey))
                .toList();
    }

    /**
     * 按创建时间选择最新项；时间相同则选择文件中最后注册的一项。
     *
     * @param routingKey 业务路由键
     * @return 最新 pending 项
     */
    @Override
    public Optional<PendingCheckpoint> findLatestPending(String routingKey) {
        List<PendingCheckpoint> pending = findPending(routingKey);
        PendingCheckpoint latest = null;
        for (PendingCheckpoint checkpoint : pending) {
            if (latest == null || checkpoint.createdAtMs() >= latest.createdAtMs()) {
                latest = checkpoint;
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * 在当前文件锁内重建未解决 checkpoint 列表，跳过损坏和缺字段记录。
     *
     * @return pending 列表
     * @throws IOException 文件读取失败
     */
    private List<PendingCheckpoint> readPendingWithoutLock() throws IOException {
        List<Map<String, Object>> entries = readValidEntries();
        Set<String> resolvedIds = new HashSet<>();
        for (Map<String, Object> entry : entries) {
            if (!entry.containsKey("routing_key") && isTruthyTimestamp(entry.get("resolved_at_ms"))) {
                resolvedIds.add(String.valueOf(entry.get("checkpoint_id")));
            }
        }
        List<PendingCheckpoint> pending = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (!entry.containsKey("routing_key")) {
                continue;
            }
            String checkpointId = nullableText(entry.get("checkpoint_id"));
            String routingKey = nullableText(entry.get("routing_key"));
            if (checkpointId == null || routingKey == null || resolvedIds.contains(checkpointId)) {
                continue;
            }
            try {
                pending.add(new PendingCheckpoint(
                        checkpointId,
                        routingKey,
                        textOrDefault(entry.get("project_id"), ""),
                        textOrDefault(entry.get("kind"), "checkpoint_request"),
                        textOrDefault(entry.get("question"), ""),
                        longOrDefault(entry.get("created_at_ms"), 0L)
                ));
            } catch (RuntimeException ignored) {
                // 与 Python 读取策略一致：单条非法记录不阻断其余 pending 恢复。
            }
        }
        return List.copyOf(pending);
    }

    /**
     * 读取全部合法 JSON 对象，跳过空行和损坏行。
     *
     * @return 合法 JSON Map 列表
     * @throws IOException 文件读取失败
     */
    private List<Map<String, Object>> readValidEntries() throws IOException {
        ensureFile();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String line : Files.readAllLines(pendingPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                entries.add(objectMapper.readValue(line, new TypeReference<>() { }));
            } catch (IOException ignored) {
                // 与 Python 契约一致：损坏 JSONL 行直接跳过。
            }
        }
        return entries;
    }

    /**
     * 将单条 JSON 记录追加到文件并 fsync。
     *
     * @param entry 待追加字段
     * @throws IOException 序列化或写入失败
     */
    private void appendLine(Map<String, Object> entry) throws IOException {
        byte[] line = (objectMapper.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                pendingPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
        )) {
            channel.write(ByteBuffer.wrap(line));
            channel.force(true);
        }
    }

    /**
     * 使用 JVM 本地锁和 NIO 文件锁串行执行一次读改写操作。
     *
     * @param operation 受保护操作
     * @param <T> 操作结果类型
     * @return 操作结果
     */
    private <T> T withLock(CheckpointOperation<T> operation) {
        Path lockPath = pendingPath.resolveSibling(pendingPath.getFileName() + ".lock");
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        localLock.lock();
        try {
            ensureFile();
            try (FileChannel lockChannel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE
            ); FileLock ignored = lockChannel.lock()) {
                return operation.apply();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("checkpoint 文件操作失败: " + pendingPath, exception);
        } finally {
            localLock.unlock();
        }
    }

    /**
     * 幂等创建父目录和空 JSONL 文件，并拒绝直接符号链接目标。
     *
     * @throws IOException 创建失败
     */
    private void ensureFile() throws IOException {
        Path parent = pendingPath.getParent();
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(parent)) {
            throw new SecurityException("checkpoint 父目录不能是符号链接: " + parent);
        }
        Files.createDirectories(parent);
        if (Files.exists(pendingPath, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(pendingPath)) {
            throw new SecurityException("checkpoint 文件不能是符号链接: " + pendingPath);
        }
        if (!Files.exists(pendingPath, LinkOption.NOFOLLOW_LINKS)) {
            try (FileChannel ignored = FileChannel.open(
                    pendingPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE
            )) {
                // CREATE 在跨进程竞争下保持幂等，空文件无需额外写入。
            }
        }
    }

    /**
     * 判断 resolved_at_ms 是否符合 Python 的 truthy 时间语义。
     *
     * @param value 原始时间值
     * @return 非零数值或非空文本时为 true
     */
    private static boolean isTruthyTimestamp(Object value) {
        if (value instanceof Number number) {
            return number.longValue() != 0L;
        }
        return value != null && !String.valueOf(value).isBlank() && !"0".equals(String.valueOf(value));
    }

    /**
     * 将必需文本值转换为字符串，缺失或空白时返回 null。
     *
     * @param value 原始值
     * @return 非空文本或 null
     */
    private static String nullableText(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    /**
     * 返回文本值，字段缺失时使用默认值。
     *
     * @param value 原始值
     * @param fallback 默认文本
     * @return 实际或默认文本
     */
    private static String textOrDefault(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * 返回整数时间值，无法转换时使用默认值。
     *
     * @param value 原始值
     * @param fallback 默认值
     * @return 实际或默认整数
     */
    private static long longOrDefault(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /**
     * 表示一次受文件锁保护的 checkpoint 操作。
     *
     * @param <T> 操作结果类型
     */
    @FunctionalInterface
    private interface CheckpointOperation<T> {

        /**
         * 执行可能抛出文件异常的操作。
         *
         * @return 操作结果
         * @throws IOException 文件操作失败
         */
        T apply() throws IOException;
    }
}
