package cn.org.chris.wake.infra.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 在独立执行器中提取单轮记忆并以幂等方式写入 PostgreSQL pgvector 表。
 */
public final class PgVectorMemoryIndexer implements TurnMemoryIndexer {

    /** 索引失败告警只记录会话、时间与异常类型，不记录 DSN、密钥或对话正文。 */
    private static final Logger LOGGER = Logger.getLogger(PgVectorMemoryIndexer.class.getName());

    /** 与 Python 基线一致的幂等写入 SQL。 */
    private static final String INSERT_SQL = """
            INSERT INTO memories (
                id, session_id, routing_key,
                user_message, assistant_reply,
                summary, tags, turn_ts,
                summary_vec, message_vec, search_text
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?::vector, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    /** 可为空的 PostgreSQL JDBC DSN；空值表示禁用可选记忆索引。 */
    private final String dbDsn;

    /** 摘要、标签与向量提取客户端。 */
    private final MemoryExtractionPort extractionClient;

    /** JDBC 连接创建端口，测试可替换。 */
    private final ConnectionFactory connectionFactory;

    /** 执行网络与 JDBC 阻塞操作的后台执行器。 */
    private final Executor executor;

    /**
     * 使用 DriverManager 和公共工作线程池创建索引器。
     *
     * @param dbDsn PostgreSQL JDBC DSN，空字符串表示关闭
     * @param extractionClient 摘要与向量提取客户端
     */
    public PgVectorMemoryIndexer(String dbDsn, MemoryExtractionPort extractionClient) {
        this(dbDsn, extractionClient, DriverManager::getConnection, ForkJoinPool.commonPool());
    }

    /**
     * 使用可测试连接工厂和执行器创建索引器。
     *
     * @param dbDsn PostgreSQL JDBC DSN，空字符串表示关闭
     * @param extractionClient 摘要与向量提取客户端
     * @param connectionFactory JDBC 连接工厂
     * @param executor 后台执行器
     */
    public PgVectorMemoryIndexer(
            String dbDsn,
            MemoryExtractionPort extractionClient,
            ConnectionFactory connectionFactory,
            Executor executor
    ) {
        this.dbDsn = Objects.requireNonNullElse(dbDsn, "").strip();
        this.extractionClient = Objects.requireNonNull(extractionClient, "extractionClient 不能为空");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory 不能为空");
        this.executor = Objects.requireNonNull(executor, "executor 不能为空");
    }

    /**
     * 触发后台索引但不等待结果，供 AgentGateway 在回复完成时调用。
     */
    @Override
    public void schedule(
            String sessionId,
            String routingKey,
            String userMessage,
            String assistantReply,
            long turnTimestampMs
    ) {
        indexTurn(sessionId, routingKey, userMessage, assistantReply, turnTimestampMs);
    }

    /**
     * 异步索引单轮对话；空 DSN 立即跳过，所有外部失败均转为 FAILED 状态。
     *
     * @return 可用于测试与指标采集的异步结果
     */
    public CompletableFuture<IndexResult> indexTurn(
            String sessionId,
            String routingKey,
            String userMessage,
            String assistantReply,
            long turnTimestampMs
    ) {
        MemoryTurn turn = new MemoryTurn(
                sessionId, routingKey, userMessage, assistantReply, turnTimestampMs
        );
        if (dbDsn.isBlank()) {
            return CompletableFuture.completedFuture(new IndexResult(IndexStatus.SKIPPED, stableId(turn)));
        }
        return CompletableFuture.supplyAsync(() -> indexSynchronously(turn), executor)
                .exceptionally(failure -> {
                    Throwable cause = unwrap(failure);
                    LOGGER.log(
                            Level.WARNING,
                            "pgvector 记忆索引失败，sessionId={0}, turnTs={1}, cause={2}",
                            new Object[]{turn.sessionId(), turn.turnTimestampMs(), cause.getClass().getSimpleName()}
                    );
                    return new IndexResult(IndexStatus.FAILED, stableId(turn));
                });
    }

    /**
     * 在后台线程内完成连接、提取、向量化和单事务幂等写入。
     */
    private IndexResult indexSynchronously(MemoryTurn turn) {
        try (Connection connection = connectionFactory.open(dbDsn)) {
            connection.setAutoCommit(false);
            try {
                MemoryExtractionClient.Extraction extraction = extractionClient.extract(
                        turn.userMessage(), turn.assistantReply()
                );
                String messageText = "用户：" + turn.userMessage() + "\n助手：" + turn.assistantReply();
                List<float[]> vectors = extractionClient.embed(List.of(extraction.summary(), messageText));
                if (vectors.size() != 2) {
                    throw new IllegalStateException("记忆索引必须生成两份向量");
                }
                int affected = insert(connection, turn, extraction, vectors.get(0), vectors.get(1));
                connection.commit();
                return new IndexResult(
                        affected == 0 ? IndexStatus.DUPLICATE : IndexStatus.INSERTED,
                        stableId(turn)
                );
            } catch (Exception failure) {
                rollbackQuietly(connection);
                throw new MemoryIndexException(failure);
            }
        } catch (SQLException failure) {
            throw new MemoryIndexException(failure);
        }
    }

    /**
     * 绑定 JDBC 参数并执行 `ON CONFLICT DO NOTHING` 插入。
     */
    private int insert(
            Connection connection,
            MemoryTurn turn,
            MemoryExtractionClient.Extraction extraction,
            float[] summaryVector,
            float[] messageVector
    ) throws SQLException {
        validateVector(summaryVector, "summaryVector");
        validateVector(messageVector, "messageVector");
        Array tags = connection.createArrayOf("text", extraction.tags().toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            statement.setString(1, stableId(turn));
            statement.setString(2, turn.sessionId());
            statement.setString(3, turn.routingKey());
            statement.setString(4, turn.userMessage());
            statement.setString(5, turn.assistantReply());
            statement.setString(6, extraction.summary());
            statement.setArray(7, tags);
            statement.setTimestamp(8, Timestamp.from(Instant.ofEpochMilli(turn.turnTimestampMs())));
            statement.setString(9, vectorLiteral(summaryVector));
            statement.setString(10, vectorLiteral(messageVector));
            statement.setString(11, searchText(turn.userMessage(), extraction.tags()));
            return statement.executeUpdate();
        } finally {
            tags.free();
        }
    }

    /**
     * 生成与 Python `str(list)` 兼容的 pgvector 文本字面量。
     */
    private static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 8).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(vector[index]));
        }
        return literal.append(']').toString();
    }

    /**
     * 确保写入数据库的每份向量均为 1024 维有限浮点数。
     */
    private static void validateVector(float[] vector, String name) {
        if (vector == null || vector.length != MemoryExtractionClient.EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException(name + " 必须为 1024 维");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " 包含非有限数值");
            }
        }
    }

    /**
     * 拼接用户消息和标签，供 PostgreSQL GIN 全文索引使用。
     */
    private static String searchText(String userMessage, List<String> tags) {
        if (tags.isEmpty()) {
            return userMessage;
        }
        return userMessage + " " + String.join(" ", tags);
    }

    /**
     * 精确复刻 Python 稳定 ID：SHA-256 输入的前 16 个十六进制字符。
     */
    static String stableId(MemoryTurn turn) {
        String raw = turn.sessionId() + "_" + turn.turnTimestampMs() + "_"
                + MemoryExtractionClient.limitCodePoints(turn.userMessage(), 32);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    /**
     * 索引失败时尽力回滚，回滚异常不得覆盖原始失败。
     */
    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 原始索引异常更能反映失败原因，回滚异常只做静默降级。
        }
    }

    /**
     * 去除 CompletableFuture 包装，日志仅记录真实异常类型。
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof MemoryIndexException)) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * JDBC 连接创建端口。
     */
    @FunctionalInterface
    public interface ConnectionFactory {

        /** 使用配置的 DSN 打开连接。 */
        Connection open(String dsn) throws SQLException;
    }

    /** 描述索引操作最终状态。 */
    public enum IndexStatus {
        /** DSN 为空，未访问模型或数据库。 */
        SKIPPED,
        /** 成功插入一条新记忆。 */
        INSERTED,
        /** 稳定 ID 已存在，幂等跳过。 */
        DUPLICATE,
        /** 模型、向量或数据库调用失败并已降级。 */
        FAILED
    }

    /**
     * 单轮索引结果。
     *
     * @param status 最终状态
     * @param memoryId 稳定幂等标识
     */
    public record IndexResult(
            /** 索引操作最终状态。 */ IndexStatus status,
            /** 稳定幂等标识。 */ String memoryId
    ) {
    }

    /**
     * 单轮对话索引输入。
     *
     * @param sessionId AgentScope 会话标识
     * @param routingKey 外部或团队路由键
     * @param userMessage 用户消息
     * @param assistantReply 助手回复
     * @param turnTimestampMs 本轮开始时间，Unix 毫秒
     */
    record MemoryTurn(
            /** AgentScope 会话标识。 */ String sessionId,
            /** 外部或团队路由键。 */ String routingKey,
            /** 本轮用户消息。 */ String userMessage,
            /** 本轮助手回复。 */ String assistantReply,
            /** 本轮开始时间，Unix 毫秒。 */ long turnTimestampMs
    ) {
        /**
         * 标准化文本并校验幂等键所需字段。
         */
        MemoryTurn {
            sessionId = requireText(sessionId, "sessionId");
            routingKey = requireText(routingKey, "routingKey");
            userMessage = Objects.requireNonNullElse(userMessage, "");
            assistantReply = Objects.requireNonNullElse(assistantReply, "");
            if (turnTimestampMs < 0) {
                throw new IllegalArgumentException("turnTimestampMs 不能为负数");
            }
        }
    }

    /**
     * 隐藏外部异常正文的索引失败包装。
     */
    private static final class MemoryIndexException extends RuntimeException {

        /**
         * 对外消息只保留异常类型，同时保留 cause 供内部日志识别真实类型。
         */
        private MemoryIndexException(Throwable cause) {
            super(cause == null ? "Unknown" : cause.getClass().getSimpleName(), cause);
        }
    }

    /**
     * 校验稳定 ID 所需文本非空。
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
