package cn.org.chris.wake.domain.workflow;

/**
 * 定义模型可以提交给 Java 状态机的有限业务结论。
 */
public enum WorkflowSignal {

    /** 人类已批准需求 checkpoint。 */
    CHECKPOINT_APPROVED,

    /** 人类已拒绝需求 checkpoint，项目终止。 */
    CHECKPOINT_REJECTED,

    /** Manager 接受当前阶段产物。 */
    STAGE_ACCEPTED,

    /** Manager 要求当前阶段 Owner 修订产物。 */
    STAGE_REVISION_REQUIRED,

    /** Manager 已答复阻塞澄清，原 Owner 应在同一阶段继续原任务。 */
    CLARIFICATION_ANSWERED,

    /** 首次工具基础设施故障，保持阶段并恢复原 Owner。 */
    TOOL_EXECUTION_FAILED,

    /** 同类工具故障恢复耗尽，保持业务阶段但阻断后续推进。 */
    TOOL_RETRY_EXHAUSTED,

    /** QA 结论为存在缺陷，需要进入研发修复。 */
    QA_DEFECT_FOUND,

    /** 人类已批准最终交付。 */
    DELIVERY_APPROVED,

    /** 人类拒绝最终交付，需要进入研发修复与 QA 回归。 */
    DELIVERY_REJECTED
}
