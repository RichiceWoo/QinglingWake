package cn.org.chris.wake.domain.workflow;

/**
 * 定义功能开发流程中由 Java 持有的唯一阶段状态。
 */
public enum WorkflowStage {

    /** 等待需求确认 checkpoint 的初始阶段。 */
    REQUIREMENTS_CHECKPOINT,

    /** PM 正在生成产品设计。 */
    PRODUCT_DESIGN,

    /** RD 正在生成或修订技术方案。 */
    TECH_DESIGN,

    /** RD 正在实现代码并执行研发测试。 */
    CODE_IMPLEMENTATION,

    /** QA 正在生成测试方案。 */
    QA_TEST_DESIGN,

    /** QA 正在执行独立验收测试。 */
    QA_TEST_EXECUTION,

    /** RD 正在修复 QA 发现的缺陷。 */
    DEFECT_FIX,

    /** 等待人类确认交付结果。 */
    DELIVERY_CHECKPOINT,

    /** 项目已完成人类验收。 */
    COMPLETED,

    /** 项目在需求 checkpoint 被人类拒绝后终止。 */
    CANCELLED
}
