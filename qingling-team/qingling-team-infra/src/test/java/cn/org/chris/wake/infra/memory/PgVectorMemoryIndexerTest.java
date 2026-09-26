package cn.org.chris.wake.infra.memory;

import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 pgvector 索引器的非阻塞、幂等 ID、双向量写入和失败降级。
 */
class PgVectorMemoryIndexerTest {

    /**
     * 空 DSN 应立即跳过且完全不访问模型或数据库。
     */
    @Test
    void shouldSkipWithoutDsn() throws Exception {
        FakeExtraction extraction = new FakeExtraction();
        PgVectorMemoryIndexer.ConnectionFactory connections = mock(PgVectorMemoryIndexer.ConnectionFactory.class);
        PgVectorMemoryIndexer indexer = new PgVectorMemoryIndexer("", extraction, connections, Runnable::run);

        PgVectorMemoryIndexer.IndexResult result = indexer.indexTurn(
                "session-1", "p2p:user", "hello", "world", 1L
        ).join();

        assertThat(result.status()).isEqualTo(PgVectorMemoryIndexer.IndexStatus.SKIPPED);
        assertThat(extraction.extractCalls).isZero();
        verify(connections, never()).open(anyString());
    }

    /**
     * 调用应先返回未完成 Future，再由后台执行器完成 JDBC 写入。
     */
    @Test
    void shouldScheduleWithoutBlockingAndInsertTwoVectors() throws Exception {
        JdbcFixture jdbc = new JdbcFixture(1);
        FakeExtraction extraction = new FakeExtraction();
        QueuedExecutor executor = new QueuedExecutor();
        PgVectorMemoryIndexer indexer = new PgVectorMemoryIndexer(
                "jdbc:postgresql://db/memory", extraction, ignored -> jdbc.connection, executor
        );

        CompletableFuture<PgVectorMemoryIndexer.IndexResult> future = indexer.indexTurn(
                "session-1", "p2p:user", "用户问题", "助手回答", 1_700_000_000_000L
        );

        assertThat(future).isNotDone();
        assertThat(extraction.extractCalls).isZero();

        executor.runNext();
        assertThat(future.join().status()).isEqualTo(PgVectorMemoryIndexer.IndexStatus.INSERTED);
        assertThat(extraction.embeddedTexts).containsExactly("摘要", "用户：用户问题\n助手：助手回答");
        verify(jdbc.statement).setString(1, "7b25d00408da54da");
        verify(jdbc.statement).setString(11, "用户问题 Java pgvector");
        verify(jdbc.connection).commit();
    }

    /**
     * 同一输入必须生成稳定 ID，数据库返回零影响行时标记为 DUPLICATE。
     */
    @Test
    void shouldReturnStableIdAndDuplicateStatus() throws Exception {
        JdbcFixture jdbc = new JdbcFixture(0);
        PgVectorMemoryIndexer indexer = new PgVectorMemoryIndexer(
                "jdbc:postgresql://db/memory", new FakeExtraction(),
                ignored -> jdbc.connection, Runnable::run
        );
        String user = "这是超过三十二字符的用户消息abcdefghijklmnopqrstuvwxyz";

        PgVectorMemoryIndexer.IndexResult result = indexer.indexTurn(
                "session-1", "p2p:user", user, "reply", 1_700_000_000_000L
        ).join();

        assertThat(result.status()).isEqualTo(PgVectorMemoryIndexer.IndexStatus.DUPLICATE);
        assertThat(result.memoryId()).isEqualTo("e1f769ee4260de99");
    }

    /**
     * 数据库不可用时 Future 应正常完成 FAILED，不能把异常传播给主调用。
     */
    @Test
    void shouldDegradeWhenDatabaseIsUnavailable() {
        PgVectorMemoryIndexer indexer = new PgVectorMemoryIndexer(
                "jdbc:postgresql://secret-user:secret-pass@db/memory",
                new FakeExtraction(),
                ignored -> {
                    throw new SQLException("secret-pass");
                },
                Runnable::run
        );

        PgVectorMemoryIndexer.IndexResult result = indexer.indexTurn(
                "session-1", "p2p:user", "user", "reply", 10L
        ).join();

        assertThat(result.status()).isEqualTo(PgVectorMemoryIndexer.IndexStatus.FAILED);
    }

    /**
     * 返回固定摘要、标签与两份 1024 维向量的测试提取器。
     */
    private static final class FakeExtraction implements MemoryExtractionPort {

        /** 摘要提取调用次数。 */
        private int extractCalls;

        /** 最近一次批量向量化文本。 */
        private List<String> embeddedTexts = List.of();

        /**
         * 返回确定性摘要与标签。
         */
        @Override
        public MemoryExtractionClient.Extraction extract(String userMessage, String assistantReply) {
            extractCalls++;
            return new MemoryExtractionClient.Extraction("摘要", List.of("Java", "pgvector"));
        }

        /**
         * 捕获输入并返回两份合法向量。
         */
        @Override
        public List<float[]> embed(List<String> texts) {
            embeddedTexts = List.copyOf(texts);
            float[] summary = new float[MemoryExtractionClient.EMBEDDING_DIMENSION];
            float[] message = new float[MemoryExtractionClient.EMBEDDING_DIMENSION];
            summary[0] = 0.1f;
            message[0] = 0.2f;
            return List.of(summary, message);
        }
    }

    /**
     * 保存一次 JDBC mock 事务。
     */
    private static final class JdbcFixture {

        /** JDBC 连接 mock。 */
        private final Connection connection = mock(Connection.class);

        /** PreparedStatement mock。 */
        private final PreparedStatement statement = mock(PreparedStatement.class);

        /** PostgreSQL text[] mock。 */
        private final Array tags = mock(Array.class);

        /**
         * 配置 executeUpdate 返回影响行数。
         */
        private JdbcFixture(int affectedRows) throws SQLException {
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(connection.createArrayOf(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(tags);
            when(statement.executeUpdate()).thenReturn(affectedRows);
        }
    }

    /**
     * 仅在测试显式推进时运行任务的执行器。
     */
    private static final class QueuedExecutor implements Executor {

        /** 尚未运行的任务队列。 */
        private final List<Runnable> tasks = new ArrayList<>();

        /**
         * 把任务加入内存队列。
         */
        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        /**
         * 运行并移除首个排队任务。
         */
        private void runNext() {
            tasks.remove(0).run();
        }
    }
}
