package cn.org.chris.wake.starter;

import cn.org.chris.wake.adapter.api.TestApiController;
import cn.org.chris.wake.adapter.feishu.FeishuWebSocketListener;
import cn.org.chris.wake.app.cron.CronService;
import cn.org.chris.wake.infra.agentscope.AgentScopeAgentGateway;
import cn.org.chris.wake.starter.config.QinglingTeamProperties;
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
                "--qingling.agent.api-key=test-only-key",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + data,
                "--qingling.memory.context-dir=" + data.resolve("ctx"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false",
                "--qingling.debug.test-api-enabled=true",
                "--qingling.cleanup.run-on-startup=false",
                "--qingling.cron.heartbeat-stagger-seconds[0]=3600",
                "--qingling.cron.heartbeat-stagger-seconds[1]=3607",
                "--qingling.cron.heartbeat-stagger-seconds[2]=3614",
                "--qingling.cron.heartbeat-stagger-seconds[3]=3621"
        )) {
            QinglingTeamProperties properties = context.getBean(QinglingTeamProperties.class);
            assertThat(context.getBean(RuntimeSettings.class).workspaceRoot()).isEqualTo(workspace.toAbsolutePath());
            assertThat(properties.agent().model()).isEqualTo("qwen3.6-max-preview");
            assertThat(properties.agent().subAgentMaxIterations()).isEqualTo(20);
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
                "--qingling.agent.api-key=",
                "--qingling.workspace.root=" + workspace,
                "--qingling.data-dir=" + temporaryDirectory.resolve("missing-key-data"),
                "--qingling.feishu.enabled=false",
                "--qingling.sandbox.enabled=false"
        ));

        assertThat(failure).isNotNull();
        assertThat(rootMessage(failure)).contains("DASHSCOPE_API_KEY 或 QWEN_API_KEY 未配置");
        assertThat(Files.exists(workspace)).isFalse();
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
