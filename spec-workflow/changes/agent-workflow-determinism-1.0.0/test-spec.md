# 测试 Spec — Agent 工作流确定性与编排收敛 1.0.0

> status: apply
> created: 2026-10-25
> 用户已于 2026-10-25 确认编码；当前按 Task 顺序执行非 E2E 验证，真实模型 E2E 仍需二次授权。

## 0. 测试原则

- **单一权威**：断言首先读取 runtime v2 aggregate；mailbox/event/Cron 只验证投影一致性。
- **确定性重放**：相同输入、profile、policy 和 command ID 必须得到相同状态、分支和副作用集合。
- **崩溃优先**：每个原子更新与 outbox 投影边界都要模拟进程中断和恢复。
- **模型不参与行政断言**：L0～L2 全部使用 NoCallModel 或直接应用服务，不调用百炼/NewAPI。
- **真实执行证据**：Test Runner 终态只从 execution registry/runner state 读取，不信任模型复制字段。
- **独立验收**：RD 自测与 E2E acceptance tests 分离；RD 无权修改验收夹具。
- **不以延长 timeout 代替收敛**：测试必须验证直接终态和原因，而不是只等待更久。
- **展示证据**：每个 Task 在四份文档中记录真实命令、数量、耗时和失败原因。

## 1. 测试框架

| 项目 | 值 |
|------|-----|
| 单元测试 | JUnit 5、AssertJ、Mockito |
| 文件并发 | 临时目录、真实 FileChannel lock、并发 Future |
| Spring 集成 | Spring Boot Test、NoCallModel |
| Docker 契约 | 现有 AIO-Sandbox `RealSandboxToolContractIT` |
| E2E | Failsafe + 真实模型，仅二次授权后 |
| 影响模块 | domain、app、infra、starter |

## 2. P0 — 必须通过

### 2.1 WorkExecution 状态机

| 场景 | 输入 | 预期 |
|------|------|------|
| 正常完成 | PENDING → CLAIMED → RUNNING → SUCCEEDED | 合法且时间单调 |
| 等待评审 | RUNNING → WAITING_REVIEW → SUCCEEDED/revision | mandatory review 才阻断 |
| 业务失败 | 真实 pytest exit 1 | 保持业务失败，交给原 Owner 修复，不生成 MCP incident |
| 基础设施失败 | 类型化 MCP/AgentScope failure | RECOVERING/FAILED/BLOCKED 按预算迁移 |
| 终态回退 | SUCCEEDED → RUNNING | 明确拒绝 |
| 重复 continuation | 同 execution ID | 不创建第二 WorkExecution |
| 并发 claim | 两线程领取同一工作项 | 只有一个成功，其余得到同一权威状态 |
| 假 heartbeat | 只有 Cron/health 时间变化 | 不刷新 `last_progress_at` |

### 2.2 ReviewPolicy 与 ReviewRound

| 场景 | Profile/Policy | 预期 |
|------|----------------|------|
| 低风险 happy path | STANDARD/REQUIRED_ONLY | 不创建阻断评审 |
| 新手期 | STANDARD/REQUIRED_ONLY | 只写 advisory 事件，不阻断 |
| 15% 抽查命中 | STANDARD/REQUIRED_ONLY | 只写 advisory 事件，同场景分支不变 |
| 高风险 | STANDARD/REQUIRED_ONLY | 创建一个确定性 mandatory Reviewer |
| Strict 双评审 | STRICT/STRICT_BLOCKING | 两个 verdict 齐全后才收敛 |
| Reviewer 超时 | 任意 blocking round | EXPIRED/BLOCKED，直接诊断 reviewer 和 deadline |
| 重复 review_done | 相同 review_round_id/reviewer | 幂等，不重复推进 |
| 不同 round 串线 | 旧 review 回包 | 不得影响当前 round |

### 2.3 runtime v2 仓储与 outbox

| 场景 | 故障注入 | 预期 |
|------|----------|------|
| 原子写 | 写临时文件前/后中断 | 旧版本或完整新版本，不出现半 JSON |
| command 幂等 | 同 command 执行 10 次 | 一个状态变化、一组 outbox action |
| 并发完成 | 两线程同 execution | 一个完成，其余返回已完成 |
| 投影前崩溃 | aggregate 已提交、mail 未写 | 重启后 outbox 补写一次 |
| 部分投影崩溃 | event 已写、mail 未写 | event 不重复，mail 补写一次 |
| wake 重放 | 相同 action ID | Cron 只保留一个有效 wake |
| schema 不兼容 | 未知 version | fail-fast 并保留原文件 |
| 敏感数据 | command 含模拟 key/header | runtime/outbox 不得落盘凭据 |

### 2.4 WorkCompletionCoordinator

| 场景 | 输入 | 预期 |
|------|------|------|
| PM 完成 | 合法产物 | Java 自动关闭工作项并创建 RD 工作项 |
| RD 完成 | 成功 execution ID | Java 从 registry 补全测试证据并推进 QA |
| RD 失败 | TEST_FAILED execution ID | 不接受成功，保持 RD 修复语义 |
| QA 通过 | 成功 QA execution | 进入交付 checkpoint |
| QA 缺陷 | TEST_FAILED + defects | 唯一创建 RD 缺陷修复工作项 |
| 完成正文 Map | 最小协议 | 成功 |
| 完成正文 JSON string | 同一最小协议 | 与 Map 完全同义 |
| 模型伪造 status | execution ID 成功但复制字段错误 | Java 账本为准，记录有界审计，不创建第二事实源 |
| 重复 task_done | 同 msg/command/execution | 相同结果，无重复 event/mail/wake |
| 协调中断 | 任意步骤抛错 | aggregate 未提交或可由 outbox 恢复，不留 done-without-next |

### 2.5 Profile 与 Agent 工具策略

| 场景 | Profile | 预期 |
|------|---------|------|
| 简单 Todo | SIMPLE | 不唤醒 PM、不创建子 Agent，仍有用户 checkpoint、RD、QA、交付 |
| Task 18 Todo | STANDARD | 产生既有五类核心产物，optional review 不阻断，RD 不强制子 Agent |
| 复杂严格任务 | STRICT | 允许完整阶段和显式子 Agent |
| 模型越权 spawn | SIMPLE/STANDARD | 工具边界明确拒绝且不创建 TaskRepository 记录 |
| 运行中换 profile | 任意 | 拒绝 |
| legacy 项目 | 无 v2 marker | 继续 legacy，不创建半迁移 aggregate |

### 2.6 固定验收契约

| 场景 | RD 实现/自测 | 权威预期 |
|------|-------------|----------|
| 排序共同写错 | 实现和 RD 测试均断言 ASC | acceptance `id_desc` 失败 |
| 第二入口 | 存在 `code/app/main.py` | 失败 |
| 非单文件前端 | HTML 外链本地 app.js/style.css | `single_html=true` 失败 |
| CRUD 全部正确 | 权威测试通过 | 成功 |
| RD 修改契约 | 写 `acceptance/contract.json` | 权限拒绝并记录事件 |
| QA 扩写范围 | case 无 requirement ID | 报告门禁拒绝 |

### 2.7 Driver 与直接诊断

| 场景 | 权威状态 | 预期诊断 |
|------|----------|----------|
| Review 卡住 | WAITING_REVIEW + 过 deadline | review_round_id、reviewer、status、deadline |
| outbox 卡住 | pending action 超预算 | action ID/type/attempt，不能伪装成文件超时 |
| mailbox 投影迟延 | WorkExecution RUNNING 且有进展 | 不误判失败 |
| dispatch 消失 | WorkExecution RUNNING、无 dispatch/Agent | 直接 orphan execution |
| 全部终态 | SUCCEEDED + outbox empty | 无 pending mailbox/dispatch/child/test process |
| advisory review 未完成 | 主 WorkExecution 已推进 | 不阻断 happy-path |

## 3. P1 — 集成与兼容

### 3.1 NoCallModel 完整工作流

- STANDARD profile 从 project 创建推进到 delivery。
- PM、RD、QA 通过真实工具适配，但模型由脚本化 NoCallModel 驱动。
- 验证 Manager 不需要执行旧的行政四连调用。
- 验证每阶段一个 WorkExecution、一个 assignment 投影、最多一个 wake。
- 验证 shutdown 后所有非终态工作项落成 CANCELLED/FAILED/BLOCKED。

### 3.2 Legacy/coordinated 双模式

- legacy 配置下现有契约测试保持通过。
- coordinated 配置下只读旧 mailbox/event，但不从旧投影反向改 v2 状态。
- 没有 v2 marker 的现有项目不自动升级。
- 新建 v2 项目重启后仍选择 coordinated 模式。

### 3.3 真实 Docker 零模型契约

在现有契约基础上继续验证：

- 10 次无副作用 Shell；
- 5 次结构化 Test Runner；
- 一次受控连接恢复；
- RD/QA 真实 AgentScope/MCP 工具；
- NoCallModel 零百炼/NewAPI 调用；
- 无 pending tool、outbox、dispatch；
- 无 pytest/pip/tmux 孤儿进程；
- WorkExecution 与 runner execution 关联完整。

## 4. P2 — 真实模型 E2E（单独授权）

### 4.1 前置门禁

- [ ] 所有 P0/P1 通过。
- [ ] `mvn verify` 通过。
- [ ] `RealSandboxToolContractIT` 通过。
- [ ] `git diff --check` 通过。
- [ ] 用户明确授权启动真实 E2E。

### 4.2 场景顺序

1. `happy-path`
2. `checkpoint-revise`
3. `qa-defect-rd-fix`
4. `code-fail-recovery`

### 4.3 共同断言

- profile 固定 `STANDARD`，review policy 固定 `REQUIRED_ONLY`；
- 同一输入不因 message ID、跨项目历史或抽样桶进入不同分支；
- happy-path 不出现阻断式 audit/novice review；
- 正常 task_done 由 Java 自动协调，不要求 Manager 行政工具链；
- RD 无子 Agent 也能完成代码和测试；
- 固定 acceptance tests 独立于 RD 自测；
- 最终 WorkExecution 全部终态、outbox empty；
- 无 pending mailbox/dispatch/AgentScope/Test Runner；
- 无 pytest/pip/tmux 孤儿进程；
- 失败时报告 WorkExecution/review/outbox 的直接原因。

### 4.4 停止规则

- 任一场景连续两次干净失败后暂停，不启动第三次。
- happy-path 未通过不得启动其余三个场景。
- 不更换昂贵模型掩盖协议问题，不延长 timeout 掩盖无进展。
- 不 mock、不 skip、不放宽断言、不伪造产物或 summary。

## 5. 不测试/本版本不覆盖

- 不验证新的模型 Provider 协议；NewAPI/DashScope 由既有需求覆盖。
- 不对旧项目执行自动迁移，因为本版本明确禁止隐式迁移。
- 不修改或测试 AIO-Sandbox 服务端实现，只验证现有 MCP 契约。
- 不以真实飞书环境作为本版本完成条件；入口通过现有 adapter 契约覆盖。

## 6. 执行计划

- [x] Task 1：配置和协议定向测试。`WorkflowProtocolTest` 4 项、`RuntimeBootstrapTest` 14 项，共 18 项通过；命令耗时 13.215s。
- [x] Task 2：领域状态表驱动测试。新增 `WorkExecutionTest` 7 项、`ReviewRoundTest` 8 项；domain 全模块 63 项通过。
- [x] Task 3：文件仓储、并发、崩溃恢复测试。新增 aggregate 5 项、文件仓储 8 项；domain+infra 全量 219 项通过。
- [x] Task 4：完成协调器测试。新增 11 项，覆盖 command 去重、claim/start、pytest 成功/业务失败/基础设施失败、QA 缺陷回流、mandatory/Strict/advisory 评审、修订复用 execution 与原子回滚；domain+app+infra 267 项通过。
- [x] Task 5：outbox 投影和生命周期测试。覆盖 event/mailbox/Cron action ID 重放、投影中断、有限失败、incident RECOVERING/BLOCKED、恢复领取、watchdog v2 终态保护、shutdown 终态和 Spring 接线；联合模块 367 项通过。
- [x] Task 6：工具协议与历史正文重放测试。覆盖 Map/合法 JSON 字符串同义、旧字段非权威审计、重复 `task_done`、同 WorkExecution 多次 Test Runner execution、`review_round_id` 必填、mandatory/advisory reviewer deadline；联合回归共 375 项通过。
- [x] Task 7：profile、模板和去子 Agent 测试。覆盖 SIMPLE/STANDARD/STRICT 固定迁移、运行中不可换档、真实项目入口初始化 v2、SIMPLE 角色裁剪、SIMPLE/STANDARD 无子 Agent 工具、STRICT 保留子 Agent，以及生命周期 heartbeat 角色集合。
- [x] Task 8：固定验收契约、Driver 和 L0～L2。Harness fixture 8 项、Driver v2 直接诊断、QA 独立 runner 与零模型 STANDARD 全链均已覆盖。
- [x] Task 9 非 E2E：完整非 E2E 门禁通过，已暂停等待用户二次授权。
- [ ] Task 9 真实 E2E：已授权并执行一次 `happy-path`，启动阶段失败，尚未完成。

## 7. 计划验证命令

> 具体测试类名允许实现阶段随最终命名调整，但验证层级不得减少。

```bash
cd /Users/qingling/workspace/IdeaProjects/QinglingWake/qingling-team

mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra,qingling-team-starter -am test

mvn verify

mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify

git diff --check
```

真实 E2E 命令只有在用户二次授权后记录和执行，不在 propose 阶段预执行。

## 8. 测试结果

| 日期 | Task | 命令 | 结果 |
|------|------|------|------|
| 2026-10-25 | Task 1 | `mvn -pl qingling-team-domain,qingling-team-starter -am -Dtest=WorkflowProtocolTest,RuntimeBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false test` | 18 项通过，0 failure/error/skip，BUILD SUCCESS，13.215s；未运行真实 E2E |
| 2026-10-25 | Task 2 | `mvn -pl qingling-team-domain test` | domain 全模块 63 项通过，含 `WorkExecutionTest` 7 项、`ReviewRoundTest` 8 项；0 failure/error/skip，BUILD SUCCESS，2.035s；未运行真实 E2E |
| 2026-10-25 | Task 3 | `mvn -pl qingling-team-domain,qingling-team-infra -am test` | domain 68 项、infra 151 项，共 219 项通过；覆盖 10 路并发 command、原子临时文件、pending/部分 outbox 重启、schema/corrupt fail-fast、legacy 隔离与凭据拒绝；BUILD SUCCESS，8.685s；未运行真实 E2E |
| 2026-10-25 | Task 4 | `mvn -pl qingling-team-app -am -Dtest=WorkCompletionCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false test` | `WorkCompletionCoordinatorTest` 11 项通过，0 failure/error/skip，BUILD SUCCESS，1.607s；`git diff --check` 同命令链通过 |
| 2026-10-25 | Task 4 | `mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra -am test` | domain 68 项、app 48 项、infra 151 项，共 267 项通过，0 failure/error/skip，BUILD SUCCESS，8.937s；未运行真实 E2E |
| 2026-10-25 | Task 5 | `mvn -pl qingling-team-app -am -Dtest=MailboxLeaseWatchdogTest,WorkflowShutdownCoordinatorTest,WorkflowOutboxProjectorTest,CoordinatedRuntimeIncidentGatewayTest,WorkCompletionCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false test` | 22 项通过；覆盖恢复领取同 execution/attempt+1、watchdog v2 RUNNING 保护、shutdown 终态、outbox crash window/有限重试及 incident 恢复预算；BUILD SUCCESS，2.078s |
| 2026-10-25 | Task 5 | `mvn -pl qingling-team-infra,qingling-team-starter -am -Dtest=LegacyWorkflowProjectionGatewayTest,RuntimeLifecycleTest,RuntimeBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false test` | infra 投影 3 项、starter 16 项，共 19 项通过；验证三类旧存储幂等副作用、协调模式 Bean 接线和 shutdown 次序；BUILD SUCCESS，12.199s |
| 2026-10-25 | Task 5 | `mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra,qingling-team-starter -am test` | domain 68、app 56、infra 154、adapter 13、starter 76，共 367 项通过，0 failure/error/skip，BUILD SUCCESS，17.953s；`git diff --check` 通过；未运行真实 E2E |
| 2026-10-25 | Task 6 | `mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra,qingling-team-starter -am test` | domain 68、app 58、infra 156、adapter 13、starter 80，共 375 项通过，0 failure/error/skip，BUILD SUCCESS，16.910s；`git diff --check` 通过；未运行真实 E2E |
| 2026-10-25 | Task 7 | `mvn -pl qingling-team-starter -am -Dtest=WorkflowStateMachineTest,WorkExecutionTest,WorkflowRuntimeStateTest,WorkCompletionCoordinatorTest,CommonTeamToolsCoordinatedTest,TeamAgentFactoryTest,CoordinatedWorkflowToolCoordinatorTest,WorkspaceTemplateContractTest,RuntimeLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test` | domain 23、app 17、infra 12、starter 24，共 76 项通过；覆盖 profile、v2 初始化、角色裁剪、子 Agent 工具边界和模板契约，BUILD SUCCESS，5.321s |
| 2026-10-25 | Task 7 | `mvn test` | domain 72、app 61、infra 159、adapter 13、starter 88，共 393 项通过，0 failure/error/skip，BUILD SUCCESS，17.539s；`git diff --check` 通过；未运行真实 E2E |
| 2026-10-25 | Task 8 | `mvn -pl qingling-team-starter -am -Dtest=McpProjectTestRunnerTest,TodoAcceptanceFixtureTest,RealTeamE2EDriverTest,WorkspaceTemplateContractTest -Dsurefire.failIfNoSpecifiedTests=false test` | infra 6、starter 47，共 53 项通过；覆盖 QA 固定 acceptance 目标、只读 fixture、Driver review/outbox/execution/orphan 直接诊断、summary 字段和 QA requirement ID 模板，BUILD SUCCESS，4.909s |
| 2026-10-25 | Task 8 | `python3 -c 'import ast, pathlib; ast.parse(...)'` | 固定验收 pytest 源码语法校验通过；未生成 `__pycache__` |
| 2026-10-25 | Task 8 | `mvn -Pcontract-it -pl qingling-team-starter -am -Dit.test=CoordinatedWorkflowContractIT -Dfailsafe.failIfNoSpecifiedTests=false verify` | domain 72、app 61、infra 159、adapter 13、starter 89 项单元测试及 `CoordinatedWorkflowContractIT` 1 项通过；零模型、零网络完成 STANDARD 全链并验证 mailbox/Cron/outbox/execution 无残留，BUILD SUCCESS，21.803s |
| 2026-10-25 | Task 8 | `git diff --check` | 通过；未 source E2E 凭据、未运行真实 E2E |
| 2026-10-25 | Task 9 | 相关模块定向测试 | domain 35、app 27、infra 29、starter 74，共 165 项通过，0 failure/error/skip，BUILD SUCCESS，13.633s |
| 2026-10-25 | Task 9 | 首次 `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 失败并暴露真实 fixture 缺口：QA 固定目标 `../acceptance/test_todo_contract.py` 未安装，Test Runner 返回 `exit_code=4`；未掩盖或按环境抖动跳过 |
| 2026-10-25 | Task 9 | 修复后 `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 394 项单元回归及 `RealSandboxToolContractIT` 1 IT 通过，BUILD SUCCESS，37.157s；真实 Docker/AgentScope/MCP + NoCallModel，10 次 Shell、5 次成功 Test Runner、大输出 `exit_code=1`、受控连接恢复和无 pytest/pip/tmux orphan 均通过 |
| 2026-10-25 | Task 9 | `mvn -Pcontract-it -pl qingling-team-starter -am -Dit.test=CoordinatedWorkflowContractIT -Dfailsafe.failIfNoSpecifiedTests=false verify` | 394 项单元回归及 coordinated IT 1 项通过，BUILD SUCCESS，27.483s；STANDARD 全链无模型/无网络并收敛 execution/outbox/mailbox/Cron |
| 2026-10-25 | Task 9 | 最终 `mvn verify` | domain 72、app 61、infra 159、adapter 13、starter 89，共 394 项通过，0 failure/error/skip，BUILD SUCCESS，18.805s |
| 2026-10-25 | Task 9 | `git diff --check` 与宿主/容器进程复核 | 通过；无 pending tool，未发现 pytest/pip/tmux/Maven/E2E 孤儿；未 source E2E 凭据、未调用真实模型、未运行真实 E2E |
| 2026-10-25 | Task 9 | `mvn -Pe2e -De2e.scenario=happy-path verify`（仅一次） | 失败：Failsafe 1 test、0 failures、1 error、0 skipped，E2E 7.139s，Maven 26.359s。Spring 创建 Gateway 时对禁用 Sub-Agent 的 HarnessAgent 空 TaskRepository 调用 `TaskSemanticsRegistry.forRepository`，报 `NullPointerException: repository 不能为空`；phase=startup，显式模型 `qwen3.8-flash`。未进入业务 workflow 或角色模型调用，未重试；finally 保存脱敏 summary，宿主/容器无孤儿进程 |
| 2026-10-25 | Task 9 启动缺陷修复 | `mvn -pl qingling-team-starter -am -Dtest=TeamAgentFactoryTest,RuntimeBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false test` | `TeamAgentFactoryTest` 11 项、`RuntimeBootstrapTest` 15 项通过，BUILD SUCCESS，14.525s；覆盖无 TaskRepository 的空诊断/取消/关闭以及真实 Spring + HarnessAgent coordinated STANDARD 启动 |
| 2026-10-25 | Task 9 启动缺陷修复 | `mvn verify` | domain 72、app 61、infra 160、adapter 13、starter 90，共 396 项通过，0 failure/error/skip，BUILD SUCCESS，27.491s；未运行真实 E2E |
| 2026-10-25 | Task 9 修复后单次 E2E | `mvn -Pe2e -De2e.scenario=happy-path verify` | 失败：1 E2E、1 failure、0 errors、0 skipped，E2E 1054s，Maven 17:52。Driver 在 Manager 正处理 RD 完成消息时，仅因 RD dispatch/AgentScope 已空闲就把 `TECH_DESIGN/RUNNING` 判为孤儿；48 秒后真实状态已迁移为 `TECH_DESIGN/SUCCEEDED → CODE_IMPLEMENTATION/RD`。四路 MCP 均 CLOSED/0 failures；未重试、未启动后三场景 |
| 2026-10-25 | Manager 完成交接诊断修复 | `mvn -pl qingling-team-starter -am -Dtest=RealTeamE2EDriverTest -Dsurefire.failIfNoSpecifiedTests=false test` | 36 项通过，BUILD SUCCESS，2.511s；覆盖完全匹配交接放行、execution 错配拒绝、5 分 30 秒宽限耗尽后恢复孤儿诊断 |
| 2026-10-25 | Manager 完成交接诊断修复 | `mvn verify` | domain 72、app 61、infra 160、adapter 13、starter 93，共 399 项通过，0 failure/error/skip，BUILD SUCCESS，18.542s；未运行真实 E2E |
| 2026-10-25 | 交接诊断修复后单次 E2E | `mvn -Pe2e -De2e.scenario=happy-path verify` | 失败：1 E2E、1 failure、0 errors、0 skipped，E2E 500.7s，Maven 8:40。PM 产物已生成，但 `task_done.content.decision` 连续使用非法 `WorkCompletionDecision` 值且错误未返回允许值，模型退出后留下 `PRODUCT_DESIGN/RUNNING` 真孤儿；四路 MCP CLOSED/0 failures。未重试、未启动后三场景，shutdown 后无孤儿进程 |
| 2026-10-25 | 完成协议修复聚焦测试 | `mvn -pl qingling-team-starter -am -Dtest=RoleToolkitFactoryTest,CommonTeamToolsCoordinatedTest,AgentScopeAgentGatewayTest,CoordinatedWorkflowToolCoordinatorTest,RuntimeBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false test` | infra 30、starter 20，共 50 项通过；覆盖 schema 值域、非法枚举诊断、拒绝登记/成功清除、静默退出类型化失败与 Spring 接线，BUILD SUCCESS，14.627s |
| 2026-10-25 | 完成协议修复默认回归 | `mvn verify` | domain 72、app 61、infra 162、adapter 13、starter 94，共 402 项通过，0 failure/error/skip，BUILD SUCCESS，21.789s；未运行 E2E |
| 2026-10-25 | E2E 前零模型门禁 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 通过：1 IT、0 failure/error/skip，真实 Docker 测试 18.13s，Maven 39.936s |
| 2026-10-25 | 完成协议修复后单次 E2E | `mvn -Pe2e -De2e.scenario=happy-path verify` | 失败：1 E2E、0 failures、1 error、0 skipped，E2E 6.025s，Maven 24.792s；显式 DashScope `qwen3.8-flash` Function Calling 预检返回 `transport_error`，phase=startup，未进入 workflow/Agent/完成协议路径；未重试或启动后三场景，证据已脱敏保存且无孤儿进程 |
| 2026-10-25 | 模型预检聚焦回归 | `mvn -pl qingling-team-starter -am -Dtest=ModelAvailabilitySelectorTest,RealTeamE2EDriverTest,RuntimeBootstrapTest -Dsurefire.failIfNoSpecifiedTests=false test` | 67 项通过，0 failure/error/skip，10.835s；覆盖分类、有限重试、未知客户端错误不重试、TLS cause chain、证据脱敏与 Driver 启动摘要 |
| 2026-10-25 | 独立最小 Function Calling 探针 | `mvn -Pmodel-probe -Dtest=__NoUnitTests__ -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RealModelFunctionCallingProbeIT verify` | `qwen3.8-flash` attempt 1、HTTP 200、available，探针 5.012s、Maven 8.431s；未启动 E2E/workflow |
| 2026-10-25 | 模型预检修复默认回归 | `mvn verify` | 409 项通过，0 failure/error/skip，18.601s |
| 2026-10-25 | 模型预检修复零模型 Docker 契约 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 1 IT 通过，0 failure/error/skip；IT 18.31s、Maven 37.054s；NoCallModel，不调用百炼 |
| 2026-10-25 | 模型预检修复后单次 E2E | `mvn -Pe2e -De2e.scenario=happy-path verify` | 失败：1 failure、0 errors、0 skipped；E2E 869.3s、Maven 14:49。模型预检 attempt 1/HTTP 200；checkpoint、产品、技术设计成功，RD 代码落盘后未调用 `run_project_tests` 或 `task_done`，以“下次唤醒继续”的普通文本退出，留下 `CODE_IMPLEMENTATION/RUNNING` 真孤儿；MCP 全 CLOSED，未重试或启动后三场景 |
| 2026-10-25 | Owner 未终态类型化失败聚焦测试 | 定向 Maven 测试 | 通过 63 项，16.025s；覆盖登记/解除义务、`OWNER_EXITED_WITH_RUNNING_EXECUTION`、原 Owner attempt=2 continuation、第二次同类失败 BLOCKED 与 incident 幂等 |
| 2026-10-25 | Owner 未终态修复默认回归 | `mvn verify` | 通过 410 项，23.491s；domain 72、app 61、infra 163、adapter 13、starter 101，0 failure/error/skip；未运行 E2E |
| 2026-10-25 | Owner 未终态修复零模型 Docker 契约 | `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` | 410 unit + 1 IT 通过，36.849s；10 次 Shell、5 次 Test Runner、受控连接恢复和无 pytest/pip/tmux orphan；NoCallModel，未运行 E2E |
