package cn.org.chris.wake.app.cleanup;

import cn.org.chris.wake.domain.gateway.CleanupExecutor;
import cn.org.chris.wake.domain.model.CleanupPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 CleanupService 的同步启动扫描与异步扫描编排。
 */
class CleanupServiceTest {

    /**
     * 同步与异步扫描都应把固定时间和策略传给执行端口。
     */
    @Test
    void shouldDelegateSynchronousAndAsynchronousSweeps() {
        CleanupExecutor executor = mock(CleanupExecutor.class);
        CleanupPolicy policy = CleanupPolicy.defaults();
        Instant now = Instant.parse("2026-09-26T00:00:00Z");
        Map<String, Integer> result = Map.of("traces", 2);
        when(executor.sweep(policy, now)).thenReturn(result);
        CleanupService service = new CleanupService(executor, policy, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.sweepOnStartup()).isEqualTo(result);
        assertThat(service.sweep().join()).isEqualTo(result);
        verify(executor, org.mockito.Mockito.times(2)).sweep(policy, now);
    }
}
