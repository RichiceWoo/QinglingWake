# 变更日志 — Agent 工作流确定性与编排收敛 1.0.0

> 当前阶段：propose。记录系统性评估和方案决策；用户确认前不得编码。

## 时间线

| 时间 | 阶段 | 事件 | 备注 |
|------|------|------|------|
| 2026-10-25 | assess | 复盘 Task 18 多轮真实 E2E 失败 | 失败边界从 MCP、Test Runner、AgentScope 生命周期逐步移动到 review、完成协议和阶段等待；判断为系统性编排问题，不是单一缺陷 |
| 2026-10-25 | evidence | 审计最近一次 `happy-path` | 约 43 分钟、231 次工具调用、8 次 Test Runner 最终 exit 0；MCP/dispatch/AgentScope 终态健康，但 PM review in_progress，QA 发现真实排序契约偏差 |
| 2026-10-25 | decision | 判断 Java/COLA 不是根因 | 领域状态机和模块测试可靠；主要问题是 AgentScope/模型承担了过多行政工作流责任 |
| 2026-10-25 | propose | 新增本版本四份需求文档 | 只新增 Markdown，未修改源码、配置、测试或 E2E；等待用户确认 |

## 失败模式归类

| 类别 | 历史表现 | 当前判断 |
|------|----------|----------|
| MCP/沙盒 | 共享 shell PS1 失步、恢复复用坏会话、cwd 错误熔断 | 已有针对性修复，最近一次四路 circuit 全部健康 |
| Test Runner | 大输出截断、exit code 终态竞态、execution 恢复预算 | 已有结构化终态和真实 Docker 契约，最近一次最终 exit 0 |
| AgentScope 生命周期 | spawn/send/task_output 超时、父子取消、重复 continuation | 已多轮修复；仍不应让简单任务默认承担这套生命周期 |
| mailbox/dispatch | stale lease、重复 assignment、orphan dispatch | 已增强 watchdog/诊断，但其状态仍与 workflow 分离 |
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

## 知识发现

- [ ] **Agent 工作流事实源**：模型、mailbox 和 dispatch 都不应成为 workflow 当前状态的权威 → 建议 `knowledge/agent-work-execution.md`
- [ ] **文件型 outbox**：在无数据库事务时用单 aggregate + 幂等投影实现可恢复一致性 → 建议 `knowledge/file-outbox-pattern.md`
- [ ] **模型职责边界**：模型负责语义与产物，Java 负责行政状态和副作用 → 建议 `knowledge/model-orchestration-boundary.md`
- [ ] **独立验收契约**：生成者自测不能替代外部验收 → 建议 `knowledge/independent-acceptance-contract.md`

## Spec-Code 偏差记录

| 偏差点 | Spec 预期 | 实际情况 | 处理方式 |
|--------|-----------|----------|----------|
| 当前阶段 | 只规划，不编码 | 已遵守 | 等待用户确认 HARD-GATE |

## 代码质量备忘

- 实施时强制使用 `chinese-code-comments` skill。
- domain/app 不得依赖 AgentScope、Reactor、MCP、Spring 或文件实现类型。
- runtime v2 不得记录 prompt、模型完整输出、Key、鉴权 Header 或未脱敏日志。
- 不使用无限重试、宽泛 catch、固定 sleep 或单纯扩大超时掩盖状态不收敛。
- 当前工作区已有 Task 18 修改、`CompletableFutureDemo.java` 和 `tmp.txt`，必须保留且不得覆盖。
- 每个 Task 完成后先记录真实验证，再勾选文档；不得提前宣称通过。
