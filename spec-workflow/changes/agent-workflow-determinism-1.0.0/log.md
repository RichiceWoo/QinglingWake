# 变更日志 — Agent 工作流确定性与编排收敛 1.0.0

> 当前阶段：apply。用户已确认编码，真实模型 E2E 仍受二次授权门禁约束。

## 时间线

| 时间 | 阶段 | 事件 | 备注 |
|------|------|------|------|
| 2026-10-25 | assess | 复盘 Task 18 多轮真实 E2E 失败 | 失败边界从 MCP、Test Runner、AgentScope 生命周期逐步移动到 review、完成协议和阶段等待；判断为系统性编排问题，不是单一缺陷 |
| 2026-10-25 | evidence | 审计最近一次 `happy-path` | 约 43 分钟、231 次工具调用、8 次 Test Runner 最终 exit 0；MCP/dispatch/AgentScope 终态健康，但 PM review in_progress，QA 发现真实排序契约偏差 |
| 2026-10-25 | decision | 判断 Java/COLA 不是根因 | 领域状态机和模块测试可靠；主要问题是 AgentScope/模型承担了过多行政工作流责任 |
| 2026-10-25 | propose | 新增本版本四份需求文档 | 只新增 Markdown，未修改源码、配置、测试或 E2E；等待用户确认 |
| 2026-10-25 | confirm | 用户确认按 `tasks.md` 开始实施 | 解除编码门禁；保留真实 E2E 二次授权门禁，不 commit、不 push |
| 2026-10-25 | Task 1 | 冻结 v2 协议、配置和兼容边界 | 新增 5 个领域协议类型及测试；新增 `qingling.workflow` 类型安全配置和 fail-fast 校验；默认 `legacy + STANDARD + REQUIRED_ONLY` |
| 2026-10-25 | verify | Task 1 定向验证通过 | `WorkflowProtocolTest` 4 项、`RuntimeBootstrapTest` 14 项，共 18 项通过；BUILD SUCCESS，13.215s；未运行真实 E2E |
| 2026-10-25 | Task 2 | 实现 WorkExecution 与 ReviewRound 领域模型 | 工作执行新增 9 态有限转换、原 execution retry/revision、显式进度租约；评审新增必要/建议语义、单 Reviewer 与 Strict 双 Reviewer barrier、deadline 终态 |
| 2026-10-25 | verify | Task 2 domain 全量验证通过 | `mvn -pl qingling-team-domain test` 共 63 项通过，其中新增 WorkExecution 7 项、ReviewRound 8 项；BUILD SUCCESS，2.035s；未运行真实 E2E |
| 2026-10-25 | Task 3 | 实现 runtime v2 单 aggregate 与文件仓储 | schema=2、项目级 JVM/FileLock、write/force/atomic move、command 稳定结果去重、pending/applied outbox、敏感数据拒绝；legacy 查询不建 v2 半成品 |
| 2026-10-25 | verify | Task 3 domain+infra 全量通过 | domain 68 项、infra 151 项，共 219 项通过；新增 aggregate 5 项、仓储 8 项，覆盖并发、崩溃残留、重启重放、schema 损坏和凭据边界；BUILD SUCCESS，8.685s；未运行真实 E2E |
| 2026-10-25 | Task 4 | 实现 Java 工作完成协调器 | 新增完成/领取/启动/评审应用命令；在单个 runtime command 内校验产物与真实 Test Runner 证据，确定 mandatory/advisory/Strict 评审、Java 固定迁移和下一 WorkExecution/outbox；pytest 业务失败与基础设施失败分流 |
| 2026-10-25 | verify | Task 4 定向与模块回归通过 | `WorkCompletionCoordinatorTest` 11 项通过；domain 68、app 48、infra 151，共 267 项通过，0 failure/error/skip，BUILD SUCCESS（8.937s）；`git diff --check` 通过；未运行真实 E2E |
| 2026-10-25 | Task 5 | 接入幂等投影与 runtime v2 生命周期 | event/mailbox/Cron 以 action ID 幂等投影并有限重放；incident 首次 RECOVERING、第二次 BLOCKED，恢复领取复用 execution；watchdog 服从 v2 状态；shutdown 持久化未终态执行/评审；真实测试证据与完成协调器接入 Spring |
| 2026-10-25 | verify | Task 5 定向与联合回归通过 | app 定向 22 项、infra/starter 定向 19 项通过；联合回归 domain 68、app 56、infra 154、adapter 13、starter 76，共 367 项，0 failure/error/skip，BUILD SUCCESS（17.953s）；`git diff --check` 通过；未运行真实 E2E |
| 2026-10-25 | Task 6 | 收敛最小完成协议与 ReviewRound 工具 | `read_inbox` 绑定 WorkExecution 上下文；`task_done` 的 Map/JSON 输入统一归一化，真实 Test Runner 账本补全终态，旧 7 项测试字段仅记录字段名审计；正常完成绕过 Manager 行政链；`review_done` 强制 review_round_id；新增 mandatory/advisory deadline watchdog 与生命周期接线 |
| 2026-10-25 | verify | Task 6 定向与联合回归通过 | 定向 app 14、infra 2、starter 18 项通过；协议/模板聚焦 app 14、starter 20 项通过；联合回归 domain 68、app 58、infra 156、adapter 13、starter 80，共 375 项，0 failure/error/skip，BUILD SUCCESS（16.910s）；`git diff --check` 通过；未运行真实 E2E |
| 2026-10-25 | Task 7 | 接入 profile 并取消 SIMPLE/STANDARD 强制子 Agent | `create_project` 创建 runtime v2 与需求 checkpoint；SIMPLE 只构造 Manager/RD/QA 并跳过 PM/独立设计阶段；STANDARD 保留四角色产物链；SIMPLE/STANDARD 移除子 Agent 声明和 continuation 工具，STRICT 保留；模板改为父角色直做 |
| 2026-10-25 | fix | 修正 Task 7 聚焦测试发现的模板契约漂移 | 首轮聚焦测试仅 `WorkspaceTemplateContractTest` 1 项失败：仍硬编码“进入 PM”；将断言与 Manager Skill 改为冻结 profile 分流后复测通过，没有回退正确实现 |
| 2026-10-25 | verify | Task 7 聚焦与完整单元回归通过 | 聚焦 domain 23、app 17、infra 12、starter 24，共 76 项通过；`mvn test` 通过 domain 72、app 61、infra 159、adapter 13、starter 88，共 393 项，0 failure/error/skip，BUILD SUCCESS（17.539s）；`git diff --check` 通过；未运行真实 E2E |
| 2026-10-25 | Task 8 | 接入 Harness 固定验收契约与 Driver v2 直接诊断 | 新增 8 个稳定 Todo requirement、只读 fixture 和独立 pytest；QA Runner 固定执行 acceptance；Driver 直接报告 review/outbox/execution/orphan 并扩展 summary/evidence；QA 计划强制 requirement ID 映射 |
| 2026-10-25 | fix | 修正 Task 8 聚焦测试发现的 QA Runner shell 引号问题 | 首轮聚焦测试仅 1 项断言失败并暴露嵌套 `sh -c` 内部双引号未转义；修正为显式转义后，避免目标列表在外层 shell 被拆词，复测通过 |
| 2026-10-25 | verify | Task 8 分层验证通过 | 聚焦 53 项通过；验收 Python AST 通过；contract profile 下 domain 72、app 61、infra 159、adapter 13、starter 89 单元测试及 `CoordinatedWorkflowContractIT` 1 项通过，BUILD SUCCESS（21.803s）；`git diff --check` 通过；未 source 凭据、未运行真实 E2E |
| 2026-10-25 | Task 9 | 完成全部非 E2E 门禁 | 相关模块 165 项、最终默认回归 394 项、coordinated IT 1 项通过；首次 Docker 工具契约真实暴露 QA fixture 未安装并以 exit_code=4 失败，复用公共只读 Todo fixture 并补齐最小 FastAPI/SQLite 应用后重跑 1 IT 通过；`git diff --check` 和 orphan 复核通过 |
| 2026-10-25 | hard-gate | 暂停等待真实 E2E 二次授权 | 未 source E2E 凭据、未调用真实模型、未运行四条真实场景；父 Task 18 保持未完成 |

## 失败模式归类

| 类别 | 历史表现 | 当前判断 |
|------|----------|----------|
| MCP/沙盒 | 共享 shell PS1 失步、恢复复用坏会话、cwd 错误熔断 | 已有针对性修复，最近一次四路 circuit 全部健康 |
| Test Runner | 大输出截断、exit code 终态竞态、execution 恢复预算 | 已有结构化终态和真实 Docker 契约，最近一次最终 exit 0 |
| AgentScope 生命周期 | spawn/send/task_output 超时、父子取消、重复 continuation | 已多轮修复；仍不应让简单任务默认承担这套生命周期 |
| mailbox/dispatch | stale lease、重复 assignment、orphan dispatch | Task 5 已让 watchdog 服从 v2 WorkExecution 并以 outbox action ID 去重投影；Task 8 Driver 已直接读取 v2 execution/review/outbox，并识别无 dispatch 的 RUNNING orphan |
| 完成协议 | task_done JSON 类型、重复复制测试字段、mark_done 顺序 | 当前仍有重复事实源，应收敛到 execution ID + Java 补全 |
| 评审流程 | 新手/抽查触发双 Reviewer，单 reviewer 悬挂阻断 | 当前最高优先级业务设计问题 |
| 业务质量 | 实现与自测共同偏离倒序契约 | 需要独立固定验收测试，不可只信 RD pytest |
| E2E 等待 | 绝对阶段预算误杀真实进展 | 当前未提交补丁已改为硬预算 + 无进展租约，但它不是编排根治 |

## 技术决策

| 决策 | 选择 | 放弃的方案 | 原因 |
|------|------|------------|------|
| 根因处理 | 收敛编排所有权 | 继续逐个增加 timeout/diagnostic | 单点补丁只会把失败推到下一个状态边界 |
| Java/COLA | 保留 | 迁回 Python/更换 Java 架构 | 问题在职责和状态源，不在语言 |
| 工作流权威 | WorkExecution aggregate | 从 event/mailbox/dispatch 动态反推 | 多投影组合无法稳定表达唯一当前工作 |
| 文件一致性 | aggregate + outbox | 跨多个 JSON/JSONL 假装事务 | 文件系统无法提供多文件原子提交 |
| 评审 | mandatory 阻断，novice/audit advisory | 所有条件 OR 后双人阻断 | 保留质量控制，同时保证主路径可复现 |
| Task 18 profile | STANDARD + REQUIRED_ONLY | 随机分支或 STRICT | Task 18 首要验证四角色交付，不应随机验证 review barrier |
| task_done | execution ID 引用 | 模型复制完整 runner 结果 | Java 已有权威结果，复制只制造类型和一致性错误 |
| 子 Agent | SIMPLE/STANDARD 默认关闭 | 每个 RD 代码任务强制 spawn | 简单应用不值得承担父子任务生命周期成本 |
| 验收 | 只读固定 acceptance contract | RD 自测作为唯一质量证据 | 实现与自测可能共同偏离需求 |
| 旧数据 | 显式 legacy，不隐式迁移 | 启动时自动猜测迁移 | 避免未完成项目半迁移和双事实源 |
| 真实 E2E | 二次用户授权 | 编码完成后自动执行 | 成本高且历史已多次失败，需在非 E2E 门禁后单独确认 |

## 待用户确认的关键取舍

1. `SIMPLE` 可跳过 PM 和独立技术设计回合，但仍保留用户 checkpoint、RD、QA 和交付。
2. `STANDARD` 保留 Task 18 当前产物和四角色业务能力，但不随机插阻断评审，也不强制 RD 子 Agent。
3. `STRICT` 保留完整评审和子 Agent 能力，用于真正高风险或复杂任务。
4. 新手期和 15% 抽查继续产生审计证据，但不阻断 SIMPLE/STANDARD。
5. 新项目 coordinated 模式使用 runtime v2 aggregate；旧项目不自动升级。

## 踩坑记录

| 问题 | 原因 | 方案 | 沉淀到 knowledge？ |
|------|------|------|-------------------|
| 每修一个故障，下次在更后面的边界失败 | 多个独立状态机共同承担一次业务事务 | 引入唯一 WorkExecution 和 outbox | 建议 |
| 相同 happy-path 有时评审、有时不评审 | 抽查基于每次变化的 message ID，且新手期依赖历史数据 | profile + deterministic review policy | 建议 |
| pytest 全绿仍有真实业务错误 | 实现和 RD 自测一起偏离需求 | Harness 固定只读验收契约 | 建议 |
| Manager 工具调用很多但业务价值低 | 确定性行政步骤仍由模型逐项执行 | Java 完成协调器 | 建议 |
| 简单 Todo 也出现父子任务取消/续跑问题 | 强制 Sub-Agent 放大生命周期 | SIMPLE/STANDARD 父角色直做 | 建议 |
| 首次 Docker 工具契约找不到 QA 固定验收文件 | Task 8 只在 E2E Driver 安装 fixture，独立工具契约未复用同一 Harness 入口 | 公开并复用 `TodoAcceptanceFixture`，在工具契约工作区安装只读验收并提供最小正确应用 | 建议 |

## 知识发现

- [ ] **Agent 工作流事实源**：模型、mailbox 和 dispatch 都不应成为 workflow 当前状态的权威 → 建议 `knowledge/agent-work-execution.md`
- [ ] **文件型 outbox**：在无数据库事务时用单 aggregate + 幂等投影实现可恢复一致性 → 建议 `knowledge/file-outbox-pattern.md`
- [ ] **模型职责边界**：模型负责语义与产物，Java 负责行政状态和副作用 → 建议 `knowledge/model-orchestration-boundary.md`
- [ ] **独立验收契约**：生成者自测不能替代外部验收 → 建议 `knowledge/independent-acceptance-contract.md`

## Spec-Code 偏差记录

| 偏差点 | Spec 预期 | 实际情况 | 处理方式 |
|--------|-----------|----------|----------|
| 编码门禁 | 用户确认后才编码 | 2026-10-25 已确认并从 Task 1 开始 | 已解除编码门禁，真实 E2E 门禁保持 |
| 基线全量验证 | 开始编码前记录 `mvn verify` 与零模型 Docker | 本轮开始前仅完成工作区状态和 diff 校验；未重复执行全量与 Docker | 如实记录偏差；Task 9 仍必须按既定顺序执行全量和 Docker 门禁，不以 Task 1 定向测试替代 |

## 代码质量备忘

- 实施时强制使用 `chinese-code-comments` skill。
- domain/app 不得依赖 AgentScope、Reactor、MCP、Spring 或文件实现类型。
- runtime v2 不得记录 prompt、模型完整输出、Key、鉴权 Header 或未脱敏日志。
- 不使用无限重试、宽泛 catch、固定 sleep 或单纯扩大超时掩盖状态不收敛。
- 当前工作区已有 Task 18 修改、`CompletableFutureDemo.java` 和 `tmp.txt`，必须保留且不得覆盖。
- 每个 Task 完成后先记录真实验证，再勾选文档；不得提前宣称通过。
