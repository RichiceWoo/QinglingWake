package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.MailMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 向四个角色提供邮箱三态流转和受权限约束的共享工作区 AgentScope 工具。
 */
public final class CommonTeamTools {

    /** RD 可交付的唯一 Python 应用入口相对路径。 */
    private static final String CANONICAL_PYTHON_ENTRYPOINT = "code/main.py";

    /** 调用这些工具时固定使用的当前角色，防止模型伪造发送者或文件 Owner。 */
    private final String role;

    /** 团队邮箱领域服务。 */
    private final MailboxService mailboxService;

    /** 共享工作区持久化端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** 发送邮件后注册一秒唤醒的调度器。 */
    private final MailWakeScheduler wakeScheduler;

    /** 可为空的 Java 确定性状态机；为空仅用于独立领域工具测试。 */
    private final DeterministicWorkflowTools workflowTools;

    /** 工具 JSON 响应编解码器。 */
    private final ObjectMapper objectMapper;

    /** coordinated 项目完成、评审和运行时关联的窄协调端口。 */
    private final WorkflowToolCoordinator workflowToolCoordinator;

    /**
     * 创建绑定单个团队角色的五个公共工具。
     *
     * @param role 当前角色，只允许 manager、pm、rd、qa
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享工作区端口
     * @param wakeScheduler 邮件唤醒调度端口
     * @param objectMapper JSON 编解码器
     */
    public CommonTeamTools(
            String role,
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            MailWakeScheduler wakeScheduler,
            ObjectMapper objectMapper
    ) {
        this(
                role, mailboxService, workspaceRepository, wakeScheduler, objectMapper,
                null, WorkflowToolCoordinator.legacy()
        );
    }

    /**
     * 创建绑定 Java 工作流门禁的公共团队工具。
     *
     * @param role 当前角色
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享工作区端口
     * @param wakeScheduler 邮件唤醒调度端口
     * @param objectMapper JSON 编解码器
     * @param workflowTools Java 状态机与完成证据门禁
     */
    public CommonTeamTools(
            String role,
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            MailWakeScheduler wakeScheduler,
            ObjectMapper objectMapper,
            DeterministicWorkflowTools workflowTools
    ) {
        this(
                role, mailboxService, workspaceRepository, wakeScheduler, objectMapper,
                workflowTools, WorkflowToolCoordinator.legacy()
        );
    }

    /**
     * 创建同时支持 legacy 邮箱协议与 runtime v2 原子工具协议的公共工具。
     *
     * @param role 当前角色
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享工作区端口
     * @param wakeScheduler 邮件唤醒调度端口
     * @param objectMapper JSON 编解码器
     * @param workflowTools legacy Java 状态机与完成证据门禁
     * @param workflowToolCoordinator runtime v2 工具协调端口
     */
    public CommonTeamTools(
            String role,
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            MailWakeScheduler wakeScheduler,
            ObjectMapper objectMapper,
            DeterministicWorkflowTools workflowTools,
            WorkflowToolCoordinator workflowToolCoordinator
    ) {
        if (!MailboxService.TEAM_ROLES.contains(role)) {
            throw new IllegalArgumentException("未知团队角色: " + role);
        }
        this.role = role;
        this.mailboxService = Objects.requireNonNull(mailboxService, "mailboxService 不能为空");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.wakeScheduler = Objects.requireNonNull(wakeScheduler, "wakeScheduler 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.workflowTools = workflowTools;
        this.workflowToolCoordinator = Objects.requireNonNull(
                workflowToolCoordinator, "workflowToolCoordinator 不能为空"
        );
    }

    /**
     * 为 Java 直接调用保留不传 AgentScope 上下文的兼容入口。
     *
     * @param to 收件角色
     * @param type 邮件业务类型
     * @param subject 邮件主题
     * @param content 字符串或结构化正文
     * @param projectId 项目标识
     * @return 工具 JSON 结果
     */
    public String sendMail(
            String to,
            String type,
            String subject,
            Object content,
            String projectId
    ) {
        return sendMail(to, type, subject, content, projectId, null);
    }

    /**
     * 发送团队邮件并为收件角色注册一次性 new_mail 唤醒。
     *
     * @param to 收件角色
     * @param type 邮件业务类型
     * @param subject 邮件主题
     * @param content 字符串或结构化正文
     * @param projectId 项目标识
     * @param runtimeContext AgentScope 注入的当前执行上下文，不进入工具 schema
     * @return 包含 msg_id 和 scheduled_wake 的 JSON
     */
    @Tool(
            name = "send_mail",
            description = "向 manager/pm/rd/qa 发送团队邮件并自动唤醒收件角色。",
            concurrencySafe = true
    )
    public String sendMail(
            @ToolParam(name = "to", description = "收件角色：manager/pm/rd/qa") String to,
            @ToolParam(name = "type", description = "邮件类型，如 task_done/review_request/clarification_request；流水线分派和澄清答复必须走 advance_workflow") String type,
            @ToolParam(name = "subject", description = "一行邮件标题") String subject,
            @ToolParam(name = "content", description = "字符串或结构化 JSON 正文") Object content,
            @ToolParam(name = "project_id", description = "小写项目 ID") String projectId,
            RuntimeContext runtimeContext
    ) {
        try {
            String checkedProjectId = AgentToolSupport.requireProjectId(projectId);
            Object normalizedContent = normalizeStructuredContent(type, content);
            if (workflowToolCoordinator.coordinated(checkedProjectId)
                    && ("task_done".equals(type) || "review_done".equals(type))) {
                return completeCoordinatedMail(
                        checkedProjectId, to, type, subject, normalizedContent, runtimeContext
                );
            }
            if (workflowTools != null) {
                workflowTools.validateOutgoingMail(checkedProjectId, role, type, normalizedContent);
            }
            List<MailMessage> completedAssignments = List.of();
            if ("task_done".equals(type)) {
                if (!"manager".equals(to)) {
                    throw new IllegalStateException("task_done 被拒绝：流水线完成回报必须发送给 manager");
                }
                completedAssignments = mailboxService.findInProgressTaskAssignments(checkedProjectId, role);
                if (completedAssignments.isEmpty()) {
                    throw new IllegalStateException("task_done 被拒绝：当前角色没有 in_progress task_assign");
                }
            }
            String messageId = mailboxService.send(
                    checkedProjectId, to, role, type, subject, normalizedContent
            );
            for (MailMessage completedAssignment : completedAssignments) {
                mailboxService.markDone(checkedProjectId, role, completedAssignment.id());
            }
            String wakeId = wakeScheduler.schedule(to, checkedProjectId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("msg_id", messageId);
            response.put("scheduled_wake", wakeId);
            response.put("to", to);
            response.put("type", type);
            if (!completedAssignments.isEmpty()) {
                response.put("completed_assignment_id", completedAssignments.get(completedAssignments.size() - 1).id());
                response.put(
                        "completed_assignment_ids",
                        completedAssignments.stream().map(MailMessage::id).toList()
                );
            }
            return AgentToolSupport.success(objectMapper, response);
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 在 runtime v2 项目中把完成或评审邮件直接转换为原子命令，并收敛旧邮箱投影。
     *
     * @param projectId 项目标识
     * @param to 兼容入口收件角色
     * @param type task_done 或 review_done
     * @param subject 有界邮件主题
     * @param content 已归一化正文
     * @param runtimeContext 当前 AgentScope 上下文
     * @return 不产生 Manager 行政邮件的权威协调结果
     */
    private String completeCoordinatedMail(
            String projectId,
            String to,
            String type,
            String subject,
            Object content,
            RuntimeContext runtimeContext
    ) {
        if (!"manager".equals(to)) {
            throw new IllegalStateException(type + " 被拒绝：兼容入口必须发送给 manager");
        }
        Map<String, Object> coordinated = "task_done".equals(type)
                ? workflowToolCoordinator.complete(projectId, role, subject, content)
                : workflowToolCoordinator.submitReview(projectId, role, content);
        String expectedMessageType = "task_done".equals(type) ? "task_assign" : "review_request";
        List<MailMessage> completed = mailboxService.findInProgressMessages(projectId, role).stream()
                .filter(message -> expectedMessageType.equals(message.type()))
                .toList();
        for (MailMessage message : completed) {
            mailboxService.markDone(projectId, role, message.id());
        }
        Map<String, Object> response = new LinkedHashMap<>(coordinated);
        response.put("coordinated", true);
        response.put("legacy_mail_sent", false);
        response.put("completed_assignment_ids", completed.stream().map(MailMessage::id).toList());
        if (runtimeContext != null) {
            response.put("agent_session_id", Objects.toString(runtimeContext.getSessionId(), ""));
        }
        return AgentToolSupport.success(objectMapper, response);
    }

    /**
     * 将 AgentScope 实际传入的 task_done/review_done JSON 字符串恢复为对象，其他正文保持原类型。
     *
     * @param type 邮件业务类型
     * @param content 原始工具入参
     * @return 可供证据门禁和邮箱共同使用的正文
     */
    private Object normalizeStructuredContent(String type, Object content) {
        if (!("task_done".equals(type) || "review_done".equals(type))
                || !(content instanceof String text)) {
            return content;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return content;
        }
        try {
            Object parsed = objectMapper.readValue(trimmed, Object.class);
            return parsed instanceof Map<?, ?> ? parsed : content;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return content;
        }
    }

    /**
     * 为 Java 直接调用保留不传 AgentScope 上下文的兼容入口。
     *
     * @param projectId 项目标识
     * @return 邮件数量与消息快照 JSON
     */
    public String readInbox(String projectId) {
        return readInbox(projectId, null);
    }

    /**
     * 原子领取当前角色的 unread 邮件并转为 in_progress。
     *
     * @param projectId 项目标识
     * @param runtimeContext AgentScope 注入的当前执行上下文，不进入工具 schema
     * @return 邮件数量与消息快照 JSON
     */
    @Tool(
            name = "read_inbox",
            description = "领取当前角色全部未读邮件；返回后邮件状态已原子切换为 in_progress。",
            concurrencySafe = true
    )
    public String readInbox(
            @ToolParam(name = "project_id", description = "项目 ID") String projectId,
            RuntimeContext runtimeContext
    ) {
        try {
            String checkedProjectId = AgentToolSupport.requireProjectId(projectId);
            List<MailMessage> messages = mailboxService.readInbox(
                    checkedProjectId, role
            );
            if (workflowToolCoordinator.coordinated(checkedProjectId)) {
                bindCoordinatedAssignments(checkedProjectId, messages, runtimeContext);
            }
            return AgentToolSupport.success(objectMapper, Map.of(
                    "role", role,
                    "count", messages.size(),
                    "messages", messages.stream().map(CommonTeamTools::mailToMap).toList()
            ));
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 把本轮领取到的 v2 task_assign 绑定到 WorkExecution 和当前 AgentScope 回合。
     *
     * @param projectId 项目标识
     * @param messages 本轮领取邮件
     * @param runtimeContext 当前 AgentScope 上下文
     */
    private void bindCoordinatedAssignments(
            String projectId,
            List<MailMessage> messages,
            RuntimeContext runtimeContext
    ) {
        if (runtimeContext == null) {
            throw new IllegalStateException("coordinated read_inbox 缺少 AgentScope RuntimeContext");
        }
        String dispatchId = Objects.toString(runtimeContext.get("dispatchId", String.class), "").trim();
        String sessionId = Objects.toString(runtimeContext.getSessionId(), "").trim();
        if (dispatchId.isEmpty() || sessionId.isEmpty()) {
            throw new IllegalStateException("coordinated read_inbox 缺少 dispatch/session 上下文");
        }
        for (MailMessage message : messages) {
            if ("task_assign".equals(message.type())) {
                workflowToolCoordinator.claimAndStart(
                        projectId, role, message.id(), message.content(), dispatchId, sessionId
                );
            }
        }
    }

    /**
     * 将当前角色已领取的一封邮件标记为 done。
     *
     * @param projectId 项目标识
     * @param messageId msg-xxxxxxxx 邮件标识
     * @return 完成状态 JSON
     */
    @Tool(
            name = "mark_done",
            description = "把当前角色收件箱中的普通 in_progress 邮件标记为 done；task_assign 仅由成功 task_done 自动完成。",
            concurrencySafe = true
    )
    public String markDone(
            @ToolParam(name = "project_id", description = "项目 ID") String projectId,
            @ToolParam(name = "msg_id", description = "待完成的 msg-xxxxxxxx 标识") String messageId
    ) {
        try {
            String checkedProjectId = AgentToolSupport.requireProjectId(projectId);
            String checkedMessageId = AgentToolSupport.requireText(messageId, "msg_id");
            MailMessage message = mailboxService.findMessage(checkedProjectId, role, checkedMessageId)
                    .orElseThrow(() -> new IllegalStateException("msg not found: " + checkedMessageId));
            if ("task_assign".equals(message.type())) {
                if (message.status().name().equals("DONE")) {
                    return AgentToolSupport.success(objectMapper, Map.of(
                            "msg_id", checkedMessageId,
                            "status", "done",
                            "already_done", true,
                            "completion_managed_by", "task_done"
                    ));
                }
                throw new IllegalStateException(
                        "task_assign 不能单独 mark_done；请先发送通过门禁的 task_done，系统将自动完成原任务"
                );
            }
            mailboxService.markDone(checkedProjectId, role, checkedMessageId);
            return AgentToolSupport.success(objectMapper, Map.of(
                    "msg_id", checkedMessageId,
                    "status", "done"
            ));
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 读取当前项目中的共享文本文件。
     *
     * @param projectId 项目标识
     * @param relativePath 项目根目录内相对路径
     * @return 文件正文 JSON
     */
    @Tool(
            name = "read_shared",
            description = "读取 shared/projects/{project_id} 下的安全相对路径文本文件。",
            readOnly = true,
            concurrencySafe = true
    )
    public String readShared(
            @ToolParam(name = "project_id", description = "项目 ID") String projectId,
            @ToolParam(name = "rel_path", description = "项目内相对路径") String relativePath
    ) {
        try {
            String content = workspaceRepository.read(
                    AgentToolSupport.requireProjectId(projectId), role, relativePath
            );
            return AgentToolSupport.success(objectMapper, Map.of(
                    "rel_path", relativePath,
                    "content", content
            ));
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 按当前角色 Owner 权限写入项目共享文本文件。
     *
     * @param projectId 项目标识
     * @param relativePath 项目内相对路径
     * @param content UTF-8 文件正文
     * @return 写入路径 JSON
     */
    @Tool(
            name = "write_shared",
            description = "按角色 Owner 权限写入共享项目文件；邮箱与事件必须使用专用工具。",
            concurrencySafe = true
    )
    public String writeShared(
            @ToolParam(name = "project_id", description = "项目 ID") String projectId,
            @ToolParam(name = "rel_path", description = "受 Owner 权限约束的相对路径") String relativePath,
            @ToolParam(name = "content", description = "文件正文") String content
    ) {
        try {
            validateCanonicalEntrypoint(relativePath);
            workspaceRepository.write(
                    AgentToolSupport.requireProjectId(projectId), role, relativePath,
                    Objects.requireNonNullElse(content, "")
            );
            return AgentToolSupport.success(objectMapper, Map.of("rel_path", relativePath));
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 拒绝 RD 在 code 子目录创建第二个 main.py，保证 Driver、QA 与测试入口使用同一产物。
     *
     * @param relativePath 模型请求写入的项目相对路径
     */
    private void validateCanonicalEntrypoint(String relativePath) {
        if (!"rd".equals(role) || relativePath == null || relativePath.isBlank()) {
            return;
        }
        String normalized = Path.of(relativePath).normalize().toString().replace('\\', '/');
        if (normalized.startsWith("code/")
                && normalized.endsWith("/main.py")
                && !CANONICAL_PYTHON_ENTRYPOINT.equals(normalized)) {
            throw new IllegalArgumentException(
                    "Python 应用唯一入口必须是 " + CANONICAL_PYTHON_ENTRYPOINT
                            + "，禁止创建替代入口: " + normalized
            );
        }
    }

    /**
     * 将领域邮件转换为无需 JavaTime 模块即可稳定序列化的 Python 字段 Map。
     *
     * @param message 领域邮件
     * @return 保持 Python 字段名的有序 Map
     */
    private static Map<String, Object> mailToMap(MailMessage message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", message.id());
        value.put("project_id", message.projectId());
        value.put("from", message.from());
        value.put("to", message.to());
        value.put("type", message.type());
        value.put("subject", message.subject());
        value.put("content", message.content());
        value.put("timestamp", message.timestamp().toString());
        value.put("status", message.status().name().toLowerCase());
        value.put("processing_since", message.processingSince() == null
                ? null : message.processingSince().toString());
        return value;
    }

    /**
     * 将 app 层邮件唤醒用例以窄接口注入 infra Tool，避免反转 COLA 模块依赖方向。
     */
    @FunctionalInterface
    public interface MailWakeScheduler {

        /**
         * 为目标角色注册或复用一次性 new_mail 唤醒。
         *
         * @param role 目标角色
         * @param projectId 项目标识
         * @return 新建或复用的任务标识
         */
        String schedule(String role, String projectId);
    }
}
