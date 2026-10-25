package cn.org.chris.wake.domain.workflow;

/**
 * 控制项目使用旧多投影链路还是 runtime v2 单一权威协调链。
 */
public enum WorkflowCoordinationMode {

    /** 继续使用现有 mailbox、event 和状态机工具链，保证旧项目可回退。 */
    LEGACY,

    /** 使用 WorkExecution aggregate 与 outbox 作为唯一工作流事实源。 */
    COORDINATED
}
