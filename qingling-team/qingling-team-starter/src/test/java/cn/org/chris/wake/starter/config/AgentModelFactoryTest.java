package cn.org.chris.wake.starter.config;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Provider 工厂在不访问真实网络时创建正确的 AgentScope Model 类型。
 */
class AgentModelFactoryTest {

    /** DashScope Provider 必须继续创建原生 DashScope 模型。 */
    @Test
    void shouldCreateDashScopeModel() {
        Model model = new AgentModelFactory().create(agent(
                QinglingTeamProperties.ModelProvider.DASHSCOPE
        ), "qwen3-max");

        assertThat(model).isInstanceOf(DashScopeChatModel.class);
        assertThat(((DashScopeChatModel) model).getModelName()).isEqualTo("qwen3-max");
    }

    /** NewAPI Provider 必须创建 OpenAI 兼容模型而不是伪装成 DashScope 模型。 */
    @Test
    void shouldCreateOpenAiCompatibleModelForNewApi() {
        Model model = new AgentModelFactory().create(agent(
                QinglingTeamProperties.ModelProvider.NEWAPI
        ), "qwen3.8");

        assertThat(model).isInstanceOf(OpenAIChatModel.class).isNotInstanceOf(DashScopeChatModel.class);
        assertThat(((OpenAIChatModel) model).getModelName()).isEqualTo("qwen3.8");
    }

    /** 构造工厂测试使用的完整 Agent 配置。 */
    private static QinglingTeamProperties.Agent agent(QinglingTeamProperties.ModelProvider provider) {
        return new QinglingTeamProperties.Agent(
                provider, "qwen3.8", "qwen3.8", false, false, List.of(),
                Duration.ofSeconds(1), 30, 30_000, 8_192, 20,
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(5),
                new QinglingTeamProperties.DashScope(
                        "dashscope-placeholder", URI.create("https://example.invalid/v1/chat/completions")
                ),
                new QinglingTeamProperties.NewApi("sk-xxx", "https://newapi.invalid/v1")
        );
    }
}
