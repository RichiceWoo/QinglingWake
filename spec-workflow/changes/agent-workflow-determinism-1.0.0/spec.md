# Agent 工作流确定性与编排收敛 1.0.0

> status: apply
> created: 2026-10-25
> complexity: 🔴复杂
> change-dir: `spec-workflow/changes/agent-workflow-determinism-1.0.0/`
> parent-change: `spec-workflow/changes/python-to-java-cola-migration/` Task 18
> related-change: `spec-workflow/changes/task18-e2e-runtime-reliability-1.0.1/`

## 0. 实施门禁（HARD-GATE）

- 用户已于 2026-10-25 明确确认本目录需求并授权从 Task 1 开始编码；当前按 Task 1 → Task 9 严格实施。
- 用户确认编码不等于授权真实模型 E2E。完成定向测试、`mvn verify`、零模型真实 Docker 契约和 `git diff --check` 后，必须再次获得用户明确授权，才能启动真实 E2E。
- 未经用户明确要求，不提交、不推送代码。



## 1. 背景与目标

Task 18 已经历大量真实 E2E。历史失败先后暴露 MCP 会话失步、连接恢复、Test Runner 终态竞态、AgentScope 父子取消、continuation 去重、mailbox stale lease、项目上下文传播和 Driver 误判等缺陷。上述缺陷大部分已有定向修复，但最新运行表明系统仍可能在所有底层组件健康时，因为业务编排链没有收敛而失败。

2026-10-24 最近一次 `happy-path` 的关键证据：

- 场景运行约 43 分钟，在进入 QA 设计前已经发生 231 次工具调用；
- RD 共触发 8 次真实 Test Runner，最后一次达到 `exit_code=0`；
- 四个 Owner 的 MCP circuit 全程 `CLOSED/0 failures`；
- 终止时 dispatch 全部 inactive、AgentScope 无活跃任务；
- RD 首次 `task_done` 因正文类型不满足重复证据协议被拒绝，重发后又进入 PM/QA 双评审；
- QA 已返回 `changes_requested`，PM 的 `review_request` 长时间保持 `in_progress`；
- QA 发现真实契约偏差：需求和设计要求按 `id DESC` 返回，代码和 RD 自测却共同实现并认可 `id ASC`；
- Driver 最终按阶段绝对预算失败，而不是按最后一次真实业务进度判断。

这说明当前问题已经从单一运行时缺陷演变为系统性编排问题：workflow event、mailbox、Cron wake、dispatch、AgentScope 父回合、AgentScope 子任务、Test Runner、MCP circuit 和 review 邮件分别维护生命周期，但没有一个可持久化的权威执行实体原子决定“当前工作是什么、谁在执行、是否完成、下一步是什么”。

本版本的目标是：

1. 将模型职责收敛为业务产出和有限语义判断，行政编排由 Java 确定性完成；
2. 建立单一 `WorkExecution` 权威事实源，mailbox、event、Cron 和 Driver 只作为投影或执行器；
3. 让同一项目、同一输入和同一策略稳定进入相同工作流分支；
4. 将随机抽查和新手评审从阻断式主流程中移除；
5. 让模型只提交最小完成引用，测试结果由 Java 从真实执行账本补全；
6. 对简单和标准任务取消不必要的强制子 Agent；
7. 用不可被 RD 修改的验收契约防止“实现和自测一起写错”；
8. 在保持 Task 18 既有产物和四角色能力的前提下，显著减少模型负责的行政工具调用和跨层状态协调。



## 2. 代码现状（Research Findings）



### 2.1 当前确定性能力

- `qingling-team-domain/.../workflow/WorkflowStateMachine.java`：固定 checkpoint、PM、RD、QA、缺陷修复和交付阶段的合法迁移；状态机本身职责清晰。
- `qingling-team-infra/.../agentscope/tool/DeterministicWorkflowTools.java#advanceWorkflow`：校验当前阶段和 Owner，并生成下一 `task_assign` 和 wake。
- `qingling-team-infra/.../agentscope/tool/TaskCompletionEvidenceGate.java`：将 RD/QA 完成回报与真实 `ProjectTestExecutionRegistry` 结果绑定。
- `qingling-team-app/.../runner/Runner.java`：按 routing key 串行 dispatch，并将类型化 Agent 故障桥接到 incident。
- `qingling-team-app/.../mailbox/MailboxLeaseWatchdog.java`：回收 stale mailbox lease。
- `qingling-team-infra/.../agentscope/AgentScopeAgentGateway.java`：管理父子任务、进度租约、取消和 AgentScope 故障分类。
- `qingling-team-starter/.../e2e/RealTeamE2EDriver.java`：观察 workflow、mailbox、dispatch、AgentScope、MCP 和 incident，并保存脱敏证据。



### 2.2 当前编排放大点

1. `manager/skills/sop_feature_dev/SKILL.md` 要求 Manager 对每个 `task_done` 依次执行读取、加载 Skill、计算评审、写事件、关邮件、发评审或推进状态机等动作。这些确定性行政步骤仍由模型逐项调用。
2. `ManagerTeamTools#checkReviewCriteria` 使用 OR 逻辑，只要命中高风险、低自评、硬约束、新手期或 15% message ID 抽查之一，就插入阻断式评审。
3. 新手期依赖近 30 天跨项目历史；抽查依赖每次运行变化的 message ID。同一 E2E 场景在不同干净目录可能走不同分支。
4. 命中评审后由 Manager 发送两个 `review_request` 并等待两个角色。任一 Reviewer 丢失上下文、长生成或 lease 悬挂，主流程都无法收敛。
5. `TaskCompletionEvidenceGate#requireRecordedExecution` 要求模型复制真实执行的 8 个字段，再逐字段比对。权威结果已经存在于 Java，但模型仍成为重复事实源。
6. `rd/skills/code_impl/SKILL.md` 对简单 MVP 也强制进入声明式 Sub-Agent，并要求父 Agent 协调 spawn、output、send、continuation 和测试修复。
7. RD 生成实现的同时也生成自测；当实现和自测共同偏离需求时，RD pytest 可以全绿，但仍不代表真实契约满足。
8. `RealTeamE2EDriver` 需要同时推断多个存储的组合状态。即使增强诊断，也只能在状态已经分叉后做事后裁决。



### 2.3 根因判断

- **不是 Java/COLA 选型问题**：领域状态机、模块依赖和单元/集成测试能力适合承载确定性工作流。
- **AgentScope 存在职责错位**：AgentScope 适合模型会话、推理和内容产出，不应作为任务事务、review barrier 和恢复预算的权威工作流引擎。
- **原业务流程设计是当前主要放大器**：一个简单 Todo MVP 被扩展为多阶段、多 Reviewer、多子任务和多轮完成协议，失败面远大于业务本身。
- **模型质量是放大器而不是根因**：`qwen3.8-flash` 已证明可以多轮修复到 pytest 成功，但无法可靠承担数百次工具动作中的全部行政协议。



## 3. 范围与非目标



### 3.1 本版本范围

- 新增可持久化 `WorkExecution`、`ReviewRound` 和 project-level outbox。
- 新增工作流档位与确定性评审策略。
- 新增 Java 完成协调器，将完成校验、阶段推进、评审创建和下一工作项收敛到单一命令。
- 简化 `task_done` 测试证据协议。
- 为 `SIMPLE`/`STANDARD` 档位取消强制 RD 子 Agent。
- 为真实 E2E 提供不可由角色修改的固定验收契约。
- 分层改造 E2E：先验证最小确定性主路径，再验证 review、缺陷和恢复。
- 保持旧项目数据只读兼容和 legacy 模式回退能力。



### 3.2 非目标

- 不更换 Java、Spring Boot、COLA、AgentScope、Maven、AIO-Sandbox 或模型 Provider。
- 不通过提高模型价格、无限延长 timeout、跳过 QA 或放宽断言解决问题。
- 不移除 PM、RD、QA 四角色能力；档位只决定哪些角色和阶段在当前任务中实际参与。
- 不让模型获得宿主机任意 Shell。
- 不在本版本重写 Memory、飞书入口或 NewAPI Provider。
- 不对已有未完成项目执行隐式状态迁移；没有 v2 标记的旧项目继续走 legacy 模式。
- 不在用户确认需求前编码，不在二次授权前运行真实 E2E。



## 4. 功能点



### 4.1 工作流档位

- [x] 新增 `WorkflowProfile`：`SIMPLE`、`STANDARD`、`STRICT`。
- [ ] `SIMPLE`：需求确认 → RD 直接实现 → QA 验收 → 交付；不强制 PM、独立技术设计回合或子 Agent。
- [ ] `STANDARD`：保留 PM、RD、QA 与 Task 18 现有产物契约，但 optional review 不阻断、RD 默认直接执行、不强制子 Agent。
- [ ] `STRICT`：保留完整阶段和阻断式 ReviewRound，可允许复杂任务使用子 Agent。
- [ ] profile 由项目创建参数或运行配置确定，禁止模型在中途自行切换。
- [ ] Task 18 四个既有场景固定使用 `STANDARD`，保证同一场景不因随机 message ID 或历史次数改变分支。



### 4.2 确定性评审策略

- [x] 新增 `ReviewPolicy`：`REQUIRED_ONLY`、`ADVISORY_AUDIT`、`STRICT_BLOCKING`。
- [x] 高/严重影响、`self_score < 0.70`、`hard_constraints < 0.80` 属于 mandatory risk，可创建阻断式 ReviewRound。
- [ ] “同 Skill 完成不足 3 次”和 15% 抽查只产生 `review_suggested`/审计指标，不阻断 `SIMPLE` 和 `STANDARD` 主流程。
- [x] `STRICT_BLOCKING` 才允许两个 Reviewer 形成 barrier；其他 policy 固定一个确定性 Reviewer。profile 与 policy 的组合仍由 Task 4 协调器复核。
- [x] 每个 ReviewRound 持久化预期 Reviewer、已收到结论、deadline、最终 verdict 和失败原因。
- [ ] Reviewer 超时必须形成明确 `EXPIRED/BLOCKED`，不得只留下 `review_request=in_progress`。



### 4.3 单一 WorkExecution 事实源

- [x] 每个活动工作项有唯一 execution ID，并绑定 project、stage、owner、assignment、dispatch、Agent session、child task、test execution 和 review round。
- [x] runtime v2 aggregate 强制同一 project/stage/owner 同时最多一个非终态 WorkExecution。
- [x] 状态包含：`PENDING`、`CLAIMED`、`RUNNING`、`WAITING_REVIEW`、`RECOVERING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`BLOCKED`。
- [x] 领域状态机中的 retry/continuation 复用原 WorkExecution ID，不得另建平行事实源。
- [x] `last_progress_at` 只由显式 `recordProgress` 业务证据刷新；关联 assignment、dispatch、child task 或 test execution 均不会续租。
- [x] watchdog 和 incident 已使用同一 WorkExecution 状态；stale mailbox lease 不得覆盖 RUNNING、WAITING_REVIEW 或终态，incident 保持 stage/owner 并在同 execution 内 RECOVERING/BLOCKED。
- [ ] Driver 使用同一 WorkExecution 状态与时间戳直接诊断当前任务，不再从旧投影推导。



### 4.4 项目级原子协调与 outbox

- [x] 新增项目级 runtime v2 状态文件，原子保存 WorkExecution、ReviewRound、已处理 command ID 和待投影 outbox。
- [x] `completeWorkItem(command)` 在一次项目锁内完成幂等检查、证据门禁、工作项终态、review 决策和下一工作项计算。
- [x] event、mailbox 和 Cron wake 以稳定 action ID 幂等投影；副作用成功后原子确认 APPLIED，失败记录有界类型并最多尝试三次，启动后后台重放 pending action。
- [x] runtime v2 aggregate 采用单文件、项目级文件锁、强制刷盘和原子替换，不依赖跨多个 JSON/JSONL 文件的物理事务。
- [x] shutdown 在停止入站、Cron/wake 和 dispatch 并有限排空后，把非终态 WorkExecution/ReviewRound 持久化为 `CANCELLED/RUNTIME_SHUTDOWN`，再停止 projector 和外部 Agent/MCP。



### 4.5 最小 task_done 协议

- [ ] RD/QA 测试阶段只需提交 `execution_id`、`artifacts`、`decision`、`rationale` 和可选自评。
- [ ] `status`、`exit_code`、pytest 数量、coverage、failure type、retryable 由 Java 从真实 Test Runner 注册表读取并附加到事件/审计。
- [ ] 兼容读取旧 8 字段正文，但忽略模型复制值并以 Java 账本为准；若旧值冲突，记录有界审计而不是制造第二事实源。
- [ ] task_done 类型必须在工具边界统一解析，模型传 Map 或合法 JSON 字符串得到相同语义。



### 4.6 简单任务去子 Agent

- [ ] `SIMPLE` 和 `STANDARD` 的 RD 代码阶段默认由父角色直接执行 `read_shared`、`write_shared`、`sandbox_execute_bash` 和 `run_project_tests`。
- [ ] 只有 `STRICT` 或 Java 明确标记 `subagent_required=true` 时才允许 `agent_spawn/agent_send`。
- [ ] Skill 不再要求模型自行判断是否创建多个 continuation。
- [ ] 保留既有父子取消、单飞和 shutdown 能力，供真正启用子 Agent 的任务使用。



### 4.7 固定验收契约

- [ ] E2E Harness 在项目创建后写入角色只读的 `acceptance/contract.json` 和受控测试入口。
- [ ] 契约包含稳定 requirement ID、输入、预期、证据类型和是否必须自动化。
- [ ] RD 可以新增自己的单测，但不能修改 E2E 验收契约或权威验收测试。
- [ ] QA 测试计划必须引用固定 requirement ID，不得重新解释或扩写 happy-path 范围。
- [ ] Todo happy-path 至少固定验证倒序、单文件 HTML、CRUD、404/422、持久化和无框架约束。



### 4.8 分层 E2E 与可观测性

- [ ] L0：纯 Java domain/app 测试，验证状态、幂等和 outbox。
- [ ] L1：NoCallModel + 真实文件仓储，验证完整 WorkExecution 主路径。
- [ ] L2：NoCallModel + 真实 Docker/AgentScope/MCP，验证工具和生命周期。
- [ ] L3：真实模型最小主路径；只在 L0～L2 全通过且用户二次授权后执行。
- [ ] `happy-path` 不再随机插评审；review/revision 由独立测试覆盖。
- [ ] summary 增加 profile、review policy、work execution 数、模型行政工具调用数、outbox pending 数和最终权威状态。



## 5. 业务规则

1. Java 是 workflow stage、WorkExecution、ReviewRound 和测试终态的唯一权威。
2. 模型不得选择下一阶段、下一 Owner、retry attempt 或 review 数量。
3. 同一 command ID 重复提交必须返回同一结果，不得产生重复邮件、事件或 wake。
4. 同一 project/stage/owner 同时只能有一个开放 WorkExecution。
5. mailbox lease 只能反映投影消费状态，不能反向覆盖 WorkExecution 终态。
6. mandatory review 未通过时保持当前业务阶段，并创建同一 WorkExecution 的 revision；不得并行创建新阶段工作项。
7. advisory review 不阻断主流程、不持有主流程 lease，也不能把已完成工作重新改为运行中。
8. pytest 业务失败由 RD/QA 处理；MCP/传输/AgentScope 基础设施失败由 Java incident 和 WorkExecution 状态处理。
9. Test Runner 的权威终态只按 execution ID 查询，模型文本不得覆盖。
10. E2E 验收契约只由 Harness/Java 测试夹具创建；PM、RD、QA 无写权限。
11. `STANDARD` 必须继续产出 Task 18 当前要求的 `design/product_spec.md`、`tech/tech_design.md`、`code/main.py`、`qa/test_plan.md`、`qa/test_report.md`。
12. legacy 项目没有 `workflow_version=2` 时继续使用旧链路，禁止自动推断或半迁移。



## 6. 数据变更

不新增数据库表。新增文件型持久化模型：


| 操作  | 路径                                               | 内容                                                                        | 说明               |
| --- | ------------------------------------------------ | ------------------------------------------------------------------------- | ---------------- |
| 新增  | `shared/projects/<pid>/runtime/workflow-v2.json` | profile、review policy、WorkExecution、ReviewRound、processed commands、outbox | v2 唯一权威状态；原子写入   |
| 新增  | `shared/projects/<pid>/acceptance/contract.json` | 稳定验收条目                                                                    | E2E/受控项目只读       |
| 保留  | `events.jsonl`                                   | 可追溯事件投影                                                                   | 不再用来反推唯一当前状态     |
| 保留  | `mailboxes/*.json`                               | 角色通知投影                                                                    | lease 不再代表业务执行终态 |
| 保留  | Cron tasks                                       | wake 执行投影                                                                 | 由 outbox 幂等创建    |


`workflow-v2.json` 写入要求：

- 临时文件 + fsync/flush（平台允许时）+ 原子移动；
- 项目级文件锁；
- schema version；
- 不记录 prompt、模型完整输出、API Key、MCP Header 或未脱敏 stdout/stderr；
- pending outbox 项必须包含稳定 action ID、类型、目标和有界 payload；
- 已应用 outbox 可按上限压缩，但 processed command ID 必须有有界去重窗口。



## 7. 接口与配置变更



### 7.1 配置

建议新增：


| 配置                                         | 默认值                                | 说明                                       |
| ------------------------------------------ | ---------------------------------- | ---------------------------------------- |
| `qingling.workflow.coordination-mode`      | `legacy`（开发期间）/ 完成后切 `coordinated` | 显式控制新旧协调链                                |
| `qingling.workflow.default-profile`        | `STANDARD`                         | 新项目默认档位                                  |
| `qingling.workflow.review-policy`          | `REQUIRED_ONLY`                    | 默认评审策略                                   |
| `qingling.workflow.advisory-audit-enabled` | `true`                             | 仅记录建议，不阻断                                |
| `qingling.workflow.review-timeout`         | 有界配置                               | ReviewRound deadline，不与 Agent timeout 共用 |




### 7.2 内部应用接口

建议新增或等价实现：

```java
WorkCompletionResult completeWorkItem(CompleteWorkItemCommand command);

WorkExecution claimWorkItem(ClaimWorkItemCommand command);

ReviewRoundResult submitReview(SubmitReviewCommand command);

int projectPendingOutbox(String projectId);
```

具体命名允许实现阶段按项目约定调整，但必须保持 domain/app 不依赖 AgentScope、Reactor、MCP 或 Spring 具体类型。

### 7.3 Agent 工具协议

- `send_mail(type=task_done)` 可以保留对模型的兼容入口，但内部必须转换为 `CompleteWorkItemCommand`。
- Manager 不再需要为正常 task_done 逐项调用 `check_review_criteria`、`append_event`、`mark_done` 和 `advance_workflow`。
- `check_review_criteria` 兼容期可保留为只读诊断，不能成为 coordinated 模式的推进前置。
- Review 提交必须引用 `review_round_id`，不能只靠自由文本 subject 关联。



## 8. 影响范围


| 模块                      | 影响                                                             |
| ----------------------- | -------------------------------------------------------------- |
| `qingling-team-domain`  | WorkExecution、ReviewRound、WorkflowProfile、ReviewPolicy、状态规则与端口 |
| `qingling-team-app`     | 完成协调器、outbox 投影调度、claim/review 用例、watchdog 统一读取                |
| `qingling-team-infra`   | 文件仓储、Agent 工具适配、Test Runner 证据补全、AgentScope profile 限制         |
| `qingling-team-starter` | 配置装配、workspace 模板、E2E Driver、分层 IT                             |
| AIO-Sandbox             | 不改服务端；继续通过现有 MCP 和 Test Runner 执行                              |
| DashScope/NewAPI        | 不改 Provider 协议；模型收到的行政任务减少                                     |
| 旧项目数据                   | 不隐式迁移；legacy 路径保持可回退                                           |




## 9. 风险与关注点

1. **双事实源风险**：迁移期同时存在 runtime v2 与旧 mailbox/event。必须明确 v2 是权威、旧文件是投影，不能双向同步。
2. **outbox 重复风险**：投影动作必须带稳定 ID，事件、邮件和 wake 都要幂等。
3. **旧项目兼容风险**：只根据显式 `workflow_version` 选模式，不能根据文件是否存在猜测。
4. **评审降级风险**：advisory 不等于删除质量控制；mandatory risk 仍需阻断，固定验收契约承担基础质量门禁。
5. **简单档位越权风险**：SIMPLE 可以跳过 PM，但不能跳过用户 checkpoint、真实测试和 QA 验收。
6. **测试夹具污染风险**：只读验收契约不能包含凭据，不能被模型工具写入或通过路径逃逸修改。
7. **一次性大改风险**：必须通过 feature flag 逐层接入，先 shadow/对照，再切 coordinated 默认。
8. **当前工作区风险**：已有 Task 18 未提交修改和用户文件，实施时必须保留，禁止 reset/checkout/覆盖。



## 10. 测试策略

- **测试范围**：domain 状态转换、文件仓储原子性、command 幂等、outbox 重放、review policy、最小 task_done、legacy 兼容、NoCallModel 工作流、真实 Docker 工具契约、最终真实模型 E2E。
- **覆盖率目标**：新增 domain/app 核心分支行覆盖率不低于 90%；所有 P0 状态转换和崩溃恢复分支必须有显式断言。
- **性能/复杂度目标**：coordinated 模式正常阶段推进不依赖 Manager 行政工具链；相同 Task 18 场景的 workflow 分支必须完全可复现。
- **独立 Test Spec**：是，见 `test-spec.md`。
- **真实 E2E 门禁**：本版本编码确认后仍不自动运行，必须完成前三层验证并再次请求用户确认。



## 11. 待确认

- [x] 确认版本目录名使用 `agent-workflow-determinism-1.0.0`。
- [x] 确认 Task 18 四个既有场景固定使用 `STANDARD + REQUIRED_ONLY`。
- [x] 确认新手期和 15% 抽查改为 advisory，不再阻断 SIMPLE/STANDARD。
- [x] 确认 SIMPLE/STANDARD 默认不强制 RD Sub-Agent。
- [x] 确认 runtime v2 aggregate + outbox 成为新项目唯一权威，旧 mailbox/event 仅作投影。
- [x] 确认旧项目不自动迁移，通过显式 legacy 模式继续运行。
- [x] 确认真实 E2E 需要实现完成后的第二次明确授权。



## 12. 技术决策


| 决策        | 选择                                 | 原因                    |
| --------- | ---------------------------------- | --------------------- |
| 架构语言      | 保留 Java/COLA                       | 当前问题不是语言和分层，而是编排所有权不清 |
| 模型职责      | 内容产出 + 有限语义结论                      | 行政状态迁移交给 Java 可降低随机失败 |
| 权威状态      | WorkExecution aggregate            | 避免从多个投影反推当前任务         |
| 跨文件一致性    | aggregate 内置 outbox                | 文件系统无法提供多文件物理事务       |
| 评审策略      | mandatory 阻断、audit/novice advisory | 同时保留质量控制和主流程确定性       |
| task_done | execution ID 引用真实账本                | 消除模型复制结构化运行结果造成的漂移    |
| 子 Agent   | SIMPLE/STANDARD 默认关闭               | 简单任务不应承担父子任务生命周期成本    |
| 验收测试      | Harness 固定只读契约                     | 防止实现和自测一起偏离需求         |
| E2E       | 分层、真实模型最后执行                        | 降低每次发现基础编排缺陷的时间和成本    |




## 13. 执行日志


| Task     | 状态  | 实际改动文件         | 备注                      |
| -------- | --- | -------------- | ----------------------- |
| 需求规划     | 已完成 | 本目录四份 Markdown | 仅新增方案文档，未编码、未测试、未运行 E2E |
| Task 1 | 已完成 | domain/starter 协议与配置 | 18 项定向测试通过，未运行 E2E |
| Task 2 | 已完成 | WorkExecution/ReviewRound | domain 63 项通过，未运行 E2E |
| Task 3 | 已完成 | runtime v2 aggregate/文件仓储 | domain+infra 219 项通过，未运行 E2E |
| Task 4 | 已完成 | Java 完成协调器/Test Runner 证据适配 | 新增协调器测试 11 项；domain+app+infra 共 267 项通过，未运行 E2E |
| Task 5 | 已完成 | 幂等投影、incident/watchdog/shutdown/Spring 接线 | 联合模块共 367 项通过，`git diff --check` 通过，未运行 E2E |
| Task 6 | 已完成 | 最小完成协议、ReviewRound 工具、评审 deadline | 联合回归 375 项通过，`git diff --check` 通过，未运行 E2E |
| Task 7 | 已完成 | profile 状态机、v2 项目入口、角色/子 Agent 策略与模板 | SIMPLE 跳过 PM/独立设计阶段；SIMPLE/STANDARD 父角色直做；STRICT 保留子 Agent；完整单元回归 393 项通过 |
| Task 8 | 已完成 | 固定验收 fixture、QA 独立 runner、Driver v2 诊断、零模型 coordinated IT | 聚焦 53 项及 contract IT 1 项通过；未运行真实 E2E |
| Task 9 | 非 E2E 已完成 | 完整回归、真实 Docker/NoCallModel 契约、coordinated IT 与三组需求回写 | 394 项默认回归、1 项 Docker 工具契约、1 项 coordinated IT 和 diff/orphan 检查均通过；真实 E2E 仍需二次授权 |




## 14. 审查结论

用户已确认实施，当前已完成 Task 1～8，并完成 Task 9 的全部非 E2E 门禁。新项目入口已接入 runtime v2，profile 在创建时冻结；固定 Todo 验收由 Harness 只读注入，QA Runner 不再以 RD 自测代替验收；Driver 直接诊断 execution/review/outbox/orphan；零模型 STANDARD 全链与真实 Docker 工具契约均已收敛。当前按 HARD-GATE 暂停，等待用户二次明确授权真实 E2E；四条场景尚未运行，因此父 Task 18 继续未完成。

## 15. 确认记录（HARD-GATE）

- **确认时间**：2026-10-25
- **确认人**：用户
- **确认说明**：用户明确要求按 `tasks.md` 开始实施代码；该确认不自动授权真实 E2E。
