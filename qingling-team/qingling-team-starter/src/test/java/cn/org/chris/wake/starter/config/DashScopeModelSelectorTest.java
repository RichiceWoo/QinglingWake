package cn.org.chris.wake.starter.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证免费模型选择顺序、动态回退和显式关闭行为。
 */
class DashScopeModelSelectorTest {

    /** 首个候选不可用时应选择下一个支持 Function Calling 的候选。 */
    @Test
    void shouldSelectFirstAvailableFunctionCallingModel() {
        List<String> probed = new ArrayList<>();
        DashScopeModelSelector selector = new DashScopeModelSelector((endpoint, apiKey, model, timeout) -> {
            probed.add(model);
            return new DashScopeModelSelector.ProbeResult(
                    "deepseek-v4.1-flash".equals(model),
                    "deepseek-v4.1-flash".equals(model) ? "available" : "http_429"
            );
        });

        DashScopeModelSelector.Selection selection = selector.select(agent(
                true, "paid-fallback", List.of("qwen3.8-27b", "deepseek-v4.1-flash", "glm-5.3")
        ));

        assertThat(selection.modelName()).isEqualTo("deepseek-v4.1-flash");
        assertThat(selection.dynamicallySelected()).isTrue();
        assertThat(selection.selectionMode()).isEqualTo("free_dynamic");
        assertThat(selection.attempts()).extracting(DashScopeModelSelector.Attempt::status)
                .containsExactly("http_429", "available");
        assertThat(probed).containsExactly("qwen3.8-27b", "deepseek-v4.1-flash");
    }

    /** 关闭动态探测时必须直接使用显式模型且不能访问网络。 */
    @Test
    void shouldUseConfiguredModelWithoutProbeWhenDisabled() {
        DashScopeModelSelector selector = new DashScopeModelSelector((endpoint, apiKey, model, timeout) -> {
            throw new AssertionError("关闭动态选择后不应发起探测");
        });

        DashScopeModelSelector.Selection selection = selector.select(agent(
                false, "qwen3.8-max", null
        ));

        assertThat(selection.modelName()).isEqualTo("qwen3.8-max");
        assertThat(selection.dynamicallySelected()).isFalse();
        assertThat(selection.selectionMode()).isEqualTo("explicit");
        assertThat(selection.attempts()).isEmpty();
    }

    /** 所有候选不可用或不支持工具调用时应带脱敏状态失败，禁止静默使用付费模型。 */
    @Test
    void shouldFailWhenNoFreeCandidateSupportsFunctionCalling() {
        DashScopeModelSelector selector = new DashScopeModelSelector((endpoint, apiKey, model, timeout) ->
                new DashScopeModelSelector.ProbeResult(false, "function_call_missing")
        );

        assertThatThrownBy(() -> selector.select(agent(
                true, "paid-fallback", List.of("qwen3.8-27b", "glm-5.3")
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有可用且支持 Function Calling 的免费模型")
                .hasMessageContaining("function_call_missing")
                .hasMessageNotContaining("test-api-key");
    }

    /** 探测传输抛出的异常即使携带密钥，也只能转换为脱敏失败状态。 */
    @Test
    void shouldNotLeakApiKeyFromProbeFailure() {
        DashScopeModelSelector selector = new DashScopeModelSelector((endpoint, apiKey, model, timeout) -> {
            throw new IllegalStateException("upstream rejected " + apiKey);
        });

        assertThatThrownBy(() -> selector.select(agent(
                true, "qwen3.8-max", List.of("qwen3.8-27b")
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transport_error")
                .hasMessageNotContaining("test-api-key");
    }

    /** 构造测试所需的完整 Agent 配置。 */
    private static QinglingTeamProperties.Agent agent(
            boolean freeModelSelection,
            String model,
            List<String> candidates
    ) {
        return new QinglingTeamProperties.Agent(
                model,
                model,
                freeModelSelection,
                candidates,
                URI.create("https://example.invalid/chat/completions"),
                Duration.ofSeconds(1),
                30,
                30_000,
                8_192,
                20,
                Duration.ofMinutes(5),
                "test-api-key"
        );
    }
}
