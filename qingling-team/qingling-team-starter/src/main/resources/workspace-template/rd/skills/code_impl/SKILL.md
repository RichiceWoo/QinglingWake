---
name: code_impl
description: "RD 按 tech_design.md 在沙盒里实现代码 + 单测 + 跑 pytest 通过。当 RD 收到 type=task_assign 且 subject 含'实现代码/code'的邮件时**一定**加载本 skill。五层分目录写代码 + 单测 ≥80% 覆盖 + 最多 3 次失败重试 → task_done 回 Manager。所有'开始写 feature code'的时刻走此 skill。必须 sandbox_execute_bash。"
type: task
---

# code_impl — 代码实现 + 单测

## ⚠️ 执行方式
**必须真实调用 `sandbox_execute_bash` 跑测试**。文件写入与沙盒执行分工如下：
- 用宿主机 Tool `write_shared(project_id, rel_path, content)` 逐个写 `code/` 文件；它会安全创建父目录，且产物立即映射到沙盒 `/workspace/shared/projects/{pid}/code/`。
- `{pid}` 不是字面量：必须替换为当前 `task_assign.project_id`。第一次沙盒调用必须执行 `test -d /workspace/shared/projects/{pid}/code && cd /workspace/shared/projects/{pid}/code && pwd` 验证唯一目录；验证失败时发送 `clarification_request`，禁止猜测或切换目录。
- 用 `sandbox_execute_bash` 安装依赖并跑 pytest；不要用 heredoc、`cat >`、`tee` 或 shell 重定向写源码，避免命令进入人工确认状态。
- 调用 `sandbox_execute_bash` 时优先省略 `timeout`；确需设置时必须传 JSON 整数秒（例如 `timeout=300`），禁止传字符串（例如 `timeout="300"`），否则工具参数校验会拒绝执行。
- 用宿主机 Tool `send_mail` 发 task_done（因为 mailbox 在宿主机 filesystem）。

## 🚨 Critical Rules
1. **唯一执行目录**：只允许 `/workspace/shared/projects/{pid}/code`。即使 `pwd` 显示 `/workspace/rd`，也不得在该目录下新建 `code/`；禁止把 `write_shared` 产物复制、重建或改写到 `/workspace/rd/code`、`/workspace/code`、`/tmp` 等第二份目录。测试结果只有在唯一目录执行才有效。
2. **禁 shell 写源码**：修复失败用例时仍只能调用 `write_shared` 修改正式产物；禁止 heredoc、`cat >`、`tee`、`sed -i` 或 shell 重定向写入任何源码和测试文件。
3. **禁 uvicorn 长进程**：测试一律 `httpx.Client(app=app)`；打开进程=失败。
4. **覆盖率 < 70% 不交付**：必须补测试或跟 Manager 说明放宽（revise）。
5. **pytest 失败 ≤ 3 次重试**：每次失败分析 stderr，不要盲猜。
6. **requirements.txt 必写**：包含所有 import 依赖；不许"假定全局安装"。
7. **SQLite 测试隔离必须可共享连接**：FastAPI `TestClient` 场景默认使用每个测试独立的命名临时 SQLite 文件（例如 `tmp_path / "test.db"`），把该路径传入应用的数据库配置；禁止直接使用 `sqlite:///:memory:`。若技术方案明确要求内存库，必须配置 SQLAlchemy `StaticPool`，并在 `Base.metadata.create_all(...)` 前显式导入全部 ORM model，确保请求连接能看到已建表；否则很容易出现 `no such table`。
8. **测试与应用配置必须同源**：若 `database.py` 支持 `TODO_DB_PATH` 或其他数据库环境变量，测试必须在导入或创建 app 前设置它，或通过依赖覆盖使用同一个 engine；不得设置一个应用并不读取的环境变量。
9. **SQLite 服务端时间默认值必须可建表**：需要数据库默认时间时使用 SQLite 可接受的 `server_default=text("CURRENT_TIMESTAMP")`，或改用 Python 侧 `default=datetime.utcnow`；禁止把 `strftime(...)` 函数表达式直接放进 `server_default`，避免 `CREATE TABLE` 语法错误。
10. **失败时先守住文档契约**：测试断言来自 `needs/requirements.md`、`design/product_spec.md` 和 `tech/tech_design.md`。实现返回值与断言不一致时，必须先回读这三份文档并修复业务代码；禁止仅因为当前实现返回 `422`、`500` 或其他结果，就把文档要求的 `400` 等期望值改成实际值来制造全绿。
11. **依赖清单是测试前置门禁**：运行 pytest 前必须执行 `test -s requirements.txt`，并通过 `pip install -r requirements.txt` 安装依赖。文件缺失、为空或未列全 import 依赖时不得交付，也不得改成逐个 `pip install` 后声称硬约束通过。

## 步骤

### Step 1 — 读技术方案
`sandbox_execute_bash cmd="cat /workspace/shared/projects/{pid}/tech/tech_design.md"`

### Step 2 — 规划五层目录
列出 `routers/`、`services/`、`models/`、`schemas/`、`tests/` 下的全部目标文件；目录由下一步的 `write_shared` 自动创建，不调用 shell 建目录。

### Step 3 — 按 tech_design 写代码
对每个模块调用 `write_shared(project_id={pid}, rel_path="code/...", content="...")`。每次调用只写一个完整文件，禁止让 shell 解释源码内容。
必需文件：
- `code/main.py`（app + router 挂载）
- `code/database.py`（engine + SessionLocal + get_db）
- `code/models/*.py`（ORM）
- `code/schemas/*.py`（Pydantic）
- `code/routers/*.py`（薄路由层）
- `code/services/*.py`（业务）
- `code/tests/test_*.py`（每 router 一份）
- `code/requirements.txt`

编写 `tests/conftest.py` 时优先采用 `tmp_path` 创建数据库文件，并确保应用的 `get_db` 与测试请求共用该文件/engine。每个测试结束后关闭 session 和 engine；不得让 API 测试与 service 测试各自创建互不可见的 `:memory:` 数据库。

### Step 4 — 跑测试
调用工具时只传 `cmd` 即可；不要自行添加字符串形式的 `timeout`。若测试确需延长单次执行时间，只能传整数秒。

先把 `{pid}` 替换为当前邮件中的真实 `project_id`，验证目录后再安装和测试。若 `test -d` 失败，停止本任务并向 Manager 报告沙盒挂载异常；禁止 `ls /workspace` 后选择角色私有目录作为回退。

```bash
test -d /workspace/shared/projects/{pid}/code && \
  cd /workspace/shared/projects/{pid}/code && \
  test -s requirements.txt && \
  pip install -r requirements.txt -q && \
  python -m pytest -x --cov=. --cov-report=term 2>&1 | tail -60
```

### Step 5 — 失败修复（≤ 3 轮）
- 读 stderr 最后 30 行
- 定位是哪个测试哪个断言失败
- 回读三份需求文档确定唯一期望；改代码（而非改测试）。只有断言与三份文档冲突时才允许修测试，并在 `task_done` 中说明文档依据
- 禁止把断言改成当前实现的实际状态码或返回值来消除失败
- 重跑 Step 4

### Step 6 — 记 metrics + 发 task_done
AgentScope Tool：
```
send_mail(
  to="manager", type="task_done",
  subject="代码实现完成",
  content={
    "execution_id": "<run_project_tests.execution_id>",
    "status": "<run_project_tests.status>",
    "exit_code": 0,
    "pytest_collected": "<run_project_tests.pytest_collected>",
    "pytest_passed": "<run_project_tests.pytest_passed>",
    "coverage_percent": "<run_project_tests.coverage_percent>",
    "failure_type": "<run_project_tests.failure_type>",
    "retryable": "<run_project_tests.retryable>",
    "artifacts": ["code/main.py", "code/tests/...", "code/requirements.txt"],
    "metrics": {"pytest_final_status": "pass", "pytest_attempts": N, "loc": ...},
    "self_score": 0.XX, "breakdown": {...},
    "rationale": "..."
  },
  project_id=pid,
)
```

`send_mail` 成功后 Java 会自动把对应 `task_assign` 标记为 `done`；不得在门禁失败后单独调用 `mark_done`。

## 输出

```json
{
  "status": "success",
  "artifacts": [{"path": "code/main.py", "kind": "code"}, ...],
  "execution_id": "test-run-...",
  "status": "PASSED",
  "exit_code": 0,
  "pytest_collected": 12,
  "pytest_passed": 12,
  "coverage_percent": 87.0,
  "failure_type": "NONE",
  "retryable": false,
  "metrics": {"pytest_final_status": "pass", "pytest_attempts": 1, "loc_total": 420, "loc_test": 180},
  "self_score": 0.88,
  "breakdown": {"completeness": 1.0, "self_review": 0.95, "hard_constraints": 1.0, "clarity": 0.7, "timeliness": 0.85}
}
```
