package cn.org.chris.wake.domain.workflow;

/**
 * 定义 outbox action 在投影前后的有限状态。
 */
public enum OutboxActionStatus {

    /** 动作尚未确认投影成功，可在重启后安全重放。 */
    PENDING,

    /** 动作已经由目标投影按 action ID 幂等应用。 */
    APPLIED
}
