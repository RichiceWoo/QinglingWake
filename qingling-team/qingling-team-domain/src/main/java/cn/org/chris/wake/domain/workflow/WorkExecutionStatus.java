package cn.org.chris.wake.domain.workflow;

import java.util.EnumSet;
import java.util.Set;

/**
 * 定义一个工作执行从待领取到确定终态的有限状态集合。
 */
public enum WorkExecutionStatus {

    /** 工作项已创建，等待唯一 Owner 领取。 */
    PENDING,

    /** 工作项已由 assignment 领取，但尚未启动角色 dispatch。 */
    CLAIMED,

    /** Owner 正在执行当前阶段的业务工作。 */
    RUNNING,

    /** 业务产物已完成，正在等待阻断式评审结论。 */
    WAITING_REVIEW,

    /** 基础设施故障已被识别，等待同一执行恢复。 */
    RECOVERING,

    /** 当前工作执行已经成功完成。 */
    SUCCEEDED,

    /** 当前工作执行因不可恢复错误失败。 */
    FAILED,

    /** 当前工作执行被显式取消。 */
    CANCELLED,

    /** 当前工作执行因恢复预算耗尽被阻断。 */
    BLOCKED;

    /** 可以从待领取状态进入的后继状态。 */
    private static final Set<WorkExecutionStatus> PENDING_TARGETS =
            EnumSet.of(CLAIMED, CANCELLED, BLOCKED);

    /** 可以从已领取状态进入的后继状态。 */
    private static final Set<WorkExecutionStatus> CLAIMED_TARGETS =
            EnumSet.of(RUNNING, RECOVERING, FAILED, CANCELLED, BLOCKED);

    /** 可以从运行中状态进入的后继状态。 */
    private static final Set<WorkExecutionStatus> RUNNING_TARGETS =
            EnumSet.of(WAITING_REVIEW, RECOVERING, SUCCEEDED, FAILED, CANCELLED, BLOCKED);

    /** 可以从等待评审状态进入的后继状态。 */
    private static final Set<WorkExecutionStatus> REVIEW_TARGETS =
            EnumSet.of(PENDING, SUCCEEDED, FAILED, CANCELLED, BLOCKED);

    /** 可以从恢复中状态进入的后继状态。 */
    private static final Set<WorkExecutionStatus> RECOVERING_TARGETS =
            EnumSet.of(PENDING, FAILED, CANCELLED, BLOCKED);

    /**
     * 判断当前状态是否允许迁移到给定目标状态。
     *
     * @param target 目标状态
     * @return 仅当转换位于固定状态表中时返回 true
     */
    public boolean canTransitionTo(WorkExecutionStatus target) {
        if (target == null || target == this || terminal()) {
            return false;
        }
        return switch (this) {
            case PENDING -> PENDING_TARGETS.contains(target);
            case CLAIMED -> CLAIMED_TARGETS.contains(target);
            case RUNNING -> RUNNING_TARGETS.contains(target);
            case WAITING_REVIEW -> REVIEW_TARGETS.contains(target);
            case RECOVERING -> RECOVERING_TARGETS.contains(target);
            case SUCCEEDED, FAILED, CANCELLED, BLOCKED -> false;
        };
    }

    /**
     * 判断当前状态是否已经不可再恢复为运行态。
     *
     * @return 成功、失败、取消或阻断时返回 true
     */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == BLOCKED;
    }
}
