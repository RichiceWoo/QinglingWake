package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.infra.agentscope.tool.RoleToolkitFactory;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import java.nio.file.Path;
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

    /** 四角色共享的模型实例。 */
    private final Model model;

    /** 按角色提供最小权限团队工具的工厂。 */
    private final RoleToolkitFactory toolkitFactory;

    /** 从外部 workspace 读取声明式 Sub-Agent 的工厂。 */
    private final RoleSubagentFactory subagentFactory;

    /** AIO-Sandbox MCP 配置和注册状态采集器。 */
    private final McpSandboxConfiguration sandboxConfiguration;

    /** 按角色显式启用的辅助 Skill 能力。 */
    private final Map<String, Set<String>> activeSkillsByRole;

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
        this(model, toolkitFactory, subagentFactory, sandboxConfiguration, Map.of());
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
        this.model = Objects.requireNonNull(model, "model 不能为空");
        this.toolkitFactory = Objects.requireNonNull(toolkitFactory, "toolkitFactory 不能为空");
        this.subagentFactory = Objects.requireNonNull(subagentFactory, "subagentFactory 不能为空");
        this.sandboxConfiguration = Objects.requireNonNull(
                sandboxConfiguration, "sandboxConfiguration 不能为空"
        );
        this.activeSkillsByRole = copySkills(activeSkillsByRole);
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
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(role)
                .description(roleDescription(role))
                .sysPrompt("遵循当前角色 workspace 中的 AGENTS.md、Skill 与团队协作约束完成任务。")
                .model(model)
                .toolkit(toolkitFactory.create(role, activeSkillsByRole.getOrDefault(role, Set.of())))
                .workspace(roleWorkspace)
                .stateStore(new JsonFileAgentStateStore(roleWorkspace.resolve(".agentscope/state")))
                .subagents(subagents)
                .disableDynamicSubagents()
                .disableShellTool();
        return sandboxConfiguration.applyTo(builder).build();
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
}
