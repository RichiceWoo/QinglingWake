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
 * 按配置优先级探测 DashScope 免费模型，并只选择真实支持 Function Calling 的候选项。
 */
public final class DashScopeModelSelector {

    /** 模型探测只记录候选名称和脱敏结果，不记录 API Key 或响应正文。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DashScopeModelSelector.class);

    /** 探测工具的固定名称，用于确认响应确实包含 Function Calling。 */
    private static final String PROBE_TOOL_NAME = "qingling_model_probe";

    /** 执行单次模型探测的可测试端口。 */
    private final ProbeTransport transport;

    /**
     * 使用 JDK HTTP 客户端创建生产选择器。
     *
     * @param httpClient 共享 HTTP 客户端
     * @param objectMapper JSON 编解码器
     */
    public DashScopeModelSelector(HttpClient httpClient, ObjectMapper objectMapper) {
        this(jdkTransport(httpClient, objectMapper));
    }

    /**
     * 使用可替换传输创建选择器，供无网络单元测试验证回退顺序。
     *
     * @param transport 模型探测传输
     */
    DashScopeModelSelector(ProbeTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
    }

    /**
     * 动态选择第一个可用且支持工具调用的模型；关闭开关时直接返回显式模型。
     *
     * @param agent 模型选择配置
     * @return 最终模型及已探测候选的脱敏结果
     */
    public Selection select(QinglingTeamProperties.Agent agent) {
        Objects.requireNonNull(agent, "agent 配置不能为空");
        if (!agent.freeModelSelectionEnabled()) {
            LOGGER.info("免费模型选择已关闭，使用显式模型: {}", agent.model());
            return new Selection(requireText(agent.model(), "model"), List.of(), false);
        }
        List<String> candidates = normalizedCandidates(agent.modelCandidates());
        List<Attempt> attempts = new ArrayList<>();
        for (String candidate : candidates) {
            try {
                ProbeResult result = transport.probe(
                        agent.modelProbeUrl(), agent.apiKey(), candidate, agent.modelProbeTimeout()
                );
                attempts.add(new Attempt(candidate, result.status()));
                if (result.available()) {
                    LOGGER.info("已选择可用的 DashScope 免费模型: {}", candidate);
                    return new Selection(candidate, attempts, true);
                }
                LOGGER.warn("DashScope 免费模型探测未通过: model={}, status={}", candidate, result.status());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DashScope 免费模型探测被中断", exception);
            } catch (IOException | RuntimeException exception) {
                attempts.add(new Attempt(candidate, "transport_error"));
                LOGGER.warn("DashScope 免费模型探测失败: model={}, status=transport_error", candidate);
            }
        }
        throw new IllegalStateException("没有可用且支持 Function Calling 的免费模型，探测结果=" + attempts);
    }

    /**
     * 清理空值和重复候选，同时保持用户配置的优先级。
     *
     * @param candidates 原始候选列表
     * @return 稳定有序的候选列表
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
     * 构造只要求调用固定探测函数的最小 OpenAI 兼容请求。
     *
     * @param httpClient JDK HTTP 客户端
     * @param objectMapper JSON 编解码器
     * @return 可复用探测传输
     */
    private static ProbeTransport jdkTransport(HttpClient httpClient, ObjectMapper objectMapper) {
        Objects.requireNonNull(httpClient, "httpClient 不能为空");
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        return (endpoint, apiKey, model, timeout) -> {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", PROBE_TOOL_NAME);
            function.put("description", "模型可用性探测；收到请求时必须调用此函数");
            function.put("parameters", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of(
                    "role", "user", "content", "请只调用 qingling_model_probe，不要输出普通文本。"
            )));
            body.put("tools", List.of(Map.of("type", "function", "function", function)));
            body.put("tool_choice", Map.of("type", "function", "function", Map.of("name", PROBE_TOOL_NAME)));
            body.put("stream", false);
            body.put("max_tokens", 32);
            body.put("enable_thinking", false);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ProbeResult(false, "http_" + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode toolCalls = root.path("choices").path(0).path("message").path("tool_calls");
            for (JsonNode toolCall : toolCalls) {
                if (PROBE_TOOL_NAME.equals(toolCall.path("function").path("name").asText())) {
                    return new ProbeResult(true, "available");
                }
            }
            return new ProbeResult(false, "function_call_missing");
        };
    }

    /** 校验必填文本，避免关闭动态选择时接受空模型名。 */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 不能为空");
        }
        return value.strip();
    }

    /** 单次模型探测端口。 */
    @FunctionalInterface
    interface ProbeTransport {
        /** 返回模型是否可用且支持 Function Calling。 */
        ProbeResult probe(URI endpoint, String apiKey, String model, Duration timeout)
                throws IOException, InterruptedException;
    }

    /** 单次候选探测结果。 */
    record ProbeResult(boolean available, String status) {
        /** 固化非空的脱敏状态。 */
        ProbeResult {
            status = requireText(status, "status");
        }
    }

    /** 单个候选的脱敏探测记录。 */
    public record Attempt(
            /** 候选模型名称。 */ String modelName,
            /** available、HTTP 状态分类或传输错误。 */ String status
    ) { }

    /** 最终模型选择结果。 */
    public record Selection(
            /** 实际传给 AgentScope 的模型名称。 */ String modelName,
            /** 按尝试顺序记录的脱敏结果。 */ List<Attempt> attempts,
            /** 是否经过动态探测。 */ boolean dynamicallySelected
    ) {
        /** 防止调用方修改探测记录。 */
        public Selection {
            modelName = requireText(modelName, "modelName");
            attempts = List.copyOf(attempts);
        }

        /** 返回证据文件使用的稳定选择模式，不包含任何凭据信息。 */
        public String selectionMode() {
            return dynamicallySelected ? "free_dynamic" : "explicit";
        }
    }
}
