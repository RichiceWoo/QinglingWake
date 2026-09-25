package cn.org.chris.wake.infra.persistence.session;

import cn.org.chris.wake.domain.model.ConversationAuditEntry;
import cn.org.chris.wake.domain.model.SessionRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 session index 原子更新与只写 JSONL 审计契约。
 */
class FileSessionPersistenceTest {

    /** JUnit 提供的隔离数据目录。 */
    @TempDir
    Path dataDirectory;

    /**
     * 新 session 应追加到 Python 兼容 sessions 数组并切换 active id。
     *
     * @throws Exception 测试文件读取失败
     */
    @Test
    void shouldPreservePreviousSessionsWhenSwitchingActiveRoute() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FileSessionRouteRepository repository = new FileSessionRouteRepository(dataDirectory, mapper);
        repository.save(new SessionRoute("p2p:u1", "s-first", Instant.EPOCH, false, 2));
        repository.save(new SessionRoute("p2p:u1", "s-second", Instant.ofEpochSecond(1), true, 0));

        SessionRoute active = repository.find("p2p:u1").orElseThrow();
        String json = Files.readString(dataDirectory.resolve("sessions/index.json"));

        assertThat(active.activeSessionId()).isEqualTo("s-second");
        assertThat(active.verbose()).isTrue();
        assertThat(json).contains("active_session_id", "s-first", "s-second", "message_count");
        assertThat(Files.exists(dataDirectory.resolve("sessions/index.json.tmp"))).isFalse();
    }

    /**
     * 审计 append 应写出完整 meta 和两条消息行，并保持来源消息字段兼容。
     *
     * @throws Exception 测试文件读取失败
     */
    @Test
    void shouldAppendDurableAuditLinesWithoutHistoryLoader() throws Exception {
        JsonlConversationAuditRepository repository = new JsonlConversationAuditRepository(
                dataDirectory, new ObjectMapper()
        );
        repository.initialize("s-audit", "p2p:u1", Instant.EPOCH);
        repository.append("s-audit", List.of(
                new ConversationAuditEntry("user", "问题", 1000L, "om-1"),
                new ConversationAuditEntry("assistant", "回答", 1000L, null)
        ));

        List<String> lines = Files.readAllLines(dataDirectory.resolve("sessions/s-audit.jsonl"));

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("\"type\":\"meta\"");
        assertThat(lines.get(1)).contains("\"role\":\"user\"", "\"feishu_msg_id\":\"om-1\"");
        assertThat(lines.get(2)).contains("\"role\":\"assistant\"");
    }
}
