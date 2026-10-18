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
| `McpProjectTestRunner` | 大输出终态查询 | `FINISHED/exit_code=1` + 100 KiB pytest stdout | fake MCP 截断形态 + 真实 Docker | 小型元数据不受输出截断影响，返回 `TEST_FAILED/PYTEST_FAILURE`；输出仅保留脱敏首尾摘录，circuit 不打开 |
| `McpProjectTestRunner` | 终态写入竞态 | `FINISHED` 已可见但 `exit_code` 暂不可见 | fake MCP 元数据 | 视为 FINALIZING 并继续轮询，不产生 `RESULT_UNAVAILABLE` incident |
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
| `SerialDispatchRegistry` | 悬挂诊断快照 | 同路由一项执行中、一项排队 | fake clock/controllable executor | 只记录真实执行的 `active_since`，排队等待不提前计时，终止后快照归零 |
| `AgentScopeAgentGateway` | 父调用取消 | 父 Future 被取消且存在非终态子任务 | fake runtime + 真实 Harness TaskRepository | 取消钩子只执行一次；真实仓库子任务进入 CANCELLED，并 interrupt 父 Agent |
| `TeamAgentFactory` | 兼容旧阻塞参数 | 运行中子任务 + `task_output(block=true, timeout=600000)` | 真实 HarnessAgent + TaskRepository | 1 秒测试上限内返回当前状态；底层子 Future 保持运行，不等待 600 秒 |
| `AgentScopeAgentGateway` | 子任务取消后父回合悬挂 | 全部子任务 terminal 且包含 CANCELLED，父 Mono 永不结束 | fake runtime + 毫秒级轮询/宽限 | 父 Future 明确失败、取消只传播一次、active execution 清空 |
| AgentScope execution 上下文 | new_mail 唤醒并启动测试 | project/role/stage 在异步期间变化 | RuntimeContext + controllable runner | 继承 project ID，并以工具调用开始时冻结的 project/role/stage 注册 execution |
| `RuntimeLifecycle` | 关闭顺序 | 有在途 Agent 与 Cron | ordered fakes | Listener/Cron 停止先于 drain，最后关闭 Gateway |
| E2E Driver | 工具终态失败 | workflow incident | 临时证据目录 | 快速失败并指出上游工具原因，不报假 `qa/test_plan` 根因 |
| E2E Driver | AgentScope 子任务终态但 dispatch 悬挂 | 子任务 FAILED/CANCELLED，父路由持续 active | fake clock + 诊断快照 | 宽限 20 秒后报告直接悬挂原因；子任务仍运行时不误报 |
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
8. 覆盖一次超过 MCP 响应上限的 pytest 业务失败，验证 `exit_code=1` 不被误判为基础设施故障。
9. 失败时禁止进入真实模型 E2E。

## 4. 真实 E2E

原始基线配置为 `qwen3-max`。经用户确认，2026-10-11 当前单次运行使用以下进程级显式覆盖，未打印或改写环境文件：

```bash
QINGLING_FREE_MODEL_SELECTION_ENABLED=false
QINGLING_AGENT_MODEL=qwen3.8-flash
QINGLING_SUB_AGENT_MODEL=qwen3.8-flash
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
| 2026-10-10 | `mvn -pl qingling-team-infra -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ProjectTestAgentToolTest,McpProjectTestRunnerTest,TaskCompletionEvidenceGateTest,DeterministicWorkflowToolsTest,RoleToolkitFactoryTest,TeamAgentFactoryTest test` | 通过（30 tests） | 验证 execution ID 必须绑定当前 project/role/stage 和真实终态；模型回报的状态、退出码、pytest、覆盖率、失败类型及可重试性必须逐字段匹配；超时/传输故障最多恢复一次；QA 缺少独立契约时不能借用 RD 测试通过 |
| 2026-10-10 | 首次加强后的 `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 失败，未进入 E2E | 真实发现 RD 代码测试与 QA 契约都名为 `test_contract.py` 时，pytest 默认导入模式发生 module import mismatch；这是测试收集基础设施问题，不是业务失败。改为固定 `--import-mode=importlib`，未放宽断言 |
| 2026-10-10 | `mvn verify` | 通过（212 tests） | domain 43、app 30、infra 93、adapter 13、starter 33；0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-10 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；连续 10 次 Shell、5 次结构化测试、一次受控连接失效/重建全部通过，QA 契约独立执行，结束后无 pending tool 和 pytest/pip/tmux 孤儿进程；未调用百炼 |
| 2026-10-10 | E2E 启动诊断（未形成场景执行结果） | 当前显式模型前置不可用，诊断后人工中止 | 兼容端点对配置的 `qwen3-max` 返回 HTTP 404 `model_not_found`；只记录模型名和状态分类，未记录 API Key、鉴权 Header 或响应正文。该结果证明当前密钥/区域/端点/模型组合不可用，不代表模型全局下线；未进入业务工作流，未启动后续场景 |
| 2026-10-10 | 首次新增显式模型预检后的 `mvn verify` | 失败（配置回归，未进入 E2E） | 显式模型预检最初无条件启用，导致使用假密钥的 `RuntimeBootstrapTest` 发起网络探测；改为默认关闭、仅由 E2E 驱动强制开启，保持普通启动和默认回归无网络副作用 |
| 2026-10-10 | `mvn -pl qingling-team-starter -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DashScopeModelSelectorTest,RuntimeBootstrapTest,RealTeamE2EDriverTest test` | 通过（16 tests） | 验证普通显式模式不探测、E2E 显式模式对不可用/缺少 Function Calling 快速失败、启动异常保存脱敏 `summary.json` 与 `failure-summary.json` |
| 2026-10-10 | 最终 `mvn verify` | 通过（215 tests） | domain 43、app 30、infra 93、adapter 13、starter 36；0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-10 | 最终 `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；连续 10 次 Shell、5 次结构化测试、一次受控断线恢复，且无 pending tool 或 pytest/pip/tmux 孤儿进程；未调用百炼、未进入 E2E |
| 2026-10-11 | `mvn -pl qingling-team-infra -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ProjectTestAgentToolTest,DeterministicWorkflowToolsTest test` | 通过（16 tests） | 验证重连后同一 execution ID 已取消时仍上报 attempt=2、`retryable=false` 并触发 Java BLOCKED；另验证取消锁定进程前 pytest 已完成时保留真实成功终态，不产生虚假 incident；不调用模型 |
| 2026-10-11 | `mvn verify` | 通过（217 tests） | domain 43、app 30、infra 95、adapter 13、starter 36；0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-11 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；连续 10 次 Shell、5 次结构化测试、一次受控断线恢复全部通过。外部复核 AIO 活动会话为 0，未发现 pytest/pip/tmux 进程；未进入 E2E |
| 2026-10-11 | `mvn -Pe2e -De2e.scenario=happy-path verify` | 失败（1 E2E，0 failures、1 error、0 skipped），未启动后续场景 | 未打印环境文件或凭据；显式配置检查通过后，E2E Function Calling 启动预检对 `qwen3-max` 收到 HTTP 404，Spring 在进入业务工作流前快速失败。脱敏 `summary.json` 与 `failure-summary.json` 均记录 `configured_model=qwen3-max`、`failure_type=IllegalStateException` 和 `status=http_404`；AIO 活动会话为 0，未发现 pytest/pip/tmux 进程 |
| 2026-10-11 | 一请求最小 Function Calling 探针（`qwen3.8-flash`） | 通过（仅 1 请求） | HTTP 200，返回预期 `qingling_model_probe` 工具调用；未启动 E2E、未重试，未记录密钥或鉴权 Header |
| 2026-10-11 | `QINGLING_AGENT_MODEL=qwen3.8-flash QINGLING_SUB_AGENT_MODEL=qwen3.8-flash mvn -Pe2e -De2e.scenario=happy-path verify` | 失败（1 E2E，1 failure、0 errors、0 skipped；Maven 44 分 29 秒），未启动后续场景 | `model-selection.json` 为 `selected_model=qwen3.8-flash`、`selection_mode=explicit`、`available`。workflow 到达 `CODE_IMPLEMENTATION/RD`；AgentScope 的 `agent_spawn`、`agent_send`、`task_output` 多次 5 分钟超时，但子任务仍继续写文件。execution `44068f07-e3ca-470d-bfee-aae24e73eeef` 终态 `FINISHED/exit_code=5`，pytest 收集 0 项；task_done 因 execution 归属不匹配被拒绝，且没有 `tool_execution_failed`/BLOCKED。最终直接测试错误仍是等待 `code/main.py` 30 分钟超时。`finally` 保存脱敏证据；AIO session=0，pytest/pip/tmux 与宿主 Maven/Java 均无残留；没有自动重试 |
| 2026-10-11 | `mvn -pl qingling-team-app,qingling-team-infra,qingling-team-starter -am -Dtest=SerialDispatchRegistryTest,RunnerTest,AgentScopeAgentGatewayTest,ProjectTestAgentToolTest,TeamAgentFactoryTest,RealTeamE2EDriverTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（37 tests） | 覆盖异步工具转后台阈值上限、父调用取消钩子幂等、active execution 脱敏诊断、new_mail project ID 传播、测试 stage 启动时冻结、dispatch `active_since` 和 Driver 悬挂宽限；未调用模型 |
| 2026-10-11 | `mvn -pl qingling-team-infra -am -Dtest=TeamAgentFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（4 tests） | 使用真实 HarnessAgent 与 TaskRepository 放入已接管子 Future，调用 `clearSession` 后验证 BackgroundTask 为 CANCELLED 且底层 Future 已取消；证明父取消可真实级联到 AgentScope 子任务 |
| 2026-10-11 | 最终 `mvn verify` | 通过（223 tests） | domain 43、app 31、infra 99、adapter 13、starter 37；0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-11 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；连续 10 次无副作用 Shell、5 次结构化测试和一次受控连接失效/恢复全部通过，结束后无 pending tool 或 pytest/pip/tmux 孤儿进程；本轮未运行 E2E |
| 2026-10-11 | `QINGLING_AGENT_MODEL=qwen3.8-flash QINGLING_SUB_AGENT_MODEL=qwen3.8-flash mvn -Pe2e -De2e.scenario=happy-path verify`（父子生命周期修复后，仅一次） | 失败（1 E2E，1 failure、0 errors、0 skipped；场景 1021 秒，Maven 17 分 31 秒），未启动后续场景 | `model-selection.json` 确认为显式 `qwen3.8-flash` 且免费选择关闭。workflow 完成 checkpoint、产品设计和技术设计，进入 `CODE_IMPLEMENTATION/RD`；30 秒 async-tool 卸载生效，但后台 `task_output` 仍触发 AgentScope PT5M 超时。两个真实 `code_impl` 子任务被级联取消为 CANCELLED，父 `team:rd` dispatch 仍为 active/pending=1；Driver 直接报告 `不可恢复 AgentScope dispatch 悬挂`，没有伪装为下游文件超时。无代码文件、无 Test Runner execution。`finally` 保存脱敏 summary/failure-summary/runtime snapshot；清理后 AIO session=0，pytest/pip/tmux 与宿主 Maven/Java 均无残留；没有重试 |
| 2026-10-11 | `mvn -pl qingling-team-infra -am -Dtest=AgentScopeAgentGatewayTest,TeamAgentFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（10 tests） | 新增验证 `task_output(block=true, timeout=600000)` 仍立即返回，以及子任务全部 CANCELLED 后父 Future 失败、取消仅传播一次、active execution 清空；未调用模型 |
| 2026-10-11 | 修复后 `mvn verify` | 通过（225 tests） | domain 43、app 31、infra 101、adapter 13、starter 37；0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-11 | 修复后 `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；连续 10 次 Shell、5 次结构化测试及一次受控断线恢复通过；未运行 E2E |
| 2026-10-11 | Task 19 打包回归修复后 `mvn verify` | 通过（225 tests） | Spring Boot repackage 改用 `exec` classifier；普通 starter JAR 含根路径 `workspace-template/file-manifest.txt`，`*-exec.jar` 的 `Main-Class` 为 JarLauncher、`Start-Class` 为 `QinglingTeamApplication`；0 failures、0 errors、0 skipped |
| 2026-10-11 | Task 19 打包回归修复后 `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT -Dtool.contract.workspace="$(pwd)/target/e2e-workspace" verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + AgentScope + NoCallModel；重新证明模板资源可由 Failsafe 普通 classpath 加载并完成压力契约，未调用百炼 |
| 2026-10-11 | `mvn -Pe2e -De2e.scenario=happy-path verify`（后台查询修复后，仅一次） | 失败（1 E2E，1 failure、0 errors、0 skipped；场景 190.2 秒，Maven 3 分 19 秒），未启动后续场景 | 显式 `qwen3.8-flash` 可用且免费选择关闭。workflow 完成需求、checkpoint 请求/批准并迁移到 `PRODUCT_DESIGN/PM`；PM 首次 `sandbox_execute_bash` 将宿主机 `qingling-team-starter` 目录作为沙盒 working directory，AIO-Sandbox 返回目录不存在。当前 MCP 健康层将确定性的调用参数错误计为基础设施失败并使该 PM client 的 circuit OPEN；Driver 直接报告 `不可恢复 MCP 故障：circuit 持续 OPEN`，没有等待下游文件超时。`finally` 保存脱敏 summary/failure-summary/model-selection/项目证据；外部复核 AIO session=0，宿主与容器均无 pytest/pip/tmux/Maven/Java 残留；没有自动重试 |
| 2026-10-12 | `mvn -pl qingling-team-infra,qingling-team-starter -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=McpSandboxConfigurationTest,RuntimeBootstrapTest test` | 通过（15 tests） | 验证宿主 workspace 子路径映射、Maven `user.dir → /workspace`、ToolUse content 同步，以及真实形态 `Working directory does not exist` 连续两次 ERROR 后 circuit 仍 CLOSED、失败计数仍为 0；启动配置回归通过 |
| 2026-10-12 | `mvn verify` | 通过（227 tests） | 六模块全部 SUCCESS，0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-12 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT -Dtool.contract.workspace="$(pwd)/target/e2e-workspace" verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；10 次 Shell 均故意传入宿主 Maven `user.dir` 并断言实际 `pwd=/workspace`，5 次结构化 pytest 和一次受控连接恢复通过。AIO session=0，宿主与容器均无 pytest/pip/tmux/Maven/Java 残留；未运行 E2E |
| 2026-10-12 | `mvn -Pe2e -De2e.scenario=happy-path verify`（cwd/熔断修复后，仅一次） | 失败（1 E2E，1 failure、0 errors、0 skipped；场景 2589 秒，Maven 43 分 18 秒），未启动后续场景 | 显式 `qwen3.8-flash` 探针可用。workflow 完成 checkpoint、PM 产品设计、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；宿主 cwd 归一化已生效，四个 MCP circuit 全程 CLOSED/0 failures。RD 生成应用与测试，但三个 `code_impl` 子任务均进入 CANCELLED；父 dispatch 正确终止并把 RD 邮件恢复为 unread。一次 heartbeat 自动恢复再次得到相同 CANCELLED，未形成 `tool_execution_failed`/BLOCKED，RD 邮件未被重新领取，最终仍以等待 `qa/test_plan.md` 30 分钟超时失败。证据保存于 `target/e2e-evidence/happy-path`；退出后宿主和容器均无 pytest/pip/tmux/Maven/Java 残留，没有启动第二次 E2E |
| 2026-10-14 | `mvn -pl qingling-team-starter -am -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RunnerTest,MailboxLeaseWatchdogTest,RoleToolkitFactoryTest,TeamAgentFactoryTest,AgentScopeAgentGatewayTest,DeterministicWorkflowToolsTest,RealTeamE2EDriverTest test` | 通过（56 个定向测试） | 覆盖最新 continuation 权威判定、MODEL_REQUESTED/运行时取消来源、父 dispatch 类型化失败、路由与唯一 heartbeat incident 归属、第一次恢复/第二次 BLOCKED/重复 occurrence 幂等、lease 释放、BLOCKED watchdog 隔离、Cron 直接失败及证据脱敏；未调用模型、未运行 E2E |
| 2026-10-14 | `mvn verify` | 通过（245 tests） | domain 44、app 36、infra 109、adapter 13、starter 43；0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-14 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；连续 10 次无副作用 Shell、5 次 `run_project_tests` 和一次受控连接失效/恢复全部通过，结束时没有 pending tool 或 pytest/pip/tmux/AIO 孤儿会话；未运行真实 E2E |
| 2026-10-15 | `mvn -Pe2e -De2e.scenario=happy-path verify`（取消恢复闭环修复后，仅一次） | 失败（1 E2E，1 failure、0 errors、0 skipped；场景 1786 秒，Maven 29 分 55 秒），未启动后续场景 | 显式 `qwen3.8-flash`、免费模型选择关闭；workflow 完成 checkpoint、PM、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`。RD 使用一个 spawn 和终态后的 continuation，无主动取消；watchdog 将 stale assignment 恢复为 unread 并安排 wake。execution `84eab81b-ec29-47ef-9f98-fc3f9246eb03` 的容器状态文件最终为 `FINISHED/exit_code=1`，pytest 32 collected、11 passed、21 failed、coverage 72%，但工具返回 `INFRASTRUCTURE_FAILED/RESULT_UNAVAILABLE/exit_code=null`，并写入 attempt 1 RECOVERING、attempt 2 BLOCKED。Driver 直接报告 tool/execution/failure/stage/owner，没有等待 QA 文件超时。`finally` 保存脱敏证据；退出后宿主无 Maven/E2E，容器无 pytest/pip/tmux；没有自动重试 |
| 2026-10-15 | `mvn -pl qingling-team-infra -am -Dtest=McpProjectTestRunnerTest,ProjectTestAgentToolTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（15 tests） | 覆盖终态元数据与输出分离、`FINISHED/exit_code=1` 稳定分类为 `TEST_FAILED/PYTEST_FAILURE`、输出响应不含终态字段时仍保留业务结果，以及 FINALIZING 竞态不制造 `RESULT_UNAVAILABLE`；未调用模型 |
| 2026-10-15 | `mvn verify` | 通过（247 tests） | domain 44、app 36、infra 111、adapter 13、starter 43；0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-15 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT，15.45 秒） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；10 次无副作用 Shell、原有 5 次成功 `run_project_tests`、1 次生成 100 KiB stdout 的 `exit_code=1` 业务失败、一次受控连接恢复全部通过。大输出失败返回 `TEST_FAILED/PYTEST_FAILURE`，不含 `RESULT_UNAVAILABLE/INFRASTRUCTURE_FAILED`，所有 circuit 保持 CLOSED；契约结束后无 pytest/pip/tmux 孤儿进程。本轮未运行 E2E |
| 2026-10-17 | `mvn -Pe2e -De2e.scenario=happy-path verify`（Test Runner 终态修复后，仅一次） | 失败（1 E2E，1 failure、0 errors、0 skipped；场景 2468 秒，Maven 41 分 17 秒），未启动后续场景 | 显式 `qwen3.8-flash`、免费模型选择关闭；workflow 完成 checkpoint、PM 产品设计、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`。首个 `code_impl` task COMPLETED/delivered，但父 RD 在其完成前登记同 agent/session 的第二个 task；第二个 task 日志与产物停止更新、状态却长期 RUNNING，父 dispatch active/pending=1，且无 workflow incident、MCP/cron failure 或 hanging_dispatch。Driver 最终仍报告等待 `code/main.py` 30 分钟超时。未产生 `run_project_tests` execution，因此 Test Runner `exit_code=1` 修复未在本场景走到。finally 保存脱敏证据；退出后宿主无 Maven/Java、容器无 pytest/pip/tmux，Docker healthy，但持久化任务仓库仍遗留 RUNNING/cancelRequested=false/delivered=false 记录；没有自动重试 |
| 2026-10-17 | `mvn -pl qingling-team-infra -am -Dtest=AgentScopeAgentGatewayTest,TaskSemanticsTrackingToolTest,TeamAgentFactoryTest,RoleToolkitFactoryTest,TaskCompletionEvidenceGateTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（30 tests） | 覆盖稳定指纹不续租/真实变化续租、label 与真实 taskId-only spawn 后未知 agent_key 两种同 session continuation 去重、RUNNING 租约过期触发 `CHILD_STALLED`、唯一 Python 入口写入与完成门禁、Gateway shutdown 遗留任务持久化终态 |
| 2026-10-17 | `mvn -pl qingling-team-starter -am -Dtest=RealTeamE2EDriverTest,WorkspaceTemplateContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（30 tests） | 覆盖 Driver 对 RUNNING 无进展 task 的直接诊断、`last_progress_at` 脱敏证据，以及 RD 模板的 `code/main.py` 唯一入口契约 |
| 2026-10-17 | `mvn -pl qingling-team-infra -am -Dtest=DeterministicWorkflowToolsTest -Dsurefire.failIfNoSpecifiedTests=false test` | 通过（10 tests） | 补齐唯一入口门禁后的 RD/QA 状态机夹具，验证收件人、完成证据与阶段推进仍保持原语义 |
| 2026-10-17 | `mvn verify` | 通过（255 tests） | domain 44、app 36、infra 118、adapter 13、starter 44；0 failures、0 errors、0 skipped；未加载 E2E profile、未调用百炼 |
| 2026-10-17 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过（1 IT，15.25 秒） | 真实 Docker AIO-Sandbox + RD/QA AgentScope + NoCallModel；0 failures、0 errors、0 skipped。既有连续 Shell、结构化 Test Runner、受控断线恢复和孤儿进程断言全部通过；本轮未运行真实 E2E |
