---
name: mailbox_ops
description: "QA 团队邮箱操作。通过 AgentScope 并发安全工具读取收件箱、发送邮件或标记完成；禁止直接编辑 mailbox JSON。"
type: task
---

# mailbox_ops — QA 邮箱操作

## 执行工具

- `read_inbox(project_id)`：原子领取 QA 的 unread 邮件并转为 in_progress。
- `send_mail(to, type, subject, content, project_id)`：向团队角色发信并自动注册 wake。
- `mark_done(project_id, msg_id)`：将已处理的 in_progress 邮件转为 done。

## 强约束

1. 只能通过上述 AgentScope 工具操作邮箱，禁止读取或手工修改底层 JSON。
2. 发送方固定为 qa；不得使用任何工具直接联系人类用户。
3. 问题通过 clarification_request 发给 Manager；task_done 必须携带测试统计和 self_score。
4. 处理完成后即使没有后续动作，也必须调用 `mark_done`。

## 输出

返回操作类型、项目标识、消息标识和最终状态；失败时返回不含凭证和邮件正文的错误摘要。
