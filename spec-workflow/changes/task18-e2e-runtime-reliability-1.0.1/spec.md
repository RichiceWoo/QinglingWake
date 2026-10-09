# Task 18 E2E 运行时可靠性修复 1.0.1

> status: apply
> created: 2026-10-07
> complexity: 🔴复杂
> change-dir: `spec-workflow/changes/task18-e2e-runtime-reliability-1.0.1/`

## 1. 背景与目标

`python-to-java-cola-migration` 的 Task 18 已完成状态机、RD/QA 完成硬门禁、固定目录 `run_project_tests(project_id)`、MCP 工具继承和零模型真实工具契约，但真实 `happy-path` 仍连续失败。

2026-10-07 的两次干净运行表明：模型能够完成 checkpoint、PM、RD 技术设计并进入代码实现；第二次运行还把 12 项 pytest 中的 8 项失败修复到只剩 1 项。随后 AIO-Sandbox MCP 命令通道无响应，`run_project_tests` 和简单 Shell 都在 5 分钟后超时，而容器内没有仍在运行的 pytest/pip 子进程。运行时没有可靠的执行状态查询、连接熔断/重建、Java 侧工具故障状态迁移和及时失败机制，最终只表现为等待 `qa/test_plan.md` 超时。

本版本目标是：

1. 将沙盒命令从“单次不可追踪的 MCP 文本调用”提升为可分类、可观测、可恢复的结构化执行。
2. 工具超时或连接失效时，由 Java 状态机确定性保留当前阶段、恢复原 Owner 或进入 `BLOCKED`，不再依赖模型自行发送澄清请求。
3. 使 mailbox 租约、Cron、dispatch 和运行时关闭形成完整故障闭环，停止后不再产生新任务。
4. 在昂贵模型 E2E 前，以零模型真实 Docker 压力契约验证 MCP 连续调用可靠性。
5. 真实 E2E 发生基础设施故障时快速失败并保存直接原因；修复后重新完成 Task 18 的四条真实场景。
6. 修复完成后，把实现、测试、真实 E2E 结论和证据回写到 `python-to-java-cola-migration` 的 `spec.md`、`test-spec.md`、`log.md` 和 `tasks.md`。

## 2. 代码现状（Research Findings）

### 2.1 相关入口与链路

- `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/ProjectTestAgentTool.java` — `callAsync` 为原始 `sandbox_execute_bash` 构造固定 pytest 命令，只能等待一次返回，异常被压缩成普通工具错误文本。
- `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/McpSandboxConfiguration.java` — `applyTo` 为每个角色注册 MCP；`CompletedMcpAgentTool` 只把“Mono 已完成且有输出”的伪 `RUNNING` 归一为 `SUCCESS`，不能恢复真正的空输出 pending、传输超时或断连。
- `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/TeamAgentFactory.java` — RD/QA 注册 `run_project_tests`，启用 pending-tool recovery，异步工具默认等待上限为 300 秒。
- `qingling-team-app/src/main/java/cn/org/chris/wake/app/runner/SerialDispatchRegistry.java` — 按 routing key 串行化 dispatch，但没有“停止接收”、取消在途任务或按工具故障隔离队列的能力。
- `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/runtime/RuntimeLifecycle.java` — 当前先等待 dispatch 排空，之后才停止 Cron；排空期间 heartbeat/一次性 wake 仍可能继续创建工作。
- `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/persistence/mailbox/FileMailboxRepository.java` — 已实现 `resetStale`，但正式运行路径没有调用，超时 `in_progress` 邮件缺少 watchdog。
- `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/contract/RealSandboxToolContractIT.java` — 当前只证明启动后的单次真实目录探针成功，没有覆盖连续调用、结果丢失、连接重建和无孤儿进程。
- `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/e2e/RealTeamE2EDriver.java` — 按顺序等待产物文件，每个阶段可等待 30 分钟；没有同时检查 workflow incident、MCP 熔断或失效任务租约。

### 2.2 已验证有效的实现

- Java 状态机已能固定 checkpoint → PM → RD 技术设计 → RD 代码 → QA 设计/执行 → 交付，不再跳过 RD 代码实现。
- RD/QA 成功 `task_done` 已有测试证据硬门禁；本次失败没有被伪装为成功。
- `run_project_tests(project_id)` 已固定 `/workspace/shared/projects/{projectId}/code`，模型不能传宿主机路径或任意测试命令。
- Manager 澄清答复协议已经能在相同阶段重新唤醒原 Owner，但它只能处理已成功送达 Manager 的业务澄清，不能替代 Java 对工具超时的故障恢复。
- 显式模型选择有效，最近一次证据为 `qwen3-max`、`selection_mode=explicit`、免费模型选择关闭。

### 2.3 最近两次失败证据

- 第一次：RD 生成的 Python 相对导入导致 pytest 收集失败；随后 `sandbox_execute_bash`/`run_project_tests` 连续超时，并伴随百炼 TLS 握手异常。流程停在代码实现阶段。
- 第二次：首轮 12 项 pytest 有 8 项失败，RD 修复后只剩 `test_get_todos_with_items`；第三次 `run_project_tests` 超时，随后简单命令也无响应。超时时容器内没有 pytest/pip 子进程，说明主要问题是命令通道或响应关联失效，而不是测试仍在运行。
- 第二次诊断目录：`qingling-team/target/e2e-evidence/diagnostics/happy-path-20261007-attempt2/`。
- 遗留业务代码问题是相同 `created_at` 下排序不稳定，应以 `created_at DESC, id DESC` 提供稳定次序；该问题不能通过放宽断言掩盖。

### 2.4 发现与风险

1. 容器 `healthy`、TCP 8029 可达和一次工具探针成功，均不能证明 MCP 长时间连续调用可靠。
2. 当前工具结果没有 execution ID，传输超时后无法判断命令是否执行、是否完成或仅丢失响应；直接盲重试可能重复副作用。
3. `onErrorResume` 抹平异常细节，Java 工作流无法区分业务测试失败和基础设施失败。
4. stale mailbox 恢复能力只存在于仓储实现和测试，尚未形成运行时闭环。
5. shutdown 期间 Cron 仍可提交新任务，可能把一次 E2E 失败放大成数十分钟的关闭等待。
6. 如果 AIO-Sandbox 当前 MCP 工具不支持 execution ID/状态查询，需要增加受控的无状态 Test Runner 适配层；不得用宿主机任意 Shell 绕开沙盒边界。
7. 自动重试只允许用于具有稳定 execution ID 或已证明幂等的测试执行，不能盲目重放任意 Shell。
8. 后续真实运行还暴露出独立的完成协议断点：RD 的结构化 `task_done` 被 Java 证据门禁拒绝后，模型仍可单独调用 `mark_done` 关闭 `task_assign`，使状态机停在 `CODE_IMPLEMENTATION`、Owner 保持 RD，但 mailbox 与 dispatch 都为空。该问题不是 MCP 故障，必须由工具层阻止而非依赖提示词。

## 3. 功能点

- [x] 定义结构化沙盒执行结果：execution ID、状态、退出码、stdout/stderr、pytest 数量、覆盖率、错误分类、是否可重试。
- [x] 实现单项目串行、可超时终止、可查询结果的 `run_project_tests` 执行链。
- [x] MCP 超时后触发熔断，拒绝向失效会话继续投递工具调用，并能关闭、重建和探测连接。
- [x] 对已证明幂等的测试执行最多自动恢复一次；连续失败后明确进入不可重试状态。
- [x] Java 在 Agent/tool 边界记录结构化 `tool_execution_failed`/workflow incident，保持当前阶段并确定性恢复原 Owner。
- [x] 达到工具重试上限后将项目置为 `BLOCKED`，不得进入 QA 或交付。
- [x] 把 mailbox stale lease 恢复接入正式 watchdog，并避免活跃 dispatch 被错误回收。
- [x] 修正运行时关闭顺序，停止新入站/Cron 后再排空和取消 dispatch。
- [x] 扩展零模型真实工具契约为连续调用压力测试和断连恢复测试。
- [x] E2E 驱动同时监测阶段、incident、MCP 状态、mailbox 和 dispatch，遇到终态故障快速失败。
- [x] 成功或失败均在 `finally` 保存脱敏证据，失败摘要必须给出真实上游原因。
- [x] 流水线 `task_assign` 只能在合规 `task_done` 成功写入后由 Java 自动完成；门禁失败时禁止单独 `mark_done`，恢复任务并存时一次性收敛全部处理中分派。
- [ ] 完成四条真实 E2E 后回写父需求；未全部通过时不得标记父 Task 18 完成。

## 4. 业务规则

1. 业务测试失败（`exit_code != 0`）与基础设施失败必须分开表示；前者交给 RD 修复代码，后者由 Java 运行时恢复连接/任务。
2. MCP 工具超时后不得继续把同一连接当作健康连接使用。
3. 自动重试上限为 1 次；只有明确幂等或可以按 execution ID 找回原结果时才允许重试。
4. 工具失败时 workflow stage 不前进，当前 Owner 不改变。
5. 第一次可恢复基础设施故障：记录 incident、恢复工具连接、重新唤醒原 Owner。
6. 第二次同类故障：项目进入 `BLOCKED`，生成明确告警和失败证据，等待人工处理。
7. mailbox stale lease 只有在处理时间超限且对应角色/路由没有活跃 dispatch 时才能恢复为 `unread`。
8. shutdown 开始后禁止新的 dispatch、heartbeat 和一次性 wake 进入执行队列。
9. API Key、Header、完整连接 URL 查询参数不得出现在日志、异常、`summary.json` 或测试证据中。
10. E2E 不得 mock 模型或沙盒，不得放宽断言、跳过阶段或延长超时来掩盖失败。
11. 任一真实场景连续两次从干净目录失败后必须暂停，不启动第三次，并完整记录两次根因。
12. 当前真实 E2E 使用：`QINGLING_FREE_MODEL_SELECTION_ENABLED=false`、`QINGLING_AGENT_MODEL=qwen3-max`、`QINGLING_SUB_AGENT_MODEL=qwen3-max`；不得打印环境文件内容。
13. `task_done` 的证据门禁与原 `task_assign` 完成必须形成单向协议：先通过门禁并成功发送完成邮件，再由 Java 自动关闭任务；任何角色不得在完成回报失败后单独关闭流水线任务。

## 5. 数据变更

无数据库表变更。文件状态与证据可能增加以下结构：

| 操作 | 文件/对象 | 字段 | 说明 |
|------|-----------|------|------|
| 新增 | workflow event | `execution_id`、`tool`、`failure_type`、`retryable`、`attempt` | 记录脱敏工具故障 |
| 新增/扩展 | 项目状态 | `runtime_status`、`blocked_reason` 或等价字段 | 区分业务阶段和运行时阻断 |
| 扩展 | mailbox message | `attempt`、租约时间或等价字段 | 支持有限恢复与审计 |
| 新增 | E2E 证据 | `failure-summary.json` | 保存阶段、incident、mailbox、dispatch 与工具错误摘要 |

具体字段必须保持向后兼容；读取旧项目文件时不得失败。

## 6. 接口变更

不新增对外 REST/飞书业务接口。内部契约调整如下：

| 操作 | 接口/组件 | 变更内容 |
|------|-----------|----------|
| 扩展 | `run_project_tests(project_id)` | 返回结构化成功、业务失败和基础设施失败，不再只返回普通错误文本 |
| 新增/扩展 | 沙盒执行适配器 | 支持 execution ID、状态查询、取消、熔断和重建 |
| 扩展 | workflow signal/event | 接收工具执行失败与运行时阻断，不推进业务阶段 |
| 扩展 | `SerialDispatchRegistry` | 停止接收、活动查询、有限排空和取消 |
| 扩展 | 运行时健康快照 | 暴露脱敏 MCP 可用状态、连续失败次数和最近恢复时间 |

## 7. 影响范围

- `qingling-team-domain`：工具故障/阻断的领域状态或信号，mailbox 恢复契约。
- `qingling-team-app`：workflow incident 编排、原 Owner 恢复、dispatch 停止接收与取消。
- `qingling-team-infra`：MCP/Test Runner 适配、结构化测试工具、熔断/重连、文件状态兼容。
- `qingling-team-starter`：配置校验、watchdog、生命周期顺序、真实工具契约和 E2E 驱动。
- Docker AIO-Sandbox：可能增加受控 Test Runner 服务或执行状态端点；不得扩大宿主机目录/命令权限。
- Python 参考项目：只作为行为参考，不修改。
- 父需求文档：修复与验证完成后必须回写。

## 8. 风险与关注点

- ⚠️ 自动重试如果缺少 execution ID，可能重复执行有副作用的命令。
- ⚠️ 熔断器必须按 MCP server/session 隔离，不能让一个角色故障永久阻断所有项目。
- ⚠️ stale lease 恢复与仍在运行的 dispatch 存在竞争，必须原子判断或使用租约 token。
- ⚠️ 取消 Java Future 不一定会终止容器进程，必须验证进程组实际退出。
- ⚠️ 不能把 AIO-Sandbox 的健康接口当作命令通道健康证据。
- ⚠️ 真实 E2E 会消耗百炼额度，必须先完成默认回归和零模型压力契约。
- ⚠️ 证据保存必须脱敏，不得记录 DASHSCOPE API Key 或 MCP 鉴权 Header。

## 8.5 测试策略

- **测试范围**：结构化执行、错误分类、熔断/重建、workflow incident、mailbox lease、shutdown、E2E fail-fast、零模型真实 Docker 压力契约、四条真实模型 E2E。
- **覆盖率目标**：所有新增分支具备单元测试；P0 故障路径必须有集成或契约验证。
- **独立 Test Spec**：是，见同目录 `test-spec.md`。

## 9. 待澄清

- [x] 实现前先读取 AIO-Sandbox 当前 `sandbox_execute_bash` 的真实工具 schema 和服务能力，确认是否原生支持 execution ID、状态查询和取消；不得凭假设编码。
- [x] 若不支持上述能力，记录证据并采用受控无状态 Test Runner 适配层；接口仍须满足本 Spec 的结构化执行契约。

以上两项是实现路径调研，不改变已经确认的业务目标，不需要重新扩大需求范围。

## 10. 技术决策

| 决策 | 选项 | 原因 |
|------|------|------|
| 根因优先级 | 先修 MCP/运行时可靠性，再比较模型 | 第二次运行已从 8 失败修到 1 失败，工具通道才是阻断点 |
| 工具结果 | 结构化结果与错误分类 | 让状态机区分业务失败和基础设施失败 |
| 执行追踪 | execution ID + 可查询终态 | 避免响应丢失后盲重试 |
| 恢复策略 | 熔断、重建、幂等调用最多重试一次 | 防止失效会话被 heartbeat 持续放大 |
| 工作流故障 | Java 自动记录 incident 并恢复原 Owner | 不依赖模型在工具异常后自行发澄清 |
| 停机顺序 | 先停止生产新任务，再排空/取消消费者 | 防止 shutdown 期间继续产生工作 |
| 前置验证 | 零模型真实 Docker 连续工具契约 | 在消耗模型额度前发现通道耐久性问题 |
| 模型 | 暂时保持显式 `qwen3-max` | 当前证据不足以说明更昂贵模型能解决工具故障 |

## 11. 执行日志

| Task | 状态 | 实际改动文件 | 备注 |
|------|------|--------------|------|
| Spec 初始化 | 完成 | `spec.md`、`tasks.md`、`test-spec.md`、`log.md` | 依据 2026-10-07 两次 E2E 失败建立 1.0.1 修复需求 |
| Task 1 AIO-Sandbox 能力审计 | 完成 | `spec.md`、`tasks.md`、`test-spec.md`、`log.md` | 真实 MCP `initialize`/`tools/list`/`tasks/list` 与无副作用调用证明服务端为 2.14.7；`sandbox_execute_bash` 不支持 task-augmented execution，普通结果无 execution ID/查询/取消句柄，选用受控无状态 Test Runner 适配层 |
| Task 2 结构化 Test Runner | 完成 | `ProjectTestExecution.java`、`ProjectTestRunner.java`、`McpProjectTestRunner.java`、`ProjectTestAgentTool.java` 及对应测试 | Docker 内 execution ID 状态目录、固定 pytest、进程组超时/取消、结果查询、stdout/stderr 限制与脱敏、pytest/基础设施分类完成 |
| Task 3 MCP 熔断与有限恢复 | 完成 | `McpSandboxConfiguration.java`、`ProjectTestAgentTool.java`、`McpProjectTestRunner.java` 及对应测试 | OPEN 快速拒绝；真实关闭旧 AgentScope MCP client、重新注册并通过无副作用探针后 CLOSED；同一 execution ID 最多恢复一次，第二次失败不可重试 |
| Task 4 Java 工作流故障处理 | 完成 | `WorkflowSignal.java`、`WorkflowStateMachine.java`、`EventService.java`、`DeterministicWorkflowTools.java`、`RoleToolkitFactory.java`、`ProjectTestAgentTool.java`、`TeamAgentFactory.java` 及对应测试 | pytest 业务失败保持普通测试结果；基础设施故障记录幂等 `tool_execution_failed` incident、保持阶段和 Owner 并自动唤醒；第二次同类失败进入 BLOCKED，禁止推进 QA/交付；相关三模块 155 项测试通过 |
| Task 5 mailbox lease watchdog | 完成 | `MailboxLeaseWatchdog.java`、`SerialDispatchRegistry.java`、`RuntimeLifecycle.java`、`RuntimeConfiguration.java`、`MailboxLeaseWatchdogTest.java`、`Task5FileRepositoriesTest.java` | 正式运行时周期扫描项目与角色；在 dispatch 接纳锁内原子确认 `team:{role}` 空闲后恢复 stale 邮件并安排去重 wake；活跃路由不回收，重复 tick 不重复 wake，缺少 `processing_since` 的旧格式 unread 邮件可读取；相关模块 196 项测试通过 |
| Task 6 dispatch 与 shutdown | 完成 | `SerialDispatchRegistry.java`、`WakeScheduler.java`、`RuntimeLifecycle.java`、`SerialDispatchRegistryTest.java`、`WakeSchedulerTest.java`、`RuntimeLifecycleTest.java` | shutdown 顺序固定为关闭入站、停止 watchdog、禁止一次性 wake、停止 Cron、停止 dispatch 接纳、有限排空、超时取消、关闭 Agent/MCP；取消会传播到活跃 Future 并阻止排队供应器启动；相关模块 201 项测试通过 |
| Task 7 零模型压力契约与 E2E 诊断 | 完成 | `RealSandboxToolContractIT.java`、`RealTeamE2EDriver.java`、`RealTeamE2EDriverTest.java`、`McpSandboxConfiguration.java`、`McpProjectTestRunner.java`、`ProjectTestAgentTool.java`、`sandbox-docker-compose.yaml`、`pom.xml`、`E2E.md` 及对应测试 | 通用 Bash 强制 `new_session=false`，外层取消会打开熔断；关闭时先移除 AgentScope MCP client，再调用 AIO-Sandbox 1.11.0 原生 `DELETE /v1/shell/sessions`。E2E 专用容器禁用 shell 预热池，干净镜像的 pytest 依赖由固定 Docker 参数受控预置。`mvn verify` 通过 206 项；最终真实契约 1 IT 通过，完成 10 次 Shell、5 次 pytest、1 次连接恢复，NoCallModel 为 0，关闭后活动 shell 与 pytest/pip/tmux 均为 0 |
| Task 8 前置修复 | 修复完成；等待再次确认重测 | `EventService.java`、`CommonTeamTools.java`、`DeterministicWorkflowTools.java`、`RealTeamE2EDriver.java` 及对应测试 | Java 边界兼容真实 AgentScope 的 task_done JSON 字符串并统一为 Map；完成门禁拒绝写入保留事件，Driver 对同阶段连续拒绝且 dispatch 空闲的情况直接报告原因。定向 18 项、默认 210 项和零模型真实 Docker 1 IT 均通过；按用户要求未启动新 E2E |

## 12. 审查结论

Task 1～7 已完成验证；Task 8 最近一次真实 `happy-path` 失败所暴露的 task_done 类型契约与 Driver fail-fast 问题已经修复，并通过定向、完整默认回归和零模型真实 Docker 契约。按用户要求本轮没有启动新 E2E；当前仍不得宣称 Task 18 已通过，Task 8、Task 9 与父需求 Task 18 必须保持未完成，等待用户再次确认后从干净 `happy-path` 开始。

## 13. 确认记录（HARD-GATE）

- **确认时间**：2026-10-07
- **确认人**：用户
- **确认说明**：用户要求按既有 E2E 根因分析建立独立 `1.0.1` 修复需求，并在新聊天窗口执行；当前聊天只生成 Spec 和交接话术，不实施源码修复。
