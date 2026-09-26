package cn.org.chris.wake.app.runner;

import cn.org.chris.wake.domain.mailbox.MailboxService;
import cn.org.chris.wake.domain.model.InboundMessage;

import java.util.Objects;

/**
 * 将外部会话和团队唤醒 routing key 解析为稳定角色与 AgentScope 用户标识。
 */
public final class RoutingKeyResolver {

    /** 内部角色唤醒路由前缀。 */
    public static final String TEAM_PREFIX = "team:";

    /**
     * 解析单条入站消息；外部消息固定交给 Manager，team:{role} 交给对应角色。
     *
     * @param inbound 标准化入站消息
     * @return Agent 执行目标
     */
    public RoutingTarget resolve(InboundMessage inbound) {
        Objects.requireNonNull(inbound, "inbound 不能为空");
        String routingKey = requireText(inbound.routingKey(), "routingKey");
        if (routingKey.startsWith(TEAM_PREFIX)) {
            String role = routingKey.substring(TEAM_PREFIX.length());
            if (!MailboxService.TEAM_ROLES.contains(role)) {
                throw new IllegalArgumentException("未知团队角色: " + role);
            }
            return new RoutingTarget(role, routingKey, true);
        }
        return new RoutingTarget("manager", requireText(inbound.senderId(), "senderId"), false);
    }

    /**
     * 判断路由是否为不得产生外部回复的团队内部唤醒。
     *
     * @param routingKey 业务路由键
     * @return team: 前缀时为 true
     */
    public boolean isTeamRoute(String routingKey) {
        return routingKey != null && routingKey.startsWith(TEAM_PREFIX);
    }

    /**
     * 校验路由相关文本非空。
     *
     * @param value 待校验文本
     * @param name 参数名称
     * @return 原文本
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    /**
     * 描述一次入站消息应调用的角色和会话用户。
     *
     * @param role manager、pm、rd 或 qa
     * @param userId AgentScope RuntimeContext 用户标识
     * @param teamRoute 是否为内部团队路由
     */
    public record RoutingTarget(
            /** 目标团队角色。 */ String role,
            /** AgentScope 用户标识。 */ String userId,
            /** 为 true 时禁止向外发送任何消息。 */ boolean teamRoute
    ) {
        /**
         * 校验解析结果必需字段。
         */
        public RoutingTarget {
            Objects.requireNonNull(role, "role 不能为空");
            Objects.requireNonNull(userId, "userId 不能为空");
        }
    }
}
