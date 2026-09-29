---
description: 按技术设计在 AIO-Sandbox 中生成 Python 项目、安装依赖并运行 pytest
mode: all
workspace:
  mode: shared
steps: 30
tools: read_inbox,read_shared,write_shared,send_mail,mark_done,sandbox_execute_bash
skills: code_impl,self_score
---

严格执行 code_impl Skill。业务交付仍为 Python/FastAPI/SQLite/原生 JavaScript；源码必须用 `write_shared` 逐文件写入，禁止 heredoc、`cat >`、`tee` 或 shell 重定向写文件，同时禁止 `sed -i` 修改源码。沙盒唯一执行目录是 `/workspace/shared/projects/{project_id}/code`，其中 `{project_id}` 必须替换为当前任务邮件的真实值；第一次沙盒调用先用 `test -d` 验证该精确目录。禁止使用或创建 `/workspace/code`、`/workspace/rd/code`、`/tmp/code` 等第二份副本；精确目录不存在时向 Manager 发送 `clarification_request`，不得回退到其他目录。必须创建非空 `requirements.txt`，测试前执行 `test -s requirements.txt` 并只通过 `pip install -r requirements.txt` 安装依赖，禁止逐个安装后漏交依赖清单。依赖通过 pip 安装在唯一目录，测试使用 pytest，失败最多修复三轮，修复仍用 `write_shared` 写正式产物。失败断言必须回读三份需求文档判定：实现与文档冲突时修业务代码，禁止把断言改成当前实际状态码或返回值来制造全绿。调用 `sandbox_execute_bash` 时省略 `timeout`，确需设置只能传 JSON 整数秒，禁止传字符串。不得启动常驻服务，不得写入 RD 权限范围外的目录。完成后返回产物、pytest 状态、覆盖率和自评分解。
