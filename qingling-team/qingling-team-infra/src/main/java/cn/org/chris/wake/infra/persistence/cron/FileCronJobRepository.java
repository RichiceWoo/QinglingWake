package cn.org.chris.wake.infra.persistence.cron;

import cn.org.chris.wake.domain.gateway.CronJobRepository;
import cn.org.chris.wake.domain.model.CronJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用文件锁和 write-then-rename 实现 Python tasks.json 兼容存储。
 */
public final class FileCronJobRepository implements CronJobRepository {

    /** 避免同一 JVM 并发文件锁产生 OverlappingFileLockException。 */
    private static final ConcurrentMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    /** tasks.json 规范化绝对路径。 */
    private final Path tasksPath;

    /** 同目录跨进程锁文件路径。 */
    private final Path lockPath;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 tasks.json 文件仓库。
     *
     * @param tasksPath tasks.json 路径
     * @param objectMapper JSON 编解码器
     */
    public FileCronJobRepository(Path tasksPath, ObjectMapper objectMapper) {
        this.tasksPath = Objects.requireNonNull(tasksPath, "tasksPath 不能为空").toAbsolutePath().normalize();
        this.lockPath = this.tasksPath.resolveSibling(this.tasksPath.getFileName() + ".lock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 在共享锁协议内读取全部任务。
     *
     * @return 完整任务快照
     */
    @Override
    public List<CronJob> findAll() {
        return withFileLock(this::readJobs);
    }

    /**
     * 原子替换完整任务列表。
     *
     * @param jobs 完整任务列表
     */
    @Override
    public void replaceAll(List<CronJob> jobs) {
        List<CronJob> snapshot = List.copyOf(Objects.requireNonNull(jobs, "jobs 不能为空"));
        withFileLock(() -> {
            writeJobs(snapshot);
            return null;
        });
    }

    /**
     * 按任务标识替换已有项或追加新项。
     *
     * @param job 待保存任务
     */
    @Override
    public void upsert(CronJob job) {
        Objects.requireNonNull(job, "job 不能为空");
        withFileLock(() -> {
            List<CronJob> jobs = new ArrayList<>(readJobs());
            jobs.removeIf(existing -> existing.id().equals(job.id()));
            jobs.add(job);
            writeJobs(jobs);
            return null;
        });
    }

    /**
     * 在单次 read-modify-write 锁内完成未到期 wake 去重。
     *
     * @param candidate 新的一次性 wake
     * @param nowMs 当前毫秒时间
     * @return 复用或新增任务
     */
    @Override
    public CronJob saveWakeIfAbsent(CronJob candidate, long nowMs) {
        Objects.requireNonNull(candidate, "candidate 不能为空");
        if (candidate.schedule().kind() != CronJob.ScheduleKind.AT || !candidate.deleteAfterRun()) {
            throw new IllegalArgumentException("候选 wake 必须是执行后删除的 AT 任务");
        }
        return withFileLock(() -> {
            List<CronJob> jobs = new ArrayList<>(readJobs());
            for (CronJob existing : jobs) {
                if (samePendingWake(existing, candidate, nowMs)) {
                    return existing;
                }
            }
            jobs.add(candidate);
            writeJobs(jobs);
            return candidate;
        });
    }

    /**
     * 删除指定任务并仅在发生变化时回写。
     *
     * @param jobId 任务标识
     * @return 是否实际删除
     */
    @Override
    public boolean delete(String jobId) {
        Objects.requireNonNull(jobId, "jobId 不能为空");
        return withFileLock(() -> {
            List<CronJob> jobs = new ArrayList<>(readJobs());
            boolean removed = jobs.removeIf(job -> job.id().equals(jobId));
            if (removed) {
                writeJobs(jobs);
            }
            return removed;
        });
    }

    /**
     * 获取 mtime 与 size 组合文件版本，不把锁文件变化计入热重载。
     *
     * @return 当前组合版本
     */
    @Override
    public Revision revision() {
        if (!Files.exists(tasksPath)) {
            return Revision.missing();
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(tasksPath, BasicFileAttributes.class);
            return new Revision(true, attributes.lastModifiedTime().toMillis(), attributes.size());
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Cron 文件版本失败: " + tasksPath, exception);
        }
    }

    /**
     * 判断两个任务是否为同路由、同消息且仍未到期的一次性 wake。
     *
     * @param existing 已有任务
     * @param candidate 候选任务
     * @param nowMs 当前时间
     * @return 是否应复用
     */
    private static boolean samePendingWake(CronJob existing, CronJob candidate, long nowMs) {
        return existing.enabled()
                && existing.schedule().kind() == CronJob.ScheduleKind.AT
                && existing.deleteAfterRun()
                && existing.schedule().atMs() > nowMs
                && existing.payload().routingKey().equals(candidate.payload().routingKey())
                && existing.payload().message().equals(candidate.payload().message());
    }

    /**
     * 使用 JVM 本地锁与 NIO 文件锁执行一次操作。
     *
     * @param operation 文件操作
     * @param <T> 返回类型
     * @return 操作结果
     */
    private <T> T withFileLock(FileOperation<T> operation) {
        try {
            Files.createDirectories(tasksPath.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("创建 Cron 目录失败: " + tasksPath.getParent(), exception);
        }
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        localLock.lock();
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return operation.execute();
        } catch (IOException exception) {
            throw new IllegalStateException("Cron 文件操作失败: " + tasksPath, exception);
        } finally {
            localLock.unlock();
        }
    }

    /**
     * 读取新对象格式或旧数组格式，空文件视为空任务列表。
     *
     * @return 可修改任务列表
     * @throws IOException 文件或 JSON 读取失败
     */
    private List<CronJob> readJobs() throws IOException {
        if (!Files.exists(tasksPath) || Files.size(tasksPath) == 0L) {
            return new ArrayList<>();
        }
        JsonNode root = objectMapper.readTree(tasksPath.toFile());
        JsonNode jobsNode = root.isArray() ? root : root.path("jobs");
        if (!jobsNode.isArray()) {
            return new ArrayList<>();
        }
        List<CronJob> jobs = new ArrayList<>();
        for (JsonNode node : jobsNode) {
            jobs.add(fromNode(node));
        }
        return jobs;
    }

    /**
     * 把完整任务列表编码为 version=1 对象并原子替换。
     *
     * @param jobs 完整任务列表
     * @throws IOException 编码或写入失败
     */
    private void writeJobs(List<CronJob> jobs) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 1);
        ArrayNode jobsNode = root.putArray("jobs");
        jobs.forEach(job -> jobsNode.add(toNode(job)));
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        Path temporary = tasksPath.resolveSibling(tasksPath.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
        )) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        try {
            Files.move(temporary, tasksPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, tasksPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 将 snake_case JSON 节点转换为领域任务。
     *
     * @param node 单个任务节点
     * @return 领域任务
     */
    private static CronJob fromNode(JsonNode node) {
        JsonNode scheduleNode = node.path("schedule");
        CronJob.ScheduleKind kind = CronJob.ScheduleKind.valueOf(requiredText(scheduleNode, "kind").toUpperCase(Locale.ROOT));
        String timezone = nullableText(scheduleNode, "tz");
        if (kind == CronJob.ScheduleKind.CRON && (timezone == null || timezone.isBlank())) {
            timezone = "UTC";
        }
        CronJob.Schedule schedule = new CronJob.Schedule(
                kind,
                nullableLong(scheduleNode, "at_ms"),
                nullableLong(scheduleNode, "every_ms"),
                nullableText(scheduleNode, "expr"),
                timezone
        );
        JsonNode payloadNode = node.path("payload");
        JsonNode stateNode = node.path("state");
        CronJob.State state = new CronJob.State(
                nullableLong(stateNode, "next_run_at_ms"),
                nullableLong(stateNode, "last_run_at_ms"),
                nullableText(stateNode, "last_status"),
                nullableText(stateNode, "last_error")
        );
        return new CronJob(
                requiredText(node, "id"),
                requiredText(node, "name"),
                !node.has("enabled") || node.path("enabled").asBoolean(),
                schedule,
                new CronJob.Payload(requiredText(payloadNode, "routing_key"), requiredText(payloadNode, "message")),
                state,
                node.path("created_at_ms").asLong(0L),
                node.path("updated_at_ms").asLong(0L),
                node.path("delete_after_run").asBoolean(false)
        );
    }

    /**
     * 将领域任务转换为 Python 兼容 snake_case JSON。
     *
     * @param job 领域任务
     * @return JSON 节点
     */
    private ObjectNode toNode(CronJob job) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", job.id());
        node.put("name", job.name());
        node.put("enabled", job.enabled());
        ObjectNode schedule = node.putObject("schedule");
        schedule.put("kind", job.schedule().kind().name().toLowerCase(Locale.ROOT));
        putNullable(schedule, "at_ms", job.schedule().atMs());
        putNullable(schedule, "every_ms", job.schedule().everyMs());
        putNullable(schedule, "expr", job.schedule().expression());
        putNullable(schedule, "tz", job.schedule().timezone());
        ObjectNode payload = node.putObject("payload");
        payload.put("routing_key", job.payload().routingKey());
        payload.put("message", job.payload().message());
        ObjectNode state = node.putObject("state");
        putNullable(state, "next_run_at_ms", job.state().nextRunAtMs());
        putNullable(state, "last_run_at_ms", job.state().lastRunAtMs());
        putNullable(state, "last_status", job.state().lastStatus());
        putNullable(state, "last_error", job.state().lastError());
        node.put("created_at_ms", job.createdAtMs());
        node.put("updated_at_ms", job.updatedAtMs());
        node.put("delete_after_run", job.deleteAfterRun());
        return node;
    }

    /**
     * 写入可空 JSON 标量。
     *
     * @param node 目标对象
     * @param field 字段名
     * @param value Long 或 String 值
     */
    private static void putNullable(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else if (value instanceof Long longValue) {
            node.put(field, longValue);
        } else {
            node.put(field, String.valueOf(value));
        }
    }

    /**
     * 读取必需文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 非空文本
     */
    private static String requiredText(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null) {
            throw new IllegalArgumentException("Cron JSON 缺少字段: " + field);
        }
        return value;
    }

    /**
     * 读取可空文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 文本或 null
     */
    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /**
     * 读取可空长整数字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 长整数或 null
     */
    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.longValue();
    }

    /**
     * 允许在持有文件锁期间抛出 IOException 的操作。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    private interface FileOperation<T> {

        /**
         * 执行文件操作。
         *
         * @return 操作结果
         * @throws IOException 文件访问失败
         */
        T execute() throws IOException;
    }
}
