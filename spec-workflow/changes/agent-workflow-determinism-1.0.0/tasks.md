# 任务拆分 — Agent 工作流确定性与编排收敛 1.0.0

> 执行顺序：Task 1 → Task 9，不得跳序。
> 当前状态：`propose`，全部任务等待用户确认后才能开始。
> 每个 Task 完成后必须同步更新本目录 `spec.md`、`tasks.md`、`test-spec.md` 和 `log.md`。
> 所有源码新增或修改声明必须遵守 `chinese-code-comments` skill，带相邻、准确的中文注释。
> **Git**：未经用户明确要求不得 commit 或 push。
> **E2E**：Task 9 的真实模型 E2E 需要单独二次授权。

## 前置条件

- [ ] 用户已明确确认本目录需求并允许编码。
- [ ] 开始前执行 `git status --short` 和 `git diff --check`，记录并保留全部现有修改。
- [ ] 不 reset、checkout、clean 或覆盖用户现有文件。
- [ ] 基线 `mvn verify` 与零模型 Docker 契约结果已记录；若基线失败，先记录真实原因，不将其误算为本版本回归。
- [ ] 确认 Task 18 当前未完成状态和最新证据目录不被清理。

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

- [ ] 完成

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

- [ ] 完成

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

- [ ] 完成

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

- [ ] 完成

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

- [ ] 完成

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

- [ ] 完成

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

- [ ] 完成

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

- [ ] 完成

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

- [ ] 非 E2E 验证完成，等待二次授权
- [ ] 用户二次授权真实 E2E
- [ ] 真实 E2E 完成

---

## 汇总（全部完成后填写）

- **变更摘要**：待填写
- **总文件数**：待填写
- **Spec-Plan 偏差记录**：待填写
- **遗留问题**：待填写
- **真实 E2E 授权与结果**：待填写
