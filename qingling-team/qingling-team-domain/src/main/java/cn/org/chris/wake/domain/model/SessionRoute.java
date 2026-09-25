package cn.org.chris.wake.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 表示业务 routing key 到 AgentScope 活跃 session 的持久化映射。
 *
 * @param routingKey 业务路由键
 * @param activeSessionId AgentScope 当前会话标识
 * @param createdAt 会话创建时间
 * @param verbose 是否输出详细回复
 * @param messageCount 已审计消息数量
 */
public record SessionRoute(
        /** 业务路由键。 */ String routingKey,
        /** 当前 AgentScope sessionId。 */ String activeSessionId,
        /** 活跃会话创建时间。 */ Instant createdAt,
        /** 当前路由的详细输出开关。 */ boolean verbose,
        /** 当前会话的审计消息数量。 */ int messageCount
) {
    /**
     * 校验路由快照中的必需标识和计数边界。
     */
    public SessionRoute {
        Objects.requireNonNull(routingKey, "routingKey 不能为空");
        Objects.requireNonNull(activeSessionId, "activeSessionId 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount 不能小于零");
        }
    }
}
