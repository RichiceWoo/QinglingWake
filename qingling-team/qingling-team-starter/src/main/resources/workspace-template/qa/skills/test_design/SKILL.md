---
name: test_design
description: "QA 产出完整测试计划的 skill（风险分析 + 功能用例 + 边界/异常 + 端到端）。当 QA 收到 type=task_assign 且 subject 含'测试设计'的邮件时**一定**加载本 skill。读 requirements + product_spec + tech_design → 按 5 节模板写 qa/test_plan.md → task_done。所有'新项目 QA 开始测试设计'的时刻走此 skill。"
type: reference
---

# test_design — 测试计划设计

## 🚨 Critical Rules
1. **风险分级必须 F/H/M/L**：Fatal / High / Medium / Low 四档，不是 1-5 数字。
2. **每条用例必须可自动化**：手工测试在本项目不合格。
3. **端到端 ≥ 1 个 happy path + 1 个 error path**。
4. **文档是唯一事实来源**：只测试 `needs/requirements.md`、`design/product_spec.md`、`tech/tech_design.md` 明确约定的功能、字段、路径、方法和状态码。优先级固定为 requirements > product_spec > tech_design，下游文档不得扩大上游范围。
5. **非目标不得进入计划**：需求明确排除的搜索、筛选、分页、认证等能力不得生成用例；文档只声明 `PATCH` 时不得追加 `PUT`，没有 `updated_at` 或 `description` 时不得断言这些字段。
6. **正式产物只能写共享区**：必须用 `write_shared(pid, "qa/test_plan.md", 正文)`；禁止使用 `write_file`、shell 重定向或角色工作区相对路径生成正式报告。
7. **测试设计不读实现源码**：本阶段禁止读取 `code/`，避免把实现中多出的路由、字段或行为写进测试契约；代码与文档偏差留到测试执行阶段识别。

## 步骤

### Step 1 — 读三文件
- `needs/requirements.md` — 验收标准
- `design/product_spec.md` — 接口契约
- `tech/tech_design.md` — 错误码定义

先整理“范围内功能 / 明确非目标 / 接口契约”三张清单。技术方案多出的能力直接标为不在产品验收范围，不为其生成用例；上游文档彼此冲突时才向 Manager 发 `clarification_request`，在澄清前不得自行扩展契约。

### Step 2 — 风险分析
对每个接口 × 典型场景 矩阵打分：

```markdown
## 1. 风险点
| 风险 | 影响面 | 优先级 |
|------|--------|--------|
| 标题空值校验失效 | 创建无效待办 | H |
| 删除不存在记录返回成功 | 用户误判操作结果 | M |
```

### Step 3 — 功能用例表
```markdown
## 3. 功能用例
| ID | 场景 | 输入 | 预期 | 优先级 |
|----|------|------|------|--------|
| C-01 | 创建待办成功 | POST /api/todos {title} | 201 + id | F |
| C-02 | 创建缺 title | POST /api/todos {} | 422 | H |
| C-03 | 更新完成状态 | PATCH /api/todos/{id} {completed:true} | 200 | F |
```

### Step 4 — 边界 + 异常
```markdown
## 4. 边界与异常
- 空字符串 title / 最大长度 1000 / 特殊字符（emoji/引号）
- 更新或删除不存在的 id
- title 恰好达到文档规定的最大长度
```

### Step 5 — 端到端 smoke
```markdown
## 5. E2E Smoke
- S-01 创建→列表→更新完成状态→删除 全链路
- S-02 空标题与不存在 id 的错误恢复
```

### Step 6 — 写入 + task_done
`write_shared(pid, "qa/test_plan.md", 正文)` → `send_mail(to="manager", type="task_done", ...)`

写入成功后必须立即调用 `read_shared(pid, "qa/test_plan.md")` 回读确认；回读失败时不得发送 `task_done`。

## 输出

```json
{
  "status": "success",
  "artifacts": [{"path": "qa/test_plan.md", "kind": "doc"}],
  "metrics": {"total_cases": 18, "F": 2, "H": 5, "M": 8, "L": 3, "e2e": 2},
  "self_score": 0.86,
  "breakdown": {...}
}
```
