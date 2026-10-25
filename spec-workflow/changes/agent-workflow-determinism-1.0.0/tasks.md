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
- [ ] 用户二次授权真实 E2E
- [ ] 真实 E2E 完成

- **非 E2E 门禁结果（2026-10-25）**：相关模块定向测试共 165 项通过（13.633s）；最终 `mvn verify` 通过 domain 72、app 61、infra 159、adapter 13、starter 89，共 394 项，0 failure/error/skip（18.805s）。零模型真实 Docker 工具契约首次暴露 QA 固定验收 fixture 未安装，`run_project_tests` 以 `exit_code=4` 报告 `../acceptance/test_todo_contract.py` 不存在；修复为由公共 `TodoAcceptanceFixture` 安装只读契约，并为契约工作区提供满足验收的最小 FastAPI/SQLite 实现后，`mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` 重跑通过 1 IT（37.157s），覆盖 10 次无副作用 Shell、5 次成功 Test Runner、大输出业务失败、受控连接恢复、NoCallModel 零模型调用及无 pytest/pip/tmux 孤儿。`CoordinatedWorkflowContractIT` 1 IT 通过（27.483s），`git diff --check` 通过；宿主与容器复核无 pending/orphan。未 source E2E 凭据，未运行真实 E2E，当前严格暂停在二次授权门禁。

---

## 汇总（全部完成后填写）

- **变更摘要**：已完成 runtime v2 单一 WorkExecution/outbox、Java 完成协调、确定性 profile/review、SIMPLE/STANDARD 去强制子 Agent、固定 Todo 验收与 Driver 直接诊断；非 E2E 门禁全部通过。
- **总文件数**：最终 E2E 完成后统计；当前工作区包含本版本源码、测试、模板和文档改动，并保留用户既有 `adapter/demo/` 与 `tmp.txt`。
- **Spec-Plan 偏差记录**：首次真实 Docker 工具契约发现 QA fixture 未安装，已补齐公共只读 fixture 与契约用最小应用后重跑通过；其余验证层级未减少。
- **遗留问题**：仅剩用户二次授权后的四条真实 E2E；在其全部通过前，父 Task 18 不得完成。
- **真实 E2E 授权与结果**：尚未获得本轮二次授权，未运行。
