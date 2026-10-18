package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.MailMessage;
import cn.org.chris.wake.domain.model.AgentExecutionException;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecution;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecutionRegistry;
import cn.org.chris.wake.infra.persistence.event.JsonlEventRepository;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Manager 只能提交结论，实际阶段与任务分派由 Java 状态机生成。
 */
class DeterministicWorkflowToolsTest {

    /** JUnit 隔离工作区。 */
    @TempDir
    Path temporaryDirectory;

    /** JSON 响应解析器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 真实文件邮箱服务。 */
    private MailboxService mailbox;

    /** 真实文件共享工作区。 */
    private FileWorkspaceRepository workspace;

    /** 待测工作流工具。 */
    private DeterministicWorkflowTools tools;

    /** 真实文件事件服务，用于核对结构化 incident。 */
    private EventService events;

    /** 仅由测试 fixture 模拟真实 Runner 写入的执行证据账本。 */
    private ProjectTestExecutionRegistry executionRegistry;

    /** 已调度 wake 的角色列表。 */
    private final List<String> scheduledRoles = new ArrayList<>();

    /**
     * 初始化真实文件 Repository 和新项目。
     */
    @BeforeEach
    void setUp() {
        workspace = new FileWorkspaceRepository(temporaryDirectory);
        workspace.initializeProject("flow-project");
        mailbox = new MailboxService(new FileMailboxRepository(temporaryDirectory, objectMapper));
        events = new EventService(new JsonlEventRepository(temporaryDirectory, objectMapper));
        executionRegistry = new ProjectTestExecutionRegistry();
        tools = new DeterministicWorkflowTools(
                events,
                mailbox,
                (role, projectId) -> {
                    scheduledRoles.add(role);
                    return "wake-" + role;
                },
                new TaskCompletionEvidenceGate(workspace, executionRegistry),
                objectMapper
        );
    }

    /**
     * checkpoint 批准和 PM 接受后应依次由 Java 分派 PM 与 RD 技术设计。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldDispatchOnlyStateMachineSelectedTasks() throws Exception {
        JsonNode product = json(tools.advanceWorkflow(
                "flow-project", "checkpoint_approved", "", Map.of(), ""
        ));
        JsonNode tech = json(tools.advanceWorkflow(
                "flow-project", "stage_accepted", "pm", Map.of("artifacts", List.of("design/product_spec.md")), "通过"
        ));

        assertThat(product.path("to_stage").asText()).isEqualTo("PRODUCT_DESIGN");
        assertThat(tech.path("to_stage").asText()).isEqualTo("TECH_DESIGN");
        assertThat(scheduledRoles).containsExactly("pm", "rd");
        assertThat(mailbox.readInbox("flow-project", "pm"))
                .extracting(MailMessage::subject).containsExactly("产品设计 (第 1 轮)");
        assertThat(mailbox.readInbox("flow-project", "rd"))
                .extracting(MailMessage::subject).containsExactly("技术方案设计 (第 1 轮)");
    }

    /**
     * 技术方案接受后状态机必须进入代码实现，且代码证据不足时不得进入 QA。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldBlockQaUntilRdExecutionEvidenceIsComplete() throws Exception {
        tools.advanceWorkflow("flow-project", "checkpoint_approved", "", Map.of(), "");
        tools.advanceWorkflow("flow-project", "stage_accepted", "pm", Map.of(), "");
        JsonNode code = json(tools.advanceWorkflow(
                "flow-project", "stage_accepted", "rd", Map.of("artifacts", List.of("tech/tech_design.md")), ""
        ));
        JsonNode rejected = json(tools.advanceWorkflow(
                "flow-project", "stage_accepted", "rd", Map.of("pytest_status", "not_executed"), ""
        ));

        assertThat(code.path("to_stage").asText()).isEqualTo("CODE_IMPLEMENTATION");
        assertThat(rejected.path("errcode").asInt()).isEqualTo(1);
        assertThat(rejected.path("errmsg").asText()).contains("execution_id");
        assertThat(tools.currentStage("flow-project")).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
    }

    /**
     * 非当前阶段 Owner 不得冒用评审信号推进 Java 状态机。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldRejectSignalFromWrongStageOwner() throws Exception {
        tools.advanceWorkflow("flow-project", "checkpoint_approved", "", Map.of(), "");

        JsonNode rejected = json(tools.advanceWorkflow(
                "flow-project", "stage_accepted", "rd", Map.of(), "越权推进"
        ));

        assertThat(rejected.path("errcode").asInt()).isEqualTo(1);
        assertThat(rejected.path("errmsg").asText()).contains("必须来自角色 pm");
        assertThat(tools.currentStage("flow-project")).isEqualTo(WorkflowStage.PRODUCT_DESIGN);
        assertThat(mailbox.readInbox("flow-project", "rd")).isEmpty();
    }

    /**
     * RD 通过 send_mail 回报成功时也必须满足退出码、pytest 数量和覆盖率硬门禁。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldEnforceRdEvidenceAtSendMailEntry() throws Exception {
        tools.advanceWorkflow("flow-project", "checkpoint_approved", "", Map.of(), "");
        tools.advanceWorkflow("flow-project", "stage_accepted", "pm", Map.of(), "");
        MailMessage techAssignment = mailbox.readInbox("flow-project", "rd").get(0);
        mailbox.markDone("flow-project", "rd", techAssignment.id());
        tools.advanceWorkflow("flow-project", "stage_accepted", "rd", Map.of(), "");
        MailMessage codeAssignment = mailbox.readInbox("flow-project", "rd").get(0);
        tools.reportToolExecutionFailure(
                "flow-project", "rd", "run_project_tests", "execution-complete", "MCP_TRANSPORT", true, 1
        );
        MailMessage recoveryAssignment = mailbox.readInbox("flow-project", "rd").stream()
                .filter(message -> "恢复代码实现工具执行".equals(message.subject()))
                .findFirst()
                .orElseThrow();
        CommonTeamTools rdTools = new CommonTeamTools(
                "rd", mailbox, workspace,
                (role, projectId) -> "wake-" + role,
                objectMapper, tools
        );
        assertThat(tools.currentStage("flow-project")).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);

        JsonNode rejected = json(rdTools.sendMail(
                "manager", "task_done", "代码实现完成",
                Map.of("exit_code", 0, "pytest_passed", 0, "coverage_percent", 88),
                "flow-project"
        ));
        ProjectTestExecution rdSuccess = successfulExecution("execution-complete", 12, 88D);
        executionRegistry.record(
                "flow-project", "rd", WorkflowStage.CODE_IMPLEMENTATION, rdSuccess
        );
        workspace.write("flow-project", "rd", "code/main.py", "print('ready')\n");
        JsonNode wrongRecipient = json(rdTools.sendMail(
                "qa", "task_done", "代码实现完成",
                evidence(rdSuccess),
                "flow-project"
        ));
        JsonNode prematureDone = json(rdTools.markDone("flow-project", codeAssignment.id()));
        JsonNode accepted = json(rdTools.sendMail(
                "manager", "task_done", "代码实现完成",
                objectMapper.writeValueAsString(evidence(rdSuccess)),
                "flow-project"
        ));
        JsonNode repeatedDone = json(rdTools.markDone("flow-project", codeAssignment.id()));

        assertThat(rejected.path("errcode").asInt()).isEqualTo(1);
        assertThat(rejected.path("errmsg").asText()).contains("execution_id");
        assertThat(wrongRecipient.path("errcode").asInt()).isEqualTo(1);
        assertThat(wrongRecipient.path("errmsg").asText()).contains("必须发送给 manager");
        assertThat(prematureDone.path("errcode").asInt()).isEqualTo(1);
        assertThat(prematureDone.path("errmsg").asText()).contains("不能单独 mark_done");
        assertThat(accepted.path("errcode").asInt())
                .withFailMessage(accepted.toPrettyString())
                .isZero();
        assertThat(accepted.path("completed_assignment_id").asText()).isEqualTo(recoveryAssignment.id());
        assertThat(accepted.path("completed_assignment_ids")).extracting(JsonNode::asText)
                .containsExactly(codeAssignment.id(), recoveryAssignment.id());
        assertThat(repeatedDone.path("errcode").asInt()).isZero();
        assertThat(repeatedDone.path("already_done").asBoolean()).isTrue();
        assertThat(mailbox.findMessage("flow-project", "rd", codeAssignment.id()))
                .get()
                .extracting(MailMessage::status)
                .isEqualTo(cn.org.chris.wake.domain.model.MailStatus.DONE);
        assertThat(mailbox.findMessage("flow-project", "rd", recoveryAssignment.id()))
                .get()
                .extracting(MailMessage::status)
                .isEqualTo(cn.org.chris.wake.domain.model.MailStatus.DONE);
        assertThat(mailbox.readInbox("flow-project", "manager"))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.subject()).isEqualTo("代码实现完成");
                    assertThat(message.content()).isInstanceOf(Map.class);
                });
        assertThat(events.readAll("flow-project"))
                .anySatisfy(event -> {
                    assertThat(event.get("action")).isEqualTo("completion_rejected");
                    assertThat(event.get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                            .containsEntry("role", "rd")
                            .containsEntry("workflow_stage", "CODE_IMPLEMENTATION");
                });
    }

    /**
     * QA 通过 send_mail 回报成功前必须落盘测试报告和证据矩阵。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldEnforceQaArtifactsAtSendMailEntry() throws Exception {
        tools.advanceWorkflow("flow-project", "checkpoint_approved", "", Map.of(), "");
        tools.advanceWorkflow("flow-project", "stage_accepted", "pm", Map.of(), "");
        MailMessage techAssignment = mailbox.readInbox("flow-project", "rd").get(0);
        mailbox.markDone("flow-project", "rd", techAssignment.id());
        tools.advanceWorkflow("flow-project", "stage_accepted", "rd", Map.of(), "");
        MailMessage codeAssignment = mailbox.readInbox("flow-project", "rd").get(0);
        mailbox.markDone("flow-project", "rd", codeAssignment.id());
        ProjectTestExecution rdSuccess = successfulExecution("rd-code-success", 12, 88D);
        executionRegistry.record(
                "flow-project", "rd", WorkflowStage.CODE_IMPLEMENTATION, rdSuccess
        );
        workspace.write("flow-project", "rd", "code/main.py", "print('ready')\n");
        tools.advanceWorkflow(
                "flow-project", "stage_accepted", "rd",
                evidence(rdSuccess), ""
        );
        MailMessage designAssignment = mailbox.readInbox("flow-project", "qa").get(0);
        mailbox.markDone("flow-project", "qa", designAssignment.id());
        tools.advanceWorkflow("flow-project", "stage_accepted", "qa", Map.of(), "");
        MailMessage executionAssignment = mailbox.readInbox("flow-project", "qa").get(0);
        CommonTeamTools qaTools = new CommonTeamTools(
                "qa", mailbox, workspace,
                (role, projectId) -> "wake-" + role,
                objectMapper, tools
        );
        assertThat(tools.currentStage("flow-project")).isEqualTo(WorkflowStage.QA_TEST_EXECUTION);
        ProjectTestExecution qaSuccess = successfulExecution("qa-contract-success", 6, 91D);
        executionRegistry.record(
                "flow-project", "qa", WorkflowStage.QA_TEST_EXECUTION, qaSuccess
        );

        JsonNode rejected = json(qaTools.sendMail(
                "manager", "task_done", "测试执行完成", evidence(qaSuccess), "flow-project"
        ));
        workspace.write("flow-project", "qa", "qa/test_report.md", "# 测试报告\n全部通过\n");
        workspace.write("flow-project", "qa", "qa/evidence_matrix.md", "# 证据矩阵\n| 用例 | 证据 |\n");
        JsonNode accepted = json(qaTools.sendMail(
                "manager", "task_done", "测试执行完成", evidence(qaSuccess), "flow-project"
        ));

        assertThat(rejected.path("errcode").asInt())
                .withFailMessage(rejected.toPrettyString())
                .isEqualTo(1);
        assertThat(rejected.path("errmsg").asText()).contains("qa/test_report.md");
        assertThat(accepted.path("errcode").asInt()).isZero();
        assertThat(accepted.path("completed_assignment_id").asText()).isEqualTo(executionAssignment.id());
        assertThat(mailbox.readInbox("flow-project", "manager"))
                .extracting(MailMessage::subject)
                .containsExactly("测试执行完成");
    }

    /**
     * Manager 的澄清答复必须由 Java 保持代码实现阶段、生成结构化继续任务并重新唤醒 RD。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldResumeOriginalOwnerThroughClarificationProtocol() throws Exception {
        tools.advanceWorkflow("flow-project", "checkpoint_approved", "", Map.of(), "");
        tools.advanceWorkflow("flow-project", "stage_accepted", "pm", Map.of(), "");
        tools.advanceWorkflow("flow-project", "stage_accepted", "rd", Map.of(), "");

        String answer = "请改用绝对导入并重新执行测试";
        JsonNode first = json(tools.advanceWorkflow(
                "flow-project", "clarification_answered", "rd", Map.of(), answer
        ));
        JsonNode repeated = json(tools.advanceWorkflow(
                "flow-project", "clarification_answered", "rd", Map.of(), answer
        ));

        assertThat(first.path("from_stage").asText()).isEqualTo("CODE_IMPLEMENTATION");
        assertThat(first.path("to_stage").asText()).isEqualTo("CODE_IMPLEMENTATION");
        assertThat(first.path("assignee").asText()).isEqualTo("rd");
        assertThat(first.path("subject").asText()).isEqualTo("继续代码实现");
        assertThat(first.path("scheduled_wake").asText()).isEqualTo("wake-rd");
        assertThat(repeated.path("msg_id").asText()).isEqualTo(first.path("msg_id").asText());
        assertThat(tools.currentStage("flow-project")).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);

        List<MailMessage> continuationTasks = mailbox.readInbox("flow-project", "rd").stream()
                .filter(message -> "继续代码实现".equals(message.subject()))
                .toList();
        assertThat(continuationTasks).hasSize(1);
        JsonNode content = objectMapper.valueToTree(continuationTasks.get(0).content());
        assertThat(content.path("workflow_stage").asText()).isEqualTo("CODE_IMPLEMENTATION");
        assertThat(content.path("protocol").asText()).isEqualTo("clarification_answer");
        assertThat(content.path("resume_current_stage").asBoolean()).isTrue();
        assertThat(content.path("clarification_answer").asText()).isEqualTo(answer);
    }

    /**
     * 空答复、错误 Owner 和绕过状态机直发澄清答复都必须被拒绝。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldRejectInvalidOrDirectClarificationAnswer() throws Exception {
        tools.advanceWorkflow("flow-project", "checkpoint_approved", "", Map.of(), "");
        JsonNode blank = json(tools.advanceWorkflow(
                "flow-project", "clarification_answered", "pm", Map.of(), "  "
        ));
        JsonNode wrongOwner = json(tools.advanceWorkflow(
                "flow-project", "clarification_answered", "rd", Map.of(), "补充产品边界"
        ));
        CommonTeamTools managerTools = new CommonTeamTools(
                "manager", mailbox, workspace,
                (role, projectId) -> "wake-" + role,
                objectMapper, tools
        );
        JsonNode direct = json(managerTools.sendMail(
                "pm", "clarification_answer", "澄清答复", Map.of("answer", "补充产品边界"), "flow-project"
        ));

        assertThat(blank.path("errmsg").asText()).contains("非空 feedback");
        assertThat(wrongOwner.path("errmsg").asText()).contains("必须来自角色 pm");
        assertThat(direct.path("errmsg").asText()).contains("clarification_answered");
        assertThat(tools.currentStage("flow-project")).isEqualTo(WorkflowStage.PRODUCT_DESIGN);
    }

    /**
     * 首次工具故障应生成唯一恢复任务，第二次同类失败应 BLOCKED 并禁止进入 QA。
     *
     * @throws Exception JSON 解析失败
     */
    @Test
    void shouldRecordIdempotentToolIncidentWakeOwnerAndBlockAfterExhaustion() throws Exception {
        tools.advanceWorkflow("flow-project", "checkpoint_approved", "", Map.of(), "");
        tools.advanceWorkflow("flow-project", "stage_accepted", "pm", Map.of(), "");
        tools.advanceWorkflow("flow-project", "stage_accepted", "rd", Map.of(), "");

        tools.reportToolExecutionFailure(
                "flow-project", "rd", "run_project_tests", "execution-1", "MCP_TRANSPORT", true, 1
        );
        tools.reportToolExecutionFailure(
                "flow-project", "rd", "run_project_tests", "execution-1", "MCP_TRANSPORT", true, 1
        );

        assertThat(tools.currentStage("flow-project")).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(mailbox.readInbox("flow-project", "rd").stream()
                .filter(message -> "恢复代码实现工具执行".equals(message.subject())))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content().toString()).contains("tool_execution_failed");
                    assertThat(message.content().toString()).contains("execution-1");
                });
        assertThat(events.readAll("flow-project").stream()
                .filter(event -> "tool_execution_failed".equals(event.get("action"))))
                .hasSize(1);

        tools.reportToolExecutionFailure(
                "flow-project", "rd", "run_project_tests", "execution-1", "MCP_TRANSPORT", false, 2
        );
        JsonNode rejected = json(tools.advanceWorkflow(
                "flow-project",
                "stage_accepted",
                "rd",
                Map.of("exit_code", 0, "pytest_passed", 12, "coverage_percent", 88),
                ""
        ));

        assertThat(tools.isRuntimeBlocked("flow-project")).isTrue();
        assertThat(tools.currentStage("flow-project")).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(rejected.path("errmsg").asText()).contains("BLOCKED");
        assertThat(mailbox.readInbox("flow-project", "qa")).isEmpty();
        assertThat(events.readAll("flow-project").stream()
                .filter(event -> "tool_execution_failed".equals(event.get("action"))))
                .hasSize(2);
    }

    /** Agent 父回合首次失败必须释放租约并恢复原 Owner，第二个 occurrence 必须明确阻断。 */
    @Test
    void shouldRecoverAndThenBlockAgentExecutionFailureByStableWorkflowExecution() {
        tools.advanceWorkflow("flow-project", "checkpoint_approved", "", Map.of(), "");
        tools.advanceWorkflow("flow-project", "stage_accepted", "pm", Map.of(), "");
        MailMessage techAssignment = mailbox.readInbox("flow-project", "rd").get(0);
        mailbox.markDone("flow-project", "rd", techAssignment.id());
        tools.advanceWorkflow("flow-project", "stage_accepted", "rd", Map.of(), "");
        MailMessage codeAssignment = mailbox.readInbox("flow-project", "rd").get(0);

        tools.reportAgentExecutionFailure(
                "flow-project", "rd", "dispatch-1",
                AgentExecutionException.FailureType.CHILD_CANCELLED
        );
        tools.reportAgentExecutionFailure(
                "flow-project", "rd", "dispatch-1",
                AgentExecutionException.FailureType.CHILD_CANCELLED
        );

        assertThat(mailbox.findMessage("flow-project", "rd", codeAssignment.id()))
                .get()
                .extracting(MailMessage::status)
                .isEqualTo(cn.org.chris.wake.domain.model.MailStatus.UNREAD);
        assertThat(mailbox.readInbox("flow-project", "rd").stream()
                .filter(message -> "恢复代码实现工具执行".equals(message.subject())))
                .singleElement();
        assertThat(events.readAll("flow-project").stream()
                .filter(event -> "tool_execution_failed".equals(event.get("action"))))
                .singleElement()
                .satisfies(event -> assertThat(event.get("payload"))
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("tool", "agentscope_dispatch")
                        .containsEntry("occurrence_id", "dispatch-1")
                        .containsEntry("runtime_status", "RECOVERING"));

        tools.reportAgentExecutionFailure(
                "flow-project", "rd", "dispatch-2",
                AgentExecutionException.FailureType.CHILD_CANCELLED
        );

        assertThat(tools.isRuntimeBlocked("flow-project")).isTrue();
        assertThat(tools.currentStage("flow-project")).isEqualTo(WorkflowStage.CODE_IMPLEMENTATION);
        assertThat(events.readAll("flow-project").stream()
                .filter(event -> "tool_execution_failed".equals(event.get("action"))))
                .hasSize(2);
    }

    /** 初始 checkpoint 尚无迁移事件时也必须生成稳定 execution ID，而不是丢失故障。 */
    @Test
    void shouldReportAgentFailureBeforeFirstWorkflowTransition() {
        tools.reportAgentExecutionFailure(
                "flow-project", "manager", "checkpoint-dispatch-1",
                AgentExecutionException.FailureType.CHILD_FAILED
        );

        assertThat(events.readAll("flow-project"))
                .singleElement()
                .satisfies(event -> assertThat(event.get("payload"))
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("execution_id", "agentscope:flow-project:0:REQUIREMENTS_CHECKPOINT:manager")
                        .containsEntry("runtime_status", "RECOVERING"));
        assertThat(scheduledRoles).containsExactly("manager");
    }

    /**
     * 解析工具 JSON 响应。
     *
     * @param value JSON 文本
     * @return JSON 树
     * @throws Exception 解析失败
     */
    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    /**
     * 创建 pytest 全绿的真实执行 fixture。
     *
     * @param executionId 执行标识
     * @param testCount 收集并通过的用例数
     * @param coverage 覆盖率百分比
     * @return 成功执行终态
     */
    private static ProjectTestExecution successfulExecution(
            String executionId,
            int testCount,
            double coverage
    ) {
        return new ProjectTestExecution(
                executionId,
                ProjectTestExecution.Status.SUCCEEDED,
                0,
                testCount,
                testCount,
                coverage,
                ProjectTestExecution.FailureType.NONE,
                false,
                "",
                ""
        );
    }

    /**
     * 把真实执行转换为模型必须原样回传的完成证据。
     *
     * @param execution 已登记测试终态
     * @return 结构化 task_done 证据
     */
    private static Map<String, Object> evidence(ProjectTestExecution execution) {
        return Map.of(
                "execution_id", execution.executionId(),
                "status", execution.status().name(),
                "exit_code", execution.exitCode(),
                "pytest_collected", execution.pytestCollected(),
                "pytest_passed", execution.pytestPassed(),
                "coverage_percent", execution.coveragePercent(),
                "failure_type", execution.failureType().name(),
                "retryable", execution.retryable()
        );
    }
}
