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
