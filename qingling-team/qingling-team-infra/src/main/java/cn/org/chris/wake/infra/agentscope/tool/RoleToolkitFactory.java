package cn.org.chris.wake.infra.agentscope.tool;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.gateway.ProjectTestEvidenceRepository;
import cn.org.chris.wake.domain.gateway.RuntimeIncidentGateway;
import cn.org.chris.wake.domain.gateway.SenderGateway;
import cn.org.chris.wake.domain.gateway.WorkspaceRepository;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.AgentExecutionException;
import cn.org.chris.wake.domain.workflow.WorkflowStage;
import cn.org.chris.wake.infra.agentscope.ProjectTestAgentTool;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecution;
import cn.org.chris.wake.infra.agentscope.ProjectTestExecutionRegistry;
import cn.org.chris.wake.infra.agentscope.RegistryProjectTestEvidenceRepository;
import cn.org.chris.wake.infra.agentscope.CompletionProtocolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 按团队角色和当前启用 Skill 构造最小权限 AgentScope Toolkit。
 */
public final class RoleToolkitFactory implements RuntimeIncidentGateway {

    /** 四角色共享的五个团队工具名。 */
    public static final Set<String> COMMON_TEAM_TOOL_NAMES = Set.of(
            "send_mail", "read_inbox", "mark_done", "read_shared", "write_shared"
    );

    /** Manager 独占的四个团队工具名。 */
    public static final Set<String> MANAGER_TEAM_TOOL_NAMES = Set.of(
            "create_project", "append_event", "send_to_human", "check_review_criteria", "advance_workflow"
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

    /** 四角色共享的 Java 确定性流程控制器。 */
    private final DeterministicWorkflowTools workflowTools;

    /** RD/QA 共享的真实项目测试执行证据账本。 */
    private final ProjectTestExecutionRegistry projectTestExecutionRegistry;

    /** 将 coordinated 工具调用桥接到 app 层原子协调器的窄端口。 */
    private final WorkflowToolCoordinator workflowToolCoordinator;

    /** 与 Agent Gateway 共享的未解决完成协议拒绝注册表。 */
    private final CompletionProtocolRegistry completionProtocolRegistry;

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
        this(
                mailboxService, workspaceRepository, wakeScheduler, eventService,
                senderGateway, imageAndSearchTools, objectMapper, WorkflowToolCoordinator.legacy()
        );
    }

    /**
     * 创建同时支持 legacy 与 runtime v2 工具协议的角色 Toolkit 工厂。
     *
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享工作区端口
     * @param wakeScheduler 邮件唤醒用例
     * @param eventService 事件领域服务
     * @param senderGateway 可为空的人类出站端口
     * @param imageAndSearchTools 图片和搜索工具实例
     * @param objectMapper JSON 编解码器
     * @param workflowToolCoordinator runtime v2 工具协调端口
     */
    public RoleToolkitFactory(
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            CommonTeamTools.MailWakeScheduler wakeScheduler,
            EventService eventService,
            SenderGateway senderGateway,
            ImageAndSearchTools imageAndSearchTools,
            ObjectMapper objectMapper,
            WorkflowToolCoordinator workflowToolCoordinator
    ) {
        this(
                mailboxService, workspaceRepository, wakeScheduler, eventService,
                senderGateway, imageAndSearchTools, objectMapper, workflowToolCoordinator,
                new ProjectTestExecutionRegistry(), new CompletionProtocolRegistry()
        );
    }

    /**
     * 创建共享外部 Test Runner 账本与 runtime v2 工具协调器的生产 Toolkit 工厂。
     *
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享工作区端口
     * @param wakeScheduler 邮件唤醒用例
     * @param eventService 事件领域服务
     * @param senderGateway 可为空的人类出站端口
     * @param imageAndSearchTools 图片和搜索工具实例
     * @param objectMapper JSON 编解码器
     * @param workflowToolCoordinator runtime v2 工具协调端口
     * @param projectTestExecutionRegistry 真实 Test Runner 共享执行账本
     */
    public RoleToolkitFactory(
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            CommonTeamTools.MailWakeScheduler wakeScheduler,
            EventService eventService,
            SenderGateway senderGateway,
            ImageAndSearchTools imageAndSearchTools,
            ObjectMapper objectMapper,
            WorkflowToolCoordinator workflowToolCoordinator,
            ProjectTestExecutionRegistry projectTestExecutionRegistry
    ) {
        this(
                mailboxService, workspaceRepository, wakeScheduler, eventService,
                senderGateway, imageAndSearchTools, objectMapper, workflowToolCoordinator,
                projectTestExecutionRegistry, new CompletionProtocolRegistry()
        );
    }

    /**
     * 创建同时共享 Test Runner 与完成协议状态的生产 Toolkit 工厂。
     *
     * @param mailboxService 邮箱领域服务
     * @param workspaceRepository 共享工作区端口
     * @param wakeScheduler 邮件唤醒用例
     * @param eventService 事件领域服务
     * @param senderGateway 可为空的人类出站端口
     * @param imageAndSearchTools 图片和搜索工具实例
     * @param objectMapper JSON 编解码器
     * @param workflowToolCoordinator runtime v2 工具协调端口
     * @param projectTestExecutionRegistry 真实 Test Runner 共享执行账本
     * @param completionProtocolRegistry 与 Gateway 共享的完成协议状态
     */
    public RoleToolkitFactory(
            MailboxService mailboxService,
            WorkspaceRepository workspaceRepository,
            CommonTeamTools.MailWakeScheduler wakeScheduler,
            EventService eventService,
            SenderGateway senderGateway,
            ImageAndSearchTools imageAndSearchTools,
            ObjectMapper objectMapper,
            WorkflowToolCoordinator workflowToolCoordinator,
            ProjectTestExecutionRegistry projectTestExecutionRegistry,
            CompletionProtocolRegistry completionProtocolRegistry
    ) {
        this.mailboxService = Objects.requireNonNull(mailboxService, "mailboxService 不能为空");
        this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository 不能为空");
        this.wakeScheduler = Objects.requireNonNull(wakeScheduler, "wakeScheduler 不能为空");
        this.eventService = Objects.requireNonNull(eventService, "eventService 不能为空");
        this.senderGateway = senderGateway;
        this.imageAndSearchTools = Objects.requireNonNull(imageAndSearchTools, "imageAndSearchTools 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.workflowToolCoordinator = Objects.requireNonNull(
                workflowToolCoordinator, "workflowToolCoordinator 不能为空"
        );
        this.projectTestExecutionRegistry = Objects.requireNonNull(
                projectTestExecutionRegistry, "projectTestExecutionRegistry 不能为空"
        );
        this.completionProtocolRegistry = Objects.requireNonNull(
                completionProtocolRegistry, "completionProtocolRegistry 不能为空"
        );
        this.workflowTools = new DeterministicWorkflowTools(
                eventService,
                mailboxService,
                wakeScheduler,
                new TaskCompletionEvidenceGate(workspaceRepository, projectTestExecutionRegistry),
                objectMapper,
                workflowToolCoordinator
        );
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
                role, mailboxService, workspaceRepository, wakeScheduler, objectMapper,
                workflowTools, workflowToolCoordinator, completionProtocolRegistry
        ));
        if ("manager".equals(role)) {
            toolkit.registerTool(new ManagerTeamTools(
                    workspaceRepository, eventService, senderGateway, objectMapper,
                    workflowToolCoordinator
            ));
            toolkit.registerTool(workflowTools);
        }
        toolkit.registerTool(new IntermediateArtifactTools(objectMapper));
        registerSkillTools(toolkit, skills);
        return toolkit;
    }

    /**
     * 将 AgentScope 工具边界的结构化基础设施故障交给确定性工作流控制器。
     *
     * @param projectId 项目标识
     * @param role 当前阶段 Owner
     * @param tool 工具名称
     * @param executionId 稳定执行标识
     * @param failureType 失败分类
     * @param retryable 是否仍可恢复
     * @param attempt 同一执行的尝试序号
     */
    public void reportToolExecutionFailure(
            String projectId,
            String role,
            String tool,
            String executionId,
            String failureType,
            boolean retryable,
            int attempt
    ) {
        workflowTools.reportToolExecutionFailure(
                projectId, role, tool, executionId, failureType, retryable, attempt
        );
    }

    /**
     * 将 Runner 捕获的类型化 Agent 执行故障交给共享确定性状态机处理。
     *
     * @param projectId 当前团队项目标识
     * @param role 当前 workflow Owner
     * @param occurrenceId 当前入站 dispatch 的稳定标识
     * @param failureType Agent 执行失败分类
     */
    @Override
    public void reportAgentExecutionFailure(
            String projectId,
            String role,
            String occurrenceId,
            AgentExecutionException.FailureType failureType
    ) {
        workflowTools.reportAgentExecutionFailure(projectId, role, occurrenceId, failureType);
    }

    /**
     * 将无项目 heartbeat 故障绑定到该角色唯一的处理中任务；候选不唯一时拒绝猜测。
     *
     * @param role 当前团队角色
     * @param occurrenceId 当前入站 dispatch 的稳定标识
     * @param failureType Agent 执行失败分类
     */
    @Override
    public void reportUnscopedAgentExecutionFailure(
            String role,
            String occurrenceId,
            AgentExecutionException.FailureType failureType
    ) {
        List<String> candidates = workspaceRepository.listProjectIds().stream()
                .filter(projectId -> !mailboxService.findInProgressTaskAssignments(projectId, role).isEmpty())
                .toList();
        if (candidates.size() == 1) {
            workflowTools.reportAgentExecutionFailure(
                    candidates.get(0), role, occurrenceId, failureType
            );
        }
    }

    /**
     * 按 Java 工作流当前阶段解析角色唯一负责的项目，使 heartbeat 不会丢失项目上下文。
     *
     * @param role 当前团队角色
     * @return 唯一项目；无候选或多项目并发时为空
     */
    @Override
    public Optional<String> resolveProjectId(String role) {
        if (!MailboxService.TEAM_ROLES.contains(role)) {
            return Optional.empty();
        }
        List<String> candidates = workspaceRepository.listProjectIds().stream()
                .filter(projectId -> !workflowTools.isRuntimeBlocked(projectId))
                .filter(projectId -> role.equals(currentOwner(projectId)))
                .toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    /**
     * 安全解析项目当前 Owner；未形成合法工作流事件的目录不参与候选。
     *
     * @param projectId 候选项目标识
     * @return 当前 Owner 或空字符串
     */
    private String currentOwner(String projectId) {
        try {
            return stageOwner(workflowTools.currentStage(projectId));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * 返回当前工作流阶段的固定 Owner；等待人类的阶段不归属后台角色。
     *
     * @param stage 当前工作流阶段
     * @return manager、pm、rd、qa 或空字符串
     */
    private static String stageOwner(cn.org.chris.wake.domain.workflow.WorkflowStage stage) {
        return switch (stage) {
            case PRODUCT_DESIGN -> "pm";
            case TECH_DESIGN, CODE_IMPLEMENTATION, DEFECT_FIX -> "rd";
            case QA_TEST_DESIGN, QA_TEST_EXECUTION -> "qa";
            case REQUIREMENTS_CHECKPOINT, DELIVERY_CHECKPOINT, COMPLETED, CANCELLED -> "";
        };
    }

    /**
     * 查询项目是否已经耗尽运行时恢复预算。
     *
     * @param projectId 项目标识
     * @return 已存在 BLOCKED incident 时为 true
     */
    @Override
    public boolean isRuntimeBlocked(String projectId) {
        return workflowTools.isRuntimeBlocked(projectId);
    }

    /**
     * 创建与当前工作流状态和证据门禁共享执行账本的受控项目测试工具。
     *
     * @param toolkit 已注册原始 AIO-Sandbox 工具的角色 Toolkit
     * @param role 当前角色，只允许 rd 或 qa
     * @return 已接入 incident 与真实证据登记的项目测试工具
     */
    public ProjectTestAgentTool createProjectTestTool(Toolkit toolkit, String role) {
        if (!Set.of("rd", "qa").contains(role)) {
            throw new IllegalArgumentException("run_project_tests 只允许 rd 或 qa 使用");
        }
        return new ProjectTestAgentTool(
                toolkit,
                objectMapper,
                role,
                this::reportToolExecutionFailure,
                (projectId, owner) -> workflowTools.currentStage(projectId),
                new ProjectTestAgentTool.ProjectTestExecutionObserver() {
                    /** 在 Docker 启动前把最新 execution 与当前 WorkExecution 原子绑定。 */
                    @Override
                    public void begin(
                            String projectId,
                            String owner,
                            WorkflowStage stage,
                            String executionId
                    ) {
                        if (workflowToolCoordinator.coordinated(projectId)) {
                            workflowToolCoordinator.beginTestExecution(
                                    projectId, owner, stage.name(), executionId
                            );
                        }
                    }

                    /** 把真实 Test Runner 终态登记到共享证据账本。 */
                    @Override
                    public void record(
                            String projectId,
                            String owner,
                            WorkflowStage stage,
                            ProjectTestExecution execution
                    ) {
                        projectTestExecutionRegistry.record(projectId, owner, stage, execution);
                    }
                },
                completionProtocolRegistry
        );
    }

    /**
     * 暴露与 RD/QA 真实测试工具共享同一执行账本的只读领域证据端口。
     *
     * @return 不包含 stdout/stderr 的真实 Test Runner 证据仓储
     */
    public ProjectTestEvidenceRepository projectTestEvidenceRepository() {
        return new RegistryProjectTestEvidenceRepository(projectTestExecutionRegistry);
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
