package cn.org.chris.wake.domain.workflow;

/**
 * 定义一次评审从创建到确定结论的有限状态。
 */
public enum ReviewRoundStatus {

    /** 评审轮次已创建，尚未收到 Reviewer 处理信号。 */
    PENDING,

    /** 至少一个 Reviewer 已开始或提交结论，但屏障尚未收敛。 */
    IN_PROGRESS,

    /** 全部预期 Reviewer 已明确批准。 */
    APPROVED,

    /** 至少一个 Reviewer 明确要求修订。 */
    CHANGES_REQUESTED,

    /** 截止时间已到且评审屏障未收敛。 */
    EXPIRED,

    /** 评审因所属工作被取消而终止。 */
    CANCELLED;

    /**
     * 判断评审是否已经形成不可逆终态。
     *
     * @return 已批准、要求修订、超时或取消时返回 true
     */
    public boolean terminal() {
        return this == APPROVED
                || this == CHANGES_REQUESTED
                || this == EXPIRED
                || this == CANCELLED;
    }
}
