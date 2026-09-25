package cn.org.chris.wake.infra.persistence.event;

import cn.org.chris.wake.domain.gateway.EventRepository;
import cn.org.chris.wake.domain.workspace.WorkspacePolicy;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用单行 JSON、跨进程文件锁和单调 seq 实现 append-only 项目事件流。
 */
public final class JsonlEventRepository implements EventRepository {

    /** 以秒精度和显式 +00:00 输出 UTC 时间。 */
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** 防止同 JVM 并发文件锁重叠并确保读取 seq 与追加属于同一临界区。 */
    private static final ConcurrentMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    /** 外部 AgentScope workspace 根目录。 */
    private final Path workspaceRoot;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /** 生成可测试事件时间。 */
    private final Clock clock;

    /**
     * 创建使用系统 UTC 时钟的事件 Repository。
     *
     * @param workspaceRoot 外部 workspace 根目录
     * @param objectMapper JSON 编解码器
     */
    public JsonlEventRepository(Path workspaceRoot, ObjectMapper objectMapper) {
        this(workspaceRoot, objectMapper, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的事件 Repository。
     *
     * @param workspaceRoot 外部 workspace 根目录
     * @param objectMapper JSON 编解码器
     * @param clock UTC 时钟
     */
    public JsonlEventRepository(Path workspaceRoot, ObjectMapper objectMapper, Clock clock) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在同一文件锁内读取最后合法 seq、追加下一条事件并 fsync。
     *
     * @param projectId 项目标识
     * @param actor 事件发起角色
     * @param action 动作名称
     * @param details 事件详情
     * @return 新事件 seq
     */
    @Override
    public long append(String projectId, String actor, String action, Map<String, Object> details) {
        Path eventsPath = prepareEventsPath(projectId);
        Path lockPath = eventsPath.resolveSibling("events.jsonl.lock");
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        localLock.lock();
        try (FileChannel lockChannel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE
        ); FileLock ignored = lockChannel.lock()) {
            long seq = lastSequence(eventsPath) + 1;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("ts", format(clock.instant()));
            event.put("seq", seq);
            event.put("actor", actor);
            event.put("action", action);
            event.put("payload", details);
            byte[] line = (objectMapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel dataChannel = FileChannel.open(
                    eventsPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
            )) {
                if (needsLeadingNewline(eventsPath)) {
                    dataChannel.write(ByteBuffer.wrap(new byte[]{'\n'}));
                }
                dataChannel.write(ByteBuffer.wrap(line));
                dataChannel.force(true);
            }
            return seq;
        } catch (IOException exception) {
            throw new IllegalStateException("追加项目事件失败: " + projectId, exception);
        } finally {
            localLock.unlock();
        }
    }

    /**
     * 按文件顺序读取合法事件，静默跳过空行和损坏 JSON 行。
     *
     * @param projectId 项目标识
     * @return 合法事件快照
     */
    @Override
    public List<Map<String, Object>> readAll(String projectId) {
        Path eventsPath = eventsPath(projectId);
        if (!Files.exists(eventsPath, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        assertNoSymbolicLinks(eventsPath);
        List<Map<String, Object>> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(eventsPath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> event = objectMapper.readValue(line, new TypeReference<>() { });
                    events.add(new LinkedHashMap<>(event));
                } catch (IOException ignored) {
                    // 与 Python 契约一致：单条损坏记录不阻断其余事件读取。
                }
            }
            return List.copyOf(events);
        } catch (IOException exception) {
            throw new IllegalStateException("读取项目事件失败: " + projectId, exception);
        }
    }

    /**
     * 从后向前找到最后一条含合法数值 seq 的事件。
     *
     * @param eventsPath 事件文件
     * @return 最后 seq；无合法事件时为零
     * @throws IOException 文件读取失败
     */
    private long lastSequence(Path eventsPath) throws IOException {
        if (!Files.exists(eventsPath) || Files.size(eventsPath) == 0) {
            return 0;
        }
        List<String> lines = Files.readAllLines(eventsPath, StandardCharsets.UTF_8);
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            try {
                Map<String, Object> event = objectMapper.readValue(line, new TypeReference<>() { });
                Object seq = event.get("seq");
                if (seq instanceof Number number) {
                    return number.longValue();
                }
            } catch (IOException ignored) {
                // 损坏尾行向前继续查找，避免 seq 回退或重复。
            }
        }
        return 0;
    }

    /**
     * 检测崩溃遗留的非换行结尾，避免下一条合法 JSON 与损坏尾行粘连。
     *
     * @param eventsPath 事件文件
     * @return 非空文件且最后一个字节不是换行时为 true
     * @throws IOException 文件读取失败
     */
    private static boolean needsLeadingNewline(Path eventsPath) throws IOException {
        if (!Files.exists(eventsPath) || Files.size(eventsPath) == 0) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(eventsPath, StandardOpenOption.READ)) {
            ByteBuffer lastByte = ByteBuffer.allocate(1);
            channel.position(channel.size() - 1);
            channel.read(lastByte);
            return lastByte.array()[0] != '\n';
        }
    }

    /**
     * 创建事件父目录并校验所有路径段不是符号链接。
     *
     * @param projectId 项目标识
     * @return 可安全追加的事件路径
     */
    private Path prepareEventsPath(String projectId) {
        Path path = eventsPath(projectId);
        try {
            assertNoSymbolicLinks(path.getParent());
            Files.createDirectories(path.getParent());
            assertNoSymbolicLinks(path.getParent());
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
                throw new SecurityException("events.jsonl 不能是符号链接");
            }
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("创建事件目录失败: " + projectId, exception);
        }
    }

    /**
     * 构造 workspace 内的项目事件路径。
     *
     * @param projectId 项目标识
     * @return events.jsonl 路径
     */
    private Path eventsPath(String projectId) {
        String safeProjectId = WorkspacePolicy.validateProjectId(projectId);
        Path path = workspaceRoot.resolve("shared/projects").resolve(safeProjectId).resolve("events.jsonl").normalize();
        if (!path.startsWith(workspaceRoot)) {
            throw new SecurityException("事件路径逃逸 workspace");
        }
        return path;
    }

    /**
     * 校验从 workspace 根到目标的已存在路径段均不是符号链接。
     *
     * @param target 待校验路径
     */
    private void assertNoSymbolicLinks(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new SecurityException("事件路径逃逸 workspace");
        }
        Path current = workspaceRoot;
        if (Files.isSymbolicLink(current)) {
            throw new SecurityException("workspace 根目录不能是符号链接");
        }
        for (Path part : workspaceRoot.relativize(normalized)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new SecurityException("不允许通过符号链接访问事件文件: " + current);
            }
        }
    }

    /**
     * 将时间格式化为 Python 兼容的 UTC +00:00 文本。
     *
     * @param instant 时间点
     * @return 秒精度时间文本
     */
    private static String format(Instant instant) {
        return UTC_FORMATTER.format(instant.atOffset(ZoneOffset.UTC));
    }
}
