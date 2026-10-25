package cn.org.chris.wake.domain.workflow;

/**
 * 区分会阻断主流程的必要评审与只留审计证据的建议评审。
 */
public enum ReviewRequirement {

    /** 未形成明确结论前必须保持当前业务阶段。 */
    MANDATORY,

    /** 允许主流程继续，仅异步保留质量审计结论。 */
    ADVISORY
}
