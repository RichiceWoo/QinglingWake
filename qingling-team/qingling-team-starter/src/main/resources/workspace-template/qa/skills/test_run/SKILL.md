---
name: test_run
description: "QA 在沙盒里执行 qa/test_plan.md 所有用例、收集失败、写 defect 的 skill。当 QA 收到 type=task_assign 且 subject 含'测试执行/run tests'的邮件时**一定**加载本 skill。按 test_plan 跑 pytest → 通过标 pass、失败写 qa/defects/defect_{id}.md → 汇总 qa/test_report.md、证据矩阵和 task_done；缺陷只上报 Manager，由 Java 状态机决定后续阶段。所有'QA 真的跑测试'的时刻走此 skill。必须使用 run_project_tests。"
type: task
---

# test_run — 执行测试

## ⚠️ 执行方式
沙盒里跑 pytest（`sandbox_execute_bash`）；宿主机写 defect 报告和邮件可以用 AgentScope Tool。

调用 `sandbox_execute_bash` 时优先省略 `timeout`；确需设置时必须传 JSON 整数秒（例如 `timeout=300`），禁止传字符串（例如 `timeout="300"`），否则工具参数校验会拒绝执行。

## 🚨 Critical Rules
1. **一条用例一个 defect 文件**：便于后续 RD 修复时精确对应。
2. **test_report.md 是强制交付物**：Manager 据此判断交付 or 打回。
3. **defect 文件必填 reproduce 步骤 + 期望 vs 实际**：否则 RD 修不了。
4. **不得发明测试契约**：执行范围必须来自三份需求文档和 `qa/test_plan.md`；不得要求未声明的字段、HTTP 方法或功能，也不得测试 requirements 明确列为非目标的能力。
5. **优先执行共享代码自带测试**：先在共享 `code/` 上运行 RD 提交的 pytest，再仅补充测试计划中有明确文档依据但尚未覆盖的用例；禁止重建一套改变接口契约的 42 条或其他任意数量测试。
6. **正式产物只能用 `write_shared`**：`qa/test_report.md` 与 `qa/defects/*.md` 禁止使用 `write_file`、shell 重定向或角色工作区相对路径写入。
7. **直接使用沙盒挂载目录**：宿主 `target/e2e-workspace` 已挂载到沙盒 `/workspace`。必须直接在 `/workspace/shared/projects/{pid}/code` 安装依赖并运行 pytest；禁止逐个 `read_shared` 后复制到 `/tmp`、使用 heredoc 重建代码，或维护第二份测试副本。
8. **缺陷只上报 Manager**：若有 defect，只在 `task_done` 中附缺陷路径交给 Manager；禁止 QA 直接向 RD 发送 `task_assign`。
9. **RD 自测通过不等于 QA 契约通过**：必须独立执行 `qa/test_plan.md` 中每一条有文档依据的用例。仅运行 `code/tests/`、阅读测试名或看到 pytest 全绿，不得声称计划用例通过。
10. **禁止迎合实现改期望**：状态码、HTTP 方法、路径、长度边界和响应字段必须来自三份文档。实现返回 `422` 而产品契约要求 `400` 时应记录 defect；禁止修改预期、复用 RD 已改写的断言或在报告中把实际值描述为正确值。
11. **计划与证据必须一对一**：报告必须包含每个可执行用例 ID、文档期望、实际执行入口、实际结果和结论。可执行计划用例数、证据行数和 pass/fail 总数不相等时，本轮测试必须失败，禁止发送“全部通过”。
12. **缺少依赖清单即失败**：执行前必须验证 `code/requirements.txt` 存在且非空。缺失时生成独立 defect，不得用逐个 `pip install` 绕过并继续宣称交付可复现。

## 步骤

### Step 1 — 读 test_plan
`read_shared(pid, "qa/test_plan.md")` 拿用例清单

同时回读 `needs/requirements.md`、`design/product_spec.md` 和 `tech/tech_design.md`。若计划含文档无依据或明确非目标的用例，将其标为“计划错误，不执行”，并在报告中说明；不得把这类用例失败记为产品 defect。

### Step 2 — 跑 RD 自带测试并检查可复现依赖
```bash
cd /workspace/shared/projects/{pid}/code && \
  test -s requirements.txt && \
  pip install -r requirements.txt -q && \
  python -m pytest -v --tb=short
```

同一缺陷修复后的回归轮次仍执行这条命令，不重新复制源码。RD 自带测试只是一组输入证据，不能据此跳过下面的 QA 独立契约执行。

### Step 3 — 生成并执行 QA 独立契约测试
1. 从 `qa/test_plan.md` 和 `qa/test_cases.md` 提取全部有文档依据的用例 ID；逐条记录其来源章节、精确路径、HTTP 方法、输入、期望状态码/响应和边界值。
2. 用 `write_shared(pid, "qa/contract_tests/test_contract.py", ...)` 生成 QA 自有的最小 pytest 契约套件。每个可执行用例必须有唯一同名测试或参数化 case id；不得导入或修改 `code/tests/` 的断言。
3. 在沙盒执行：
   ```bash
   cd /workspace/shared/projects/{pid}/code && \
     PYTHONPATH=. python -m pytest -v --tb=short ../qa/contract_tests/test_contract.py
   ```
4. 对每个 C-XX 建立证据矩阵：
   - 文档期望与真实执行结果一致 → pass
   - 真实执行失败或实际值不符 → 写 defect：`write_shared(pid, "qa/defects/defect_{id}.md", defect_md)`
   - 没有真实执行入口或输出证据 → fail，并写“用例未执行” defect
5. 校验 `可执行用例数 == QA 契约测试 collected 数 == 报告证据矩阵行数 == pass + fail`。任一不相等都不得声称全绿。

Defect 模板：
```markdown
# Defect {C-XX}
- **用例**: ...
- **优先级**: F/H/M/L
- **Reproduce**: POST /diary {invalid}...
- **期望**: 422 + error_code=invalid_input
- **实际**: 500 + "internal server error"
- **栈**: （pytest 输出最后 30 行）
```

### Step 4 — 汇总 test_report.md
```markdown
# 测试报告 (xiaopaw 日记 v0.1)
- 执行时间：2026-04-21
- 总用例：18
- 通过：16
- 失败：2

## 失败用例
- C-07（defect qa/defects/defect_c07.md）
- C-12（defect qa/defects/defect_c12.md）

## 覆盖率
78%（来自 pytest --cov）

## 契约执行证据矩阵
| 用例 ID | 文档期望 | 执行入口 | 实际结果 | 结论 |
|---|---|---|---|---|
| C-01 | POST /api/todos → 201 | test_contract.py::test_c_01 | 201 | pass |
```
`write_shared(pid, "qa/test_report.md", ...)`

所有 defect 和 QA 契约测试写完后逐个 `read_shared` 回读，报告写完后必须 `read_shared(pid, "qa/test_report.md")` 回读确认。任一正式产物回读失败，或证据矩阵数量校验失败时，不得发送成功状态的 `task_done` 或声称交付完成。

### Step 5 — 发 task_done
1. `send_mail(to="manager", type="task_done", subject="测试执行完成", content={"execution_id": "<run_project_tests.execution_id>", "status": "<run_project_tests.status>", "exit_code": "<run_project_tests.exit_code>", "pytest_collected": "<run_project_tests.pytest_collected>", "pytest_passed": "<run_project_tests.pytest_passed>", "coverage_percent": "<run_project_tests.coverage_percent>", "failure_type": "<run_project_tests.failure_type>", "retryable": "<run_project_tests.retryable>", ...}, project_id=pid)`
2. 若有 defect：在上述 `task_done` 正文中携带 `"defects": ["qa/defects/defect_c07.md", ...]`，由 Manager 唯一决定并分派修复任务。
3. `send_mail` 成功后 Java 自动完成对应 `task_assign`；门禁失败时禁止单独调用 `mark_done`。

## 输出

```json
{
  "status": "success",
  "execution_id": "test-run-...",
  "exit_code": 1,
  "pytest_collected": 18,
  "pytest_passed": 16,
  "coverage_percent": 78.0,
  "failure_type": "PYTEST_FAILURE",
  "retryable": false,
  "artifacts": [{"path": "qa/test_report.md"}, {"path": "qa/defects/defect_c07.md"}, {"path": "qa/defects/defect_c12.md"}],
  "metrics": {"total": 18, "pass": 16, "fail": 2, "coverage": 0.78},
  "self_score": 0.83,
  "breakdown": {...}
}
```
