package cn.org.chris.wake.starter.runtime;

import cn.org.chris.wake.app.workflow.WorkCompletionCoordinator;
import cn.org.chris.wake.app.workflow.WorkflowOutboxProjector;
import cn.org.chris.wake.domain.gateway.ProjectTestEvidenceRepository;
import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.domain.workflow.OutboxActionStatus;
import cn.org.chris.wake.infra.persistence.workflow.FileWorkflowRuntimeRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecution;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecutionRegistry;
import cn.org.chris.wake.infra.agentscope.RegistryProjectTestEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 AgentScope 最小完成协议只引用 execution，并由 Java 权威状态幂等收敛。
 */
class CoordinatedWorkflowToolCoordinatorTest {

    /** 测试项目标识。 */
    private static final String PROJECT_ID = "coordinated-tool-project";

    /** 初始 PM WorkExecution 标识。 */
    private static final String EXECUTION_ID = "work-product";

    /** 测试 runtime 创建时间。 */
    private static final Instant CREATED_AT = Instant.parse("2026-10-25T00:00:00Z");

    /** JUnit 隔离工作区。 */
    @TempDir
    Path workspaceRoot;

    /** 真实文件工作区仓储。 */
    private FileWorkspaceRepository workspaceRepository;

    /** 真实 runtime v2 文件仓储。 */
    private FileWorkflowRuntimeRepository runtimeRepository;

    /** 待测工具协调适配器。 */
    private CoordinatedWorkflowToolCoordinator toolCoordinator;

    /**
     * 创建运行中的 PM 执行、真实产物和无副作用 outbox 投影器。
     */
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        workspaceRepository = new FileWorkspaceRepository(workspaceRoot);
        workspaceRepository.initializeProject(PROJECT_ID);
        workspaceRepository.write(PROJECT_ID, "pm", "design/product_spec.md", "# 产品设计");
        runtimeRepository = new FileWorkflowRuntimeRepository(
                workspaceRoot, objectMapper, Clock.fixed(CREATED_AT.plusSeconds(5), ZoneOffset.UTC)
        );
        WorkExecution running = WorkExecution.create(
                        EXECUTION_ID, PROJECT_ID, WorkflowStage.PRODUCT_DESIGN,
                        "pm", WorkflowProfile.STANDARD, CREATED_AT
                )
                .claim("assignment-product", CREATED_AT.plusSeconds(1))
                .start("dispatch-product", "session-product", CREATED_AT.plusSeconds(2));
        runtimeRepository.create(WorkflowRuntimeState.initialize(
                PROJECT_ID, WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY, CREATED_AT
        ).addExecution(running, CREATED_AT.plusSeconds(2)));
        AtomicInteger nextExecution = new AtomicInteger();
        AtomicInteger nextReview = new AtomicInteger();
        ProjectTestEvidenceRepository noEvidence = (projectId, role, stage, executionId) ->
                java.util.Optional.empty();
        WorkCompletionCoordinator completionCoordinator = new WorkCompletionCoordinator(
                runtimeRepository, noEvidence, workspaceRepository,
                () -> "work-next-" + nextExecution.incrementAndGet(),
                () -> "review-next-" + nextReview.incrementAndGet(),
                Clock.fixed(CREATED_AT.plusSeconds(5), ZoneOffset.UTC), Duration.ofMinutes(10)
        );
        WorkflowOutboxProjector projector = new WorkflowOutboxProjector(
                runtimeRepository, workspaceRepository, action -> { },
                Clock.fixed(CREATED_AT.plusSeconds(6), ZoneOffset.UTC), 3
        );
        toolCoordinator = new CoordinatedWorkflowToolCoordinator(
                runtimeRepository, completionCoordinator, projector,
                Clock.fixed(CREATED_AT.plusSeconds(5), ZoneOffset.UTC)
        );
    }

    /**
     * 验证最小正文重复提交返回相同结果，且嵌套旧测试复制字段只留下字段名审计。
     */
    @Test
    void shouldReplayMinimalTaskDoneWithoutDuplicatingWorkflowFacts() {
        Map<String, Object> content = Map.of(
                "execution_id", EXECUTION_ID,
                "artifacts", List.of("design/product_spec.md"),
                "decision", "accepted",
                "rationale", "产品设计完成",
                "evidence", Map.of("status", "FORGED", "exit_code", 99)
        );

        Map<String, Object> first = toolCoordinator.complete(
                PROJECT_ID, "pm", "产品设计完成", content
        );
        Map<String, Object> repeated = toolCoordinator.complete(
                PROJECT_ID, "pm", "产品设计完成", content
        );

        WorkflowRuntimeState state = runtimeRepository.find(PROJECT_ID).orElseThrow();
        assertThat(repeated).isEqualTo(first);
        assertThat(first.get("outcome")).isEqualTo("COMPLETED");
        assertThat(first.get("ignored_legacy_test_fields"))
                .isEqualTo(List.of("status", "exit_code"));
        assertThat(state.executions()).hasSize(2);
        assertThat(state.findExecution(EXECUTION_ID).orElseThrow().status())
                .isEqualTo(WorkExecutionStatus.SUCCEEDED);
        assertThat(state.outboxActions()).filteredOn(action ->
                action.attributes().getOrDefault("action", "")
                        .equals("task_done_legacy_fields_ignored")
        ).singleElement().satisfies(action -> {
            assertThat(action.attributes()).containsEntry("fields", "status,exit_code");
            assertThat(action.attributes().values()).doesNotContain("FORGED", "99");
        });
    }

    /** 非法完成结论必须返回全部允许值和角色用法，禁止模型盲猜 Java 枚举。 */
    @Test
    void shouldDescribeAllowedCompletionDecisionsWhenDecisionIsInvalid() {
        assertThatThrownBy(() -> toolCoordinator.complete(
                PROJECT_ID,
                "pm",
                "产品设计完成",
                Map.of(
                        "execution_id", EXECUTION_ID,
                        "artifacts", List.of("design/product_spec.md"),
                        "decision", "SUCCESS",
                        "rationale", "产品设计完成"
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("允许值仅为 ACCEPTED 或 DEFECT_FOUND")
                .hasMessageContaining("PM/RD 正常完成必须使用 ACCEPTED")
                .hasMessageContaining("QA 真实测试发现缺陷");
    }

    /** 验证评审回包缺少 review_round_id 时在工具边界直接拒绝。 */
    @Test
    void shouldRequireReviewRoundIdForReviewDone() {
        assertThatThrownBy(() -> toolCoordinator.submitReview(
                PROJECT_ID, "rd", Map.of("verdict", "APPROVED")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("review_round_id");
    }

    /** 新项目工具入口必须真正创建并投影冻结 profile 的 runtime v2 aggregate。 */
    @Test
    void shouldInitializeRuntimeV2AggregateFromProjectEntryPoint() {
        String projectId = "coordinated-new-project";
        workspaceRepository.initializeProject(projectId);
        Map<String, Object> initialized = toolCoordinator.initializeProject(projectId, "新项目");

        WorkflowRuntimeState state = runtimeRepository.find(projectId).orElseThrow();
        assertThat(initialized)
                .containsEntry("coordinated", true)
                .containsEntry("profile", "STANDARD")
                .containsEntry("review_policy", "REQUIRED_ONLY");
        assertThat(state.executions()).singleElement().satisfies(execution -> {
            assertThat(execution.stage()).isEqualTo(WorkflowStage.REQUIREMENTS_CHECKPOINT);
            assertThat(execution.owner()).isEqualTo("manager");
            assertThat(execution.executionId()).isEqualTo(initialized.get("execution_id"));
        });
        assertThat(state.outboxActions()).singleElement().satisfies(action -> {
            assertThat(action.status()).isEqualTo(OutboxActionStatus.APPLIED);
            assertThat(action.attributes()).containsEntry("action", "project_created");
        });
    }

    /**
     * 验证同一 RD WorkExecution 首次测试失败后可引用新的 Test Runner execution 再次完成。
     */
    @Test
    void shouldReevaluateNewTestExecutionAfterBusinessFailure() {
        String projectId = "coordinated-test-retry";
        String workExecutionId = "work-code";
        workspaceRepository.initializeProject(projectId);
        workspaceRepository.write(projectId, "rd", "code/main.py", "app = object()\n");
        WorkExecution running = WorkExecution.create(
                        workExecutionId, projectId, WorkflowStage.CODE_IMPLEMENTATION,
                        "rd", WorkflowProfile.STANDARD, CREATED_AT
                )
                .claim("assignment-code", CREATED_AT.plusSeconds(1))
                .start("dispatch-code", "session-code", CREATED_AT.plusSeconds(2));
        runtimeRepository.create(WorkflowRuntimeState.initialize(
                projectId, WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY, CREATED_AT
        ).addExecution(running, CREATED_AT.plusSeconds(2)));
        ProjectTestExecutionRegistry registry = new ProjectTestExecutionRegistry();
        registry.record(projectId, "rd", WorkflowStage.CODE_IMPLEMENTATION, execution(
                "test-failed", ProjectTestExecution.Status.TEST_FAILED, 1,
                ProjectTestExecution.FailureType.PYTEST_FAILURE
        ));
        AtomicInteger nextExecution = new AtomicInteger();
        WorkCompletionCoordinator completionCoordinator = new WorkCompletionCoordinator(
                runtimeRepository, new RegistryProjectTestEvidenceRepository(registry), workspaceRepository,
                () -> "work-qa-" + nextExecution.incrementAndGet(),
                () -> "review-unused",
                Clock.fixed(CREATED_AT.plusSeconds(5), ZoneOffset.UTC), Duration.ofMinutes(10)
        );
        WorkflowOutboxProjector projector = new WorkflowOutboxProjector(
                runtimeRepository, workspaceRepository, action -> { },
                Clock.fixed(CREATED_AT.plusSeconds(6), ZoneOffset.UTC), 3
        );
        CoordinatedWorkflowToolCoordinator retryCoordinator = new CoordinatedWorkflowToolCoordinator(
                runtimeRepository, completionCoordinator, projector,
                Clock.fixed(CREATED_AT.plusSeconds(5), ZoneOffset.UTC)
        );

        retryCoordinator.beginTestExecution(
                projectId, "rd", WorkflowStage.CODE_IMPLEMENTATION.name(), "test-failed"
        );
        Map<String, Object> failed = retryCoordinator.complete(
                projectId, "rd", "首次测试失败", Map.of(
                        "execution_id", "test-failed",
                        "artifacts", List.of("code/main.py"),
                        "decision", "ACCEPTED",
                        "rationale", "业务断言失败"
                )
        );
        registry.record(projectId, "rd", WorkflowStage.CODE_IMPLEMENTATION, execution(
                "test-succeeded", ProjectTestExecution.Status.SUCCEEDED, 0,
                ProjectTestExecution.FailureType.NONE
        ));
        retryCoordinator.beginTestExecution(
                projectId, "rd", WorkflowStage.CODE_IMPLEMENTATION.name(), "test-succeeded"
        );
        Map<String, Object> succeeded = retryCoordinator.complete(
                projectId, "rd", "修复后测试成功", Map.of(
                        "execution_id", "test-succeeded",
                        "artifacts", List.of("code/main.py"),
                        "decision", "ACCEPTED",
                        "rationale", "业务修复完成"
                )
        );

        WorkflowRuntimeState state = runtimeRepository.find(projectId).orElseThrow();
        assertThat(failed.get("outcome")).isEqualTo("TEST_FAILED");
        assertThat(succeeded.get("outcome")).isEqualTo("COMPLETED");
        assertThat(state.findExecution(workExecutionId).orElseThrow()).satisfies(execution -> {
            assertThat(execution.status()).isEqualTo(WorkExecutionStatus.SUCCEEDED);
            assertThat(execution.testExecutionId()).isEqualTo("test-succeeded");
        });
        assertThat(state.processedCommands()).hasSize(4);
        assertThat(state.processedCommands()).filteredOn(command -> "TEST_STARTED".equals(command.outcomeCode()))
                .hasSize(2);
    }

    /** 创建不含输出正文的 Test Runner 终态。 */
    private static ProjectTestExecution execution(
            String executionId,
            ProjectTestExecution.Status status,
            int exitCode,
            ProjectTestExecution.FailureType failureType
    ) {
        return new ProjectTestExecution(
                executionId, status, exitCode, 3,
                status == ProjectTestExecution.Status.SUCCEEDED ? 3 : 2,
                85D, failureType, false, "", ""
        );
    }
}
