package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.AgentExecutionException;

/**
 * 隔离 Runner 与运行时 incident 持久化、恢复调度和阻断查询的领域端口。
 */
public interface RuntimeIncidentGateway {

    /**
     * 返回不记录 incident 且永不报告 BLOCKED 的空实现，供不涉及团队运行时的测试装配使用。
     *
     * @return 无副作用运行时 incident 端口
     */
    static RuntimeIncidentGateway noop() {
        return new RuntimeIncidentGateway() {
            /** 忽略无模型测试中的 Agent 执行故障。 */
            @Override
            public void reportAgentExecutionFailure(
                    String projectId,
                    String role,
                    String occurrenceId,
                    AgentExecutionException.FailureType failureType
            ) {
                // 空实现仅用于无需验证 incident 的装配测试。
            }

            /** 忽略无项目 heartbeat 中的 Agent 执行故障。 */
            @Override
            public void reportUnscopedAgentExecutionFailure(
                    String role,
                    String occurrenceId,
                    AgentExecutionException.FailureType failureType
            ) {
                // 空实现仅用于无需验证 incident 的装配测试。
            }

            /** 空实现没有持久化事件，因此项目始终未阻断。 */
            @Override
            public boolean isRuntimeBlocked(String projectId) {
                return false;
            }
        };
    }

    /**
     * 上报一次已脱敏的 Agent 执行故障；实现必须按 occurrence 保持幂等并限制自动恢复次数。
     *
     * @param projectId 当前团队项目标识
     * @param role 当前 workflow Owner
     * @param occurrenceId 当前入站 dispatch 的稳定标识
     * @param failureType Agent 执行失败分类
     */
    void reportAgentExecutionFailure(
            String projectId,
            String role,
            String occurrenceId,
            AgentExecutionException.FailureType failureType
    );

    /**
     * 上报不携带项目标识的通用 heartbeat 故障；实现只能在项目归属唯一时落盘。
     *
     * @param role 当前团队角色
     * @param occurrenceId 当前入站 dispatch 的稳定标识
     * @param failureType Agent 执行失败分类
     */
    void reportUnscopedAgentExecutionFailure(
            String role,
            String occurrenceId,
            AgentExecutionException.FailureType failureType
    );

    /**
     * 判断项目是否已经因运行时恢复耗尽进入 BLOCKED。
     *
     * @param projectId 项目标识
     * @return 已阻断时为 true
     */
    boolean isRuntimeBlocked(String projectId);
}
