package cn.org.chris.wake.domain.model;

/**
 * 定义团队邮箱严格单向流转的三种状态。
 */
public enum MailStatus {
    /** 尚未被目标角色读取。 */
    UNREAD,
    /** 已被读取，正由目标角色处理。 */
    IN_PROGRESS,
    /** 目标角色已经完成处理。 */
    DONE
}
