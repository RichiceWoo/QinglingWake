package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.MailMessage;

import java.time.Duration;
import java.util.List;

/**
 * 定义团队邮箱文件存储的并发安全端口。
 */
public interface MailboxRepository {

    /**
     * 原子写入一封 unread 邮件；若存在相同且未完成的任务分派，则复用已有邮件。
     *
     * <p>幂等键仅用于 {@code task_assign}，由项目邮箱、发件人、收件人、类型和主题共同确定。
     * 已完成邮件不参与去重，因此后续轮次仍可按业务需要重新分派。</p>
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
