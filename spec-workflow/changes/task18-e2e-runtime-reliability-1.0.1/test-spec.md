# 单测 Spec — Task 18 E2E 运行时可靠性修复 1.0.1

> status: apply
> created: 2026-10-07

## 0. 测试原则

- **展示证据**：所有通过结论必须记录真实命令、测试数和关键输出；无 `summary.json` 的真实场景不得计为通过。
- **先便宜后昂贵**：单元/契约 → 默认回归 → 零模型真实 Docker 压力契约 → 真实模型 E2E。
- **故障分类**：必须区分 pytest 业务失败、MCP 传输失败、命令超时、响应丢失、模型失败和流程超时。
- **不掩盖失败**：不得用延长超时、跳过、放宽断言、mock 模型/沙盒或手工补产物换取通过。
- **秘密保护**：日志、异常和证据中不得出现 DASHSCOPE API Key、MCP Header 或完整鉴权参数。
- **两次上限**：任一真实场景连续两次从干净目录失败后暂停，不启动第三次。

## 1. 测试框架

| 项目 | 值 |
|------|-----|
| JUnit | 5 |
| 断言/Mock | AssertJ、Mockito 或现有确定性 fake |
| 异步 | CompletableFuture、Reactor Test（仅 infra 边界需要时） |
| 默认回归 | Maven Surefire/Failsafe，`mvn verify` |
| 真实工具契约 | Maven `tool-contract` profile + Docker AIO-Sandbox + NoCallModel |
| 真实 E2E | Maven `e2e` profile + 百炼 `qwen3-max` + AgentScope + Docker AIO-Sandbox |

## 2. 覆盖范围

### P0 — 必须通过

| 类/组件 | 方法或场景 | 输入/故障 | Mock/前置 | 预期 |
|---------|------------|-----------|-----------|------|
| `ProjectTestAgentTool` | pytest 成功 | exit 0、多个测试、覆盖率 | fake MCP 输出 | 结构化解析 execution ID、数量和覆盖率 |
| `ProjectTestAgentTool` | pytest 失败 | exit 非 0 | fake MCP 输出 | 标记业务失败，可由 RD 修复，不触发 MCP 熔断 |
| 沙盒执行器 | 命令超时 | 子进程超过上限 | fake/集成 runner | 终止进程组，返回 `TIMEOUT`，无孤儿进程 |
| 沙盒执行器 | 响应丢失 | 命令完成后 transport 断开 | 可查询执行 fake | 按 execution ID 找回同一结果，不重复执行 |
| MCP 熔断器 | 首次传输失败 | timeout/disconnect | fake clock/client | OPEN 后快速失败，重建探针成功才关闭 |
| MCP 熔断器 | 连续失败 | 两次相同基础设施失败 | fake clock/client | 第二次不可重试，工作流进入 `BLOCKED` |
| MCP 调用 | 并发测试 | 同 project 两次提交 | controllable future | 同项目串行，不覆盖或串结果 |
| 工作流状态机 | 工具故障 | CODE_IMPLEMENTATION/RD | deterministic fake | 阶段与 Owner 不变，产生唯一恢复任务 |
| 工作流状态机 | 重试耗尽 | 同类 incident 第 2 次 | deterministic fake | 项目 BLOCKED，不能分派 QA/交付 |
| mailbox watchdog | stale 且无 dispatch | 超过 lease | fake clock/registry | 恢复 unread 并安排唯一 wake |
| mailbox watchdog | stale 但 dispatch 活跃 | 超过 lease | fake clock/registry | 不回收，不重复执行 |
| `SerialDispatchRegistry` | shutdown | 停止后 submit | controllable executor | 明确拒绝新任务；在途任务有限排空/取消 |
| `RuntimeLifecycle` | 关闭顺序 | 有在途 Agent 与 Cron | ordered fakes | Listener/Cron 停止先于 drain，最后关闭 Gateway |
| E2E Driver | 工具终态失败 | workflow incident | 临时证据目录 | 快速失败并指出上游工具原因，不报假 `qa/test_plan` 根因 |
| 完成协议 | task_done 门禁失败后单独 mark_done | in_progress task_assign | 真实文件 mailbox | 拒绝关闭，任务继续保持 in_progress |
| 完成协议 | 合规 task_done | 原任务与同阶段恢复任务均 in_progress | 真实文件 mailbox | 先写入完成邮件，再由 Java 自动把全部关联分派置为 done |
| E2E Driver | 当前任务 done 但缺少 task_done | mailbox/dispatch 均空闲 | JSON 快照 | 直接报告 stage、Owner、assignment ID 与 `task_done_missing`，不等待下游文件 |
| E2E Driver | 失败证据 | 任意异常 | 临时证据目录 | `finally` 保存脱敏 `failure-summary.json` 等证据 |
| 配置/日志 | 秘密保护 | 含测试 API Key 的配置 | fake secret | 日志、异常、JSON 不包含秘密 |

Task 4 实测：`mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra -am test` 通过，domain 43、app 25、infra 87，共 155 项；覆盖 pytest 业务失败不报 incident、可恢复故障唯一唤醒原 Owner、重复 incident 幂等、第二次同类故障 BLOCKED，以及 BLOCKED 后拒绝状态推进。

Task 5 实测：`mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra,qingling-team-starter -am test` 通过，domain 43、app 27、infra 88、adapter 13、starter 25，共 196 项；覆盖 stale 且空闲时恢复、活跃角色 dispatch 不回收、重复 tick 不重复 wake，以及缺少 `processing_since` 的旧格式 unread mailbox 读取。

Task 6 实测：`mvn -pl qingling-team-app,qingling-team-starter -am test` 通过，domain 43、app 30、infra 88、adapter 13、starter 27，共 201 项；覆盖停止接纳后拒绝 submit、停止创建一次性 wake、有限排空成功路径、超时取消活跃/排队 Future，以及关闭 Agent/MCP 的严格顺序。

### P1 — 建议

| 类/组件 | 场景 | 预期 |
|---------|------|------|
| 执行结果存储 | 旧版本文件读取 | 缺少新字段时使用安全默认值 |
| 熔断健康快照 | 多项目/多角色 | 一个失效会话不会永久污染无关项目 |
| E2E Driver | 正常业务测试失败 | 继续等待 RD 修复，不误判基础设施失败 |
| 证据输出 | stdout/stderr 很大 | 有明确截断标记，不撑爆 JSON 或上下文 |
| 取消逻辑 | Java Future 被取消 | 容器进程和 MCP 请求最终停止 |

### P2 — 可选

- 长时间 soak（30～60 分钟）和并发项目压力测试。
- Docker 容器重启后的执行结果恢复验证。
- MCP 服务版本升级兼容性矩阵。

### 不测试（写明原因）

- 不断言模型逐字输出；只验证状态、产物、证据和业务不变量。
- 不在单元测试中调用真实百炼，避免额度、网络和随机性污染。
- 不使用宿主机任意 Shell 作为沙盒成功的替代证据。

## 3. 零模型真实 Docker 压力契约

必须在真实 E2E 前通过：

1. 重建或确认 AIO-Sandbox 可用。
2. 以 NoCallModel 创建真实 RD、QA Agent/子代理和实际 MCP 工具。
3. 连续执行至少 10 次无副作用 `pwd/test -d`。
4. 在隔离项目代码目录连续执行至少 5 次 `run_project_tests`。
5. 验证每次都有唯一 execution ID 和明确终态。
6. 验证不存在 pending tool、孤儿 pytest/pip/tmux 进程。
7. 覆盖一次受控连接故障；验证结果找回或客户端重建。
8. 失败时禁止进入真实模型 E2E。

## 4. 真实 E2E

当前配置：

```bash
QINGLING_FREE_MODEL_SELECTION_ENABLED=false
QINGLING_AGENT_MODEL=qwen3-max
QINGLING_SUB_AGENT_MODEL=qwen3-max
```

依次执行：

| 场景 | 核心断言 |
|------|----------|
| `happy-path` | checkpoint → PM → RD 技术设计/代码 → QA 设计/执行 → 交付完整通过 |
| `checkpoint-revise` | 人工要求修订后再次 checkpoint，批准后完整交付 |
| `qa-defect-rd-fix` | QA 缺陷必须回到 RD 修复，再回 QA 验证 |
| `code-fail-recovery` | pytest 失败不能伪成功，RD 修复并取得新证据后才推进 |

共同证据：

- `summary.json` 与 `model-selection.json`。
- 项目文档、代码、QA 证据矩阵和报告。
- workflow events、mailbox 三态、Session、MCP 工具调用与沙盒命令结果。
- 每次结构化测试的 execution ID、exit code、pytest 数量和覆盖率。
- 无 CrewAI 残留、无 API Key 泄漏、无孤儿进程。

## 5. 执行计划

- [x] 运行当前默认回归，确认实现前基线。
- [x] 按 Task 2～Task 7 新增红绿测试。
- [x] 运行相关模块定向测试。
- [x] 运行完整 `mvn verify`。
- [x] 运行零模型真实 Docker 压力契约。
- [ ] 只在压力契约成功后运行四条真实 E2E。
- [ ] 再运行完整 `mvn verify` 和 `git diff --check`。
- [ ] 把实际结果回写本需求和父需求。

## 6. 常用命令

```bash
cd /Users/qingling/workspace/IdeaProjects/QinglingWake/qingling-team

mvn -pl qingling-team-infra -am \
  -Dtest=ProjectTestAgentToolTest,McpSandboxConfigurationTest test

mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra,qingling-team-starter \
  -am test

mvn verify

mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify

mvn -Pe2e -De2e.scenario=happy-path verify
mvn -Pe2e -De2e.scenario=checkpoint-revise verify
mvn -Pe2e -De2e.scenario=qa-defect-rd-fix verify
mvn -Pe2e -De2e.scenario=code-fail-recovery verify

git diff --check
```

## 7. 测试结果

| 时间 | 命令 | 结果 | 证据/备注 |
|------|------|------|-----------|
| 2026-10-07 | 两次干净 `happy-path` | 均失败，未启动第三次 | 第二次从 8 项 pytest 失败修到 1 项后 MCP 命令通道超时；诊断位于 `target/e2e-evidence/diagnostics/happy-path-20261007-attempt2`，不得作为成功证据 |
| 2026-10-08 | MCP `initialize`、`tools/list`、`tasks/list` 与无副作用 `printf` 探针 | Task 1 审计通过，未调用模型 | 服务端 `Sandbox MCP Tools 2.14.7`；顶层虽公布 tasks list/cancel，但 `sandbox_execute_bash` 明确拒绝 task-augmented execution；普通结果只有 `status/output/exit_code/cwd`，无 execution ID、状态查询或取消句柄；容器 healthy、8029 可达且无遗留 Maven/Java/pytest/pip/tmux 进程 |
| 2026-10-08 | `mvn -pl qingling-team-infra -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ProjectTestAgentToolTest,McpProjectTestRunnerTest,McpSandboxConfigurationTest,TeamAgentFactoryTest test` | 通过（19 tests） | 覆盖结构化成功/业务失败/超时/传输失败、execution ID 找回、脱敏、OPEN 快速失败、关闭重建、HALF_OPEN 探针和同 ID 最多恢复一次 |
| 2026-10-08 | `mvn -pl qingling-team-infra -am test` | 通过（domain 42 + infra 86 tests） | Task 2～3 模块回归全部成功；未调用真实模型 |
| 2026-10-08 | `mvn -pl qingling-team-infra -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=McpProjectTestRunnerTest,ProjectTestAgentToolTest test` | 通过（9 tests） | 验证实际 ToolUse 参数、结构化 ToolResult 明确为 SUCCESS，并固定使用 `new_session=false`，不创建持久 tmux 会话 |
| 2026-10-08 | `mvn verify` | 通过（203 tests） | domain 43、app 30、infra 88、adapter 13、starter 29；0 failures、0 errors、0 skipped |
| 2026-10-08 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 连续两轮通过（每轮 1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；每轮完成 10 次 Shell、5 次结构化 pytest、一次受控连接失效恢复，并确认无 pending tool 与 pytest/pip/tmux 孤儿进程；最终一轮总耗时 18.664 秒，未调用百炼 |
| 2026-10-08 | 修复后第 1 次干净 `mvn -Pe2e -De2e.scenario=happy-path verify` | 失败，未启动后续场景 | RD 通用 `sandbox_execute_bash` 创建 3 个空闲 tmux pane，工具 5 分钟超时；容器无 pytest/pip 子进程。驱动仍在 30 分钟后报告 `qa/test_plan.md` 超时，证据位于 `target/e2e-evidence/happy-path`；因此撤回 Task 7 完成标记并继续修复，当前只剩 1 次干净 `happy-path` 机会 |
| 2026-10-08 | `mvn -pl qingling-team-infra -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=McpSandboxConfigurationTest test` | 通过（10 tests） | 新增验证通用 Bash 无论模型是否传 `new_session=true` 都强制为 false，且 AgentScope 外层超时取消 Mono 时立即打开 MCP 熔断 |
| 2026-10-08 | 加强后的 `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 失败，正确暴露 2 个空闲 tmux | 原契约只看 MCP transport SUCCESS，未校验进程检查命令的成功标记；修正断言后证明 `removeMcpClient` 不会关闭服务端 shell |
| 2026-10-08 | AIO-Sandbox 1.11.0 容器源码与原生 API 审计 | 通过，未调用模型 | MCP schema 未暴露关闭工具；确认 `DELETE /v1/shell/sessions` 调用 shell manager 的 `cleanup_all_sessions()`，可真实关闭 tmux；另确认默认 `SHELL_POOL_SIZE=1` 会保留一个基础设施 tmux |
| 2026-10-08 | 干净容器首轮契约 / MCP 内 pip 预置尝试 | 先后因 pytest 缺失和 60 秒 MCP 超时失败 | 未进入真实 E2E；两次失败的 `finally` 都将活动 shell 恢复为 0，无 pip/tmux 孤儿进程。改为创建 MCP client 前使用固定、无 Shell 解析的 `docker exec` 参数预置 pytest/pytest-cov |
| 2026-10-08 | `mvn -pl qingling-team-infra -am -Dtest=McpSandboxConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（11 tests） | 新增关闭幂等和服务端会话清理仅执行一次的验证 |
| 2026-10-08 | `mvn verify` | 通过（206 tests） | domain 43、app 30、infra 91、adapter 13、starter 29；0 failures、0 errors、0 skipped |
| 2026-10-08 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；10 次无副作用 Shell、5 次结构化 pytest、1 次受控连接失效/重建，总耗时 21.089 秒。契约内断言及外部复核均确认活动 shell=0，pytest/pip/tmux=0，未调用百炼 |
| 2026-10-08 | 最新一次干净 `mvn -Pe2e -De2e.scenario=happy-path verify` | 失败，未启动另外三个场景 | 34 分 35 秒后等待 `qa/test_plan.md` 超时。证据显示 workflow 停在 `CODE_IMPLEMENTATION/RD`，代码任务 `msg-db8f391e` 已被标记 done，但 Manager 邮箱没有该任务之后的 RD `task_done`；mailbox/dispatch 均空闲，MCP 全部 CLOSED 且失败计数为 0。直接根因是完成协议可被单独 `mark_done` 绕过，不是模型能力或 MCP 基础设施故障；证据保存在 `target/e2e-evidence/happy-path` |
| 2026-10-09 | `mvn -pl qingling-team-infra,qingling-team-starter -am -Dtest=DeterministicWorkflowToolsTest,RealTeamE2EDriverTest,WorkspaceTemplateContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（19 tests） | 验证 task_done 门禁失败不关闭任务、提前 mark_done 被拒绝、成功 task_done 自动收敛原任务与恢复任务、重复 mark_done 幂等，以及 Driver 对静默死锁的直接诊断 |
| 2026-10-09 | `mvn verify` | 通过（208 tests） | domain 43、app 30、infra 91、adapter 13、starter 31；0 failures、0 errors、0 skipped；未调用真实模型 |
| 2026-10-09 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；5 个执行状态均为 FINISHED，AIO 原生会话查询为 0，容器内 pytest/pip/tmux 为 0；未调用百炼 |
| 2026-10-09 | `mvn -Pe2e -De2e.scenario=happy-path verify` | 失败（1 E2E，34 分 35 秒），未启动后续场景 | 真实 `qwen3-max`、AgentScope、Docker AIO-Sandbox；RD 的 13/13 pytest 通过、覆盖率 94%，多个 execution ID 均有 FINISHED 终态。`send_mail.content` 在实际 ToolUse 中始终为 JSON 字符串，而 `TaskCompletionEvidenceGate` 只接受 `Map`，所以代码阶段 `task_done` 全部被拒绝并停在 `CODE_IMPLEMENTATION/RD`，最终表面错误为等待 `qa/test_plan.md` 超时。`finally` 保存 `target/e2e-evidence/happy-path/{summary.json,failure-summary.json,...}`；活动 shell=0，pytest/pip/tmux=0 |
| 2026-10-09 | `mvn -pl qingling-team-domain,qingling-team-infra,qingling-team-starter -am -Dtest=EventServiceTest,DeterministicWorkflowToolsTest,RealTeamE2EDriverTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（18 tests） | 验证真实 AgentScope 形态的 JSON 字符串可被恢复为 Map 后通过门禁并结构化落盘；拒绝事件不可由通用工具伪造；Driver 对连续拒绝直接报告原因，并对活跃 dispatch/更新 task_done 不误报 |
| 2026-10-09 | `mvn verify` | 通过（210 tests） | domain 43、app 30、infra 91、adapter 13、starter 33；0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-09 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + AgentScope + NoCallModel；结束后活动 shell=0，pytest/pip/tmux=0；未调用百炼、未进入 E2E |
