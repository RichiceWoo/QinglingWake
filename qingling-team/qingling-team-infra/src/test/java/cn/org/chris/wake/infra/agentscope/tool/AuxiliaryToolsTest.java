package cn.org.chris.wake.infra.agentscope.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证中间产物、受限图片读取和百度搜索的跨语言行为。
 */
class AuxiliaryToolsTest {

    /** JUnit 临时 workspace。 */
    @TempDir
    Path workspace;

    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 中间产物工具应按 Python 规则转换列表和 Map，并保留最近一次结果。
     */
    @Test
    void shouldNormalizeAndSaveIntermediateArtifact() {
        IntermediateArtifactTools tools = new IntermediateArtifactTools(objectMapper);

        String acknowledgement = tools.saveIntermediateProduct(List.of("first", "second"));
        assertThat(acknowledgement).contains("已保存");
        assertThat(tools.latestArtifact()).isEqualTo("first\nsecond");

        tools.saveIntermediateProduct(Map.of("status", "ready"));
        assertThat(tools.latestArtifact()).contains("\"status\"", "\"ready\"");
    }

    /**
     * 图片工具应生成正确 Data URL、透传网络地址并阻止 workspace 外文件。
     *
     * @throws IOException 测试图片写入失败
     */
    @Test
    void shouldLoadOnlyImagesInsideWorkspace() throws IOException {
        Path image = workspace.resolve("uploads/sample.png");
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[]{1, 2});
        Path external = Files.createTempFile("qingling-external-image", ".png");
        ImageAndSearchTools tools = new ImageAndSearchTools(
                workspace, HttpClient.newHttpClient(), "", objectMapper
        );

        assertThat(tools.addImage("uploads/sample.png")).isEqualTo("data:image/png;base64,AQI=");
        assertThat(tools.addImage("https://example.com/image.png"))
                .isEqualTo("https://example.com/image.png");
        assertThat(tools.addImage(external.toString())).contains("路径不在允许的工作区范围内");
    }

    /**
     * 搜索工具应验证参数和外置凭据，不发送无效网络请求。
     */
    @Test
    void shouldValidateSearchBeforeNetworkCall() {
        ImageAndSearchTools tools = new ImageAndSearchTools(
                workspace, HttpClient.newHttpClient(), "", objectMapper
        );

        assertThat(tools.searchWeb(" ", 20, null, null)).contains("查询内容不能为空");
        assertThat(tools.searchWeb("query", 51, null, null)).contains("top_k参数值无效");
        assertThat(tools.searchWeb("query", 20, "day", null)).contains("recency_filter参数值无效");
        assertThat(tools.searchWeb("query", 20, null, java.util.Collections.nCopies(21, "example.com")))
                .contains("站点列表数量超出限制");
        assertThat(tools.searchWeb("query", null, null, null)).contains("缺少API认证密钥");
    }

    /**
     * 有效搜索应发送 Python 兼容请求并格式化 references 响应。
     *
     * @throws Exception HTTP mock 调用失败
     */
    @Test
    void shouldSendCompatibleSearchRequestAndFormatResults() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        ImageAndSearchTools.SearchTransport transport = (request, body) -> {
            authorization.set(request.headers().firstValue("X-Appbuilder-Authorization").orElse(""));
            requestBody.set(body);
            return new ImageAndSearchTools.SearchResponse(200, """
                {"request_id":"req-1","references":[
                  {"id":"1","title":"AgentScope","url":"https://example.com","content":"Java agent"}
                ]}
                """);
        };
        ImageAndSearchTools tools = new ImageAndSearchTools(
                workspace, transport, "api-key", objectMapper
        );

        String result = tools.searchWeb("AgentScope", 5, "month", List.of("example.com"));

        assertThat(result).contains("找到 1 条搜索结果", "AgentScope", "https://example.com", "Java agent");
        assertThat(authorization.get()).isEqualTo("Bearer api-key");
        assertThat(requestBody.get()).contains(
                "\"search_source\":\"baidu_search_v2\"",
                "\"top_k\":5",
                "\"search_recency_filter\":\"month\"",
                "\"site\":[\"example.com\"]"
        );
    }
}
