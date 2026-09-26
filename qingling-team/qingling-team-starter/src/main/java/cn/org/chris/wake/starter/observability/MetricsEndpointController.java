package cn.org.chris.wake.starter.observability;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 在 Python 兼容的根路径暴露 Prometheus 文本指标。
 */
@RestController
@ConditionalOnProperty(prefix = "qingling.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class MetricsEndpointController {

    /** Prometheus 专用注册表。 */
    private final PrometheusMeterRegistry registry;

    /**
     * 创建 Metrics 端点。
     *
     * @param registry Prometheus 注册表
     */
    public MetricsEndpointController(PrometheusMeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
    }

    /**
     * 导出 Prometheus 文本。
     *
     * @return 当前进程全部指标
     */
    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok(registry.scrape());
    }
}
