package cn.org.chris.wake.domain.workflow;

/**
 * 定义 runtime v2 aggregate 可以投影的有限副作用类型。
 */
public enum OutboxActionType {

    /** 向旧 mailbox 投影一封带稳定 action ID 的业务消息。 */
    MAILBOX_MESSAGE,

    /** 向旧事件流投影一条可追溯工作流事件。 */
    WORKFLOW_EVENT,

    /** 创建或复用一次性 Cron wake。 */
    CRON_WAKE
}
