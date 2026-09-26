package cn.org.chris.wake.starter.cli;

import cn.org.chris.wake.app.observability.LogQueryService;
import cn.org.chris.wake.infra.observability.JsonlLogQueryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 以 Python fixture 形状验证日志查询 CLI JSON 契约。
 */
class LogQueryCommandTest {

    /** 临时日志根目录。 */
    @TempDir
    Path temporaryDirectory;

    /** JSON 比较器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 验证 stats、tasks、l1 与 all-agents 的 Python 字段和计算规则。
     */
    @Test
    void shouldMatchPythonQueryContract() throws Exception {
        Path l2 = temporaryDirectory.resolve("shared/projects/demo/logs/l2_task/tasks.jsonl");
        Path l1 = temporaryDirectory.resolve("shared/logs/l1_human/feedback.jsonl");
        Files.createDirectories(l2.getParent());
        Files.createDirectories(l1.getParent());
        Files.writeString(l2, """
                {"task_id":"t001","ts":"2026-09-25T01:00:00+00:00","role":"pm","result_quality_estimate":0.4,"task_desc":"a"}
                broken
                {"task_id":"t002","ts":"2026-09-25T02:00:00+00:00","role":"pm","result_quality_estimate":0.85,"task_desc":"b"}
                {"task_id":"t003","ts":"2026-09-25T03:00:00+00:00","role":"qa","result_quality_estimate":0.9,"task_desc":"c"}
                """, StandardCharsets.UTF_8);
        Files.writeString(l1, """
                {"ts":"2026-09-25T04:00:00+00:00","content":{"classified_as":"rejected","user_text":"移动端需要修改"}}
                """, StandardCharsets.UTF_8);

        JsonNode stats = execute("stats", "--logs-root", temporaryDirectory.toString(), "--agent-id", "pm");
        assertThat(stats.get("task_count").asInt()).isEqualTo(2);
        assertThat(stats.get("avg_quality").asDouble()).isEqualTo(0.625D);
        assertThat(stats.get("failure_count").asInt()).isEqualTo(1);
        assertThat(stats.get("human_correction_count").asInt()).isEqualTo(1);

        JsonNode tasks = execute("tasks", "--logs-root", temporaryDirectory.toString(), "--agent-id", "pm",
                "--sort", "quality_asc", "--limit", "1");
        assertThat(tasks.at("/tasks/0/task_id").asText()).isEqualTo("t001");
        assertThat(tasks.at("/tasks/0/artifacts_produced").isArray()).isTrue();

        JsonNode l1Result = execute("l1", "--logs-root", temporaryDirectory.toString(),
                "--keyword", "移动端");
        assertThat(l1Result.get("entries").size()).isEqualTo(1);

        JsonNode allAgents = execute("all-agents", "--logs-root", temporaryDirectory.toString());
        assertThat(allAgents.get("bottleneck").asText()).isEqualTo("pm");
        assertThat(allAgents.at("/per_agent/qa/task_count").asInt()).isEqualTo(1);
    }

    /**
     * 验证 steps 过滤失败步骤并跳过损坏 JSONL 行。
     */
    @Test
    void shouldFilterFailedSteps() throws Exception {
        Path session = temporaryDirectory.resolve("raw.jsonl");
        Files.writeString(session, """
                {"task_id":"t1","step":1,"failed":false}
                broken
                {"task_id":"t1","step":2,"failed":true}
                {"task_id":"t2","step":1,"failed":true}
                """, StandardCharsets.UTF_8);

        JsonNode result = execute("steps", "--session-raw", session.toString(),
                "--task-id", "t1", "--only-failed");

        assertThat(result.get("only_failed").asBoolean()).isTrue();
        assertThat(result.get("steps").size()).isEqualTo(1);
        assertThat(result.at("/steps/0/step").asInt()).isEqualTo(2);

        JsonNode missing = execute("steps", "--session-raw", temporaryDirectory.resolve("missing.jsonl").toString(),
                "--task-id", "t1", "--only-failed");
        assertThat(missing.has("only_failed")).isFalse();
        assertThat(missing.get("steps").size()).isZero();
    }

    /** 使用固定 UTC 时钟执行命令并解析 stdout。 */
    private JsonNode execute(String... arguments) throws Exception {
        LogQueryService service = new LogQueryService(
                new JsonlLogQueryRepository(objectMapper),
                Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC)
        );
        LogQueryCommand command = new LogQueryCommand(service, objectMapper);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = command.execute(arguments, new PrintStream(stdout), new PrintStream(stderr));
        assertThat(code).as(stderr.toString(StandardCharsets.UTF_8)).isZero();
        return objectMapper.readTree(stdout.toString(StandardCharsets.UTF_8));
    }
}
