package cn.org.chris.wake.infra.persistence.workflow;

import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository.WorkflowCommandMutation;
import cn.org.chris.wake.domain.gateway.WorkflowRuntimeRepository.WorkflowCommandReceipt;
import cn.org.chris.wake.domain.workflow.OutboxAction;
import cn.org.chris.wake.domain.workflow.OutboxActionId;
import cn.org.chris.wake.domain.workflow.OutboxActionType;
import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkflowCommandId;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 runtime v2 文件仓储的显式创建、原子提交、并发去重和重启恢复。
 */
class FileWorkflowRuntimeRepositoryTest {

    /** 固定 aggregate 创建时间。 */
    private static final Instant START = Instant.parse("2026-10-25T00:00:00Z");

    /** 固定仓储命令处理时间。 */
    private static final Instant COMMAND_TIME = START.plusSeconds(100);

    /**
     * 只读查询 legacy 项目不得创建 runtime 目录、锁文件或 v2 半成品。
     *
     * @param workspace 临时 workspace
     * @throws Exception 创建测试目录失败
     */
    @Test
    void shouldNotCreateV2StateWhileReadingLegacyProject(@TempDir Path workspace) throws Exception {
        Path legacyProject = workspace.resolve("shared/projects/project-1");
        Files.createDirectories(legacyProject);
        FileWorkflowRuntimeRepository repository = repository(workspace);

        assertThat(repository.find("project-1")).isEmpty();
        assertThat(legacyProject.resolve("runtime")).doesNotExist();
    }

    /**
     * 显式创建必须写入 schema=2 的单 aggregate，并可由新仓储实例完整恢复。
     *
     * @param workspace 临时 workspace
     * @throws Exception 读取持久化文件失败
     */
    @Test
    void shouldCreateAndReloadSingleAggregate(@TempDir Path workspace) throws Exception {
        FileWorkflowRuntimeRepository repository = repository(workspace);
        WorkflowRuntimeState initial = initialState()
                .addExecution(execution(), START.plusSeconds(1))
                .enqueue(action("action-create"), START.plusSeconds(2));

        repository.create(initial);
        WorkflowRuntimeState reloaded = repository(workspace).find("project-1").orElseThrow();
        String json = Files.readString(statePath(workspace), StandardCharsets.UTF_8);

        assertThat(reloaded).isEqualTo(initial);
        assertThat(json).contains("\"schema_version\" : 2");
        assertThat(json).contains("\"PENDING\"").doesNotContain("Bearer ").doesNotContain("sk-");
    }

    /**
     * 十个并发重复 command 只能执行一次变换并产生一条 execution、outbox 和结果记录。
     *
     * @param workspace 临时 workspace
     */
    @Test
    void shouldDeduplicateConcurrentCommandUnderProjectLock(@TempDir Path workspace) {
        FileWorkflowRuntimeRepository repository = repository(workspace);
        repository.create(initialState());
        WorkflowCommandId commandId = new WorkflowCommandId("complete-work-1");
        AtomicInteger handlerCalls = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<CompletableFuture<WorkflowCommandReceipt>> futures = java.util.stream.IntStream
                    .range(0, 10)
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> repository.execute(
                            "project-1", commandId, current -> {
                                handlerCalls.incrementAndGet();
                                WorkflowRuntimeState changed = current
                                        .addExecution(execution(), START.plusSeconds(1))
                                        .enqueue(action("action-command"), START.plusSeconds(2));
                                return new WorkflowCommandMutation(changed, "CREATED", "work-1");
                            }
                    ), executor))
                    .toList();
            List<WorkflowCommandReceipt> receipts = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            WorkflowRuntimeState persisted = repository.find("project-1").orElseThrow();
            assertThat(handlerCalls).hasValue(1);
            assertThat(receipts).filteredOn(WorkflowCommandReceipt::newlyProcessed).hasSize(1);
            assertThat(receipts).extracting(receipt -> receipt.command().outcomeCode())
                    .containsOnly("CREATED");
            assertThat(persisted.executions()).hasSize(1);
            assertThat(persisted.outboxActions()).hasSize(1);
            assertThat(persisted.processedCommands()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * aggregate 提交后未投影的 action 必须跨仓储重启保持 PENDING。
     *
     * @param workspace 临时 workspace
     */
    @Test
    void shouldReplayPendingOutboxAfterRepositoryRestart(@TempDir Path workspace) {
        FileWorkflowRuntimeRepository repository = repository(workspace);
        repository.create(initialState()
                .addExecution(execution(), START.plusSeconds(1))
                .enqueue(action("action-replay"), START.plusSeconds(2)));

        WorkflowRuntimeState restarted = repository(workspace).find("project-1").orElseThrow();
        assertThat(restarted.pendingOutbox())
                .extracting(item -> item.actionId().value())
                .containsExactly("action-replay");
    }

    /**
     * 部分 action 已确认时，重启只能保留尚未确认的动作作为待重放集合。
     *
     * @param workspace 临时 workspace
     */
    @Test
    void shouldRecoverOnlyUnappliedActionAfterPartialProjection(@TempDir Path workspace) {
        FileWorkflowRuntimeRepository repository = repository(workspace);
        OutboxAction first = action("action-first");
        OutboxAction second = action("action-second");
        repository.create(initialState()
                .addExecution(execution(), START.plusSeconds(1))
                .enqueue(first, START.plusSeconds(2))
                .enqueue(second, START.plusSeconds(3)));

        repository.update("project-1", current -> current.replaceOutboxAction(
                first.applied(START.plusSeconds(4)), START.plusSeconds(4)
        ));

        WorkflowRuntimeState restarted = repository(workspace).find("project-1").orElseThrow();
        assertThat(restarted.pendingOutbox())
                .extracting(item -> item.actionId().value())
                .containsExactly("action-second");
    }

    /**
     * 模拟 move 前崩溃遗留损坏临时文件时，完整目标 aggregate 必须仍可读取。
     *
     * @param workspace 临时 workspace
     * @throws Exception 写入故障注入文件失败
     */
    @Test
    void shouldIgnoreTruncatedTemporaryFileFromPreMoveCrash(@TempDir Path workspace) throws Exception {
        FileWorkflowRuntimeRepository repository = repository(workspace);
        WorkflowRuntimeState initial = initialState();
        repository.create(initial);
        Path temporary = statePath(workspace).resolveSibling("workflow-v2.json.tmp");
        Files.writeString(temporary, "{\"schema_version\":2", StandardCharsets.UTF_8);

        assertThat(repository.find("project-1")).contains(initial);
        assertThat(Files.readString(temporary)).isEqualTo("{\"schema_version\":2");
    }

    /**
     * 未知 schema 或损坏 JSON 必须明确失败并保持原文件字节不变。
     *
     * @param workspace 临时 workspace
     * @throws Exception 写入故障文件失败
     */
    @Test
    void shouldFailFastAndPreserveUnknownOrCorruptSchema(@TempDir Path workspace) throws Exception {
        Path statePath = statePath(workspace);
        Files.createDirectories(statePath.getParent());
        String unknown = "{\"schema_version\":99,\"project_id\":\"project-1\"}";
        Files.writeString(statePath, unknown, StandardCharsets.UTF_8);
        FileWorkflowRuntimeRepository repository = repository(workspace);

        assertThatThrownBy(() -> repository.find("project-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持");
        assertThat(Files.readString(statePath)).isEqualTo(unknown);

        String corrupt = "{not-json";
        Files.writeString(statePath, corrupt, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> repository.find("project-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("读取 workflow runtime 失败");
        assertThat(Files.readString(statePath)).isEqualTo(corrupt);
    }

    /**
     * command 结果引用含凭据时必须在写盘前失败且目标文件不出现秘密。
     *
     * @param workspace 临时 workspace
     * @throws Exception 读取状态文件失败
     */
    @Test
    void shouldNeverPersistCredentialShapedCommandResult(@TempDir Path workspace) throws Exception {
        FileWorkflowRuntimeRepository repository = repository(workspace);
        repository.create(initialState());

        assertThatThrownBy(() -> repository.execute(
                "project-1", new WorkflowCommandId("command-secret"),
                current -> new WorkflowCommandMutation(current, "OK", "Bearer hidden-secret")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据");
        assertThat(Files.readString(statePath(workspace)))
                .doesNotContain("hidden-secret")
                .doesNotContain("command-secret");
    }

    /**
     * 创建使用固定 UTC 时钟的仓储。
     *
     * @param workspace workspace 根目录
     * @return 文件仓储
     */
    private static FileWorkflowRuntimeRepository repository(Path workspace) {
        return new FileWorkflowRuntimeRepository(
                workspace,
                new ObjectMapper(),
                Clock.fixed(COMMAND_TIME, ZoneOffset.UTC)
        );
    }

    /**
     * 创建固定空 aggregate。
     *
     * @return STANDARD runtime v2 aggregate
     */
    private static WorkflowRuntimeState initialState() {
        return WorkflowRuntimeState.initialize(
                "project-1", WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY, START
        );
    }

    /**
     * 创建固定代码实现工作执行。
     *
     * @return PENDING 工作执行
     */
    private static WorkExecution execution() {
        return WorkExecution.create(
                "work-1", "project-1", WorkflowStage.CODE_IMPLEMENTATION,
                "rd", WorkflowProfile.STANDARD, START
        );
    }

    /**
     * 创建固定 mailbox 投影动作。
     *
     * @param actionId 稳定动作标识
     * @return PENDING 动作
     */
    private static OutboxAction action(String actionId) {
        return OutboxAction.pending(
                new OutboxActionId(actionId), OutboxActionType.MAILBOX_MESSAGE,
                "rd", Map.of("message_type", "task_assign", "subject", "代码实现"), START
        );
    }

    /**
     * 返回测试使用的 runtime v2 固定路径。
     *
     * @param workspace workspace 根目录
     * @return 状态文件路径
     */
    private static Path statePath(Path workspace) {
        return workspace.resolve("shared/projects/project-1/runtime/workflow-v2.json");
    }
}
