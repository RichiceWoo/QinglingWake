package cn.org.chris.wake.domain.workflow;

/**
 * 将 Reviewer 的自由文本判断收敛为有限业务结论。
 */
public enum ReviewVerdict {

    /** Reviewer 接受当前工作产物。 */
    APPROVED,

    /** Reviewer 要求原 Owner 在当前阶段修订。 */
    CHANGES_REQUESTED
}
