package cn.org.chris.wake.domain.model;

import java.util.Objects;

/**
 * 表示 Agent 执行基础设施已经无法在当前父回合内继续，供应用层确定性记录运行时 incident。
 */
public final class AgentExecutionException extends IllegalStateException {

    /** 不依赖具体 Agent 框架的失败分类。 */
    private final FailureType failureType;

    /**
     * 使用脱敏原因创建 Agent 执行异常。
     *
     * @param failureType 稳定失败分类
     * @param message 不含提示词、工具输出或凭据的有界摘要
     */
    public AgentExecutionException(FailureType failureType, String message) {
        super(message);
        this.failureType = Objects.requireNonNull(failureType, "failureType 不能为空");
    }

    /**
     * 返回可持久化到 incident 的稳定失败分类。
     *
     * @return 与具体 Agent 框架版本无关的失败分类
     */
    public FailureType failureType() {
        return failureType;
    }

    /** 表示父回合可确定识别的 Agent 执行故障类型。 */
    public enum FailureType {
        /** 最新子任务执行被取消，当前父回合不能继续信任。 */
        CHILD_CANCELLED,

        /** 最新子任务执行以失败终态结束。 */
        CHILD_FAILED,

        /** 最新子任务保持 RUNNING 但真实事件与会话日志超过租约窗口没有进展。 */
        CHILD_STALLED,

        /** 没有运行中子任务时父 Agent 自身会话日志超过租约窗口没有进展。 */
        PARENT_STALLED
    }
}
