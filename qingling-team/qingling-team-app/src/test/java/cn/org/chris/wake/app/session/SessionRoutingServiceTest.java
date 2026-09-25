package cn.org.chris.wake.app.session;

import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.gateway.ConversationAuditRepository;
import cn.org.chris.wake.domain.gateway.SessionRouteRepository;
import cn.org.chris.wake.domain.model.SessionRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证选择 2A 后 routing mapping 与 AgentScope state 的职责边界。
 */
class SessionRoutingServiceTest {

    /** 路由仓库测试替身。 */
    private SessionRouteRepository routeRepository;

    /** 审计仓库测试替身。 */
    private ConversationAuditRepository auditRepository;

    /** AgentScope 端口测试替身。 */
    private AgentGateway agentGateway;

    /** 待测服务。 */
    private SessionRoutingService service;

    /**
     * 为每个测试建立固定时间和顺序 sessionId。
     */
    @BeforeEach
    void setUp() {
        routeRepository = mock(SessionRouteRepository.class);
        auditRepository = mock(ConversationAuditRepository.class);
        agentGateway = mock(AgentGateway.class);
        Deque<String> ids = new ArrayDeque<>();
        ids.add("s-first");
        ids.add("s-second");
        service = new SessionRoutingService(
                routeRepository,
                auditRepository,
                agentGateway,
                ids::removeFirst,
                Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    /**
     * 首次路由创建 AgentScope sessionId，并只初始化审计而不加载历史。
     */
    @Test
    void shouldCreateAndReuseActiveSession() {
        when(routeRepository.find("p2p:u1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new SessionRoute(
                        "p2p:u1", "s-first", Instant.parse("2026-09-25T00:00:00Z"), false, 0
                )));

        SessionRoute first = service.getOrCreate("p2p:u1");
        SessionRoute second = service.getOrCreate("p2p:u1");

        assertThat(first.activeSessionId()).isEqualTo("s-first");
        assertThat(second.activeSessionId()).isEqualTo("s-first");
        verify(routeRepository).save(first);
        verify(auditRepository).initialize("s-first", "p2p:u1", Instant.parse("2026-09-25T00:00:00Z"));
    }

    /**
     * /new 切换到新 id，但不把旧审计内容交给服务或 AgentGateway。
     */
    @Test
    void shouldSwitchSessionOnNewCommand() {
        when(routeRepository.find("p2p:u1")).thenReturn(Optional.of(new SessionRoute(
                "p2p:u1", "s-old", Instant.EPOCH, true, 6
        )));

        SessionRoute route = service.createNew("p2p:u1");

        assertThat(route.activeSessionId()).isEqualTo("s-first");
        assertThat(route.verbose()).isTrue();
        assertThat(route.messageCount()).isZero();
        verify(routeRepository).save(route);
    }

    /**
     * 清理测试会话时同时移除 mapping 和当前 Harness state。
     */
    @Test
    void shouldClearRouteAndAgentScopeStateTogether() {
        when(routeRepository.find("p2p:u1")).thenReturn(Optional.of(new SessionRoute(
                "p2p:u1", "s-old", Instant.EPOCH, false, 0
        )));

        service.clear("p2p:u1", "manager", "u1");

        verify(agentGateway).clearSession("manager", "u1", "s-old");
        verify(routeRepository).delete("p2p:u1");
    }
}
