package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.SessionRoute;

import java.util.Optional;

/**
 * 持久化业务路由键与 AgentScope sessionId 的映射。
 */
public interface SessionRouteRepository {

    /**
     * 查询路由键当前的活跃会话。
     *
     * @param routingKey 业务路由键
     * @return 已存在的会话映射
     */
    Optional<SessionRoute> find(String routingKey);

    /**
     * 原子保存或替换路由映射。
     *
     * @param route 最新路由快照
     */
    void save(SessionRoute route);

    /**
     * 删除测试或用户显式重置的路由映射。
     *
     * @param routingKey 业务路由键
     */
    void delete(String routingKey);
}
