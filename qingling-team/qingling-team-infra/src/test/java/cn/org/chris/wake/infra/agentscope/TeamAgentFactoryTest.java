package cn.org.chris.wake.infra.agentscope;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.infra.agentscope.tool.ImageAndSearchTools;
import cn.org.chris.wake.infra.agentscope.tool.RoleToolkitFactory;
import cn.org.chris.wake.infra.persistence.event.JsonlEventRepository;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证四角色 HarnessAgent 的 workspace、Skill、Toolkit 和声明式 Sub-Agent 装配。
 */
class TeamAgentFactoryTest {

    /** JUnit 提供的无污染临时目录。 */
    @TempDir
    Path temporaryRoot;

    /** 四角色 workspace 根目录。 */
    private Path workspaceRoot;

    /** 待测团队 Agent 工厂。 */
    private TeamAgentFactory factory;

    /**
     * 创建最小但完整的四角色 AgentScope workspace 和领域工具依赖。
     *
     * @throws IOException 测试 fixture 写入失败
     */
    @BeforeEach
    void setUp() throws IOException {
        workspaceRoot = temporaryRoot.resolve("workspace");
        for (String role : TeamAgentFactory.TEAM_ROLES) {
            createRoleWorkspace(role, taskSubagentName(role));
        }
        Path dataRoot = temporaryRoot.resolve("data");
        Files.createDirectories(dataRoot);
        ObjectMapper objectMapper = new ObjectMapper();
        FileWorkspaceRepository workspaceRepository = new FileWorkspaceRepository(dataRoot);
        RoleToolkitFactory toolkitFactory = new RoleToolkitFactory(
                new MailboxService(new FileMailboxRepository(dataRoot, objectMapper)),
                workspaceRepository,
                (role, projectId) -> "job-smoke",
                new EventService(new JsonlEventRepository(dataRoot, objectMapper)),
                null,
                new ImageAndSearchTools(dataRoot, HttpClient.newHttpClient(), "", objectMapper),
                objectMapper
        );
        factory = new TeamAgentFactory(
                new NoCallModel(),
                toolkitFactory,
                new RoleSubagentFactory(workspaceRoot),
                McpSandboxConfiguration.disabled()
        );
    }

    /**
     * 四角色应在不调用模型和 MCP 的情况下构建，并绑定各自 workspace 与 task Sub-Agent。
     *
     * @throws IOException 解析临时 workspace 真实路径失败
     */
    @Test
    void shouldBuildFourRoleAgentsWithoutCallingRealModelOrMcp() throws IOException {
        Map<String, HarnessAgent> agents = factory.createAll();
        try {
            assertThat(agents).containsOnlyKeys("manager", "pm", "rd", "qa");
            for (String role : TeamAgentFactory.TEAM_ROLES) {
                HarnessAgent agent = agents.get(role);
                assertThat(agent.getName()).isEqualTo(role);
                assertThat(agent.getModel().getModelName()).isEqualTo("no-call-model");
                assertThat(agent.getWorkspaceManager().getWorkspace())
                        .isEqualTo(workspaceRoot.resolve(role).toRealPath());
                assertThat(skillNames(agent)).contains("fixture_skill");
                assertThat(agent.getSubagentAgentManager().hasAgent(taskSubagentName(role))).isTrue();
                assertThat(agent.getToolkit().getToolNames())
                        .containsAll(RoleToolkitFactory.COMMON_TEAM_TOOL_NAMES);
            }
            assertThat(agents.get("manager").getToolkit().getToolNames())
                    .containsAll(RoleToolkitFactory.MANAGER_TEAM_TOOL_NAMES);
            assertThat(agents.get("rd").getSubagentAgentManager().getDeclaration("code_impl"))
                    .get().extracting(declaration -> declaration.getSkills())
                    .asList().contains("fixture_skill");
            assertThat(agents.get("qa").getSubagentAgentManager().getDeclaration("test_run"))
                    .isPresent();
            Agent codeSubagent = agents.get("rd").getSubagentAgentManager().createAgent(
                    "code_impl",
                    RuntimeContext.builder().userId("smoke-user").sessionId("smoke-session").build()
            );
            assertThat(codeSubagent.getName()).isEqualTo("code_impl");
            if (codeSubagent instanceof HarnessAgent harnessSubagent) {
                harnessSubagent.close();
            }
        } finally {
            agents.values().forEach(HarnessAgent::close);
        }
    }

    /**
     * 创建角色 workspace、一个可发现 Skill 和一个声明式 task Sub-Agent。
     *
     * @param role 角色名称
     * @param subagentName Sub-Agent 名称
     * @throws IOException fixture 写入失败
     */
    private void createRoleWorkspace(String role, String subagentName) throws IOException {
        Path roleRoot = workspaceRoot.resolve(role);
        Files.createDirectories(roleRoot.resolve("skills/fixture_skill"));
        Files.createDirectories(roleRoot.resolve("subagents"));
        Files.writeString(roleRoot.resolve("AGENTS.md"), "# " + role + "\n遵循测试约束。\n");
        Files.writeString(roleRoot.resolve("MEMORY.md"), "# Memory\n");
        Files.writeString(roleRoot.resolve("skills/fixture_skill/SKILL.md"), """
                ---
                name: fixture_skill
                description: 用于验证 Harness workspace Skill 自动发现
                ---

                执行确定性 fixture 任务。
                """);
        Files.writeString(roleRoot.resolve("subagents/" + subagentName + ".md"), """
                ---
                description: 用于验证 task Skill 到声明式 Sub-Agent 的映射
                mode: all
                workspace:
                  mode: shared
                steps: 3
                tools: read_inbox
                skills: fixture_skill
                ---

                只执行 fixture Skill。
                """);
    }

    /**
     * 返回各角色具有代表性的 task Sub-Agent 名称。
     *
     * @param role 角色名称
     * @return Sub-Agent 名称
     */
    private static String taskSubagentName(String role) {
        return switch (role) {
            case "rd" -> "code_impl";
            case "qa" -> "test_run";
            default -> "mailbox_ops";
        };
    }

    /**
     * 汇总 Harness 中全部 Skill Repository 的 Skill 名称。
     *
     * @param agent 已构建 Agent
     * @return Skill 名称列表
     */
    private static List<String> skillNames(HarnessAgent agent) {
        List<String> names = new ArrayList<>();
        agent.getSkillRepositories().forEach(repository -> names.addAll(repository.getAllSkillNames()));
        return List.copyOf(names);
    }

    /**
     * 若测试误触模型调用就返回空流，不访问任何外部服务。
     */
    private static final class NoCallModel implements Model {

        /**
         * 返回空响应流，保证 smoke 不进行网络请求。
         *
         * @param messages 模型消息
         * @param tools 工具 schema
         * @param options 生成参数
         * @return 空响应流
         */
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options
        ) {
            return Flux.empty();
        }

        /**
         * 返回可断言的测试模型名称。
         *
         * @return 固定测试模型名
         */
        @Override
        public String getModelName() {
            return "no-call-model";
        }
    }
}
