package cn.org.chris.wake.starter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 Provider 执行独立、最小且无业务副作用的 Function Calling 启动探针。
 */
class RealModelFunctionCallingProbeIT {

    /** DashScope OpenAI 兼容 Function Calling 探针的默认 HTTPS 地址。 */
    private static final URI DEFAULT_DASHSCOPE_PROBE_URL = URI.create(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    );

    /** 独立探针连接建立上限，避免 DNS 或 TCP 故障长期占用验证进程。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 单次真实探针请求上限，与生产默认配置保持一致。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 从环境变量选择显式 Provider 和模型，执行最小工具调用并保存脱敏证据。
     *
     * @throws Exception 请求或证据写入失败
     */
    @Test
    void shouldCompleteMinimalRealFunctionCallingProbe() throws Exception {
        Instant startedAt = Instant.now();
        Map<String, String> environment = System.getenv();
        QinglingTeamProperties.Agent agent = agentConfiguration(environment);
        ObjectMapper objectMapper = new ObjectMapper();
        ModelAvailabilitySelector selector = new ModelAvailabilitySelector(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), objectMapper
        );
        Path evidence = evidencePath();
        try {
            ModelAvailabilitySelector.Selection selection = selector.select(agent);
            writeEvidence(objectMapper, evidence, startedAt, "SUCCESS", selection.attempts(), null);
            assertThat(selection.attempts()).isNotEmpty();
            assertThat(selection.attempts().get(selection.attempts().size() - 1).available()).isTrue();
        } catch (ModelAvailabilitySelector.ModelProbeException failure) {
            writeEvidence(objectMapper, evidence, startedAt, "FAILED", failure.attempts(), failure);
            throw failure;
        }
    }

    /**
     * 根据环境变量构造只启用显式模型预检的最小 Agent 配置。
     *
     * @param environment 当前进程环境变量
     * @return 不启用 workflow 或 AgentScope 的模型探针配置
     */
    private static QinglingTeamProperties.Agent agentConfiguration(Map<String, String> environment) {
        String providerText = environment.getOrDefault("QINGLING_MODEL_PROVIDER", "dashscope")
                .strip().toUpperCase(Locale.ROOT);
        QinglingTeamProperties.ModelProvider provider = QinglingTeamProperties.ModelProvider.valueOf(providerText);
        String model = requireText(environment.get("QINGLING_AGENT_MODEL"), "QINGLING_AGENT_MODEL");
        String dashScopeKey = firstNonBlank(
                environment.get("DASHSCOPE_API_KEY"), environment.get("QWEN_API_KEY")
        );
        URI dashScopeProbeUrl = optionalUri(
                environment.get("QINGLING_MODEL_PROBE_URL"), DEFAULT_DASHSCOPE_PROBE_URL
        );
        String newApiKey = environment.getOrDefault("NEWAPI_API_KEY", "");
        String newApiBaseUrl = environment.getOrDefault("NEWAPI_BASE_URL", "");
        if (provider == QinglingTeamProperties.ModelProvider.DASHSCOPE) {
            requireText(dashScopeKey, "DASHSCOPE_API_KEY/QWEN_API_KEY");
        } else {
            requireText(newApiKey, "NEWAPI_API_KEY");
            requireText(newApiBaseUrl, "NEWAPI_BASE_URL");
        }
        return new QinglingTeamProperties.Agent(
                provider, model, model, false, true, List.of(), REQUEST_TIMEOUT,
                1, 1_024, 32, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(30),
                new QinglingTeamProperties.DashScope(dashScopeKey, dashScopeProbeUrl),
                new QinglingTeamProperties.NewApi(newApiKey, newApiBaseUrl)
        );
    }

    /** 返回第一个非空候选；全部为空时返回空串供统一校验。 */
    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    /** 使用环境变量 URI 或安全默认值，禁止记录其查询参数。 */
    private static URI optionalUri(String value, URI fallback) {
        return value == null || value.isBlank() ? fallback : URI.create(value.strip());
    }

    /** 校验真实探针必需配置，错误消息只包含变量名称。 */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 未配置");
        }
        return value.strip();
    }

    /** 返回独立探针的固定脱敏证据路径。 */
    private static Path evidencePath() throws Exception {
        Path directory = Path.of(System.getProperty("user.dir"), "target", "model-probe-evidence");
        Files.createDirectories(directory);
        return directory.resolve("summary.json");
    }

    /**
     * 保存 Provider、模型、结构化尝试和稳定失败类型，不写入 Key、Header 或响应正文。
     *
     * @param objectMapper JSON 编码器
     * @param evidence 证据文件
     * @param startedAt 探针开始时间
     * @param outcome SUCCESS 或 FAILED
     * @param attempts 脱敏尝试记录
     * @param failure 结构化探针失败，可为空
     * @throws Exception 证据写入失败
     */
    private static void writeEvidence(
            ObjectMapper objectMapper,
            Path evidence,
            Instant startedAt,
            String outcome,
            List<ModelAvailabilitySelector.Attempt> attempts,
            ModelAvailabilitySelector.ModelProbeException failure
    ) throws Exception {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("started_at", startedAt.toString());
        summary.put("completed_at", Instant.now().toString());
        summary.put("outcome", outcome);
        summary.put("provider", System.getenv().getOrDefault("QINGLING_MODEL_PROVIDER", "dashscope"));
        summary.put("model", System.getenv("QINGLING_AGENT_MODEL"));
        summary.put("attempts", attempts);
        if (failure != null && !attempts.isEmpty()) {
            ModelAvailabilitySelector.Attempt last = attempts.get(attempts.size() - 1);
            summary.put("failure_type", last.status());
            summary.put("retryable", last.retryable());
            summary.put("exception_class", last.exceptionClass());
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), summary);
    }
}
