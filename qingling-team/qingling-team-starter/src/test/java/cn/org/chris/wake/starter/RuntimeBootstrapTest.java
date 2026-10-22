package cn.org.chris.wake.starter;

import cn.org.chris.wake.adapter.api.TestApiController;
import cn.org.chris.wake.adapter.feishu.FeishuWebSocketListener;
import cn.org.chris.wake.app.cron.CronService;
import cn.org.chris.wake.infra.agentscope.AgentScopeAgentGateway;
import cn.org.chris.wake.starter.config.QinglingTeamProperties;
import cn.org.chris.wake.starter.config.ModelAvailabilitySelector;
import cn.org.chris.wake.infra.memory.PgVectorMemoryIndexer;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import cn.org.chris.wake.starter.config.RuntimeSettings;
import cn.org.chris.wake.starter.runtime.RuntimeLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 以真实 Spring 容器验证 Task 16 启动模式、Bean 装配与 fail-fast 行为。
 */
class RuntimeBootstrapTest {

    /** JUnit 提供的隔离运行目录。 */
    @TempDir
    Path temporaryDirectory;

    /**
     * 验证无飞书、无 MCP 模式可构造四角色运行时并注册四条 heartbeat。
     */
    @Test
    void shouldStartWithoutFeishuAndStopGracefully() {
        Path workspace = temporaryDirectory.resolve("workspace");
        Path data = temporaryDirectory.resolve("data");

        try (ConfigurableApplicationContext context = start(
                "--qingling.agent.dashscope.api-key=test-only-key",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + data,
                "--qingling.memory.context-dir=" + data.resolve("ctx"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false",
                "--qingling.agent.free-model-selection-enabled=false",
                "--qingling.agent.model=qwen3.8-max",
                "--qingling.debug.test-api-enabled=true",
                "--qingling.cleanup.run-on-startup=false",
                "--qingling.cron.heartbeat-stagger-seconds[0]=3600",
                "--qingling.cron.heartbeat-stagger-seconds[1]=3607",
                "--qingling.cron.heartbeat-stagger-seconds[2]=3614",
                "--qingling.cron.heartbeat-stagger-seconds[3]=3621"
        )) {
            QinglingTeamProperties properties = context.getBean(QinglingTeamProperties.class);
            assertThat(context.getBean(RuntimeSettings.class).workspaceRoot()).isEqualTo(workspace.toAbsolutePath());
            assertThat(properties.agent().provider()).isEqualTo(QinglingTeamProperties.ModelProvider.DASHSCOPE);
            assertThat(properties.agent().freeModelSelectionEnabled()).isFalse();
            assertThat(properties.agent().model()).isEqualTo("qwen3.8-max");
            assertThat(properties.agent().modelCandidates()).containsExactly(
                    "qwen3.8-27b", "qwen3.8-2.4t-a95b", "deepseek-v4.1-flash", "glm-5.3",
                    "kimi-k3", "deepseek-v4-flash-0731", "qwen3.7-flash-2026-07-15", "qwen3.7-flash"
            );
            assertThat(context.getBean(ModelAvailabilitySelector.Selection.class).modelName())
                    .isEqualTo("qwen3.8-max");
            assertThat(context.getBean(ModelAvailabilitySelector.Selection.class).selectionMode())
                    .isEqualTo("explicit");
            assertThat(properties.agent().subAgentMaxIterations()).isEqualTo(20);
            assertThat(properties.agent().shutdownDrainTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
            assertThat(properties.sandbox().enabled()).isFalse();
            assertThat(properties.team().roles()).containsExactly("manager", "pm", "rd", "qa");
            assertThat(context.getBean(RuntimeLifecycle.class).isRunning()).isTrue();
            assertThat(context.getBean(CronService.class).currentJobs()).hasSize(4);
            assertThat(context.getBean(AgentScopeAgentGateway.class)).isNotNull();
            assertThat(context.getBean(TestApiController.class)).isNotNull();
            assertThat(context.getBeansOfType(FeishuWebSocketListener.class)).isEmpty();
            assertThat(Files.isRegularFile(workspace.resolve("manager/AGENTS.md"))).isTrue();
            assertThat(Files.isRegularFile(data.resolve("cron/tasks.json"))).isTrue();
            assertThat(Files.isDirectory(data.resolve("ctx"))).isTrue();
        }
    }

    /**
     * 验证缺少模型密钥时在创建 workspace 前失败，且错误文本不包含秘密值。
     */
    @Test
    void shouldFailFastWithSanitizedMessageWhenModelKeyMissing() {
        Path workspace = temporaryDirectory.resolve("missing-key-workspace");

        Throwable failure = catchThrowable(() -> start(
                "--qingling.agent.dashscope.api-key=",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + temporaryDirectory.resolve("missing-key-data"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false"
        ));

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("DASHSCOPE_API_KEY 或 QWEN_API_KEY 未配置");
        assertThat(Files.exists(workspace)).isFalse();
    }

    /**
     * 验证选择 NewAPI 时空请求域名会在创建 workspace 前失败。
     */
    @Test
    void shouldFailFastWhenNewApiBaseUrlIsEmpty() {
        Path workspace = temporaryDirectory.resolve("missing-newapi-url-workspace");

        Throwable failure = catchThrowable(() -> start(
                "--qingling.agent.provider=newapi",
                "--qingling.agent.model=qwen3.8",
                "--qingling.agent.sub-agent-model=qwen3.8",
                "--qingling.agent.new-api.api-key=sk-test-placeholder",
                "--qingling.agent.new-api.base-url=",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + temporaryDirectory.resolve("missing-newapi-url-data"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false"
        ));

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("NEWAPI_BASE_URL 未配置");
        assertThat(rootMessage(failure)).doesNotContain("sk-test-placeholder");
        assertThat(Files.exists(workspace)).isFalse();
    }

    /**
     * 验证选择 NewAPI 时不会回退使用同时存在的 DashScope Key。
     */
    @Test
    void shouldRequireDedicatedNewApiCredential() {
        Path workspace = temporaryDirectory.resolve("missing-newapi-key-workspace");

        Throwable failure = catchThrowable(() -> start(
                "--qingling.agent.provider=newapi",
                "--qingling.agent.model=qwen3.8",
                "--qingling.agent.sub-agent-model=qwen3.8",
                "--qingling.agent.dashscope.api-key=dashscope-test-key",
                "--qingling.agent.new-api.api-key=",
                "--qingling.agent.new-api.base-url=https://newapi.invalid/v1",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + temporaryDirectory.resolve("missing-newapi-key-data"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false"
        ));

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("NEWAPI_API_KEY 未配置");
        assertThat(rootMessage(failure)).doesNotContain("dashscope-test-key");
        assertThat(Files.exists(workspace)).isFalse();
    }

    /**
     * 验证 NewAPI 模式必须提供显式主模型名称。
     */
    @Test
    void shouldRequireExplicitModelForNewApi() {
        Path workspace = temporaryDirectory.resolve("missing-newapi-model-workspace");

        Throwable failure = catchThrowable(() -> start(
                "--qingling.agent.provider=newapi",
                "--qingling.agent.model=",
                "--qingling.agent.sub-agent-model=qwen3.8",
                "--qingling.agent.new-api.api-key=sk-test-placeholder",
                "--qingling.agent.new-api.base-url=https://newapi.invalid/v1",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + temporaryDirectory.resolve("missing-newapi-model-data"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false"
        ));

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("qingling.agent.model 未配置");
        assertThat(rootMessage(failure)).doesNotContain("sk-test-placeholder");
        assertThat(Files.exists(workspace)).isFalse();
    }

    /**
     * 验证 NewAPI 模式拒绝启用 DashScope 专属的免费候选模型轮询。
     */
    @Test
    void shouldRejectFreeModelSelectionForNewApi() {
        Path workspace = temporaryDirectory.resolve("newapi-free-selection-workspace");

        Throwable failure = catchThrowable(() -> start(
                "--qingling.agent.provider=newapi",
                "--qingling.agent.model=qwen3.8",
                "--qingling.agent.sub-agent-model=qwen3.8",
                "--qingling.agent.free-model-selection-enabled=true",
                "--qingling.agent.new-api.api-key=sk-test-placeholder",
                "--qingling.agent.new-api.base-url=https://newapi.invalid/v1",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + temporaryDirectory.resolve("newapi-free-selection-data"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false"
        ));

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("NewAPI 不支持 DashScope 免费候选模型选择");
        assertThat(rootMessage(failure)).doesNotContain("sk-test-placeholder");
        assertThat(Files.exists(workspace)).isFalse();
    }

    /**
     * 验证 NewAPI 地址不得携带查询参数，错误文本不得回显 SK 或原始地址。
     */
    @Test
    void shouldRejectUnsafeNewApiBaseUrlWithoutLeakingCredential() {
        Path workspace = temporaryDirectory.resolve("unsafe-newapi-url-workspace");

        Throwable failure = catchThrowable(() -> start(
                "--qingling.agent.provider=newapi",
                "--qingling.agent.model=qwen3.8",
                "--qingling.agent.sub-agent-model=qwen3.8",
                "--qingling.agent.new-api.api-key=sk-test-placeholder",
                "--qingling.agent.new-api.base-url=https://newapi.invalid/v1?token=secret-query",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + temporaryDirectory.resolve("unsafe-newapi-url-data"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false"
        ));

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("NEWAPI_BASE_URL 必须是无凭据、无查询参数的 HTTPS 地址");
        assertThat(rootMessage(failure)).doesNotContain("sk-test-placeholder", "secret-query");
        assertThat(Files.exists(workspace)).isFalse();
    }

    /**
     * 验证格式损坏的 NewAPI 地址不会通过底层 URI 异常泄露原始文本。
     */
    @Test
    void shouldSanitizeMalformedNewApiBaseUrl() {
        QinglingTeamProperties.NewApi newApi = new QinglingTeamProperties.NewApi(
                "sk-test-placeholder", "https://newapi.invalid/[secret-query"
        );

        Throwable failure = catchThrowable(newApi::normalizedBaseUrl);

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(rootMessage(failure)).isEqualTo("NEWAPI_BASE_URL 格式非法");
        assertThat(rootMessage(failure)).doesNotContain("secret-query", "sk-test-placeholder");
    }

    /**
     * 验证启用 pgvector 时必须提供独立 Memory DashScope Key，不能继承 Agent Key。
     */
    @Test
    void shouldRequireDedicatedMemoryCredentialWhenPgVectorEnabled() {
        Path workspace = temporaryDirectory.resolve("missing-memory-key-workspace");

        Throwable failure = catchThrowable(() -> start(
                "--qingling.agent.dashscope.api-key=agent-test-key",
                "--qingling.memory.db-dsn=jdbc:postgresql://localhost/test",
                "--qingling.memory.dashscope.api-key=",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + temporaryDirectory.resolve("missing-memory-key-data"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false"
        ));

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("QINGLING_MEMORY_DASHSCOPE_API_KEY 未配置");
        assertThat(rootMessage(failure)).doesNotContain("agent-test-key");
        assertThat(Files.exists(workspace)).isFalse();
    }

    /**
     * 验证 NewAPI Agent 与启用的 Memory 使用两套独立配置，启动期间不访问任一远程端点。
     */
    @Test
    void shouldStartNewApiAgentWithIndependentDashScopeMemory() {
        Path workspace = temporaryDirectory.resolve("newapi-memory-workspace");

        try (ConfigurableApplicationContext context = start(
                "--qingling.agent.provider=newapi",
                "--qingling.agent.model=qwen3.8",
                "--qingling.agent.sub-agent-model=qwen3.8",
                "--qingling.agent.new-api.api-key=sk-agent-placeholder",
                "--qingling.agent.new-api.base-url=https://newapi.invalid/v1",
                "--qingling.memory.db-dsn=jdbc:postgresql://memory.invalid/test",
                "--qingling.memory.dashscope.api-key=memory-placeholder",
                "--qingling.memory.dashscope.base-url=https://memory.invalid/v1/",
                "--qingling.memory.dashscope.extract-model=memory-extract-model",
                "--qingling.memory.dashscope.embedding-model=text-embedding-v3",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + temporaryDirectory.resolve("newapi-memory-data"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false"
        )) {
            QinglingTeamProperties properties = context.getBean(QinglingTeamProperties.class);

            assertThat(context.getBean(Model.class)).isInstanceOf(OpenAIChatModel.class);
            assertThat(context.getBean(PgVectorMemoryIndexer.class)).isNotNull();
            assertThat(properties.agent().newApi().apiKey()).isEqualTo("sk-agent-placeholder");
            assertThat(properties.memory().dashscope().apiKey()).isEqualTo("memory-placeholder");
            assertThat(properties.memory().dashscope().baseUrl())
                    .hasToString("https://memory.invalid/v1/");
            assertThat(properties.memory().dashscope().extractModel()).isEqualTo("memory-extract-model");
        }
    }

    /**
     * 验证 NewAPI 地址可以从纯域名或带尾斜杠的 `/v1` 地址规范化为唯一协议根。
     */
    @Test
    void shouldNormalizeNewApiBaseUrlToVersionRoot() {
        QinglingTeamProperties.NewApi domainOnly = new QinglingTeamProperties.NewApi(
                "sk-test-placeholder", "https://newapi.invalid"
        );
        QinglingTeamProperties.NewApi versionRoot = new QinglingTeamProperties.NewApi(
                "sk-test-placeholder", "https://newapi.invalid/v1/"
        );

        assertThat(domainOnly.normalizedBaseUrl()).hasToString("https://newapi.invalid/v1");
        assertThat(versionRoot.normalizedBaseUrl()).hasToString("https://newapi.invalid/v1");
    }

    /** 使用命令行高优先级属性启动非 Web 测试上下文。 */
    private ConfigurableApplicationContext start(String... arguments) {
        return new SpringApplicationBuilder(QinglingTeamApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(arguments);
    }

    /** 沿异常原因链返回最内层脱敏错误文本。 */
    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
