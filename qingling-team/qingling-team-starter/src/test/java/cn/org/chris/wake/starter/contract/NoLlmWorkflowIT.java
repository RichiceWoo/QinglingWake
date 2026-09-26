package cn.org.chris.wake.starter.contract;

import cn.org.chris.wake.adapter.api.CaptureSender;
import cn.org.chris.wake.adapter.api.TestApiController;
import cn.org.chris.wake.app.checkpoint.CheckpointService;
import cn.org.chris.wake.app.cron.CronService;
import cn.org.chris.wake.app.cron.WakeScheduler;
import cn.org.chris.wake.app.runner.InboundAttachmentService;
import cn.org.chris.wake.app.runner.RoutingKeyResolver;
import cn.org.chris.wake.app.runner.Runner;
import cn.org.chris.wake.app.runner.SerialDispatchRegistry;
import cn.org.chris.wake.app.session.SessionRoutingService;
import cn.org.chris.wake.app.session.SlashCommandService;
import cn.org.chris.wake.domain.checkpoint.HumanInputClassifier;
import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.gateway.MetricsGateway;
import cn.org.chris.wake.domain.gateway.SenderGateway;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import cn.org.chris.wake.domain.model.Attachment;
import cn.org.chris.wake.domain.model.InboundMessage;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.infra.persistence.checkpoint.JsonlCheckpointRepository;
import cn.org.chris.wake.infra.persistence.cron.FileCronJobRepository;
import cn.org.chris.wake.infra.persistence.event.JsonlEventRepository;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.session.FileSessionRouteRepository;
import cn.org.chris.wake.infra.persistence.session.JsonlConversationAuditRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import cn.org.chris.wake.infra.workspace.WorkspaceTemplateInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 以真实文件 Repository 和 Mock Agent 验证 IT-01～IT-06，全程不访问 LLM、飞书或网络。
 */
class NoLlmWorkflowIT {

    /** 所有场景共享的确定性 UTC 时间。 */
    private static final Instant NOW = Instant.parse("2026-09-26T08:00:00Z");

    /** 固定时间便于比较 wake、审计、checkpoint 与事件。 */
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** JUnit 为每项集成场景提供的隔离根目录。 */
    @TempDir
    Path temporaryDirectory;

    /** 文件 Repository 与 HTTP 响应共用的 JSON 编解码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * IT-01：邮件落盘后的一次性 wake 应经 tasks、Cron 和 Runner 仅调用一次 PM。
     */
    @Test
    void shouldCompleteAutomaticMailWakeLoopOnce() {
        Path workspace = temporaryDirectory.resolve("it01/workspace");
        Path data = temporaryDirectory.resolve("it01/data");
        new FileWorkspaceRepository(workspace).initializeProject("proj-contract");
        MailboxService mailbox = new MailboxService(
                new FileMailboxRepository(workspace, objectMapper, CLOCK), () -> "msg-c0ffee01", CLOCK
        );
        mailbox.send("proj-contract", "pm", "manager", "task_assign", "产品设计", "请处理需求");

        FileCronJobRepository cronRepository = new FileCronJobRepository(
                data.resolve("cron/tasks.json"), objectMapper
        );
        WakeScheduler scheduler = new WakeScheduler(cronRepository, () -> "job-contract01", CLOCK);
        scheduler.scheduleMailWake("pm", "proj-contract");
        scheduler.scheduleHeartbeat("pm", Duration.ofSeconds(30), Duration.ofSeconds(1));

        CapturingAgent agent = new CapturingAgent();
        Runner runner = runner(workspace, data, agent, new TrackingSender(), () -> "s-wake");
        CronService cron = new CronService(cronRepository, runner, () -> "cron-contract01", CLOCK);
        cron.tick(NOW.plusSeconds(1).toEpochMilli());

        assertThat(agent.requests).singleElement().satisfies(request -> {
            assertThat(request.role()).isEqualTo("pm");
            assertThat(request.content()).isEqualTo("__wake__:new_mail:proj-contract");
        });
        List<MailMessage> claimed = mailbox.readInbox("proj-contract", "pm");
        assertThat(claimed).singleElement().extracting(MailMessage::id).isEqualTo("msg-c0ffee01");
        assertThat(cronRepository.findAll()).singleElement()
                .extracting(job -> job.id()).isEqualTo("heartbeat-pm");
    }

    /**
     * IT-02：同一路由复用 session，/new 后切换，审计文件不得被拼接到 Agent 请求。
     *
     * @throws Exception 审计文件扫描失败
     */
    @Test
    void shouldKeepAgentScopeSessionMappingWithoutHistoryInjection() throws Exception {
        Path workspace = temporaryDirectory.resolve("it02/workspace");
        Path data = temporaryDirectory.resolve("it02/data");
        CapturingAgent agent = new CapturingAgent();
        Deque<String> ids = new ArrayDeque<>(List.of("s-session001", "s-session002"));
        Runner runner = runner(workspace, data, agent, new TrackingSender(), ids::removeFirst);

        runner.dispatch(external("m-1", "第一轮", null)).join();
        runner.dispatch(external("m-2", "第二轮", null)).join();
        runner.dispatch(external("m-new", "/new", null)).join();
        runner.dispatch(external("m-3", "第三轮", null)).join();

        assertThat(agent.requests).extracting(AgentRequest::sessionId)
                .containsExactly("s-session001", "s-session001", "s-session002");
        assertThat(agent.requests).extracting(AgentRequest::content)
                .containsExactly("第一轮", "第二轮", "第三轮");
        assertThat(agent.requests).allSatisfy(request ->
                assertThat(request.attributes()).doesNotContainKey("history")
        );
        try (Stream<Path> files = Files.list(data.resolve("sessions"))) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))).hasSize(2);
        }
    }

    /**
     * IT-03：classpath 模板应初始化四角色 Skill/Sub-Agent，重复执行不得覆盖运行记忆。
     *
     * @throws Exception 模板文件读写失败
     */
    @Test
    void shouldInitializeDiscoverableWorkspaceWithoutOverwritingMemory() throws Exception {
        Path workspace = temporaryDirectory.resolve("it03/workspace");
        WorkspaceTemplateInitializer initializer = new WorkspaceTemplateInitializer(workspace, objectMapper);
        initializer.initialize();
        Path managerMemory = workspace.resolve("manager/MEMORY.md");
        Files.writeString(managerMemory, "运行期记忆", StandardCharsets.UTF_8);

        initializer.initialize();

        assertThat(Files.readString(managerMemory)).isEqualTo("运行期记忆");
        assertThat(workspace.resolve("manager/skills/requirements_write/SKILL.md")).isRegularFile();
        assertThat(workspace.resolve("pm/skills/product_design/SKILL.md")).isRegularFile();
        assertThat(workspace.resolve("rd/subagents/code_impl.md")).isRegularFile();
        assertThat(workspace.resolve("qa/subagents/test_run.md")).isRegularFile();
        JsonNode manifest = objectMapper.readTree(workspace.resolve("migration-manifest.json").toFile());
        assertThat(manifest.path("skills").size()).isEqualTo(35);
    }

    /**
     * IT-04：附件应先落盘并进入 Agent 请求，外部回复顺序必须是 Loading 后更新卡片。
     *
     * @throws Exception 附件读取失败
     */
    @Test
    void shouldPersistAttachmentAndUpdateLoadingCard() throws Exception {
        Path workspace = temporaryDirectory.resolve("it04/workspace");
        Path data = temporaryDirectory.resolve("it04/data");
        CapturingAgent agent = new CapturingAgent();
        TrackingSender sender = new TrackingSender();
        Runner runner = runner(workspace, data, agent, sender, () -> "s-attachment");

        runner.dispatch(external(
                "m-file", "请分析", new Attachment("file", "file-key", "../需求?.txt")
        )).join();

        assertThat(Files.readString(workspace.resolve("sessions/s-attachment/uploads/需求_.txt")))
                .isEqualTo("fixture-attachment");
        assertThat(agent.requests).singleElement().satisfies(request -> {
            assertThat(request.attachmentPaths()).containsExactly("sessions/s-attachment/uploads/需求_.txt");
            assertThat(request.content()).contains("/workspace/sessions/s-attachment/uploads/需求_.txt");
        });
        assertThat(sender.events).containsExactly("thinking:m-file", "update:card-m-file:mock-reply");
    }

    /**
     * IT-05：checkpoint revise/approve 分类应绑定正确标识，resolve 幂等且事件按序保留。
     *
     * @throws Exception JSONL 读取失败
     */
    @Test
    void shouldClassifyResolveAndRecordCheckpointEvents() throws Exception {
        Path root = temporaryDirectory.resolve("it05");
        Path pendingPath = root.resolve("workspace/shared/feishu_bridge/pending.jsonl");
        CheckpointService checkpoints = new CheckpointService(
                new JsonlCheckpointRepository(pendingPath, objectMapper), () -> "ckpt-1234abcd", CLOCK
        );
        String id = checkpoints.register(
                "p2p:ou_contract", "proj-contract", "requirements", "是否批准需求？"
        );

        HumanInputClassifier.Classification revise = checkpoints.classify(
                "p2p:ou_contract", "请修改第二条", Map.of("checkpoint_id", id, "decision", "revise")
        );
        assertThat(revise.category()).isEqualTo(HumanInputClassifier.Category.CHECKPOINT_RESPONSE);
        assertThat(revise.checkpointId()).isEqualTo(id);
        assertThat(checkpoints.resolve(id)).isTrue();
        assertThat(checkpoints.resolve(id)).isFalse();
        assertThat(checkpoints.pending("p2p:ou_contract")).isEmpty();

        cn.org.chris.wake.domain.event.EventService events = new cn.org.chris.wake.domain.event.EventService(
                new JsonlEventRepository(root.resolve("workspace"), objectMapper, CLOCK)
        );
        events.append("proj-contract", "manager", "checkpoint_reply_classified", Map.of("checkpoint_id", id));
        events.append("proj-contract", "manager", "checkpoint_approved", Map.of("checkpoint_id", id));
        assertThat(events.readAll("proj-contract")).extracting(event -> event.get("seq"))
                .containsExactly(1, 2);
        assertThat(Files.readString(pendingPath)).contains("\"resolved_at_ms\"");
    }

    /**
     * IT-06：无飞书 TestAPI 应完成请求，DELETE 后同一路由获得全新 session。
     *
     * @throws Exception MockMvc 请求或响应解析失败
     */
    @Test
    void shouldCompleteNoFeishuTestApiAndResetSession() throws Exception {
        Path workspace = temporaryDirectory.resolve("it06/workspace");
        Path data = temporaryDirectory.resolve("it06/data");
        CapturingAgent agent = new CapturingAgent();
        CaptureSender sender = new CaptureSender();
        Deque<String> ids = new ArrayDeque<>(List.of("s-api001", "s-api002"));
        SessionRoutingService sessions = sessions(data, agent, ids::removeFirst);
        Runner runner = runner(workspace, sessions, agent, sender);
        TestApiController controller = new TestApiController(
                runner, sender, sessions, workspace, Duration.ofSeconds(1)
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        JsonNode first = response(mvc.perform(post("/api/test/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routing_key\":\"p2p:ou_contract\",\"msg_id\":\"api-1\"}"))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(delete("/api/test/sessions")).andExpect(status().isOk());
        JsonNode second = response(mvc.perform(post("/api/test/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routing_key\":\"p2p:ou_contract\",\"msg_id\":\"api-2\"}"))
                .andExpect(status().isOk()).andReturn());

        assertThat(first.path("session_id").asText()).isEqualTo("s-api001");
        assertThat(first.path("reply").asText()).isEqualTo("mock-reply");
        assertThat(second.path("session_id").asText()).isEqualTo("s-api002");
        assertThat(agent.clearedSessions).containsExactly("manager:ou_test001:s-api001");
    }

    /** 使用真实文件会话组件创建 Runner。 */
    private Runner runner(
            Path workspace,
            Path data,
            CapturingAgent agent,
            SenderGateway sender,
            Supplier<String> sessionIds
    ) {
        return runner(workspace, sessions(data, agent, sessionIds), agent, sender);
    }

    /** 使用给定会话服务创建同步、确定性的无网络 Runner。 */
    private Runner runner(
            Path workspace,
            SessionRoutingService sessions,
            CapturingAgent agent,
            SenderGateway sender
    ) {
        InboundAttachmentService attachments = new InboundAttachmentService(
                workspace,
                (messageId, attachment) -> CompletableFuture.completedFuture(
                        "fixture-attachment".getBytes(StandardCharsets.UTF_8)
                )
        );
        return new Runner(
                sessions,
                new SlashCommandService(sessions),
                agent,
                sender,
                attachments,
                new SerialDispatchRegistry(Runnable::run),
                new RoutingKeyResolver(),
                MetricsGateway.noop()
        );
    }

    /** 使用真实 index 与只写 JSONL 审计创建会话服务。 */
    private SessionRoutingService sessions(
            Path data,
            CapturingAgent agent,
            Supplier<String> sessionIds
    ) {
        return new SessionRoutingService(
                new FileSessionRouteRepository(data, objectMapper),
                new JsonlConversationAuditRepository(data, objectMapper),
                agent,
                sessionIds,
                CLOCK
        );
    }

    /** 创建固定 p2p 入站消息，可选携带单个附件。 */
    private static InboundMessage external(String messageId, String content, Attachment attachment) {
        return new InboundMessage(
                "p2p:ou_contract", content, messageId, messageId,
                "ou_test001", NOW.toEpochMilli(), false, attachment, Map.of("source", "contract-it")
        );
    }

    /** 按 UTF-8 解码并解析 MockMvc JSON 响应。 */
    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 捕获 Agent 请求并返回确定性结果，不启动 AgentScope 或模型。 */
    private static final class CapturingAgent implements AgentGateway {

        /** 按执行顺序保存收到的请求。 */
        private final List<AgentRequest> requests = new ArrayList<>();

        /** 保存 TestAPI DELETE 触发的框架状态清理参数。 */
        private final List<String> clearedSessions = new ArrayList<>();

        /** 记录请求并立即返回固定回复。 */
        @Override
        public CompletableFuture<AgentReply> execute(AgentRequest request) {
            requests.add(request);
            return CompletableFuture.completedFuture(new AgentReply("mock-reply", List.of("fixture_skill")));
        }

        /** 记录被清理的角色、用户和 sessionId。 */
        @Override
        public void clearSession(String role, String userId, String sessionId) {
            clearedSessions.add(role + ":" + userId + ":" + sessionId);
        }
    }

    /** 捕获 Loading、卡片更新与普通发送的顺序。 */
    private static final class TrackingSender implements SenderGateway {

        /** 按实际发生顺序保存出站事件。 */
        private final List<String> events = new ArrayList<>();

        /** 记录普通富文本发送。 */
        @Override
        public CompletableFuture<Void> send(String routingKey, String content, String rootId) {
            events.add("send:" + rootId + ":" + content);
            return CompletableFuture.completedFuture(null);
        }

        /** 记录 Loading 并返回与来源消息绑定的虚拟卡片 ID。 */
        @Override
        public CompletableFuture<String> sendThinking(String routingKey, String rootId) {
            events.add("thinking:" + rootId);
            return CompletableFuture.completedFuture("card-" + rootId);
        }

        /** 记录卡片最终内容更新。 */
        @Override
        public CompletableFuture<Void> updateCard(String cardMessageId, String content) {
            events.add("update:" + cardMessageId + ":" + content);
            return CompletableFuture.completedFuture(null);
        }

        /** 记录 Slash Command 等纯文本回复。 */
        @Override
        public CompletableFuture<Void> sendText(String routingKey, String content, String rootId) {
            events.add("text:" + rootId + ":" + content);
            return CompletableFuture.completedFuture(null);
        }
    }
}
