package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.SessionRoute;

import java.util.List;
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
     * 列出所有路由的当前活跃会话，供 TestAPI 成组清理使用。
     *
     * @return 当前活跃路由快照
     */
    List<SessionRoute> findAll();

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

    /**
     * 清空全部测试路由映射，不负责删除独立审计文件。
     */
    void clearAll();
}
