package cn.org.chris.wake.adapter.api;

import cn.org.chris.wake.app.session.SessionRoutingService;
import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.gateway.ConversationAuditRepository;
import cn.org.chris.wake.domain.gateway.CronDispatchGateway;
import cn.org.chris.wake.domain.gateway.SessionRouteRepository;
import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import cn.org.chris.wake.domain.model.ConversationAuditEntry;
import cn.org.chris.wake.domain.model.InboundMessage;
import cn.org.chris.wake.domain.model.SessionRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 TestAPI 的 Python golden、错误状态、附件、超时与会话清理契约。
 */
class TestApiControllerTest {

    /** JUnit 提供的隔离 workspace。 */
    @TempDir
    Path workspaceDirectory;

    /** 捕获测试出站回复。 */
    private CaptureSender captureSender;

    /** 内存路由仓库。 */
    private MemoryRouteRepository routeRepository;

    /** 内存审计仓库。 */
    private MemoryAuditRepository auditRepository;

    /** 捕获 AgentScope 清理参数。 */
    private CapturingAgentGateway agentGateway;

    /** 使用固定 sessionId 的路由服务。 */
    private SessionRoutingService sessionRoutingService;

    /** 最近一次分发的入站消息。 */
    private InboundMessage lastInbound;

    /** JSON golden 比较使用的序列化器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 为每个测试创建无外部依赖的路由和 CaptureSender 环境。
     */
    @BeforeEach
    void setUp() {
        captureSender = new CaptureSender();
        routeRepository = new MemoryRouteRepository();
        auditRepository = new MemoryAuditRepository();
        agentGateway = new CapturingAgentGateway();
        sessionRoutingService = new SessionRoutingService(
                routeRepository,
                auditRepository,
                agentGateway,
                () -> "s-test001",
                Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    /**
     * 缺省请求应与 Python golden 响应字段、默认 senderId 和 msgId 保持一致。
     */
    @Test
    void shouldMatchPythonDefaultGoldenContract() throws Exception {
        MockMvc mvc = mvc(replyingDispatcher("完成"), Duration.ofSeconds(1));
        String request = resourceText("/golden/task14/default-request.json");

        MvcResult result = mvc.perform(post("/api/test/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();

        ObjectNode actual = (ObjectNode) objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)
        );
        actual.put("duration_ms", 0);
        JsonNode expected = objectMapper.readTree(resourceText("/golden/task14/default-response.json"));
        assertThat(actual).isEqualTo(expected);
        assertThat(lastInbound.senderId()).isEqualTo("ou_test001");
        assertThat(lastInbound.content()).isEmpty();
        assertThat(lastInbound.rootId()).isEqualTo("test_123456789abc");
    }

    /**
     * 非法 JSON 返回 400，缺少 routing_key 返回 422 字段错误。
     */
    @Test
    void shouldReturn400And422ForInvalidRequests() throws Exception {
        MockMvc mvc = mvc(replyingDispatcher("unused"), Duration.ofSeconds(1));

        MvcResult malformed = mvc.perform(post("/api/test/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andReturn();

        MvcResult missing = mvc.perform(post("/api/test/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        assertThat(responseJson(malformed).path("error").asText()).isEqualTo("Invalid JSON body");
        assertThat(responseJson(missing).path("error").path(0).path("loc").path(0).asText())
                .isEqualTo("routing_key");
    }

    /**
     * 本地附件应复制到 session uploads，并把入站正文改写为沙盒路径提示。
     */
    @Test
    void shouldCopyAttachmentAndRewriteInboundContent() throws Exception {
        Path source = workspaceDirectory.resolve("source.txt");
        Files.writeString(source, "附件内容", StandardCharsets.UTF_8);
        MockMvc mvc = mvc(replyingDispatcher("已处理"), Duration.ofSeconds(1));
        String json = """
                {
                  "routing_key": "p2p:ou_test001",
                  "content": "请分析",
                  "attachment": {
                    "file_path": "%s",
                    "file_name": "renamed.txt"
                  }
                }
                """.formatted(source.toString().replace("\\", "\\\\"));

        mvc.perform(post("/api/test/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        Path copied = workspaceDirectory.resolve("sessions/s-test001/uploads/renamed.txt");
        assertThat(Files.readString(copied)).isEqualTo("附件内容");
        assertThat(lastInbound.attachment()).isNull();
        assertThat(lastInbound.content()).isEqualTo("""
                用户发来了文件，已自动保存至沙盒路径：
                `/workspace/sessions/s-test001/uploads/renamed.txt`
                请根据文件内容和用户意图完成相应处理。
                （用户备注：请分析）""");
    }

    /**
     * Dispatcher 未产生回复时应按 Python 语义返回 500，并释放 CaptureSender 注册项。
     */
    @Test
    void shouldReturnGatewayTimeoutAndReleaseRegistration() throws Exception {
        MockMvc mvc = mvc(message -> CompletableFuture.completedFuture(null), Duration.ofMillis(5));

        MvcResult result = mvc.perform(post("/api/test/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routing_key\":\"p2p:u1\",\"msg_id\":\"timeout-1\"}"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertThat(responseJson(result).path("error").asText()).isEqualTo("Timed out waiting for Bot reply");
        assertThat(captureSender.pendingCount()).isZero();
    }

    /**
     * DELETE 应清理路由、审计和已知测试用户的 AgentScope state。
     */
    @Test
    void shouldDeleteAllTestSessionsAndAgentState() throws Exception {
        MockMvc mvc = mvc(replyingDispatcher("完成"), Duration.ofSeconds(1));
        mvc.perform(post("/api/test/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routing_key\":\"p2p:u1\",\"sender_id\":\"sender-1\"}"))
                .andExpect(status().isOk());

        MvcResult deleted = mvc.perform(delete("/api/test/sessions"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(responseJson(deleted).path("status").asText()).isEqualTo("ok");
        assertThat(routeRepository.routes).isEmpty();
        assertThat(auditRepository.cleared).isTrue();
        assertThat(agentGateway.cleared).containsExactly("manager", "sender-1", "s-test001");
    }

    /**
     * 创建会捕获入站并立即通过 CaptureSender 回复的 Dispatcher。
     */
    private CronDispatchGateway replyingDispatcher(String reply) {
        return message -> {
            lastInbound = message;
            return captureSender.send(message.routingKey(), reply, message.rootId());
        };
    }

    /**
     * 使用固定时间和消息标识创建 standalone Spring MVC 测试端点。
     */
    private MockMvc mvc(CronDispatchGateway dispatcher, Duration timeout) {
        TestApiController controller = new TestApiController(
                dispatcher,
                captureSender,
                sessionRoutingService,
                workspaceDirectory,
                timeout,
                Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC),
                () -> "test_123456789abc"
        );
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 读取 UTF-8 classpath golden 文件。
     */
    private static String resourceText(String name) throws Exception {
        try (InputStream input = TestApiControllerTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("找不到 golden 文件: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 按 UTF-8 解码 MockMvc 响应并解析为 JSON 树。
     */
    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 用内存 Map 保存当前活跃路由。
     */
    private static final class MemoryRouteRepository implements SessionRouteRepository {

        /** routing key 到路由快照。 */
        private final Map<String, SessionRoute> routes = new LinkedHashMap<>();

        /** 查询当前路由。 */
        @Override
        public Optional<SessionRoute> find(String routingKey) {
            return Optional.ofNullable(routes.get(routingKey));
        }

        /** 返回全部当前路由。 */
        @Override
        public List<SessionRoute> findAll() {
            return List.copyOf(routes.values());
        }

        /** 保存最新路由。 */
        @Override
        public void save(SessionRoute route) {
            routes.put(route.routingKey(), route);
        }

        /** 删除单个路由。 */
        @Override
        public void delete(String routingKey) {
            routes.remove(routingKey);
        }

        /** 清空全部路由。 */
        @Override
        public void clearAll() {
            routes.clear();
        }
    }

    /**
     * 记录清理状态的内存审计仓库。
     */
    private static final class MemoryAuditRepository implements ConversationAuditRepository {

        /** DELETE 是否已清理全部审计。 */
        private boolean cleared;

        /** 初始化在 Controller 测试中无需保存内容。 */
        @Override
        public void initialize(String sessionId, String routingKey, Instant createdAt) {
            // Controller 契约只关心路由与清理，不读取审计正文。
        }

        /** 追加在 Controller 测试中无需保存内容。 */
        @Override
        public void append(String sessionId, List<ConversationAuditEntry> entries) {
            // Controller 契约只关心路由与清理，不读取审计正文。
        }

        /** 标记审计已清理。 */
        @Override
        public void clearAll() {
            cleared = true;
        }
    }

    /**
     * 捕获 clearSession 参数且不运行模型的 AgentGateway。
     */
    private static final class CapturingAgentGateway implements AgentGateway {

        /** 最近一次 clearSession 的 role、userId 和 sessionId。 */
        private List<String> cleared = new ArrayList<>();

        /** Controller 契约测试不会直接调用 Agent。 */
        @Override
        public CompletableFuture<AgentReply> execute(AgentRequest request) {
            return CompletableFuture.completedFuture(new AgentReply("", List.of()));
        }

        /** 捕获 AgentScope 清理参数。 */
        @Override
        public void clearSession(String role, String userId, String sessionId) {
            cleared = List.of(role, userId, sessionId);
        }
    }
}
