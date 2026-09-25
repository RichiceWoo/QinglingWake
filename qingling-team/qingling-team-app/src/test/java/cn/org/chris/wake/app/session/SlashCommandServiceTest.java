package cn.org.chris.wake.app.session;

import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.gateway.ConversationAuditRepository;
import cn.org.chris.wake.domain.gateway.SessionRouteRepository;
import cn.org.chris.wake.domain.model.SessionRoute;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 Slash Command 在 Agent 调用前被稳定消费。
 */
class SlashCommandServiceTest {

    /**
     * 验证 /new、/verbose 和普通文本的分流结果。
     */
    @Test
    void shouldHandleKnownCommandsAndIgnoreNormalText() {
        SessionRouteRepository routes = mock(SessionRouteRepository.class);
        when(routes.find("p2p:u1")).thenReturn(Optional.empty());
        SessionRoutingService routing = new SessionRoutingService(
                routes,
                mock(ConversationAuditRepository.class),
                mock(AgentGateway.class),
                () -> "s-new",
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        );
        SlashCommandService commands = new SlashCommandService(routing);

        assertThat(commands.handle("p2p:u1", "普通需求")).isEmpty();
        assertThat(commands.handle("p2p:u1", "/new"))
                .get()
                .extracting(SlashCommandResult::command, SlashCommandResult::sessionId)
                .containsExactly("new", "s-new");
    }
}
