package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.MailMessage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 定义团队邮箱文件存储的并发安全端口。
 */
public interface MailboxRepository {

    /**
     * 原子写入一封 unread 邮件；若存在相同且未完成的任务分派或澄清答复，则复用已有邮件。
     *
     * <p>{@code task_assign} 优先按项目、Owner 与 workflow stage 去重，旧格式回退到规范主题；
     * {@code clarification_answer} 按答复内容去重。已完成邮件不参与去重。</p>
     *
     * @param message 待保存邮件
     * @return 实际持久化或复用的邮件
     */
    MailMessage appendOrReuseOpenTask(MailMessage message);

    /**
     * 原子读取 unread 邮件并转为 in_progress。
     *
     * @param projectId 所属项目标识
     * @param role 目标角色
     * @return 状态切换后的快照
     */
    List<MailMessage> claimUnread(String projectId, String role);

    /**
     * 按项目、收件角色和消息标识读取当前持久化快照。
     *
     * @param projectId 所属项目标识
     * @param role 邮箱角色
     * @param messageId 邮件标识
     * @return 命中的邮件；不存在时为空
     */
    Optional<MailMessage> findById(String projectId, String role, String messageId);

    /**
     * 查找角色当前全部处于处理中的流水线任务分派。
     *
     * @param projectId 所属项目标识
     * @param role 邮箱角色
     * @return 当前处理中的全部 task_assign
     */
    List<MailMessage> findInProgressTaskAssignments(String projectId, String role);

    /**
     * 原子释放指定角色当前全部处理中的流水线任务，使同阶段恢复可以重新领取原任务。
     *
     * @param projectId 所属项目标识
     * @param role 邮箱角色
     * @return 从 in_progress 恢复为 unread 的 task_assign 数量
     */
    int releaseInProgressTaskAssignments(String projectId, String role);

    /**
     * 将指定 in_progress 邮件标记为 done。
     *
     * @param projectId 所属项目标识
     * @param role 邮箱角色
     * @param messageId 邮件标识
     */
    void markDone(String projectId, String role, String messageId);

    /**
     * 把超过处理时限的邮件恢复为 unread。
     *
     * @param projectId 所属项目标识
     * @param role 邮箱角色
     * @param timeout 处理超时时间
     * @return 恢复数量
     */
    int resetStale(String projectId, String role, Duration timeout);
}
