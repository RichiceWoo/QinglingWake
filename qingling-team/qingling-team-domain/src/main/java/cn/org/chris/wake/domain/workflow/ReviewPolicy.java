package cn.org.chris.wake.domain.workflow;

/**
 * 定义评审是否阻断主工作流以及审计抽查的处理方式。
 */
public enum ReviewPolicy {

    /** 只有高风险、低自评或硬约束不足等强制条件才阻断推进。 */
    REQUIRED_ONLY,

    /** 新手期和抽查只形成建议性审计，不持有主流程执行权。 */
    ADVISORY_AUDIT,

    /** 严格档位使用完整阻断式评审屏障，等待全部指定 Reviewer。 */
    STRICT_BLOCKING
}
