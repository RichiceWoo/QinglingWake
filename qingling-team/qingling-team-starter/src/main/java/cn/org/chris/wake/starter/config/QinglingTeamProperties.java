package cn.org.chris.wake.starter.config;

import cn.org.chris.wake.domain.workflow.ReviewPolicy;
import cn.org.chris.wake.domain.workflow.WorkflowCoordinationMode;
import cn.org.chris.wake.domain.workflow.WorkflowProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 映射 qingling 前缀下的完整运行时配置，敏感字段只由环境变量注入。
 */
@ConfigurationProperties("qingling")
public record QinglingTeamProperties(
        /** 外部 AgentScope workspace 配置。 */ @DefaultValue Workspace workspace,
        /** 飞书连接与白名单配置。 */ @DefaultValue Feishu feishu,
        /** 主模型与执行限制配置。 */ @DefaultValue Agent agent,
        /** AIO-Sandbox MCP 配置。 */ @DefaultValue Sandbox sandbox,
        /** 可选 pgvector 记忆配置。 */ @DefaultValue Memory memory,
        /** 团队固定角色配置。 */ @DefaultValue Team team,
        /** TestAPI 和调试配置。 */ @DefaultValue Debug debug,
        /** Prometheus 开关配置。 */ @DefaultValue Metrics metrics,
        /** 数据保留和启动清理配置。 */ @DefaultValue Cleanup cleanup,
        /** Cron tick 与 heartbeat 配置。 */ @DefaultValue Cron cron,
        /** 百度搜索工具配置。 */ @DefaultValue Search search,
        /** runtime v2 工作流档位、评审和兼容模式配置。 */ @DefaultValue Workflow workflow,
        /** session、cron、日志与凭据的外部数据根目录。 */ @DefaultValue("./data") Path dataDir
) {

    /** 外部 workspace 标识、名称与路径。 */
    public record Workspace(
            /** workspace 业务标识。 */ @DefaultValue("qingling-team") String id,
            /** workspace 展示名称。 */ @DefaultValue("Qingling Team") String name,
            /** 四角色共享的外部可写根目录。 */ @DefaultValue("./workspace") Path root
    ) { }

    /** 飞书模式、凭据与群聊白名单。 */
    public record Feishu(
            /** 是否启动飞书 WebSocket 和 OpenAPI Sender。 */ @DefaultValue("false") boolean enabled,
            /** 飞书企业自建应用标识，仅允许环境变量注入。 */ @DefaultValue("") String appId,
            /** 飞书企业自建应用密钥，仅允许环境变量注入。 */ @DefaultValue("") String appSecret,
            /** 允许接收消息的群聊 ID；空列表表示不限制。 */ @DefaultValue List<String> allowedChats
    ) { }

    /** Agent 模型供应商、模型名称和执行边界。 */
    public record Agent(
            /** 父 Agent 与子 Agent 共用的模型供应商。 */ @DefaultValue("dashscope") ModelProvider provider,
            /** 关闭免费模型选择时主 Agent 使用的显式模型名称。 */ @DefaultValue("qwen3-max") String model,
            /** 声明式 Sub-Agent 使用的模型名称。 */ @DefaultValue("qwen3-max") String subAgentModel,
            /** 是否在启动时按优先级探测免费模型。 */ @DefaultValue("false") boolean freeModelSelectionEnabled,
            /** 是否在启动时验证显式模型可用且支持 Function Calling。 */
            @DefaultValue("false") boolean validateExplicitModel,
            /** 免费模型候选列表，按智能体适配度从高到低排列。 */
            @DefaultValue({
                    "qwen3.8-27b", "qwen3.8-2.4t-a95b", "deepseek-v4.1-flash", "glm-5.3",
                    "kimi-k3", "deepseek-v4-flash-0731", "qwen3.7-flash-2026-07-15", "qwen3.7-flash"
            }) List<String> modelCandidates,
            /** 单个候选模型探测超时。 */ @DefaultValue("20s") Duration modelProbeTimeout,
            /** 主 Agent 最大迭代次数。 */ @DefaultValue("30") int maxIterations,
            /** 主模型上下文窗口上限。 */ @DefaultValue("30000") int maxInputTokens,
            /** 单次模型响应最大 token，避免复杂工具编排在调用前被截断。 */ @DefaultValue("8192") int maxOutputTokens,
            /** Sub-Agent 最大迭代次数。 */ @DefaultValue("20") int subAgentMaxIterations,
            /** 长工具转入 AgentScope 后台任务前的等待时间，必须短于核心五分钟工具超时。 */
            @DefaultValue("30s") Duration asyncToolOffloadTimeout,
            /** 关闭阶段等待已接纳 dispatch 排空的独立短超时。 */
            @DefaultValue("30s") Duration shutdownDrainTimeout,
            /** 单次 Agent 调用超时。 */ @DefaultValue("300s") Duration timeout,
            /** DashScope Agent 模型和可用性探针配置。 */ @DefaultValue DashScope dashscope,
            /** NewAPI OpenAI 兼容网关配置。 */ @DefaultValue NewApi newApi
    ) { }

    /** Agent 模型供应商；父 Agent 与所有子 Agent 在首版中共用同一取值。 */
    public enum ModelProvider {
        /** 使用阿里云百炼 DashScope 原生 AgentScope 适配器。 */
        DASHSCOPE,

        /** 使用 NewAPI 提供的 OpenAI 兼容 Chat Completions 接口。 */
        NEWAPI
    }

    /** DashScope Agent 模型凭据和 Function Calling 探针地址。 */
    public record DashScope(
            /** DashScope API Key，优先 DASHSCOPE_API_KEY、回退 QWEN_API_KEY。 */ @DefaultValue("") String apiKey,
            /** DashScope OpenAI 兼容的模型可用性探测端点。 */
            @DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions") URI modelProbeUrl
    ) { }

    /** NewAPI OpenAI 兼容网关凭据和协议根地址。 */
    public record NewApi(
            /** NewAPI 独立 SK，只允许通过 NEWAPI_API_KEY 注入。 */ @DefaultValue("") String apiKey,
            /** NewAPI 协议根地址；未启用 NewAPI 时允许为空。 */ @DefaultValue("") String baseUrl
    ) {
        /**
         * 将 NewAPI 地址收敛为不含凭据和查询参数的 HTTPS `/v1` 协议根地址。
         *
         * @return 规范化后的 NewAPI `/v1` 根地址
         * @throws IllegalStateException 地址为空、结构非法或不是受支持的 HTTPS 根地址
         */
        public URI normalizedBaseUrl() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("NEWAPI_BASE_URL 未配置");
            }
            try {
                URI candidate = URI.create(baseUrl.strip());
                if (!candidate.isAbsolute()
                        || !"https".equalsIgnoreCase(candidate.getScheme())
                        || candidate.getHost() == null
                        || candidate.getHost().isBlank()
                        || candidate.getUserInfo() != null
                        || candidate.getQuery() != null
                        || candidate.getFragment() != null) {
                    throw new IllegalStateException("NEWAPI_BASE_URL 必须是无凭据、无查询参数的 HTTPS 地址");
                }
                String path = candidate.getPath() == null ? "" : candidate.getPath();
                while (path.endsWith("/") && path.length() > 1) {
                    path = path.substring(0, path.length() - 1);
                }
                if (!path.isEmpty() && !"/".equals(path) && !"/v1".equals(path)) {
                    throw new IllegalStateException("NEWAPI_BASE_URL 路径必须为空或 /v1");
                }
                return new URI(
                        "https", null, candidate.getHost(), candidate.getPort(), "/v1", null, null
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("NEWAPI_BASE_URL 格式非法");
            } catch (java.net.URISyntaxException exception) {
                throw new IllegalStateException("NEWAPI_BASE_URL 无法规范化");
            }
        }

        /**
         * 生成唯一的 OpenAI Chat Completions 地址，避免 URI.resolve 覆盖 `/v1` 路径。
         *
         * @return 规范化后的 `/v1/chat/completions` 地址
         */
        public URI chatCompletionsUrl() {
            return URI.create(normalizedBaseUrl().toString() + "/chat/completions");
        }
    }

    /** AIO-Sandbox MCP 连接参数。 */
    public record Sandbox(
            /** 是否为四角色启用 AIO-Sandbox MCP。 */ @DefaultValue("true") boolean enabled,
            /** streamable HTTP MCP 地址。 */ @DefaultValue("http://localhost:8029/mcp") String url,
            /** MCP 中映射宿主 workspace 的路径。 */ @DefaultValue("/workspace") String workspaceDir,
            /** 单次 MCP 工具调用超时。 */ @DefaultValue("120s") Duration timeout,
            /** MCP 初始化握手超时。 */ @DefaultValue("30s") Duration initializationTimeout,
            /** 外部调用允许的最大重试次数。 */ @DefaultValue("2") int maxRetries,
            /** 允许注册到 AgentScope 的 MCP 工具名。 */ @DefaultValue List<String> enabledTools,
            /** 不含秘密时可选的 MCP 请求 Header。 */ @DefaultValue Map<String, String> headers
    ) { }

    /** 记忆目录与可选 pgvector 连接。 */
    public record Memory(
            /** 兼容源项目的上下文数据目录。 */ @DefaultValue("./data/ctx") Path contextDir,
            /** PostgreSQL JDBC DSN；空值关闭 pgvector。 */ @DefaultValue("") String dbDsn,
            /** Memory 摘要与向量生成专用的 DashScope 配置。 */ @DefaultValue MemoryDashScope dashscope
    ) { }

    /** Memory 专用 DashScope 凭据、协议地址和模型契约。 */
    public record MemoryDashScope(
            /** Memory 专用 DashScope API Key，不继承 Agent Provider 凭据。 */ @DefaultValue("") String apiKey,
            /** Memory 使用的 DashScope OpenAI 兼容协议根地址。 */
            @DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1/") URI baseUrl,
            /** 对话摘要和标签提取模型。 */ @DefaultValue("qwen3.6-max-preview") String extractModel,
            /** 固定生成 1024 维向量的 Embedding 模型。 */ @DefaultValue("text-embedding-v3") String embeddingModel
    ) { }

    /** 固定团队角色。 */
    public record Team(
            /** 必须按 manager、pm、rd、qa 顺序提供的角色。 */
            @DefaultValue({"manager", "pm", "rd", "qa"}) List<String> roles
    ) { }

    /** 调试接口开关与等待时间。 */
    public record Debug(
            /** 是否注册 TestAPI Controller。 */ @DefaultValue("false") boolean testApiEnabled,
            /** TestAPI 等待最终回复的最长时间。 */ @DefaultValue("300s") Duration replyTimeout
    ) { }

    /** 指标端点配置。 */
    public record Metrics(
            /** 是否注册 Python 兼容的根路径 metrics 端点。 */ @DefaultValue("true") boolean enabled
    ) { }

    /** 清理策略配置。 */
    public record Cleanup(
            /** 是否启用文件保留期清理。 */ @DefaultValue("true") boolean enabled,
            /** 是否在完成凭据初始化后同步清理一次。 */ @DefaultValue("true") boolean runOnStartup,
            /** session 审计 JSONL 保留天数。 */ @DefaultValue("365") int sessionRetentionDays,
            /** 相对 data root 的 glob 与保留天数。 */ @DefaultValue Map<String, Integer> rules
    ) { }

    /** Cron 后台轮询与 heartbeat 配置。 */
    public record Cron(
            /** tasks.json 热重载和到期检查间隔。 */ @DefaultValue("1s") Duration tickInterval,
            /** 四角色 heartbeat 周期。 */ @DefaultValue("30s") Duration heartbeatInterval,
            /** manager、pm、rd、qa 首次 heartbeat 错峰秒数。 */
            @DefaultValue({"0", "7", "14", "21"}) List<Long> heartbeatStaggerSeconds
    ) { }

    /** 百度千帆网页搜索配置。 */
    public record Search(
            /** 百度 AppBuilder API Key，空值让搜索工具返回建设性错误。 */ @DefaultValue("") String baiduApiKey
    ) { }

    /** runtime v2 工作流档位、评审策略和兼容切换配置。 */
    public record Workflow(
            /** 使用旧链路或 WorkExecution 单一权威协调链。 */
            @DefaultValue("legacy") WorkflowCoordinationMode coordinationMode,
            /** 新项目创建时固定且运行中不可切换的复杂度档位。 */
            @DefaultValue("standard") WorkflowProfile defaultProfile,
            /** 控制 mandatory 与 advisory 评审是否阻断主流程。 */
            @DefaultValue("required-only") ReviewPolicy reviewPolicy,
            /** 是否记录新手期和抽查命中的建议性审计证据。 */
            @DefaultValue("true") boolean advisoryAuditEnabled,
            /** 阻断式 ReviewRound 从创建到明确终态的最长业务时间。 */
            @DefaultValue("10m") Duration reviewTimeout
    ) { }
}
