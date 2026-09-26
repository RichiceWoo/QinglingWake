package cn.org.chris.wake.infra.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 DashScope 摘要提取、坏 JSON 降级和固定维度向量契约。
 */
class MemoryExtractionClientTest {

    /** JSON 构造与请求断言使用的序列化器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Markdown JSON 应被正确剥离，并按 Python 基线提取摘要与标签。
     */
    @Test
    void shouldExtractSummaryAndTagsFromCodeFence() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        transport.responses.add(jsonResponse("""
                {"choices":[{"message":{"content":"```json\\n{\\\"summary\\\":\\\"完成迁移\\\",\\\"tags\\\":[\\\"Java\\\",\\\"pgvector\\\"]}\\n```"}}]}
                """));
        MemoryExtractionClient client = client(transport);

        MemoryExtractionClient.Extraction extraction = client.extract("用户消息", "助手回复");

        assertThat(extraction.summary()).isEqualTo("完成迁移");
        assertThat(extraction.tags()).containsExactly("Java", "pgvector");
        CapturedRequest request = transport.requests.get(0);
        assertThat(request.uri().getPath()).endsWith("/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer test-key");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.path("model").asText()).isEqualTo(MemoryExtractionClient.DEFAULT_EXTRACT_MODEL);
        assertThat(body.path("extra_body").path("enable_thinking").asBoolean()).isFalse();
    }

    /**
     * 模型返回坏 JSON 时应回退到用户消息前 50 个 Unicode 字符且标签为空。
     */
    @Test
    void shouldFallbackWhenExtractionJsonIsMalformed() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        transport.responses.add(jsonResponse("""
                {"choices":[{"message":{"content":"not-json"}}]}
                """));
        MemoryExtractionClient client = client(transport);
        String user = "😀".repeat(55);

        MemoryExtractionClient.Extraction extraction = client.extract(user, "reply");

        assertThat(extraction.summary().codePointCount(0, extraction.summary().length())).isEqualTo(50);
        assertThat(extraction.tags()).isEmpty();
    }

    /**
     * embedding 请求必须声明 1024 维，并按响应顺序返回两份向量。
     */
    @Test
    void shouldRequestTwo1024DimensionVectors() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        transport.responses.add(vectorResponse(2, MemoryExtractionClient.EMBEDDING_DIMENSION));
        MemoryExtractionClient client = client(transport);

        List<float[]> vectors = client.embed(List.of("摘要", "完整消息"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(1024);
        assertThat(vectors.get(1)[1023]).isEqualTo(1023.0f);
        JsonNode request = objectMapper.readTree(transport.requests.get(0).body());
        assertThat(request.path("dimensions").asInt()).isEqualTo(1024);
        assertThat(request.path("input")).hasSize(2);
    }

    /**
     * 非 1024 维响应和 HTTP 错误都应失败，异常不得包含 API key 或响应正文。
     */
    @Test
    void shouldRejectInvalidVectorAndRedactHttpFailure() {
        CapturingTransport invalidVector = new CapturingTransport();
        invalidVector.responses.add(vectorResponse(1, 3));
        assertThatThrownBy(() -> client(invalidVector).embed(List.of("text")))
                .hasMessageContaining("1024");

        CapturingTransport httpFailure = new CapturingTransport();
        httpFailure.responses.add(new MemoryExtractionClient.HttpResult(
                401, "test-key secret-response".getBytes(StandardCharsets.UTF_8)
        ));
        assertThatThrownBy(() -> client(httpFailure).extract("user", "reply"))
                .hasMessageContaining("401")
                .hasMessageNotContaining("test-key")
                .hasMessageNotContaining("secret-response");
    }

    /**
     * 创建使用内存传输的提取客户端。
     */
    private MemoryExtractionClient client(CapturingTransport transport) {
        return new MemoryExtractionClient(
                transport,
                objectMapper,
                "test-key",
                URI.create("https://example.test/v1/"),
                MemoryExtractionClient.DEFAULT_EXTRACT_MODEL,
                MemoryExtractionClient.DEFAULT_EMBEDDING_MODEL
        );
    }

    /**
     * 将 JSON 文本包装成成功 HTTP 响应。
     */
    private static MemoryExtractionClient.HttpResult jsonResponse(String json) {
        return new MemoryExtractionClient.HttpResult(200, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 构造指定数量和维度的 embedding 响应。
     */
    private MemoryExtractionClient.HttpResult vectorResponse(int count, int dimension) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode data = root.putArray("data");
        for (int itemIndex = 0; itemIndex < count; itemIndex++) {
            ArrayNode embedding = data.addObject().putArray("embedding");
            for (int dimensionIndex = 0; dimensionIndex < dimension; dimensionIndex++) {
                embedding.add(dimensionIndex);
            }
        }
        try {
            return new MemoryExtractionClient.HttpResult(200, objectMapper.writeValueAsBytes(root));
        } catch (IOException impossible) {
            throw new IllegalStateException("内存 JSON 序列化失败", impossible);
        }
    }

    /**
     * 捕获请求并按队列返回预设响应。
     */
    private static final class CapturingTransport implements MemoryExtractionClient.HttpTransport {

        /** 待返回响应队列。 */
        private final List<MemoryExtractionClient.HttpResult> responses = new ArrayList<>();

        /** 已收到请求。 */
        private final List<CapturedRequest> requests = new ArrayList<>();

        /**
         * 保存请求并弹出首个预设响应。
         */
        @Override
        public MemoryExtractionClient.HttpResult post(
                URI uri,
                String authorization,
                byte[] body,
                Duration timeout
        ) {
            requests.add(new CapturedRequest(uri, authorization, body.clone(), timeout));
            return responses.remove(0);
        }
    }

    /**
     * 测试捕获的 HTTP 请求。
     *
     * @param uri 请求地址
     * @param authorization 认证请求头
     * @param body 请求 JSON 字节
     * @param timeout 超时配置
     */
    private record CapturedRequest(
            /** 请求地址。 */ URI uri,
            /** 认证请求头。 */ String authorization,
            /** 请求 JSON 字节。 */ byte[] body,
            /** 超时配置。 */ Duration timeout
    ) {
        /**
         * 复制请求字节，保证捕获内容不可变。
         */
        private CapturedRequest {
            body = body.clone();
        }

        /**
         * 返回请求字节副本。
         */
        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
