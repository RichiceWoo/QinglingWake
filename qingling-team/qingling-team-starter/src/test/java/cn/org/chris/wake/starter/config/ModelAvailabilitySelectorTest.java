package cn.org.chris.wake.starter.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证免费模型选择顺序、动态回退和显式关闭行为。
 */
class ModelAvailabilitySelectorTest {

    /** 首个候选不可用时应选择下一个支持 Function Calling 的候选。 */
    @Test
    void shouldSelectFirstAvailableFunctionCallingModel() {
        List<String> probed = new ArrayList<>();
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector((endpoint, apiKey, model, timeout) -> {
            probed.add(model);
            return new ModelAvailabilitySelector.ProbeResult(
                    "deepseek-v4.1-flash".equals(model),
                    "deepseek-v4.1-flash".equals(model) ? "available" : "http_4xx"
            );
        });

        ModelAvailabilitySelector.Selection selection = selector.select(agent(
                true, "paid-fallback", List.of("qwen3.8-27b", "deepseek-v4.1-flash", "glm-5.3")
        ));

        assertThat(selection.modelName()).isEqualTo("deepseek-v4.1-flash");
        assertThat(selection.dynamicallySelected()).isTrue();
        assertThat(selection.selectionMode()).isEqualTo("free_dynamic");
        assertThat(selection.attempts()).extracting(ModelAvailabilitySelector.Attempt::status)
                .containsExactly("http_4xx", "available");
        assertThat(probed).containsExactly("qwen3.8-27b", "deepseek-v4.1-flash");
    }

    /** 关闭动态选择时仍须探测显式模型，避免无效模型拖到首轮业务请求才超时。 */
    @Test
    void shouldProbeConfiguredModelWhenDynamicSelectionDisabled() {
        List<String> probed = new ArrayList<>();
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector((endpoint, apiKey, model, timeout) -> {
            probed.add(model);
            return new ModelAvailabilitySelector.ProbeResult(true, "available");
        });

        ModelAvailabilitySelector.Selection selection = selector.select(agent(
                false, "qwen3.8-max", null
        ));

        assertThat(selection.modelName()).isEqualTo("qwen3.8-max");
        assertThat(selection.dynamicallySelected()).isFalse();
        assertThat(selection.selectionMode()).isEqualTo("explicit");
        assertThat(selection.attempts()).extracting(ModelAvailabilitySelector.Attempt::status)
                .containsExactly("available");
        assertThat(probed).containsExactly("qwen3.8-max");
    }

    /** 普通生产启动未开启显式探针时保持原有零额外模型调用行为。 */
    @Test
    void shouldUseConfiguredModelWithoutProbeByDefault() {
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector((endpoint, apiKey, model, timeout) -> {
            throw new AssertionError("未启用显式模型探针时不应访问网络");
        });

        ModelAvailabilitySelector.Selection selection = selector.select(agent(
                false, false, "qwen3.8-max", null
        ));

        assertThat(selection.modelName()).isEqualTo("qwen3.8-max");
        assertThat(selection.dynamicallySelected()).isFalse();
        assertThat(selection.attempts()).isEmpty();
    }

    /** 显式模型不存在或不支持工具调用时必须在启动阶段以脱敏原因失败。 */
    @Test
    void shouldFailFastWhenConfiguredModelIsUnavailable() {
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector((endpoint, apiKey, model, timeout) ->
                new ModelAvailabilitySelector.ProbeResult(false, "http_4xx")
        );

        assertThatThrownBy(() -> selector.select(agent(false, "qwen3-max", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("显式模型不可用")
                .hasMessageContaining("model=qwen3-max")
                .hasMessageContaining("status=http_4xx")
                .hasMessageNotContaining("test-api-key");
    }

    /** 所有候选不可用或不支持工具调用时应带脱敏状态失败，禁止静默使用付费模型。 */
    @Test
    void shouldFailWhenNoFreeCandidateSupportsFunctionCalling() {
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector((endpoint, apiKey, model, timeout) ->
                new ModelAvailabilitySelector.ProbeResult(false, "function_call_missing")
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
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector((endpoint, apiKey, model, timeout) -> {
            throw new IllegalStateException("upstream rejected " + apiKey);
        });

        assertThatThrownBy(() -> selector.select(agent(
                true, "qwen3.8-max", List.of("qwen3.8-27b")
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transport_error")
                .hasMessageNotContaining("test-api-key");
    }

    /** 本地 Stub 应收到标准路径、Bearer Header 和不含 DashScope 专用字段的请求。 */
    @Test
    void shouldUseStandardOpenAiFunctionCallingContract() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = stubServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            respond(exchange, 200, """
                    {"choices":[{"message":{"tool_calls":[{"function":{"name":"qingling_model_probe"}}]}}]}
                    """);
        });
        try {
            ModelAvailabilitySelector.ProbeResult result = ModelAvailabilitySelector
                    .jdkTransport(HttpClient.newHttpClient(), new ObjectMapper())
                    .probe(endpoint(server), "sk-test-placeholder", "qwen3.8", Duration.ofSeconds(2));

            assertThat(result.available()).isTrue();
            assertThat(result.status()).isEqualTo("available");
            assertThat(path.get()).isEqualTo("/v1/chat/completions");
            assertThat(authorization.get()).isEqualTo("Bearer sk-test-placeholder");
            assertThat(body.get().path("model").asText()).isEqualTo("qwen3.8");
            assertThat(body.get().path("tools")).hasSize(1);
            assertThat(body.get().path("tool_choice").path("function").path("name").asText())
                    .isEqualTo("qingling_model_probe");
            assertThat(body.get().has("enable_thinking")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    /** HTTP 成功但只返回普通文本时必须识别为缺失 Function Calling。 */
    @Test
    void shouldRejectSuccessfulResponseWithoutToolCall() throws Exception {
        HttpServer server = stubServer(exchange -> respond(
                exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}"
        ));
        try {
            ModelAvailabilitySelector.ProbeResult result = ModelAvailabilitySelector
                    .jdkTransport(HttpClient.newHttpClient(), new ObjectMapper())
                    .probe(endpoint(server), "sk-test-placeholder", "qwen3.8", Duration.ofSeconds(2));

            assertThat(result.available()).isFalse();
            assertThat(result.status()).isEqualTo("function_call_missing");
        } finally {
            server.stop(0);
        }
    }

    /** 网关错误必须只暴露 4xx/5xx 分类，不读取或传播响应正文。 */
    @Test
    void shouldClassifyHttpFailuresWithoutResponseBody() throws Exception {
        assertThat(probeStatus(401, "sk-secret-in-body")).isEqualTo("http_4xx");
        assertThat(probeStatus(503, "upstream-secret-in-body")).isEqualTo("http_5xx");
    }

    /** 使用指定状态和敏感响应创建一次本地探测并只返回稳定状态。 */
    private static String probeStatus(int status, String responseBody) throws Exception {
        HttpServer server = stubServer(exchange -> respond(exchange, status, responseBody));
        try {
            return ModelAvailabilitySelector.jdkTransport(HttpClient.newHttpClient(), new ObjectMapper())
                    .probe(endpoint(server), "sk-test-placeholder", "qwen3.8", Duration.ofSeconds(2))
                    .status();
        } finally {
            server.stop(0);
        }
    }

    /** 创建绑定随机本地端口的无外网 HTTP Stub。 */
    private static HttpServer stubServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    /** 返回本地 Stub 的 Chat Completions 地址。 */
    private static URI endpoint(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
    }

    /** 写入本地 Stub 响应并关闭交换对象。 */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    /** 本地 Stub 请求处理端口。 */
    @FunctionalInterface
    private interface ExchangeHandler {
        /** 处理一次探针请求。 */
        void handle(HttpExchange exchange) throws IOException;
    }

    /** 构造测试所需的完整 Agent 配置。 */
    private static QinglingTeamProperties.Agent agent(
            boolean freeModelSelection,
            String model,
            List<String> candidates
    ) {
        return agent(freeModelSelection, true, model, candidates);
    }

    /** 构造可指定显式模型探针开关的完整 Agent 配置。 */
    private static QinglingTeamProperties.Agent agent(
            boolean freeModelSelection,
            boolean validateExplicitModel,
            String model,
            List<String> candidates
    ) {
        return new QinglingTeamProperties.Agent(
                QinglingTeamProperties.ModelProvider.DASHSCOPE,
                model,
                model,
                freeModelSelection,
                validateExplicitModel,
                candidates,
                Duration.ofSeconds(1),
                30,
                30_000,
                8_192,
                20,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                new QinglingTeamProperties.DashScope(
                        "test-api-key", URI.create("https://example.invalid/chat/completions")
                ),
                new QinglingTeamProperties.NewApi("", "")
        );
    }
}
