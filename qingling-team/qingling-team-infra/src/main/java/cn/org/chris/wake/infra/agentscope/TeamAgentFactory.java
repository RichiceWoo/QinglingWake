package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.infra.agentscope.tool.RoleToolkitFactory;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 以相同安全基线构造 Manager、PM、RD、QA 四个 AgentScope HarnessAgent。
 */
public final class TeamAgentFactory {

    /** 保持业务编排顺序稳定的四角色列表。 */
    public static final List<String> TEAM_ROLES = List.of("manager", "pm", "rd", "qa");

    /** AgentScope 核心工具执行器固定五分钟上限前必须完成异步卸载。 */
    private static final Duration MAX_ASYNC_TOOL_OFFLOAD_TIMEOUT = Duration.ofMinutes(4);

    /** 四角色共享的模型实例。 */
    private final Model model;

    /** 与 Gateway 共享且仅保存角色时间戳的模型活动注册表。 */
    private final ModelActivityRegistry modelActivityRegistry;

    /** 按角色提供最小权限团队工具的工厂。 */
    private final RoleToolkitFactory toolkitFactory;

    /** 从外部 workspace 读取声明式 Sub-Agent 的工厂。 */
    private final RoleSubagentFactory subagentFactory;

    /** AIO-Sandbox MCP 配置和注册状态采集器。 */
    private final McpSandboxConfiguration sandboxConfiguration;

    /** 按角色显式启用的辅助 Skill 能力。 */
    private final Map<String, Set<String>> activeSkillsByRole;

    /** 单个 HarnessAgent 允许的最大 ReAct 迭代次数。 */
    private final int maxIterations;

    /** Harness workspace 上下文允许的最大 token 数。 */
    private final int maxContextTokens;

    /** 长工具转入 AgentScope 后台任务前的等待时间，必须短于核心工具执行器上限。 */
    private final Duration asyncToolOffloadTimeout;

    /**
     * 创建只启用团队工具、workspace Skill 和声明式 Sub-Agent 的默认工厂。
     *
     * @param model AgentScope 模型
     * @param toolkitFactory 角色 Toolkit 工厂
     * @param subagentFactory 角色 Sub-Agent 工厂
     * @param sandboxConfiguration AIO-Sandbox MCP 配置
     */
    public TeamAgentFactory(
            Model model,
            RoleToolkitFactory toolkitFactory,
            RoleSubagentFactory subagentFactory,
            McpSandboxConfiguration sandboxConfiguration
    ) {
        this(model, toolkitFactory, subagentFactory, sandboxConfiguration, Map.of(), 30, 30_000,
                Duration.ofSeconds(30), new ModelActivityRegistry());
    }

    /**
     * 创建可按角色额外开放图片或搜索工具的团队 Agent 工厂。
     *
     * @param model AgentScope 模型
     * @param toolkitFactory 角色 Toolkit 工厂
     * @param subagentFactory 角色 Sub-Agent 工厂
     * @param sandboxConfiguration AIO-Sandbox MCP 配置
     * @param activeSkillsByRole 按角色显式启用的辅助 Skill 名称
     */
    public TeamAgentFactory(
            Model model,
            RoleToolkitFactory toolkitFactory,
            RoleSubagentFactory subagentFactory,
            McpSandboxConfiguration sandboxConfiguration,
            Map<String, Set<String>> activeSkillsByRole
    ) {
        this(model, toolkitFactory, subagentFactory, sandboxConfiguration, activeSkillsByRole,
                30, 30_000, Duration.ofSeconds(30), new ModelActivityRegistry());
    }

    /**
     * 创建由配置控制迭代、上下文和异步工具超时的团队 Agent 工厂。
     *
     * @param model AgentScope 原始模型
     * @param toolkitFactory 角色 Toolkit 工厂
     * @param subagentFactory 角色 Sub-Agent 工厂
     * @param sandboxConfiguration AIO-Sandbox MCP 配置
     * @param activeSkillsByRole 按角色启用的辅助 Skill
     * @param maxIterations 单回合最大 ReAct 迭代数
     * @param maxContextTokens workspace 上下文最大 token 数
     * @param asyncToolOffloadTimeout 长工具转后台前的等待时间
     */
    public TeamAgentFactory(
            Model model,
            RoleToolkitFactory toolkitFactory,
            RoleSubagentFactory subagentFactory,
            McpSandboxConfiguration sandboxConfiguration,
            Map<String, Set<String>> activeSkillsByRole,
            int maxIterations,
            int maxContextTokens,
            Duration asyncToolOffloadTimeout
    ) {
        this(
                model,
                toolkitFactory,
                subagentFactory,
                sandboxConfiguration,
                activeSkillsByRole,
                maxIterations,
                maxContextTokens,
                asyncToolOffloadTimeout,
                new ModelActivityRegistry()
        );
    }

    /**
     * 创建由配置控制且与 Gateway 共享模型活动记录器的团队 Agent 工厂。
     *
     * @param model AgentScope 原始模型 Bean
     * @param toolkitFactory 角色 Toolkit 工厂
     * @param subagentFactory 角色 Sub-Agent 工厂
     * @param sandboxConfiguration AIO-Sandbox MCP 配置
     * @param activeSkillsByRole 按角色启用的辅助 Skill
     * @param maxIterations 单回合最大 ReAct 迭代数
     * @param maxContextTokens workspace 上下文最大 token 数
     * @param asyncToolOffloadTimeout 长工具转后台前的等待时间
     * @param modelActivityRegistry 与 Gateway 共享的角色模型活动注册表
     */
    public TeamAgentFactory(
            Model model,
            RoleToolkitFactory toolkitFactory,
            RoleSubagentFactory subagentFactory,
            McpSandboxConfiguration sandboxConfiguration,
            Map<String, Set<String>> activeSkillsByRole,
            int maxIterations,
            int maxContextTokens,
            Duration asyncToolOffloadTimeout,
            ModelActivityRegistry modelActivityRegistry
    ) {
        this.model = Objects.requireNonNull(model, "model 不能为空");
        this.modelActivityRegistry = Objects.requireNonNull(
                modelActivityRegistry, "modelActivityRegistry 不能为空"
        );
        this.toolkitFactory = Objects.requireNonNull(toolkitFactory, "toolkitFactory 不能为空");
        this.subagentFactory = Objects.requireNonNull(subagentFactory, "subagentFactory 不能为空");
        this.sandboxConfiguration = Objects.requireNonNull(
                sandboxConfiguration, "sandboxConfiguration 不能为空"
        );
        this.activeSkillsByRole = copySkills(activeSkillsByRole);
        if (maxIterations <= 0 || maxContextTokens <= 0) {
            throw new IllegalArgumentException("Agent 迭代次数和上下文 token 必须大于零");
        }
        this.maxIterations = maxIterations;
        this.maxContextTokens = maxContextTokens;
        this.asyncToolOffloadTimeout = requireSafeOffloadTimeout(asyncToolOffloadTimeout);
    }

    /**
     * 构造单个角色 Agent；Harness 默认会话持久化保持开启，由其独占模型上下文。
     *
     * @param role manager、pm、rd 或 qa
     * @return 已接入 workspace、Skill、Sub-Agent、Toolkit 和 MCP 的 Agent
     */
    public HarnessAgent create(String role) {
        Path roleWorkspace = subagentFactory.roleWorkspace(role);
        List<SubagentDeclaration> subagents = subagentFactory.create(role);
        Toolkit toolkit = toolkitFactory.create(role, activeSkillsByRole.getOrDefault(role, Set.of()));
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(role)
                .description(roleDescription(role))
                .sysPrompt("遵循当前角色 workspace 中的 AGENTS.md、Skill 与团队协作约束完成任务。")
                .model(new ActivityTrackingModel(model, modelActivityRegistry, role))
                .toolkit(toolkit)
                .workspace(roleWorkspace)
                .stateStore(new PendingToolRecoveringStateStore(
                        new JsonFileAgentStateStore(roleWorkspace.resolve(".agentscope/state"))
                ))
                .subagents(subagents)
                .maxIters(maxIterations)
                .maxContextTokens(maxContextTokens)
                .asyncToolTimeout(asyncToolOffloadTimeout)
                // 长工具链被中断后，由 AgentScope 注入缺失工具结果，使后续 wake 能继续修复或明确失败。
                .enablePendingToolRecovery(true)
                // 团队运行时无人值守；沙盒命令在隔离容器执行，宿主工具仍受各自边界校验保护。
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                .disableDynamicSubagents()
                // 正式产物只能经团队共享工具读写，避免 Harness 相对路径文件工具写入错误目录或长时间阻塞。
                .disableFilesystemTools()
                .disableShellTool();
        HarnessAgent.Builder configuredBuilder = sandboxConfiguration.applyTo(builder, toolkit, role);
        if (sandboxConfiguration.enabled() && Set.of("rd", "qa").contains(role)) {
            toolkit.registerAgentTool(toolkitFactory.createProjectTestTool(toolkit, role));
        }
        HarnessAgent agent = configuredBuilder.build();
        replaceBlockingTaskOutput(agent);
        trackTaskSemantics(agent);
        return agent;
    }

    /**
     * 在 Harness 完成内置工具装配后替换 task_output，防止模型请求的等待时长撞上核心五分钟超时。
     *
     * @param agent 已完成构建的角色 Agent
     */
    private static void replaceBlockingTaskOutput(HarnessAgent agent) {
        Toolkit runtimeToolkit = agent.getToolkit();
        runtimeToolkit.removeTool("task_output");
        runtimeToolkit.registerTool(new NonBlockingTaskOutputTool(agent.getTaskRepository()));
    }

    /**
     * 包装 AgentScope 内置子任务工具，补充 TaskRepository 未保存的任务种类和取消来源。
     *
     * @param agent 已完成内置工具装配的角色 Agent
     */
    private static void trackTaskSemantics(HarnessAgent agent) {
        Toolkit runtimeToolkit = agent.getToolkit();
        TaskSemanticsRegistry registry = TaskSemanticsRegistry.forRepository(agent.getTaskRepository());
        for (String toolName : List.of("agent_spawn", "agent_send", "task_cancel")) {
            AgentTool original = runtimeToolkit.getTool(toolName);
            if (original == null || original instanceof TaskSemanticsTrackingTool) {
                continue;
            }
            runtimeToolkit.removeToolIfSame(toolName, original);
            runtimeToolkit.registerAgentTool(new TaskSemanticsTrackingTool(original, registry));
        }
    }

    /**
     * 一次性构造四角色 Agent；中途失败时关闭已创建实例，避免资源泄漏。
     *
     * @return 以 manager、pm、rd、qa 顺序排列的不可变映射
     */
    public Map<String, HarnessAgent> createAll() {
        Map<String, HarnessAgent> agents = new LinkedHashMap<>();
        try {
            for (String role : TEAM_ROLES) {
                agents.put(role, create(role));
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(agents));
        } catch (RuntimeException exception) {
            agents.values().forEach(HarnessAgent::close);
            throw exception;
        }
    }

    /**
     * 对角色 Skill 映射进行角色校验和深层不可变复制。
     *
     * @param source 调用方配置
     * @return 不可变角色 Skill 映射
     */
    private static Map<String, Set<String>> copySkills(Map<String, Set<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        source.forEach((role, skills) -> {
            if (!TEAM_ROLES.contains(role)) {
                throw new IllegalArgumentException("未知团队角色: " + role);
            }
            copy.put(role, skills == null ? Set.of() : Set.copyOf(skills));
        });
        return Map.copyOf(copy);
    }

    /**
     * 提供稳定且不包含配置秘密的角色描述。
     *
     * @param role 已校验角色
     * @return 中文角色职责摘要
     */
    private static String roleDescription(String role) {
        return switch (role) {
            case "manager" -> "团队经理，负责需求协调、审核、checkpoint 与项目推进";
            case "pm" -> "产品经理，负责产品方案与需求澄清";
            case "rd" -> "研发工程师，负责技术设计与代码实现";
            case "qa" -> "质量工程师，负责测试设计、执行与缺陷反馈";
            default -> throw new IllegalArgumentException("未知团队角色: " + role);
        };
    }

    /**
     * 校验异步工具卸载阈值为正数且严格早于 AgentScope 核心工具执行器超时。
     *
     * @param timeout 异步工具卸载阈值
     * @return 已校验阈值
     */
    private static Duration requireSafeOffloadTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "asyncToolOffloadTimeout 不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("asyncToolOffloadTimeout 必须大于零");
        }
        if (timeout.compareTo(MAX_ASYNC_TOOL_OFFLOAD_TIMEOUT) > 0) {
            throw new IllegalArgumentException("asyncToolOffloadTimeout 必须不大于 PT4M，以早于核心 PT5M 超时卸载");
        }
        return timeout;
    }
}
