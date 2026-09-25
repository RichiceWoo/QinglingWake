package cn.org.chris.wake.infra.persistence.mailbox;

import cn.org.chris.wake.domain.gateway.MailboxRepository;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.model.MailStatus;
import cn.org.chris.wake.domain.workspace.WorkspacePolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 以每角色 JSON 数组实现跨线程、跨进程安全的团队邮箱。
 */
public final class FileMailboxRepository implements MailboxRepository {

    /** 强制 UTC 输出为 +00:00，而不是 Jackson 默认的 Z。 */
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** 避免同一 JVM 对同一文件并发加锁触发 OverlappingFileLockException。 */
    private static final ConcurrentMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    /** 外部 AgentScope workspace 根目录。 */
    private final Path workspaceRoot;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /** 为领取和 stale 判断提供可测试时间。 */
    private final Clock clock;

    /**
     * 创建使用系统 UTC 时钟的邮箱 Repository。
     *
     * @param workspaceRoot 外部 workspace 根目录
     * @param objectMapper JSON 编解码器
     */
    public FileMailboxRepository(Path workspaceRoot, ObjectMapper objectMapper) {
        this(workspaceRoot, objectMapper, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的邮箱 Repository。
     *
     * @param workspaceRoot 外部 workspace 根目录
     * @param objectMapper JSON 编解码器
     * @param clock UTC 时钟
     */
    public FileMailboxRepository(Path workspaceRoot, ObjectMapper objectMapper, Clock clock) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在收件箱文件锁内追加 unread 邮件，不覆盖已有消息。
     *
     * @param message 待保存邮件
     */
    @Override
    public void append(MailMessage message) {
        validateRole(message.to());
        Path inbox = inboxPath(message.projectId(), message.to());
        withLockedInbox(inbox, messages -> {
            messages.add(toMap(message));
            return null;
        });
    }

    /**
     * 原子把全部 unread 邮件转为 in_progress，并返回反序列化快照。
     *
     * @param projectId 所属项目标识
     * @param role 目标角色
     * @return 已领取邮件快照
     */
    @Override
    public List<MailMessage> claimUnread(String projectId, String role) {
        validateRole(role);
        Path inbox = inboxPath(projectId, role);
        return withLockedInbox(inbox, messages -> {
            List<MailMessage> claimed = new ArrayList<>();
            String now = format(clock.instant());
            for (Map<String, Object> message : messages) {
                if ("unread".equals(message.get("status"))) {
                    message.put("status", "in_progress");
                    message.put("processing_since", now);
                    claimed.add(fromMap(new LinkedHashMap<>(message)));
                }
            }
            return List.copyOf(claimed);
        });
    }

    /**
     * 只允许把指定 in_progress 邮件转为 done。
     *
     * @param projectId 所属项目标识
     * @param role 邮箱角色
     * @param messageId 邮件标识
     */
    @Override
    public void markDone(String projectId, String role, String messageId) {
        validateRole(role);
        Path inbox = inboxPath(projectId, role);
        withLockedInbox(inbox, messages -> {
            Map<String, Object> target = messages.stream()
                    .filter(message -> messageId.equals(message.get("id")))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException("msg not found: " + messageId));
            if (!"in_progress".equals(target.get("status"))) {
                throw new IllegalStateException("msg " + messageId + " is not in_progress");
            }
            target.put("status", "done");
            return null;
        });
    }

    /**
     * 把处理时长严格超过 timeout 的邮件恢复为 unread。
     *
     * @param projectId 所属项目标识
     * @param role 邮箱角色
     * @param timeout 处理超时时间
     * @return 恢复数量
     */
    @Override
    public int resetStale(String projectId, String role, Duration timeout) {
        validateRole(role);
        Path inbox = inboxPath(projectId, role);
        return withLockedInbox(inbox, messages -> {
            int reset = 0;
            Instant now = clock.instant();
            for (Map<String, Object> message : messages) {
                if (!"in_progress".equals(message.get("status")) || message.get("processing_since") == null) {
                    continue;
                }
                Instant processingSince = OffsetDateTime.parse(String.valueOf(message.get("processing_since"))).toInstant();
                if (Duration.between(processingSince, now).compareTo(timeout) > 0) {
                    message.put("status", "unread");
                    message.put("processing_since", null);
                    reset++;
                }
            }
            return reset;
        });
    }

    /**
     * 在本地可重入锁和 NIO 文件锁内完成 read-modify-write。
     *
     * @param inbox 收件箱文件
     * @param operation 修改操作
     * @param <T> 操作结果类型
     * @return 操作结果
     */
    private <T> T withLockedInbox(Path inbox, MailboxOperation<T> operation) {
        assertNoSymbolicLinks(inbox);
        if (!Files.isRegularFile(inbox)) {
            throw new IllegalStateException("mailbox not initialized: " + inbox);
        }
        Path lockPath = inbox.resolveSibling(inbox.getFileName() + ".lock");
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        localLock.lock();
        try (FileChannel lockChannel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE
        ); FileLock ignored = lockChannel.lock()) {
            List<Map<String, Object>> messages = readMessages(inbox);
            T result = operation.apply(messages);
            writeMessages(inbox, messages);
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("邮箱文件操作失败: " + inbox, exception);
        } finally {
            localLock.unlock();
        }
    }

    /**
     * 读取邮箱 JSON 数组。
     *
     * @param inbox 收件箱文件
     * @return 可修改消息 Map 列表
     * @throws IOException JSON 读取失败
     */
    private List<Map<String, Object>> readMessages(Path inbox) throws IOException {
        if (Files.size(inbox) == 0) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(inbox.toFile(), new TypeReference<>() { });
    }

    /**
     * 将完整邮箱写入临时文件并原子替换，强制刷新磁盘。
     *
     * @param inbox 收件箱文件
     * @param messages 完整消息列表
     * @throws IOException 写入失败
     */
    private void writeMessages(Path inbox, List<Map<String, Object>> messages) throws IOException {
        Path temporary = inbox.resolveSibling(inbox.getFileName() + ".tmp");
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(messages);
        try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
        )) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        try {
            Files.move(temporary, inbox, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, inbox, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 构造项目角色邮箱路径。
     *
     * @param projectId 项目标识
     * @param role 目标角色
     * @return 规范化邮箱路径
     */
    private Path inboxPath(String projectId, String role) {
        String safeProjectId = WorkspacePolicy.validateProjectId(projectId);
        return workspaceRoot.resolve("shared/projects").resolve(safeProjectId).resolve("mailboxes").resolve(role + ".json");
    }

    /**
     * 校验从 workspace 根到邮箱的已存在路径段均不是符号链接。
     *
     * @param target 待校验邮箱路径
     */
    private void assertNoSymbolicLinks(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new SecurityException("邮箱路径逃逸 workspace");
        }
        Path current = workspaceRoot;
        if (Files.isSymbolicLink(current)) {
            throw new SecurityException("workspace 根目录不能是符号链接");
        }
        for (Path part : workspaceRoot.relativize(normalized)) {
            current = current.resolve(part);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new SecurityException("不允许通过符号链接访问邮箱: " + current);
            }
        }
    }

    /**
     * 校验邮箱角色为四个团队角色之一。
     *
     * @param role 待校验角色
     */
    private static void validateRole(String role) {
        if (!MailboxService.TEAM_ROLES.contains(role)) {
            throw new IllegalArgumentException("invalid role: " + role);
        }
    }

    /**
     * 将领域邮件转换为 Python 字段命名和小写状态的 JSON Map。
     *
     * @param message 领域邮件
     * @return JSON Map
     */
    private static Map<String, Object> toMap(MailMessage message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", message.id());
        data.put("project_id", message.projectId());
        data.put("from", message.from());
        data.put("to", message.to());
        data.put("type", message.type());
        data.put("subject", message.subject());
        data.put("content", message.content());
        data.put("timestamp", format(message.timestamp()));
        data.put("status", message.status().name().toLowerCase(Locale.ROOT));
        data.put("processing_since", message.processingSince() == null ? null : format(message.processingSince()));
        return data;
    }

    /**
     * 将 Python 兼容 JSON Map 转回领域邮件。
     *
     * @param data JSON Map
     * @return 领域邮件
     */
    private static MailMessage fromMap(Map<String, Object> data) {
        Object processingSince = data.get("processing_since");
        return new MailMessage(
                String.valueOf(data.get("id")),
                String.valueOf(data.get("project_id")),
                String.valueOf(data.get("from")),
                String.valueOf(data.get("to")),
                String.valueOf(data.get("type")),
                String.valueOf(data.get("subject")),
                data.get("content"),
                OffsetDateTime.parse(String.valueOf(data.get("timestamp"))).toInstant(),
                MailStatus.valueOf(String.valueOf(data.get("status")).toUpperCase(Locale.ROOT)),
                processingSince == null ? null : OffsetDateTime.parse(String.valueOf(processingSince)).toInstant()
        );
    }

    /**
     * 将 Instant 格式化为秒精度 UTC +00:00 字符串。
     *
     * @param instant 时间点
     * @return Python 兼容时间文本
     */
    private static String format(Instant instant) {
        return UTC_FORMATTER.format(instant.atOffset(ZoneOffset.UTC));
    }

    /**
     * 表示一次受锁保护的邮箱修改。
     *
     * @param <T> 操作结果类型
     */
    @FunctionalInterface
    private interface MailboxOperation<T> {

        /**
         * 修改已读取的完整邮箱列表。
         *
         * @param messages 可修改消息列表
         * @return 操作结果
         */
        T apply(List<Map<String, Object>> messages);
    }
}
