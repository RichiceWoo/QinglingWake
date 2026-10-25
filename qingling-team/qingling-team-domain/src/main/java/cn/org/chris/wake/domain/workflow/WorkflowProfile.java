package cn.org.chris.wake.domain.workflow;

/**
 * 定义项目创建时固定的工作流复杂度档位，运行中不得由模型自行切换。
 */
public enum WorkflowProfile {

    /** 简单任务只保留用户确认、研发实现、QA 验收和交付。 */
    SIMPLE,

    /** 标准任务保留四角色产物契约，但不让可选评审或子 Agent 阻断主路径。 */
    STANDARD,

    /** 严格任务启用完整阶段、阻断式双评审和按需子 Agent。 */
    STRICT
}
