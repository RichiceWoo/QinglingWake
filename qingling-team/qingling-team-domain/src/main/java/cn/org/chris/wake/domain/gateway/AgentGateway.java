package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.AgentReply;
import cn.org.chris.wake.domain.model.AgentRequest;

import java.util.concurrent.CompletableFuture;

/**
 * 隔离应用编排与 AgentScope Reactor API 的领域端口。
 */
public interface AgentGateway {

    /**
     * 异步执行一轮角色 Agent；请求正文不得包含审计 JSONL 拼接历史。
     *
     * @param request 单轮 Agent 请求
     * @return 可组合的异步回复
     */
    CompletableFuture<AgentReply> execute(AgentRequest request);

    /**
     * 清除指定 AgentScope 会话的框架状态。
     *
     * @param role 目标角色
     * @param userId 用户标识
     * @param sessionId 会话标识
     */
    void clearSession(String role, String userId, String sessionId);
}
