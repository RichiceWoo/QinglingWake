package cn.org.chris.wake.infra.agentscope;

/**
 * 表示一次可查询的 Docker 项目测试执行及其受限输出。
 *
 * @param executionId 稳定执行标识，用于响应丢失后的结果找回
 * @param status 当前执行状态
 * @param exitCode 进程退出码，尚未结束时为 null
 * @param pytestCollected pytest 收集数量，无法解析时为 0
 * @param pytestPassed pytest 通过数量，无法解析时为 0
 * @param coveragePercent 覆盖率百分比，无法解析时为 -1
 * @param failureType 失败分类
 * @param retryable 是否允许 Java 运行时进行有限恢复
 * @param stdout 受限且脱敏的标准输出
 * @param stderr 受限且脱敏的标准错误
 */
public record ProjectTestExecution(
        String executionId,
        Status status,
        Integer exitCode,
        int pytestCollected,
        int pytestPassed,
        double coveragePercent,
        FailureType failureType,
        boolean retryable,
        String stdout,
        String stderr
) {

    /** 项目测试执行的可观测状态。 */
    public enum Status {
        /** 执行已经登记但尚未取得终态。 */
        RUNNING,
        /** pytest 成功完成。 */
        SUCCEEDED,
        /** pytest 正常执行但业务断言失败。 */
        TEST_FAILED,
        /** 测试进程超过受控时限并已被终止。 */
        TIMED_OUT,
        /** Docker/MCP/命令基础设施无法可靠完成执行。 */
        INFRASTRUCTURE_FAILED,
        /** 执行已被运行时主动取消。 */
        CANCELLED
    }

    /** 项目测试失败的稳定分类。 */
    public enum FailureType {
        /** 执行成功，没有失败。 */
        NONE,
        /** pytest 返回非零业务失败，应该交给 RD 修复代码。 */
        PYTEST_FAILURE,
        /** pytest 或其进程组超过固定时限。 */
        COMMAND_TIMEOUT,
        /** MCP 传输、响应关联或连接发生故障。 */
        MCP_TRANSPORT,
        /** execution ID 对应的 Docker 状态或结果不可找回。 */
        RESULT_UNAVAILABLE,
        /** 运行时主动取消执行。 */
        CANCELLED
    }

    /**
     * 判断执行是否已经进入不可再变化的终态。
     *
     * @return 非 RUNNING 状态返回 true
     */
    public boolean terminal() {
        return status != Status.RUNNING;
    }
}
