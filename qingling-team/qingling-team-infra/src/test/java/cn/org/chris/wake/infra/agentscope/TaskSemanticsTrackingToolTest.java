package cn.org.chris.wake.infra.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 AgentScope 子任务工具的同 session 单飞、去重与真实进度租约。
 */
class TaskSemanticsTrackingToolTest {

    /** 同一 label 的 agent_send 在已有 RUNNING task 时必须复用 taskId，不能调用第二次原始工具。 */
    @Test
    void shouldDeduplicateContinuationWhileSameAgentSessionIsRunning() {
        FakeTaskRepository repository = new FakeTaskRepository();
        TaskSemanticsRegistry registry = TaskSemanticsRegistry.forRepository(repository);
        RuntimeContext context = RuntimeContext.builder()
                .userId("team-rd")
                .sessionId("session-code")
                .build();
        AtomicInteger spawnCalls = new AtomicInteger();
        AtomicInteger sendCalls = new AtomicInteger();
        AgentTool spawnDelegate = new FakeAgentTool("agent_spawn", param -> {
            spawnCalls.incrementAndGet();
            repository.addRunning("task-first", "code_impl");
            return ToolResultBlock.text("agent_key: agent:code:1\nstatus: accepted\ntask_id: task-first");
        });
        AgentTool sendDelegate = new FakeAgentTool("agent_send", param -> {
            sendCalls.incrementAndGet();
            return ToolResultBlock.text("status: accepted\ntask_id: task-second");
        });
        TaskSemanticsTrackingTool spawn = new TaskSemanticsTrackingTool(spawnDelegate, registry);
        TaskSemanticsTrackingTool send = new TaskSemanticsTrackingTool(sendDelegate, registry);

        ToolResultBlock first = spawn.callAsync(toolParam(
                context,
                "call-spawn",
                "agent_spawn",
                Map.of("agent_id", "code_impl", "label", "code_impl_r1", "task", "实现")
        )).block();
        ToolResultBlock duplicate = send.callAsync(toolParam(
                context,
                "call-send",
                "agent_send",
                Map.of("label", "code_impl_r1", "message", "继续")
        )).block();

        assertThat(first).isNotNull();
        assertThat(duplicate).isNotNull();
        assertThat(spawnCalls).hasValue(1);
        assertThat(sendCalls).hasValue(0);
        assertThat(duplicate.getOutput().toString())
                .contains("already_running", "task-first", "deduplicated: true");
    }

    /** 真实后台 spawn 只返回 taskId 且存在多个活跃子代理时，必须按 spawn registry 精确去重。 */
    @Test
    void shouldDeduplicateAgentKeyUsingPersistedSpawnRegistry() {
        FakeTaskRepository repository = new FakeTaskRepository();
        TaskSemanticsRegistry registry = TaskSemanticsRegistry.forRepository(repository);
        RuntimeContext context = RuntimeContext.builder()
                .userId("team-rd")
                .sessionId("session-agent-key")
                .build();
        ToolContextState toolState = ToolContextState.builder().build();
        toolState.putSpawnEntry(
                "agent:code:known",
                new ToolContextState.SpawnEntry(
                        "agent:code:known", "code_impl", "session-agent-key", null, 1
                )
        );
        context.setAgentState(AgentState.builder()
                .userId("team-rd")
                .sessionId("session-agent-key")
                .toolContext(toolState)
                .build());
        AtomicInteger sendCalls = new AtomicInteger();
        AgentTool spawnDelegate = new FakeAgentTool("agent_spawn", param -> {
            repository.addRunning("task-real-shape", "code_impl");
            repository.addRunning("task-other-agent", "mailbox_ops");
            return ToolResultBlock.text("status: accepted\ntask_id: task-real-shape");
        });
        AgentTool sendDelegate = new FakeAgentTool("agent_send", param -> {
            sendCalls.incrementAndGet();
            return ToolResultBlock.text("status: accepted\ntask_id: duplicate");
        });
        TaskSemanticsTrackingTool spawn = new TaskSemanticsTrackingTool(spawnDelegate, registry);
        TaskSemanticsTrackingTool send = new TaskSemanticsTrackingTool(sendDelegate, registry);

        spawn.callAsync(toolParam(
                context,
                "call-real-spawn",
                "agent_spawn",
                Map.of("agent_id", "code_impl", "task", "实现")
        )).block();
        ToolResultBlock duplicate = send.callAsync(toolParam(
                context,
                "call-agent-key-send",
                "agent_send",
                Map.of("agent_key", "agent:code:known", "message", "继续")
        )).block();

        assertThat(duplicate).isNotNull();
        assertThat(duplicate.getOutput().toString())
                .contains("already_running", "task-real-shape", "deduplicated: true");
        assertThat(sendCalls).hasValue(0);
    }

    /** spawn 与 send 的长同步等待参数必须在 Java 边界归零，避免后台调用撞上 PT5M。 */
    @Test
    void shouldForceSpawnAndSendIntoImmediateBackgroundExecution() {
        FakeTaskRepository repository = new FakeTaskRepository();
        TaskSemanticsRegistry registry = TaskSemanticsRegistry.forRepository(repository);
        RuntimeContext context = RuntimeContext.builder()
                .userId("team-rd")
                .sessionId("session-background")
                .build();
        List<ToolCallParam> delegated = new ArrayList<>();
        TaskSemanticsTrackingTool spawn = new TaskSemanticsTrackingTool(
                new FakeAgentTool("agent_spawn", param -> {
                    delegated.add(param);
                    return ToolResultBlock.text("status: accepted\ntask_id: task-spawn");
                }),
                registry
        );
        TaskSemanticsTrackingTool send = new TaskSemanticsTrackingTool(
                new FakeAgentTool("agent_send", param -> {
                    delegated.add(param);
                    return ToolResultBlock.text("status: accepted\ntask_id: task-send");
                }),
                registry
        );

        spawn.callAsync(toolParam(
                context, "call-spawn-background", "agent_spawn",
                Map.of("agent_id", "code_impl", "task", "实现", "timeout_seconds", 600)
        )).block();
        repository.tasks.clear();
        send.callAsync(toolParam(
                context, "call-send-background", "agent_send",
                Map.of("agent_key", "agent:code:1", "message", "继续", "timeout_seconds", 600)
        )).block();

        assertThat(delegated).hasSize(2);
        assertThat(delegated).allSatisfy(param -> {
            assertThat(param.getInput()).containsEntry("timeout_seconds", 0);
            assertThat(param.getToolUseBlock().getInput()).containsEntry("timeout_seconds", 0);
        });
    }

    /** 外层取消 spawn 后必须保留有界单飞占位，并在真实任务延迟出现后返回其 taskId。 */
    @Test
    void shouldKeepReservationUntilCancelledSpawnAppearsInRepository() {
        FakeTaskRepository repository = new FakeTaskRepository();
        TaskSemanticsRegistry registry = TaskSemanticsRegistry.forRepository(repository);
        RuntimeContext context = RuntimeContext.builder()
                .userId("team-rd")
                .sessionId("session-late-spawn")
                .build();
        AtomicInteger delegateCalls = new AtomicInteger();
        AgentTool delayedSpawn = new AgentTool() {
            /** 返回真实内置工具名。 */
            @Override
            public String getName() {
                return "agent_spawn";
            }

            /** 返回延迟落地测试说明。 */
            @Override
            public String getDescription() {
                return "模拟被外层取消但仍可能在后台落地的 spawn";
            }

            /** 返回接受任意对象的最小参数 schema。 */
            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            /** 记录唯一委托并保持调用未完成，直到测试主动取消订阅。 */
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                delegateCalls.incrementAndGet();
                return Mono.never();
            }
        };
        TaskSemanticsTrackingTool tracked = new TaskSemanticsTrackingTool(delayedSpawn, registry);
        ToolCallParam param = toolParam(
                context,
                "call-late-spawn",
                "agent_spawn",
                Map.of("agent_id", "code_impl", "task", "继续实现")
        );

        Disposable first = tracked.callAsync(param).subscribe();
        first.dispose();
        ToolResultBlock reserved = tracked.callAsync(param).block();
        repository.addRunning("task-late", "code_impl");
        ToolResultBlock resolved = tracked.callAsync(param).block();

        assertThat(delegateCalls).hasValue(1);
        assertThat(reserved).isNotNull();
        assertThat(reserved.getOutput().toString()).contains("already_running", "deduplicated: true");
        assertThat(resolved).isNotNull();
        assertThat(resolved.getOutput().toString())
                .contains("already_running", "task-late", "deduplicated: true");
    }

    /** 相同稳定指纹不得因轮询续租，只有真实事件指纹变化才更新最后进展时间。 */
    @Test
    void shouldRenewProgressLeaseOnlyWhenStableEvidenceChanges() {
        FakeTaskRepository repository = new FakeTaskRepository();
        TaskSemanticsRegistry registry = TaskSemanticsRegistry.forRepository(repository);
        Instant createdAt = Instant.parse("2026-10-17T00:40:00Z");
        Instant firstPoll = Instant.parse("2026-10-17T00:45:00Z");
        Instant heartbeatPoll = Instant.parse("2026-10-17T00:50:00Z");
        Instant eventPoll = Instant.parse("2026-10-17T00:51:00Z");

        TaskSemanticsRegistry.ProgressLease first = registry.observeProgress(
                "task-running", "RUNNING|event=3|bytes=120", createdAt, firstPoll
        );
        TaskSemanticsRegistry.ProgressLease heartbeat = registry.observeProgress(
                "task-running", "RUNNING|event=3|bytes=120", heartbeatPoll, heartbeatPoll
        );
        TaskSemanticsRegistry.ProgressLease progressed = registry.observeProgress(
                "task-running", "RUNNING|event=4|bytes=180", eventPoll, eventPoll
        );

        assertThat(first.lastProgressAt()).isEqualTo(createdAt);
        assertThat(heartbeat.lastProgressAt()).isEqualTo(createdAt);
        assertThat(progressed.lastProgressAt()).isEqualTo(eventPoll);
    }

    /** 创建保留工具调用 id/name 的测试参数。 */
    private static ToolCallParam toolParam(
            RuntimeContext context,
            String callId,
            String toolName,
            Map<String, Object> input
    ) {
        ToolUseBlock use = new ToolUseBlock(callId, toolName, input, Map.of());
        return ToolCallParam.builder()
                .toolUseBlock(use)
                .input(input)
                .runtimeContext(context)
                .build();
    }

    /** 提供可编程结果且记录调用次数的最小 AgentTool。 */
    private record FakeAgentTool(
            /** 工具名。 */ String name,
            /** 根据调用参数生成结果的测试函数。 */ ToolCall call
    ) implements AgentTool {

        /** 返回测试工具名。 */
        @Override
        public String getName() {
            return name;
        }

        /** 返回测试工具说明。 */
        @Override
        public String getDescription() {
            return "单飞测试工具";
        }

        /** 返回接受任意对象的最小参数 schema。 */
        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object");
        }

        /** 委托测试函数生成同步 Mono 结果。 */
        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.just(call.invoke(param));
        }
    }

    /** 表示测试工具的一次同步调用。 */
    @FunctionalInterface
    private interface ToolCall {
        /** 根据工具参数生成结果。 */
        ToolResultBlock invoke(ToolCallParam param);
    }

    /** 只保存测试创建任务的内存 TaskRepository。 */
    private static final class FakeTaskRepository implements TaskRepository {

        /** 当前父 session 可见的后台任务。 */
        private final List<BackgroundTask> tasks = new ArrayList<>();

        /** 添加一个永不自行结束的 RUNNING 任务。 */
        private void addRunning(String taskId, String agentId) {
            tasks.add(new BackgroundTask(taskId, agentId, new CompletableFuture<>()));
        }

        /** 按 taskId 返回测试任务。 */
        @Override
        public BackgroundTask getTask(RuntimeContext context, String parentSessionId, String taskId) {
            return tasks.stream().filter(task -> taskId.equals(task.getTaskId())).findFirst().orElse(null);
        }

        /** 本测试不通过 TaskRepository 创建任务。 */
        @Override
        public BackgroundTask putTask(
                RuntimeContext context,
                String taskId,
                String agentId,
                String parentSessionId,
                TaskRunSpec runSpec
        ) {
            throw new UnsupportedOperationException("测试未使用 putTask");
        }

        /** 返回可选状态过滤后的任务快照。 */
        @Override
        public Collection<BackgroundTask> listTasks(
                RuntimeContext context,
                String parentSessionId,
                TaskStatus status
        ) {
            if (status == null) {
                return List.copyOf(tasks);
            }
            return tasks.stream().filter(task -> task.getTaskStatus() == status).toList();
        }

        /** 取消测试任务并返回是否找到目标。 */
        @Override
        public boolean cancelTask(RuntimeContext context, String parentSessionId, String taskId) {
            BackgroundTask task = getTask(context, parentSessionId, taskId);
            return task != null && task.cancel(true);
        }
    }
}
