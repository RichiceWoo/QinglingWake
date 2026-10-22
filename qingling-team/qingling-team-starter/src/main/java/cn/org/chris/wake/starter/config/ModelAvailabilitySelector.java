package cn.org.chris.wake.starter.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    /** 执行单次 OpenAI 兼容协议探测的可测试端口。 */
    private final ProbeTransport transport;

    /**
     * 使用 JDK HTTP 客户端创建生产选择器。
     *
     * @param httpClient 共享 HTTP 客户端
     * @param objectMapper JSON 编解码器
     */
    public ModelAvailabilitySelector(HttpClient httpClient, ObjectMapper objectMapper) {
        this(jdkTransport(httpClient, objectMapper));
    }

    /**
     * 使用可替换传输创建选择器，供本地无网络测试验证协议和选择顺序。
     *
     * @param transport 模型探测传输
     */
    ModelAvailabilitySelector(ProbeTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
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
            ProbeResult result = probe(agent, candidate);
            attempts.add(new Attempt(candidate, result.status()));
            if (result.available()) {
                LOGGER.info("已选择可用的 DashScope 免费模型: {}", candidate);
                return new Selection(agent.provider(), candidate, attempts, true);
            }
            LOGGER.warn("模型探测未通过: provider={}, model={}, status={}",
                    agent.provider(), candidate, result.status());
        }
        throw new IllegalStateException("没有可用且支持 Function Calling 的免费模型，探测结果=" + attempts);
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
        ProbeResult result = probe(agent, model);
        Attempt attempt = new Attempt(model, result.status());
        if (!result.available()) {
            throw new IllegalStateException(
                    "显式模型不可用或不支持 Function Calling: provider=" + agent.provider()
                            + ", model=" + model + ", status=" + result.status()
            );
        }
        LOGGER.info("显式模型探测通过: provider={}, model={}", agent.provider(), model);
        return new Selection(agent.provider(), model, List.of(attempt), false);
    }

    /**
     * 对单个模型执行有界探针，并把传输异常收敛为不含凭据的稳定状态。
     *
     * @param agent Provider 与探针配置
     * @param model 待验证模型名称
     * @return 脱敏可用性结果
     */
    private ProbeResult probe(QinglingTeamProperties.Agent agent, String model) {
        EndpointCredential target = endpointCredential(agent);
        try {
            return transport.probe(target.endpoint(), target.apiKey(), model, agent.modelProbeTimeout());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型探测被中断: provider=" + agent.provider());
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("模型探测失败: provider={}, model={}, status=transport_error",
                    agent.provider(), model);
            return new ProbeResult(false, ProbeStatus.TRANSPORT_ERROR.value());
        }
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
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                return new ProbeResult(false, ProbeStatus.HTTP_4XX.value());
            }
            if (response.statusCode() >= 500) {
                return new ProbeResult(false, ProbeStatus.HTTP_5XX.value());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ProbeResult(false, ProbeStatus.TRANSPORT_ERROR.value());
            }
            JsonNode toolCalls = mapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("tool_calls");
            for (JsonNode toolCall : toolCalls) {
                if (PROBE_TOOL_NAME.equals(toolCall.path("function").path("name").asText())) {
                    return new ProbeResult(true, ProbeStatus.AVAILABLE.value());
                }
            }
            return new ProbeResult(false, ProbeStatus.FUNCTION_CALL_MISSING.value());
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
        /** 网络、超时、解析或非 HTTP 成功路径失败。 */
        TRANSPORT_ERROR("transport_error"),
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

    /** Provider 专属端点和凭据，仅在内存中参与请求构造。 */
    private record EndpointCredential(
            /** OpenAI 兼容 Chat Completions 端点。 */ URI endpoint,
            /** 只写入 Authorization Header 的 Provider Key。 */ String apiKey
    ) { }

    /** 单次候选探测结果。 */
    record ProbeResult(boolean available, String status) {
        /** 固化非空脱敏状态。 */
        ProbeResult {
            status = requireText(status, "status");
        }
    }

    /** 单个候选的脱敏探测记录。 */
    public record Attempt(
            /** 候选模型名称。 */ String modelName,
            /** available、HTTP 分类或传输错误。 */ String status
    ) { }

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
