# 变更日志 — xiaopaw-team Python → Java (COLA + AgentScope) 项目迁移

> 记录决策、踩坑与知识发现；归档时可挑选沉淀到 `spec-workflow/knowledge/`。

## 时间线

| 时间 | 阶段 | 事件 | 备注 |
|------|------|------|------|
| 2026-09-13 | propose | 完成源项目分析 + spec/tasks/test-spec 文档生成 | 等待用户确认 |
| 2026-09-25 | review | 完成跨语言迁移 Spec 审阅 | 发现 AgentScope、Session、Skills、范围、测试五项 P0 |
| 2026-09-25 | propose | 用户选择 `1A / 2A / 3A / 4A / 5A` | 修订四份 Spec 文档，仍等待 HARD-GATE |
| 2026-09-25 | apply | 用户确认修订需求并授权开始编码 | 按 tasks 顺序执行，不自动 commit/push |
| 2026-09-25 | apply | 完成 Task 1 Maven 五模块骨架 | `mvn -DskipTests compile` 通过 |
| 2026-09-25 | apply | 完成 Task 2 AgentScope 2.0.3 编译探针 | Harness/Model/Tool/Sub-Agent/RuntimeContext/MCP 共 4 项测试通过 |
| 2026-09-25 | apply | 完成 Task 3 领域模型与 Gateway 契约 | 16 个生产类；领域契约与 ArchUnit 共 4 项测试通过 |
| 2026-09-25 | apply | 完成 Task 4 Session 路由、命令与审计存储 | domain/app/infra 回归共 14 项测试通过；审计无 history loader |
| 2026-09-25 | apply | 完成 Task 5 邮箱、共享工作区与事件日志 | Python golden、三态并发、权限/逃逸、事件 seq 与损坏行恢复测试通过；Task 5 命令共 32 项测试 |
| 2026-09-25 | apply | 完成 Task 6 Human checkpoint、分类与自评 | callback/id/latest pending 分类优先级、JSONL resolve marker、五维 HALF_UP 自评及 Python golden 通过；对应命令共 57 项测试 |
| 2026-09-26 | apply | 完成 Task 7 Cron、wake、heartbeat 与 Cleanup | at/every/cron、时区、mtime+size 热重载、并发 wake 去重、四角色错峰、同角色 heartbeat 抑制、data root 防逃逸及凭证权限通过；对应命令共 72 项测试 |

## 技术决策

| 决策 | 选择 | 放弃的方案 | 原因 |
|------|------|------------|------|
| Agent 框架 | AgentScope Java `2.0.3` HarnessAgent | 2.0.0 / 浮动最新版本 / CrewAI | 用户选择 1A；版本固定且使用 Harness 完整能力 |
| 项目架构 | COLA 5.x | 传统三层 / DDD 标准 | 用户指定；COLA 分层清晰，适合多 Agent 系统 |
| Java 版本 | Java 17 | Java 21 (Virtual Threads) | Spring Boot 3.x 最低要求，LTS 版本，兼容性最广 |
| 异步模型 | App 使用 CompletableFuture；AgentScope adapter 内 Reactor → Future | 全局 Reactor / Virtual Threads | 用户选择 1A；框架类型不泄漏到 domain/app |
| 会话与记忆 | Harness 是模型上下文唯一事实源；自研 Session 只管路由/命令/审计 | 自研全量上下文 / 双主双写 | 用户选择 2A；避免重复注入和状态漂移 |
| Workspace/Skills | 转换为 AgentScope 外部可写 workspace；保留原 Python 交付行为 | 原样复制 / 全部改成生成 Java | 用户选择 3A；运行平台 Java 化且业务能力等价 |
| 迁移范围 | 完整迁移矩阵 | 核心版 / 延期未声明 | 用户选择 4A；所有源模块必须有明确去向 |
| 测试策略 | 单元 + 契约 + 集成 + 4 条真实 E2E | 只做单测 / 全部 7 条 E2E | 用户选择 5A；成本与等价信心平衡 |
| 飞书 SDK | oapi-sdk-java | 自封装 HTTP | 官方 SDK 维护，WebSocket 支持完整 |
| JSON 处理 | Jackson | Gson / Fastjson2 | Spring Boot 默认集成，生态成熟 |
| 文件锁 | Java NIO FileLock | Apache Commons IO | JDK 原生，无额外依赖 |
| Cron 表达式 | cron-utils 库 | 自实现 parser | 成熟库，支持时区 |
| 测试框架 | JUnit 5 + Mockito + AssertJ | TestNG | Java 社区主流，COLA 项目标配 |

## 踩坑记录

| 问题 | 原因 | 解决方案 | 沉淀到 knowledge？ |
|------|------|----------|-------------------|
| 原 Spec 使用错误的 `HarnessAgent` 包名 | 未以 AgentScope 2.0.3 编译验证 API | 增加 AgentScope compile/MCP probe，正式实现前先验证 | 是 |
| Harness 与自研 Context 双轨 | 未指定模型上下文事实源 | Harness 管模型 state/memory；JSONL 只作审计 | 是 |
| workspace 原样复制不可运行 | CrewAI Skill/工具名与 AgentScope 语义不同，classpath 不可长期写 | 转换 Skill/Sub-Agent 并初始化到外部 workspace | 是 |
| 迁移模块遗漏 | 仅按核心链路拆任务，没有逐文件盘点 | 新增完整迁移矩阵 | 是 |
| 测试无法证明跨语言等价 | 原计划主要是 Java 单元测试 | 增加 Python golden contract、集成和 4 E2E | 是 |
| Mockito 5 inline mock maker 在当前 JDK 无法自附加 | 当前运行环境拒绝 Byte Buddy 动态 attach | Task 5 新测试使用确定性 fake；App 测试配置 `mock-maker-subclass` 后全量回归通过 | 否 |
| self_score 结构化输出与 raw 同时存在 | Python 只要 `json_output` 声明了 `self_score` 就不再回退 raw | Java 提取器保持相同优先级，避免用 raw 掩盖结构化分数错误 | 否 |
| 同角色 AT wake 与 heartbeat 同时到期 | 两个 Cron job 都投递会让同一角色重复执行 | Cron tick 优先投递业务 wake，同时推进 heartbeat 的下次时间但不重复投递 | 否 |

## 知识发现

> 每个 Task 后可追加；`/archive` 前逐条确认是否写入 `knowledge/index.md` 与专题文档。

- [ ] **Python→Java 映射**: CrewAI 概念到 AgentScope 概念的映射关系 → 建议文件 `knowledge/crewai-to-agentscope-mapping.md`
- [ ] **COLA 分层规则**: 多 Agent 系统在 COLA 架构中的模块归属 → 建议文件 `knowledge/cola-agent-system.md`
- [ ] **飞书 SDK 差异**: Python lark-oapi 与 Java oapi-sdk-java 的 API 差异点 → 建议文件 `knowledge/feishu-sdk-diff.md`
- [ ] **Harness Session 边界**: routing index、AgentState、审计 JSONL 的单一职责 → 建议文件 `knowledge/agentscope-session-boundary.md`
- [ ] **Workspace 转换**: CrewAI reference/task Skill 到 AgentScope Skill/Sub-Agent 的映射 → 建议文件 `knowledge/agentscope-workspace-migration.md`

## Spec-Code 偏差记录

| 偏差点 | Spec 预期 | 实际情况 | 处理方式 |
|--------|-------------|----------|----------|
| AgentScope 版本 | 原文混用“最新稳定版”、`>=1.0`、`2.0.0` | 用户选择固定 `2.0.3` | 已同步 spec/tasks/test-spec |
| Session/Memory | 原文同时规划 Harness 与自研 ContextManager | 用户选择 Harness 为模型上下文真相源 | 删除自研模型上下文任务，仅保留路由和审计 |
| Skills | 原文要求 resources 原样复制 | 用户选择转换为 AgentScope 外部 workspace | 重写 workspace 任务和测试 |
| 测试 | 原文主要覆盖 domain/app 单测 | 用户选择 5A | 增加契约、IT-01～06 和 4 E2E |
| `Mono.toFuture()` 探针 | Spec 要求从 Agent 调用边界验证 | 使用无外部调用的 `Mono<Msg>` 固化转换类型，正式 gateway 继续以真实 `agent.call(...).toFuture()` 验证 | 避免探针请求真实模型 |
| Session 索引表示 | domain 只暴露当前活跃 `SessionRoute` | infra 在兼容 JSON 中保留每个 routing key 的历史 `sessions` 数组 | Harness 管模型 state；索引历史仅用于路由元数据兼容 |
| wake 去重原子性 | Python `schedule_wake` 的查重与 `create_job` 分属两次文件锁 | Java Repository 在一次跨线程/跨进程文件锁内完成查重和追加 | 消除并发 send_mail 产生重复 wake 的窗口 |

## 代码质量备忘

- domain/app 不得暴露 AgentScope/Reactor/飞书/JDBC 类型。
- 不得把审计 JSONL 再次拼入 Harness prompt。
- 文档 HARD-GATE 已由用户在 2026-09-25 明确确认，可按 tasks 开始 Java 实现。
