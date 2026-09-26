package cn.org.chris.wake.infra.memory;

/**
 * 定义 Agent 完成一轮回复后触发的非阻塞记忆索引入口。
 */
@FunctionalInterface
public interface TurnMemoryIndexer {

    /**
     * 调度单轮索引；实现不得阻塞 Agent 主回复。
     *
     * @param sessionId AgentScope 会话标识
     * @param routingKey 外部或团队路由键
     * @param userMessage 本轮用户输入
     * @param assistantReply 本轮助手回复
     * @param turnTimestampMs 本轮开始时间，Unix 毫秒
     */
    void schedule(
            String sessionId,
            String routingKey,
            String userMessage,
            String assistantReply,
            long turnTimestampMs
    );
}
