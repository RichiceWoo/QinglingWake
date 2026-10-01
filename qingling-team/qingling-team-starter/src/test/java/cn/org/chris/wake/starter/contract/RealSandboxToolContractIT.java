package cn.org.chris.wake.starter.contract;

import cn.org.chris.wake.domain.event.EventService;
import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.infra.agentscope.McpSandboxConfiguration;
import cn.org.chris.wake.infra.agentscope.RoleSubagentFactory;
import cn.org.chris.wake.infra.agentscope.TeamAgentFactory;
import cn.org.chris.wake.infra.agentscope.tool.ImageAndSearchTools;
import cn.org.chris.wake.infra.agentscope.tool.RoleToolkitFactory;
import cn.org.chris.wake.infra.persistence.event.JsonlEventRepository;
import cn.org.chris.wake.infra.persistence.mailbox.FileMailboxRepository;
import cn.org.chris.wake.infra.persistence.workspace.FileWorkspaceRepository;
import cn.org.chris.wake.infra.workspace.WorkspaceTemplateInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在不调用模型的前提下验证真实 Docker MCP、RD/QA 子代理和无副作用命令契约。
 */
class RealSandboxToolContractIT {

    /** 真实 AIO-Sandbox MCP 默认地址。 */
    private static final String DEFAULT_MCP_URL = "http://127.0.0.1:8029/mcp";

    /**
     * 创建真实 RD/QA 声明式子代理，核对工具列表并执行 pwd/test -d。
     *
     * @throws Exception 目录准备或工具调用失败
     */
    @Test
    void shouldExposeAndExecuteRealSandboxToolsWithoutCallingModel() throws Exception {
        String configuredWorkspace = System.getProperty("tool.contract.workspace", "");
        if (configuredWorkspace.isBlank()) {
            throw new IllegalStateException("必须通过 -Dtool.contract.workspace 指向 Docker 挂载的 e2e-workspace");
        }
        Path workspaceRoot = Path.of(configuredWorkspace).toAbsolutePath().normalize();
        ObjectMapper objectMapper = new ObjectMapper();
        new WorkspaceTemplateInitializer(workspaceRoot, objectMapper).initialize();
        FileWorkspaceRepository workspaceRepository = new FileWorkspaceRepository(workspaceRoot);
        workspaceRepository.initializeProject("tool-contract");
        Files.createDirectories(workspaceRoot.resolve("shared/projects/tool-contract/code"));
        MailboxService mailbox = new MailboxService(
                new FileMailboxRepository(workspaceRoot, objectMapper)
        );
        RoleToolkitFactory toolkitFactory = new RoleToolkitFactory(
                mailbox,
                workspaceRepository,
                (role, projectId) -> "contract-wake-" + role,
                new EventService(new JsonlEventRepository(workspaceRoot, objectMapper)),
                null,
                new ImageAndSearchTools(workspaceRoot, HttpClient.newHttpClient(), "", objectMapper),
                objectMapper
        );
        McpSandboxConfiguration sandbox = McpSandboxConfiguration.streamableHttp(
                "aio-sandbox",
                System.getProperty("tool.contract.mcp-url", DEFAULT_MCP_URL),
                Map.of(),
                Map.of(),
                List.of("sandbox_execute_bash"),
                Duration.ofSeconds(60),
                Duration.ofSeconds(30)
        );
        TeamAgentFactory factory = new TeamAgentFactory(
                new NoCallModel(),
                toolkitFactory,
                new RoleSubagentFactory(workspaceRoot),
                sandbox
        );

        verifyRoleSubagent(factory, "rd", "code_impl");
        verifyRoleSubagent(factory, "qa", "test_run");
    }

    /**
     * 创建指定父角色及子代理，并在子代理 Toolkit 直接执行真实 MCP 命令。
     *
     * @param factory 团队 Agent 工厂
     * @param role 父角色
     * @param subagentName 子代理名称
     */
    private static void verifyRoleSubagent(
            TeamAgentFactory factory,
            String role,
            String subagentName
    ) {
        HarnessAgent parent = factory.create(role);
        HarnessAgent child = null;
        try {
            Agent created = parent.getSubagentAgentManager().createAgent(
                    subagentName,
                    RuntimeContext.builder()
                            .userId("tool-contract-user")
                            .sessionId("tool-contract-" + role)
                            .build()
            );
            assertThat(created).isInstanceOf(HarnessAgent.class);
            child = (HarnessAgent) created;
            assertThat(child.getToolkit().getToolNames())
                    .contains("sandbox_execute_bash", "run_project_tests")
                    .doesNotContain("read_file", "write_file", "list_files", "edit_file");
            ToolUseBlock use = new ToolUseBlock(
                    "contract-" + role,
                    "sandbox_execute_bash",
                    Map.of(
                            "cmd", "pwd && test -d /workspace/shared/projects/tool-contract/code "
                                    + "&& printf '__QINGLING_TOOL_CONTRACT_OK__'",
                            "timeout", 30
                    ),
                    "{\"cmd\":\"pwd && test -d /workspace/shared/projects/tool-contract/code "
                            + "&& printf '__QINGLING_TOOL_CONTRACT_OK__'\",\"timeout\":30}",
                    Map.of()
            );
            ToolResultBlock result = child.getToolkit().callTool(ToolCallParam.builder()
                    .toolUseBlock(use)
                    .input(use.getInput())
                    .agent(child)
                    .runtimeContext(child.getRuntimeContext())
                    .build()).block(Duration.ofSeconds(60));
            assertThat(result).isNotNull();
            // 注册层必须把 AgentScope 2.0.3 已完成但仍标记 RUNNING 的结果归一化，避免子代理悬空。
            assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
            assertThat(result.getOutput()).isNotEmpty();
            assertThat(result.getOutput().toString()).contains("__QINGLING_TOOL_CONTRACT_OK__");
        } finally {
            if (child != null) {
                child.close();
            }
            parent.close();
        }
    }

    /**
     * 若测试误触模型就返回空流，保证契约测试绝不产生百炼费用。
     */
    private static final class NoCallModel implements Model {

        /**
         * 返回空响应流。
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

        /** 返回固定测试模型名。 */
        @Override
        public String getModelName() {
            return "no-call-model";
        }
    }
}
