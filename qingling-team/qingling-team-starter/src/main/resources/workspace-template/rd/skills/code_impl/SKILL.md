---
name: code_impl
description: "RD 按 tech_design.md 在沙盒里实现代码 + 单测 + 跑 pytest 通过。当 RD 收到 type=task_assign 且 subject 含'实现代码/code'的邮件时**一定**加载本 skill。五层分目录写代码 + 单测 ≥80% 覆盖 + 最多 3 次失败重试 → task_done 回 Manager。所有'开始写 feature code'的时刻走此 skill。必须 sandbox_execute_bash。"
type: task
---

# code_impl — 代码实现 + 单测

## ⚠️ 执行方式
**必须 `sandbox_execute_bash`**。宿主机 AgentScope Tool (write_shared) 虽然能写文件，但跑测试需要沙盒环境（pip install + pytest）。两种混用：
- 用 `sandbox_execute_bash cat > EOF` 写代码文件（因为要立刻跑 pytest）
- 用宿主机 Tool `send_mail` 发 task_done（因为 mailbox 在宿主机 filesystem）

## 🚨 Critical Rules
1. **禁 uvicorn 长进程**：测试一律 `httpx.Client(app=app)`；打开进程=失败。
2. **覆盖率 < 70% 不交付**：必须补测试或跟 Manager 说明放宽（revise）。
3. **pytest 失败 ≤ 3 次重试**：每次失败分析 stderr，不要盲猜。
4. **requirements.txt 必写**：包含所有 import 依赖；不许"假定全局安装"。

## 步骤

### Step 1 — 读技术方案
`sandbox_execute_bash cmd="cat /workspace/shared/projects/{pid}/tech/tech_design.md"`

### Step 2 — 建五层目录
```bash
mkdir -p /workspace/shared/projects/{pid}/code/{routers,services,models,schemas,tests}
```

### Step 3 — 按 tech_design 写代码
对每个模块：
```bash
cat > /workspace/shared/projects/{pid}/code/models/{module}.py <<'EOF'
...
EOF
```
必需文件：
- `code/main.py`（app + router 挂载）
- `code/database.py`（engine + SessionLocal + get_db）
- `code/models/*.py`（ORM）
- `code/schemas/*.py`（Pydantic）
- `code/routers/*.py`（薄路由层）
- `code/services/*.py`（业务）
- `code/tests/test_*.py`（每 router 一份）
- `code/requirements.txt`

### Step 4 — 跑测试
```bash
cd /workspace/shared/projects/{pid}/code && \
  pip install -r requirements.txt -q && \
  python -m pytest -x --cov=. --cov-report=term 2>&1 | tail -60
```

### Step 5 — 失败修复（≤ 3 轮）
- 读 stderr 最后 30 行
- 定位是哪个测试哪个断言失败
- 改代码（而非改测试，除非测试本身写错）
- 重跑 Step 4

### Step 6 — 记 metrics + 发 task_done
AgentScope Tool：
```
send_mail(
  to="manager", type="task_done",
  subject="代码实现完成",
  content={
    "artifacts": ["code/main.py", "code/tests/...", "code/requirements.txt"],
    "metrics": {"pytest_final_status": "pass", "pytest_attempts": N, "coverage": 0.XX, "loc": ...},
    "self_score": 0.XX, "breakdown": {...},
    "rationale": "..."
  },
  project_id=pid,
)
mark_done(pid, <task_assign_msg_id>)
```

## 输出

```json
{
  "status": "success",
  "artifacts": [{"path": "code/main.py", "kind": "code"}, ...],
  "metrics": {"pytest_final_status": "pass", "pytest_attempts": 1, "coverage": 0.87, "loc_total": 420, "loc_test": 180},
  "self_score": 0.88,
  "breakdown": {"completeness": 1.0, "self_review": 0.95, "hard_constraints": 1.0, "clarity": 0.7, "timeliness": 0.85}
}
```
