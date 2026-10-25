package cn.org.chris.wake.starter.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLHandshakeException;

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

    /** 客户端运行时异常即使携带密钥，也只能转换为不可重试的脱敏失败状态。 */
    @Test
    void shouldNotLeakApiKeyFromProbeFailure() {
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector((endpoint, apiKey, model, timeout) -> {
            throw new IllegalStateException("upstream rejected " + apiKey);
        });

        assertThatThrownBy(() -> selector.select(agent(
                true, "qwen3.8-max", List.of("qwen3.8-27b")
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client_failure")
                .hasMessageNotContaining("test-api-key");
    }

    /** 首次 5xx 后只允许额外探测一次，第二次成功时应保留两次结构化证据。 */
    @Test
    void shouldRetryRetryableProbeFailureOnceAndThenSucceed() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector(
                (endpoint, apiKey, model, timeout) -> calls.incrementAndGet() == 1
                        ? new ModelAvailabilitySelector.ProbeResult(false, "http_5xx", true, 503, null)
                        : new ModelAvailabilitySelector.ProbeResult(true, "available", false, 200, null),
                ignored -> sleeps.incrementAndGet()
        );

        ModelAvailabilitySelector.Selection selection = selector.select(agent(false, "qwen3.8-flash", null));

        assertThat(calls).hasValue(2);
        assertThat(sleeps).hasValue(1);
        assertThat(selection.attempts()).extracting(ModelAvailabilitySelector.Attempt::status)
                .containsExactly("http_5xx", "available");
        assertThat(selection.attempts()).extracting(ModelAvailabilitySelector.Attempt::attempt)
                .containsExactly(1, 2);
    }

    /** 确定性 4xx 不得触发重试，避免重复发送无效请求。 */
    @Test
    void shouldNotRetryNonRetryableHttpFailure() {
        AtomicInteger calls = new AtomicInteger();
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector((endpoint, apiKey, model, timeout) -> {
            calls.incrementAndGet();
            return new ModelAvailabilitySelector.ProbeResult(false, "http_4xx", false, 404, null);
        });

        assertThatThrownBy(() -> selector.select(agent(false, "missing-model", null)))
                .isInstanceOf(ModelAvailabilitySelector.ModelProbeException.class)
                .satisfies(failure -> assertThat(
                        ((ModelAvailabilitySelector.ModelProbeException) failure).attempts()
                ).hasSize(1));
        assertThat(calls).hasValue(1);
    }

    /** 连续传输超时必须在第二次后停止，并保留可重试分类与异常类名。 */
    @Test
    void shouldStopAfterSecondRetryableTimeout() {
        AtomicInteger calls = new AtomicInteger();
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector(
                (endpoint, apiKey, model, timeout) -> {
                    calls.incrementAndGet();
                    throw new HttpTimeoutException("sensitive-timeout-detail");
                },
                ignored -> { }
        );

        assertThatThrownBy(() -> selector.select(agent(false, "qwen3.8-flash", null)))
                .isInstanceOf(ModelAvailabilitySelector.ModelProbeException.class)
                .satisfies(failure -> {
                    List<ModelAvailabilitySelector.Attempt> attempts =
                            ((ModelAvailabilitySelector.ModelProbeException) failure).attempts();
                    assertThat(attempts).hasSize(2);
                    assertThat(attempts).extracting(ModelAvailabilitySelector.Attempt::status)
                            .containsOnly("request_timeout");
                    assertThat(attempts).extracting(ModelAvailabilitySelector.Attempt::exceptionClass)
                            .containsOnly("HttpTimeoutException");
                })
                .hasMessageNotContaining("sensitive-timeout-detail");
        assertThat(calls).hasValue(2);
    }

    /** DNS 与连接拒绝必须使用不同稳定分类，且不得暴露底层异常正文。 */
    @Test
    void shouldClassifyDnsAndConnectFailuresSeparately() {
        ModelAvailabilitySelector dnsSelector = new ModelAvailabilitySelector(
                (endpoint, apiKey, model, timeout) -> {
                    throw new UnknownHostException("secret-dns-detail");
                },
                ignored -> { }
        );
        ModelAvailabilitySelector connectSelector = new ModelAvailabilitySelector(
                (endpoint, apiKey, model, timeout) -> {
                    throw new ConnectException("secret-connect-detail");
                },
                ignored -> { }
        );

        assertProbeFailure(dnsSelector, "dns_failure", "UnknownHostException", "secret-dns-detail");
        assertProbeFailure(connectSelector, "connect_failure", "ConnectException", "secret-connect-detail");
    }

    /** TLS 包装异常即使含有更深层 cause，也必须保持 TLS 分类。 */
    @Test
    void shouldPreserveTlsClassificationAcrossWrappedCause() {
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector(
                (endpoint, apiKey, model, timeout) -> {
                    SSLHandshakeException tlsFailure = new SSLHandshakeException("secret-tls-detail");
                    tlsFailure.initCause(new IllegalArgumentException("certificate-detail"));
                    throw tlsFailure;
                },
                ignored -> { }
        );

        assertProbeFailure(selector, "tls_failure", "SSLHandshakeException", "secret-tls-detail");
    }

    /** 未知客户端运行时错误必须归为不可重试故障，避免掩盖本地编程错误。 */
    @Test
    void shouldNotRetryUnexpectedClientFailure() {
        AtomicInteger calls = new AtomicInteger();
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector(
                (endpoint, apiKey, model, timeout) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("secret-client-detail");
                },
                ignored -> { }
        );

        assertThatThrownBy(() -> selector.select(agent(false, "qwen3.8-flash", null)))
                .isInstanceOf(ModelAvailabilitySelector.ModelProbeException.class)
                .satisfies(failure -> {
                    List<ModelAvailabilitySelector.Attempt> attempts =
                            ((ModelAvailabilitySelector.ModelProbeException) failure).attempts();
                    assertThat(attempts).singleElement().satisfies(attempt -> {
                        assertThat(attempt.status()).isEqualTo("client_failure");
                        assertThat(attempt.retryable()).isFalse();
                        assertThat(attempt.exceptionClass()).isEqualTo("IllegalStateException");
                    });
                })
                .hasMessageNotContaining("secret-client-detail");
        assertThat(calls).hasValue(1);
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

    /**
     * 断言异常被转换为指定稳定分类，且原始敏感正文不会进入异常消息。
     *
     * @param selector 待验证选择器
     * @param status 预期稳定状态
     * @param exceptionClass 预期异常简单类名
     * @param sensitiveText 不得泄露的原始正文
     */
    private static void assertProbeFailure(
            ModelAvailabilitySelector selector,
            String status,
            String exceptionClass,
            String sensitiveText
    ) {
        assertThatThrownBy(() -> selector.select(agent(false, "qwen3.8-flash", null)))
                .isInstanceOf(ModelAvailabilitySelector.ModelProbeException.class)
                .satisfies(failure -> {
                    List<ModelAvailabilitySelector.Attempt> attempts =
                            ((ModelAvailabilitySelector.ModelProbeException) failure).attempts();
                    assertThat(attempts).hasSize(2);
                    assertThat(attempts).extracting(ModelAvailabilitySelector.Attempt::status)
                            .containsOnly(status);
                    assertThat(attempts).extracting(ModelAvailabilitySelector.Attempt::exceptionClass)
                            .containsOnly(exceptionClass);
                })
                .hasMessageNotContaining(sensitiveText);
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
