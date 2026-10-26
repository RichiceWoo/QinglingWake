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
9. AgentScope 2.0.3 内置 `task_output` 默认阻塞 30 秒，并允许模型把等待参数提高到 600 秒；后台卸载后仍受核心 PT5M ToolExecutor 上限约束，因此不能仅靠缩短异步卸载阈值解决。
10. `task_cancel` 只改变 Sub-Agent 任务终态，不会使仍在执行的父 Agent `Mono` 结束；若父模型不再返回，`SerialDispatchRegistry` 会持续保持 active/pending，必须由 Java 根据同 session 子任务终态主动终止父调用。

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
- [x] 构建 Harness 后用同名无阻塞实现替换内置 `task_output`；即使模型传入 `block=true` 或 600 秒 timeout，也只读取一次当前快照。
- [x] 当前 session 的全部子任务进入终态且存在 FAILED/CANCELLED 时，Java 在短宽限后终止父调用、级联清理并释放 dispatch。
- [x] Test Runner 将小型终态元数据与 stdout/stderr 摘录分离查询；大段 pytest 输出不得截断 `exit_code`，`FINISHED/exit_code=1` 必须稳定返回 `TEST_FAILED/PYTEST_FAILURE`。
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
12. 原始真实 E2E 基线使用 `qwen3-max`；经用户确认，2026-10-11 当前单次运行以进程级覆盖切换为 `QINGLING_FREE_MODEL_SELECTION_ENABLED=false`、`QINGLING_AGENT_MODEL=qwen3.8-flash`、`QINGLING_SUB_AGENT_MODEL=qwen3.8-flash`。不得打印环境文件内容，也不得把凭据写入证据。
13. `task_done` 的证据门禁与原 `task_assign` 完成必须形成单向协议：先通过门禁并成功发送完成邮件，再由 Java 自动关闭任务；任何角色不得在完成回报失败后单独关闭流水线任务。
14. `task_output` 是只读状态查询，不得接受模型参数重新引入分钟级阻塞；长任务等待由异步通知和后续非阻塞查询完成。
15. 同一父 session 中所有子任务均已终止且至少一个失败或取消时，父 dispatch 不得无限占用串行队列；父 Agent 未在宽限期内自行结束时由运行时主动失败并取消。

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
| 扩展 | `SerialDispatchRegistry` | 停止接收、活动查询、真实执行起始时间快照、有限排空和取消 |
| 扩展 | AgentScope 父子执行生命周期 | 父调用取消时取消同 session 的非终态子任务，再 interrupt 父 Agent；上下文携带 project/role/stage，诊断快照只暴露脱敏状态 |
| 替换 | `task_output` | 保留原工具名及兼容参数，但强制使用 `block=false` 的单次状态读取，消除 30 秒/600 秒内置等待与 PT5M 冲突 |
| 扩展 | `AgentScopeAgentGateway` | 并行监控同 session 子任务；全部终态且含 FAILED/CANCELLED 时，在 2 秒收尾宽限后结束父调用并释放 dispatch |
| 新增 | `async-tool-offload-timeout` | 将异步工具转后台阈值与业务 Agent 总等待时长解耦；默认 30 秒且强制小于 AgentScope 固定 5 分钟 ToolExecutor 上限 |
| 扩展 | 运行时健康快照 | 暴露脱敏 MCP 可用状态、连续失败次数、最近恢复时间、AgentScope 子任务终态和 dispatch 活跃时长 |

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
| 终态查询 | 小型元数据先行，stdout/stderr 分别读取受限首尾摘录 | 防止大段 Base64 输出使 MCP 响应截断 `status/exit_code`；输出不可用也不得覆盖已持久化的 pytest 业务终态 |
| 模型 | 当前单次 E2E 显式使用 `qwen3.8-flash` | 最小 Function Calling 探针已证明模型可用；是否能完成全流程必须由真实 E2E 验证，不能用模型切换掩盖运行时故障 |

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
| Task 8 根因复审与二次加固 | 代码修复完成；按用户要求暂停 E2E | `ProjectTestExecutionRegistry.java`、`McpProjectTestRunner.java`、`ProjectTestAgentTool.java`、`RoleToolkitFactory.java`、`TaskCompletionEvidenceGate.java`、`DeterministicWorkflowTools.java`、QA 模板、`RealSandboxToolContractIT.java`、`DashScopeModelSelector.java`、`RealTeamE2EDriver.java`、`E2E.md` 及对应测试 | 将 execution ID 与 project/role/stage 的真实终态绑定；门禁逐字段核对模型回报；QA 只运行真实 QA 契约，RD 在缺陷回修阶段一并运行该契约；所有基础设施终态（含超时）都进入一次受控重建，重连后若同一执行已取消或仍为基础设施终态，也会上报第二次不可重试 incident 并进入 BLOCKED；取消与 pytest 完成竞态时保留查询到的真实业务终态，不伪造超时；pytest 使用 importlib 模式消除跨目录同名模块冲突；E2E 启动前有界验证显式模型并保存脱敏启动失败证据。最新恢复定向 16 项通过，最终 `mvn verify` 通过 217 项，零模型真实 Docker 1 IT 通过 |
| Task 8 单次 E2E（2026-10-11） | 未完成；启动预检失败 | `target/e2e-evidence/happy-path/summary.json`、`target/e2e-evidence/happy-path/failure-summary.json`、`test-spec.md`、`tasks.md`、`log.md` | 仅运行一次干净 `happy-path`；显式配置校验通过，但 `qwen3-max` Function Calling 预检返回 HTTP 404。Failsafe 为 1 test、0 failures、1 error、0 skipped，工作流与后续三个场景均未启动；没有自动重试。清理后活动 shell=0，pytest/pip/tmux=0 |
| Task 8 `qwen3.8-flash` 单次 E2E（2026-10-11） | 未完成；代码阶段超时 | `target/e2e-evidence/happy-path/{summary.json,failure-summary.json,model-selection.json,project/events.jsonl}`、`target/e2e-workspace/.qingling-test-runs/44068f07-e3ca-470d-bfee-aae24e73eeef`、`tasks.md`、`test-spec.md`、`log.md` | 最小 Function Calling 探针与 E2E 启动预检均通过，真实 workflow 完成 checkpoint、PM 和 RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`。`agent_spawn`、`agent_send`、`task_output` 多次在 AgentScope 外层 5 分钟超时，子任务却继续写入 `code/app` 与 `code/tests`；唯一 Test Runner 执行过早启动，终态 `FINISHED/exit_code=5/pytest_collected=0`，随后 task_done 因 execution 归属不匹配被拒绝。运行时未生成 `tool_execution_failed`/BLOCKED，Driver 最终仍以等待 `code/main.py` 30 分钟超时报错；整轮 Maven 44 分 29 秒。未重试、未启动其他场景；清理后 AIO session=0，pytest/pip/tmux 与宿主 Maven/Java 均无残留 |
| Task 8 AgentScope 父子生命周期修复（2026-10-11） | 代码与非 E2E 验证完成；等待真实 E2E | `TeamAgentFactory.java`、`AgentScopeAgentGateway.java`、`Runner.java`、`ProjectTestAgentTool.java`、`RoleToolkitFactory.java`、`SerialDispatchRegistry.java`、`QinglingTeamProperties.java`、`RuntimeConfiguration.java`、`application.yml`、`config.yaml.template`、`RealTeamE2EDriver.java` 及对应测试 | 将异步工具转后台阈值从 30 分钟业务等待中拆分为默认 30 秒、最大 4 分钟，保证早于 AgentScope 固定 5 分钟工具上限；父调用取消/clearSession/close 会按 session 取消真实 TaskRepository 非终态子任务并 interrupt 父 Agent；`new_mail` RuntimeContext 传播 project ID，测试 execution 在调用开始冻结 project/role/stage；dispatch 快照记录真实 active_since，Driver 对“子任务均终态且存在失败/取消、父 dispatch 仍活跃”持续 20 秒后直接报告悬挂。定向 37 项、真实 Harness 取消 4 项、最终 `mvn verify` 223 项和零模型真实 Docker 1 IT 均通过；未调用百炼、未运行 E2E |
| Task 8 `qwen3.8-flash` 修复后单次 E2E（2026-10-11） | 未完成；直接识别 AgentScope dispatch 悬挂 | `target/e2e-evidence/happy-path/{summary.json,failure-summary.json,model-selection.json,project/events.jsonl}`、RD AgentScope async-tool/task 状态、`tasks.md`、`test-spec.md`、`log.md` | 仅运行一次干净 `happy-path`，显式模型为 `qwen3.8-flash`。checkpoint、PM 产品设计和 RD 技术设计均完成并进入 `CODE_IMPLEMENTATION/RD`；30 秒异步卸载真实生效，但后台 `task_output` 自身仍触发 AgentScope 固定 PT5M 超时。级联取消将两个 `code_impl` 子任务置为 CANCELLED，却未结束父 `team:rd` dispatch；Driver 依据 `active_since` 和子任务终态在 1021 秒时直接失败，Maven 总耗时 17 分 31 秒，未再伪装为代码/QA 文件超时。没有生成代码文件或 Test Runner execution；未重试、未启动后三个场景。清理后 AIO session=0，pytest/pip/tmux 与宿主 Maven/Java 均无残留 |
| Task 8 打包门禁修复与单次 E2E（2026-10-11） | 未完成；PM 首次 Shell 调用触发熔断 | `qingling-team-starter/pom.xml`、`README.md`、`target/e2e-evidence/happy-path/{summary.json,failure-summary.json,model-selection.json,project/events.jsonl}`、`tasks.md`、`test-spec.md`、`log.md` | Task 19 的 Spring Boot repackage 曾替换普通 starter JAR，导致 Failsafe 无法从根路径读取 `workspace-template/file-manifest.txt`；改为 `exec` classifier 后普通 JAR 与可执行 JAR 分离，默认 `mvn verify` 225 项及零模型真实 Docker 1 IT 均通过。本轮随后只执行一次干净 `qwen3.8-flash happy-path`：checkpoint 正常批准并迁移到 `PRODUCT_DESIGN/PM`，但 PM 把宿主机 `qingling-team-starter` 目录作为沙盒 working directory 调用 `sandbox_execute_bash`，AIO-Sandbox 返回目录不存在；该调用参数错误被当前 MCP 健康层计为连接故障并将单个 PM client 的工具熔断器置为 OPEN。Driver 在场景 190.2 秒直接报告 `circuit 持续 OPEN`，未伪装成产物超时。未重试、未启动后三个场景；finally 证据显示所有 dispatch pending=0、AgentScope active=0，外部复核 AIO session=0 且无 pytest/pip/tmux/Maven/Java 残留 |
| Task 8 cwd 与熔断错误分类修复（2026-10-12） | 代码与非 E2E 验证完成；等待用户确认 | `McpSandboxConfiguration.java`、`RuntimeConfiguration.java`、`McpSandboxConfigurationTest.java`、`RealSandboxToolContractIT.java`、`tasks.md`、`test-spec.md`、`log.md` | 生产装配把宿主共享工作区和容器 `workspace-dir` 显式传给 MCP 包装层；`sandbox_execute_bash` 缺省 cwd 固定为容器挂载根，宿主 workspace 子路径按相对路径映射，AgentScope/Maven 的 `user.dir` 映射为容器根，且同步改写 ToolUse content。cwd/参数校验错误保持原始 ERROR 结果但不累计连接失败、不打开 circuit，真正异常、取消和无法识别的 ERROR 仍维持原熔断行为。定向 15 项、完整 `mvn verify` 227 项和零模型真实 Docker 1 IT 均通过；真实契约 10 次故意传宿主 cwd 的 Shell 全部在 `/workspace` 执行，5 次 pytest 与一次断线恢复通过，AIO session 和孤儿进程均为 0。本轮未运行 E2E |
| Task 8 cwd 修复后单次 E2E（2026-10-12） | 未完成；RD 取消恢复未进入 BLOCKED | `target/e2e-evidence/happy-path/{summary.json,failure-summary.json,model-selection.json,project,data}`、`tasks.md`、`test-spec.md`、`log.md` | 前置零模型真实 Docker 1 IT 通过后仅运行一次显式 `qwen3.8-flash happy-path`。workflow 正确完成 checkpoint、PM 与 RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；宿主 cwd 归一化真实生效，四个 MCP circuit 全程 CLOSED 且失败计数为 0。RD 生成应用及测试后，三个 `code_impl` 子任务均为 CANCELLED；父 dispatch 已按修复终止，mailbox lease 恢复为 unread。一次 heartbeat 自动恢复再次同类失败，但没有生成 `tool_execution_failed`/BLOCKED，也没有重新领取 RD 任务，最终仍等待 `qa/test_plan.md` 30 分钟超时。Maven 总耗时 43 分 18 秒；退出后无 pytest/pip/tmux/Maven/Java 残留，未启动第二次 E2E 或后续场景 |
| Task 8 RD 子任务取消恢复闭环（2026-10-14） | 代码与非 E2E 验证完成；等待用户确认 | `AgentExecutionException.java`、`RuntimeIncidentGateway.java`、`TaskSemanticsRegistry.java`、`TaskSemanticsTrackingTool.java`、`AgentScopeAgentGateway.java`、`Runner.java`、`DeterministicWorkflowTools.java`、`RoleToolkitFactory.java`、`MailboxLeaseWatchdog.java`、`FileMailboxRepository.java`、`RealTeamE2EDriver.java`、RD `AGENTS.md` 及对应测试 | AgentScope 工具层记录 spawn/send/cancel 语义及取消来源，同一 `agent_id` 仅以最新 continuation 为权威；最新任务失败或取消会形成脱敏类型化异常。Runner 将有 project 的路由失败和唯一可归属的 heartbeat 失败接入 Java incident；第一次失败释放 task lease、保持 stage/Owner 并唤醒原 Owner，第二次不同 occurrence 进入 BLOCKED，重复 occurrence 幂等。watchdog 跳过 BLOCKED 项目；Driver 观察 Cron、incident、mailbox、dispatch 和最新 continuation，运行时空闲但 Cron 已失败时立即给出直接原因。定向 56 项、默认 `mvn verify` 245 项及零模型真实 Docker 1 IT 全部通过；本轮未运行真实 E2E |
| Task 8 取消恢复修复后单次 E2E（2026-10-15） | 未完成；Test Runner 业务终态被误判为基础设施失败 | `target/e2e-evidence/happy-path/{summary.json,failure-summary.json,model-selection.json,project,data}`、`target/e2e-workspace/.qingling-test-runs/84eab81b-ec29-47ef-9f98-fc3f9246eb03`、`tasks.md`、`test-spec.md`、`log.md` | 仅运行一次显式 `qwen3.8-flash happy-path`。checkpoint、PM、RD 技术设计及进入代码实现均成功；单一 spawn、终态后 continuation、无模型取消、stale lease 恢复与新 wake 均真实发生。容器执行最终为 `FINISHED/exit_code=1`，pytest 32 collected、11 passed、21 failed、coverage 72%，属于应交给 RD 的业务失败；结构化工具却返回 `INFRASTRUCTURE_FAILED/RESULT_UNAVAILABLE/exit_code=null`，Java 随即按基础设施失败写入 RECOVERING 和 BLOCKED。Driver 立即报告直接 incident，未退化为 QA 文件超时。Failsafe 1 failure、0 errors、0 skipped，Maven 29 分 55 秒；未重试或启动后续场景，退出后无孤儿进程 |
| Task 8 Test Runner 终态协调修复（2026-10-15） | 代码与非 E2E 验证完成；等待用户确认 | `McpProjectTestRunner.java`、`McpProjectTestRunnerTest.java`、`RealSandboxToolContractIT.java`、本需求四份文档 | 根因是终态元数据与最高 32 KiB Base64 输出共用单条 MCP 响应，真实 93 KiB pytest 日志被通道截断后仍能解析部分 stdout，却丢失 `exit_code=1`。现先查询固定小型 status/exit/pytest/coverage 元数据，终态后再分别读取每流最多 1 KiB 的脱敏首尾摘录；`FINISHED` 暂缺退出码时视为 FINALIZING 继续轮询，输出摘录失败也不覆盖已知业务终态。定向 15 项、`mvn verify` 247 项、零模型真实 Docker 1 IT 全部通过；契约新增 100 KiB 失败输出并验证 `TEST_FAILED/PYTEST_FAILURE`、熔断保持 CLOSED。未运行 E2E |
| Task 8 Test Runner 修复后单次 E2E（2026-10-17） | 未完成；AgentScope continuation 悬挂且未快速诊断 | `target/e2e-evidence/happy-path/{summary.json,failure-summary.json,model-selection.json,project,data}`、`target/e2e-workspace/rd/team:rd/agents/rd/tasks/s-6878139fa793.json`、本需求四份文档 | 仅运行一次显式 `qwen3.8-flash happy-path`。workflow 完成 checkpoint、PM 产品设计、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`，MCP 四路始终 CLOSED/0 failures；本轮尚未产生 Test Runner execution，因此没有再次触发 `exit_code=1` 误判。首个 `code_impl` task `task_99b...` 最终 COMPLETED/delivered，父 RD 在其终态前又登记同 agent/session 的 continuation `task_84b...`；该 task 的真实子代理日志在 00:43:37 后停止，但运行时只刷新 `lastUpdatedAt` 并持续报告 RUNNING。Driver 未把“RUNNING 但无日志/产物进展”识别为悬挂，最终仍以等待 `code/main.py` 30 分钟超时失败。场景 2468 秒、Maven 41 分 17 秒，1 failure、0 errors、0 skipped；未重试或启动后三个场景。退出后无 Maven/Java、pytest/pip/tmux 进程，Docker healthy，但持久化任务仓库仍保留 `task_84b...` 为 RUNNING/cancelRequested=false/delivered=false，证明 shutdown 没有协调其终态 |
| Task 8 RUNNING continuation 可靠性修复（2026-10-17） | 代码与非 E2E 验证完成；等待用户确认 | `AgentExecutionException.java`、`TaskSemanticsRegistry.java`、`TaskSemanticsTrackingTool.java`、`AgentScopeAgentGateway.java`、`CommonTeamTools.java`、`TaskCompletionEvidenceGate.java`、`RealTeamE2EDriver.java`、RD 模板及对应测试 | 真实进度租约只由 TaskRecord 稳定状态/事件序号和子代理 JSONL 日志元数据变化续租，排除 `lastUpdatedAt`、`lastCheckedAt` 心跳；同 parent user/session/agent 的 spawn/send 以 TaskRepository 与原子占位做单飞，重复 continuation 返回既有 task，真实后台 spawn 只返回 taskId 时也能从 AgentScope 持久化 spawn registry 精确解析 agent_key/label；RUNNING 静默 5 分钟由 Gateway 抛出 `CHILD_STALLED` 并由 Driver 直接报告 task/agent/last_progress；RD 仅允许 `code/main.py` 为 Python 入口并拒绝 `code/app/main.py`；Gateway shutdown 遍历已观察 session，取消非终态任务并持久化为 CANCELLED/FAILED。定向 70 项、`mvn verify` 255 项、零模型真实 Docker 1 IT 全部通过；未运行 E2E |
| Task 8 RUNNING continuation 修复后单次 E2E（2026-10-18） | 未完成；COMPLETED 子任务后的父 dispatch/重复 assignment 未被快速诊断 | `target/e2e-evidence/happy-path/{summary.json,failure-summary.json,model-selection.json,project,data}`、`target/e2e-workspace/.qingling-test-runs/*`、本需求四份文档 | 仅运行一次显式 `qwen3.8-flash happy-path`。workflow 完成 checkpoint、PM 产品设计和 RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；唯一 `code_impl` task 为 COMPLETED/delivered，没有重复 continuation，且只生成 `code/main.py`。5 个 Test Runner execution 均真实终态为 `FINISHED/exit_code=2`，pytest 收集错误由 1 个扩大到 6 个后降到 4 个，属于业务失败且未触发 MCP incident；四路 circuit 始终 CLOSED/0 failures。随后 watchdog/heartbeat 形成两条同时 `in_progress` 的 RD assignment，父 `team:rd` dispatch active/pending=1，而唯一子任务已成功终态；现有 Driver 只快速诊断 RUNNING 静默或 FAILED/CANCELLED 子任务，`hanging_dispatch` 为空，最终仍以等待 `qa/test_plan.md` 30 分钟超时。场景证据跨度 2534 秒，Failsafe 测试 2913 秒，Maven 48 分 42 秒；未重试或启动后三个场景。关闭后 AIO session、pytest/pip/tmux、宿主 Maven/Java 均为 0，Docker healthy |
| Task 8 父 dispatch、assignment 与测试收集加固（2026-10-19） | 代码与非 E2E 验证完成；等待用户确认 | `AgentScopeAgentGateway.java`、`FileMailboxRepository.java`、`MailboxLeaseWatchdog.java`、`RealTeamE2EDriver.java`、`McpProjectTestRunner.java`、`RuntimeLifecycle.java`、`QinglingTeamProperties.java`、角色模板及对应测试 | 父回复只有在最新逻辑子任务全部终态后才能结束 dispatch，FAILED/CANCELLED 直接类型化失败；父角色 session JSONL 提供独立真实进度，成功子任务后父调用静默 5 分钟进入 `PARENT_STALLED` 并复用 incident 恢复/BLOCKED 链。开放 `task_assign` 以 project/stage/from/to 原子复用，恢复指令刷新原邮件；`clarification_answer` 不再生成第二个 task assignment；watchdog 同时要求 dispatch 与真实父 Agent 均空闲。Driver 直接报告父停滞和重复开放 assignment；shutdown 使用独立 30 秒 drain。Test Runner 先以 30 秒 collect-only 验证导入，再以 240 秒执行正式覆盖率测试；RD 模板要求测试只引用真实实现符号。定向 76 项及仓储边界 12 项通过，`mvn verify` 263 项通过，零模型真实 Docker 1 IT 在 15.39 秒通过；未运行 E2E |
| Task 8 P0/P1 修复后单次 E2E（2026-10-20） | 未完成；恢复竞态产生重复 continuation，Driver 再次退化为 QA 文件超时 | `target/e2e-evidence/happy-path/{summary.json,failure-summary.json,model-selection.json}`、`target/e2e-workspace/.qingling-test-runs/*`、`target/e2e-workspace/rd/team:rd/agents/{rd,code_impl}`、本需求四份文档 | 仅运行一次干净的显式 `qwen3.8-flash happy-path`，未重试或启动后三个场景。workflow 完成 checkpoint、PM、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；初始 `code_impl` 为 COMPLETED/delivered，唯一入口为 `code/main.py`。6 个 Docker Test Runner execution 均真实 `FINISHED/exit_code=1`，最好达到 27 collected、24 passed、coverage 97%，工具结果正确返回 `TEST_FAILED/PYTEST_FAILURE`。但查询在 `RUNNING` 进程已退出、原子 `exit_code` 尚未可见的短窗口把同一 execution 先报为 `RESULT_UNAVAILABLE`，留下 3 条 attempt=1 RECOVERING incident；恢复后的业务终态没有撤销临时 incident，且按 execution ID 计数使同类故障始终无法进入 BLOCKED。两个延迟完成的后台 `agent_spawn` 又绕过同 session 单飞，形成两个并发 continuation；Driver 既未识别重复非终态子任务，也未把 RECOVERING incident 视为终止条件，最终仍报告等待 `qa/test_plan.md` 30 分钟超时。Failsafe 1 failure、0 errors、0 skipped，场景约 45 分 54 秒，Maven 46 分 04 秒；关闭后遗留任务持久化为 CANCELLED/FAILED，宿主与容器无 pytest/pip/tmux/Maven/Java 残留 |
| Task 8 终态竞态与 late-arrival continuation 修复（2026-10-21） | 代码与全部非 E2E 门禁完成；按用户要求暂停 E2E | `AgentExecutionException.java`、`McpProjectTestRunner.java`、`ProjectTestAgentTool.java`、`TaskSemanticsRegistry.java`、`TaskSemanticsTrackingTool.java`、`AgentScopeAgentGateway.java`、`DeterministicWorkflowTools.java`、`RealTeamE2EDriver.java` 及对应测试 | Test Runner 在 runner 退出但退出码尚未原子落盘时进入最长 5 秒 FINALIZING 宽限，仅宽限耗尽才判 ORPHANED；同一 execution 恢复到 SUCCESS/TEST_FAILED 时不再写入临时 RECOVERING。恢复预算按 project/stage/Owner/tool/稳定失败指纹跨 execution 共享，第二次同类基础设施失败进入 BLOCKED。外层取消的 spawn/send 保留 6 分钟有界单飞占位，直到延迟任务可由 TaskRepository 识别；Gateway 对同 agent 的多个未终态任务抛出 `DUPLICATE_CHILD_TASK` 并结束父 dispatch。Driver 直接报告重复 continuation 或多个 RECOVERING execution，不再退化为下游文件超时。扩展定向 92 项、`mvn verify` 270 项和零模型真实 Docker 1 IT 全部通过；未加载 E2E profile、未调用百炼 |
| Task 8 阶段进度租约与后台任务语义修复（2026-10-25） | 代码与全部非 E2E 门禁完成；等待真实 E2E 复核 | `TaskSemanticsTrackingTool.java`、`TaskSemanticsTrackingToolTest.java`、`RealTeamE2EDriver.java`、`RealTeamE2EDriverTest.java`、本需求四份文档 | spawn/send 的模型长等待参数在 Java 边界统一归零并同步 ToolUse content，从调用起即后台执行，消除固定 PT5M 外层超时。Driver 使用 90 分钟硬总预算和约 12 分钟真实无进展租约；进展指纹包含事件、开放 mailbox、dispatch 和 AgentScope 活动，不包含 Cron/MCP 周期噪声。mailbox 悬挂阈值由 watchdog 默认租约、扫描间隔及恢复宽限派生，恢复链未获得机会前不得误报。定向 38 项、默认 311 项与零模型真实 Docker 1 IT 均通过；本轮未运行真实 E2E，Task 8、Task 9 和父 Task 18 保持未完成 |

### 11.1 跨语言迁移根因评估（2026-10-10）

| 候选原因 | 结论 | 证据 |
|----------|------|------|
| Java/COLA 技术选型 | 不是根因 | 分层模块、确定性状态机、类型化工具边界和 217 项默认回归均稳定；这些机制反而使故障可以被精确分类、熔断和阻断推进。风险集中在 AgentScope/MCP 集成边界，不在 Java 语言或 COLA 分层本身 |
| Java 迁移实现 | 最近多轮失败的直接主因 | 曾缺少 execution ID 可信绑定、超时未进入 incident、QA 测试目标不独立、真实 ToolUse 字符串与 Map 类型不一致、任务关闭与完成回报可拆开。这些都是迁移实现缺口，现已在 Java 边界修复并由定向、默认及真实 Docker 契约验证 |
| 原 Python 功能规划/实现 | 长期可靠性问题的重要来源 | Python Runner 捕获 Agent 异常后只记录日志，原 E2E 主要等待文件且成功条件未强制 QA 报告和 delivered 终态；测试可因环境缺失 skip，stale lease 与 shutdown 也缺少正式闭环。这会把真实上游故障拖成下游文件超时，并降低历史“通过/失败”结论的辨识度 |
| 模型能力 | 不是当前首要阻断 | 真实 `qwen3-max` 曾将 12 项 pytest 从 8 失败修到 1 失败，后续一轮达到 13/13、覆盖率 94%；`qwen3.8-flash` 本轮也完成 checkpoint、产品设计和技术设计。两种模型最终都被工具协议或 Agent 子任务生命周期问题阻断 |
| 模型配置/可用性 | 新一轮运行的独立前置阻断 | 2026-10-10 启动诊断和 2026-10-11 单次真实 E2E 均表明，当前配置的兼容端点对 `qwen3-max` 返回 HTTP 404；这只证明当前密钥、区域、端点和模型组合不可用，不能推出模型在阿里云全局下线。E2E 驱动已在 Spring 启动前有界探测并输出脱敏失败证据，2026-10-11 的运行因此在 1 个测试报错后快速退出，没有进入长时间 Agent 等待 |
| AgentScope 子任务生命周期 | 已完成本轮代码闭环，待真实 E2E 复核 | 后台 `task_output` 已替换为非阻塞快照；spawn/send/cancel 的任务种类与取消来源被 Java 记录，同一逻辑子任务只以最新 continuation 为权威。最新子任务 FAILED/CANCELLED 会终止父调用并进入结构化 incident；首次恢复、第二次 BLOCKED、重复 occurrence 幂等均有测试覆盖。Driver 同步观察 Cron 失败和运行时空闲状态，不再只能等待下游文件超时。由于用户要求本轮不运行 E2E，这一结论仅代表代码与非 E2E 契约完成，尚未获得新一轮真实模型场景证据 |

本次评估基于当前保留的诊断目录、事件/mailbox/MCP 证据和父需求记录；用户报告的五十余次历史运行并非每次都保留了完整证据，因此不对不可复核的单次失败做数量归因。综合判断是“原 Python 可靠性设计留下系统性弱点，Java 迁移在 AgentScope/MCP 边界出现直接实现缺口”，而不是 Java/COLA 选型失败。

## 12. 审查结论

Task 1～7 已完成既有验证；Task 8 仍未完成。2026-10-22 在启动前重新通过默认 `mvn verify` 与零模型真实 Docker 工具契约后，只执行了一次干净的显式 `qwen3.8-flash happy-path`。流程完成 checkpoint、PM 产品设计、RD/QA 插入评审和 RD 技术设计，并进入 `CODE_IMPLEMENTATION/RD`；调用参数错误没有误开 MCP circuit，技术设计回合耗尽后同 session continuation 也能恢复并完成回报。新阻断发生在代码子任务：唯一 `code_impl` 的真实 session/产物在 21:17:45 后停止进展，但任务仍保持 RUNNING，内部 `sandbox_execute_bash` 最终达到 AgentScope `PT5M` 超时；Driver 没有先触发 `CHILD_STALLED`，而以等待 `code/main.py` 的 `PT30M` 超时结束，真实工具超时随后在 shutdown 阶段显现。Failsafe 1 failure、Maven 总耗时 48 分 59 秒，未产生代码入口或 Test Runner execution。finally 已保存 `target/e2e-evidence/happy-path`，子任务持久化为 CANCELLED，AIO shell session=0，且无 pytest/pip/tmux/Maven/E2E 残留。未重试、未启动后三个场景；Task 8、Task 9 与父需求 Task 18 继续保持未完成。

### 12.1 2026-10-23 非 E2E 修复结论

已完成 2026-10-22 失败暴露的运行时竞态修复：通用 `sandbox_execute_bash` 在模型省略或请求过大 timeout 时统一使用 60 秒，Java 传输层只保留额外 5 秒固定宽限；参数错误继续保持 circuit CLOSED。每个 MCP 包装器绑定父 Agent 角色，Gateway 在一个父回合内只允许一次连接关闭、重建和无副作用探针，不重放此前可能产生副作用的 Shell 命令；无论重建成功与否，原调用结果都视为不可判定并抛出 `MCP_CONNECTION_UNAVAILABLE`，父回合成功出口还会比较连接失败计数以封住熔断轮询竞态。既有 Runner incident 边界据此保持 stage/Owner 并执行 RECOVERING，同类故障再次发生时进入 BLOCKED。

RUNNING 与父回合真实进度租约从 5 分钟收紧到 4 分 30 秒，确保停滞诊断早于旧工具边界。E2E Driver 在等待技术方案、代码、QA 计划和 QA 报告前先确认 `TECH_DESIGN`、`CODE_IMPLEMENTATION`、`QA_TEST_DESIGN`、`QA_TEST_EXECUTION` 的真实迁移，避免提前出现的上游文件消耗下游阶段预算；失败路径先执行 shutdown 和任务终态化，再保存关闭前 MCP 健康及最终 dispatch/Agent 快照。

定向 45 项、完整 `mvn verify` 287 项和零模型真实 Docker `RealSandboxToolContractIT` 1 IT 全部通过。真实契约首个 RD/QA Shell 探针均故意省略 timeout，确认生产包装层会真实注入 60 秒；IT 耗时 15.64 秒、Maven 总耗时 31.216 秒，NoCallModel 未调用百炼，结束后无 pytest/pip/tmux 孤儿进程。本轮未启动真实 E2E，Task 8、Task 9 与父需求 Task 18 仍保持未完成。

### 12.2 2026-10-24 停滞恢复与流式进度修复结论

已先修复本轮缺陷 1、2、3，缺陷 4 的 QA 空闲 MCP 会话假设不在本轮范围内。`AgentScopeAgentGateway.DEFAULT_CHILD_TASK_STALL_TIMEOUT` 现在是唯一公开租约来源，E2E Driver 从该租约派生“租约 + 60 秒”的悬挂阈值；同 execution/Owner 最新 incident 仍为首次、可重试、`RECOVERING` 时，Driver 不再抢跑运行时恢复链，预算耗尽为 `BLOCKED` 后才允许报告不可恢复悬挂。

新增有界 `ModelActivityRegistry` 与按角色独占的纯透传 `ActivityTrackingModel`。装饰器只在订阅边界和每个流式分片记录角色活动时间，不记录提示词、模型输出或凭据，并完整委托模型名称、结构化输出能力和上下文窗口。装饰只发生在 `TeamAgentFactory` 创建的角色 Agent 内部，Spring `Model` Bean 的具体类型保持不变。Factory 与 Gateway 共享同一注册表；父停滞判定与诊断统一取 session 证据和模型活动的较晚时间。只有该角色唯一活跃父回合时才采信角色级活动，同角色并发回合的不可归因活动会被封存，避免一个 session 给另一个 session 续租。

停滞宽限复查已由不允许 null 的 Reactor `map` 改为 `flatMap` + `Mono.justOrEmpty`；故障在宽限内自行消失时继续父回合，MCP 连接故障仍走原有独立分支。对 Gateway 与 Runner 的同类 nullable Reactor 映射进行了搜索，未发现其他同型调用。

定向套件先连续两次各通过 61 项，补充当前 execution ID 与故障指纹精确匹配后通过 62 项；最终完整 `mvn verify` 通过 299 项（domain 44、app 37、infra 136、adapter 13、starter 69），耗时 15.036 秒；零模型真实 Docker `RealSandboxToolContractIT` 1 IT 在 15.52 秒通过，Maven 总耗时 34.208 秒。本轮未运行真实 E2E，Task 8、Task 9 与父需求 Task 18 继续保持未完成。

### 12.3 2026-10-24 Bash 会话争用与恢复探针修复结论

已在真实 AIO-Sandbox 1.11.0 容器内审计服务端实现：`app/mcp/shell.py` 的 `_default_session_id` 是模块级全局值，所有 MCP client/Owner 的 `new_session=false` 调用会复用同一个默认 shell 会话；服务端允许最多 10 个 shell 会话。当前 Java 代码不存在此前假设的按 Owner 空闲保活任务，因此无需保留或关闭该错误方向。

普通 `sandbox_execute_bash` 与 Test Runner 的短控制命令继续使用默认会话，但由 `McpSandboxConfiguration` 共享的公平单许可量在完整 MCP 调用期间执行跨角色单飞，杜绝并发写入同一 OpenHands PS1 会话。长时间依赖安装与项目测试不再建议经通用 Bash 静默执行，而应使用已有异步 `run_project_tests`。等待许可期间取消不会误开熔断；传输已经开始后发生取消或连接错误仍按既有规则进入 circuit。

连接恢复覆盖真正故障的 shell 层：恢复持有同一单飞许可，移除旧 MCP client，调用 AIO-Sandbox 原生 `DELETE /v1/shell/sessions` 清理服务端会话，再注册新 client，并以 `new_session=true` 执行无副作用探针；探针成功才替换 delegate 并关闭 circuit，原始 Shell 永不重放。健康快照新增最近恢复尝试时间、脱敏失败阶段/异常类型和恢复耗时，E2E Driver 证据同步携带这些字段，不记录异常消息、命令或凭据。

曾尝试让每条 Bash 都使用 `new_session=true`，但真实 Docker 契约在约 65 秒后暴露出 10 会话上限、持续淘汰和 tmux/PS1 失步，因此已撤销该实现；这证明“每次调用新建会话”不是可接受的隔离方案。最终定向 `McpSandboxConfigurationTest,McpProjectTestRunnerTest` 通过 21 项；完整 `mvn verify` 通过 301 项（domain 44、app 37、infra 138、adapter 13、starter 69），耗时 14.910 秒；零模型真实 Docker `RealSandboxToolContractIT` 通过 1 IT（17.58 秒，Maven 34.403 秒），连续 10 次 Shell、5 次 Test Runner、受控连接恢复及无 pending/orphan 断言均通过。本轮未运行真实 E2E，Task 8、Task 9 与父需求 Task 18 继续保持未完成。

### 12.4 2026-10-24 单次真实 E2E 结论

按用户要求只执行一次干净的显式 `qwen3.8-flash happy-path`，未重试或启动后三个场景。模型预检 available；流程完成需求 checkpoint、PM 产品设计、技术设计插入评审、RD 第 2 轮技术设计修订，并进入 `CODE_IMPLEMENTATION/RD`。技术设计长生成跨过旧文件租约窗口仍正常完成，证明流式活动租约没有误杀该回合。

代码阶段共产生 4 个真实 Test Runner execution：前三个均为 `FINISHED/exit_code=1`，31 collected、21 passed、10 failed、coverage 95%，被正确分类为 pytest 业务失败；后续 continuation 修改实现并扩充测试，第四个为 `FINISHED/exit_code=2`，24 collected 后因 `tests/test_acceptance_trace.py` 与 `tests/test_services.py` 仍导入不存在的 `app` 包而出现 2 个收集错误。期间出现一次已终态、已 delivered 的 continuation 错误 `Pending tool calls exist without results`，后续 continuation 保持单飞，没有并发重复任务。RD 最终在 `CODE_IMPLEMENTATION` 产生 attempt 1、retryable、`RECOVERING` 的 `PARENT_STALLED` incident，stage/Owner 保持不变。

最终直接失败不是 MCP：QA 在处理测试设计补交/澄清后形成 `route=team:qa`、`project_id=""` 的父 dispatch，session 在 19:30:32Z 后无进展、无子任务，但因为缺少项目归属没有生成可恢复 incident；Driver 最终于 20:32:52Z 报告不可恢复 dispatch 悬挂。最后进度到失败相隔约 62 分钟，明显超过 Gateway 4 分 30 秒租约与 Driver 额外 60 秒宽限，说明“项目上下文为空的角色 dispatch”仍未进入统一的停滞恢复/快速诊断时序。日志同时记录模型 SSE/NDJSON 流被远端关闭、AgentScope 两次自动重试耗尽及会话压缩失败，需作为该 QA 回合停滞的伴随证据继续定位。

四个 Owner 的 MCP circuit 全程 `CLOSED/0 failures`，没有恢复尝试，证明本轮未复现共享 PS1 会话失步和 wedged 恢复探针问题。Failsafe 为 1 test、1 failure、0 errors、0 skipped，测试耗时 7064 秒，Maven 总耗时 1 小时 58 分。finally 后 dispatch pending=0、AgentScope active=0、AIO-Sandbox healthy、shell session=0，宿主与容器无 pytest/pip/tmux/Maven/Java 残留。Task 8、Task 9 与父需求 Task 18 继续保持未完成。

### 12.5 2026-10-24 项目上下文、父进度诊断与产物契约修复结论

本轮修复上一轮 E2E 暴露的四个缺口，但按用户要求未运行新的真实 E2E。Heartbeat 现在按“可信显式元数据、`new_mail` 邮件、唯一处于当前 Owner 阶段的 Java workflow”依次解析 project ID；解析结果同时进入 RuntimeContext、团队提示和 incident 上报，避免 QA/RD 父 dispatch 因 `project_id=""` 脱离项目级恢复链。多项目或归属不唯一时保持拒绝猜测，防止错误跨项目归因。

父进度判定与诊断统一使用同一个快照，明确区分 session 状态、模型流式活动和调用起始时间，并记录最终采用的进度来源。Driver 对超过派生租约、仍存在于 SerialDispatchRegistry 但 Gateway 已无活动诊断的 `team:*` orphan dispatch 直接失败，不再继续等待产物总超时。

新增 `PendingToolRecoveringStateStore` 装饰 AgentScope 状态存储。读取 `agent_state` 时为缺失结果的 pending tool call 注入确定性的错误 ToolResult，交由模型继续处理；不重放原工具，也不记录提示词、输出或凭据。该修复覆盖 AgentScope 2.0.3 声明式子代理未继承父 Agent pending-tool recovery 开关的实际缺口。

RD/QA 完成门禁把 Python 唯一入口收紧为 `code/main.py`：拒绝任何 `code/app/main.py`，并扫描 `code/tests/**/*.py` 与 `qa/contract_tests/**/*.py`，拒绝 `from app ...` / `import app ...`。RD 模板同步要求 pytest 以 `code/` 为根，使用 `from main import app` 或直接导入顶层模块。

定向测试通过 71 项；完整 `mvn verify` 通过 307 项（domain 44、app 38、infra 142、adapter 13、starter 70），耗时 17.928 秒；零模型真实 Docker `RealSandboxToolContractIT` 通过 1 IT（17.50 秒，Maven 33.324 秒）。`git diff --check` 通过。本轮没有调用百炼或启动 E2E，Task 8、Task 9 与父需求 Task 18 继续保持未完成。

### 12.6 2026-10-24 修复后单次真实 E2E 结论

按用户要求只执行一次干净 `happy-path`，显式固定 DashScope `qwen3.8-flash`，未自动重试或启动后三个场景。流程完成 checkpoint、PM 产品设计、RD 技术设计并进入代码实现；唯一入口为 `code/main.py`，没有生成 `code/app/main.py`。Test Runner 从早期 `exit_code=5`、连续 6 次 `exit_code=1` 最终修复到 `FINISHED/exit_code=0`。期间一次 `agent_send` 达到 AgentScope 固定 `PT5M`，但 assignment 被释放且后续执行继续，未形成 MCP incident；四路 circuit 始终 `CLOSED/0 failures`。

RD 首次完成回报因 `content` 不是结构化 JSON 被确定性门禁拒绝，随后成功重发并触发 PM/QA 双评审。QA 在终止前 29 秒提交 `changes_requested`，指出列表排序实现与测试共同偏离设计契约；PM review 当时仍为 `in_progress`，持续约 10 分钟，所有 dispatch 已空闲且 AgentScope 无活动回合。

本次直接失败是 Driver 的绝对阶段预算：等待 `QA_TEST_DESIGN` 的 `PT30M` 在代码修复、完成证据重发和评审仍持续产生真实进度时没有刷新，最终在 event 12 刚落盘后仍按 deadline 终止。因此新的待修复点是“阶段总预算与真实无进展租约未分离”，以及 Driver 对仍有近期事件/评审进展的流程没有延期；不能通过简单扩大 30 分钟掩盖。

Failsafe 为 1 test、1 failure、0 errors、0 skipped，E2E 2584 秒，Maven 总耗时 43 分 19 秒。finally 保存 `summary.json`/`failure-summary.json`，关闭后 dispatch pending=0、AgentScope active=0、AIO shell session=0，容器内无 pytest/pip/tmux，宿主无 E2E Maven/Java 残留。Task 8、Task 9 与父 Task 18 继续保持未完成。

### 12.7 2026-10-25 确定性编排改造与非 E2E 门禁

后续专项版本 `agent-workflow-determinism-1.0.0` 已把新项目工作流收敛到 runtime v2 单一 WorkExecution aggregate 与内置 outbox：模型负责业务产物和有限语义结论，Java 负责完成协调、阶段迁移、评审 barrier、副作用投影和恢复。Task 18 固定为 `STANDARD + REQUIRED_ONLY`，不再随机插入阻断审计/新手评审；SIMPLE/STANDARD 默认由父角色直接完成，不强制声明式子 Agent。Harness 注入只读 Todo acceptance，QA Runner 固定执行独立验收，Driver 直接检查 execution、review、outbox 与 orphan，而不是等待下游文件掩盖根因。

非 E2E 门禁全部通过：相关模块定向 165 项；最终 `mvn verify` 394 项（domain 72、app 61、infra 159、adapter 13、starter 89），18.805 秒；真实 Docker/AgentScope/MCP + NoCallModel 工具契约 1 IT，37.157 秒；零模型 coordinated STANDARD 全链 1 IT，27.483 秒；`git diff --check` 和宿主/容器孤儿复核通过。工具契约首次运行曾真实发现 QA 固定验收 fixture 未安装并以 `exit_code=4` 失败，修复为复用公共只读 fixture 后才取得上述通过结果。

本轮没有 source E2E 凭据、没有调用百炼，也没有运行真实场景。上述结果只能证明确定性编排与工具边界已满足下一轮 E2E 的前置门禁，不能替代四条真实 E2E；Task 8、Task 9 与父 Task 18 继续保持未完成，等待用户二次明确授权。

### 12.8 2026-10-25 确定性编排后的首次真实 E2E

用户二次授权后严格只运行一次干净 `happy-path`，显式配置为 DashScope `qwen3.8-flash`，免费模型选择关闭，Docker AIO-Sandbox healthy。运行没有进入业务阶段：Spring 在创建 `AgentScopeAgentGateway` 时，调用 `TaskSemanticsRegistry.forRepository(agent.getTaskRepository())` 触发 `NullPointerException: repository 不能为空`。

根因是最新 coordinated STANDARD 明确禁用 Sub-Agent，`TeamAgentFactory` 因此调用 AgentScope `disableSubagents()`；真实 HarnessAgent 在该模式下合法地没有 TaskRepository。Gateway 的生产适配仍继承旧假设，要求每个角色都有任务仓库。现有 `TeamAgentFactoryTest` 只断言子任务工具和 manager 关闭，没有把这些真实 Agent 装入 Gateway；`CoordinatedWorkflowContractIT` 使用零模型协调链，也没有覆盖真实 Spring + HarnessAgent 的启动组合，因此非 E2E 门禁未捕获该接线错误。

Failsafe 1 test、0 failures、1 error、0 skipped，E2E 7.139 秒，Maven 26.359 秒。`summary.json` 与 `failure-summary.json` 记录 phase=startup、provider=dashscope、configured_model=qwen3.8-flash 和脱敏 NPE；未启动 checkpoint、业务 workflow 或角色模型调用。未重试或启动后三个场景；宿主和容器无 Maven/E2E/pytest/pip/tmux 残留。下一轮前需为无 TaskRepository 的父角色提供空子任务诊断/取消语义，并增加真实 Spring + HarnessAgent coordinated STANDARD 启动测试。Task 8、Task 9 和父 Task 18 继续未完成。

## 13. 确认记录（HARD-GATE）

- **确认时间**：2026-10-07
- **确认人**：用户
- **确认说明**：用户要求按既有 E2E 根因分析建立独立 `1.0.1` 修复需求，并在新聊天窗口执行；当前聊天只生成 Spec 和交接话术，不实施源码修复。

## 14. 2026-10-25 空 TaskRepository 修复结论

已修复上一轮 `happy-path` 的 Spring startup 直接原因。无 Sub-Agent 的父角色现在以空子任务运行时适配：诊断返回空列表，取消跳过不存在的仓库但保留父 Agent interrupt，shutdown 不再强制访问任务语义表。真实 Spring 测试已证明 `COORDINATED + STANDARD` 可装配四角色 Agent、Gateway、RuntimeLifecycle 与 Cron。

定向 26 项和完整默认回归 396 项通过；本轮未运行真实 E2E，因此 Task 8、Task 9 和父 Task 18 仍未完成。

## 15. 2026-10-25 修复后单次 E2E 新发现

启动缺陷已解除，真实流程推进到 `CODE_IMPLEMENTATION/RD`。Driver 在 Manager 正处理 RD 技术设计完成消息时，把原 Owner 已空闲的 `TECH_DESIGN/RUNNING` 误报为孤儿；当前算法没有识别“Owner 回合结束、Manager 完成协调器尚在提交迁移”的合法交接窗口。持久化终态证明技术设计随后已 `SUCCEEDED` 并创建代码 execution，因此这不是实际丢失的 execution。

下一轮前需让孤儿诊断识别活跃的 Manager 完成协调，或为已存在对应 `task_done`/完成 outbox 的 execution 提供短交接宽限，并补并发竞态测试。本轮只执行一次、未重试，Task 18 继续未完成。

## 16. 2026-10-25 孤儿 execution 交接竞态修复

Driver 已增加精确匹配、有限时长的 Manager 完成交接判定。只有同 execution/project/stage/Owner 的 assignment 仍在处理中，且 Manager dispatch 活跃时间未超过 5 分 30 秒，才暂缓孤儿失败；错配、未来时间或宽限耗尽均不放行。定向 36 项与默认回归 399 项通过，本轮未运行 E2E。

## 2026-10-25 交接诊断修复后真实 E2E 新发现

本轮只执行一次干净 `qwen3.8-flash happy-path`。流程通过需求 checkpoint 并进入 PM 产品设计；PM 已写出产品规格，但其 `task_done` 完成协议未能被 Java 接受。`content.decision` 被依次解析为多个非法 `WorkCompletionDecision` 值，错误响应只包含非法值而未提供合法枚举或工具 schema，模型最终正常结束，权威状态仍为 `PRODUCT_DESIGN/RUNNING`。

Driver 采样时 PM、Manager 均无活跃 dispatch，AgentScope 无活跃调用，因此这次是真实孤儿而非 Manager 交接竞态。四路 MCP circuit 均 `CLOSED/0 failures`。运行 500.7 秒失败，Maven 总耗时 8 分 40 秒；未重试、未执行后三个场景，Task 18 保持未完成。

## 2026-10-25 完成协议可发现性与终态保护修复

工具 schema、参数描述和非法值错误现在统一声明 `WorkCompletionDecision` 的完整值域：`ACCEPTED` 与 `DEFECT_FOUND`。PM/RD 正常完成只能使用 `ACCEPTED`，`DEFECT_FOUND` 仅用于 QA 有真实失败证据的缺陷结论。

完成工具与 Agent Gateway 共享一个有界 session 注册表。任意 coordinated `task_done` 被拒绝时只记录 role/session/project；同回合纠错成功即清除。若拒绝未解决而模型尝试以普通文本结束，Gateway 发出 `COMPLETION_PROTOCOL_REJECTED`，Runner 会进入现有一次恢复/再次 BLOCKED 链，避免产生无 incident 的 `RUNNING/in_progress` 孤儿。聚焦 50 项和 `mvn verify` 402 项通过；未运行真实 E2E。

## 2026-10-25 完成协议修复后真实 E2E 结果

E2E 前零模型真实 Docker 契约通过。随后只执行一次干净 `happy-path`，但 Spring 启动阶段的显式 DashScope 模型预检对 `qwen3.8-flash` 返回 `transport_error`。场景在约 6 秒内失败，未进入 workflow、AgentScope 角色回合或 completion 工具，因此本轮没有验证到完成协议修复。

Failsafe 为 1 test、0 failures、1 error、0 skipped；脱敏启动失败证据位于 `target/e2e-evidence/happy-path`。未重试或启动后三场景，Docker healthy 且无 Maven/E2E/pytest/pip/tmux 残留。Task 8 与 Task 18 继续未完成。

## 2026-10-25 模型预检分类、有限重试与证据修复

启动模型预检不再把全部异常压缩成 `transport_error`。运行时现稳定区分 HTTP 429/4xx/5xx、DNS、连接失败、连接超时、请求超时、TLS、连接重置、响应解析失败及 Function Calling 缺失；仅对可恢复传输故障和 HTTP 429/5xx 最多恢复一次，非幂等风险不适用于该无副作用预检。每次尝试只记录模型、序号、类型、retryable、耗时、endpoint host、HTTP 状态和异常类，明确排除 prompt、响应正文、鉴权头和密钥。

Driver 已能把该证据写入启动失败摘要。独立最小 Function Calling 探针以真实 DashScope `qwen3.8-flash` 在 attempt 1、HTTP 200、4797ms 通过；聚焦 67 项、默认回归 409 项、零模型真实 Docker 契约 1 IT 均通过。本轮没有运行 E2E，Task 8 仍未完成。

## 2026-10-25 模型预检修复后单次 E2E 结果

唯一一次干净 `happy-path` 的模型预检在 attempt 1、HTTP 200、2692ms 通过，验证了新分类与证据路径。流程完成 checkpoint、产品设计和技术设计，进入 `CODE_IMPLEMENTATION/RD`；代码与 RD 测试已落盘，MCP 全程健康。

失败根因是完成协议仍缺一条入口：RD 从未尝试 `run_project_tests` 或 `task_done`，却以“下次唤醒继续”的普通文本正常结束回合。由于现有 `CompletionProtocolRegistry` 只在完成工具已被拒绝时登记，Gateway 将该回合视为成功；Runner 没有基于仍为 RUNNING 的 execution/assignment 生成类型化 incident 或 continuation。Driver 在无 dispatch、无 AgentScope 活动、无 Test Runner execution 的证据下正确报告真实孤儿。shutdown 将其终态化为 `CANCELLED/RUNTIME_SHUTDOWN`。本轮未重试或启动后三场景，Task 8 仍未完成。

## 2026-10-25 Owner 回合未终态可靠性修复

Owner 成功领取并启动 `task_assign` 后，运行时按 role/session/project 登记 completion obligation。`task_done` 成功时解除；如果模型回合正常退出但 obligation 仍存在，Gateway 抛出 `OWNER_EXITED_WITH_RUNNING_EXECUTION`，Runner 以类型化 incident 接入 Java 状态机，不再留下无 incident 的 RUNNING 孤儿。

首次失败保持当前 stage、Owner 和 execution，转为 `RECOVERING`，向原 Owner 投递 `agent_execution_failed` 恢复任务并安排一次性 wake；原 execution 再次领取后 attempt=2。第二次同类失败进入明确 `BLOCKED`，不创建第三次 wake；重复报告不重复投影。无 assignment 的 heartbeat 不登记义务，完成工具已被拒绝时继续使用更具体的 `COMPLETION_PROTOCOL_REJECTED`。

聚焦 63 项、完整 `mvn verify` 410 项、零模型真实 Docker 契约 1 IT 均通过。本轮未执行真实 E2E，Task 8 和父 Task 18 均保持未完成。

## 2026-10-25 Owner 未终态修复后真实 E2E 结论

只执行一次干净 `qwen3.8-flash happy-path`。需求 checkpoint、产品设计和技术设计成功；PM 的完成协议拒绝经一次恢复后成功终态化。代码阶段 RD 首次正常退出但 execution 仍 RUNNING 时，Java 写入 `OWNER_EXITED_WITH_RUNNING_EXECUTION/RECOVERING`，保持原 stage、Owner、execution 并自动唤醒原 RD；恢复领取后 attempt=2。RD 第二次同类退出后同一 execution 转为 `BLOCKED`，没有第三次 wake，证明本轮目标机制在真实 E2E 中生效。

新阻断位于 shutdown：主线程在 `CronService.stop()` 等待对象监视器，Cron 调度线程在同步 `tick()` 持有同一监视器，并在 `fire()` 对未完成 dispatch 调用无界 `CompletableFuture.join()`。这使 Driver 的 finally 无法完成证据保存；约 31 分钟后中断唯一 Maven 进程，exit 130。只保留真实 `model-selection.json` 和场景目录，不伪造 summary。清理后 AIO session=0、无孤儿进程；Task 8 未完成。

## 2026-10-26 Cron、Driver 证据与代码完成硬门禁修复

上一轮 Cron shutdown 死锁已修复。调度 tick 使用独立串行锁，start/stop 使用生命周期锁；dispatch 等待具有 30 分钟上限，stop 不再获取 tick 锁或无界等待业务 Future。Cron 只停止生产和自身等待，未终态 dispatch 仍由正式 shutdown 顺序中的 Registry 有限排空、超时取消和工作流终态化处理。

Driver 的 finally 先保存运行中失败证据，再以 2 分钟独立看门狗执行 shutdown，最后始终保存关闭后的终态证据；场景本身受 2 小时全局看门狗约束。RD 在代码实现或缺陷修复阶段的恢复任务会明确携带 `required_action=run_project_tests_then_task_done`，Gateway 的 Owner 未终态诊断也透传该动作。代码阶段完成门禁继续以 execution registry 为唯一权威：没有真实 Test Runner execution 时，模型复制的成功指标不能通过门禁。

定向 80 项通过；`mvn verify` 通过 415 项；`mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` 通过 415 项单元回归和 1 个真实 Docker IT。工具契约使用 NoCallModel，未调用百炼。本轮未运行 E2E，Task 8、Task 9 和父 Task 18 均保持未完成。
