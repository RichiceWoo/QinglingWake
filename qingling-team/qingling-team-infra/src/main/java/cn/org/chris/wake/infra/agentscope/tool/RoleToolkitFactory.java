package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.gateway.SenderGateway;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 按团队角色和当前启用 Skill 构造最小权限 AgentScope Toolkit。
 */
public final class RoleToolkitFactory {

    /** 四角色共享的五个团队工具名。 */
    public static final Set<String> COMMON_TEAM_TOOL_NAMES = Set.of(
            "send_mail", "read_inbox", "mark_done", "read_shared", "write_shared"
    );

    /** Manager 独占的三个团队工具名。 */
    public static final Set<String> MANAGER_TEAM_TOOL_NAMES = Set.of(
            "create_project", "append_event", "send_to_human"
    );

    /** 总是提供给角色的中间产物工具名。 */
    public static final String INTERMEDIATE_TOOL_NAME = "Save_Intermediate_Product_Tool";

    /** 需要图片工具的显式 Skill 能力名称。 */
    public static final String IMAGE_SKILL = "image_analysis";

    /** 需要百度搜索工具的显式 Skill 能力名称。 */
    public static final String SEARCH_SKILL = "baidu_search";

    /** AgentScope 安全格式的本地图片工具名。 */
    public static final String IMAGE_TOOL_NAME = "add_image_to_content_local";

    /** 与 Python 保持一致的搜索工具名。 */
    public static final String SEARCH_TOOL_NAME = "search_web";

    /** 团队邮箱领域服务。 */
    private final MailboxService mailboxService;

    /** 共享工作区端口。 */
    private final WorkspaceRepository workspaceRepository;

    /** app 层邮件唤醒用例的窄接口。 */
    private final CommonTeamTools.MailWakeScheduler wakeScheduler;

    /** Manager 单写者事件服务。 */
    private final EventService eventService;

    /** 可为空的人类出站端口。 */
    private final SenderGateway senderGateway;

    /** 可按 Skill 选择注册的图片和搜索工具。 */
    private final ImageAndSearchTools imageAndSearchTools;

    /** 创建角色绑定工具及中间产物工具使用的 JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建角色 Toolkit 工厂。
     *
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享工作区端口
     * @param wakeScheduler 邮件唤醒用例
     * @param eventService 事件领域服务
     * @param senderGateway 可为空的人类出站端口
     * @param imageAndSearchTools 图片和搜索工具实例
     * @param objectMapper JSON 编解码器
     */
    public RoleToolkitFactory(
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            CommonTeamTools.MailWakeScheduler wakeScheduler,
            EventService eventService,
            SenderGateway senderGateway,
            ImageAndSearchTools imageAndSearchTools,
            ObjectMapper objectMapper
    ) {
        this.mailboxService = Objects.requireNonNull(mailboxService, "mailboxService 不能为空");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.wakeScheduler = Objects.requireNonNull(wakeScheduler, "wakeScheduler 不能为空");
        this.eventService = Objects.requireNonNull(eventService, "eventService 不能为空");
        this.senderGateway = senderGateway;
        this.imageAndSearchTools = Objects.requireNonNull(imageAndSearchTools, "imageAndSearchTools 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 构造只包含角色团队工具和中间产物工具的默认 Toolkit。
     *
     * @param role manager、pm、rd 或 qa
     * @return 已注册最小工具集的 Toolkit
     */
    public Toolkit create(String role) {
        return create(role, Set.of());
    }

    /**
     * 根据角色和当前启用 Skill 构造 Toolkit；图片、搜索能力只有对应 Skill 激活时才暴露。
     *
     * @param role manager、pm、rd 或 qa
     * @param activeSkills 当前启用的 Skill 名称
     * @return 已注册角色和 Skill 最小工具集的 Toolkit
     */
    public Toolkit create(String role, Set<String> activeSkills) {
        if (!MailboxService.TEAM_ROLES.contains(role)) {
            throw new IllegalArgumentException("未知团队角色: " + role);
        }
        Set<String> skills = activeSkills == null ? Set.of() : Set.copyOf(activeSkills);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new CommonTeamTools(
                role, mailboxService, workspaceRepository, wakeScheduler, objectMapper
        ));
        if ("manager".equals(role)) {
            toolkit.registerTool(new ManagerTeamTools(
                    workspaceRepository, eventService, senderGateway, objectMapper
            ));
        }
        toolkit.registerTool(new IntermediateArtifactTools(objectMapper));
        registerSkillTools(toolkit, skills);
        return toolkit;
    }

    /**
     * 通过 AgentScope 选择性注册 API 只暴露当前 Skill 明确需要的辅助工具。
     *
     * @param toolkit 待扩展 Toolkit
     * @param activeSkills 当前启用 Skill
     */
    private void registerSkillTools(Toolkit toolkit, Set<String> activeSkills) {
        List<String> enabledTools = new ArrayList<>();
        if (activeSkills.contains(IMAGE_SKILL)) {
            enabledTools.add(IMAGE_TOOL_NAME);
        }
        if (activeSkills.contains(SEARCH_SKILL)) {
            enabledTools.add(SEARCH_TOOL_NAME);
        }
        if (!enabledTools.isEmpty()) {
            toolkit.registration()
                    .tool(imageAndSearchTools)
                    .enableTools(enabledTools)
                    .apply();
            if (!enabledTools.contains(IMAGE_TOOL_NAME)) {
                toolkit.removeTool(IMAGE_TOOL_NAME);
            }
            if (!enabledTools.contains(SEARCH_TOOL_NAME)) {
                toolkit.removeTool(SEARCH_TOOL_NAME);
            }
        }
    }
}
