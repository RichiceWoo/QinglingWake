package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.workflow.WorkflowSignal;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.domain.workflow.WorkflowStateMachine;
import cn.org.chris.wake.domain.workflow.WorkflowTransition;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 将模型提交的评审结论转换为 Java 状态机决定的唯一阶段迁移。
 */
public final class DeterministicWorkflowTools {

    /** 阶段迁移纯领域状态机。 */
    private final WorkflowStateMachine stateMachine;

    /** 项目事件服务，用于恢复和持久化当前阶段。 */
    private final EventService eventService;

    /** 团队邮箱服务，用于发送状态机决定的下一任务。 */
    private final MailboxService mailboxService;

    /** 下一任务的一次性唤醒调度器。 */
    private final CommonTeamTools.MailWakeScheduler wakeScheduler;

    /** RD/QA 完成证据门禁。 */
    private final TaskCompletionEvidenceGate completionEvidenceGate;

    /** 工具 JSON 响应编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建确定性工作流工具。
     *
     * @param eventService 项目事件服务
     * @param mailboxService 团队邮箱服务
     * @param wakeScheduler 邮件唤醒调度器
     * @param completionEvidenceGate 完成证据门禁
     * @param objectMapper JSON 编解码器
     */
    public DeterministicWorkflowTools(
            EventService eventService,
            MailboxService mailboxService,
            CommonTeamTools.MailWakeScheduler wakeScheduler,
            TaskCompletionEvidenceGate completionEvidenceGate,
            ObjectMapper objectMapper
    ) {
        this.stateMachine = new WorkflowStateMachine();
        this.eventService = Objects.requireNonNull(eventService, "eventService 不能为空");
        this.mailboxService = Objects.requireNonNull(mailboxService, "mailboxService 不能为空");
        this.wakeScheduler = Objects.requireNonNull(wakeScheduler, "wakeScheduler 不能为空");
        this.completionEvidenceGate = Objects.requireNonNull(
                completionEvidenceGate, "completionEvidenceGate 不能为空"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 接收有限评审结论，由 Java 选择目标阶段、角色和任务主题并完成分派。
     *
     * @param projectId 项目标识
     * @param signal checkpoint/评审/交付结论
     * @param fromRole 当前阶段 Owner；checkpoint 与交付结论可为空
     * @param taskDoneContent 当前 task_done 结构化正文
     * @param feedback 可选评审意见，只作为下一任务上下文
     * @return 状态迁移、下一任务和 wake 标识
     */
    @Tool(
            name = "advance_workflow",
            description = "Manager 专用：提交 checkpoint 或阶段评审结论；Java 状态机唯一决定下一阶段和任务。",
            concurrencySafe = true
    )
    public synchronized String advanceWorkflow(
            @ToolParam(name = "project_id", description = "项目 ID") String projectId,
            @ToolParam(name = "signal", description = "checkpoint_approved/checkpoint_rejected/stage_accepted/stage_revision_required/clarification_answered/qa_defect_found/delivery_approved/delivery_rejected")
            String signal,
            @ToolParam(name = "from_role", description = "当前阶段 Owner；无角色时传空字符串") String fromRole,
            @ToolParam(name = "task_done_content", description = "当前完成邮件结构化正文；无邮件时传空对象")
            Map<String, Object> taskDoneContent,
            @ToolParam(name = "feedback", description = "可选评审意见") String feedback
    ) {
        try {
            String checkedProjectId = AgentToolSupport.requireProjectId(projectId);
            assertNotBlocked(checkedProjectId);
            WorkflowStage current = currentStage(checkedProjectId);
            WorkflowSignal checkedSignal = parseSignal(signal);
            validateStageRole(current, checkedSignal, fromRole);
            validateClarificationAnswer(checkedSignal, feedback);
            validateStageEvidence(checkedProjectId, current, checkedSignal, fromRole, taskDoneContent);
            WorkflowTransition transition = stateMachine.transition(current, checkedSignal);
            Map<String, Object> payload = transitionPayload(transition, checkedSignal, fromRole, feedback);
            long sequence = eventService.appendWorkflowTransition(checkedProjectId, payload);
            String messageId = "";
            String wakeId = "";
            if (!transition.assignee().isBlank()) {
                messageId = mailboxService.send(
                        checkedProjectId,
                        transition.assignee(),
                        "manager",
                        "task_assign",
                        transition.subject(),
                        assignmentContent(transition, checkedSignal, feedback)
                );
                wakeId = wakeScheduler.schedule(transition.assignee(), checkedProjectId);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("from_stage", transition.from().name());
            response.put("to_stage", transition.to().name());
            response.put("assignee", transition.assignee());
            response.put("subject", transition.subject());
            response.put("msg_id", messageId);
            response.put("scheduled_wake", wakeId);
            response.put("event_seq", sequence);
            return AgentToolSupport.success(objectMapper, response);
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 阻止模型绕过状态机直接发流水线任务，并校验受门禁保护的成功回报。
     *
     * @param projectId 项目标识
     * @param role 发件角色
     * @param type 邮件类型
     * @param content 邮件正文
     */
    public void validateOutgoingMail(String projectId, String role, String type, Object content) {
        if ("task_assign".equals(type)) {
            throw new IllegalStateException("流水线 task_assign 只能由 advance_workflow 的 Java 状态机发送");
        }
        if ("clarification_answer".equals(type)) {
            throw new IllegalStateException("澄清答复只能由 advance_workflow 的 clarification_answered 信号发送");
        }
        if ("task_done".equals(type)) {
            WorkflowStage stage = currentStage(projectId);
            try {
                completionEvidenceGate.validate(projectId, stage, role, content);
            } catch (IllegalStateException rejection) {
                eventService.appendCompletionRejected(projectId, Map.of(
                        "role", role,
                        "workflow_stage", stage.name(),
                        "failure_type", "COMPLETION_EVIDENCE_REJECTED",
                        "reason", boundedCompletionRejectionReason(rejection)
                ));
                throw rejection;
            }
        }
    }

    /**
     * 将受控门禁异常限制为短文本，避免诊断事件携带无界内容。
     *
     * @param rejection 完成门禁异常
     * @return 不超过 512 个字符的拒绝原因
     */
    private static String boundedCompletionRejectionReason(IllegalStateException rejection) {
        String message = rejection.getMessage() == null
                ? rejection.getClass().getSimpleName()
                : rejection.getMessage();
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    /**
     * 由 Java 工具边界记录基础设施故障，并确定性恢复原 Owner 或阻断项目。
     *
     * @param projectId 项目标识
     * @param role 发生故障的当前 Owner
     * @param tool 工具名称
     * @param executionId 稳定执行标识
     * @param failureType 脱敏失败分类
     * @param retryable 本次是否仍允许恢复
     * @param attempt 同一 execution ID 的尝试序号，从 1 开始
     */
    public synchronized void reportToolExecutionFailure(
            String projectId,
            String role,
            String tool,
            String executionId,
            String failureType,
            boolean retryable,
            int attempt
    ) {
        String checkedProjectId = AgentToolSupport.requireProjectId(projectId);
        String checkedRole = AgentToolSupport.requireText(role, "role").trim().toLowerCase(Locale.ROOT);
        String checkedTool = AgentToolSupport.requireText(tool, "tool");
        String checkedExecutionId = AgentToolSupport.requireText(executionId, "execution_id");
        String checkedFailureType = AgentToolSupport.requireText(failureType, "failure_type");
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt 必须大于零");
        }
        String incidentId = checkedExecutionId + ":" + attempt;
        if (hasIncident(checkedProjectId, incidentId)) {
            return;
        }
        WorkflowStage current = currentStage(checkedProjectId);
        WorkflowSignal signal = retryable
                ? WorkflowSignal.TOOL_EXECUTION_FAILED
                : WorkflowSignal.TOOL_RETRY_EXHAUSTED;
        WorkflowTransition transition = stateMachine.transition(current, signal);
        String expectedOwner = stateMachine.transition(
                current, WorkflowSignal.TOOL_EXECUTION_FAILED
        ).assignee();
        if (!expectedOwner.equals(checkedRole)) {
            throw new IllegalStateException(
                    "阶段 " + current + " 的工具故障 Owner 必须为 " + expectedOwner
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incident_id", incidentId);
        payload.put("execution_id", checkedExecutionId);
        payload.put("tool", checkedTool);
        payload.put("failure_type", checkedFailureType);
        payload.put("retryable", retryable);
        payload.put("attempt", attempt);
        payload.put("workflow_stage", current.name());
        payload.put("owner", checkedRole);
        payload.put("runtime_status", retryable ? "RECOVERING" : "BLOCKED");
        eventService.appendToolExecutionFailed(checkedProjectId, payload);
        if (retryable) {
            mailboxService.send(
                    checkedProjectId,
                    transition.assignee(),
                    "manager",
                    "task_assign",
                    transition.subject(),
                    Map.of(
                            "protocol", "tool_execution_failed",
                            "execution_id", checkedExecutionId,
                            "failure_type", checkedFailureType,
                            "retry_attempt", attempt,
                            "workflow_stage", current.name(),
                            "resume_current_stage", true
                    )
            );
            wakeScheduler.schedule(transition.assignee(), checkedProjectId);
        }
    }

    /**
     * 判断项目是否已因工具恢复耗尽进入运行时阻断状态。
     *
     * @param projectId 项目标识
     * @return 存在 BLOCKED 工具 incident 时为 true
     */
    public boolean isRuntimeBlocked(String projectId) {
        return eventService.readAll(projectId).stream()
                .filter(event -> "tool_execution_failed".equals(event.get("action")))
                .map(event -> event.get("payload"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(payload -> "BLOCKED".equals(String.valueOf(payload.get("runtime_status"))));
    }

    /**
     * 从最近一次 Java 迁移事件恢复当前阶段；新项目固定从需求 checkpoint 开始。
     *
     * @param projectId 项目标识
     * @return 当前唯一阶段
     */
    public WorkflowStage currentStage(String projectId) {
        List<Map<String, Object>> events = eventService.readAll(projectId);
        for (int index = events.size() - 1; index >= 0; index--) {
            Map<String, Object> event = events.get(index);
            if (!"workflow_transitioned".equals(event.get("action"))) {
                continue;
            }
            Object payload = event.get("payload");
            if (payload instanceof Map<?, ?> values) {
                Object toStage = values.get("to_stage");
                if (toStage != null) {
                    return WorkflowStage.valueOf(toStage.toString());
                }
            }
        }
        return WorkflowStage.REQUIREMENTS_CHECKPOINT;
    }

    /**
     * 拒绝已因工具重试耗尽而阻断的项目继续进入 QA 或交付。
     *
     * @param projectId 项目标识
     */
    private void assertNotBlocked(String projectId) {
        if (isRuntimeBlocked(projectId)) {
            throw new IllegalStateException("项目运行时状态为 BLOCKED，禁止推进 workflow");
        }
    }

    /**
     * 检查 incident 幂等键是否已经持久化。
     *
     * @param projectId 项目标识
     * @param incidentId execution ID 与 attempt 组成的幂等键
     * @return 已存在时为 true
     */
    private boolean hasIncident(String projectId, String incidentId) {
        return eventService.readAll(projectId).stream()
                .filter(event -> "tool_execution_failed".equals(event.get("action")))
                .map(event -> event.get("payload"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(payload -> incidentId.equals(String.valueOf(payload.get("incident_id"))));
    }

    /**
     * 在接受 RD/QA 执行阶段前重复执行证据门禁，避免伪造评审信号绕过 send_mail。
     *
     * @param projectId 项目标识
     * @param current 当前阶段
     * @param signal 评审信号
     * @param fromRole 完成角色
     * @param content 完成正文
     */
    private void validateStageEvidence(
            String projectId,
            WorkflowStage current,
            WorkflowSignal signal,
            String fromRole,
            Map<String, Object> content
    ) {
        if (signal == WorkflowSignal.STAGE_ACCEPTED || signal == WorkflowSignal.QA_DEFECT_FOUND) {
            completionEvidenceGate.validate(projectId, current, Objects.requireNonNullElse(fromRole, ""), content);
        }
    }

    /**
     * 校验评审结论确实来自当前阶段 Owner，防止模型冒用其他角色推进状态。
     *
     * @param current 当前阶段
     * @param signal 待提交信号
     * @param fromRole 声称完成任务的角色
     */
    private static void validateStageRole(WorkflowStage current, WorkflowSignal signal, String fromRole) {
        String expectedRole = switch (signal) {
            case CHECKPOINT_APPROVED, CHECKPOINT_REJECTED, DELIVERY_APPROVED, DELIVERY_REJECTED -> "";
            default -> switch (current) {
                case PRODUCT_DESIGN -> "pm";
                case TECH_DESIGN, CODE_IMPLEMENTATION, DEFECT_FIX -> "rd";
                case QA_TEST_DESIGN, QA_TEST_EXECUTION -> "qa";
                default -> "";
            };
        };
        String actualRole = Objects.requireNonNullElse(fromRole, "").trim().toLowerCase(Locale.ROOT);
        if (!expectedRole.equals(actualRole)) {
            throw new IllegalStateException(
                    "阶段 " + current + " 的信号 " + signal + " 必须来自角色 "
                            + (expectedRole.isBlank() ? "human" : expectedRole)
            );
        }
    }

    /**
     * 要求澄清恢复信号携带非空答复，避免无内容唤醒原 Owner 后再次阻塞。
     *
     * @param signal 待提交信号
     * @param feedback Manager 的澄清答复
     */
    private static void validateClarificationAnswer(WorkflowSignal signal, String feedback) {
        if (signal == WorkflowSignal.CLARIFICATION_ANSWERED
                && Objects.requireNonNullElse(feedback, "").isBlank()) {
            throw new IllegalStateException("clarification_answered 必须携带非空 feedback");
        }
    }

    /**
     * 将稳定的小写工具参数转换为领域信号。
     *
     * @param signal 工具参数
     * @return 领域信号
     */
    private static WorkflowSignal parseSignal(String signal) {
        String checked = AgentToolSupport.requireText(signal, "signal")
                .trim().toUpperCase(Locale.ROOT);
        return WorkflowSignal.valueOf(checked);
    }

    /**
     * 构造不可由模型覆盖的阶段迁移事件详情。
     *
     * @param transition 状态机结果
     * @param signal 触发信号
     * @param fromRole 完成角色
     * @param feedback 评审意见
     * @return 事件详情
     */
    private static Map<String, Object> transitionPayload(
            WorkflowTransition transition,
            WorkflowSignal signal,
            String fromRole,
            String feedback
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from_stage", transition.from().name());
        payload.put("to_stage", transition.to().name());
        payload.put("signal", signal.name());
        payload.put("from_role", Objects.requireNonNullElse(fromRole, ""));
        payload.put("assignee", transition.assignee());
        payload.put("subject", transition.subject());
        payload.put("feedback", Objects.requireNonNullElse(feedback, ""));
        return payload;
    }

    /**
     * 构造状态机任务正文，模型只能补充评审反馈，不能改变阶段与主题。
     *
     * @param transition 状态机结果
     * @param signal 触发信号
     * @param feedback 评审意见或澄清答复
     * @return 下一任务结构化正文
     */
    private static Map<String, Object> assignmentContent(
            WorkflowTransition transition,
            WorkflowSignal signal,
            String feedback
    ) {
        String checkedFeedback = Objects.requireNonNullElse(feedback, "");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("workflow_stage", transition.to().name());
        content.put("instruction", transition.subject());
        content.put("review_feedback", checkedFeedback);
        if (signal == WorkflowSignal.CLARIFICATION_ANSWERED) {
            content.put("protocol", "clarification_answer");
            content.put("resume_current_stage", true);
            content.put("clarification_answer", checkedFeedback);
        }
        return content;
    }
}
