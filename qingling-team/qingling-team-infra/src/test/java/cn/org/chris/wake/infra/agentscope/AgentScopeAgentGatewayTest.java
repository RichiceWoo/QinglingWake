package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import cn.org.chris.wake.infra.memory.TurnMemoryIndexer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(runtime.closed).isTrue();
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
