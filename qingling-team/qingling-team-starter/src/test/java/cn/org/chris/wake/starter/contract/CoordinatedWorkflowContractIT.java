package cn.org.chris.wake.starter.contract;

import cn.org.chris.wake.app.workflow.WorkCompletionCoordinator;
import cn.org.chris.wake.app.workflow.WorkflowOutboxProjector;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.workflow.OutboxActionStatus;
import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.ReviewRoundStatus;
import cn.org.chris.wake.domain.workflow.WorkExecution;
import cn.org.chris.wake.domain.workflow.WorkExecutionStatus;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import cn.org.chris.wake.domain.workflow.WorkflowRuntimeState;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecution;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecutionRegistry;
import cn.org.chris.wake.infra.agentscope.RegistryProjectTestEvidenceRepository;
import cn.org.chris.wake.infra.persistence.cron.FileCronJobRepository;
import cn.org.chris.wake.infra.persistence.event.JsonlEventRepository;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.workflow.FileWorkflowRuntimeRepository;
import cn.org.chris.wake.infra.persistence.workflow.LegacyWorkflowProjectionGateway;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import cn.org.chris.wake.starter.runtime.CoordinatedWorkflowToolCoordinator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不调用模型或网络，以真实文件仓储和 runtime v2 协调器验证 STANDARD 主链确定性收敛。
 */
class CoordinatedWorkflowContractIT {

    /** 测试项目稳定标识。 */
    private static final String PROJECT_ID = "coordinated-contract";

    /** Todo 固定验收需求标识集合。 */
    private static final Set<String> TODO_REQUIREMENT_IDS = Set.of(
            "TODO-API-001", "TODO-API-002", "TODO-API-003", "TODO-API-004",
            "TODO-API-005", "TODO-API-006", "TODO-WEB-001", "TODO-DATA-001"
    );

    /** 各业务阶段唯一核心产物。 */
    private static final Map<WorkflowStage, String> STAGE_ARTIFACTS = stageArtifacts();

    /** JUnit 隔离临时目录。 */
    @TempDir
    Path tempRoot;

    /** 验证零模型路径经过固定阶段、真实测试证据和投影副作用后无悬挂状态。 */
    @Test
    void shouldCompleteStandardWorkflowWithoutModelOrNetwork() throws IOException {
        Path workspaceRoot = tempRoot.resolve("workspace");
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FileWorkspaceRepository workspaceRepository = new FileWorkspaceRepository(workspaceRoot);
        workspaceRepository.initializeProject(PROJECT_ID);
        FileWorkflowRuntimeRepository runtimeRepository = new FileWorkflowRuntimeRepository(
                workspaceRoot, objectMapper
        );
        FileMailboxRepository mailboxRepository = new FileMailboxRepository(workspaceRoot, objectMapper);
        MailboxService mailboxService = new MailboxService(mailboxRepository);
        JsonlEventRepository eventRepository = new JsonlEventRepository(workspaceRoot, objectMapper);
        FileCronJobRepository cronRepository = new FileCronJobRepository(
                tempRoot.resolve("runtime/tasks.json"), objectMapper
        );
        ProjectTestExecutionRegistry testRegistry = new ProjectTestExecutionRegistry();
        AtomicInteger executionSequence = new AtomicInteger();
        AtomicInteger reviewSequence = new AtomicInteger();
        WorkCompletionCoordinator completionCoordinator = new WorkCompletionCoordinator(
                runtimeRepository,
                new RegistryProjectTestEvidenceRepository(testRegistry),
                workspaceRepository,
                () -> "work-contract-" + executionSequence.incrementAndGet(),
                () -> "review-contract-" + reviewSequence.incrementAndGet(),
                java.time.Clock.systemUTC(),
                Duration.ofMinutes(10)
        );
        LegacyWorkflowProjectionGateway projectionGateway = new LegacyWorkflowProjectionGateway(
                eventRepository, mailboxRepository, cronRepository
        );
        WorkflowOutboxProjector outboxProjector = new WorkflowOutboxProjector(
                runtimeRepository, workspaceRepository, projectionGateway
        );
        CoordinatedWorkflowToolCoordinator toolCoordinator = new CoordinatedWorkflowToolCoordinator(
                runtimeRepository, completionCoordinator, outboxProjector,
                java.time.Clock.systemUTC(), true,
                WorkflowProfile.STANDARD, ReviewPolicy.REQUIRED_ONLY
        );

        toolCoordinator.initializeProject(PROJECT_ID, "Todo 固定验收契约");
        toolCoordinator.resolveCheckpoint(PROJECT_ID, "checkpoint_approved");
        consumeAllWakeJobs(cronRepository);

        runStage(
                WorkflowStage.PRODUCT_DESIGN, "pm", "# 产品规格\n" + requirementReferences(),
                workspaceRepository, runtimeRepository, mailboxService,
                cronRepository, testRegistry, toolCoordinator
        );
        runStage(
                WorkflowStage.TECH_DESIGN, "rd", "# 技术设计\nFastAPI + SQLite + 单文件 HTML",
                workspaceRepository, runtimeRepository, mailboxService,
                cronRepository, testRegistry, toolCoordinator
        );
        runStage(
                WorkflowStage.CODE_IMPLEMENTATION, "rd", "app = object()\n",
                workspaceRepository, runtimeRepository, mailboxService,
                cronRepository, testRegistry, toolCoordinator
        );
        runStage(
                WorkflowStage.QA_TEST_DESIGN, "qa", "# 测试计划\n" + requirementReferences(),
                workspaceRepository, runtimeRepository, mailboxService,
                cronRepository, testRegistry, toolCoordinator
        );
        runStage(
                WorkflowStage.QA_TEST_EXECUTION, "qa", "# 测试报告\n8 passed，固定验收契约全部通过。",
                workspaceRepository, runtimeRepository, mailboxService,
                cronRepository, testRegistry, toolCoordinator
        );
        completeDeliveryCheckpoint(
                runtimeRepository, mailboxService, cronRepository, toolCoordinator
        );

        WorkflowRuntimeState completed = runtimeRepository.find(PROJECT_ID).orElseThrow();
        assertThat(completed.profile()).isEqualTo(WorkflowProfile.STANDARD);
        assertThat(completed.reviewPolicy()).isEqualTo(ReviewPolicy.REQUIRED_ONLY);
        assertThat(completed.executions())
                .extracting(WorkExecution::stage)
                .containsExactly(
                        WorkflowStage.REQUIREMENTS_CHECKPOINT,
                        WorkflowStage.PRODUCT_DESIGN,
                        WorkflowStage.TECH_DESIGN,
                        WorkflowStage.CODE_IMPLEMENTATION,
                        WorkflowStage.QA_TEST_DESIGN,
                        WorkflowStage.QA_TEST_EXECUTION,
                        WorkflowStage.DELIVERY_CHECKPOINT
                );
        assertThat(completed.executions())
                .allMatch(execution -> execution.status() == WorkExecutionStatus.SUCCEEDED);
        assertThat(completed.outboxActions())
                .allMatch(action -> action.status() == OutboxActionStatus.APPLIED);
        assertThat(completed.reviewRounds())
                .noneMatch(review -> !review.status().terminal());
        assertThat(completed.reviewRounds())
                .noneMatch(review -> review.status() == ReviewRoundStatus.PENDING);
        assertThat(cronRepository.findAll()).isEmpty();
        assertAllMailboxMessagesDone(workspaceRoot, objectMapper);
        assertThat(eventRepository.readAll(PROJECT_ID))
                .extracting(event -> event.get("action"))
                .contains("project_created", "workflow_transitioned");
    }

    /** 执行一个投影任务并用真实结构化 Test Runner 终态完成需要测试证据的阶段。 */
    private static void runStage(
            WorkflowStage expectedStage,
            String role,
            String artifactContent,
            FileWorkspaceRepository workspaceRepository,
            FileWorkflowRuntimeRepository runtimeRepository,
            MailboxService mailboxService,
            FileCronJobRepository cronRepository,
            ProjectTestExecutionRegistry testRegistry,
            CoordinatedWorkflowToolCoordinator toolCoordinator
    ) {
        WorkExecution execution = currentExecution(runtimeRepository, expectedStage, role);
        MailMessage assignment = claimAssignment(mailboxService, role, execution.executionId());
        toolCoordinator.claimAndStart(
                PROJECT_ID, role, assignment.id(), assignment.content(),
                "dispatch-" + execution.executionId(), "session-" + role
        );
        String artifact = STAGE_ARTIFACTS.get(expectedStage);
        workspaceRepository.write(PROJECT_ID, role, artifact, artifactContent);
        String submittedExecutionId = execution.executionId();
        if (requiresTestEvidence(expectedStage)) {
            submittedExecutionId = "test-" + execution.executionId();
            testRegistry.record(
                    PROJECT_ID, role, expectedStage,
                    successfulTestExecution(submittedExecutionId)
            );
        }
        Map<String, Object> completion = toolCoordinator.complete(
                PROJECT_ID, role, expectedStage.name() + " 完成",
                Map.of(
                        "execution_id", submittedExecutionId,
                        "artifacts", List.of(artifact),
                        "decision", "ACCEPTED",
                        "rationale", "固定契约证据完整"
                )
        );
        assertThat(completion).containsEntry("outcome", "COMPLETED");
        mailboxService.markDone(PROJECT_ID, role, assignment.id());
        consumeAllWakeJobs(cronRepository);
    }

    /** 领取当前阶段唯一 task_assign，并校验其显式引用当前 WorkExecution。 */
    private static MailMessage claimAssignment(
            MailboxService mailboxService,
            String role,
            String executionId
    ) {
        List<MailMessage> assignments = mailboxService.readInbox(PROJECT_ID, role).stream()
                .filter(message -> "task_assign".equals(message.type()))
                .toList();
        assertThat(assignments).singleElement();
        MailMessage assignment = assignments.get(0);
        assertThat(assignment.content()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) assignment.content()).get("execution_id")).isEqualTo(executionId);
        return assignment;
    }

    /** 完成交付 checkpoint，并收敛其投影邮箱与一次性 wake。 */
    private static void completeDeliveryCheckpoint(
            FileWorkflowRuntimeRepository runtimeRepository,
            MailboxService mailboxService,
            FileCronJobRepository cronRepository,
            CoordinatedWorkflowToolCoordinator toolCoordinator
    ) {
        WorkExecution delivery = currentExecution(
                runtimeRepository, WorkflowStage.DELIVERY_CHECKPOINT, "manager"
        );
        MailMessage assignment = claimAssignment(mailboxService, "manager", delivery.executionId());
        toolCoordinator.claimAndStart(
                PROJECT_ID, "manager", assignment.id(), assignment.content(),
                "dispatch-" + delivery.executionId(), "session-manager"
        );
        Map<String, Object> result = toolCoordinator.resolveCheckpoint(
                PROJECT_ID, "delivery_approved"
        );
        assertThat(result).containsEntry("to_stage", "COMPLETED");
        mailboxService.markDone(PROJECT_ID, "manager", assignment.id());
        consumeAllWakeJobs(cronRepository);
    }

    /** 查找阶段和 Owner 唯一匹配且未终态的 WorkExecution。 */
    private static WorkExecution currentExecution(
            FileWorkflowRuntimeRepository runtimeRepository,
            WorkflowStage stage,
            String owner
    ) {
        return runtimeRepository.find(PROJECT_ID).orElseThrow().executions().stream()
                .filter(execution -> execution.stage() == stage)
                .filter(execution -> execution.owner().equals(owner))
                .filter(execution -> !execution.status().terminal())
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少当前执行: " + stage + "/" + owner));
    }

    /** 构造字段完整且不包含输出正文的成功 Test Runner 终态。 */
    private static ProjectTestExecution successfulTestExecution(String executionId) {
        return new ProjectTestExecution(
                executionId, ProjectTestExecution.Status.SUCCEEDED, 0,
                TODO_REQUIREMENT_IDS.size(), TODO_REQUIREMENT_IDS.size(), 91D,
                ProjectTestExecution.FailureType.NONE, false, "", ""
        );
    }

    /** 判断阶段是否受真实 Test Runner 证据门禁约束。 */
    private static boolean requiresTestEvidence(WorkflowStage stage) {
        return stage == WorkflowStage.CODE_IMPLEMENTATION
                || stage == WorkflowStage.DEFECT_FIX
                || stage == WorkflowStage.QA_TEST_EXECUTION;
    }

    /** 模拟一次性 wake 被调度器消费，确保测试结束没有孤儿 Cron。 */
    private static void consumeAllWakeJobs(FileCronJobRepository cronRepository) {
        cronRepository.findAll().forEach(job -> cronRepository.delete(job.id()));
    }

    /** 校验四个角色邮箱不存在 unread 或 in_progress 残留。 */
    private static void assertAllMailboxMessagesDone(
            Path workspaceRoot,
            ObjectMapper objectMapper
    ) throws IOException {
        for (String role : List.of("manager", "pm", "rd", "qa")) {
            Path inbox = workspaceRoot.resolve(
                    "shared/projects/" + PROJECT_ID + "/mailboxes/" + role + ".json"
            );
            List<Map<String, Object>> messages = objectMapper.readValue(
                    Files.readString(inbox), new TypeReference<>() { }
            );
            assertThat(messages)
                    .allMatch(message -> "done".equals(message.get("status")));
        }
    }

    /** 返回包含八个固定需求标识的稳定引用文本。 */
    private static String requirementReferences() {
        return String.join("\n", TODO_REQUIREMENT_IDS.stream().sorted().toList());
    }

    /** 创建各阶段核心产物的不可变映射。 */
    private static Map<WorkflowStage, String> stageArtifacts() {
        EnumMap<WorkflowStage, String> artifacts = new EnumMap<>(WorkflowStage.class);
        artifacts.put(WorkflowStage.PRODUCT_DESIGN, "design/product_spec.md");
        artifacts.put(WorkflowStage.TECH_DESIGN, "tech/tech_design.md");
        artifacts.put(WorkflowStage.CODE_IMPLEMENTATION, "code/main.py");
        artifacts.put(WorkflowStage.QA_TEST_DESIGN, "qa/test_plan.md");
        artifacts.put(WorkflowStage.QA_TEST_EXECUTION, "qa/test_report.md");
        return Map.copyOf(artifacts);
    }
}
