package cn.org.chris.wake.infra.persistence.session;

import cn.org.chris.wake.domain.gateway.ConversationAuditRepository;
import cn.org.chris.wake.domain.model.ConversationAuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将对话保存为只写 JSONL 审计文件，不提供任何模型历史加载方法。
 */
public final class JsonlConversationAuditRepository implements ConversationAuditRepository {

    /** Jackson JSON 编码器。 */
    private final ObjectMapper objectMapper;

    /** 外部数据目录中的 sessions 子目录。 */
    private final Path sessionsDirectory;

    /**
     * 创建 JSONL 审计仓库。
     *
     * @param dataDirectory 应用外部数据目录
     * @param objectMapper JSON 编码器
     */
    public JsonlConversationAuditRepository(Path dataDirectory, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.sessionsDirectory = dataDirectory.resolve("sessions");
        ensureDirectory();
    }

    /**
     * 文件不存在时写入一行 meta；已有文件保持不变。
     *
     * @param sessionId AgentScope 会话标识
     * @param routingKey 业务路由键
     * @param createdAt 创建时间
     */
    @Override
    public void initialize(String sessionId, String routingKey, Instant createdAt) {
        Path jsonl = jsonlPath(sessionId);
        if (Files.exists(jsonl)) {
            return;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "meta");
        meta.put("session_id", sessionId);
        meta.put("routing_key", routingKey);
        meta.put("created_at", createdAt.toString());
        appendRaw(sessionId, List.of(meta), false);
    }

    /**
     * 在同一个锁和 fsync 周期内追加本轮全部审计记录。
     *
     * @param sessionId AgentScope 会话标识
     * @param entries 本轮审计记录
     */
    @Override
    public void append(String sessionId, List<ConversationAuditEntry> entries) {
        List<Map<String, Object>> records = entries.stream().map(entry -> {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", "message");
            record.put("role", entry.role());
            record.put("content", entry.content());
            record.put("ts", entry.timestampMs());
            if (entry.sourceMessageId() != null) {
                record.put("feishu_msg_id", entry.sourceMessageId());
            }
            return record;
        }).toList();
        appendRaw(sessionId, records, true);
    }

    /**
     * 删除测试环境下的 JSONL 与锁文件；不会触碰 sessions 目录之外的数据。
     */
    @Override
    public void clearAll() {
        ensureDirectory();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(sessionsDirectory, "s-*.jsonl*")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("清理 session 审计失败", exception);
        }
    }

    /**
     * 将多条 Map 编码为换行 JSON，并强制刷盘。
     *
     * @param sessionId AgentScope 会话标识
     * @param records 待写记录
     * @param append 是否追加到现有文件
     */
    private void appendRaw(String sessionId, List<Map<String, Object>> records, boolean append) {
        ensureDirectory();
        Path jsonl = jsonlPath(sessionId);
        Path lockPath = jsonl.resolveSibling(jsonl.getFileName() + ".lock");
        try (FileChannel lockChannel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE
        ); FileLock ignored = lockChannel.lock(); FileChannel dataChannel = openDataChannel(jsonl, append)) {
            for (Map<String, Object> record : records) {
                byte[] line = (objectMapper.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8);
                dataChannel.write(ByteBuffer.wrap(line));
            }
            dataChannel.force(true);
        } catch (IOException exception) {
            throw new IllegalStateException("写入 session 审计失败", exception);
        }
    }

    /**
     * 按初始化或追加模式打开 JSONL 文件。
     *
     * @param path JSONL 文件
     * @param append 是否追加
     * @return 已打开文件通道
     * @throws IOException 打开失败
     */
    private static FileChannel openDataChannel(Path path, boolean append) throws IOException {
        if (append) {
            return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }
        return FileChannel.open(
                path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
        );
    }

    /**
     * 返回指定 session 的 JSONL 路径。
     *
     * @param sessionId AgentScope 会话标识
     * @return 审计文件路径
     */
    private Path jsonlPath(String sessionId) {
        if (sessionId == null || !sessionId.matches("^s-[0-9A-Za-z_-]+$")) {
            throw new IllegalArgumentException("非法 sessionId");
        }
        return sessionsDirectory.resolve(sessionId + ".jsonl");
    }

    /**
     * 确保 sessions 目录存在。
     */
    private void ensureDirectory() {
        try {
            Files.createDirectories(sessionsDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("创建 sessions 目录失败", exception);
        }
    }
}
