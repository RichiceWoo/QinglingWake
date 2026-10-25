package cn.org.chris.wake.app.workflow;

/**
 * 将模型的完成语义限制为 Java 可以验证的有限结论。
 */
public enum WorkCompletionDecision {

    /** 当前 Owner 认为工作产物满足本阶段要求。 */
    ACCEPTED,

    /** QA 真实测试发现缺陷，需要进入 RD 修复阶段。 */
    DEFECT_FOUND
}
