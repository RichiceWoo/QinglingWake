package cn.org.chris.wake.infra.persistence.session;

import cn.org.chris.wake.domain.gateway.SessionRouteRepository;
import cn.org.chris.wake.domain.model.SessionRoute;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 使用 FileLock、临时文件和原子替换维护 Python 兼容的 session index.json。
 */
public final class FileSessionRouteRepository implements SessionRouteRepository {

    /** Jackson JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /** sessions/index.json 文件。 */
    private final Path indexPath;

    /** 跨进程写锁文件。 */
    private final Path lockPath;

    /**
     * 创建文件路由仓库。
     *
     * @param dataDirectory 应用外部数据目录
     * @param objectMapper JSON 编解码器
     */
    public FileSessionRouteRepository(Path dataDirectory, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.indexPath = dataDirectory.resolve("sessions").resolve("index.json");
        this.lockPath = dataDirectory.resolve("sessions").resolve("index.json.lock");
        ensureDirectory();
    }

    /**
     * 查询当前 active session，并兼容缺少可选字段的 Python 索引。
     *
     * @param routingKey 业务路由键
     * @return 当前路由快照
     */
    @Override
    public Optional<SessionRoute> find(String routingKey) {
        try {
            Map<String, Object> index = readIndex();
            Map<String, Object> routing = asMap(index.get(routingKey));
            if (routing == null) {
                return Optional.empty();
            }
            String activeId = String.valueOf(routing.get("active_session_id"));
            for (Object rawSession : asList(routing.get("sessions"))) {
                Map<String, Object> session = asMap(rawSession);
                if (session != null && activeId.equals(session.get("id"))) {
                    return Optional.of(toRoute(routingKey, session));
                }
            }
            return Optional.empty();
        } catch (IOException exception) {
            throw persistenceFailure("读取 session index 失败", exception);
        }
    }

    /**
     * 读取每个 routing key 的当前 active session，忽略没有有效 active 条目的损坏路由。
     *
     * @return 当前活跃路由的不可变列表
     */
    @Override
    public List<SessionRoute> findAll() {
        try {
            Map<String, Object> index = readIndex();
            List<SessionRoute> routes = new ArrayList<>();
            index.forEach((routingKey, rawRouting) -> activeRoute(routingKey, asMap(rawRouting)).ifPresent(routes::add));
            return List.copyOf(routes);
        } catch (IOException exception) {
            throw persistenceFailure("读取全部 session 路由失败", exception);
        }
    }

    /**
     * 在文件锁内追加新 session 或更新当前 session，并原子替换 index。
     *
     * @param route 最新路由快照
     */
    @Override
    public void save(SessionRoute route) {
        withWriteLock(index -> {
            Map<String, Object> routing = asMap(index.get(route.routingKey()));
            if (routing == null) {
                routing = new LinkedHashMap<>();
                routing.put("active_session_id", route.activeSessionId());
                routing.put("sessions", new ArrayList<>());
                index.put(route.routingKey(), routing);
            }
            List<Object> sessions = mutableList(routing.get("sessions"));
            Map<String, Object> session = sessions.stream()
                    .map(FileSessionRouteRepository::asMap)
                    .filter(candidate -> candidate != null && route.activeSessionId().equals(candidate.get("id")))
                    .findFirst()
                    .orElseGet(() -> {
                        Map<String, Object> created = new LinkedHashMap<>();
                        sessions.add(created);
                        return created;
                    });
            session.put("id", route.activeSessionId());
            session.put("created_at", route.createdAt().toString());
            session.put("verbose", route.verbose());
            session.put("message_count", route.messageCount());
            routing.put("active_session_id", route.activeSessionId());
            routing.put("sessions", sessions);
        });
    }

    /**
     * 在文件锁内删除整个 routing key 映射。
     *
     * @param routingKey 业务路由键
     */
    @Override
    public void delete(String routingKey) {
        withWriteLock(index -> index.remove(routingKey));
    }

    /**
     * 在文件锁内把路由索引原子替换为空对象。
     */
    @Override
    public void clearAll() {
        withWriteLock(Map::clear);
    }

    /**
     * 从单个 routing JSON 对象中解析当前 active session。
     *
     * @param routingKey 业务路由键
     * @param routing routing JSON 对象
     * @return 可解析的当前活跃路由
     */
    private static Optional<SessionRoute> activeRoute(String routingKey, Map<String, Object> routing) {
        if (routing == null) {
            return Optional.empty();
        }
        String activeId = String.valueOf(routing.get("active_session_id"));
        return asList(routing.get("sessions")).stream()
                .map(FileSessionRouteRepository::asMap)
                .filter(session -> session != null && activeId.equals(session.get("id")))
                .findFirst()
                .map(session -> toRoute(routingKey, session));
    }

    /**
     * 将 Python 索引中的 session Map 转为领域快照。
     *
     * @param routingKey 业务路由键
     * @param session session JSON 对象
     * @return 领域路由快照
     */
    private static SessionRoute toRoute(String routingKey, Map<String, Object> session) {
        return new SessionRoute(
                routingKey,
                String.valueOf(session.get("id")),
                Instant.parse(String.valueOf(session.get("created_at"))),
                Boolean.TRUE.equals(session.get("verbose")),
                ((Number) session.getOrDefault("message_count", 0)).intValue()
        );
    }

    /**
     * 在跨进程独占锁中执行一次 read-modify-write。
     *
     * @param mutation 对完整索引的修改动作
     */
    private void withWriteLock(IndexMutation mutation) {
        ensureDirectory();
        try (FileChannel lockChannel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE
        ); FileLock ignored = lockChannel.lock()) {
            Map<String, Object> index = readIndex();
            mutation.apply(index);
            writeIndex(index);
        } catch (IOException exception) {
            throw persistenceFailure("更新 session index 失败", exception);
        }
    }

    /**
     * 读取完整索引；文件不存在或为空时返回空 Map。
     *
     * @return 可修改索引 Map
     * @throws IOException 文件或 JSON 读取失败
     */
    private Map<String, Object> readIndex() throws IOException {
        if (!Files.exists(indexPath) || Files.size(indexPath) == 0) {
            return new LinkedHashMap<>();
        }
        return objectMapper.readValue(indexPath.toFile(), new TypeReference<>() { });
    }

    /**
     * 强制刷盘临时文件后，以原子 move 替换正式索引。
     *
     * @param index 完整索引
     * @throws IOException 序列化或替换失败
     */
    private void writeIndex(Map<String, Object> index) throws IOException {
        Path temporary = indexPath.resolveSibling("index.json.tmp");
        byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(index);
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
            Files.move(temporary, indexPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, indexPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 创建 sessions 目录，并移除上次崩溃留下的固定临时文件。
     */
    private void ensureDirectory() {
        try {
            Files.createDirectories(indexPath.getParent());
        } catch (IOException exception) {
            throw persistenceFailure("创建 sessions 目录失败", exception);
        }
    }

    /**
     * 安全转换 Jackson 解析出的 JSON 对象。
     *
     * @param value 任意 JSON 值
     * @return Map 或空
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /**
     * 安全读取不可修改使用的 JSON 数组。
     *
     * @param value 任意 JSON 值
     * @return 数组或空列表
     */
    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /**
     * 获取 JSON 数组的可修改副本。
     *
     * @param value 任意 JSON 值
     * @return 可修改数组
     */
    private static List<Object> mutableList(Object value) {
        return new ArrayList<>(asList(value));
    }

    /**
     * 统一包装文件持久化异常，避免向领域端口暴露 IOException。
     *
     * @param message 失败摘要
     * @param cause 原始异常
     * @return 非受检持久化异常
     */
    private static IllegalStateException persistenceFailure(String message, Exception cause) {
        return new IllegalStateException(message, cause);
    }

    /**
     * 表示一次受文件锁保护的索引修改。
     */
    @FunctionalInterface
    private interface IndexMutation {

        /**
         * 修改内存中的完整索引。
         *
         * @param index 可修改索引
         */
        void apply(Map<String, Object> index);
    }
}
