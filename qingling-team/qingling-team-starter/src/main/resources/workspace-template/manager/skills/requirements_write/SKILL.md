---
name: requirements_write
description: "Manager 起草需求文档 needs/requirements.md 的 skill。当 requirements_guide 判定 coverage_complete=true、准备发需求确认 checkpoint 时**一定**加载本 skill。按 5 节模板从对话历史里提炼产出 needs/requirements.md。需求文档必须先于用户 approve 落盘，approve 只负责允许后续分派。"
type: reference
---

# requirements_write — 起草 requirements.md

## 🚨 Critical Rules
1. **5 节缺一不可**：Goal/Boundary/Constraint/Risk/Acceptance。
2. **Acceptance 必须可机械验证**：每条能写成 pytest 断言（不是"体验流畅"这种模糊词）。
3. **Not-doing 清单越具体越好**：否则 PM 会悄悄把它做进去。
4. **必须先落盘再确认**：没有真实 `project_id` 或 `needs/requirements.md` 尚未写入时，禁止调用 `send_to_human` 发送 checkpoint。
5. **固定确认标识**：需求确认只能使用 `checkpoint_id="requirements_review"`，不得临时创造 `initial_requirements` 等其他值。

## 步骤

### Step 1 — 汇总对话
从 requirements_guide 四维对话历史整理关键决定。

### Step 2 — 按模板生成
```markdown
# {产品名} 需求文档

## 1. 目标（Goal）
- 核心痛点：...
- 目标用户：...
- MVP 功能清单：
  1. ...
  2. ...
  3. ...

## 2. 边界（Boundary）
- **做**：...
- **不做**：...（3 条起）
- 时间预算：...

## 3. 约束（Constraint）
- 技术栈：...
- 部署：...
- 安全/合规：...

## 4. 风险（Risk）
| 风险 | 影响 | 缓解 |
|------|------|------|

## 5. 接受标准（Acceptance，每条可机械测）
- [ ] POST /diary 能创建日记并返回 201 + id
- [ ] GET /diary?limit=200 返回 422
- [ ] ...
```

### Step 3 — 写入（必做）
`write_shared(project_id, "needs/requirements.md", content)`。

如果还没有真实 `project_id`，先调用 `create_project`；不得猜测或在 checkpoint 正文中临时替代共享文件。

### Step 4 — 记录事件
`append_event(project_id, "requirements_drafted", {"sections": 5, "acceptance_count": N})`

### Step 5 — 发 checkpoint_request 请求最终确认（必做且必须最后执行）
`send_to_human(routing_key="__current__", message=摘要 + "请回复 同意/修改建议", kind="checkpoint_request", project_id=project_id, checkpoint_id="requirements_review")`；真实路由由工具从运行上下文解析，配合 `CheckpointStore.register`。

完整顺序固定为 `create_project` → `write_shared` → `append_event(requirements_drafted)` → `send_to_human(checkpoint_request)`。任何前置工具失败时先修正失败调用，禁止跳到 checkpoint。发送成功后立即结束当前轮次，等待用户下一条明确回复；初始需求消息不构成 approve。

## 输出

```json
{
  "status": "success",
  "artifacts": [{"path": "needs/requirements.md", "kind": "doc"}],
  "metrics": {"sections": 5, "acceptance_count": 8, "mvp_features": 5}
}
```
