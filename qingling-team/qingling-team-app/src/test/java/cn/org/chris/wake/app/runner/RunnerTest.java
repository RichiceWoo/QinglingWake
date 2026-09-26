package cn.org.chris.wake.app.runner;

import cn.org.chris.wake.app.session.SessionRoutingService;
import cn.org.chris.wake.app.session.SlashCommandService;
import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.gateway.MetricsGateway;
import cn.org.chris.wake.domain.gateway.ConversationAuditRepository;
import cn.org.chris.wake.domain.gateway.SenderGateway;
import cn.org.chris.wake.domain.gateway.SessionRouteRepository;
import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import cn.org.chris.wake.domain.model.Attachment;
import cn.org.chris.wake.domain.model.ConversationAuditEntry;
import cn.org.chris.wake.domain.model.InboundMessage;
import cn.org.chris.wake.domain.model.SessionRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Runner 从入站到 Agent、审计和出站的完整应用链路。
 */
class RunnerTest {

    /** 固定测试时间。 */
    private static final Instant NOW = Instant.parse("2026-09-26T03:00:00Z");

    /** JUnit 隔离附件 workspace。 */
    @TempDir
    Path temporaryRoot;

    /** 内存路由仓库。 */
    private MemoryRouteRepository routeRepository;

    /** 内存审计仓库。 */
    private MemoryAuditRepository auditRepository;

    /** 可编排回复的 Agent 假实现。 */
    private FakeAgentGateway agentGateway;

    /** 捕获所有出站调用的 Sender。 */
    private FakeSenderGateway senderGateway;

    /** 捕获失败分类的 Metrics。 */
    private CapturingMetrics metrics;

    /** 待测 Runner。 */
    private Runner runner;

    /**
     * 为每个测试创建同步串行注册表和固定 sessionId 序列。
     */
    @BeforeEach
    void setUp() {
        routeRepository = new MemoryRouteRepository();
        auditRepository = new MemoryAuditRepository();
        agentGateway = new FakeAgentGateway();
        senderGateway = new FakeSenderGateway();
        metrics = new CapturingMetrics();
        Deque<String> sessionIds = new ArrayDeque<>(List.of("s-first", "s-second", "s-third"));
        SessionRoutingService routingService = new SessionRoutingService(
                routeRepository,
                auditRepository,
                agentGateway,
                sessionIds::removeFirst,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        InboundAttachmentService attachmentService = new InboundAttachmentService(
                temporaryRoot.resolve("workspace"),
                (messageId, attachment) -> CompletableFuture.completedFuture(
                        "attachment-body".getBytes(StandardCharsets.UTF_8)
                )
        );
        runner = new Runner(
                routingService,
                new SlashCommandService(routingService),
                agentGateway,
                senderGateway,
                attachmentService,
                new SerialDispatchRegistry(Runnable::run),
                new RoutingKeyResolver(),
                metrics
        );
    }

    /**
     * 外部附件消息应落入 session workspace、进入 AgentRequest，并通过 Loading 卡片回复和审计。
     *
     * @throws Exception 读取已写入附件失败
     */
    @Test
    void shouldRunExternalAttachmentThroughLoadingAgentAuditAndCardUpdate() throws Exception {
        InboundMessage inbound = message(
                "p2p:user-1",
                "请分析附件",
                false,
                new Attachment("file", "file-key", "../设计?.txt"),
                Map.of("source", "feishu")
        );

        runner.dispatch(inbound).join();

        assertThat(agentGateway.requests).singleElement().satisfies(request -> {
            assertThat(request.role()).isEqualTo("manager");
            assertThat(request.userId()).isEqualTo("user-1");
            assertThat(request.sessionId()).isEqualTo("s-first");
            assertThat(request.content()).contains("/workspace/sessions/s-first/uploads/设计_.txt");
            assertThat(request.content()).contains("用户备注：请分析附件");
            assertThat(request.attachmentPaths())
                    .containsExactly("sessions/s-first/uploads/设计_.txt");
            assertThat(request.attributes()).containsEntry("routingKey", "p2p:user-1")
                    .doesNotContainKey("history");
        });
        Path stored = temporaryRoot.resolve("workspace/sessions/s-first/uploads/设计_.txt");
        assertThat(Files.readString(stored)).isEqualTo("attachment-body");
        assertThat(senderGateway.events).containsExactly("thinking:p2p:user-1", "update:card-1:reply-1");
        assertThat(auditRepository.entries.get("s-first"))
                .extracting(ConversationAuditEntry::role)
                .containsExactly("user", "assistant");
        assertThat(routeRepository.find("p2p:user-1").orElseThrow().messageCount()).isEqualTo(2);
    }

    /**
     * 相同 team 路由的并发 wake 只执行一次，且内部唤醒不得调用任何 Sender。
     */
    @Test
    void shouldDeduplicateTeamWakeAndNeverSendExternalReply() {
        CompletableFuture<AgentReply> gate = new CompletableFuture<>();
        agentGateway.behavior = request -> gate;
        InboundMessage wake = message(
                "team:pm", "__wake__:heartbeat", true, null, Map.of("wake_reason", "heartbeat")
        );

        CompletableFuture<Void> first = runner.dispatch(wake);
        CompletableFuture<Void> duplicate = runner.dispatch(wake);

        assertThat(duplicate).isCompleted();
        assertThat(agentGateway.requests).hasSize(1);
        assertThat(agentGateway.requests.get(0).role()).isEqualTo("pm");
        gate.complete(new AgentReply("internal", List.of("mailbox_ops")));
        first.join();
        assertThat(senderGateway.events).isEmpty();
    }

    /**
     * 单条 Agent 失败应记录指标并返回异常，但同 key 下一条消息仍能成功处理。
     */
    @Test
    void shouldRecordFailureAndContinueSameRouteQueue() {
        Deque<CompletableFuture<AgentReply>> replies = new ArrayDeque<>();
        replies.add(CompletableFuture.failedFuture(new IllegalStateException("sensitive failure")));
        replies.add(CompletableFuture.completedFuture(new AgentReply("recovered", List.of())));
        agentGateway.behavior = request -> replies.removeFirst();

        CompletableFuture<Void> failed = runner.dispatch(message(
                "p2p:user-2", "first", false, null, Map.of()
        ));
        CompletableFuture<Void> recovered = runner.dispatch(message(
                "p2p:user-2", "second", false, null, Map.of()
        ));

        assertThatThrownBy(failed::join).hasCauseInstanceOf(IllegalStateException.class);
        recovered.join();
        assertThat(agentGateway.requests).extracting(AgentRequest::content)
                .containsExactly("first", "second");
        assertThat(senderGateway.events).contains(
                "text:p2p:user-2:处理出错，请稍后重试。",
                "update:card-2:recovered"
        );
        assertThat(metrics.failures).containsExactly("runner:IllegalStateException");
    }

    /**
     * Slash Command 应在 Agent 和审计之前被消费，并以纯文本回复。
     */
    @Test
    void shouldInterceptSlashCommandBeforeAgent() {
        runner.dispatch(message("p2p:user-3", "/status", false, null, Map.of())).join();

        assertThat(agentGateway.requests).isEmpty();
        assertThat(auditRepository.entries).containsKey("s-first");
        assertThat(auditRepository.entries.get("s-first")).isEmpty();
        assertThat(senderGateway.events).singleElement()
                .asString().startsWith("text:p2p:user-3:session=s-first");
    }

    /**
     * Loading 卡片更新失败时应记录 Sender 指标并降级发送普通富文本回复。
     */
    @Test
    void shouldFallbackToSendWhenCardUpdateFails() {
        senderGateway.failCardUpdate = true;

        runner.dispatch(message("p2p:user-4", "hello", false, null, Map.of())).join();

        assertThat(senderGateway.events).containsExactly(
                "thinking:p2p:user-4",
                "update:card-1:reply-1",
                "send:p2p:user-4:reply-1"
        );
        assertThat(metrics.failures).containsExactly("sender:IllegalStateException");
    }

    /**
     * 创建用于测试的标准消息。
     *
     * @param routingKey 路由键
     * @param content 正文
     * @param cron 是否为定时任务
     * @param attachment 可空附件
     * @param meta 扩展属性
     * @return 入站消息
     */
    private static InboundMessage message(
            String routingKey,
            String content,
            boolean cron,
            Attachment attachment,
            Map<String, Object> meta
    ) {
        String sender = routingKey.startsWith("p2p:")
                ? routingKey.substring("p2p:".length())
                : "cron";
        return new InboundMessage(
                routingKey,
                content,
                "msg-" + content.hashCode(),
                "root-1",
                sender,
                NOW.toEpochMilli(),
                cron,
                attachment,
                meta
        );
    }

    /**
     * 以内存 Map 保存当前活跃路由。
     */
    private static final class MemoryRouteRepository implements SessionRouteRepository {

        /** routing key 到路由快照。 */
        private final Map<String, SessionRoute> routes = new LinkedHashMap<>();

        /**
         * 查询当前路由。
         *
         * @param routingKey 业务路由键
         * @return 当前快照
         */
        @Override
        public Optional<SessionRoute> find(String routingKey) {
            return Optional.ofNullable(routes.get(routingKey));
        }

        /**
         * 返回全部当前路由快照。
         *
         * @return 路由快照列表
         */
        @Override
        public List<SessionRoute> findAll() {
            return List.copyOf(routes.values());
        }

        /**
         * 保存最新路由。
         *
         * @param route 最新路由快照
         */
        @Override
        public void save(SessionRoute route) {
            routes.put(route.routingKey(), route);
        }

        /**
         * 删除路由。
         *
         * @param routingKey 业务路由键
         */
        @Override
        public void delete(String routingKey) {
            routes.remove(routingKey);
        }

        /**
         * 清空全部内存路由。
         */
        @Override
        public void clearAll() {
            routes.clear();
        }
    }

    /**
     * 捕获各 session 审计条目的内存实现。
     */
    private static final class MemoryAuditRepository implements ConversationAuditRepository {

        /** sessionId 到审计条目列表。 */
        private final Map<String, List<ConversationAuditEntry>> entries = new LinkedHashMap<>();

        /**
         * 初始化空 session 审计列表。
         *
         * @param sessionId 会话标识
         * @param routingKey 路由键
         * @param createdAt 创建时间
         */
        @Override
        public void initialize(String sessionId, String routingKey, Instant createdAt) {
            entries.putIfAbsent(sessionId, new ArrayList<>());
        }

        /**
         * 追加一轮审计条目。
         *
         * @param sessionId 会话标识
         * @param newEntries 新条目
         */
        @Override
        public void append(String sessionId, List<ConversationAuditEntry> newEntries) {
            entries.computeIfAbsent(sessionId, ignored -> new ArrayList<>()).addAll(newEntries);
        }

        /**
         * 清空全部测试审计。
         */
        @Override
        public void clearAll() {
            entries.clear();
        }
    }

    /**
     * 捕获请求并按测试函数返回结果的 AgentGateway。
     */
    private static final class FakeAgentGateway implements AgentGateway {

        /** 收到的全部请求。 */
        private final List<AgentRequest> requests = new ArrayList<>();

        /** 每次请求的可替换行为。 */
        private Function<AgentRequest, CompletableFuture<AgentReply>> behavior = request ->
                CompletableFuture.completedFuture(new AgentReply("reply-1", List.of()));

        /**
         * 捕获请求并执行当前行为。
         *
         * @param request Agent 请求
         * @return 测试回复
         */
        @Override
        public CompletableFuture<AgentReply> execute(AgentRequest request) {
            requests.add(request);
            return behavior.apply(request);
        }

        /**
         * 测试不持有真实 AgentScope state，因此无需清理。
         *
         * @param role 角色
         * @param userId 用户标识
         * @param sessionId 会话标识
         */
        @Override
        public void clearSession(String role, String userId, String sessionId) {
            // fake 不持久化状态。
        }
    }

    /**
     * 以字符串事件捕获 Loading、更新、富文本和纯文本发送。
     */
    private static final class FakeSenderGateway implements SenderGateway {

        /** 按调用顺序保存的出站事件。 */
        private final List<String> events = new ArrayList<>();

        /** 下一个 Loading 卡片序号。 */
        private int cardSequence;

        /** 是否让卡片更新以异步异常失败。 */
        private boolean failCardUpdate;

        /**
         * 捕获普通富文本回复。
         *
         * @param routingKey 路由键
         * @param content 正文
         * @param rootId 话题根标识
         * @return 完成信号
         */
        @Override
        public CompletableFuture<Void> send(String routingKey, String content, String rootId) {
            events.add("send:" + routingKey + ":" + content);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 创建并捕获 Loading 卡片。
         *
         * @param routingKey 路由键
         * @param rootId 话题根标识
         * @return 新卡片标识
         */
        @Override
        public CompletableFuture<String> sendThinking(String routingKey, String rootId) {
            events.add("thinking:" + routingKey);
            return CompletableFuture.completedFuture("card-" + ++cardSequence);
        }

        /**
         * 捕获卡片更新。
         *
         * @param cardMessageId 卡片标识
         * @param content 最终正文
         * @return 完成信号
         */
        @Override
        public CompletableFuture<Void> updateCard(String cardMessageId, String content) {
            events.add("update:" + cardMessageId + ":" + content);
            if (failCardUpdate) {
                return CompletableFuture.failedFuture(new IllegalStateException("update failed"));
            }
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 捕获纯文本回复或错误提示。
         *
         * @param routingKey 路由键
         * @param content 正文
         * @param rootId 话题根标识
         * @return 完成信号
         */
        @Override
        public CompletableFuture<Void> sendText(String routingKey, String content, String rootId) {
            events.add("text:" + routingKey + ":" + content);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 捕获 Runner 指标标签而不依赖 Micrometer。
     */
    private static final class CapturingMetrics implements MetricsGateway {

        /** 按发生顺序保存失败组件和类型。 */
        private final List<String> failures = new ArrayList<>();

        /**
         * 本测试不断言入站低基数标签。
         *
         * @param routingType 路由类型
         * @param hasAttachment 是否含附件
         */
        @Override
        public void recordInbound(String routingType, boolean hasAttachment) {
            // 仅失败指标属于本组测试断言范围。
        }

        /** 本测试不记录飞书事件。 */
        @Override
        public void recordFeishuEvent(String eventType, String chatType) {
            // 飞书指标由 adapter/infra 测试覆盖。
        }

        /** 本测试不记录 HTTP 请求。 */
        @Override
        public void recordHttpRequest(String path, String method, int statusCode, double durationSeconds) {
            // HTTP 指标由 adapter/infra 测试覆盖。
        }

        /**
         * 本测试不持久化瞬时队列深度。
         *
         * @param routingType 路由类型
         * @param queueDepth 队列深度
         */
        @Override
        public void recordQueueDepth(String routingType, int queueDepth) {
            // 队列顺序由独立 SerialDispatchRegistryTest 验证。
        }

        /**
         * 本测试不持久化瞬时活跃 worker 数。
         */
        @Override
        public void recordWorkerDelta(String routingType, int delta) {
            // Gauge 数值由 MetricsRecorderTest 单独验证。
        }

        /**
         * 捕获脱敏失败分类。
         *
         * @param component 失败组件
         * @param errorType 异常类型
         */
        @Override
        public void recordFailure(String component, String errorType) {
            failures.add(component + ":" + errorType);
        }
    }
}
