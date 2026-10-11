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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 供不同 Sandbox 配置复用的角色 Toolkit 工厂。 */
    private RoleToolkitFactory toolkitFactory;

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
        toolkitFactory = new RoleToolkitFactory(
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
                assertThat(agent.getDelegate().isPendingToolRecoveryEnabled()).isTrue();
                assertThat(agent.getDelegate().getPermissionContext().getMode()).isEqualTo(PermissionMode.BYPASS);
                assertThat(agent.getWorkspaceManager().getWorkspace())
                        .isEqualTo(workspaceRoot.resolve(role).toRealPath());
                assertThat(skillNames(agent)).contains("fixture_skill");
                assertThat(agent.getSubagentAgentManager().hasAgent(taskSubagentName(role))).isTrue();
                assertThat(agent.getSubagentAgentManager().getDeclaration(taskSubagentName(role)))
                        .get().extracting(SubagentDeclaration::isInheritParentPermissions).isEqualTo(true);
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
     * 父 Agent 与声明式 Sub-Agent 都不得暴露 Harness 文件工具，RD 子代理必须继承白名单 MCP 工具。
     */
    @Test
    void shouldDisableFilesystemToolsAndInheritSandboxToolInDeclaredSubagent() {
        McpServerConfig server = new McpServerConfig();
        server.setTransport(McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT);
        server.setEnableTools(List.of("sandbox_execute_bash"));
        McpSandboxConfiguration sandbox = new McpSandboxConfiguration(
                "aio-sandbox",
                server,
                (toolkit, servers, listener) -> {
                    toolkit.registerTool(new FakeSandboxTools());
                    listener.onCompleted(McpServerRegistrationResult.success(
                            "aio-sandbox", McpSandboxConfiguration.STREAMABLE_HTTP_TRANSPORT
                    ));
                }
        );
        TeamAgentFactory sandboxFactory = new TeamAgentFactory(
                new NoCallModel(),
                toolkitFactory,
                new RoleSubagentFactory(workspaceRoot),
                sandbox
        );

        HarnessAgent rd = sandboxFactory.create("rd");
        HarnessAgent codeSubagent = null;
        try {
            assertThat(rd.getToolkit().getToolNames())
                    .contains("sandbox_execute_bash", "run_project_tests")
                    .doesNotContain(
                            "read_file", "write_file", "edit_file", "list_files", "grep_files", "glob_files"
                    );
            Agent created = rd.getSubagentAgentManager().createAgent(
                    "code_impl",
                    RuntimeContext.builder().userId("smoke-user").sessionId("smoke-session").build()
            );
            assertThat(created).isInstanceOf(HarnessAgent.class);
            codeSubagent = (HarnessAgent) created;
            assertThat(codeSubagent.getToolkit().getToolNames())
                    .contains("read_inbox", "sandbox_execute_bash", "run_project_tests")
                    .doesNotContain(
                            "read_file", "write_file", "edit_file", "list_files", "grep_files", "glob_files"
                    );
            assertThat(sandbox.registrationStatuses())
                    .extracting(McpSandboxConfiguration.RegistrationStatus::status)
                    .containsExactly(McpServerRegistrationResult.Status.SUCCESS);
        } finally {
            if (codeSubagent != null) {
                codeSubagent.close();
            }
            rd.close();
        }
    }

    /** 异步工具卸载阈值不得达到核心五分钟超时，否则父工具先超时会遗留后台子任务。 */
    @Test
    void shouldRejectAsyncToolOffloadTimeoutThatCannotBeatCoreTimeout() {
        assertThatThrownBy(() -> new TeamAgentFactory(
                new NoCallModel(),
                toolkitFactory,
                new RoleSubagentFactory(workspaceRoot),
                McpSandboxConfiguration.disabled(),
                Map.of(),
                30,
                30_000,
                Duration.ofMinutes(5)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PT4M")
                .hasMessageContaining("PT5M");
    }

    /** 清理真实 Harness session 时必须取消 TaskRepository 中仍运行的子任务，不能只清父状态。 */
    @Test
    void shouldCancelRealHarnessChildTaskWhenParentSessionIsCleared() {
        HarnessAgent rd = factory.create("rd");
        AgentScopeAgentGateway gateway = new AgentScopeAgentGateway(Map.of("rd", rd));
        RuntimeContext context = RuntimeContext.builder()
                .userId("team-rd")
                .sessionId("session-cancel")
                .put(AgentScopeAgentGateway.ROLE_ATTRIBUTE, "rd")
                .put(AgentScopeAgentGateway.PROJECT_ID_ATTRIBUTE, "todo-mvp")
                .build();
        CompletableFuture<String> childFuture = new CompletableFuture<>();
        BackgroundTask child = rd.getTaskRepository().putTask(
                context,
                "task-cancel",
                "code_impl",
                context.getSessionId(),
                new TaskRunSpec.AdoptedTaskRunSpec(childFuture)
        );

        try {
            gateway.clearSession("rd", context.getUserId(), context.getSessionId());

            assertThat(child.getTaskStatus()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(childFuture).isCancelled();
        } finally {
            gateway.close();
        }
    }

    /** 即使模型沿用 block=true 和十分钟 timeout，替换后的 task_output 也必须立即返回运行中快照。 */
    @Test
    void shouldKeepTaskOutputNonBlockingForLegacyBlockingArguments() {
        HarnessAgent rd = factory.create("rd");
        RuntimeContext context = RuntimeContext.builder()
                .userId("team-rd")
                .sessionId("session-task-output")
                .put(AgentScopeAgentGateway.ROLE_ATTRIBUTE, "rd")
                .build();
        CompletableFuture<String> childFuture = new CompletableFuture<>();
        BackgroundTask child = rd.getTaskRepository().putTask(
                context,
                "task-running",
                "code_impl",
                context.getSessionId(),
                new TaskRunSpec.AdoptedTaskRunSpec(childFuture)
        );
        Map<String, Object> input = Map.of(
                "task_id", child.getTaskId(),
                "block", true,
                "timeout", 600_000L
        );
        ToolUseBlock use = new ToolUseBlock(
                "call-task-output", "task_output", input, "{\"task_id\":\"task-running\"}", Map.of()
        );

        try {
            assertThat(rd.getToolkit().getToolSchemas())
                    .filteredOn(schema -> "task_output".equals(schema.getName()))
                    .singleElement()
                    .extracting(ToolSchema::getDescription)
                    .asString()
                    .contains("始终非阻塞")
                    .doesNotContain("default: 30000", "max: 600000");
            ToolResultBlock result = rd.getToolkit().callTool(ToolCallParam.builder()
                    .toolUseBlock(use)
                    .input(input)
                    .agent(rd)
                    .runtimeContext(context)
                    .build()).block(Duration.ofSeconds(1));

            assertThat(result).isNotNull();
            assertThat(result.getOutput().toString()).contains("task-running");
            assertThat(childFuture).isNotDone();
        } finally {
            childFuture.cancel(true);
            rd.close();
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
                tools: read_inbox,sandbox_execute_bash,run_project_tests
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

    /** 为无网络回归测试提供与真实 MCP 同名的最小沙盒工具。 */
    private static final class FakeSandboxTools {

        /**
         * 返回固定成功结果，测试只验证工具继承而不执行任何宿主命令。
         *
         * @param command 被忽略的沙盒命令
         * @return 固定成功文本
         */
        @Tool(name = "sandbox_execute_bash", description = "测试用沙盒命令", concurrencySafe = true)
        public String execute(
                @ToolParam(name = "cmd", description = "测试命令") String command
        ) {
            return "ok";
        }
    }
}
