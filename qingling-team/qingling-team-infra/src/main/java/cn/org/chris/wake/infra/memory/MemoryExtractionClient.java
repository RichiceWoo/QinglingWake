package cn.org.chris.wake.infra.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 调用 DashScope OpenAI 兼容接口提取摘要/标签，并生成固定 1024 维向量。
 */
public final class MemoryExtractionClient implements MemoryExtractionPort {

    /** 与 Python 基线一致的摘要模型。 */
    public static final String DEFAULT_EXTRACT_MODEL = "qwen3.6-max-preview";

    /** 与 Python 基线一致的向量模型。 */
    public static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-v3";

    /** pgvector 表要求的固定向量维度。 */
    public static final int EMBEDDING_DIMENSION = 1024;

    /** DashScope OpenAI 兼容接口默认根地址。 */
    private static final URI DEFAULT_BASE_URI = URI.create(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/"
    );

    /** 单次请求允许的最长时间。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** 摘要提取和标签识别提示模板。 */
    private static final String EXTRACTION_PROMPT = """
            分析以下一轮对话，提取结构化信息，以 JSON 格式返回：

            {
              "summary": "一句话摘要，描述这轮对话做了什么（20字以内）",
              "tags": ["标签1", "标签2"]
            }

            只返回 JSON，不要其他内容。

            用户：%s
            助手：%s
            """;

    /** 不暴露具体 HTTP 客户端的可测试传输端口。 */
    private final HttpTransport transport;

    /** 用于请求与响应 JSON 的 Jackson 实例。 */
    private final ObjectMapper objectMapper;

    /** DashScope API key，只用于 Authorization 请求头。 */
    private final String apiKey;

    /** OpenAI 兼容接口根地址。 */
    private final URI baseUri;

    /** 摘要提取模型名称。 */
    private final String extractModel;

    /** 向量模型名称。 */
    private final String embeddingModel;

    /**
     * 使用 JDK HttpClient 和默认 DashScope 模型创建客户端。
     *
     * @param httpClient JDK HTTP 客户端
     * @param objectMapper JSON 序列化器
     * @param apiKey DashScope API key
     */
    public MemoryExtractionClient(HttpClient httpClient, ObjectMapper objectMapper, String apiKey) {
        this(
                jdkTransport(httpClient), objectMapper, apiKey, DEFAULT_BASE_URI,
                DEFAULT_EXTRACT_MODEL, DEFAULT_EMBEDDING_MODEL
        );
    }

    /**
     * 使用可替换传输和模型配置创建客户端。
     *
     * @param transport HTTP 传输端口
     * @param objectMapper JSON 序列化器
     * @param apiKey DashScope API key
     * @param baseUri OpenAI 兼容接口根地址
     * @param extractModel 摘要模型
     * @param embeddingModel 向量模型
     */
    public MemoryExtractionClient(
            HttpTransport transport,
            ObjectMapper objectMapper,
            String apiKey,
            URI baseUri,
            String extractModel,
            String embeddingModel
    ) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.apiKey = requireText(apiKey, "apiKey");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri 不能为空");
        this.extractModel = requireText(extractModel, "extractModel");
        this.embeddingModel = requireText(embeddingModel, "embeddingModel");
    }

    /**
     * 每轮仅调用一次聊天模型提取摘要与标签；模型返回坏 JSON 时按 Python 规则降级。
     *
     * @param userMessage 用户消息
     * @param assistantReply 助手回复
     * @return 摘要和去空后的标签
     */
    @Override
    public Extraction extract(String userMessage, String assistantReply) throws IOException, InterruptedException {
        String user = Objects.requireNonNullElse(userMessage, "");
        String assistant = Objects.requireNonNullElse(assistantReply, "");
        String prompt = EXTRACTION_PROMPT.formatted(limitCodePoints(user, 500), limitCodePoints(assistant, 500));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", extractModel);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("extra_body", Map.of("enable_thinking", false));
        JsonNode response = post("chat/completions", body);
        String raw = response.path("choices").path(0).path("message").path("content").asText("").strip();
        try {
            JsonNode value = objectMapper.readTree(stripCodeFence(raw));
            if (value == null || !value.isObject()) {
                return new Extraction(limitCodePoints(user, 50), List.of());
            }
            String summary = value.path("summary").asText("");
            List<String> tags = new ArrayList<>();
            if (value.path("tags").isArray()) {
                value.path("tags").forEach(tag -> {
                    if (tag.isTextual() && !tag.asText().isBlank()) {
                        tags.add(tag.asText());
                    }
                });
            }
            return new Extraction(summary, tags);
        } catch (JsonProcessingException malformed) {
            return new Extraction(limitCodePoints(user, 50), List.of());
        }
    }

    /**
     * 批量生成 1024 维向量，并严格校验数量、维度和有限数值。
     *
     * @param texts 待向量化文本
     * @return 与输入顺序一致的不可变向量列表
     */
    @Override
    public List<float[]> embed(List<String> texts) throws IOException, InterruptedException {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        JsonNode response = post("embeddings", Map.of(
                "model", embeddingModel,
                "input", List.copyOf(texts),
                "dimensions", EMBEDDING_DIMENSION
        ));
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new IOException("向量响应数量不匹配");
        }
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.size() != EMBEDDING_DIMENSION) {
                throw new IOException("向量维度必须为 " + EMBEDDING_DIMENSION);
            }
            float[] vector = new float[EMBEDDING_DIMENSION];
            for (int index = 0; index < EMBEDDING_DIMENSION; index++) {
                vector[index] = (float) embedding.get(index).asDouble();
                if (!Float.isFinite(vector[index])) {
                    throw new IOException("向量包含非有限数值");
                }
            }
            vectors.add(vector);
        }
        return List.copyOf(vectors);
    }

    /**
     * 调用指定 OpenAI 兼容端点，错误只包含 HTTP 状态码，不回显响应或密钥。
     */
    private JsonNode post(String relativePath, Object body) throws IOException, InterruptedException {
        byte[] requestBody = objectMapper.writeValueAsBytes(body);
        HttpResult result = transport.post(
                baseUri.resolve(relativePath),
                "Bearer " + apiKey,
                requestBody,
                REQUEST_TIMEOUT
        );
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new IOException("记忆模型 HTTP 调用失败，status=" + result.statusCode());
        }
        return objectMapper.readTree(result.body());
    }

    /**
     * 剥离模型可能返回的 Markdown JSON 代码块。
     */
    private static String stripCodeFence(String raw) {
        if (!raw.startsWith("```")) {
            return raw;
        }
        int firstNewline = raw.indexOf('\n');
        int closingFence = raw.lastIndexOf("```");
        if (firstNewline < 0 || closingFence <= firstNewline) {
            return raw;
        }
        return raw.substring(firstNewline + 1, closingFence).strip();
    }

    /**
     * 按 Unicode code point 截断，保持与 Python 字符切片语义一致。
     */
    static String limitCodePoints(String value, int maximum) {
        String text = Objects.requireNonNullElse(value, "");
        int count = text.codePointCount(0, text.length());
        if (count <= maximum) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maximum));
    }

    /**
     * 校验关键客户端配置非空，异常不得包含实际密钥。
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 未配置");
        }
        return value;
    }

    /**
     * 把 JDK HttpClient 适配为窄传输端口。
     */
    private static HttpTransport jdkTransport(HttpClient httpClient) {
        HttpClient client = Objects.requireNonNull(httpClient, "httpClient 不能为空");
        return (uri, authorization, body, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new HttpResult(response.statusCode(), response.body());
        };
    }

    /**
     * 摘要提取结果。
     *
     * @param summary 一句话摘要，坏 JSON 时为用户消息前 50 字符
     * @param tags 领域标签
     */
    public record Extraction(
            /** 一句话对话摘要。 */ String summary,
            /** 去除空值后的领域标签。 */ List<String> tags
    ) {
        /**
         * 标准化可空摘要并复制标签列表。
         */
        public Extraction {
            summary = Objects.requireNonNullElse(summary, "");
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /**
     * 隔离 HTTP 实现的测试端口。
     */
    @FunctionalInterface
    public interface HttpTransport {

        /**
         * 发送 JSON POST 请求。
         *
         * @param uri 完整请求地址
         * @param authorization Authorization 请求头
         * @param body JSON 请求字节
         * @param timeout 请求超时
         * @return HTTP 状态和响应字节
         */
        HttpResult post(URI uri, String authorization, byte[] body, Duration timeout)
                throws IOException, InterruptedException;
    }

    /**
     * 最小 HTTP 响应。
     *
     * @param statusCode HTTP 状态码
     * @param body 响应字节
     */
    public record HttpResult(
            /** HTTP 状态码。 */ int statusCode,
            /** 响应 JSON 字节。 */ byte[] body
    ) {
        /**
         * 复制响应字节，避免测试或调用方后续修改。
         */
        public HttpResult {
            body = body == null ? new byte[0] : body.clone();
        }

        /**
         * 返回响应字节副本。
         */
        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
