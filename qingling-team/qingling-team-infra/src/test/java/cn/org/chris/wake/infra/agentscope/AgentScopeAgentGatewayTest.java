package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import cn.org.chris.wake.infra.memory.TurnMemoryIndexer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 AgentScope Gateway 的路由上下文、异步转换和 prompt 边界。
 */
class AgentScopeAgentGatewayTest {

    /**
     * 正文必须原样进入 Harness，路由与附件只进入 RuntimeContext，Mono 最终转为 Future。
     */
    @Test
    void shouldPassOnlyCurrentContentAndConvertMonoToFuture() {
        CapturingRuntime runtime = new CapturingRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime), AgentScopeAgentGateway.RuntimeMode.PRODUCTION
        );
        AgentRequest request = new AgentRequest(
                "rd",
                "user-001",
                "session-001",
                "只包含当前轮正文",
                List.of("uploads/design.png"),
                Map.of("routingKey", "p2p:user-001", "auditJsonl", "不得拼接到正文")
        );

        AgentReply reply = gateway.execute(request).join();

        assertThat(runtime.content).isEqualTo("只包含当前轮正文");
        assertThat(runtime.context.getUserId()).isEqualTo("user-001");
        assertThat(runtime.context.getSessionId()).isEqualTo("session-001");
        assertThat(runtime.context.get("routingKey", String.class)).isEqualTo("p2p:user-001");
        assertThat(runtime.context.get(AgentScopeAgentGateway.ROLE_ATTRIBUTE, String.class)).isEqualTo("rd");
        Object attachmentPaths = runtime.context.get(AgentScopeAgentGateway.ATTACHMENT_PATHS_ATTRIBUTE);
        assertThat(attachmentPaths).isEqualTo(List.of("uploads/design.png"));
        assertThat(reply.content()).isEqualTo("done");
        assertThat(reply.skillsCalled()).containsExactly("code_impl", "self_score");
    }

    /**
     * 记忆索引调度应收到完整单轮数据，且同步失败不得改变 Agent 主回复。
     */
    @Test
    void shouldScheduleMemoryWithoutAffectingReply() {
        CapturingRuntime runtime = new CapturingRuntime();
        CapturingMemoryIndexer memoryIndexer = new CapturingMemoryIndexer();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime), AgentScopeAgentGateway.RuntimeMode.PRODUCTION, memoryIndexer
        );
        AgentRequest request = new AgentRequest(
                "rd",
                "user-001",
                "session-001",
                "用户问题",
                List.of(),
                Map.of("routingKey", "p2p:user-001")
        );

        AgentReply reply = gateway.execute(request).join();

        assertThat(reply.content()).isEqualTo("done");
        assertThat(memoryIndexer.sessionId).isEqualTo("session-001");
        assertThat(memoryIndexer.routingKey).isEqualTo("p2p:user-001");
        assertThat(memoryIndexer.userMessage).isEqualTo("用户问题");
        assertThat(memoryIndexer.assistantReply).isEqualTo("done");
        assertThat(memoryIndexer.turnTimestampMs).isPositive();
    }

    /**
     * 清理会话和关闭 Gateway 必须委托给对应角色运行时。
     */
    @Test
    void shouldDelegateSessionCleanupAndClose() {
        CapturingRuntime runtime = new CapturingRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("qa", runtime), AgentScopeAgentGateway.RuntimeMode.PRODUCTION
        );

        gateway.clearSession("qa", "user-002", "session-002");
        gateway.close();

        assertThat(runtime.clearedUserId).isEqualTo("user-002");
        assertThat(runtime.clearedSessionId).isEqualTo("session-002");
        assertThat(runtime.cancelledContext.getSessionId()).isEqualTo("session-002");
        assertThat(runtime.closed).isTrue();
    }

    /** 父 dispatch Future 取消时必须只级联一次，并保留可直接定位项目与子任务的活跃诊断。 */
    @Test
    void shouldCancelParentAndChildRuntimeWhenDispatchFutureIsCancelled() {
        PendingRuntime runtime = new PendingRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime), AgentScopeAgentGateway.RuntimeMode.PRODUCTION
        );
        AgentRequest request = new AgentRequest(
                "rd",
                "team-rd",
                "session-code",
                "执行代码实现",
                List.of(),
                Map.of("routingKey", "team:rd", "projectId", "todo-mvp")
        );

        CompletableFuture<AgentReply> future = gateway.execute(request);
        AgentScopeAgentGateway.ActiveExecutionDiagnostic diagnostic =
                gateway.activeExecutionDiagnostics().get(0);

        assertThat(diagnostic.routingKey()).isEqualTo("team:rd");
        assertThat(diagnostic.projectId()).isEqualTo("todo-mvp");
        assertThat(diagnostic.childTasks())
                .extracting(AgentScopeAgentGateway.ChildTaskDiagnostic::status)
                .containsExactly("RUNNING");
        assertThat(future.cancel(true)).isTrue();
        assertThat(runtime.cancellations).hasValue(1);
        assertThat(runtime.cancelledContext.getSessionId()).isEqualTo("session-code");
        assertThat(gateway.activeExecutionDiagnostics()).isEmpty();
    }

    /** 子任务全部取消后，运行时必须快速失败并清除父回合，不能留下永久占用串行队列的 dispatch。 */
    @Test
    void shouldTerminateParentDispatchWhenAllChildTasksAreCancelled() {
        CancelledChildrenRuntime runtime = new CancelledChildrenRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime),
                AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                Duration.ofMillis(5),
                Duration.ofMillis(10)
        );
        AgentRequest request = new AgentRequest(
                "rd",
                "team-rd",
                "session-cancelled-child",
                "执行代码实现",
                List.of(),
                Map.of("routingKey", "team:rd", "projectId", "todo-mvp")
        );

        CompletableFuture<AgentReply> future = gateway.execute(request);

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasRootCauseMessage("AgentScope 子任务异常终止，父 dispatch 已结束: code_impl=CANCELLED");
        assertThat(runtime.cancellations).hasValue(1);
        assertThat(gateway.activeExecutionDiagnostics()).isEmpty();
    }

    /**
     * 捕获适配器参数且不调用任何真实模型的测试运行时。
     */
    private static final class CapturingRuntime implements AgentScopeAgentGateway.AgentRuntime {

        /** 最近一次收到的正文。 */
        private String content;

        /** 最近一次收到的运行上下文。 */
        private RuntimeContext context;

        /** 最近一次清理的用户标识。 */
        private String clearedUserId;

        /** 最近一次清理的会话标识。 */
        private String clearedSessionId;

        /** 是否已收到关闭调用。 */
        private boolean closed;

        /** 最近一次收到取消的执行上下文。 */
        private RuntimeContext cancelledContext;

        /**
         * 捕获正文和上下文后返回确定性消息。
         *
         * @param currentContent 仅本轮正文
         * @param currentContext 路由上下文
         * @return 不依赖模型的回复
         */
        @Override
        public Mono<Msg> call(String currentContent, RuntimeContext currentContext) {
            content = currentContent;
            context = currentContext;
            return Mono.just(Msg.builder()
                    .textContent("done")
                    .metadata(Map.of("skills_called", List.of("code_impl", "self_score")))
                    .build());
        }

        /** 捕获会话级取消上下文。 */
        @Override
        public void cancel(RuntimeContext currentContext) {
            cancelledContext = currentContext;
        }

        /**
         * 捕获会话清理参数。
         *
         * @param userId 用户标识
         * @param sessionId 会话标识
         */
        @Override
        public void clearContext(String userId, String sessionId) {
            clearedUserId = userId;
            clearedSessionId = sessionId;
        }

        /**
         * 标记运行时已关闭。
         */
        @Override
        public void close() {
            closed = true;
        }
    }

    /** 返回永不自行结束的父回合，并暴露一个运行中子任务的取消测试运行时。 */
    private static final class PendingRuntime implements AgentScopeAgentGateway.AgentRuntime {

        /** 收到的会话级取消次数。 */
        private final AtomicInteger cancellations = new AtomicInteger();

        /** 最近一次取消上下文。 */
        private RuntimeContext cancelledContext;

        /** 保持父回合活跃，直到测试取消 Future。 */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            return Mono.never();
        }

        /** 捕获父子级联取消。 */
        @Override
        public void cancel(RuntimeContext context) {
            cancellations.incrementAndGet();
            cancelledContext = context;
        }

        /** 返回不含任务正文的运行中子任务诊断。 */
        @Override
        public List<AgentScopeAgentGateway.ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            return List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                    "task-code", "code_impl", "RUNNING", false, java.time.Instant.EPOCH
            ));
        }

        /** 本测试不需要清理持久会话。 */
        @Override
        public void clearContext(String userId, String sessionId) { }

        /** 本测试运行时没有外部资源。 */
        @Override
        public void close() { }
    }

    /** 返回永不结束的父回合和已取消子任务，用于验证运行时主动终止悬挂 dispatch。 */
    private static final class CancelledChildrenRuntime implements AgentScopeAgentGateway.AgentRuntime {

        /** 收到的父子级联取消次数。 */
        private final AtomicInteger cancellations = new AtomicInteger();

        /** 保持父回合悬挂，直到 Gateway 根据子任务终态主动终止。 */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            return Mono.never();
        }

        /** 捕获 Gateway 发起的单次父子级联取消。 */
        @Override
        public void cancel(RuntimeContext context) {
            cancellations.incrementAndGet();
        }

        /** 返回一个已取消且处于终态的 code_impl 子任务。 */
        @Override
        public List<AgentScopeAgentGateway.ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            return List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                    "task-cancelled", "code_impl", "CANCELLED", true, java.time.Instant.EPOCH
            ));
        }

        /** 本测试不需要清理持久会话。 */
        @Override
        public void clearContext(String userId, String sessionId) { }

        /** 本测试运行时没有外部资源。 */
        @Override
        public void close() { }
    }

    /**
     * 捕获单轮索引参数并模拟同步调度失败的测试实现。
     */
    private static final class CapturingMemoryIndexer implements TurnMemoryIndexer {

        /** 最近一次收到的会话标识。 */
        private String sessionId;

        /** 最近一次收到的外部路由键。 */
        private String routingKey;

        /** 最近一次收到的用户消息。 */
        private String userMessage;

        /** 最近一次收到的助手回复。 */
        private String assistantReply;

        /** 最近一次收到的轮次时间戳。 */
        private long turnTimestampMs;

        /**
         * 捕获全部参数后抛出异常，验证旁路失败隔离。
         */
        @Override
        public void schedule(
                String currentSessionId,
                String currentRoutingKey,
                String currentUserMessage,
                String currentAssistantReply,
                long currentTurnTimestampMs
        ) {
            sessionId = currentSessionId;
            routingKey = currentRoutingKey;
            userMessage = currentUserMessage;
            assistantReply = currentAssistantReply;
            turnTimestampMs = currentTurnTimestampMs;
            throw new IllegalStateException("模拟记忆调度失败");
        }
    }
}
