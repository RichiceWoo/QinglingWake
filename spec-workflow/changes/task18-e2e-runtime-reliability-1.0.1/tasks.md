# 任务拆分 — Task 18 E2E 运行时可靠性修复 1.0.1

> 实施顺序：能力调研 → 结构化沙盒执行 → 故障状态机 → mailbox/dispatch/lifecycle → 契约与 E2E 驱动 → 回归与真实 E2E → 回写父需求。
> **Git**：当前不提交或推送，除非用户另行明确要求；本文件勾选表示已有真实证据证明逻辑完成。

## 前置条件

- [x] 两次失败证据已保留，且未启动第三次 E2E。
- [x] 当前显式模型配置为 `qwen3-max`，免费模型选择关闭。
- [x] 新聊天开始后检查 `git status`，保留现有未提交修改，不得 reset 或覆盖。
- [x] 确认没有遗留 Maven/Java E2E 进程；真实测试前确认 Docker 沙盒健康和 8029 可达。
- [x] 不读取或打印 `~/.config/qingling-team/e2e.env` 内容，只允许 `source` 后检查必需变量是否非空。

## Task 1: 审计 AIO-Sandbox 执行与超时语义

- **目标**：用源码、依赖 API、真实 MCP schema 和最小无模型探针确认 `sandbox_execute_bash` 是否支持 execution ID、状态查询、取消、并发隔离和连接重建。
- **涉及文件**:
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/McpSandboxConfiguration.java` — 读取现有注册与包装逻辑
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/ProjectTestAgentTool.java` — 读取现有委托调用
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/contract/RealSandboxToolContractIT.java` — 增加必要的调研型契约测试
- **依赖**: 无
- **验收标准**:
  - 在 `log.md` 记录真实工具 schema、能力边界和选定实现路径。
  - 若原生不支持结果查询，明确采用受控无状态 Test Runner，不能假造 execution ID 能力。
  - 探针不得调用模型。
- **验证命令**: `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify`

- [x] 完成

## Task 2: 实现结构化、可恢复的项目测试执行器

- **目标**：使每次项目测试具有稳定 execution ID、明确终态、进程级超时和结果查询；区分测试失败与基础设施失败。
- **涉及文件**:
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/ProjectTestAgentTool.java` — 重构结构化调用与返回
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/McpSandboxConfiguration.java` — 增加健康状态、熔断与受控重建，或接入新执行适配器
  - `qingling-team-infra/src/test/java/cn/org/chris/wake/infra/agentscope/ProjectTestAgentToolTest.java` — 成功、测试失败、超时、响应丢失、脱敏测试
  - 按 Task 1 结论新增必要的 domain/infra 执行模型和适配器文件
- **关键契约**:

```text
project_id -> execution_id + status + exit_code + pytest_collected
           + pytest_passed + coverage_percent + failure_type
           + retryable + stdout/stderr(受限长度、脱敏)
```

- **依赖**: Task 1
- **验收标准**:
  - 同一项目测试串行执行，不同项目按明确策略隔离。
  - 超时实际终止容器进程组，不遗留 pytest/pip。
  - 传输断开可查询原 execution ID；不能查询时不得盲重试。
  - API Key、Header 和连接秘密不进入结果或日志。
- **验证命令**: `mvn -pl qingling-team-infra -am -Dtest=ProjectTestAgentToolTest,McpSandboxConfigurationTest test`

- [x] 完成

## Task 3: 增加 MCP 熔断、重建和有限重试

- **目标**：首次连接故障后停止向失效会话投递，并为幂等测试执行完成一次受控恢复；连续失败后明确阻断。
- **涉及文件**:
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/McpSandboxConfiguration.java`
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/TeamAgentFactory.java`
  - 对应 infra 测试
- **依赖**: Task 2
- **验收标准**:
  - `CLOSED → OPEN → HALF_OPEN → CLOSED` 或等价状态可测试。
  - OPEN 时新调用快速失败，不再等待完整 MCP 超时。
  - 恢复探针成功才重新开放。
  - 自动恢复最多一次，第二次失败返回明确不可重试结果。
- **验证命令**: `mvn -pl qingling-team-infra -am test`

- [x] 完成

## Task 4: 将工具故障接入 Java 工作流状态机

- **目标**：工具基础设施失败由 Java 记录 incident、保留当前阶段、恢复原 Owner；重试耗尽后进入 `BLOCKED`。
- **涉及文件**:
  - `qingling-team-domain/src/main/java/cn/org/chris/wake/domain/workflow/WorkflowSignal.java` — 增加或归并运行时故障信号
  - `qingling-team-domain/src/main/java/cn/org/chris/wake/domain/workflow/WorkflowStateMachine.java` — 同阶段恢复和阻断规则
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/tool/DeterministicWorkflowTools.java` — 只暴露必要的确定性协议
  - `qingling-team-app`/`qingling-team-starter` 中 Agent/tool 边界 — 自动上报，不依赖模型主动发邮件
  - 对应状态机、工具和编排测试
- **依赖**: Task 2、Task 3
- **验收标准**:
  - 业务 pytest 失败仍交给 RD 修复，不误判为基础设施 incident。
  - 可恢复工具故障不推进阶段且只产生一个恢复任务。
  - 同类第二次失败进入 `BLOCKED`，不能进入 QA/交付。
  - 恢复任务带幂等键，重复事件不重复分派。
- **验证命令**: `mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra -am test`

- [x] 完成

## Task 5: 接入 mailbox 租约 watchdog

- **目标**：把已有 `resetStale` 接入正式运行时，并避免回收仍有活跃 dispatch 的邮件。
- **涉及文件**:
  - `qingling-team-domain/src/main/java/cn/org/chris/wake/domain/mailbox/MailboxService.java`
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/persistence/mailbox/FileMailboxRepository.java`
  - `qingling-team-app` 或 `qingling-team-starter` watchdog 编排
  - mailbox、并发和 runtime 测试
- **依赖**: Task 4
- **验收标准**:
  - 超时且无活跃 dispatch 的 `in_progress` 邮件恢复为 `unread`。
  - 活跃任务不得被误回收。
  - 重复 watchdog tick 不产生重复 wake。
  - 旧格式 mailbox 文件仍可读取。
- **验证命令**: `mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra,qingling-team-starter -am test`

- [x] 完成

## Task 6: 修正 dispatch 与运行时关闭顺序

- **目标**：shutdown 先停止任务生产，再有限排空和取消消费者，避免失败后继续产生 heartbeat/工具调用。
- **涉及文件**:
  - `qingling-team-app/src/main/java/cn/org/chris/wake/app/runner/SerialDispatchRegistry.java` — 停止接收、活动快照、有限取消
  - `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/runtime/RuntimeLifecycle.java` — Listener/Cron/dispatch/Agent 正确关闭顺序
  - 对应 app/starter 测试
- **依赖**: Task 3
- **验收标准**:
  - shutdown 开始后新 submit 被明确拒绝。
  - Cron 在 awaitDrained 前停止，且不再创建一次性 wake。
  - 超时后执行有限取消并继续关闭 MCP/Agent，不无限等待。
  - 单测证明 drain 期间没有新增 dispatch。
- **验证命令**: `mvn -pl qingling-team-app,qingling-team-starter -am test`

- [x] 完成

## Task 7: 强化零模型真实工具契约和 E2E 失败诊断

- **目标**：昂贵 E2E 前验证连续工具可靠性；E2E 发生真实终态故障时快速失败并保存证据。
- **涉及文件**:
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/contract/RealSandboxToolContractIT.java`
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/e2e/RealTeamE2EDriver.java`
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/e2e/RealTeamE2EIT.java` 或对应场景入口
  - `qingling-team/E2E.md`
- **依赖**: Task 2～Task 6
- **验收标准**:
  - 零模型契约连续完成至少 10 次无副作用命令和 5 次结构化项目测试。
  - 覆盖一次受控响应断开/连接失效后的结果找回或重建。
  - 验证没有孤儿 pytest/pip/tmux 子进程。
  - E2E 轮询同时检查 incident、MCP、mailbox、dispatch；终态故障不再伪装成下游文件超时。
  - 成功和失败都保存脱敏 summary/model-selection/事件/邮件/Session/MCP 证据。
- **验证命令**:
  - `mvn verify`
  - `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify`

- [x] 完成

## Task 8: 运行真实 Task 18 E2E

- **目标**：从干净场景目录依次完成四条真实百炼 + AgentScope + Docker AIO-Sandbox 场景。
- **依赖**: Task 7；默认 `mvn verify` 和零模型压力契约必须先通过
- **执行顺序**:
  1. `happy-path`
  2. `checkpoint-revise`
  3. `qa-defect-rd-fix`
  4. `code-fail-recovery`
- **验收标准**:
  - 每条场景 Failsafe 成功并生成 `summary.json`。
  - 当前经用户确认的运行中，`model-selection.json` 显示显式模型 `qwen3.8-flash`，免费模型选择关闭；历史 `qwen3-max` 证据保持不变。
  - 真实模型、真实 AgentScope、真实 Docker 沙盒；不 mock、不 skip、不放宽断言。
  - 任一场景连续两次干净失败即暂停，不启动第三次。
  - 业务排序断言保持“最新在前”，不得删除或弱化。
- **验证命令**:
  - `mvn -Pe2e -De2e.scenario=happy-path verify`
  - `mvn -Pe2e -De2e.scenario=checkpoint-revise verify`
  - `mvn -Pe2e -De2e.scenario=qa-defect-rd-fix verify`
  - `mvn -Pe2e -De2e.scenario=code-fail-recovery verify`

- **当前状态（2026-10-09）**：用户确认后已启动一轮干净 `happy-path`，真实 `qwen3-max`、AgentScope 与 Docker AIO-Sandbox 均已参与。RD 完成 13/13 pytest、覆盖率 94%，但 AgentScope 将 `send_mail.content` 实际序列化为字符串，而 `TaskCompletionEvidenceGate` 只接受 `Map`，导致所有代码阶段 `task_done` 被拒绝；最终 34 分 35 秒后等待 `qa/test_plan.md` 超时。证据位于 `target/e2e-evidence/happy-path`，结束后活动 shell=0 且 pytest/pip/tmux=0。
- **后续场景状态**：因 `happy-path` 失败，未启动 `checkpoint-revise`、`qa-defect-rd-fix`、`code-fail-recovery`；Task 8 保持未完成，需先修复工具 schema/门禁类型契约。
- **修复状态（2026-10-09）**：`CommonTeamTools` 已在 Java 边界把 task_done 的 JSON 对象字符串受控解析为 Map，并使用同一对象执行门禁与邮箱持久化；门禁拒绝由 Java 写入保留事件 `completion_rejected`。Driver 在同阶段同 Owner 连续拒绝至少两次、无活跃 dispatch 且没有更新成功回报时直接失败。定向 18 项、默认 `mvn verify` 210 项和零模型真实 Docker 1 IT 均通过；按用户要求未启动新的 E2E，等待再次确认。
- **二次评估与修复状态（2026-10-10）**：确认 Java/COLA 不是根因；原 Python 的异常吞没与弱 E2E 判定是长期放大器，近期阻断则来自 Java 迁移后的 AgentScope/MCP 证据边界。新增真实 execution registry，将 project/role/stage/execution ID 与终态绑定并逐字段校验；QA 固定运行 `qa/contract_tests/test_contract.py`，RD 回修时同时运行 QA 契约；基础设施超时也进入一次受控连接恢复；pytest 使用 importlib 模式允许跨目录同名测试。随后发现当前兼容端点对显式 `qwen3-max` 返回 HTTP 404 `model_not_found`，新增 E2E 专用有界模型预检和脱敏启动失败证据，避免长时间等待。定向 30 项、模型/启动定向 16 项、完整 `mvn verify` 215 项和零模型真实 Docker 1 IT 均通过；按用户最新要求不进入 E2E，Task 8 保持未完成。
- **恢复耗尽补强（2026-10-11）**：补齐“第一次超时后连接重建成功，但同一 execution ID 已被 Docker 取消”的边界。恢复查询若仍得到取消或其他非业务终态，Java 现在会写入 attempt=2、`retryable=false` 的结构化 incident 并进入 BLOCKED，不再静默返回而遗漏状态迁移。同时修复等待上限与 pytest 完成竞态：取消查询若已得到 `SUCCEEDED`/`TEST_FAILED`，保留真实终态而不伪造超时。定向 16 项、完整 `mvn verify` 217 项、零模型真实 Docker 1 IT 均通过；AIO 活动 shell=0，pytest/pip/tmux=0。本轮未运行 E2E。
- **本轮 E2E 状态（2026-10-11）**：用户确认后只启动一次干净 `happy-path`。环境变量非空及显式模型配置校验通过，但 E2E 专用 Function Calling 启动预检对 `qwen3-max` 收到 HTTP 404，Failsafe 结果为 1 test、0 failures、1 error、0 skipped；业务工作流、AgentScope 角色执行和后续三个场景均未启动。脱敏证据位于 `target/e2e-evidence/happy-path/{summary.json,failure-summary.json}`，清理复核为活动 shell=0 且 pytest/pip/tmux=0。Task 8 保持未完成，不自动重试。
- **`qwen3.8-flash` 单次 E2E 状态（2026-10-11）**：先完成一次最小 Function Calling 探针，HTTP 200 且返回预期工具调用；随后仅运行一次干净 `happy-path`，进程级显式覆盖两个模型变量为 `qwen3.8-flash`。workflow 完成 checkpoint、PM 产品设计和 RD 技术设计，并正确进入 `CODE_IMPLEMENTATION/RD`；`agent_spawn`、`agent_send` 和多次 `task_output` 在 5 分钟外层超时后，子任务仍继续写入 `code/app` 与 `code/tests`。唯一 execution `44068f07-e3ca-470d-bfee-aae24e73eeef` 在测试文件写入前结束为 `FINISHED/exit_code=5/pytest_collected=0`，完成回报随后因 execution 归属不匹配被拒绝。未生成 `tool_execution_failed` 或 BLOCKED，RD assignment 与 dispatch 保持活跃，Driver 最终以 `file:code/main.py` 的 30 分钟等待超时失败；Maven 总耗时 44 分 29 秒。未重试、未启动后续三个场景；清理后 AIO session=0，pytest/pip/tmux 与宿主 Maven/Java 均无残留。Task 8 保持未完成。
- **AgentScope 父子生命周期修复（2026-10-11）**：确认 `qingling.agent.timeout=30m` 被错误传给 `AsyncToolMiddleware`，使异步工具在 AgentScope 固定 5 分钟 ToolExecutor 上限前无法转后台。现新增独立 `async-tool-offload-timeout`，默认 30 秒且校验不超过 4 分钟；父调用取消、`clearSession` 和关闭会按同一 session 取消真实 TaskRepository 中所有非终态子任务，再 interrupt 父 Agent。`new_mail` RuntimeContext 传播 project ID，`run_project_tests` 在调用开始冻结 project/role/stage；dispatch 记录真实 `active_since`，Driver 对失败/取消子任务均已终态但父 dispatch 仍活跃的情况给予 20 秒宽限后直接报告。定向 37 项、真实 Harness/TaskRepository 取消 4 项、最终 `mvn verify` 223 项、零模型真实 Docker 1 IT 均通过；没有调用百炼或运行 E2E。Task 8 继续保持未完成，等待用户确认下一轮真实 `happy-path`。
- **修复后单次 E2E 状态（2026-10-11）**：用户确认后仅执行一次干净 `happy-path`，显式模型为 `qwen3.8-flash`。checkpoint、PM 产品设计和 RD 技术设计均完成，正确进入 `CODE_IMPLEMENTATION/RD`。30 秒 async-tool 卸载已真实生效，但后台 `task_output` 自身仍达到 AgentScope 固定 PT5M 上限；级联取消把两个 `code_impl` 子任务置为 CANCELLED，父 `team:rd` dispatch 却继续 active。Driver 在 1021 秒时以 `不可恢复 AgentScope dispatch 悬挂` 直接失败，Maven 总耗时 17 分 31 秒，没有再报告代码或 QA 文件超时。未生成代码文件或 Test Runner execution，未重试、未启动后三个场景；脱敏证据位于 `target/e2e-evidence/happy-path`。清理后 AIO session=0，pytest/pip/tmux 与宿主 Maven/Java 均无残留。Task 8 保持未完成，下一步需修复后台 `task_output` 的固定超时及子任务取消后父 dispatch 不终止的问题。
- **后台查询与父 dispatch 修复（2026-10-11）**：构建每个 HarnessAgent 后，以 `NonBlockingTaskOutputTool` 替换 AgentScope 2.0.3 内置 `task_output`；旧调用即使携带 `block=true, timeout=600000` 也只执行一次非阻塞快照。`AgentScopeAgentGateway` 同时监控当前 session 的子任务，全部终态且至少一个 FAILED/CANCELLED 时给予生产 2 秒收尾宽限，父调用仍未结束则发出脱敏异常、只级联取消一次并清除 active invocation，从而释放串行 dispatch。新增真实 Harness 工具调用与假运行时父悬挂回归；定向 10 项、`mvn verify` 225 项、零模型真实 Docker 1 IT 均通过。本轮未运行 E2E，Task 8 继续保持未完成，等待用户确认下一轮。
- **最新单次 E2E 状态（2026-10-11）**：先修复 Task 19 将 starter 普通 JAR 替换为 Boot JAR、造成 Failsafe 找不到 `workspace-template/file-manifest.txt` 的打包回归；Spring Boot repackage 改用 `exec` classifier 后，普通 JAR 保留根路径资源，可执行 JAR 改为 `*-exec.jar`。随后 `mvn verify` 225 项与零模型真实 Docker 1 IT 均通过，并且只启动一次干净 `qwen3.8-flash happy-path`。checkpoint 正常完成并进入 `PRODUCT_DESIGN/PM`；PM 首次 `sandbox_execute_bash` 却传入宿主机模块目录作为沙盒 working directory，服务端返回目录不存在。当前熔断器把调用参数错误归为 MCP 基础设施故障，单次失败即 OPEN；Driver 在场景 190.2 秒报告 `不可恢复 MCP 故障：circuit 持续 OPEN`。未重试、未启动后三个场景；证据位于 `target/e2e-evidence/happy-path`，清理后 AIO session=0、dispatch pending=0、AgentScope active=0，且无 pytest/pip/tmux/Maven/Java 残留。Task 8 保持未完成；下轮前需统一沙盒工作目录语义，并避免把确定性的调用参数错误升级为连接熔断。
- **cwd 与熔断分类修复（2026-10-12）**：`RuntimeConfiguration` 将宿主 workspace 根和容器 `workspace-dir` 注入 `McpSandboxConfiguration`；Bash 包装器把宿主 workspace 子路径映射为 `/workspace` 下同一相对路径，把 Maven/AgentScope `user.dir` 映射为 `/workspace`，缺省 cwd 也固定为 `/workspace`，并同步更新实际 ToolUse content。服务端明确的 cwd/参数 validation ERROR 保持可见但不打开 circuit，下一次修正调用仍可进入原 client；连接异常、取消和其他 ERROR 仍执行熔断。`McpSandboxConfigurationTest` 13 项与 `RuntimeBootstrapTest` 2 项通过；`mvn verify` 227 项通过；零模型真实 Docker 1 IT 通过，其中连续 10 次 Shell 都传宿主 cwd 并断言容器 `pwd=/workspace`，另有 5 次 pytest 和一次连接恢复。结束后 AIO session=0、无 pytest/pip/tmux/Maven/Java 残留。本轮未运行 E2E，Task 8 保持未完成并等待用户确认。
- **cwd 修复后单次 E2E（2026-10-12）**：仅执行一次显式 `qwen3.8-flash happy-path`。checkpoint、PM、RD 技术设计及进入 `CODE_IMPLEMENTATION/RD` 均成功，MCP circuit 全程 CLOSED/0 failures，证明 cwd 与参数错误分类修复在真实流程生效。RD 已生成应用和测试，但三个 `code_impl` 子任务均被取消；父 dispatch 正确结束且 stale lease 回到 unread。一次 heartbeat 自动恢复再次产生同类 CANCELLED，却没有写入 `tool_execution_failed`/BLOCKED 或重新领取 RD 邮件，最终 43 分 18 秒后仍报告 `qa/test_plan.md` 超时。证据位于 `target/e2e-evidence/happy-path`；退出后无 pytest/pip/tmux/Maven/Java 残留。Task 8 保持未完成，未启动第二次 E2E及后三个场景。
- **RD 子任务取消恢复闭环修复（2026-10-14）**：为 `agent_spawn`、`agent_send`、`task_cancel` 增加任务语义登记，按同一 `agent_id` 的最新 continuation 判定逻辑子任务终态，并记录取消来源；最新子任务 FAILED/CANCELLED 时由 `AgentScopeAgentGateway` 抛出脱敏的类型化异常，不再把旧取消记录误判为当前失败。`Runner` 将有/无 project 上下文的 Agent 故障接入 Java incident；同一 workflow execution 第一次失败保持 stage/Owner、释放当前 `task_assign` 并唤醒原 Owner，第二次不同 occurrence 进入 BLOCKED，重复 occurrence 不消耗重试。watchdog 不再恢复 BLOCKED 项目，RD 模板也限制同一逻辑任务只保留一条 continuation。Driver 现在同时检查 Cron 直接失败、incident、mailbox、dispatch 与最新 continuation，可在运行时已空闲但 Cron 失败时报告原始 Agent 原因。定向 56 项、完整 `mvn verify` 245 项和零模型真实 Docker 1 IT 均通过；按用户要求未运行 E2E，Task 8 保持未完成并等待再次确认。
- **取消恢复闭环修复后的单次 E2E（2026-10-15）**：用户确认后仅执行一次干净的显式 `qwen3.8-flash happy-path`。流程完成需求 checkpoint、PM 产品设计和 RD 技术设计，进入 `CODE_IMPLEMENTATION/RD`；RD 只 spawn 一个 `code_impl`，后续在旧任务终态后使用 continuation，没有主动取消，watchdog 也把 stale assignment 恢复为 unread 并重新唤醒。首个 `run_project_tests` execution `84eab81b-ec29-47ef-9f98-fc3f9246eb03` 的容器状态文件最终为 `FINISHED/exit_code=1`，真实 pytest 为 32 collected、11 passed、21 failed、coverage 72%，本应作为业务测试失败交给 RD 修复；Java 工具返回却是 `INFRASTRUCTURE_FAILED/RESULT_UNAVAILABLE/exit_code=null`，随即写入 attempt 1 RECOVERING 和 attempt 2 BLOCKED。Driver 在事件生成后直接报告 `run_project_tests / RESULT_UNAVAILABLE / CODE_IMPLEMENTATION / rd`，没有再等待 `qa/test_plan.md`；Failsafe 1 failure、0 errors、0 skipped，Maven 总耗时 29 分 55 秒。证据位于 `target/e2e-evidence/happy-path`；退出后无 Maven/E2E、pytest/pip/tmux 残留。未启动第二次 E2E 或后三个场景；Task 8 保持未完成，下一步需修复 Test Runner 已完成业务失败被误判为结果不可用的问题。
- **Test Runner 终态协调修复（2026-10-15）**：确认根因不是 pytest 或连接失效，而是元数据与 32 KiB Base64 输出共用一条 MCP 响应；真实 93 KiB 输出被截断后 `exit_code=1` 丢失。`McpProjectTestRunner` 现先读取小型 status/exit/pytest/coverage 元数据，终态后再分别获取每流最多 1 KiB 的脱敏首尾摘录；`FINISHED` 但退出码尚未可见时按 FINALIZING 继续轮询，输出摘录失败也不会反向改写已知终态。`McpProjectTestRunnerTest,ProjectTestAgentToolTest` 15 项通过；`mvn verify` 247 项通过；零模型真实 Docker 1 IT 通过，包含 10 次 Shell、5 次成功测试、1 次产生 100 KiB 输出的 `exit_code=1` 业务失败和一次受控恢复，业务失败稳定返回 `TEST_FAILED/PYTEST_FAILURE` 且 circuit 保持 CLOSED。未运行真实 E2E；Task 8 仍保持未完成，等待用户确认下一轮。
- **Test Runner 修复后单次 E2E（2026-10-17）**：用户确认后仅执行一次干净的显式 `qwen3.8-flash happy-path`。流程完成 checkpoint、PM、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；MCP 四路全程 CLOSED/0 failures，未产生 Test Runner execution，因此本轮没有复现旧的 `exit_code=1` 误判。首个 `code_impl` task `task_99b418f8-3294-4293-bf3c-11ff42e74232` 最终 COMPLETED/delivered；其尚未终态时父 RD 已登记同 agent/session 的 `task_84bfc0d2-39a5-459b-9709-d932f354e9d4`，后者真实日志自 00:43:37 停止、只由运行时刷新 `lastUpdatedAt` 并长期保持 RUNNING。Driver 未快速识别该无进展 task，最终在 2468 秒以等待 `code/main.py` 30 分钟超时失败，Maven 41 分 17 秒；未重试或启动后三个场景。退出后无真实孤儿进程，但任务仓库仍把 `task_84b...` 记录为 RUNNING/cancelRequested=false/delivered=false。Task 8 继续未完成；下一步修复无进展 RUNNING 检测、重复 continuation 防护与 shutdown 终态协调。
- **RUNNING continuation 可靠性修复（2026-10-17）**：`TaskSemanticsRegistry` 以任务稳定状态、TaskRecord 事件序号和子代理 JSONL 日志文件数/字节/mtime 建立真实进度租约，明确排除每次查询都会刷新的 `lastUpdatedAt`/`lastCheckedAt`；`agent_spawn`/`agent_send` 对同 parent user/session/agent 使用 TaskRepository 活跃任务检查与原子调用占位，重复调用返回 `already_running` 和既有 task ID；真实后台 spawn 只有 taskId 时，也从 AgentScope 持久化 spawn registry 精确解析 agent_key/label，多子代理并行时不猜测归属。`AgentScopeAgentGateway` 在 RUNNING 连续 5 分钟无真实变化时抛出 `CHILD_STALLED`、取消父子执行并释放 dispatch；Driver 同步按 `last_progress_at` 立即报告直接 task/agent 原因。RD 写入与完成门禁统一要求唯一 `code/main.py`，拒绝 `code/app/main.py`；Gateway shutdown 遍历所有已观察父 session，取消非终态任务并保证持久化记录为 CANCELLED/FAILED。定向 70 项、完整 `mvn verify` 255 项和零模型真实 Docker 1 IT 全部通过；本轮未运行 E2E，Task 8 保持未完成并等待用户确认。
- **RUNNING continuation 修复后单次 E2E（2026-10-18）**：用户确认后只运行一次干净的显式 `qwen3.8-flash happy-path`，没有重试或启动后三个场景。流程完成 checkpoint、PM 产品设计、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；唯一 `code_impl` task 为 COMPLETED/delivered，没有重复 continuation，代码入口只有 `code/main.py`。RD 发起 5 个真实 Test Runner execution，全部为 `FINISHED/exit_code=2` 的 pytest 收集业务失败，四路 MCP circuit 保持 CLOSED/0 failures，证明终态分类没有回退为基础设施故障。随后 stale lease/heartbeat 留下两条同时 `in_progress` 的 RD assignment，父 `team:rd` dispatch active/pending=1，而唯一子任务已经成功终态；Driver 对“COMPLETED 子任务 + 父 dispatch 活跃 + 重复 assignment”没有快速诊断，最终仍以 `qa/test_plan.md` 30 分钟超时失败。证据跨度 2534 秒、Failsafe 测试 2913 秒、Maven 48 分 42 秒。关闭后 AIO session=0，宿主与容器无 pytest/pip/tmux/Maven/Java 残留。Task 8 继续未完成；下一步修复父 dispatch 成功终态协调、assignment 单飞/恢复幂等和 Driver 直接诊断，并把 shutdown 排空从 30 分钟 Agent 业务超时中拆分。
- **P0/P1 修复（2026-10-19）**：父 `AgentReply` 只有在每个逻辑子 Agent 的最新任务全部终态后才能结束 dispatch，FAILED/CANCELLED 子任务会直接失败；父角色 session JSONL 的 mtime 作为独立真实进度，成功子任务后父回合静默 5 分钟会抛出 `PARENT_STALLED`，进入既有 incident 恢复/BLOCKED 链。mailbox 在文件锁内按 project/stage/from/to 复用开放 `task_assign`，工具恢复刷新原 assignment，澄清答复改为幂等 `clarification_answer` 通知；watchdog 除路由空闲外还要求对应真实父 Agent 不活跃。Driver 新增父停滞和重复开放 assignment 的直接失败；shutdown 使用独立默认 30 秒 drain。Test Runner 先进行 30 秒 collect-only，成功后再进行 240 秒正式覆盖率测试；RD 模板要求先读实现并只引用真实符号，收集错误期间不得继续扩写测试。定向 76 项及新增仓储边界 12 项通过；`mvn verify` 263 项通过；零模型真实 Docker 1 IT 在 15.39 秒通过。本轮未运行 E2E，Task 8 保持未完成并等待用户确认。
- **P0/P1 修复后单次 E2E（2026-10-20）**：只运行一次干净的显式 `qwen3.8-flash happy-path`，没有重试或启动后三个场景。流程完成 checkpoint、PM 产品设计、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；初始 `code_impl` 正常 COMPLETED/delivered，且只生成 `code/main.py`。6 个真实 Test Runner execution 均为 `FINISHED/exit_code=1`，最好一次达到 27 collected、24 passed、coverage 97%，模型收到的结构化结果也是 `TEST_FAILED/PYTEST_FAILURE/retryable=false`，证明模型能力和 pytest 业务修复不是首要阻断。新暴露的运行时缺口有三项：查询在 runner 已退出但 `exit_code` 尚未原子可见的短窗口先生成 `RESULT_UNAVAILABLE` incident，随后恢复取得业务终态却不撤销该 incident；每个新 execution ID 都从 attempt 1 开始，使重复同类故障无法 BLOCKED；两个延迟完成的后台 `agent_spawn` 绕过同 session 单飞，生成两个并发 continuation。Driver 未识别重复非终态子任务和重复 RECOVERING incident，最终仍以等待 `qa/test_plan.md` 30 分钟超时失败。Failsafe 1 failure、0 errors、0 skipped，Maven 46 分 04 秒；关闭后两个遗留 continuation 分别持久化为 CANCELLED/FAILED，宿主与容器无残留进程。Task 8 保持未完成，下一轮 E2E 前必须修复终态协调、稳定故障指纹计数、后台 spawn late-arrival 单飞和 Driver 直接诊断。
- **终态竞态与 late-arrival continuation 修复（2026-10-21）**：`McpProjectTestRunner` 对 runner 退出但退出码未落盘增加 5 秒 FINALIZING 宽限；`ProjectTestAgentTool` 仅在一次受控恢复仍未取得业务终态时连续写 RECOVERING/BLOCKED，恢复为 SUCCESS/TEST_FAILED 时不生成临时 incident。`DeterministicWorkflowTools` 按 stage/Owner/tool/稳定失败指纹跨 execution 共享一次恢复预算。取消/offload 的 spawn/send 不再立即释放单飞占位，而是有界保留 6 分钟并等待 TaskRepository 延迟可见；Gateway 对同 agent 多个未终态 task 抛出 `DUPLICATE_CHILD_TASK` 并级联终止父 dispatch，Driver 同时直报重复 continuation 和重复 RECOVERING execution。扩展定向 92 项、完整 `mvn verify` 270 项、真实 Docker `RealSandboxToolContractIT` 1 IT 均通过；后者使用 NoCallModel，完成连续 Shell、Test Runner、断连恢复和无 pending/orphan 断言。按用户要求未运行 E2E，Task 8 保持未完成。
- **2026-10-22 单次真实 E2E 状态**：启动前 `mvn verify` 在 14.878 秒通过，零模型真实 Docker `RealSandboxToolContractIT` 1 IT 在 29.927 秒通过；随后严格只运行一次干净的显式 `qwen3.8-flash happy-path`，没有重试或启动后三个场景。流程完成 checkpoint、PM 产品设计、RD/QA 插入评审和 RD 技术设计，并进入 `CODE_IMPLEMENTATION/RD`。一次 `sandbox_execute_bash` 参数校验错误没有打开 circuit，模型纠正参数后工具继续可用；RD 技术设计回合耗尽后也由同一 session 自动续跑并成功 `task_done`。代码阶段唯一 `code_impl` 子任务 `task_b0e15d5a-9d22-40cf-8e2d-6ba5ecef1c27` 在 21:17:45 后无真实日志/产物进展，却仍保持 RUNNING；其内部 `sandbox_execute_bash` 最终达到 AgentScope `PT5M` 工具超时。Driver 未在子任务 5 分钟无进展时给出 `CHILD_STALLED`，而是先以等待 `code/main.py` 的 `PT30M` 超时失败，造成直接原因与文件超时竞态；未生成代码入口或 Test Runner execution。Failsafe 1 failure、0 errors、0 skipped，测试 2925 秒，Maven 总耗时 48 分 59 秒。finally 证据位于 `target/e2e-evidence/happy-path`；shutdown 将子任务持久化为 `CANCELLED/cancelRequested=true`，RD assignment 回到 unread，AIO shell session=0，宿主与容器无 pytest/pip/tmux/Maven/E2E 残留。Task 8 保持未完成，下一轮前需修复 RUNNING 快速诊断与工具超时/Driver 终态协调。

- **RUNNING/Shell/MCP/Driver 协调修复（2026-10-23）**：通用 `sandbox_execute_bash` 在模型省略或请求过大 timeout 时统一使用 60 秒，Java 传输边界再保留 5 秒固定宽限；参数错误继续不熔断。每个 MCP 工具绑定父角色，Gateway 首次观察 OPEN 时只重建 client 并执行无副作用探针，不重放原 Shell；无论恢复成功与否都将原调用判为未知并抛出 `MCP_CONNECTION_UNAVAILABLE`，成功出口再用连接失败计数封住父回复抢先完成的竞态，交给 Runner 既有 incident 恢复/BLOCKED 链。RUNNING/父进度租约收紧为 4 分 30 秒。Driver 先等待真实 workflow 阶段迁移，再启动对应产物预算；失败路径先关闭运行时、终态化任务，再保存最后 MCP/dispatch/Agent 快照。定向测试、`mvn verify` 与零模型真实 Docker 契约全部通过；真实契约首个 RD/QA Shell 探针均省略 timeout，证明默认限时生效。按用户要求未运行 E2E，Task 8 保持未完成。

- **停滞恢复与流式进度修复（2026-10-24）**：先完成缺陷 1～3，缺陷 4 暂不处理。Driver 的悬挂阈值改为从 Gateway 公开租约派生并固定增加 60 秒，只有当前 workflow execution ID 与故障指纹均匹配的首次可恢复 `agentscope_dispatch` incident 才继续等待，`BLOCKED`/预算耗尽后失败；新增只保存角色时间戳的有界模型活动注册表和每角色纯透传 Model 装饰器，订阅及每个流式分片刷新活动，Gateway 仅在角色只有一个活跃父回合时将其并入统一的父进度判定/诊断；宽限复查改用 `flatMap` + `Mono.justOrEmpty`，故障自行消失不再触发 NPE。定向套件最终 62 项通过，`mvn verify` 299 项通过，零模型真实 Docker 1 IT 通过；未运行真实 E2E。

- **Bash 会话争用与恢复探针修复（2026-10-24）**：审计 AIO-Sandbox 1.11.0 源码确认 `new_session=false` 跨 MCP client/Owner 共享模块级默认 shell，会话上限为 10，当前代码不存在按 Owner 空闲保活。通用 Bash 和 Test Runner 控制命令保留默认会话，但在所有角色间使用同一个公平单飞许可覆盖完整 MCP 调用；恢复链在同一许可内移除旧 client、清理全部服务端 shell session、注册新 client，并以 `new_session=true` 执行无副作用探针，原调用仍不重放。健康快照补充脱敏恢复阶段、尝试时间和耗时。真实契约否决了“每次调用都新建会话”的中间方案：其会耗尽 10 个会话并引发 tmux/PS1 失步，现已撤销。最终定向 21 项、`mvn verify` 301 项和零模型真实 Docker 1 IT 均通过；未运行真实 E2E，Task 8 保持未完成。

- **缺陷 1～5 修复后单次 E2E（2026-10-24）**：严格只运行一次干净 `qwen3.8-flash happy-path`，无重试和后续场景。流程完成 checkpoint、产品设计、技术设计评审/修订并进入 `CODE_IMPLEMENTATION/RD`。4 个 Test Runner execution 中前三个为 31 collected、21 passed、10 failed、95% coverage 的业务失败，第四个为 24 collected 后 2 个 `app` 导入收集错误；MCP 四路始终 CLOSED/0 failures，缺陷 4、5 未复现。RD 首次 `PARENT_STALLED` 正确进入 RECOVERING；随后 QA 补交测试设计的父 dispatch 丢失 project ID，session 无进展且无子任务，未产生可归属 incident，Driver 约 62 分钟后才报告 `team:qa/project_id=""` 悬挂，远超 5 分 30 秒目标。Failsafe 1 failure，测试 7064 秒，Maven 1 小时 58 分；finally 后无 dispatch/Agent/shell session 或孤儿进程。Task 8 保持未完成。

- **E2E 后续缺口修复（2026-10-24）**：Heartbeat 可从可信元数据、`new_mail` 或唯一 workflow Owner 安全恢复 project ID，并把它传播到 RuntimeContext、团队提示和 incident；Gateway 父进度快照统一记录 session/model/effective 时间及来源，Driver 对超时且缺少 Gateway 活动诊断的 orphan `team:*` dispatch 快速失败；AgentScope 状态存储为声明式子代理补齐 pending tool 的确定性错误结果且不重放工具；完成门禁与 RD 模板拒绝 `code/app/main.py` 和测试中的 `app` 包导入，只允许 `code/main.py` 唯一入口。定向 71 项、`mvn verify` 307 项及零模型真实 Docker 1 IT 均通过，`git diff --check` 通过。按用户要求未运行真实 E2E，因此 Task 8 继续保持未完成。

- [ ] 完成

## Task 9: 最终回归、质量审计与父需求回写

- **目标**：验证无回归，并把真实完成情况记录到父需求。
- **涉及文件**:
  - `spec-workflow/changes/task18-e2e-runtime-reliability-1.0.1/{spec.md,tasks.md,test-spec.md,log.md}`
  - `spec-workflow/changes/python-to-java-cola-migration/spec.md`
  - `spec-workflow/changes/python-to-java-cola-migration/test-spec.md`
  - `spec-workflow/changes/python-to-java-cola-migration/log.md`
  - `spec-workflow/changes/python-to-java-cola-migration/tasks.md`
- **依赖**: Task 8
- **验收标准**:
  - 再运行一次完整 `mvn verify`。
  - `git diff --check` 通过。
  - 所有新增/修改源码声明符合 `chinese-code-comments` skill。
  - 本需求四份文档记录实际文件、命令和结果，不伪造通过数。
  - 只有四条真实 E2E 全部通过，才把父 Task 18 标记完成；否则只回写失败诊断并保持未完成。
  - 未经用户明确要求，不 commit、不 push。
- **验证命令**: `mvn verify && git diff --check`

- [ ] 完成

---

## 汇总（/apply 全部完成后填写）

- **变更摘要**：待填写
- **总文件数**：待填写
- **Spec-Plan 偏差记录**：待填写
- **遗留问题**：待填写
