package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.workflow.OutboxAction;

/**
 * 将 runtime v2 outbox 动作幂等投影到旧事件、邮箱或 Cron 存储。
 */
@FunctionalInterface
public interface WorkflowProjectionGateway {

    /**
     * 按稳定 action ID 应用一次投影；相同动作重复调用不得产生第二个可见副作用。
     *
     * @param action 待投影动作
     */
    void project(OutboxAction action);
}
