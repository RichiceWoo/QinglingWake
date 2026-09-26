package cn.org.chris.wake.infra.agentscope.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 提供受 workspace 边界保护的图片加载和百度千帆网页搜索工具。
 */
public final class ImageAndSearchTools {

    /** 单张本地图片允许读取的最大字节数，保持 Python 的 20 MiB 限制。 */
    public static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;

    /** 百度千帆网页搜索端点。 */
    private static final URI BAIDU_SEARCH_URI = URI.create(
            "https://qianfan.baidubce.com/v2/ai_search/web_search"
    );

    /** 支持的发布时间过滤值。 */
    private static final Set<String> RECENCY_FILTERS = Set.of("week", "month", "semiyear", "year");

    /** 已解析真实路径的图片访问根目录。 */
    private final Path workspaceRoot;

    /** 可替换的搜索 HTTP 传输，生产构造器使用 JDK HttpClient。 */
    private final SearchTransport searchTransport;

    /** 百度千帆 AppBuilder API Key；为空时返回建设性错误。 */
    private final String baiduApiKey;

    /** 请求和响应 JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建图片与搜索工具。
     *
     * @param workspaceRoot 本地图片允许读取的根目录
     * @param httpClient JDK HTTP 客户端
     * @param baiduApiKey 可为空的百度 API Key
     * @param objectMapper JSON 编解码器
     */
    public ImageAndSearchTools(
            Path workspaceRoot,
            HttpClient httpClient,
            String baiduApiKey,
            ObjectMapper objectMapper
    ) {
        this(workspaceRoot, jdkTransport(httpClient), baiduApiKey, objectMapper);
    }

    /**
     * 创建可注入确定性搜索传输的工具，供同包契约测试使用。
     *
     * @param workspaceRoot 本地图片允许读取的根目录
     * @param searchTransport 搜索 HTTP 传输
     * @param baiduApiKey 可为空的百度 API Key
     * @param objectMapper JSON 编解码器
     */
    ImageAndSearchTools(
            Path workspaceRoot,
            SearchTransport searchTransport,
            String baiduApiKey,
            ObjectMapper objectMapper
    ) {
        this.workspaceRoot = realWorkspaceRoot(workspaceRoot);
        this.searchTransport = Objects.requireNonNull(searchTransport, "searchTransport 不能为空");
        this.baiduApiKey = baiduApiKey == null ? "" : baiduApiKey.trim();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /**
     * 透传 HTTP(S) 图片地址，或将 workspace 内不超过 20 MiB 的本地图片转为 Data URL。
     *
     * @param imageUrl HTTP(S) URL 或 workspace 内路径
     * @return 可交给多模态模型的 URL；校验失败时返回中文错误
     */
    @Tool(
            name = "add_image_to_content_local",
            description = "加载 workspace 内本地图片并转为 base64 Data URL；HTTP(S) 地址直接透传。",
            readOnly = true,
            concurrencySafe = true
    )
    public String addImage(
            @ToolParam(name = "image_url", description = "The URL or path of the image to add") String imageUrl
    ) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return "错误：image_url 不能为空";
        }
        String value = imageUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        try {
            Path candidate = Path.of(value);
            Path resolved = candidate.isAbsolute() ? candidate : workspaceRoot.resolve(candidate);
            Path image = resolved.normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
            assertSafeImagePath(image);
            if (!Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)) {
                return "图片文件不存在: " + imageUrl;
            }
            long size = Files.size(image);
            if (size > MAX_IMAGE_BYTES) {
                return "错误：图片文件过大（" + size + " bytes），最大支持 " + MAX_IMAGE_BYTES + " bytes";
            }
            byte[] raw = Files.readAllBytes(image);
            return "data:" + imageMimeType(image) + ";base64," + Base64.getEncoder().encodeToString(raw);
        } catch (SecurityException exception) {
            return "错误：路径不在允许的工作区范围内: " + imageUrl;
        } catch (IOException | RuntimeException exception) {
            return "图片文件不存在: " + imageUrl;
        }
    }

    /**
     * 调用百度千帆 web_search，返回适合 Agent 阅读的标题、URL 与摘要列表。
     *
     * @param query 搜索问题或关键词
     * @param topK 返回结果数量，缺省 20，范围 0 到 50
     * @param recencyFilter 可选发布时间过滤
     * @param sites 可选站点列表，最多 20 个
     * @return 格式化搜索结果或建设性错误文本
     */
    @Tool(
            name = "search_web",
            description = "使用百度搜索引擎查询公开网页，可按时间范围和站点筛选。",
            readOnly = true
    )
    public String searchWeb(
            @ToolParam(name = "query", description = "不能为空的搜索问题或关键词") String query,
            @ToolParam(name = "top_k", required = false, description = "结果数量，默认 20，范围 0 到 50")
            Integer topK,
            @ToolParam(
                    name = "recency_filter",
                    required = false,
                    description = "week/month/semiyear/year"
            ) String recencyFilter,
            @ToolParam(name = "sites", required = false, description = "可选站点列表，最多 20 个")
            List<String> sites
    ) {
        String validationError = validateSearchInput(query, topK, recencyFilter, sites);
        if (validationError != null) {
            return validationError;
        }
        if (baiduApiKey.isBlank()) {
            return "错误：缺少API认证密钥。\n原因：未提供百度千帆 AppBuilder API Key。\n"
                    + "解决提示：联系管理员设置百度 API Key。\n";
        }
        int resultCount = topK == null ? 20 : topK;
        try {
            String requestBody = searchPayload(query.trim(), resultCount, recencyFilter, sites);
            HttpRequest request = HttpRequest.newBuilder(BAIDU_SEARCH_URI)
                    .timeout(Duration.ofSeconds(30))
                    .header("X-Appbuilder-Authorization", "Bearer " + baiduApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            SearchResponse response = searchTransport.send(request, requestBody);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "错误：HTTP请求错误。\n原因：状态码" + response.statusCode()
                        + "。\n解决提示：请稍后重试，若持续失败可尝试其它工具。\n";
            }
            return formatSearchResponse(query.trim(), response.body());
        } catch (java.net.http.HttpTimeoutException exception) {
            return "错误：请求超时。\n原因：服务器响应时间超过30秒。\n解决提示：请稍后重试。\n";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "错误：搜索请求被中断。\n原因：当前任务正在停止。\n解决提示：稍后重新发起搜索。\n";
        } catch (IOException exception) {
            return "错误：网络请求异常。\n原因：" + exception.getClass().getSimpleName()
                    + "。\n解决提示：请稍后重试，若持续失败可尝试其它工具。\n";
        }
    }

    /**
     * 校验搜索参数并返回可供 Agent 修正的错误文本。
     *
     * @param query 查询文本
     * @param topK 结果数量
     * @param recencyFilter 时间过滤
     * @param sites 站点列表
     * @return 合法时为 null，否则为建设性错误
     */
    private static String validateSearchInput(
            String query,
            Integer topK,
            String recencyFilter,
            List<String> sites
    ) {
        if (query == null || query.isBlank()) {
            return "错误：查询内容不能为空。\n原因：查询参数为空或只包含空白字符。\n"
                    + "解决提示：请提供有效的搜索关键词或问题。";
        }
        int resultCount = topK == null ? 20 : topK;
        if (resultCount < 0 || resultCount > 50) {
            return "错误：top_k参数值无效。\n原因：当前值" + resultCount
                    + "不在0到50范围内。\n解决提示：请调整结果数量。";
        }
        if (recencyFilter != null && !recencyFilter.isBlank() && !RECENCY_FILTERS.contains(recencyFilter)) {
            return "错误：recency_filter参数值无效。\n原因：只支持week/month/semiyear/year。";
        }
        if (sites != null && sites.size() > 20) {
            return "错误：站点列表数量超出限制。\n原因：当前提供了" + sites.size()
                    + "个站点，但最多只支持20个站点。\n解决提示：请减少站点数量。";
        }
        return null;
    }

    /**
     * 构造百度千帆搜索请求 JSON。
     *
     * @param query 查询文本
     * @param topK 结果数量
     * @param recencyFilter 可选时间过滤
     * @param sites 可选站点列表
     * @return 请求 JSON
     */
    private String searchPayload(String query, int topK, String recencyFilter, List<String> sites) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", List.of(Map.of("content", query, "role", "user")));
        payload.put("search_source", "baidu_search_v2");
        payload.put("resource_type_filter", List.of(Map.of("type", "web", "top_k", topK)));
        if (recencyFilter != null && !recencyFilter.isBlank()) {
            payload.put("search_recency_filter", recencyFilter);
        }
        if (sites != null && !sites.isEmpty()) {
            payload.put("search_filter", Map.of("match", Map.of("site", sites)));
        }
        return AgentToolSupport.json(objectMapper, payload);
    }

    /**
     * 解析 API 响应并格式化为源工具兼容的文本列表。
     *
     * @param query 原搜索词
     * @param body 响应 JSON
     * @return 格式化结果或建设性错误
     */
    private String formatSearchResponse(String query, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode errorCode = root.path("code");
            if (!errorCode.isMissingNode() && !errorCode.isNull()
                    && !(errorCode.isInt() && errorCode.asInt() == 0)
                    && !errorCode.asText().isBlank()) {
                return "错误：API返回错误。\n原因：错误码" + errorCode.asText() + "，错误信息："
                        + root.path("message").asText("未知错误") + "。\n解决提示：请检查请求参数或稍后重试。\n";
            }
            JsonNode references = root.path("references");
            if (!references.isArray() || references.isEmpty()) {
                return "错误：未找到相关搜索结果。\n原因：使用关键词'" + query
                        + "'未找到匹配结果。\n解决提示：尝试更通用的搜索词或放宽过滤条件。\n";
            }
            List<String> lines = new ArrayList<>();
            lines.add("找到 " + references.size() + " 条搜索结果");
            lines.add("");
            for (JsonNode reference : references) {
                lines.add("结果" + reference.path("id").asText("?") + ": [ "
                        + reference.path("title").asText("无标题") + " ] ( "
                        + reference.path("url").asText("") + " )\n  内容摘要: "
                        + reference.path("content").asText("") + "\n");
            }
            return String.join("\n", lines);
        } catch (JsonProcessingException exception) {
            return "错误：响应解析错误。\n原因：服务器返回的响应不是有效JSON。\n解决提示：稍后重试。\n";
        }
    }

    /**
     * 将配置的 workspace 解析为真实目录，避免后续相对路径基准漂移。
     *
     * @param root 配置根目录
     * @return 无符号链接歧义的真实路径
     */
    private static Path realWorkspaceRoot(Path root) {
        Objects.requireNonNull(root, "workspaceRoot 不能为空");
        try {
            return root.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalArgumentException("workspaceRoot 必须是已存在目录: " + root, exception);
        }
    }

    /**
     * 拒绝根目录外路径以及从根目录到图片任一层的符号链接。
     *
     * @param image 图片真实路径
     */
    private void assertSafeImagePath(Path image) {
        if (!image.startsWith(workspaceRoot)) {
            throw new SecurityException("图片路径越出 workspace");
        }
        Path current = workspaceRoot;
        for (Path part : workspaceRoot.relativize(image)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new SecurityException("图片路径不得经过符号链接");
            }
        }
    }

    /**
     * 根据扩展名返回受支持图片 MIME，未知类型回退 JPEG 与源实现一致。
     *
     * @param image 图片路径
     * @return MIME 类型
     */
    private static String imageMimeType(Path image) {
        String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    /**
     * 将 JDK HttpClient 适配为可测试的窄搜索传输。
     *
     * @param httpClient JDK HTTP 客户端
     * @return 搜索传输
     */
    private static SearchTransport jdkTransport(HttpClient httpClient) {
        HttpClient client = Objects.requireNonNull(httpClient, "httpClient 不能为空");
        return (request, requestBody) -> {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new SearchResponse(response.statusCode(), response.body());
        };
    }

    /**
     * 隔离 HTTP 客户端实现的最小搜索传输。
     */
    @FunctionalInterface
    interface SearchTransport {

        /**
         * 发送已经完成鉴权和超时配置的搜索请求。
         *
         * @param request HTTP 请求
         * @param requestBody 已序列化的请求 JSON，供测试和诊断观测
         * @return 状态码与响应正文
         * @throws IOException 网络读写失败
         * @throws InterruptedException 当前线程被中断
         */
        SearchResponse send(HttpRequest request, String requestBody) throws IOException, InterruptedException;
    }

    /**
     * 搜索 HTTP 响应的最小不可变表示。
     *
     * @param statusCode HTTP 状态码
     * @param body 响应正文
     */
    record SearchResponse(
            /** HTTP 状态码。 */ int statusCode,
            /** 响应 JSON 正文。 */ String body
    ) {
    }
}
