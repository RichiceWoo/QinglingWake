package cn.org.chris.wake.domain.workflow;

/**
 * 定义应用层可以信任的 Test Runner 有限终态。
 */
public enum ProjectTestEvidenceStatus {

    /** 测试仍在执行，不能作为完成证据。 */
    RUNNING,

    /** pytest 已成功完成。 */
    SUCCEEDED,

    /** pytest 正常结束但业务断言失败。 */
    TEST_FAILED,

    /** 测试进程超过受控时限。 */
    TIMED_OUT,

    /** MCP、传输或执行基础设施失败。 */
    INFRASTRUCTURE_FAILED,

    /** 测试被 Java 运行时主动取消。 */
    CANCELLED;

    /**
     * 判断测试是否已经进入不可再变化的终态。
     *
     * @return 非 RUNNING 状态返回 true
     */
    public boolean terminal() {
        return this != RUNNING;
    }
}
