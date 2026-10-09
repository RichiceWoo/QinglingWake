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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在不调用模型的前提下验证真实 Docker MCP、RD/QA 子代理和无副作用命令契约。
 */
class RealSandboxToolContractIT {

    /** 真实 AIO-Sandbox MCP 默认地址。 */
    private static final String DEFAULT_MCP_URL = "http://127.0.0.1:8029/mcp";

    /** Compose 中专用于 E2E 的固定沙盒容器名。 */
    private static final String SANDBOX_CONTAINER = "qingling-team-e2e-sandbox";

    /** 把结构化工具输入同步写入 AgentScope 要求的原始 content 字段。 */
    private static final ObjectMapper TOOL_INPUT_MAPPER = new ObjectMapper();

    /**
     * 创建真实 RD/QA 声明式子代理，核对工具列表并执行 pwd/test -d。
     *
     * @throws Exception 目录准备或工具调用失败
     */
    @Test
    void shouldExposeAndExecuteRealSandboxToolsWithoutCallingModel() throws Exception {
        String configuredWorkspace = System.getProperty("tool.contract.workspace", "");
        Path projectRoot = locateProjectRoot();
        Path workspaceRoot = configuredWorkspace.isBlank()
                ? projectRoot.resolve("target/e2e-workspace")
                : Path.of(configuredWorkspace).toAbsolutePath().normalize();
        resetContractProject(projectRoot, workspaceRoot.resolve("shared/projects/tool-contract"));
        ensureSandboxPythonDependencies();
        ObjectMapper objectMapper = new ObjectMapper();
        new WorkspaceTemplateInitializer(workspaceRoot, objectMapper).initialize();
        FileWorkspaceRepository workspaceRepository = new FileWorkspaceRepository(workspaceRoot);
        workspaceRepository.initializeProject("tool-contract");
        Path codeDirectory = workspaceRoot.resolve("shared/projects/tool-contract/code");
        Files.createDirectories(codeDirectory);
        Files.writeString(codeDirectory.resolve("test_contract.py"), "def test_contract():\n    assert 2 + 2 == 4\n");
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
        NoCallModel noCallModel = new NoCallModel();
        TeamAgentFactory factory = new TeamAgentFactory(
                noCallModel,
                toolkitFactory,
                new RoleSubagentFactory(workspaceRoot),
                sandbox
        );

        Set<String> executionIds = new LinkedHashSet<>();
        try {
            verifyRoleSubagent(factory, sandbox, "rd", "code_impl", 5, 3, executionIds, true);
            verifyRoleSubagent(factory, sandbox, "qa", "test_run", 5, 2, executionIds, false);
            assertThat(executionIds).hasSize(5);
            assertThat(noCallModel.calls()).as("零模型契约不得触发百炼调用").isZero();
        } finally {
            sandbox.close();
        }
        assertNoOrphanProcesses();
    }

    /** 从当前 Maven 工作目录向上查找包含 Compose 文件的 qingling-team 根目录。 */
    private static Path locateProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("sandbox-docker-compose.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("无法定位 qingling-team/sandbox-docker-compose.yaml");
    }

    /**
     * 清理默认 target 工作区内的契约项目，防止旧测试文件污染连续 pytest 结果。
     *
     * @param projectRoot Maven 项目根目录
     * @param contractProject 契约项目目录
     * @throws Exception 目录遍历或删除失败
     */
    private static void resetContractProject(Path projectRoot, Path contractProject) throws Exception {
        Path allowedRoot = projectRoot.resolve("target/e2e-workspace/shared/projects")
                .toAbsolutePath().normalize();
        Path normalizedProject = contractProject.toAbsolutePath().normalize();
        if (!normalizedProject.startsWith(allowedRoot) || normalizedProject.equals(allowedRoot)) {
            throw new SecurityException("拒绝清理非隔离工具契约目录: " + normalizedProject);
        }
        if (!Files.exists(normalizedProject)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(normalizedProject)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 创建指定父角色及子代理，并在子代理 Toolkit 直接执行真实 MCP 命令。
     *
     * @param factory 团队 Agent 工厂
     * @param sandbox MCP 配置与受控恢复入口
     * @param role 父角色
     * @param subagentName 子代理名称
     * @param shellRuns 无副作用 Shell 连续调用次数
     * @param testRuns 结构化 pytest 连续调用次数
     * @param executionIds 跨角色收集的 execution ID
     * @param exerciseRecovery 是否在当前连接执行受控失效与重建
     */
    private static void verifyRoleSubagent(
            TeamAgentFactory factory,
            McpSandboxConfiguration sandbox,
            String role,
            String subagentName,
            int shellRuns,
            int testRuns,
            Set<String> executionIds,
            boolean exerciseRecovery
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
            for (int index = 0; index < shellRuns; index++) {
                ToolResultBlock shellResult = callTool(
                        child,
                        "shell-" + role + "-" + index,
                        "sandbox_execute_bash",
                        Map.of(
                                "cmd", "pwd && test -d /workspace/shared/projects/tool-contract/code "
                                        + "&& printf '__QINGLING_TOOL_CONTRACT_OK__'",
                                "timeout", 30
                        )
                );
                assertThat(shellResult.getState())
                        .as("Shell 探针失败，工具输出=%s", shellResult.getOutput())
                        .isEqualTo(ToolResultState.SUCCESS);
                assertThat(shellResult.getOutput().toString()).contains("__QINGLING_TOOL_CONTRACT_OK__");
            }
            if (exerciseRecovery) {
                assertThat(sandbox.exerciseControlledRecovery()).as("受控 MCP 连接重建").isTrue();
                assertThat(sandbox.circuitHealth())
                        .allSatisfy(health -> assertThat(health.state())
                                .isEqualTo(McpSandboxConfiguration.CircuitState.CLOSED));
            }
            for (int index = 0; index < testRuns; index++) {
                ToolResultBlock testResult = callTool(
                        child,
                        "pytest-" + role + "-" + index,
                        "run_project_tests",
                        Map.of("project_id", "tool-contract")
                );
                assertThat(testResult.getState())
                        .as("项目测试失败，工具输出=%s", testResult.getOutput())
                        .isEqualTo(ToolResultState.SUCCESS);
                String output = testResult.getOutput().toString();
                assertThat(output).contains("execution_id", "SUCCEEDED", "exit_code", "pytest_passed");
                String executionId = extractExecutionId(output);
                assertThat(executionIds.add(executionId)).as("execution ID 必须唯一").isTrue();
            }
            ToolResultBlock processResult = callTool(
                    child,
                    "process-check-" + role,
                    "sandbox_execute_bash",
                    Map.of(
                            "cmd", "set -eu; ! pgrep -f 'python.*[p]ytest|[p]ip' >/dev/null; "
                                    + "printf '__QINGLING_NO_TEST_ORPHAN__'",
                            "timeout", 30
                    )
            );
            assertThat(processResult.getState())
                    .as("孤儿进程检查失败，工具输出=%s", processResult.getOutput())
                    .isEqualTo(ToolResultState.SUCCESS);
            assertThat(processResult.getOutput().toString()).contains("__QINGLING_NO_TEST_ORPHAN__");
        } finally {
            if (child != null) {
                child.close();
            }
            parent.close();
        }
    }

    /**
     * 在创建 MCP client 前为干净沙盒预置 pytest 和 pytest-cov。
     *
     * <p>该步骤使用固定 {@code docker exec} 参数，仅在容器内安装固定测试依赖，
     * 不暴露宿主机 Shell，也不占用 MCP 命令超时预算。后续 RD/QA 的探针与测试
     * 仍全部通过真实 AgentScope/MCP 工具执行。</p>
     *
     * @throws Exception Docker 进程启动或等待失败
     */
    private static void ensureSandboxPythonDependencies() throws Exception {
        List<String> checkCommand = List.of(
                "docker", "exec", "--user", "1000", SANDBOX_CONTAINER,
                "python", "-c", "import pytest, pytest_cov"
        );
        if (!runFixedDockerCommand(checkCommand, 15)) {
            List<String> installCommand = List.of(
                    "docker", "exec", "--user", "1000", SANDBOX_CONTAINER,
                    "python", "-m", "pip", "install", "--user",
                    "--disable-pip-version-check", "--no-input", "--quiet",
                    "--timeout", "15", "--retries", "1",
                    "--index-url", "https://pypi.org/simple", "pytest", "pytest-cov"
            );
            assertThat(runFixedDockerCommand(installCommand, 180))
                    .as("干净沙盒必须能在 180 秒内安装 pytest 和 pytest-cov")
                    .isTrue();
        }
        assertThat(runFixedDockerCommand(checkCommand, 15))
                .as("沙盒中 pytest 和 pytest-cov 必须可导入")
                .isTrue();
    }

    /**
     * 执行固定的 Docker 参数列表，并在超时时强制终止子进程。
     *
     * @param command 不经 Shell 解析的固定命令参数
     * @param timeoutSeconds 最长等待秒数
     * @return 在时限内以 0 退出时为 true
     * @throws Exception 进程启动或等待失败
     */
    private static boolean runFixedDockerCommand(List<String> command, long timeoutSeconds)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return false;
        }
        return process.exitValue() == 0;
    }

    /**
     * 所有 Agent 与 MCP client 关闭后，通过固定 Docker 诊断命令确认无 pytest、pip 或 tmux 孤儿进程。
     *
     * @throws Exception Docker 诊断启动或等待失败
     */
    private static void assertNoOrphanProcesses() throws Exception {
        Process process = new ProcessBuilder(
                "docker",
                "exec",
                SANDBOX_CONTAINER,
                "sh",
                "-lc",
                "! pgrep -f 'python.*[p]ytest|[p]ip|[t]mux' >/dev/null"
        ).redirectErrorStream(true).start();
        boolean completed = process.waitFor(15, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertThat(completed).as("Docker 孤儿进程诊断必须在 15 秒内结束").isTrue();
        assertThat(process.exitValue()).as("沙盒关闭后不得遗留 pytest、pip 或 tmux").isZero();
    }

    /**
     * 在真实子代理 Toolkit 上直接调用指定工具并要求取得非空终态。
     *
     * @param child 真实 RD 或 QA 子代理
     * @param callId 唯一调用标识
     * @param toolName 工具名称
     * @param input 结构化输入
     * @return 工具结果
     */
    private static ToolResultBlock callTool(
            HarnessAgent child,
            String callId,
            String toolName,
            Map<String, Object> input
    ) {
        String content = TOOL_INPUT_MAPPER.valueToTree(input).toString();
        ToolUseBlock use = new ToolUseBlock(callId, toolName, input, content, Map.of());
        ToolResultBlock result = child.getToolkit().callTool(ToolCallParam.builder()
                .toolUseBlock(use)
                .input(use.getInput())
                .agent(child)
                .runtimeContext(child.getRuntimeContext())
                .build()).block(Duration.ofMinutes(6));
        assertThat(result).isNotNull();
        assertThat(result.getState()).isNotIn(ToolResultState.RUNNING, ToolResultState.INTERRUPTED);
        assertThat(result.getOutput()).isNotEmpty();
        return result;
    }

    /** 从结构化工具输出提取 UUID execution ID。 */
    private static String extractExecutionId(String output) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "execution_id[^0-9a-f]+([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(output);
        assertThat(matcher.find()).as("结构化结果应包含 execution_id: %s", output).isTrue();
        return matcher.group(1);
    }

    /**
     * 若测试误触模型就返回空流，保证契约测试绝不产生百炼费用。
     */
    private static final class NoCallModel implements Model {

        /** 记录任何意外模型调用。 */
        private final AtomicInteger calls = new AtomicInteger();

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
            calls.incrementAndGet();
            return Flux.empty();
        }

        /** 返回模型被误调用的次数。 */
        private int calls() {
            return calls.get();
        }

        /** 返回固定测试模型名。 */
        @Override
        public String getModelName() {
            return "no-call-model";
        }
    }
}
