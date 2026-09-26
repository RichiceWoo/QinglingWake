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
| 2026-09-26 | apply | 完成 Task 8 workspace 模板转换与外部初始化 | 35 个源 Skill 全量映射为 29 个 reference Skill 与 6 个 task Skill/Sub-Agent；四角色 AgentScope 发现、seed/升级/改动保护、清单漂移和符号链接防护通过；对应命令共 80 项测试 |
| 2026-09-26 | apply | 完成 Task 9 AgentScope Tools 与角色 Toolkit | 8 个团队工具、Intermediate/图片/百度搜索工具完成；Manager 8 件、其他角色 5 件团队工具，辅助工具按 Skill 最小暴露，Python 参数 schema 与业务闭环通过；对应命令共 70 项测试 |
| 2026-09-26 | apply | 完成 Task 10 四角色 HarnessAgent、Sub-Agent 与 MCP | 四角色 workspace/Skill/声明式 Sub-Agent/Toolkit 构建通过；Gateway 以 RuntimeContext 传递路由并在 adapter 内 Mono→Future；MCP 状态脱敏可观测；无真实模型/MCP smoke 与回归共 76 项测试通过 |
| 2026-09-26 | apply | 完成 Task 11 Runner 完整执行链 | routing key 串行与跨 key 并行、wake 去重、Slash、session 附件、Loading、Agent、审计、卡片降级和 team 零外发完成；失败指标与队列恢复通过；对应命令共 56 项测试 |
| 2026-09-26 | apply | 完成 Task 12 飞书 Listener、Sender 与 Downloader | 官方 oapi-sdk-java 2.7.3 Channel/OpenAPI 接入；p2p/群白名单/Bot 入群、text/post/image/file、thread root、Loading/PATCH、1/2/4 秒重试、资源下载与脱敏通过；对应命令共 109 项测试 |
| 2026-09-26 | apply | 完成 Task 13 pgvector 记忆索引 | DashScope 摘要/标签、两份 1024 维向量、稳定幂等 ID、事务写入、空 DSN 跳过和旁路失败隔离完成；默认命令共 92 项测试；`pgvector-it` 完整 verify 成功，当前环境无 Docker，真实容器用例明确跳过 |
| 2026-09-26 | apply | 完成 Task 14 TestAPI 与 CaptureSender | Spring MVC 消息/会话端点、Python golden、默认 msgId/senderId、附件复制、300 秒可配置超时、400/422、CaptureSender 和会话/审计/AgentScope state 清理完成；对应命令 70 项、全项目 133 项测试通过 |
| 2026-09-26 | apply | 完成 Task 15 日志查询、Metrics 与结构化日志 | stats/tasks/steps/l1/all-agents JSONL 容错查询、Python CLI 契约、Runner/飞书/HTTP/错误 Prometheus 指标、`/metrics`、50MB×5 JSONL 滚动和凭据掩码完成；对应命令共 137 项测试通过 |
| 2026-09-26 | apply | 完成 Task 16 Starter、配置与生命周期装配 | Spring Boot 入口、Spec 第 6 节类型安全配置、显式 Bean 装配、无飞书启动、模型密钥脱敏 fail-fast、heartbeat/Cron 与入站→Runner→后台→AgentScope 反向关闭完成；对应命令共 140 项测试通过，离线测试未调用真实模型 |
| 2026-09-26 | apply | 完成 Task 17 跨语言契约与无 LLM 集成测试 | Python 只读快照生成确定性 fixture；源目录无 `.git`，以 11 个关键源文件 SHA-256 替代 commit 追溯；路由、Session、邮箱、事件、tasks、checkpoint、self_score、TestAPI、workspace 权限、自动唤醒及四角色 Skill 发现通过；`mvn -Pcontract-it verify` 共 140 项单测与 10 项契约/集成测试成功 |

## 技术决策

| 决策 | 选择 | 放弃的方案 | 原因 |
|------|------|------------|------|
| Agent 框架 | AgentScope Java `2.0.3` HarnessAgent | 2.0.0 / 浮动最新版本 / CrewAI | 用户选择 1A；版本固定且使用 Harness 完整能力 |
| 项目架构 | COLA 5.x | 传统三层 / DDD 标准 | 用户指定；COLA 分层清晰，适合多 Agent 系统 |
| Java 版本 | Java 17 | Java 21 (Virtual Threads) | Spring Boot 3.x 最低要求，LTS 版本，兼容性最广 |
| 异步模型 | App 使用 CompletableFuture；AgentScope adapter 内 Reactor → Future | 全局 Reactor / Virtual Threads | 用户选择 1A；框架类型不泄漏到 domain/app |
| 会话与记忆 | Harness 是模型上下文唯一事实源；自研 Session 只管路由/命令/审计 | 自研全量上下文 / 双主双写 | 用户选择 2A；避免重复注入和状态漂移 |
| Workspace/Skills | 转换为 AgentScope 外部可写 workspace；保留原 Python 交付行为 | 原样复制 / 全部改成生成 Java | 用户选择 3A；运行平台 Java 化且业务能力等价 |
| 模板升级 | `managed` 文件按上次模板 SHA-256 安全升级；`MEMORY.md` 使用 `seed` 永不覆盖 | 每次覆盖 / 所有文件只写一次 | 可发布模板修订，同时保护运行记忆、用户定制与清单外数据 |
| Toolkit 权限 | 公共团队工具按角色绑定；Manager 追加 3 件；图片/搜索按 Skill 显式启用 | 所有角色注册全部工具 | 减少模型误调用和额外文件、网络权限，保持 Manager 8 件/其他角色 5 件契约 |
| Harness 状态目录 | 每个角色写入其外部 workspace 的 `.agentscope/state` | AgentScope 默认用户主目录 | 会话状态随部署 workspace 管理，满足可写目录约束并避免容器/沙箱无主目录写权限 |
| 代码执行入口 | 关闭 Harness 本地 Shell Tool，RD/QA 仅使用 AIO-Sandbox MCP | 同时开放宿主 Shell 与 MCP | 保持 Python 产物执行能力，同时避免模型绕过隔离沙箱 |
| Runner 串行化 | 每个 routing key 使用可恢复的 CompletableFuture 尾链，不同 key 由执行器并行 | 全局锁 / 每 key 常驻线程 | 保证同会话顺序且无需常驻 worker；单次异常只传给自身 Future，尾链恢复后继续消费 |
| Runner 失败语义 | 记录脱敏指标、外部路由尝试错误提示，并让当前 dispatch Future 异常完成 | Python worker 完全吞掉异常 | Cron 可记录真实失败状态，同时不阻断相同 routing key 的后续消息 |
| Metrics 分层 | domain `MetricsGateway` 统一观测端口，infra 用 Micrometer 实现 | infra 直接依赖 app 的 Runner/HTTP 指标接口 | 保持 COLA 依赖方向，同时让 Runner、飞书和 TestAPI 共享七类 Python 兼容指标 |
| 迁移范围 | 完整迁移矩阵 | 核心版 / 延期未声明 | 用户选择 4A；所有源模块必须有明确去向 |
| 测试策略 | 单元 + 契约 + 集成 + 4 条真实 E2E | 只做单测 / 全部 7 条 E2E | 用户选择 5A；成本与等价信心平衡 |
| 飞书 SDK | oapi-sdk-java | 自封装 HTTP | 官方 SDK 维护，WebSocket 支持完整 |
| JSON 处理 | Jackson | Gson / Fastjson2 | Spring Boot 默认集成，生态成熟 |
| 文件锁 | Java NIO FileLock | Apache Commons IO | JDK 原生，无额外依赖 |
| Cron 表达式 | cron-utils 库 | 自实现 parser | 成熟库，支持时区 |
| 测试框架 | JUnit 5 + Mockito + AssertJ | TestNG | Java 社区主流，COLA 项目标配 |
| pgvector 集成测试 | Testcontainers 2.0.5 PostgreSQL 模块 + Failsafe profile | 默认构建强制连接数据库 / 手工 SQL 验证 | 默认构建无数据库依赖；启用 profile 时用 `pgvector/pgvector:pg16` 验证真实 DDL、双向量和幂等写入 |

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
| Task 8 规范将 `sandbox_execute_bash` 举作旧工具名 | AIO-Sandbox 当前 MCP 仍实际暴露同名工具，机械改名会导致声明不可调用 | 保留真实 MCP 工具名；移除 `skill_loader`、mailbox CLI、CrewAI/Sub-Crew 等旧胶水 | 是 |
| AgentScope `enableTools` 会先注册对象内全部 `@Tool` 方法 | 同一 `ImageAndSearchTools` 中有图片和搜索两个方法，仅启用其一时另一工具仍进入注册表 | 注册后显式移除未被当前 Skill 授权的工具，并以 Toolkit 名称集合测试固化 | 否 |
| Harness 默认状态目录在用户主目录 | AgentScope 默认创建 `~/.agentscope/state/{agent}`，受限运行环境可能不可写且与外部 workspace 生命周期分离 | TeamAgentFactory 显式注入角色 workspace 下的 `JsonFileAgentStateStore` | 是 |
| 异常 CompletableFuture 会毒化串行尾链 | 若直接将业务 Future 作为下一任务前置条件，单次 Agent 失败会使后续同 key 任务全部跳过 | 对外结果保留原异常，内部队列尾部用 `handle` 转为正常完成，并以测试覆盖“失败时已排队的下一项” | 是 |
| 飞书 Java SDK 同时提供底层 OpenAPI 与高层 Channel | 直接用底层 EventDispatcher 需要自行维护事件归一化、策略和重连 | 入站采用 Channel 归一化消息及重连事件，出站/下载采用 OpenAPI 请求模型；adapter 仍保留群白名单二次校验 | 是 |
| infra 单测首次触发 Mockito inline mock maker 自附加失败 | 当前 JDK/沙箱不允许 Byte Buddy 动态 attach | 与 app 模块保持一致，在 infra 测试资源中显式使用 `mock-maker-subclass`；默认回归通过 | 否 |
| 当前环境没有可用 Docker daemon | Testcontainers 无法启动 `pgvector/pgvector:pg16` | 集成用例使用 `disabledWithoutDocker=true`，profile 仍编译并执行到明确 skipped；有 Docker 的 CI/本机将自动运行真实 upsert 验证 | 否 |
| MockMvc 中文响应按默认字符集读取会产生乱码 | MockHttpServletResponse 的无参字符串读取不保证按响应 JSON 的 UTF-8 字节解码 | golden 测试显式使用 UTF-8 解码后交给 Jackson 比较，避免假失败 | 否 |

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
| Workspace 模板文件归属 | Spec 只要求幂等初始化，未定义模板版本升级时如何处理已有文件 | 清单区分 `managed`/`seed`，状态文件记录上次模板摘要；只升级未被用户修改的 managed 文件 | 同时满足模板可升级与运行数据不可覆盖 |
| 图片工具名称 | Python 工具名 `Add image to content Local` 含空格 | Java AgentScope 工具名改为 `add_image_to_content_local`，参数 `image_url` 与行为保持兼容 | 满足模型函数名安全格式；避免后续模型 API 拒绝非法名称 |
| infra 调用邮件唤醒 | Task 9 工具需要调用 app 层 `WakeScheduler`，但 COLA 依赖禁止 infra → app | `CommonTeamTools.MailWakeScheduler` 窄接口由 starter 后续以方法引用注入 | 保持模块依赖方向，Task 16 装配时绑定 `WakeScheduler::scheduleMailWake` |
| Runner 异常返回 | Python worker 记录错误后吞掉异常 | Java 当前消息 Future 保留异常，串行队列内部吸收异常后继续 | 让 Cron `last_status` 可感知失败；外部消息仍尝试发送统一错误提示 |

## 代码质量备忘

- domain/app 不得暴露 AgentScope/Reactor/飞书/JDBC 类型。
- 不得把审计 JSONL 再次拼入 Harness prompt。
- 文档 HARD-GATE 已由用户在 2026-09-25 明确确认，可按 tasks 开始 Java 实现。
