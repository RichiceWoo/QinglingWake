package cn.org.chris.wake.starter.contract;

import cn.org.chris.wake.adapter.api.dto.TestRequest;
import cn.org.chris.wake.adapter.api.dto.TestResponse;
import cn.org.chris.wake.adapter.feishu.FeishuEventConverter;
import cn.org.chris.wake.domain.model.CronJob;
import cn.org.chris.wake.domain.model.InboundMessage;
import cn.org.chris.wake.domain.model.SessionRoute;
import cn.org.chris.wake.domain.quality.SelfScoreCalculator;
import cn.org.chris.wake.infra.persistence.cron.FileCronJobRepository;
import cn.org.chris.wake.infra.persistence.session.FileSessionRouteRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.channel.model.NormalizedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用由 Python 原实现生成的固定 fixture 验证跨语言字段与持久化契约。
 */
class PythonBaselineContractIT {

    /** JUnit 为文件契约提供的隔离目录。 */
    @TempDir
    Path temporaryDirectory;

    /** 读取 fixture 和持久化 JSON 的统一编码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Python 路由函数与入站 dataclass 应和 Java 飞书转换结果逐字段一致。
     *
     * @throws Exception fixture 读取失败
     */
    @Test
    void shouldMatchPythonRoutingAndInboundMessageContract() throws Exception {
        JsonNode baseline = baseline();
        FeishuEventConverter converter = new FeishuEventConverter(Set.of());
        for (JsonNode route : baseline.path("routing")) {
            InboundMessage inbound = converter.convert(message(
                    route.path("chat_type").asText(),
                    route.path("sender_id").asText(),
                    route.path("chat_id").asText(),
                    nullableText(route.path("thread_id"))
            )).orElseThrow();
            assertThat(inbound.routingKey()).isEqualTo(route.path("expected").asText());
        }

        JsonNode expected = baseline.path("inbound_message");
        InboundMessage inbound = converter.convert(new NormalizedMessage(
                expected.path("msg_id").asText(),
                "oc_group",
                "group",
                expected.path("sender_id").asText(),
                "用户",
                expected.path("content").asText(),
                "file",
                List.of(new com.lark.oapi.channel.model.ResourceDescriptor(
                        "file",
                        expected.path("attachment").path("file_key").asText(),
                        expected.path("attachment").path("file_name").asText(),
                        null
                )),
                List.of(), false, false,
                expected.path("root_id").asText(),
                "omt_thread", "", expected.path("ts").asLong(), null
        )).orElseThrow();

        assertThat(inbound.routingKey()).isEqualTo(expected.path("routing_key").asText());
        assertThat(inbound.content()).isEqualTo(expected.path("content").asText());
        assertThat(inbound.msgId()).isEqualTo(expected.path("msg_id").asText());
        assertThat(inbound.rootId()).isEqualTo(expected.path("root_id").asText());
        assertThat(inbound.timestampMs()).isEqualTo(expected.path("ts").asLong());
        assertThat(inbound.attachment().msgType()).isEqualTo(expected.path("attachment").path("msg_type").asText());
        assertThat(inbound.meta()).containsEntry("source", "feishu");
    }

    /**
     * Java Repository 应直接读取 Python session index 和 at/every/cron tasks JSON。
     *
     * @throws Exception 临时 fixture 写入或读取失败
     */
    @Test
    void shouldReadPythonSessionAndTaskStores() throws Exception {
        JsonNode baseline = baseline();
        Path dataDirectory = temporaryDirectory.resolve("data");
        Path sessionIndex = dataDirectory.resolve("sessions/index.json");
        Files.createDirectories(sessionIndex.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                sessionIndex.toFile(), baseline.path("session_index")
        );

        SessionRoute route = new FileSessionRouteRepository(dataDirectory, objectMapper)
                .find("p2p:ou_contract").orElseThrow();
        assertThat(route.activeSessionId()).isEqualTo("s-contract002");
        assertThat(route.verbose()).isTrue();
        assertThat(route.messageCount()).isZero();

        Path tasksPath = dataDirectory.resolve("cron/tasks.json");
        Files.createDirectories(tasksPath.getParent());
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("version", 1);
        store.put("jobs", objectMapper.convertValue(baseline.path("tasks"), new TypeReference<List<Object>>() { }));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tasksPath.toFile(), store);

        List<CronJob> jobs = new FileCronJobRepository(tasksPath, objectMapper).findAll();
        assertThat(jobs).extracting(job -> job.schedule().kind())
                .containsExactly(CronJob.ScheduleKind.AT, CronJob.ScheduleKind.EVERY, CronJob.ScheduleKind.CRON);
        assertThat(jobs).extracting(job -> job.payload().routingKey())
                .containsExactly("team:pm", "team:rd", "team:qa");
    }

    /**
     * Python 五维权重和 TestAPI 默认字段应保持稳定，避免 Java 自测自洽但跨语言漂移。
     *
     * @throws Exception fixture 读取失败
     */
    @Test
    void shouldMatchPythonSelfScoreAndTestApiSchema() throws Exception {
        JsonNode baseline = baseline();
        Map<String, Double> breakdown = objectMapper.convertValue(
                baseline.path("self_score").path("breakdown"), new TypeReference<>() { }
        );
        assertThat(SelfScoreCalculator.compute(breakdown))
                .isEqualTo(baseline.path("self_score").path("expected").asDouble());

        TestRequest request = objectMapper.treeToValue(
                baseline.path("test_api").path("request_defaults"), TestRequest.class
        );
        assertThat(request.routingKey()).isEqualTo("p2p:ou_contract");
        assertThat(request.content()).isEmpty();
        assertThat(request.senderId()).isEqualTo("ou_test001");
        List<String> responseFields = objectMapper.convertValue(
                baseline.path("test_api").path("response_fields"), new TypeReference<>() { }
        );
        assertThat(responseFields)
                .containsExactly("msg_id", "reply", "session_id", "duration_ms", "skills_called");
        assertThat(TestResponse.class.getRecordComponents()).hasSize(5);
    }

    /**
     * fixture 必须记录完整源目录和 11 个源文件摘要；源快照存在时同时验证摘要未漂移。
     *
     * @throws Exception fixture 或源文件读取失败
     */
    @Test
    void shouldKeepPythonFixtureSourceTraceable() throws Exception {
        JsonNode metadata = baseline().path("_meta");
        assertThat(metadata.path("source_commit").isNull()).isTrue();
        assertThat(metadata.path("source_note").asText()).contains("无 .git 元数据", "SHA-256");
        assertThat(metadata.path("sha256").size()).isEqualTo(11);

        Path sourceRoot = Path.of(metadata.path("source_directory").asText());
        if (Files.isDirectory(sourceRoot)) {
            var fields = metadata.path("sha256").fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                assertThat(fileSha256(sourceRoot.resolve(entry.getKey())))
                        .as("Python 源快照文件 %s", entry.getKey())
                        .isEqualTo(entry.getValue().asText());
            }
        }
    }

    /** 构造只用于路由比较的飞书 SDK 归一化消息。 */
    private static NormalizedMessage message(
            String chatType,
            String senderId,
            String chatId,
            String threadId
    ) {
        return new NormalizedMessage(
                "om_contract", chatId, chatType, senderId, "用户", "内容", "text",
                List.of(), List.of(), false, false, "", threadId, "", 1L, null
        );
    }

    /** 将 JSON null 转为空字符串以模拟 SDK 的可空 threadId。 */
    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 读取 classpath 中已提交的 Python 基线。 */
    private JsonNode baseline() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/contract/python-baseline.json")) {
            assertThat(input).as("Task 17 Python 基线 fixture").isNotNull();
            return objectMapper.readTree(input);
        }
    }

    /** 计算单个源文件的十六进制 SHA-256。 */
    private static String fileSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
