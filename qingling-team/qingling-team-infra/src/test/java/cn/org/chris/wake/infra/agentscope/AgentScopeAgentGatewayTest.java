package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;
import cn.org.chris.wake.domain.model.AgentExecutionException;
import cn.org.chris.wake.infra.memory.TurnMemoryIndexer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
                .hasRootCauseInstanceOf(AgentExecutionException.class)
                .hasRootCauseMessage("AgentScope 子任务异常终止，父 dispatch 已结束: "
                        + "code_impl=CANCELLED[kind=SEND,cancel_origin=MODEL_REQUESTED]");
        assertThat(runtime.cancellations).hasValue(1);
        assertThat(gateway.activeExecutionDiagnostics()).isEmpty();
    }

    /** 较旧的取消记录不得覆盖同一逻辑子 Agent 最新仍在运行的 continuation。 */
    @Test
    void shouldKeepParentAliveWhileLatestContinuationIsRunning() throws Exception {
        LatestContinuationRuntime runtime = new LatestContinuationRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime),
                AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                Duration.ofMillis(5),
                Duration.ofMillis(10)
        );
        AgentRequest request = new AgentRequest(
                "rd", "team-rd", "session-continuation", "继续实现", List.of(),
                Map.of("routingKey", "team:rd", "projectId", "todo-mvp")
        );

        CompletableFuture<AgentReply> future = gateway.execute(request);
        Thread.sleep(60L);

        assertThat(future).isNotDone();
        assertThat(future.cancel(true)).isTrue();
        assertThat(runtime.cancellations).hasValue(1);
    }

    /** 父 Agent 即使先返回，也必须等运行中的子任务成功终态后才释放顶层 dispatch。 */
    @Test
    void shouldKeepParentDispatchUntilRunningChildCompletes() throws Exception {
        CompletingParentRuntime runtime = new CompletingParentRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime),
                AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                Duration.ofMillis(5),
                Duration.ofMillis(10)
        );
        AgentRequest request = new AgentRequest(
                "rd", "team-rd", "session-wait-child", "执行实现", List.of(),
                Map.of("routingKey", "team:rd", "projectId", "todo-mvp")
        );

        CompletableFuture<AgentReply> future = gateway.execute(request);
        Thread.sleep(30L);

        assertThat(future).isNotDone();
        assertThat(gateway.activeExecutionDiagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.parentLastProgressAt()).isAfterOrEqualTo(diagnostic.startedAt());
            assertThat(diagnostic.childTasks())
                    .extracting(AgentScopeAgentGateway.ChildTaskDiagnostic::status)
                    .containsExactly("RUNNING");
        });

        runtime.completed.set(true);
        assertThat(future.get(1, TimeUnit.SECONDS).content()).isEqualTo("parent-done");
        assertThat(gateway.activeExecutionDiagnostics()).isEmpty();
    }

    /** RUNNING 子任务超过真实进度租约后必须终止父 dispatch，并生成 CHILD_STALLED 类型化故障。 */
    @Test
    void shouldTerminateParentDispatchWhenRunningChildStopsMakingRealProgress() {
        StalledChildRuntime runtime = new StalledChildRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime),
                AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                Duration.ofMillis(20)
        );
        AgentRequest request = new AgentRequest(
                "rd", "team-rd", "session-stalled", "继续实现", List.of(),
                Map.of("routingKey", "team:rd", "projectId", "todo-mvp")
        );

        CompletableFuture<AgentReply> future = gateway.execute(request);

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AgentExecutionException.class)
                .rootCause()
                .extracting(failure -> ((AgentExecutionException) failure).failureType())
                .isEqualTo(AgentExecutionException.FailureType.CHILD_STALLED);
        assertThat(runtime.cancellations).hasValue(1);
        assertThat(gateway.activeExecutionDiagnostics()).isEmpty();
    }

    /** 同一逻辑子 Agent 出现两个未终态 continuation 时必须终止父 dispatch 并级联取消。 */
    @Test
    void shouldTerminateParentDispatchWhenDuplicateContinuationsAreRunning() {
        DuplicateContinuationRuntime runtime = new DuplicateContinuationRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime),
                AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                Duration.ofMinutes(1)
        );
        AgentRequest request = new AgentRequest(
                "rd", "team-rd", "session-duplicate", "继续实现", List.of(),
                Map.of("routingKey", "team:rd", "projectId", "todo-mvp")
        );

        CompletableFuture<AgentReply> future = gateway.execute(request);

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AgentExecutionException.class)
                .rootCause()
                .satisfies(failure -> {
                    AgentExecutionException typed = (AgentExecutionException) failure;
                    assertThat(typed.failureType())
                            .isEqualTo(AgentExecutionException.FailureType.DUPLICATE_CHILD_TASK);
                    assertThat(typed.getMessage()).contains("task-first", "task-second", "code_impl");
                });
        assertThat(runtime.cancellations).hasValue(1);
        assertThat(gateway.activeExecutionDiagnostics()).isEmpty();
    }

    /** 子任务均已收敛或不存在时，父回合自身超过真实进度租约也必须进入类型化恢复。 */
    @Test
    void shouldTerminateParentDispatchWhenParentStopsMakingRealProgress() {
        ParentStalledRuntime runtime = new ParentStalledRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime),
                AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                Duration.ofMillis(20)
        );
        AgentRequest request = new AgentRequest(
                "rd", "team-rd", "session-parent-stalled", "继续实现", List.of(),
                Map.of("routingKey", "team:rd", "projectId", "todo-mvp")
        );

        CompletableFuture<AgentReply> future = gateway.execute(request);

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AgentExecutionException.class)
                .rootCause()
                .extracting(failure -> ((AgentExecutionException) failure).failureType())
                .isEqualTo(AgentExecutionException.FailureType.PARENT_STALLED);
        assertThat(runtime.cancellations).hasValue(1);
        assertThat(gateway.activeExecutionDiagnostics()).isEmpty();
    }

    /** 首次 MCP 熔断应先重建连接再终止父回合，禁止把未知命令结果当成成功或自动重放。 */
    @Test
    void shouldRecoverMcpConnectionThenFailParentForIncidentRecovery() {
        AtomicInteger registrations = new AtomicInteger();
        McpServerConfig server = new McpServerConfig();
        server.setTransport(McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT);
        server.setEnableTools(List.of("sandbox_execute_bash"));
        McpSandboxConfiguration sandbox = new McpSandboxConfiguration(
                "aio-sandbox",
                server,
                (toolkit, servers, listener) -> {
                    AgentTool tool = registrations.incrementAndGet() == 1
                            ? new AlwaysFailingMcpTool()
                            : new ProbeSuccessMcpTool();
                    toolkit.registerAgentTool(tool);
                    listener.onCompleted(McpServerRegistrationResult.success(
                            "aio-sandbox", McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT
                    ));
                }
        );
        Toolkit toolkit = new Toolkit();
        sandbox.applyTo(HarnessAgent.builder(), toolkit, "rd");
        AgentTool shell = toolkit.getTool("sandbox_execute_bash");
        ToolUseBlock use = new ToolUseBlock("mcp-outage", "sandbox_execute_bash", Map.of("cmd", "pwd"));
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build();
        shell.callAsync(param).block();

        PendingRuntime runtime = new PendingRuntime();
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime),
                AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                sandbox,
                Duration.ofMillis(5),
                Duration.ofMillis(5),
                Duration.ofSeconds(10)
        );
        CompletableFuture<AgentReply> future = gateway.execute(new AgentRequest(
                "rd", "team-rd", "session-mcp", "执行代码实现", List.of(),
                Map.of("routingKey", "team:rd", "projectId", "todo-mvp")
        ));

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AgentExecutionException.class)
                .rootCause()
                .extracting(failure -> ((AgentExecutionException) failure).failureType())
                .isEqualTo(AgentExecutionException.FailureType.MCP_CONNECTION_UNAVAILABLE);
        assertThat(registrations).hasValue(2);
        assertThat(sandbox.hasUnavailableConnection("rd")).isFalse();
        assertThat(runtime.cancellations).hasValue(1);
    }

    /** 父回复抢先于熔断轮询完成时，成功出口仍须按失败计数重建连接并拒绝未知工具结果。 */
    @Test
    void shouldRejectParentSuccessWhenMcpFailureRacesCircuitPolling() {
        AtomicInteger registrations = new AtomicInteger();
        McpServerConfig server = new McpServerConfig();
        server.setTransport(McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT);
        server.setEnableTools(List.of("sandbox_execute_bash"));
        McpSandboxConfiguration sandbox = new McpSandboxConfiguration(
                "aio-sandbox",
                server,
                (toolkit, servers, listener) -> {
                    AgentTool tool = registrations.incrementAndGet() == 1
                            ? new AlwaysFailingMcpTool()
                            : new ProbeSuccessMcpTool();
                    toolkit.registerAgentTool(tool);
                    listener.onCompleted(McpServerRegistrationResult.success(
                            "aio-sandbox", McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT
                    ));
                }
        );
        Toolkit toolkit = new Toolkit();
        sandbox.applyTo(HarnessAgent.builder(), toolkit, "rd");
        AgentTool shell = toolkit.getTool("sandbox_execute_bash");
        ToolUseBlock use = new ToolUseBlock("mcp-race", "sandbox_execute_bash", Map.of("cmd", "pwd"));
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).build();
        AtomicInteger cancellations = new AtomicInteger();
        AgentScopeAgentGateway.AgentRuntime runtime = new AgentScopeAgentGateway.AgentRuntime() {
            /** 在父调用内部制造连接失败，然后立即返回表面成功回复。 */
            @Override
            public Mono<Msg> call(String content, RuntimeContext context) {
                return shell.callAsync(param).thenReturn(Msg.builder().textContent("表面成功").build());
            }

            /** 记录未知结果触发的父回合取消。 */
            @Override
            public void cancel(RuntimeContext context, TaskSemanticsRegistry.CancelOrigin origin) {
                cancellations.incrementAndGet();
            }

            /** 该一次性测试运行时没有可清理的会话状态。 */
            @Override
            public void clearContext(String userId, String sessionId) {
            }

            /** 该一次性测试运行时没有需要关闭的资源。 */
            @Override
            public void close() {
            }
        };
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime),
                AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                sandbox,
                Duration.ofSeconds(10),
                Duration.ofMillis(5),
                Duration.ofSeconds(10)
        );

        CompletableFuture<AgentReply> future = gateway.execute(new AgentRequest(
                "rd", "team-rd", "session-mcp-race", "执行代码实现", List.of(),
                Map.of("routingKey", "team:rd", "projectId", "todo-mvp")
        ));

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AgentExecutionException.class)
                .rootCause()
                .extracting(failure -> ((AgentExecutionException) failure).failureType())
                .isEqualTo(AgentExecutionException.FailureType.MCP_CONNECTION_UNAVAILABLE);
        assertThat(registrations).hasValue(2);
        assertThat(sandbox.hasUnavailableConnection("rd")).isFalse();
        assertThat(cancellations).hasValue(1);
    }

    /** 流式模型活动持续刷新时，唯一 RD 父回合不得被静态 session 日志误判为停滞。 */
    @Test
    void shouldKeepSingleParentAliveWhileModelActivityContinues() {
        ModelActivityRegistry registry = new ModelActivityRegistry();
        ActivityRuntime runtime = new ActivityRuntime(ignored -> Flux.interval(Duration.ofMillis(5))
                .doOnNext(sequence -> registry.recordActivity("rd"))
                .then(Mono.never()));
        AgentScopeAgentGateway gateway = activityAwareGateway(Map.of("rd", runtime), registry);

        CompletableFuture<AgentReply> future = gateway.execute(request("rd", "session-streaming"));

        assertThatThrownBy(() -> future.get(150, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(gateway.activeExecutionDiagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.parentProgressSource()).isEqualTo("MODEL_STREAM");
            assertThat(diagnostic.parentModelActivityAt()).isNotNull();
            assertThat(diagnostic.parentLastProgressAt())
                    .isEqualTo(diagnostic.parentModelActivityAt())
                    .isAfterOrEqualTo(diagnostic.parentSessionProgressAt());
        });
        assertThat(future.cancel(true)).isTrue();
        assertThat(runtime.cancellations).hasValue(1);
    }

    /** 模型活动停止刷新后，静态父回合仍必须按 PARENT_STALLED 类型终止。 */
    @Test
    void shouldFailParentAfterModelActivityStops() {
        ModelActivityRegistry registry = new ModelActivityRegistry();
        ActivityRuntime runtime = new ActivityRuntime(ignored -> Flux.interval(Duration.ofMillis(5))
                .take(3)
                .doOnNext(sequence -> registry.recordActivity("rd"))
                .then(Mono.never()));
        AgentScopeAgentGateway gateway = activityAwareGateway(Map.of("rd", runtime), registry);

        CompletableFuture<AgentReply> future = gateway.execute(request("rd", "session-stopped"));

        assertFailureType(future, AgentExecutionException.FailureType.PARENT_STALLED);
    }

    /** QA 的模型分片只能续租 QA，不能替没有活动的 RD 父回合续租。 */
    @Test
    void shouldAttributeModelActivityToOwningRoleOnly() {
        ModelActivityRegistry registry = new ModelActivityRegistry();
        ActivityRuntime rdRuntime = new ActivityRuntime(ignored -> Mono.never());
        ActivityRuntime qaRuntime = new ActivityRuntime(ignored -> Flux.interval(Duration.ofMillis(5))
                .doOnNext(sequence -> registry.recordActivity("qa"))
                .then(Mono.never()));
        AgentScopeAgentGateway gateway = activityAwareGateway(
                Map.of("rd", rdRuntime, "qa", qaRuntime), registry
        );

        CompletableFuture<AgentReply> rdFuture = gateway.execute(request("rd", "session-rd-static"));
        CompletableFuture<AgentReply> qaFuture = gateway.execute(request("qa", "session-qa-streaming"));

        assertFailureType(rdFuture, AgentExecutionException.FailureType.PARENT_STALLED);
        assertThatThrownBy(() -> qaFuture.get(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(qaFuture.cancel(true)).isTrue();
    }

    /** 同角色并发父回合不得采信角色级活动，以免一个 session 给另一个 session 续租。 */
    @Test
    void shouldIgnoreRoleActivityWhenTwoParentSessionsAreActive() {
        ModelActivityRegistry registry = new ModelActivityRegistry();
        ActivityRuntime runtime = new ActivityRuntime(ignored -> Mono.never());
        AgentScopeAgentGateway gateway = activityAwareGateway(Map.of("rd", runtime), registry);
        CompletableFuture<AgentReply> first = gateway.execute(request("rd", "session-first"));
        CompletableFuture<AgentReply> second = gateway.execute(request("rd", "session-second"));
        registry.recordActivity("rd");
        Instant modelActivityAt = registry.lastActivity("rd").orElseThrow();

        assertThat(gateway.activeExecutionDiagnostics())
                .allSatisfy(diagnostic -> assertThat(diagnostic.parentLastProgressAt())
                        .isBefore(modelActivityAt));
        assertFailureType(first, AgentExecutionException.FailureType.PARENT_STALLED);
        assertFailureType(second, AgentExecutionException.FailureType.PARENT_STALLED);
    }

    /** 宽限复查时故障自行消失应继续父回合，不得由 Reactor null mapper 触发 NPE。 */
    @Test
    void shouldKeepParentAliveWhenFailureDisappearsDuringGrace() {
        AtomicInteger diagnosticReads = new AtomicInteger();
        ActivityRuntime runtime = new ActivityRuntime(ignored -> Mono.never()) {
            /** 首次返回取消终态，后续复查返回健康空列表。 */
            @Override
            public List<AgentScopeAgentGateway.ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
                if (diagnosticReads.incrementAndGet() == 1) {
                    Instant now = Instant.now();
                    return List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                            "transient", "code_impl", "CANCELLED", true, now,
                            "SEND", "MODEL_REQUESTED", now
                    ));
                }
                return List.of();
            }

            /** 持续提供父日志活动，避免测试被真正的父停滞条件干扰。 */
            @Override
            public Instant parentProgressAt(RuntimeContext context) {
                return Instant.now();
            }
        };
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(
                Map.of("rd", runtime), AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                McpSandboxConfiguration.disabled(), Duration.ofMillis(5), Duration.ofMillis(20),
                Duration.ofSeconds(1), new ModelActivityRegistry()
        );

        CompletableFuture<AgentReply> future = gateway.execute(request("rd", "session-transient"));

        assertThatThrownBy(() -> future.get(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(future.cancel(true)).isTrue();
    }

    /** 使用短时序和共享活动注册表创建零模型 Gateway。 */
    private static AgentScopeAgentGateway activityAwareGateway(
            Map<String, AgentScopeAgentGateway.AgentRuntime> runtimes,
            ModelActivityRegistry registry
    ) {
        return new AgentScopeAgentGateway(
                runtimes, AgentScopeAgentGateway.RuntimeMode.PRODUCTION,
                (sessionId, routingKey, userMessage, assistantReply, turnTimestampMs) -> { },
                McpSandboxConfiguration.disabled(), Duration.ofMillis(5), Duration.ofMillis(5),
                Duration.ofMillis(40), registry
        );
    }

    /** 创建只包含角色和稳定路由属性的父请求。 */
    private static AgentRequest request(String role, String sessionId) {
        return new AgentRequest(
                role, "team-" + role, sessionId, "执行任务", List.of(),
                Map.of("routingKey", "team:" + role, "projectId", "todo-mvp")
        );
    }

    /** 断言 Future 以指定 Agent 执行故障类型结束。 */
    private static void assertFailureType(
            CompletableFuture<AgentReply> future,
            AgentExecutionException.FailureType expected
    ) {
        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(AgentExecutionException.class)
                .rootCause()
                .extracting(failure -> ((AgentExecutionException) failure).failureType())
                .isEqualTo(expected);
    }

    /** 返回自定义异步调用且不持久化会话的零模型测试运行时。 */
    private static class ActivityRuntime implements AgentScopeAgentGateway.AgentRuntime {

        /** 根据 RuntimeContext 创建当前父回合信号。 */
        private final Function<RuntimeContext, Mono<Msg>> invocation;

        /** 收到父子级联取消的次数。 */
        private final AtomicInteger cancellations = new AtomicInteger();

        /** 使用指定调用函数创建活动测试运行时。 */
        private ActivityRuntime(Function<RuntimeContext, Mono<Msg>> invocation) {
            this.invocation = invocation;
        }

        /** 执行测试预设的异步父回合。 */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            return invocation.apply(context);
        }

        /** 记录父失败或测试清理触发的取消。 */
        @Override
        public void cancel(RuntimeContext context, TaskSemanticsRegistry.CancelOrigin origin) {
            cancellations.incrementAndGet();
        }

        /** 活动测试运行时没有需要清理的持久化会话。 */
        @Override
        public void clearContext(String userId, String sessionId) {
        }

        /** 活动测试运行时没有需要关闭的资源。 */
        @Override
        public void close() {
        }
    }

    /** 每次调用都返回连接错误的 MCP 测试工具。 */
    private static final class AlwaysFailingMcpTool implements AgentTool {

        /** 返回生产工具名称。 */
        @Override
        public String getName() {
            return "sandbox_execute_bash";
        }

        /** 返回测试工具说明。 */
        @Override
        public String getDescription() {
            return "模拟首次 MCP 连接故障";
        }

        /** 返回最小输入 schema。 */
        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object");
        }

        /** 返回不含真实连接信息的错误结果。 */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(ToolResultBlock.error("connection unavailable"));
        }
    }

    /** 恢复探针成功的 MCP 测试工具。 */
    private static final class ProbeSuccessMcpTool implements AgentTool {

        /** 记录恢复探针调用次数。 */
        private final AtomicInteger calls = new AtomicInteger();

        /** 返回生产工具名称。 */
        @Override
        public String getName() {
            return "sandbox_execute_bash";
        }

        /** 返回恢复测试工具说明。 */
        @Override
        public String getDescription() {
            return "模拟连接恢复探针成功";
        }

        /** 返回最小输入 schema。 */
        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object");
        }

        /** 恢复探针返回成功；原始业务命令不会在该委托上重放。 */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            return Mono.just(ToolResultBlock.text("probe-ok"));
        }
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
        public void cancel(
                RuntimeContext currentContext,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
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
        public void cancel(
                RuntimeContext context,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
            cancellations.incrementAndGet();
            cancelledContext = context;
        }

        /** 返回不含任务正文的运行中子任务诊断。 */
        @Override
        public List<AgentScopeAgentGateway.ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            return List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                    "task-code", "code_impl", "RUNNING", false, java.time.Instant.EPOCH,
                    "SPAWN", "NONE", java.time.Instant.now()
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
        public void cancel(
                RuntimeContext context,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
            cancellations.incrementAndGet();
        }

        /** 返回一个已取消且处于终态的 code_impl 子任务。 */
        @Override
        public List<AgentScopeAgentGateway.ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            return List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                    "task-cancelled", "code_impl", "CANCELLED", true, java.time.Instant.EPOCH,
                    "SEND", "MODEL_REQUESTED", java.time.Instant.EPOCH
            ));
        }

        /** 本测试不需要清理持久会话。 */
        @Override
        public void clearContext(String userId, String sessionId) { }

        /** 本测试运行时没有外部资源。 */
        @Override
        public void close() { }
    }

    /** 返回旧取消任务和更新运行中 continuation，验证最新任务权威性。 */
    private static final class LatestContinuationRuntime implements AgentScopeAgentGateway.AgentRuntime {

        /** 收到的父子级联取消次数。 */
        private final AtomicInteger cancellations = new AtomicInteger();

        /** 保持父回合活跃，直到测试主动取消。 */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            return Mono.never();
        }

        /** 记录测试收尾触发的取消。 */
        @Override
        public void cancel(
                RuntimeContext context,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
            cancellations.incrementAndGet();
        }

        /** 返回同一 agentId 的旧取消 spawn 和较新运行中 send。 */
        @Override
        public List<AgentScopeAgentGateway.ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            return List.of(
                    new AgentScopeAgentGateway.ChildTaskDiagnostic(
                            "task-1", "code_impl", "CANCELLED", true, java.time.Instant.EPOCH,
                            "SPAWN", "MODEL_REQUESTED", java.time.Instant.EPOCH
                    ),
                    new AgentScopeAgentGateway.ChildTaskDiagnostic(
                            "task-2", "code_impl", "RUNNING", false,
                            java.time.Instant.EPOCH.plusSeconds(1), "SEND", "NONE", java.time.Instant.now()
                    )
            );
        }

        /** 本测试不需要清理持久会话。 */
        @Override
        public void clearContext(String userId, String sessionId) { }

        /** 本测试运行时没有外部资源。 */
        @Override
        public void close() { }
    }

    /** 返回真实进度时间固定在过去的 RUNNING 子任务，用于验证租约到期路径。 */
    private static final class StalledChildRuntime implements AgentScopeAgentGateway.AgentRuntime {

        /** 收到的父子级联取消次数。 */
        private final AtomicInteger cancellations = new AtomicInteger();

        /** 固定的最后进展时间，保证宽限期复查仍指向同一故障。 */
        private final java.time.Instant lastProgressAt = java.time.Instant.now().minusSeconds(60);

        /** 保持父回合活跃，等待 Gateway 根据进度租约主动终止。 */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            return Mono.never();
        }

        /** 记录租约到期触发的父子级联取消。 */
        @Override
        public void cancel(
                RuntimeContext context,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
            cancellations.incrementAndGet();
        }

        /** 返回最后真实进度早于测试租约的 RUNNING 子任务。 */
        @Override
        public List<AgentScopeAgentGateway.ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            return List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                    "task-stalled", "code_impl", "RUNNING", false, lastProgressAt,
                    "SEND", "NONE", lastProgressAt
            ));
        }

        /** 本测试不需要清理持久会话。 */
        @Override
        public void clearContext(String userId, String sessionId) { }

        /** 本测试运行时没有外部资源。 */
        @Override
        public void close() { }
    }

    /** 返回两个同 agentId 的运行中任务，用于验证 late-arrival 单飞兜底。 */
    private static final class DuplicateContinuationRuntime implements AgentScopeAgentGateway.AgentRuntime {

        /** 收到的父子级联取消次数。 */
        private final AtomicInteger cancellations = new AtomicInteger();

        /** 保持父回合悬挂，等待 Gateway 识别重复 continuation。 */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            return Mono.never();
        }

        /** 记录重复任务故障触发的父子级联取消。 */
        @Override
        public void cancel(
                RuntimeContext context,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
            cancellations.incrementAndGet();
        }

        /** 返回同一 code_impl 下两个同时运行的任务。 */
        @Override
        public List<AgentScopeAgentGateway.ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            java.time.Instant now = java.time.Instant.now();
            return List.of(
                    new AgentScopeAgentGateway.ChildTaskDiagnostic(
                            "task-first", "code_impl", "RUNNING", false,
                            now.minusSeconds(2), "SPAWN", "NONE", now
                    ),
                    new AgentScopeAgentGateway.ChildTaskDiagnostic(
                            "task-second", "code_impl", "RUNNING", false,
                            now.minusSeconds(1), "SEND", "NONE", now
                    )
            );
        }

        /** 本测试不需要清理持久会话。 */
        @Override
        public void clearContext(String userId, String sessionId) { }

        /** 本测试运行时没有外部资源。 */
        @Override
        public void close() { }
    }

    /** 父调用立即返回但子任务稍后终态的运行时，用于验证顶层单飞生命周期。 */
    private static final class CompletingParentRuntime implements AgentScopeAgentGateway.AgentRuntime {

        /** 控制子任务是否已成功完成。 */
        private final AtomicBoolean completed = new AtomicBoolean();

        /** 立即生成父回复，Gateway 仍应等待子任务。 */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            return Mono.just(Msg.builder().textContent("parent-done").build());
        }

        /** 根据测试开关返回 RUNNING 或 COMPLETED。 */
        @Override
        public List<AgentScopeAgentGateway.ChildTaskDiagnostic> taskDiagnostics(RuntimeContext context) {
            boolean terminal = completed.get();
            String status = terminal ? "COMPLETED" : "RUNNING";
            return List.of(new AgentScopeAgentGateway.ChildTaskDiagnostic(
                    "task-code", "code_impl", status, terminal, java.time.Instant.now(),
                    "SPAWN", "NONE", java.time.Instant.now()
            ));
        }

        /** 返回父 Agent 自身的稳定进度时间。 */
        @Override
        public java.time.Instant parentProgressAt(RuntimeContext context) {
            return java.time.Instant.now();
        }

        /** 本测试不需要清理持久会话。 */
        @Override
        public void clearContext(String userId, String sessionId) { }

        /** 本测试运行时没有外部资源。 */
        @Override
        public void close() { }
    }

    /** 返回永不结束且没有子任务的父回合，用于验证父 Agent 独立进度租约。 */
    private static final class ParentStalledRuntime implements AgentScopeAgentGateway.AgentRuntime {

        /** 收到的父回合取消次数。 */
        private final AtomicInteger cancellations = new AtomicInteger();

        /** 保持父回合悬挂，等待独立父进度租约终止。 */
        @Override
        public Mono<Msg> call(String content, RuntimeContext context) {
            return Mono.never();
        }

        /** 记录父进度租约到期触发的取消。 */
        @Override
        public void cancel(
                RuntimeContext context,
                TaskSemanticsRegistry.CancelOrigin origin
        ) {
            cancellations.incrementAndGet();
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
