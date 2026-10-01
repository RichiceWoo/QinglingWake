---
name: handle_checkpoint_reply
description: "Manager 处理人类对 checkpoint 的回复、分档推进的思考框架。当 feishu_bridge.classify() 判定 category=checkpoint_response 时**一定**加载本 skill。分 approve / revise / reject 三种 reply_class，按 checkpoint.kind × reply_class 矩阵选下一步动作。所有'checkpoint 回复进来了'场景都走此 skill。"
type: reference
---

# handle_checkpoint_reply — checkpoint 回复分档推进

## 🚨 Critical Rules
1. **必须 resolve checkpoint**：任何分支末端都调 `CheckpointStore.resolve(checkpoint_id)`，否则下次 wake 仍被误识别。
2. **结构化 event 必须写**：每个决策都 append `checkpoint_reply_classified` + `checkpoint_approved/rejected`。
3. **revise 不是 reject**：revise 回更细的 clarification 给用户或改 artifacts，**不回退项目阶段**。
4. **人类回复不是邮箱**：绝不调用 `mark_done`，绝不虚构 `msg_id`，也不通过 `read_inbox` 查找 checkpoint 回复。
5. **批准事件先于分派**：approve 必须先成功写入 `checkpoint_reply_classified` 和 `checkpoint_approved`，随后只调用 `advance_workflow`；禁止直接 `send_mail(to="pm")`。

## 步骤

### Step 1 — 取 checkpoint 上下文
从 Bootstrap 里已注入的 pending_checkpoints 或读 `shared/feishu_bridge/pending.jsonl` 找到对应 `checkpoint_id`，拿到 `kind`、`project_id`。

### Step 2 — 分类回复文本
输入原文 → 三类：
- `approve`：含 "同意/批准/approve/yes/ok/确认" 之一
- `reject`：含 "拒绝/不同意/reject/no" 之一
- `revise`：其他有内容的回复（视为 revision 指令）

### Step 3 — 查动作矩阵

| checkpoint.kind | reply_class | 动作 |
|-----------------|-------------|------|
| checkpoint_request（需求确认） | approve | 先 `append_event("checkpoint_reply_classified")`、再 `append_event("checkpoint_approved")`，最后 `advance_workflow(signal="checkpoint_approved")`，由 Java 分派 PM |
| checkpoint_request（需求确认） | revise | 更新 `needs/requirements.md` + 再发 checkpoint_request |
| checkpoint_request（需求确认） | reject | `advance_workflow(signal="checkpoint_rejected")` 进入 `CANCELLED`，再通知需求已取消并归档项目 |
| proposal_review（复盘审批） | approve | 按 approved_ids 发 `retro_approved` 给 target_role × N |
| proposal_review | reject | `send_mail(to=<role>, type="retro_rejected", content={reason})` |
| proposal_review | revise | `send_to_human` 澄清 |
| delivery | approve | `advance_workflow(signal="delivery_approved")` 进入 `COMPLETED`，再 `append_event("delivered")` + `send_to_human(kind="info","交付完成")` |
| delivery | reject | `advance_workflow(signal="delivery_rejected")`，由 Java 固定分派 RD 修复并在完成后回到 QA 回归 |

### Step 4 — 写事件 + resolve
- `append_event("checkpoint_reply_classified", {cid, reply_class})`
- `append_event("checkpoint_approved" or "checkpoint_rejected", {cid})`
- 通过 feishu_bridge 的 CheckpointStore.resolve(cid)（需通过 AgentScope Skill loader 加载辅助脚本，v0 先手动记忆）

对于需求 approve，确认上述两个事件都返回成功后，再发送 PM 产品设计任务。不得把当前外部消息当作 mailbox message，也不得为它调用 `mark_done`。

## 输出

```json
{
  "classified": "approve",
  "checkpoint_id": "ckpt-a1b2c3d4",
  "kind": "checkpoint_request",
  "next_actions": ["advanced workflow to PRODUCT_DESIGN", "appended checkpoint_approved"]
}
```
