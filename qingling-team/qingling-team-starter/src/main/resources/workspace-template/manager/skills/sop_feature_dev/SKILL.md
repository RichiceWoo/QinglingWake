---
name: sop_feature_dev
description: "Manager 主 SOP：小功能开发 6 阶段主流程（需求澄清→产品设计→RD 实现→QA 测试→交付→复盘）。当收到用户'帮我做 X / 做一个 / 新功能'类新需求时**一定**加载本 skill。指导 Manager 在 6 阶段中选当前阶段 + 推进下一步 + 判断是否插入团队评审。所有 feature 类开发项目都走此 SOP。"
type: reference
kind: sop
---

# SOP: 小功能开发主流程（6 阶段）

你是 Manager。收到用户新开发需求后，按下面规则推动项目。本 SOP 覆盖完整 6 阶段。

## 阶段清单

| # | 阶段 | Owner | 本阶段产物 | 用户 Checkpoint |
|---|------|-------|-----------|----------------|
| 1 | 需求澄清 | Manager | `needs/requirements.md` | ✅ 需求定稿需用户 approve |
| 2 | 产品设计 | PM | `design/product_spec.md` | （可选评审） |
| 3A | 技术方案设计与必要修订 | RD | `tech/tech_design.md` | （可选评审） |
| 3B | 代码实现 | RD | `code/*.py` + pytest 通过 | — |
| 4A | 测试设计 | QA | `qa/test_plan.md` | （可选评审） |
| 4B | 测试执行 | QA | `qa/test_report.md` | — |
| 5 | 交付 | Manager | `delivery_sent` 事件 | ✅ 用户验收 |
| 6 | 复盘 | Manager + 各角色 | `retro_report` 邮件链 + `retro_applied_by_*` 事件 | （memory 自动，skill/agent/soul 转用户） |

## 关键推进规则

### 阶段 1：收到用户新需求飞书消息
1. 加载 skill `requirements_guide` → 按 4 维（goal/boundary/constraint/risk）评估需求完整度
2. 缺口 > 0：用 `send_to_human(kind="info")` 问 1-2 个最关键问题
3. 缺口 = 0（或用户明确说"所有细节由你决定"/"直接推进"/"我批准任何方案"）：
   - **立刻**调 AgentScope Tool `create_project(project_id=<短 slug>, project_name=<中文名>, needs_content=<完整 5 节需求 markdown>)`
   - 调 AgentScope Tool `append_event(project_id=<pid>, action="requirements_drafted", payload={...})`
   - 最后调 AgentScope Tool `send_to_human(routing_key="__current__", message=<需求摘要+请确认>, kind="checkpoint_request", project_id=<pid>, checkpoint_id="requirements_review")`
   - `needs_content` 必须含 Goal/Boundary/Constraint/Risk/Acceptance 五节和可机械验证的接受标准；禁止直接复制用户原句作为需求文档

### 用户在阶段 1 回复 checkpoint
- 含"同意/批准/approve/ok" → 加载 skill `handle_checkpoint_reply`
  - 外部 checkpoint 回复不是 mailbox message：禁止调用 `mark_done`、禁止虚构 `msg_id`
  - 先 `append_event("checkpoint_reply_classified", {checkpoint_id:"requirements_review", reply_class:"approve"})`
  - 再 `append_event("checkpoint_approved", {checkpoint_id:"requirements_review"})`
  - 调 `advance_workflow(project_id=<pid>, signal="checkpoint_approved", from_role="", task_done_content={}, feedback="")` → Java 状态机自动分派 PM
- 含修改意见 → 更新 `needs/requirements.md`（调 `write_shared`）+ 再发 checkpoint

### 阶段 2-4：流水线推进

**阶段切换硬规则（必须严格遵守，不能合并）**：模型不得直接发送下表任务，只能把评审结论提交给 `advance_workflow`，由 Java 状态机生成下一任务。

| 当前 task_done 来自 | Java 状态机生成的下一任务 | subject | 阶段约束 |
|-------------------|-----------------|---------|-----------|
| PM（含 design/product_spec.md）| to=rd | "技术方案设计 (第 1 轮)" | ✅ 仅技术方案，不含实现 |
| RD（含 tech/tech_design.md，包括任意轮技术方案修订）| to=rd | "代码实现 (第 1 轮)" | ✅ Java 必须先进入代码实现，禁止模型跳到 QA；唯一目录由 `run_project_tests(project_id)` 固定 |
| RD（含 code/main.py + code/tests/）| to=qa | "测试设计 (第 1 轮)" | ✅ 仅测试设计，不含执行 |
| QA（含 qa/test_plan.md）| to=qa | "测试执行 (第 1 轮)" | ✅ Java 必须独立进入测试执行，QA 使用 `run_project_tests(project_id)` |
| QA（含 qa/test_report.md 且全 pass）| — | — | 进入阶段 5 交付 |

**缺陷闭环的唯一分派规则**：
- QA 的 `task_done` 若报告失败或携带 `qa/defects/*.md`，Manager 提交 `advance_workflow(signal="qa_defect_found")`，Java 只向 RD 发送一条固定缺陷修复任务。
- RD 修复完成并满足测试证据门禁后，Manager 提交 `advance_workflow(signal="stage_accepted")`，Java 只向 QA 发送一条回归测试任务。
- QA 不得直接给 RD 分派任务。若邮箱中已存在相同轮次的开放修复任务，不得重复发送。
- 每轮回归报告继续覆盖写入 `qa/test_report.md`；只有最新报告明确全通过且无开放 defect 才能进入交付。

**不要做的事**：
- 🚫 subject="技术设计与实现" — 这是一条消息做两件事，RD 会漏做实现
- 🚫 subject="测试设计与执行" — QA 会漏做执行
- 🚫 没收到 RD 的"代码实现完成"就派 QA —— code/ 目录还没产物可测
- 🚫 没收到 QA 的"测试执行完成"就发 delivery —— 等 test_report.md 生成、且无 fail

**技术方案评审后的唯一合法推进**：
- 评审要求 revise：只向 RD 分派“技术方案修订”；修订完成邮件仍必须按 `tech/tech_design.md` 路径识别为阶段 3A。
- 技术方案通过（包括修订后通过）：下一任务只能是向 RD 分派“代码实现 (第 1 轮)”。
- 不论技术方案经历多少轮评审或修订，只要尚未收到包含 `code/main.py` 与 `code/tests/` 的 RD 完成邮件，就绝不能向 QA 分派测试设计。
- `from=rd` 不能单独决定下一阶段；必须同时核对产物路径。`tech/tech_design.md` 与 `code/` 分别代表两个不可合并的阶段。

**收到每条 `type=task_done` 邮件时**：
1. 调 `read_inbox(project_id)` → 拿到 task_done
2. 加载 skill `check_review_criteria` → 只调用一次同名 Java 工具得到 threshold_met，禁止自行遍历历史或计算摘要
3. threshold_met=false（常见情况）：
   - `append_event("task_done_received", {from_role, artifacts})`
   - 调 `mark_done(pid, msg_id)`
   - 按上表判断评审结论，只调用 `advance_workflow`；下一 task_assign 由 Java 发送
4. threshold_met=true：`append_event("decided_insert_review")` + 发 review_request ×2 → 等 review_done → 汇总决策

### 阶段 5：交付
QA 的 test_report 到达且所有通过：
1. 调 `append_event(pid, "delivery_requested", {...})`（交付准备好）
2. 调 `send_to_human(routing_key="__current__", message="交付汇报+验收请求", kind="delivery", project_id=pid, checkpoint_id="delivery-<pid>")`
3. 记录到 events 的 `delivery_sent` 动作已由 send_to_human 自动写入

用户 approve 回复（"同意/批准/验收通过"等）时：
1. `append_event(pid, "delivered", {"artifacts_summary": ..., "deliverer_approved_at": "<ts>"})` **— 这一步必须做，否则交付未完成**
2. `send_to_human(routing_key="__current__", message="✅ 交付确认完成，感谢！", kind="info", project_id=pid)`
3. 告知用户如需复盘，发"复盘"触发阶段 6

### 阶段 6：复盘（用户发"复盘/团队复盘"后）
加载 skill `team_retrospective` → 产出 proposals → 加载 `review_proposal` → 分档审批。

## 关键 Tool 快查
- `create_project(project_id, project_name, needs_content)` — Manager 独占
- `send_to_human(routing_key, message, kind, project_id, checkpoint_id)` — Manager 独占
- `append_event(project_id, action, payload)` — Manager 独占
- `send_mail(to, type, subject, content, project_id)` / `read_inbox(project_id)` / `mark_done(project_id, msg_id)` — 全角色；`task_assign` 除外
- `advance_workflow(project_id, signal, from_role, task_done_content, feedback)` — Manager 只提交结论，Java 决定阶段与分派
- `read_shared(project_id, rel_path)` / `write_shared(project_id, rel_path, content)` — 全角色（按 owner 前缀）

## 硬约束
- 🚫 绝不亲自写需求 / 设计 / 代码 / 测试
- 🚫 绝不让 PM / RD / QA 直接联系用户
- ✅ 每个关键决策都 `append_event` 留痕
- ✅ 用户说"所有细节由你决定" = 视为需求 4 维已覆盖，**立即** create_project；不要再追问细节
