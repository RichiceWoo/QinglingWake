package cn.org.chris.wake.starter.observability;

import cn.org.chris.wake.infra.observability.MetricsRecorder;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Prometheus 指标和安全结构化日志的外部契约。
 */
class ObservabilityContractTest {

    /**
     * 验证根 metrics 输出覆盖 Runner、飞书、HTTP 与错误指标。
     */
    @Test
    void shouldExposePythonCompatibleMetrics() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsRecorder recorder = new MetricsRecorder(registry);
        recorder.recordFeishuEvent("message", "p2p");
        recorder.recordInbound("p2p", true);
        recorder.recordWorkerDelta("p2p", 1);
        recorder.recordQueueDepth("p2p", 2);
        recorder.recordHttpRequest("/api/test/message", "POST", 200, 0.125D);
        recorder.recordFailure("runner", "IllegalStateException");

        String body = new MetricsEndpointController(registry).metrics().getBody();

        assertThat(body).contains(
                "xiaopaw_feishu_events_total",
                "xiaopaw_inbound_messages_total",
                "xiaopaw_runner_workers_active",
                "xiaopaw_runner_queue_size",
                "xiaopaw_http_requests_total",
                "xiaopaw_http_request_duration_seconds_count",
                "xiaopaw_http_request_duration_seconds_bucket",
                "xiaopaw_errors_total"
        );
    }

    /**
     * 验证敏感值被掩码且日志配置启用安全 JSON encoder 和滚动策略。
     */
    @Test
    void shouldMaskSecretsAndConfigureRollingJsonLog() throws IOException {
        String sanitized = SafeJsonLoggingEncoder.sanitize(
                "app_secret=secret-value access_token:token-value password=pwd-value"
        );
        assertThat(sanitized).doesNotContain("secret-value", "token-value", "pwd-value")
                .contains("app_secret=***", "access_token:***", "password=***");

        try (InputStream stream = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(stream).isNotNull();
            String configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(configuration).contains(
                    "SafeJsonLoggingEncoder",
                    "<maxFileSize>50MB</maxFileSize>",
                    "<maxHistory>5</maxHistory>"
            );
        }
    }
}
