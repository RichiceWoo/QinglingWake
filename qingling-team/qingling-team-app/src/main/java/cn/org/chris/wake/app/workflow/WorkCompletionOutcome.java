package cn.org.chris.wake.app.workflow;

/**
 * 定义完成协调器可以返回的有限结果。
 */
public enum WorkCompletionOutcome {

    /** 当前工作完成且 Java 已原子创建下一工作项或 checkpoint。 */
    COMPLETED,

    /** 当前工作保持原阶段并等待阻断式 ReviewRound。 */
    WAITING_REVIEW,

    /** pytest 业务断言失败，原 Owner 继续修复同一执行。 */
    TEST_FAILED,

    /** 基础设施失败可恢复，原执行进入 RECOVERING。 */
    RECOVERING,

    /** 基础设施失败不可自动恢复，原执行进入 BLOCKED。 */
    BLOCKED
}
