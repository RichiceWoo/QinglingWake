package cn.org.chris.wake.app.session;

import cn.org.chris.wake.domain.gateway.AgentGateway;
import cn.org.chris.wake.domain.gateway.ConversationAuditRepository;
import cn.org.chris.wake.domain.gateway.SessionRouteRepository;
import cn.org.chris.wake.domain.model.ConversationAuditEntry;
import cn.org.chris.wake.domain.model.SessionRoute;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 管理 routing key 到 AgentScope sessionId 的映射，不拥有模型对话历史。
 */
public final class SessionRoutingService {

    /** 路由映射持久化端口。 */
    private final SessionRouteRepository routeRepository;

    /** 只写审计端口。 */
    private final ConversationAuditRepository auditRepository;

    /** 用于 TestAPI 清理 AgentScope state 的端口。 */
    private final AgentGateway agentGateway;

    /** 生成可替换的 sessionId，便于确定性测试。 */
    private final Supplier<String> sessionIdSupplier;

    /** 提供可替换的 UTC 时间。 */
    private final Clock clock;

    /**
     * 创建生产路由服务，sessionId 使用 s- 加十二位 UUID 十六进制字符。
     *
     * @param routeRepository 路由存储
     * @param auditRepository 审计存储
     * @param agentGateway AgentScope 领域端口
     */
    public SessionRoutingService(
            SessionRouteRepository routeRepository,
            ConversationAuditRepository auditRepository,
            AgentGateway agentGateway
    ) {
        this(routeRepository, auditRepository, agentGateway, SessionRoutingService::randomSessionId, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟和标识生成器的路由服务。
     *
     * @param routeRepository 路由存储
     * @param auditRepository 审计存储
     * @param agentGateway AgentScope 领域端口
     * @param sessionIdSupplier 会话标识生成器
     * @param clock UTC 时钟
     */
    public SessionRoutingService(
            SessionRouteRepository routeRepository,
            ConversationAuditRepository auditRepository,
            AgentGateway agentGateway,
            Supplier<String> sessionIdSupplier,
            Clock clock
    ) {
        this.routeRepository = Objects.requireNonNull(routeRepository, "routeRepository 不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository 不能为空");
        this.agentGateway = Objects.requireNonNull(agentGateway, "agentGateway 不能为空");
        this.sessionIdSupplier = Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 返回现有映射，首次访问时创建并初始化只写审计文件。
     *
     * @param routingKey 业务路由键
     * @return 当前活跃会话
     */
    public synchronized SessionRoute getOrCreate(String routingKey) {
        return routeRepository.find(routingKey).orElseGet(() -> createAndSave(routingKey, false));
    }

    /**
     * 为路由创建全新 sessionId 并切换 active mapping，旧模型状态不被拼入新请求。
     *
     * @param routingKey 业务路由键
     * @return 新活跃会话
     */
    public synchronized SessionRoute createNew(String routingKey) {
        boolean verbose = routeRepository.find(routingKey).map(SessionRoute::verbose).orElse(false);
        return createAndSave(routingKey, verbose);
    }

    /**
     * 修改当前活跃路由的 verbose 标志。
     *
     * @param routingKey 业务路由键
     * @param verbose 新开关值
     * @return 更新后的路由
     */
    public synchronized SessionRoute updateVerbose(String routingKey, boolean verbose) {
        SessionRoute current = getOrCreate(routingKey);
        SessionRoute updated = new SessionRoute(
                current.routingKey(), current.activeSessionId(), current.createdAt(), verbose, current.messageCount()
        );
        routeRepository.save(updated);
        return updated;
    }

    /**
     * 原子追加一轮 user/assistant 审计，并把当前会话消息计数增加二。
     *
     * @param routingKey 业务路由键
     * @param sessionId 本轮实际使用的 AgentScope sessionId
     * @param userContent 传给 Agent 的本轮正文
     * @param assistantContent Agent 最终回复
     * @param sourceMessageId 来源消息标识
     */
    public synchronized void recordTurn(
            String routingKey,
            String sessionId,
            String userContent,
            String assistantContent,
            String sourceMessageId
    ) {
        SessionRoute current = getOrCreate(routingKey);
        if (!current.activeSessionId().equals(sessionId)) {
            throw new IllegalStateException("审计 sessionId 已不是当前活跃会话: " + sessionId);
        }
        long timestampMs = clock.millis();
        auditRepository.append(sessionId, List.of(
                new ConversationAuditEntry("user", userContent, timestampMs, sourceMessageId),
                new ConversationAuditEntry("assistant", assistantContent, timestampMs, null)
        ));
        SessionRoute updated = new SessionRoute(
                current.routingKey(),
                current.activeSessionId(),
                current.createdAt(),
                current.verbose(),
                Math.addExact(current.messageCount(), 2)
        );
        routeRepository.save(updated);
    }

    /**
     * 清理指定测试路由及其当前 AgentScope state。
     *
     * @param routingKey 业务路由键
     * @param role Agent 角色
     * @param userId AgentScope 用户标识
     */
    public synchronized void clear(String routingKey, String role, String userId) {
        routeRepository.find(routingKey).ifPresent(route ->
                agentGateway.clearSession(role, userId, route.activeSessionId())
        );
        routeRepository.delete(routingKey);
    }

    /**
     * 新建、持久化并初始化一条路由。
     *
     * @param routingKey 业务路由键
     * @param verbose 初始详细输出开关
     * @return 新路由
     */
    private SessionRoute createAndSave(String routingKey, boolean verbose) {
        Instant now = clock.instant();
        SessionRoute route = new SessionRoute(routingKey, sessionIdSupplier.get(), now, verbose, 0);
        routeRepository.save(route);
        auditRepository.initialize(route.activeSessionId(), routingKey, now);
        return route;
    }

    /**
     * 生成与 Python 格式兼容的随机 sessionId。
     *
     * @return s-xxxxxxxxxxxx 格式标识
     */
    private static String randomSessionId() {
        return "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
