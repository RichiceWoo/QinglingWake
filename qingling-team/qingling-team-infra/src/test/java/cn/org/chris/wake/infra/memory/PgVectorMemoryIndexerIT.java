package cn.org.chris.wake.infra.memory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 PostgreSQL pgvector 容器验证建表、双向量和幂等写入。
 */
@Testcontainers(disabledWithoutDocker = true)
class PgVectorMemoryIndexerIT {

    /** 提供预装 vector 扩展的 PostgreSQL 16 测试容器。 */
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16")
            .withDatabaseName("qingling_memory")
            .withUsername("qingling")
            .withPassword("qingling-test");

    /**
     * 在集成测试数据库中显式启用扩展并执行可选建表脚本。
     */
    @BeforeAll
    static void initializeSchema() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            for (String sql : schemaSql().split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    /**
     * 同一轮写入两次应只保留一行，且两份向量均固定为 1024 维。
     */
    @Test
    void shouldInsertTwoVectorsAndIgnoreDuplicate() throws Exception {
        PgVectorMemoryIndexer indexer = new PgVectorMemoryIndexer(
                jdbcDsn(), new FixedExtractionClient()
        );

        PgVectorMemoryIndexer.IndexResult first = indexer.indexTurn(
                "session-it", "p2p:user-it", "集成测试问题", "集成测试回复", 1_700_000_000_000L
        ).join();
        PgVectorMemoryIndexer.IndexResult duplicate = indexer.indexTurn(
                "session-it", "p2p:user-it", "集成测试问题", "集成测试回复", 1_700_000_000_000L
        ).join();

        assertThat(first.status()).isEqualTo(PgVectorMemoryIndexer.IndexStatus.INSERTED);
        assertThat(duplicate.status()).isEqualTo(PgVectorMemoryIndexer.IndexStatus.DUPLICATE);
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT count(*) AS row_count,
                            min(vector_dims(summary_vec)) AS summary_dims,
                            min(vector_dims(message_vec)) AS message_dims
                       FROM memories
                      WHERE id = '09fab7e01fbbee7a'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("row_count")).isEqualTo(1);
            assertThat(result.getInt("summary_dims")).isEqualTo(1024);
            assertThat(result.getInt("message_dims")).isEqualTo(1024);
        }
    }

    /**
     * 创建带测试用户名和密码的 JDBC 连接。
     */
    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * 生成供索引器直接交给 DriverManager 的完整 JDBC DSN。
     */
    private static String jdbcDsn() {
        return POSTGRES.getJdbcUrl()
                + "?user=" + POSTGRES.getUsername()
                + "&password=" + POSTGRES.getPassword();
    }

    /**
     * 从 classpath 读取生产使用的可选 pgvector 建表脚本。
     */
    private static String schemaSql() throws IOException {
        try (InputStream input = PgVectorMemoryIndexerIT.class.getResourceAsStream(
                "/db/optional/pgvector-memories.sql"
        )) {
            if (input == null) {
                throw new IOException("找不到 pgvector 建表脚本");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 返回固定摘要、标签和两份合法向量，避免集成测试访问外部模型。
     */
    private static final class FixedExtractionClient implements MemoryExtractionPort {

        /**
         * 返回稳定摘要与标签。
         */
        @Override
        public MemoryExtractionClient.Extraction extract(String userMessage, String assistantReply) {
            return new MemoryExtractionClient.Extraction("集成测试摘要", List.of("Java", "pgvector"));
        }

        /**
         * 为每个输入文本创建一份 1024 维有限向量。
         */
        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(ignored -> {
                float[] vector = new float[MemoryExtractionClient.EMBEDDING_DIMENSION];
                vector[0] = 0.25f;
                return vector;
            }).toList();
        }
    }
}
