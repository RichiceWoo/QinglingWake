package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.ConversationAuditEntry;

import java.time.Instant;
import java.util.List;

/**
 * 定义独立于 Harness 模型上下文的 JSONL 审计端口。
 */
public interface ConversationAuditRepository {

    /**
     * 幂等创建 session 的 meta 审计行。
     *
     * @param sessionId AgentScope 会话标识
     * @param routingKey 业务路由键
     * @param createdAt 创建时间
     */
    void initialize(String sessionId, String routingKey, Instant createdAt);

    /**
     * 以一次 fsync 追加本轮 user 与 assistant 两条记录。
     *
     * @param sessionId AgentScope 会话标识
     * @param entries 本轮审计记录
     */
    void append(String sessionId, List<ConversationAuditEntry> entries);

    /**
     * 删除测试接口范围内的全部审计数据。
     */
    void clearAll();
}
