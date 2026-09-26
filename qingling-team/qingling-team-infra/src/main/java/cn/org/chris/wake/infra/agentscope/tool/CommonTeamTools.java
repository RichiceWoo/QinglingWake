package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.MailMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 向四个角色提供邮箱三态流转和受权限约束的共享工作区 AgentScope 工具。
 */
public final class CommonTeamTools {

    /** 调用这些工具时固定使用的当前角色，防止模型伪造发送者或文件 Owner。 */
    private final String role;

    /** 团队邮箱领域服务。 */
    private final MailboxService mailboxService;

    /** 共享工作区持久化端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** 发送邮件后注册一秒唤醒的调度器。 */
    private final MailWakeScheduler wakeScheduler;

    /** 工具 JSON 响应编解码器。 */
    private final ObjectMapper objectMapper;

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
        if (!MailboxService.TEAM_ROLES.contains(role)) {
            throw new IllegalArgumentException("未知团队角色: " + role);
        }
        this.role = role;
        this.mailboxService = Objects.requireNonNull(mailboxService, "mailboxService 不能为空");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.wakeScheduler = Objects.requireNonNull(wakeScheduler, "wakeScheduler 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 发送团队邮件并为收件角色注册一次性 new_mail 唤醒。
     *
     * @param to 收件角色
     * @param type 邮件业务类型
     * @param subject 邮件主题
     * @param content 字符串或结构化正文
     * @param projectId 项目标识
     * @return 包含 msg_id 和 scheduled_wake 的 JSON
     */
    @Tool(
            name = "send_mail",
            description = "向 manager/pm/rd/qa 发送团队邮件并自动唤醒收件角色。",
            concurrencySafe = true
    )
    public String sendMail(
            @ToolParam(name = "to", description = "收件角色：manager/pm/rd/qa") String to,
            @ToolParam(name = "type", description = "邮件类型，如 task_assign/task_done/review_request") String type,
            @ToolParam(name = "subject", description = "一行邮件标题") String subject,
            @ToolParam(name = "content", description = "字符串或结构化 JSON 正文") Object content,
            @ToolParam(name = "project_id", description = "小写项目 ID") String projectId
    ) {
        try {
            String checkedProjectId = AgentToolSupport.requireProjectId(projectId);
            String messageId = mailboxService.send(
                    checkedProjectId, to, role, type, subject, content
            );
            String wakeId = wakeScheduler.schedule(to, checkedProjectId);
            return AgentToolSupport.success(objectMapper, Map.of(
                    "msg_id", messageId,
                    "scheduled_wake", wakeId,
                    "to", to,
                    "type", type
            ));
        } catch (RuntimeException exception) {
            return AgentToolSupport.error(objectMapper, exception);
        }
    }

    /**
     * 原子领取当前角色的 unread 邮件并转为 in_progress。
     *
     * @param projectId 项目标识
     * @return 邮件数量与消息快照 JSON
     */
    @Tool(
            name = "read_inbox",
            description = "领取当前角色全部未读邮件；返回后邮件状态已原子切换为 in_progress。",
            concurrencySafe = true
    )
    public String readInbox(
            @ToolParam(name = "project_id", description = "项目 ID") String projectId
    ) {
        try {
            List<MailMessage> messages = mailboxService.readInbox(
                    AgentToolSupport.requireProjectId(projectId), role
            );
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
     * 将当前角色已领取的一封邮件标记为 done。
     *
     * @param projectId 项目标识
     * @param messageId msg-xxxxxxxx 邮件标识
     * @return 完成状态 JSON
     */
    @Tool(
            name = "mark_done",
            description = "把当前角色收件箱中的一封 in_progress 邮件标记为 done。",
            concurrencySafe = true
    )
    public String markDone(
            @ToolParam(name = "project_id", description = "项目 ID") String projectId,
            @ToolParam(name = "msg_id", description = "待完成的 msg-xxxxxxxx 标识") String messageId
    ) {
        try {
            mailboxService.markDone(
                    AgentToolSupport.requireProjectId(projectId), role,
                    AgentToolSupport.requireText(messageId, "msg_id")
            );
            return AgentToolSupport.success(objectMapper, Map.of(
                    "msg_id", messageId,
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
