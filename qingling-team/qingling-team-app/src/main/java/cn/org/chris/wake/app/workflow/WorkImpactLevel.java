package cn.org.chris.wake.app.workflow;

/**
 * 将工作完成回报的影响程度限制为固定风险等级。
 */
public enum WorkImpactLevel {

    /** 仅影响局部实现且没有关键约束变化。 */
    LOW,

    /** 影响单个模块或普通业务流程。 */
    MEDIUM,

    /** 影响关键契约、跨模块行为或数据一致性。 */
    HIGH,

    /** 影响安全、不可逆数据或核心交付能力。 */
    CRITICAL
}
