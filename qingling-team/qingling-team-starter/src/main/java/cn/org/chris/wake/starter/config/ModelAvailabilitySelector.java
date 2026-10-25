package cn.org.chris.wake.starter.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按显式 Provider 探测模型可用性，并验证真实 Function Calling 响应。
 */
public final class ModelAvailabilitySelector {

    /** 探针日志只记录 Provider、模型与稳定状态，不记录 Key、Header 或响应正文。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelAvailabilitySelector.class);

    /** 固定探测函数名，用于排除普通文本回答造成的假阳性。 */
    private static final String PROBE_TOOL_NAME = "qingling_model_probe";

    /** 幂等模型探针遇到可恢复故障时最多额外执行一次。 */
    private static final int MAX_PROBE_ATTEMPTS = 2;

    /** 两次探针之间的短退避，避免立即撞上同一瞬时限流或连接抖动。 */
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(250);

    /** 执行单次 OpenAI 兼容协议探测的可测试端口。 */
    private final ProbeTransport transport;

    /** 在可恢复故障后执行有界退避的可测试端口。 */
    private final ProbeSleeper sleeper;

    /**
     * 使用 JDK HTTP 客户端创建生产选择器。
     *
     * @param httpClient 共享 HTTP 客户端
     * @param objectMapper JSON 编解码器
     */
    public ModelAvailabilitySelector(HttpClient httpClient, ObjectMapper objectMapper) {
        this(jdkTransport(httpClient, objectMapper), duration -> Thread.sleep(duration.toMillis()));
    }

    /**
     * 使用可替换传输创建选择器，供本地无网络测试验证协议和选择顺序。
     *
     * @param transport 模型探测传输
     */
    ModelAvailabilitySelector(ProbeTransport transport) {
        this(transport, ignored -> { });
    }

    /**
     * 使用可替换传输和退避端口创建选择器，供测试精确验证有限重试。
     *
     * @param transport 模型探测传输
     * @param sleeper 有界重试退避端口
     */
    ModelAvailabilitySelector(ProbeTransport transport, ProbeSleeper sleeper) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper 不能为空");
    }

    /**
     * DashScope 可轮询免费候选；NewAPI 仅使用并按需验证显式模型。
     *
     * @param agent Provider、模型和探针配置
     * @return 最终模型以及脱敏探测记录
     */
    public Selection select(QinglingTeamProperties.Agent agent) {
        Objects.requireNonNull(agent, "agent 配置不能为空");
        if (!agent.freeModelSelectionEnabled()) {
            return selectExplicit(agent);
        }
        List<Attempt> attempts = new ArrayList<>();
        for (String candidate : normalizedCandidates(agent.modelCandidates())) {
            List<Attempt> candidateAttempts = probeWithRetry(agent, candidate);
            attempts.addAll(candidateAttempts);
            Attempt result = candidateAttempts.get(candidateAttempts.size() - 1);
            if (result.available()) {
                LOGGER.info("已选择可用的 DashScope 免费模型: {}", candidate);
                return new Selection(agent.provider(), candidate, attempts, true);
            }
            LOGGER.warn("模型探测未通过: provider={}, model={}, status={}",
                    agent.provider(), candidate, result.status());
        }
        throw new ModelProbeException("没有可用且支持 Function Calling 的免费模型", attempts);
    }

    /**
     * 使用显式模型，并仅在配置开启时执行一次 Provider 对应端点探测。
     *
     * @param agent Provider 与模型配置
     * @return 显式模型选择结果
     */
    private Selection selectExplicit(QinglingTeamProperties.Agent agent) {
        String model = requireText(agent.model(), "model");
        if (!agent.validateExplicitModel()) {
            LOGGER.info("显式模型探测已关闭，使用配置模型: provider={}, model={}", agent.provider(), model);
            return new Selection(agent.provider(), model, List.of(), false);
        }
        List<Attempt> attempts = probeWithRetry(agent, model);
        Attempt result = attempts.get(attempts.size() - 1);
        if (!result.available()) {
            throw new ModelProbeException(
                    "显式模型不可用或不支持 Function Calling: provider=" + agent.provider()
                            + ", model=" + model + ", status=" + result.status(),
                    attempts
            );
        }
        LOGGER.info("显式模型探测通过: provider={}, model={}", agent.provider(), model);
        return new Selection(agent.provider(), model, attempts, false);
    }

    /**
     * 对单个模型执行最多两次幂等探针，只恢复明确可重试的瞬时故障。
     *
     * @param agent Provider 与探针配置
     * @param model 待验证模型名称
     * @return 按真实执行顺序排列的脱敏探测证据
     */
    private List<Attempt> probeWithRetry(QinglingTeamProperties.Agent agent, String model) {
        EndpointCredential target = endpointCredential(agent);
        List<Attempt> attempts = new ArrayList<>();
        for (int attemptNumber = 1; attemptNumber <= MAX_PROBE_ATTEMPTS; attemptNumber++) {
            Attempt attempt = probeOnce(agent, target, model, attemptNumber);
            attempts.add(attempt);
            if (attempt.available() || !attempt.retryable() || attemptNumber == MAX_PROBE_ATTEMPTS) {
                return List.copyOf(attempts);
            }
            try {
                sleeper.sleep(RETRY_BACKOFF);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("模型探测重试退避被中断: provider=" + agent.provider());
            }
        }
        return List.copyOf(attempts);
    }

    /**
     * 执行一次探针并生成不含凭据、请求正文或响应正文的结构化证据。
     *
     * @param agent Provider 与探针配置
     * @param target 已解析的端点和凭据
     * @param model 待验证模型
     * @param attemptNumber 从 1 开始的尝试序号
     * @return 单次脱敏探测证据
     */
    private Attempt probeOnce(
            QinglingTeamProperties.Agent agent,
            EndpointCredential target,
            String model,
            int attemptNumber
    ) {
        long startedAt = System.nanoTime();
        try {
            ProbeResult result = transport.probe(
                    target.endpoint(), target.apiKey(), model, agent.modelProbeTimeout()
            );
            return new Attempt(
                    model, attemptNumber, result.available(), result.status(), result.retryable(),
                    elapsedMillis(startedAt), target.endpoint().getHost(), result.httpStatus(),
                    result.exceptionClass()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型探测被中断: provider=" + agent.provider());
        } catch (IOException | RuntimeException exception) {
            ProbeFailure failure = classifyFailure(exception);
            LOGGER.warn(
                    "模型探测失败: provider={}, model={}, status={}, retryable={}, exception_class={}",
                    agent.provider(), model, failure.status(), failure.retryable(), failure.exceptionClass()
            );
            return new Attempt(
                    model, attemptNumber, false, failure.status(), failure.retryable(),
                    elapsedMillis(startedAt), target.endpoint().getHost(), null, failure.exceptionClass()
            );
        }
    }

    /** 返回单次探针已经消耗的非负毫秒数。 */
    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    /**
     * 将异常链归类为稳定、脱敏的诊断类型，禁止传播异常正文。
     *
     * @param exception 探针传输或解析异常
     * @return 稳定状态、可重试性和异常简单类名
     */
    private static ProbeFailure classifyFailure(Throwable exception) {
        Throwable matched = findCause(exception, UnknownHostException.class, UnresolvedAddressException.class);
        if (matched != null) {
            return failure(ProbeStatus.DNS_FAILURE, true, matched);
        }
        matched = findCause(exception, HttpConnectTimeoutException.class);
        if (matched != null) {
            return failure(ProbeStatus.CONNECT_TIMEOUT, true, matched);
        }
        matched = findCause(exception, HttpTimeoutException.class, SocketTimeoutException.class);
        if (matched != null) {
            return failure(ProbeStatus.REQUEST_TIMEOUT, true, matched);
        }
        matched = findCause(exception, SSLException.class);
        if (matched != null) {
            return failure(ProbeStatus.TLS_FAILURE, true, matched);
        }
        matched = findCause(exception, ConnectException.class);
        if (matched != null) {
            return failure(ProbeStatus.CONNECT_FAILURE, true, matched);
        }
        matched = findCause(exception, SocketException.class);
        if (matched != null) {
            return failure(ProbeStatus.CONNECTION_RESET, true, matched);
        }
        matched = findCause(exception, JsonProcessingException.class);
        if (matched != null) {
            return failure(ProbeStatus.RESPONSE_PARSE_FAILURE, false, matched);
        }
        Throwable cause = deepestCause(exception);
        String exceptionClass = cause.getClass().getSimpleName();
        if (findCause(exception, IOException.class) != null) {
            return new ProbeFailure(ProbeStatus.TRANSPORT_FAILURE.value(), true, exceptionClass);
        }
        return new ProbeFailure(ProbeStatus.CLIENT_FAILURE.value(), false, exceptionClass);
    }

    /** 使用稳定状态、可重试性和匹配异常类构造脱敏分类结果。 */
    private static ProbeFailure failure(ProbeStatus status, boolean retryable, Throwable matched) {
        return new ProbeFailure(status.value(), retryable, matched.getClass().getSimpleName());
    }

    /**
     * 在完整 cause 链中查找首个指定类型，避免 TLS 等包装异常被最深层原因掩盖。
     *
     * @param exception 原始异常
     * @param expectedTypes 需要匹配的异常类型
     * @return 首个匹配异常，不存在时为空
     */
    private static Throwable findCause(Throwable exception, Class<?>... expectedTypes) {
        Throwable current = exception;
        for (int depth = 0; depth < 32 && current != null; depth++) {
            for (Class<?> expectedType : expectedTypes) {
                if (expectedType.isInstance(current)) {
                    return current;
                }
            }
            if (current.getCause() == current) {
                return null;
            }
            current = current.getCause();
        }
        return null;
    }

    /** 返回异常链最深处的原因，并用固定深度避免畸形 cause 循环。 */
    private static Throwable deepestCause(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; depth < 32 && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 严格按 Provider 选择端点和 Key，禁止使用另一供应商配置回退。
     *
     * @param agent Provider 配置
     * @return Provider 专属端点与凭据
     */
    private static EndpointCredential endpointCredential(QinglingTeamProperties.Agent agent) {
        return switch (agent.provider()) {
            case DASHSCOPE -> new EndpointCredential(
                    agent.dashscope().modelProbeUrl(), agent.dashscope().apiKey()
            );
            case NEWAPI -> new EndpointCredential(
                    agent.newApi().chatCompletionsUrl(), agent.newApi().apiKey()
            );
        };
    }

    /**
     * 清理空值和重复候选，同时保持用户配置优先级。
     *
     * @param candidates 原始候选列表
     * @return 稳定有序候选列表
     */
    private static List<String> normalizedCandidates(List<String> candidates) {
        Set<String> normalized = new LinkedHashSet<>();
        if (candidates != null) {
            candidates.stream()
                    .filter(Objects::nonNull)
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .forEach(normalized::add);
        }
        if (normalized.isEmpty()) {
            throw new IllegalStateException("qingling.agent.model-candidates 至少需要一个模型");
        }
        return List.copyOf(normalized);
    }

    /**
     * 构造仅含 OpenAI 标准字段的最小 Function Calling 请求。
     *
     * @param httpClient JDK HTTP 客户端
     * @param objectMapper JSON 编解码器
     * @return 可复用探测传输
     */
    static ProbeTransport jdkTransport(HttpClient httpClient, ObjectMapper objectMapper) {
        HttpClient client = Objects.requireNonNull(httpClient, "httpClient 不能为空");
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        return (endpoint, apiKey, model, timeout) -> {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", PROBE_TOOL_NAME);
            function.put("description", "模型可用性探测；收到请求时必须调用此函数");
            function.put("parameters", Map.of(
                    "type", "object", "properties", Map.of(), "additionalProperties", false
            ));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of(
                    "role", "user", "content", "请只调用 qingling_model_probe，不要输出普通文本。"
            )));
            body.put("tools", List.of(Map.of("type", "function", "function", function)));
            body.put("tool_choice", Map.of(
                    "type", "function", "function", Map.of("name", PROBE_TOOL_NAME)
            ));
            body.put("stream", false);
            body.put("max_tokens", 32);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                return new ProbeResult(false, ProbeStatus.HTTP_429.value(), true, 429, null);
            }
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                return new ProbeResult(false, ProbeStatus.HTTP_4XX.value(), false, response.statusCode(), null);
            }
            if (response.statusCode() >= 500) {
                return new ProbeResult(false, ProbeStatus.HTTP_5XX.value(), true, response.statusCode(), null);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ProbeResult(
                        false, ProbeStatus.UNEXPECTED_HTTP_STATUS.value(), false, response.statusCode(), null
                );
            }
            JsonNode toolCalls = mapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("tool_calls");
            for (JsonNode toolCall : toolCalls) {
                if (PROBE_TOOL_NAME.equals(toolCall.path("function").path("name").asText())) {
                    return new ProbeResult(true, ProbeStatus.AVAILABLE.value(), false, response.statusCode(), null);
                }
            }
            return new ProbeResult(
                    false, ProbeStatus.FUNCTION_CALL_MISSING.value(), false, response.statusCode(), null
            );
        };
    }

    /** 校验非空文本，同时避免错误消息回显实际配置值。 */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 不能为空");
        }
        return value.strip();
    }

    /** 模型探针的稳定脱敏状态集合。 */
    private enum ProbeStatus {
        /** 模型成功返回指定工具调用。 */
        AVAILABLE("available"),
        /** 网关返回任意 4xx 客户端错误。 */
        HTTP_4XX("http_4xx"),
        /** 网关返回任意 5xx 服务端错误。 */
        HTTP_5XX("http_5xx"),
        /** 网关明确返回限流，允许幂等探针短退避后再试一次。 */
        HTTP_429("http_429"),
        /** DNS 无法解析模型端点。 */
        DNS_FAILURE("dns_failure"),
        /** TCP 连接无法建立。 */
        CONNECT_FAILURE("connect_failure"),
        /** TCP 建连超过客户端上限。 */
        CONNECT_TIMEOUT("connect_timeout"),
        /** 请求已发出但未在请求上限内完成。 */
        REQUEST_TIMEOUT("request_timeout"),
        /** TLS 握手或证书校验失败。 */
        TLS_FAILURE("tls_failure"),
        /** 已建立连接被对端或中间网络重置。 */
        CONNECTION_RESET("connection_reset"),
        /** HTTP 成功响应无法解析为预期 JSON。 */
        RESPONSE_PARSE_FAILURE("response_parse_failure"),
        /** 未命中更具体分类的传输或客户端运行时故障。 */
        TRANSPORT_FAILURE("transport_failure"),
        /** 客户端内部运行时故障，不得以传输抖动名义自动重试。 */
        CLIENT_FAILURE("client_failure"),
        /** 收到不属于标准成功或错误范围的 HTTP 状态。 */
        UNEXPECTED_HTTP_STATUS("unexpected_http_status"),
        /** HTTP 成功但未返回指定工具调用。 */
        FUNCTION_CALL_MISSING("function_call_missing");

        /** 对外证据使用的稳定字符串。 */
        private final String value;

        /** 使用稳定字符串创建状态枚举。 */
        ProbeStatus(String value) {
            this.value = value;
        }

        /** 返回不会携带响应正文的稳定状态值。 */
        String value() {
            return value;
        }
    }

    /** 单次模型探测端口。 */
    @FunctionalInterface
    interface ProbeTransport {
        /** 返回模型是否可用且支持 Function Calling。 */
        ProbeResult probe(URI endpoint, String apiKey, String model, Duration timeout)
                throws IOException, InterruptedException;
    }

    /** 有界模型探针重试前的可替换退避端口。 */
    @FunctionalInterface
    interface ProbeSleeper {
        /** 按指定时长等待；中断必须交由调用方恢复线程标记。 */
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Provider 专属端点和凭据，仅在内存中参与请求构造。 */
    private record EndpointCredential(
            /** OpenAI 兼容 Chat Completions 端点。 */ URI endpoint,
            /** 只写入 Authorization Header 的 Provider Key。 */ String apiKey
    ) { }

    /** 单次候选探测结果。 */
    record ProbeResult(
            /** 是否已获得指定 Function Calling。 */ boolean available,
            /** 稳定、脱敏的探测状态。 */ String status,
            /** 同一幂等探针是否允许额外尝试一次。 */ boolean retryable,
            /** HTTP 状态码；传输尚未取得响应时为空。 */ Integer httpStatus,
            /** 异常简单类名；正常 HTTP 响应时为空。 */ String exceptionClass
    ) {
        /** 为测试传输提供不重试、无 HTTP 元数据的兼容构造器。 */
        ProbeResult(boolean available, String status) {
            this(available, status, false, null, null);
        }

        /** 固化非空脱敏状态。 */
        ProbeResult {
            status = requireText(status, "status");
        }
    }

    /** 异常分类后的稳定、脱敏故障元数据。 */
    private record ProbeFailure(
            /** 稳定故障类型。 */ String status,
            /** 是否允许同一探针额外尝试一次。 */ boolean retryable,
            /** 最深层异常的简单类名。 */ String exceptionClass
    ) { }

    /** 单个候选的脱敏探测记录。 */
    public record Attempt(
            /** 候选模型名称。 */ String modelName,
            /** 同一模型从 1 开始的尝试序号。 */ int attempt,
            /** 是否已获得指定 Function Calling。 */ boolean available,
            /** available、HTTP 分类或细分传输错误。 */ String status,
            /** 该结果是否允许额外尝试一次。 */ boolean retryable,
            /** 本次调用耗时，单位毫秒。 */ long elapsedMs,
            /** 不含路径、查询和凭据的目标主机名。 */ String endpointHost,
            /** HTTP 状态码；连接未建立时为空。 */ Integer httpStatus,
            /** 异常简单类名；正常 HTTP 响应时为空。 */ String exceptionClass
    ) {
        /** 校验探测证据的序号、耗时及非空稳定字段。 */
        public Attempt {
            modelName = requireText(modelName, "modelName");
            status = requireText(status, "status");
            endpointHost = requireText(endpointHost, "endpointHost");
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt 必须从 1 开始");
            }
            if (elapsedMs < 0L) {
                throw new IllegalArgumentException("elapsedMs 不能为负数");
            }
        }
    }

    /** 模型预检失败，携带仅含稳定分类的结构化尝试记录。 */
    public static final class ModelProbeException extends IllegalStateException {
        /** 按真实执行顺序保存的不可变脱敏尝试记录。 */
        private final List<Attempt> attempts;

        /**
         * 使用业务摘要和脱敏尝试记录创建启动阻断异常。
         *
         * @param message 不含凭据的失败摘要
         * @param attempts 已执行的探针记录
         */
        public ModelProbeException(String message, List<Attempt> attempts) {
            super(message + "，探测结果=" + attempts);
            this.attempts = List.copyOf(attempts);
        }

        /** 返回不可修改的脱敏探测记录，供启动失败证据持久化。 */
        public List<Attempt> attempts() {
            return attempts;
        }
    }

    /** 最终模型选择结果。 */
    public record Selection(
            /** 实际 Provider。 */ QinglingTeamProperties.ModelProvider provider,
            /** 实际传给 AgentScope 的模型名称。 */ String modelName,
            /** 按尝试顺序记录的脱敏结果。 */ List<Attempt> attempts,
            /** 是否经过 DashScope 免费候选动态选择。 */ boolean dynamicallySelected
    ) {
        /** 防止调用方修改探测记录并拒绝空 Provider。 */
        public Selection {
            provider = Objects.requireNonNull(provider, "provider 不能为空");
            modelName = requireText(modelName, "modelName");
            attempts = List.copyOf(attempts);
        }

        /** 返回证据文件使用的稳定选择模式，不包含任何凭据信息。 */
        public String selectionMode() {
            return dynamicallySelected ? "free_dynamic" : "explicit";
        }
    }
}
