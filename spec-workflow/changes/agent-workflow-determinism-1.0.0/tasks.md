# 任务拆分 — Agent 工作流确定性与编排收敛 1.0.0

> 执行顺序：Task 1 → Task 9，不得跳序。
> 当前状态：`apply`，用户已于 2026-10-25 确认编码，正严格按 Task 1 → Task 9 实施。
> 每个 Task 完成后必须同步更新本目录 `spec.md`、`tasks.md`、`test-spec.md` 和 `log.md`。
> 所有源码新增或修改声明必须遵守 `chinese-code-comments` skill，带相邻、准确的中文注释。
> **Git**：未经用户明确要求不得 commit 或 push。
> **E2E**：Task 9 的真实模型 E2E 需要单独二次授权。

## 前置条件

- [x] 用户已明确确认本目录需求并允许编码。
- [x] 开始前执行 `git status --short` 和 `git diff --check`，记录并保留全部现有修改。
- [x] 不 reset、checkout、clean 或覆盖用户现有文件。
- [ ] 基线 `mvn verify` 与零模型 Docker 契约结果已记录；若基线失败，先记录真实原因，不将其误算为本版本回归。
- [x] 确认 Task 18 当前未完成状态和最新证据目录不被清理。

## Task 1：冻结 v2 协议、配置和兼容边界

- **目标**：在编码前将 profile、review policy、WorkExecution 状态、command ID、outbox action ID 和 legacy/coordinated 切换规则固化为可测试协议。
- **涉及文件（预计）**：
  - `qingling-team-domain/src/main/java/.../workflow/` — 新增枚举和值对象。
  - `qingling-team-starter/src/main/java/.../config/` — 新增类型安全配置。
  - `qingling-team-starter/src/test/...` — 配置默认值和非法值测试。
- **关键协议**：
  - `WorkflowProfile = SIMPLE | STANDARD | STRICT`
  - `ReviewPolicy = REQUIRED_ONLY | ADVISORY_AUDIT | STRICT_BLOCKING`
  - `coordination-mode = legacy | coordinated`
  - legacy 项目和 v2 项目不得混用推进链。
- **依赖**：无。
- **验收标准**：
  - 配置缺省、环境变量映射和非法组合 fail-fast；
  - Task 18 profile 可由测试配置固定为 STANDARD；
  - 没有模型调用、网络调用或数据迁移副作用。
- **验证命令**：定向 domain/starter 配置测试。

- [x] 完成：新增 `WorkflowProfile`、`ReviewPolicy`、`WorkflowCoordinationMode`、`WorkflowCommandId`、`OutboxActionId`；增加类型安全配置、环境变量缺省、非法 strict 组合与 review timeout fail-fast。定向命令 `mvn -pl qingling-team-domain,qingling-team-starter -am -Dtest=WorkflowProtocolTest,RuntimeBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过，`WorkflowProtocolTest` 4 项、`RuntimeBootstrapTest` 14 项，共 18 项，BUILD SUCCESS（13.215s）。未运行真实 E2E。

## Task 2：实现 WorkExecution 与 ReviewRound 领域模型

- **目标**：建立唯一工作执行和评审轮次状态机，禁止同 project/stage/owner 平行开放任务。
- **涉及文件（预计）**：
  - `qingling-team-domain/src/main/java/.../workflow/WorkExecution.java`
  - `qingling-team-domain/src/main/java/.../workflow/WorkExecutionStatus.java`
  - `qingling-team-domain/src/main/java/.../workflow/ReviewRound.java`
  - `qingling-team-domain/src/main/java/.../workflow/ReviewRoundStatus.java`
  - 对应 domain 测试。
- **关键规则**：
  - 有限合法转换；终态不可回到 RUNNING；
  - retry/continuation 复用原 execution；
  - mandatory 与 advisory review 的阻断语义分离；
  - progress 时间只由明确业务证据刷新。
- **依赖**：Task 1。
- **验收标准**：全部状态转换、非法转换、幂等重复和 reviewer barrier 有表驱动测试。
- **验证命令**：`mvn -pl qingling-team-domain test`。

- [x] 完成：新增 `WorkExecution`、`WorkExecutionStatus`、`ReviewRound`、`ReviewRoundStatus`、`ReviewRequirement`、`ReviewVerdict`；冻结完整转换表、终态不可回退、同 execution retry/revision、显式业务进度租约、必要/建议评审和 Strict 双 Reviewer barrier。`mvn -pl qingling-team-domain test` 全模块 63 项通过，0 failure/error/skip，BUILD SUCCESS（2.035s）。同 project/stage/owner 并发唯一性由 Task 3 aggregate 仓储在项目锁内保证。未运行真实 E2E。

## Task 3：实现 runtime v2 文件仓储与原子 outbox

- **目标**：以单一 aggregate 文件持久化 WorkExecution、ReviewRound、processed commands 和 outbox，旧事件/邮箱不再作为 v2 权威状态。
- **涉及文件（预计）**：
  - `qingling-team-domain/.../gateway/WorkflowRuntimeRepository.java`
  - `qingling-team-infra/.../persistence/workflow/FileWorkflowRuntimeRepository.java`
  - repository 单元和并发测试。
- **关键规则**：
  - 项目级文件锁；
  - 临时文件 + 原子移动；
  - schema version；
  - command ID 去重；
  - pending outbox crash/restart 重放；
  - 不记录凭据、prompt 或完整模型输出。
- **依赖**：Task 2。
- **验收标准**：
  - 并发完成同一 command 只落一条结果；
  - 模拟投影前崩溃后可恢复 outbox；
  - 损坏/未知 schema 明确失败，不静默回退；
  - legacy 项目不会被隐式创建 v2 半成品。
- **验证命令**：domain + infra repository 定向测试。

- [x] 完成：新增 `WorkflowRuntimeState` 单 aggregate、`WorkflowRuntimeRepository` 端口、`FileWorkflowRuntimeRepository`、processed command 稳定结果、三类 outbox action 与 pending/applied 状态。实现项目级 JVM/FileLock、临时文件强制刷盘与原子替换、schema=2 fail-fast、512 条 command 有界去重、敏感字段/凭据拒绝、legacy 只读不隐式建 v2。10 路并发同 command 仅执行一次；move 前损坏临时文件、pending/部分 outbox 重启、未知 schema/损坏 JSON 均有测试。`mvn -pl qingling-team-domain,qingling-team-infra -am test` 通过：domain 68 项、infra 151 项，共 219 项，0 failure/error/skip，BUILD SUCCESS（8.685s）。未运行真实 E2E。

## Task 4：实现 Java 工作完成协调器

- **目标**：用一个应用命令完成证据校验、WorkExecution 终态、review 决策和下一工作项创建。
- **涉及文件（预计）**：
  - `qingling-team-app/.../workflow/WorkCompletionCoordinator.java`
  - `CompleteWorkItemCommand/Result`
  - review/claim 应用服务。
  - 对应 app 测试。
- **关键规则**：
  - 同一 command 重复调用返回同一结果；
  - 业务失败、基础设施失败和完成成功分支明确；
  - mandatory review 保持当前 stage；
  - advisory review 不阻断；
  - 下一 Owner、stage 和 subject 仍由 Java 状态机决定；
  - aggregate 提交与 outbox 生成处于同一原子更新。
- **依赖**：Task 3。
- **验收标准**：Manager 不参与正常行政推进也能完成完整 stage transition；中间异常不会产生双 assignment 或 done-without-task_done。
- **验证命令**：domain/app 联合定向测试。

- [x] 完成：新增 `WorkCompletionCoordinator`、完成/领取/启动/评审命令与有限结果、真实 `ProjectTestEvidenceRepository` 端口及 execution registry 适配。一次 runtime command 内完成产物读取、测试证据绑定、状态终态、mandatory/advisory/Strict 评审、Java 固定迁移和稳定 outbox；pytest 业务失败保持 RD 修复，基础设施失败进入 `RECOVERING/BLOCKED`，重复 command 不重复推进。`WorkCompletionCoordinatorTest` 11 项通过；`mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra -am test` 通过 domain 68、app 48、infra 151，共 267 项，0 failure/error/skip，BUILD SUCCESS（8.937s）。`git diff --check` 通过，未运行真实 E2E。

## Task 5：投影 mailbox/event/Cron 并接入 watchdog、Runner 与 incident

- **目标**：让旧 mailbox、events 和 wake 成为幂等投影，并让现有运行时组件统一读取 v2 WorkExecution。
- **涉及文件（预计）**：
  - outbox projector/application service；
  - `MailboxService`/`WakeScheduler` 适配；
  - `Runner`、`MailboxLeaseWatchdog`、`RuntimeIncidentGateway` 接线；
  - `RuntimeLifecycle` 启停与 drain。
- **关键规则**：
  - action ID 防止重复邮件、事件和 wake；
  - mailbox lease 不得覆盖 WorkExecution 终态；
  - incident 改变 WorkExecution 时保持 stage/owner 规则；
  - shutdown 前持久化非终态 WorkExecution；
  - pending outbox 在启动后有限重放。
- **依赖**：Task 4。
- **验收标准**：故意在每个投影步骤中断并重启，最终只产生一次可见副作用且权威状态一致。
- **验证命令**：app/infra/starter 生命周期与集成测试。

- [x] 完成：新增 `WorkflowOutboxProjector` 与 event/mailbox/Cron 稳定 action ID 幂等投影，投影成功后原子确认、失败最多三次且保持项目内顺序；`CoordinatedRuntimeIncidentGateway` 将首次故障写为同 execution 的 `RECOVERING` 并投影恢复 assignment/wake，第二次写为 `BLOCKED`，恢复领取递增 attempt 但不换 execution ID。watchdog 在 v2 项目只允许 PENDING/CLAIMED/RECOVERING stale lease 恢复，RUNNING/WAITING_REVIEW/终态不被旧 lease 覆盖；shutdown 在排空/取消后持久化全部非终态执行与评审，再关闭 projector/MCP/Agent。真实 Test Runner 证据端口与 `WorkCompletionCoordinator` 已接入 Spring。定向 app 22 项、infra/starter 19 项通过；联合 `mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra,qingling-team-starter -am test` 通过 domain 68、app 56、infra 154、adapter 13、starter 76，共 367 项，0 failure/error/skip，BUILD SUCCESS（17.953s）；`git diff --check` 通过。未运行真实 E2E。

## Task 6：简化 task_done 与 ReviewRound 工具协议

- **目标**：模型只引用 execution ID；Java 补全真实测试终态，并以 review_round_id 管理评审。
- **涉及文件（预计）**：
  - `CommonTeamTools`、`ManagerTeamTools`、`DeterministicWorkflowTools`；
  - `TaskCompletionEvidenceGate`；
  - 工具 schema、模板契约测试。
- **关键规则**：
  - 新协议字段：`execution_id/artifacts/decision/rationale/self_score?`；
  - 旧 8 字段正文可读但不再作为权威；
  - Map 与合法 JSON 字符串同义；
  - 正常 task_done 不再要求 Manager 手工调用四段行政工具；
  - review 必须引用 review_round_id；
  - reviewer timeout 形成明确终态。
- **依赖**：Task 5。
- **验收标准**：重放最新历史中的 JSON 字符串、字段漂移、重复 task_done 和单 Reviewer 丢失场景均能确定性收敛。
- **验证命令**：infra tools + workspace contract 定向测试。

- [x] 完成：新增 `WorkflowToolCoordinator`/`CoordinatedWorkflowToolCoordinator`，由 `read_inbox` 绑定 assignment、dispatch、session，并让 `task_done` 只提交 execution ID、产物、决策和说明；Map 与合法 JSON 字符串统一归一化，旧测试字段只记录字段名审计且不参与判定，Test Runner 账本补全权威终态。正常完成直接推进 aggregate/outbox，不再经过 Manager 四段行政工具；重复完成按 WorkExecution 或关联 Test Runner execution 幂等收敛。`review_done` 强制引用 `review_round_id`，mandatory 超时转 `EXPIRED/BLOCKED`，advisory 超时只终结评审、不回滚成功执行；模板同步最小协议。定向测试通过 app 14、infra 2、starter 18；协议/模板聚焦回归 app 14、starter 20；联合回归 domain 68、app 58、infra 156、adapter 13、starter 80，共 375 项，0 failure/error/skip，BUILD SUCCESS（16.910s）；`git diff --check` 通过。未运行真实 E2E。

## Task 7：接入 WorkflowProfile 并取消简单任务强制子 Agent

- **目标**：按 profile 决定角色、评审和子 Agent 策略，降低简单/标准任务生命周期数量。
- **涉及文件（预计）**：
  - `TeamAgentFactory`/角色工具授权；
  - Manager/RD/QA `AGENTS.md` 与相关 Skill；
  - profile/template 契约测试。
- **关键规则**：
  - SIMPLE：需求确认 → RD → QA → 交付；
  - STANDARD：保留 Task 18 现有产物和四角色业务能力，optional review 不阻断，RD 默认父角色直做；
  - STRICT：完整阶段、阻断式评审、可启用子 Agent；
  - profile 运行中不可由模型切换；
  - SIMPLE/STANDARD 调用 agent_spawn 时，工具边界拒绝或返回明确不可用原因，而不是静默创建。
- **依赖**：Task 6。
- **验收标准**：STANDARD Todo 主路径无 `agent_spawn/agent_send/task_output` 仍能生成现有全部产物并执行真实 Test Runner。
- **验证命令**：workspace contract + NoCallModel workflow IT。

- [x] 完成：`WorkflowStateMachine`、`WorkExecution` 与完成协调器按创建时冻结的 profile 推进；`create_project` 现会真正创建 runtime v2 aggregate、需求 checkpoint 和 `project_created` outbox。SIMPLE 只构造 Manager/RD/QA 并跳过 PM、独立技术设计和独立测试设计；coordinated SIMPLE/STANDARD 不注册声明式子 Agent，且工具边界不暴露 `agent_spawn/agent_send/task_output/task_cancel`；STRICT 保留完整角色、阶段与子 Agent。模板已同步 profile 责任边界和父角色直做规则。聚焦回归 domain 23、app 17、infra 12、starter 24，共 76 项通过；完整 `mvn test` 通过 domain 72、app 61、infra 159、adapter 13、starter 88，共 393 项，0 failure/error/skip，BUILD SUCCESS（17.539s）；`git diff --check` 通过。未运行真实 E2E。

## Task 8：增加固定验收契约与分层 E2E Driver

- **目标**：让权威业务断言独立于 RD 自测，并让 Driver 直接观察 WorkExecution/outbox/review 状态。
- **涉及文件（预计）**：
  - E2E acceptance fixture 与只读路径策略；
  - `RealTeamE2EDriver`；
  - `NoLlmWorkflowIT`/新的 coordinated workflow IT；
  - summary/evidence schema。
- **关键规则**：
  - Todo 固定契约覆盖倒序、单文件 HTML、CRUD、边界和持久化；
  - RD/QA 无法写 acceptance 目录；
  - happy-path 不做随机阻断评审；
  - Driver 以 WorkExecution、ReviewRound 和 outbox 为直接诊断源；
  - L0～L2 无真实模型，且不得 source E2E 凭据。
- **依赖**：Task 7。
- **验收标准**：
  - 构造“代码和 RD 自测共同写成 ASC”时，权威验收测试必须失败；
  - ReviewRound 卡住能直接报告 review ID/reviewer/status/deadline；
  - runtime v2 全部终态后不得残留 mailbox、dispatch、child task、runner 或 outbox pending。
- **验证命令**：定向测试、`mvn verify`、零模型真实 Docker契约。

- [x] 完成：新增 Harness 所有的 8 项 Todo 固定验收契约与 pytest，覆盖 `/todos` CRUD、整数 id 倒序、404/422、单文件 HTML 和 SQLite 跨 TestClient 持久化；Driver 在项目初始化后安装只读 fixture、保存摘要并在结束时校验未被角色改写。QA Test Runner 固定执行独立验收，可附加 QA 契约但不再扫描 RD 自测；同时修正嵌套 shell 的引号转义。Driver 直接读取 `workflow-v2.json`，报告 mandatory review、outbox 重试耗尽、BLOCKED/FAILED execution 和无 dispatch 的 RUNNING orphan，并在 summary/evidence 中记录 profile、policy、execution/review/outbox 计数及行政工具调用数。新增 `CoordinatedWorkflowContractIT`，零模型/零网络完成 STANDARD 全链并断言 execution/outbox/mailbox/Cron 全部终态。聚焦测试 53 项通过；contract profile 下单元回归与该 IT 通过（IT 1 项）；`git diff --check` 通过。未 source E2E 凭据，未运行真实 E2E。

## Task 9：完整验证、迁移说明与真实 E2E 二次门禁

- **目标**：完成非 E2E 验证和文档回写；只有用户再次确认后才运行 Task 18 真实场景。
- **依赖**：Task 1～8。
- **非 E2E 验证顺序**：
  1. 所有相关模块定向测试；
  2. `mvn verify`；
  3. `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify`；
  4. coordinated NoCallModel 工作流 IT；
  5. `git diff --check`；
  6. 检查无 pending outbox、dispatch、AgentScope task、pytest/pip/tmux 孤儿进程。
- **文档回写**：
  - 本目录四份文档；
  - `task18-e2e-runtime-reliability-1.0.1/`；
  - `python-to-java-cola-migration/` 仅记录真实已完成项，不提前勾选 Task 18。
- **真实 E2E HARD-GATE**：
  - 非 E2E 全部通过后暂停；
  - 向用户汇报代码、测试、风险和当前 diff；
  - 获得用户明确“运行 E2E”授权后，才按 `happy-path → checkpoint-revise → qa-defect-rd-fix → code-fail-recovery` 执行；
  - 任一场景连续两次干净失败则暂停，不启动第三次；
  - 不 mock、不 skip、不放宽断言、不伪造 summary。
- **验收标准**：四条真实 E2E、最终 `mvn verify` 和 `git diff --check` 全通过后，才允许父 Task 18 完成。

- [x] 非 E2E 验证完成，等待二次授权
- [x] 用户二次授权真实 E2E
- [ ] 真实 E2E 完成

- **非 E2E 门禁结果（2026-10-25）**：相关模块定向测试共 165 项通过（13.633s）；最终 `mvn verify` 通过 domain 72、app 61、infra 159、adapter 13、starter 89，共 394 项，0 failure/error/skip（18.805s）。零模型真实 Docker 工具契约首次暴露 QA 固定验收 fixture 未安装，`run_project_tests` 以 `exit_code=4` 报告 `../acceptance/test_todo_contract.py` 不存在；修复为由公共 `TodoAcceptanceFixture` 安装只读契约，并为契约工作区提供满足验收的最小 FastAPI/SQLite 实现后，`mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` 重跑通过 1 IT（37.157s），覆盖 10 次无副作用 Shell、5 次成功 Test Runner、大输出业务失败、受控连接恢复、NoCallModel 零模型调用及无 pytest/pip/tmux 孤儿。`CoordinatedWorkflowContractIT` 1 IT 通过（27.483s），`git diff --check` 通过；宿主与容器复核无 pending/orphan。未 source E2E 凭据，未运行真实 E2E，当前严格暂停在二次授权门禁。

- **首次真实 E2E（2026-10-25）**：用户二次授权后严格只执行一次干净 `qwen3.8-flash happy-path`，没有重试或启动后三个场景。Failsafe 在 Spring 启动阶段 7.139 秒内报错：`COORDINATED + STANDARD` 通过 `disableSubagents()` 构造的真实 HarnessAgent 没有 TaskRepository，而 `AgentScopeAgentGateway.adaptAgents` 仍无条件调用 `TaskSemanticsRegistry.forRepository(agent.getTaskRepository())`，触发 `NullPointerException: repository 不能为空`。业务工作流和角色模型调用均未开始；Maven 26.359 秒失败。脱敏 `summary.json`/`failure-summary.json` 已保存，宿主和容器无 Maven/E2E/pytest/pip/tmux 残留。真实 E2E 保持未完成，下一轮前需让无 Sub-Agent 运行时使用空任务诊断/取消语义，并补真实 Spring + HarnessAgent 启动测试。

---

## 汇总（全部完成后填写）

- **变更摘要**：已完成 runtime v2 单一 WorkExecution/outbox、Java 完成协调、确定性 profile/review、SIMPLE/STANDARD 去强制子 Agent、固定 Todo 验收与 Driver 直接诊断；非 E2E 门禁全部通过。
- **总文件数**：最终 E2E 完成后统计；当前工作区包含本版本源码、测试、模板和文档改动，并保留用户既有 `adapter/demo/` 与 `tmp.txt`。
- **Spec-Plan 偏差记录**：首次真实 Docker 工具契约发现 QA fixture 未安装，已补齐公共只读 fixture 与契约用最小应用后重跑通过；其余验证层级未减少。
- **遗留问题**：空 TaskRepository 启动缺陷已修复并通过真实 Spring 启动测试与默认回归；仍需按门禁重新执行四条真实 E2E。
- **真实 E2E 授权与结果**：已获得二次授权并只运行一次 `happy-path`；启动阶段因空 TaskRepository 接线缺陷失败，未重试。

- [x] 修复无 TaskRepository 时的空子任务诊断、取消与关闭适配
- [x] 增加 `COORDINATED + STANDARD` 真实 Spring/HarnessAgent 启动测试
- [x] 修复后定向测试与 `mvn verify`
- [ ] 修复后真实 E2E（已执行一次 `happy-path`，因 Driver 孤儿 execution 误判竞态失败，未重试）
- [x] 修复 Owner 完成到 Manager 状态迁移之间的孤儿诊断竞态，并保留有限、精确匹配的失败边界
- [x] 完成交接诊断定向测试与完整默认回归
- [ ] 交接诊断修复后的真实 E2E（本轮未运行）

### 2026-10-25 单次真实 E2E 复核

- [x] 仅执行一次干净 `happy-path`，未自动重试
- [x] 保存脱敏 `summary.json` 与 `failure-summary.json`，并确认无 Maven/pytest/pip/tmux 孤儿
- [x] 修复 `task_done.content.decision` 的契约可发现性与非法枚举后的确定性收敛
- [ ] 重新执行 `happy-path`；通过前不得启动后三个场景

本轮 E2E 500.7 秒失败，Maven 总耗时 8 分 40 秒。PM 已生成产品规格，但连续猜测非法 `WorkCompletionDecision` 枚举后正常退出，权威 execution 留在 `PRODUCT_DESIGN/RUNNING`；Driver 正确报告真实孤儿。四路 MCP 均 `CLOSED/0 failures`，Task 18 保持未完成。

### 2026-10-25 完成协议修复

- [x] `send_mail` 工具与 `content` 参数 schema 说明显式列出 `ACCEPTED/DEFECT_FOUND` 及角色用法
- [x] 非法 `decision` 错误返回完整允许值，不再只回显 `No enum constant`
- [x] 回合内记录未解决 completion rejection；成功重试清除，普通文本退出转为 `COMPLETION_PROTOCOL_REJECTED`
- [x] 新故障类型复用 Runner incident 链，保持 stage/Owner 并执行一次恢复、同类再失败 BLOCKED
- [x] 聚焦 50 项及完整默认回归 402 项通过
- [x] 修复后只执行一次干净 `happy-path` 并保存脱敏启动失败证据
- [ ] 四场景真实 E2E（最新一轮在显式模型 Function Calling 预检以 `transport_error` 失败，未进入 workflow）

### 2026-10-25 模型预检分类与证据修复

- [x] 细分 HTTP、DNS、连接、超时、TLS、重置、解析与 Function Calling 协议失败
- [x] 仅对可恢复故障执行最多一次自动重试，并记录每次脱敏尝试证据
- [x] Driver 启动失败摘要接入结构化模型预检证据
- [x] 独立最小真实 Function Calling 探针通过：`qwen3.8-flash`、attempt 1、HTTP 200、4797ms
- [x] 聚焦 67 项、`mvn verify` 409 项、零模型真实 Docker 契约 1 IT 通过
- [ ] 四场景真实 E2E（本轮明确未运行）

### 2026-10-25 模型预检修复后单次 E2E

- [x] 仅执行一次干净 `happy-path`，模型预检 attempt 1、HTTP 200 通过
- [x] checkpoint、产品设计、技术设计完成，RD 代码与测试文件落盘
- [x] 保存脱敏失败证据并确认 MCP 全 CLOSED、shutdown 将未终态 execution 置为 CANCELLED
- [ ] 修复父回合从未尝试完成工具便正常退出时的类型化失败与确定性 continuation
- [ ] 四场景真实 E2E（本轮未重试，后三场景未运行）

### 2026-10-25 Owner 回合未终态修复

- [x] Owner 领取并启动任务后登记 completion obligation，成功 `task_done` 后解除
- [x] 正常退出但 execution 仍为 RUNNING 时产生 `OWNER_EXITED_WITH_RUNNING_EXECUTION`
- [x] 首次失败保持 stage/Owner/execution，自动投递原 Owner continuation 并将 attempt 增至 2
- [x] 第二次同类失败进入 BLOCKED，禁止第三次自动唤醒，重复上报保持幂等
- [x] 聚焦 63 项、默认回归 410 项、零模型真实 Docker 契约 1 IT 通过
- [ ] 四场景真实 E2E（本轮按要求未运行）

### 2026-10-25 Owner 未终态修复后单次 E2E

- [x] 只执行一次干净 `qwen3.8-flash happy-path`，未重试或启动后三场景
- [x] 真实验证首次 `OWNER_EXITED_WITH_RUNNING_EXECUTION` 恢复原 RD、attempt=2、第二次同类失败 BLOCKED 且无第三次 wake
- [x] 清理 AIO session 与宿主/容器孤儿进程
- [x] 修复 `CronService.tick/fire` 持锁执行无界 `join()` 导致 `stop()` 永久阻塞
- [ ] 四场景真实 E2E（本轮未生成成功 summary，Task 18 未完成）

### 2026-10-26 Cron、完成门禁与 E2E 收尾可靠性修复

- [x] 拆分 Cron tick/lifecycle 锁，并为 dispatch 等待、调度器停止增加有限上限
- [x] 保持取消职责分层：Cron 停止只终止调度等待，未终态业务任务由 RuntimeLifecycle/SerialDispatchRegistry 排空或取消
- [x] Driver 在 shutdown 前先保存失败证据，shutdown 超时后仍补存证据，并增加全局 E2E 与 shutdown 看门狗
- [x] RD 代码/缺陷恢复任务明确携带 `required_action=run_project_tests_then_task_done`
- [x] 代码阶段 completion obligation 与真实 Test Runner execution 形成硬门禁，缺少 execution 时拒绝完成
- [x] 定向 80 项、默认 `mvn verify` 415 项、零模型真实 Docker 契约 1 IT 全部通过
- [ ] 四场景真实 E2E（本轮按要求未运行，Task 18 保持未完成）
