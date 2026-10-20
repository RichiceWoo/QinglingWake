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

Task 1～7 已完成既有验证；Task 8 仍未完成。2026-10-20 的单次真实 `happy-path` 证明 P1 有效：模型已将测试推进到 27 项中 24 项通过、覆盖率 97%，唯一入口和 shutdown 持久终态也符合契约；首要阻断仍在 Java/AgentScope 运行时。当前 P0 是 Test Runner 的 `RUNNING` 进程退出与 `exit_code` 原子落盘之间仍有 ORPHANED 竞态，恢复后业务终态未协调掉临时 RECOVERING incident；此外，延迟返回的后台 `agent_spawn` 可以绕过同 session 单飞并落地两个 continuation。incident 重试计数按 execution ID 隔离，Driver 也未识别同 agent/session 多个非终态任务或重复 RECOVERING，因而再次退化为 QA 文件超时。Task 8、Task 9 与父需求 Task 18 继续保持未完成；必须先修复上述问题并通过非 E2E 门禁，再由用户确认下一轮真实 E2E。

## 13. 确认记录（HARD-GATE）

- **确认时间**：2026-10-07
- **确认人**：用户
- **确认说明**：用户要求按既有 E2E 根因分析建立独立 `1.0.1` 修复需求，并在新聊天窗口执行；当前聊天只生成 Spec 和交接话术，不实施源码修复。
