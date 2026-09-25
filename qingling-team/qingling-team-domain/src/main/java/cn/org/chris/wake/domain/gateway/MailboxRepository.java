package cn.org.chris.wake.domain.gateway;

import cn.org.chris.wake.domain.model.MailMessage;

import java.time.Duration;
import java.util.List;

/**
 * 定义团队邮箱文件存储的并发安全端口。
 */
public interface MailboxRepository {

    /**
     * 写入一封 unread 邮件。
     *
     * @param message 待保存邮件
     */
    void append(MailMessage message);

    /**
     * 原子读取 unread 邮件并转为 in_progress。
     *
     * @param role 目标角色
     * @return 状态切换后的快照
     */
    List<MailMessage> claimUnread(String role);

    /**
     * 将指定 in_progress 邮件标记为 done。
     *
     * @param role 邮箱角色
     * @param messageId 邮件标识
     */
    void markDone(String role, String messageId);

    /**
     * 把超过处理时限的邮件恢复为 unread。
     *
     * @param role 邮箱角色
     * @param timeout 处理超时时间
     * @return 恢复数量
     */
    int resetStale(String role, Duration timeout);
}
