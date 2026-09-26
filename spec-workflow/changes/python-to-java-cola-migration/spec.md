# xiaopaw-team Python → Java（COLA + AgentScope）完整迁移

> status: apply  
> created: 2026-09-13  
> updated: 2026-09-25  
> complexity: 🔴复杂  
> change-dir: `spec-workflow/changes/python-to-java-cola-migration/`

## 1. 背景与目标

将 Python 多 Agent 团队项目 `/Users/qingling/workspace/IdeaProjects/kid0317/xiaopaw-team-main` 完整迁移为 Java 项目，目标目录为 `/Users/qingling/workspace/IdeaProjects/QinglingWake/qingling-team/`。新项目采用 Maven 多模块与 COLA 风格分层，项目名为 `qingling-team`，基础包名为 `cn.org.chris.wake`，使用 AgentScope Java 替代 CrewAI。

本次迁移的“完整”含义是：源项目的每个运行模块都必须在迁移矩阵中被标记为“Java 实现”“AgentScope 原生替代”“保留外部组件”或“明确删除”；不得因为语言或框架变化而静默遗漏能力。

### 1.1 可验证结果

- [ ] `qingling-team` Maven 聚合工程可在 JDK 17、Maven 3.8+ 下完整编译和测试。
- [ ] COLA 五模块 `starter / adapter / app / domain / infra` 依赖方向满足第 7 节约束。
- [ ] Manager、PM、RD、QA 四个角色通过 AgentScope Java `2.0.3` 的 `HarnessAgent` 运行。
- [ ] 飞书、Runner、Session 路由、Cron、邮箱协作、Human checkpoint、Skills、MCP 沙盒、记忆、清理、可观测性与 TestAPI 完成等价迁移。
- [ ] 原 Skills 的业务目标保持不变；其中 RD/QA 仍可通过 AIO-Sandbox 生成和测试 Python 应用产物。
- [ ] 单元测试、契约测试、无真实 LLM 集成测试及 4 条真实 E2E 全部通过并保留证据。

### 1.2 非目标

- 不把原 Skill 生成的 Python 应用强制改成 Java 应用；迁移对象是多 Agent 运行平台本身。
- 不继续使用 CrewAI 运行时。
- 不在 Spec 获得用户 HARD-GATE 确认前创建 Java 业务代码。

## 2. 代码现状（Research Findings）

### 2.1 入口与核心链路

| 链路 | 代码出处 | 当前行为 |
|---|---|---|
| 进程启动 | `xiaopaw_team/main.py`：`build_runtime()`、`async_main()` | 加载配置，构造 Session、四角色 Agent、Runner、Cron 和飞书监听 |
| 消息执行 | `xiaopaw_team/runner.py`：`Runner.dispatch()`、`Runner._handle()` | `routing_key` 串行队列、Slash Command、角色选择、历史与回复持久化 |
| 团队自驱动 | `xiaopaw_team/tools/team_tools.py`：`SendMailTool._run()`；`cron/tasks_store.py`：`schedule_wake()` | 发邮件后写入一次性 wake，Cron 触发 `team:{role}` |
| Agent 构建 | `xiaopaw_team/agents/build.py`：`build_team_agent_fn()` | 按角色加载提示、工具、SkillLoader、Sub-Crew 与记忆 |
| Human checkpoint | `xiaopaw_team/tools/feishu_bridge.py`：`CheckpointStore`、`classify()` | 持久化待确认项并识别 approve/reject/澄清/SOP 等输入 |

### 2.2 迁移矩阵

| 源能力 | Python 出处 | Java 处置 | 目标模块 |
|---|---|---|---|
| 核心消息与协议 | `models.py` | Java 实现：领域模型与 Gateway | domain |
| Session 路由索引 | `session/manager.py`、`session/models.py` | Java 实现：仅维护 `routingKey → AgentScope sessionId`、verbose、审计；模型上下文不重复注入 | app + infra |
| 模型会话、压缩、长期记忆 | `memory/context_mgmt.py`、部分 `memory/bootstrap.py` | AgentScope Harness 原生替代 | infra/agentscope |
| 角色提示注入 | `memory/bootstrap.py`、`workspace/*/{soul,agent,user,memory}.md` | 转换为外部可写 AgentScope workspace：`AGENTS.md`、`MEMORY.md`、additional context | infra + starter resources |
| 四角色 Crew | `agents/build.py` | `HarnessAgent` 四实例 + `RuntimeContext(userId, sessionId)` | infra/agentscope |
| Task Sub-Crew | `agents/skill_crew.py` | AgentScope `SubagentDeclaration`；task skill 映射为子 Agent，reference skill 映射为原生 Skill | infra/agentscope |
| 8 个团队工具 | `tools/team_tools.py` | AgentScope `@Tool` 适配，调用 domain Gateway/app use case | infra/agentscope |
| SkillLoader | `tools/skill_loader.py`、`_skill_loader_base.py` | AgentScope workspace Skill 扫描替代；`load_skills.yaml` 仅作迁移清单，不作运行时真相源 | infra/agentscope |
| IntermediateTool | `tools/intermediate_tool.py` | Java Tool 实现，保存中间产物 | infra/agentscope |
| 图片工具 | `tools/add_image_tool_local.py` | Java Tool/AgentScope 多模态适配，保留路径穿越与大小限制 | infra/agentscope |
| 百度搜索工具 | `tools/baidu_search_tool.py` | Java HTTP Tool 实现，凭据外置 | infra/agentscope |
| 邮箱三态机 | `tools/mailbox.py` | Java 领域规则 + 文件 Repository | domain + infra |
| 工作区读写 | `tools/workspace.py` | Java 领域权限 + 文件 Repository | domain + infra |
| 事件日志 | `tools/event_log.py` | append-only JSONL Repository | domain + infra |
| Checkpoint/分类 | `tools/feishu_bridge.py` | Java 实现并接入飞书入站链路 | app + infra |
| 自评 | `tools/self_score.py` | Java 计算/解析实现并接入 task_done | domain + app |
| 日志查询 | `tools/log_query.py` | Java CLI/Service，实现原子命令等价能力 | app + infra |
| Runner | `runner.py`、`_runner_base.py` | Java 实现：串行、wake 去重、附件、Loading 卡片、Agent 调用 | app |
| Cron 与 tasks store | `cron/*` | Java 调度服务 + 文件 Repository，保留 at/every/cron、mtime、wake/heartbeat | app + infra |
| 飞书监听/发送/下载 | `feishu/*` | `oapi-sdk-java` 实现；保留白名单、入群事件、附件、卡片更新和重试 | adapter + infra |
| LLM | `llm/aliyun_llm.py` | AgentScope `DashScopeChatModel` 替代；统一环境变量映射 | infra/agentscope |
| pgvector | `memory/indexer.py` | Java JDBC + pgvector 实现，配置为空时静默跳过 | infra |
| Cleanup | `cleanup/service.py` | Java 实现启动清理、定时清理、工作区初始化和凭据文件权限 | app + infra |
| Metrics/日志 | `observability/*` | Micrometer Prometheus + Logback JSON | infra + starter |
| TestAPI/CaptureSender | `api/*` | Spring MVC + CaptureSender | adapter |
| AIO-Sandbox | `sandbox-docker-compose.yaml` | 保留外部 Python/Docker 组件；Java 通过 AgentScope MCP 接入 | 外部组件 + infra |
| workspace Skills | `workspace/**` | 内容转换后迁移；业务目标与 Python 产物约束保持 | starter template + 外部工作区 |

### 2.3 主要迁移风险

1. AgentScope Harness 已提供 Session、Memory、Compaction 和 Workspace，不能再用自研上下文管理重复注入历史。
2. AgentScope 基于 Reactor；应用层统一暴露 `CompletableFuture`，仅在 infra Agent 适配器内使用 `Mono.toFuture()` 转换，禁止业务线程中无界 `block()`。
3. 原 `reference/task` Skill 与 CrewAI Sub-Crew 语义不同，必须转换而非原样复制。
4. classpath resources 不可作为长期可写 workspace，启动时必须初始化到外部目录。
5. MCP、飞书、pgvector 和真实 LLM 都需要隔离的契约/集成测试。

## 3. 功能点

- [ ] 功能 1：Maven 多模块 COLA 项目骨架和依赖边界。
- [ ] 功能 2：核心模型、Gateway、邮箱、工作区权限、事件日志和 checkpoint 领域规则。
- [ ] 功能 3：AgentScope `2.0.3` 四角色 HarnessAgent、Tools、Skills、Sub-Agent 与 MCP。
- [ ] 功能 4：AgentScope 负责模型会话/记忆；路由 Session 负责 `/new`、verbose、审计映射。
- [ ] 功能 5：Runner 串行队列、wake 去重、Slash Command、附件、Loading 卡片和角色路由。
- [ ] 功能 6：Cron at/every/cron、mtime 热重载、邮件 wake、四角色错峰 heartbeat。
- [ ] 功能 7：飞书 WebSocket、群白名单、Bot 入群、消息/卡片、附件下载与重试。
- [ ] 功能 8：Human checkpoint 注册、分类、解决和事件记录。
- [ ] 功能 9：pgvector 异步索引、self_score、log_query、Cleanup 与辅助工具。
- [ ] 功能 10：TestAPI、CaptureSender、结构化日志和 Prometheus Metrics。
- [ ] 功能 11：转换后的 workspace/Skills 在外部可写目录运行，原 Python 交付型 Skill 行为保持。
- [ ] 功能 12：契约、集成及四条真实 E2E 验收通过。

## 4. 业务规则

### 4.1 路由与 Session

- 路由键保持：`p2p:{open_id}`、`group:{chat_id}`、`thread:{chat_id}:{thread_id}`、`team:{role}`。
- 外部消息固定进入 Manager；`team:{role}` 进入指定角色。
- 同一 `routing_key` 串行，不同 key 可并行；wake 消息按 `new_mail/heartbeat` 去重。
- `routingKey → AgentScope sessionId` 是唯一模型会话映射；`/new` 创建新 id，后续通过 `RuntimeContext` 传入 HarnessAgent。
- 路由索引和 JSONL 仅用于路由元数据与审计，不再作为模型历史输入；AgentScope state 是模型上下文唯一事实来源。
- `/verbose`、`/help`、`/status` 行为保持；删除测试 session 时同时清理路由映射和 AgentScope state。

### 4.2 团队协作

- 邮箱状态保持 `unread → in_progress → done`，超时可恢复为 unread。
- `send_mail` 成功后注册 `now+1s` 一次性 wake；同角色、项目和原因的未到期 wake 去重。
- 四角色 heartbeat 默认 30 秒，首次错峰 `0/7/14/21` 秒。
- Human checkpoint 必须持久化，approve/reject/revise 必须绑定正确 checkpoint 并写事件日志。

### 4.3 文件与工作区

- JSON 元数据采用 write-to-temp + atomic move；append-only JSONL 写入后 flush，关键日志按原语义 fsync。
- 所有相对路径必须限制在工作区根目录，拒绝 `..`、符号链接逃逸和越权目录写入。
- `resources/workspace-template` 只读；启动时初始化到配置项 `workspace.root`，已有用户数据不得被覆盖。
- `soul.md + agent.md + user.md + team_protocol.md` 转换为角色 `AGENTS.md`/additional context；`memory.md` 作为初始记忆种子，不覆盖运行中的 `MEMORY.md`。
- `reference` Skill 转为 AgentScope Skill；`task` Skill 转为 Skill + SubagentDeclaration；旧工具名必须替换为 AgentScope/MCP 实际工具名。
- RD/QA Skill 在 AIO-Sandbox 中生成 Python 代码、安装依赖和运行 pytest 的业务行为保持不变。

## 5. 数据变更

### 5.1 文件数据

| 数据 | 目标 | 兼容要求 |
|---|---|---|
| Session 路由索引 | `data/sessions/index.json` | 保留 routing key、active session、verbose；增加 AgentScope sessionId 映射 |
| 会话审计 | `data/sessions/*.jsonl` | 只写审计，不重复回灌 AgentScope |
| AgentScope state/memory | `workspace.root` 下 Harness 标准目录 | 由 AgentScope `2.0.3` 管理 |
| 邮箱/事件/tasks/checkpoint | `workspace/shared/**`、`data/cron/tasks.json` | JSON/JSONL 字段与源项目契约测试一致 |

### 5.2 可选 pgvector

| 操作 | 对象 | 要求 |
|---|---|---|
| 前置 | PostgreSQL extension `vector` | `CREATE EXTENSION IF NOT EXISTS vector`，由部署方执行 |
| 前置/复用 | `memories` 表 | 包含源实现使用的 id、session_id、routing_key、消息、summary、tags、turn_ts、两个 1024 维向量和 search_text |
| 索引 | 主键/唯一键、vector/GIN 索引 | DDL 单独提供，应用不在生产环境自动破坏性建表 |

`memory.db_dsn` 为空时静默跳过；连接或索引失败只记录告警，不阻断主回复。

## 6. 接口与配置

| 操作 | 接口 | 方法 | 兼容要求 |
|---|---|---|---|
| 等价迁移 | `/api/test/message` | POST | 请求/响应字段、默认值、400/422/超时语义与 Python 版一致 |
| 等价迁移 | `/api/test/sessions` | DELETE | 清理路由映射、审计测试数据和 AgentScope 测试 state |
| 等价迁移 | `/metrics` | GET | Prometheus 格式 |
| 等价迁移 | 飞书 WS/REST | SDK | 消息、Bot 入群、白名单、回复线程、卡片更新、附件下载 |

配置必须覆盖 `workspace`、`feishu`、`agent`、`sandbox`、`memory`、`data_dir`、`team`、debug/TestAPI、metrics、cleanup；密钥仅来自环境变量或权限为 `0600` 的运行时凭据文件。模型配置优先读取 AgentScope 约定的 `DASHSCOPE_API_KEY`，兼容回退读取原项目的 `QWEN_API_KEY`，并显式传给 `DashScopeChatModel` builder；日志不得输出密钥。

## 7. COLA 模块与依赖方向

| 模块 | 职责 | 允许依赖 |
|---|---|---|
| `qingling-team-domain` | 纯模型、规则、Repository/Gateway 接口 | JDK 与轻量基础库；不得依赖 Spring、AgentScope、飞书 SDK、JDBC |
| `qingling-team-app` | Runner、用例编排、Session 路由、Cron/Cleanup 协调 | domain |
| `qingling-team-infra` | AgentScope、文件 Repository、pgvector、飞书发送/下载、MCP、外部 HTTP | domain；实现 domain Gateway |
| `qingling-team-adapter` | 飞书入站、TestAPI、DTO/事件转换 | app + domain；不得直接依赖具体 infra 实现 |
| `qingling-team-starter` | Spring Boot 启动、配置、Bean 装配、资源初始化 | adapter + app + infra |

禁止 `domain → app/infra/adapter/starter`、`app → infra`、`adapter → infra` 的编译依赖。AgentScope `@Tool` 类属于 infra，而不是 domain。

## 8. 风险与关注点

- ⚠️ AgentScope 固定 `2.0.3`；升级必须单独验证 API、state 格式、Skill、Sub-Agent 和 MCP。
- ⚠️ Harness state 与审计 JSONL 不是双主；任何实现不得把审计历史再次拼入模型输入。
- ⚠️ 外部 workspace 初始化必须幂等，升级模板不能覆盖运行记忆和用户修改。
- ⚠️ AIO-Sandbox 仍是外部 Python/Docker 组件，部署清单必须保留它。
- ⚠️ 飞书、模型、MCP 和 pgvector 的网络故障必须可降级、可观测，并避免泄漏凭据。
- ⚠️ 完整迁移范围较大，按 tasks 顺序逐步验证，禁止一次性大提交。

## 8.5 测试策略

- **单元测试**：domain ≥ 80%，app ≥ 70%；文件并发、路径安全、分类和调度为 P0。
- **契约测试**：以 Python 产出的固定 fixtures 验证 JSON、路由、API、事件、邮箱、tasks 和 checkpoint 兼容性。
- **集成测试**：真实文件系统，Mock LLM/飞书；验证自动唤醒、AgentScope session 映射、workspace 初始化和 MCP 注册。
- **真实 E2E**：happy path、checkpoint revise、QA defect→RD fix、code fail recovery 四条。
- **独立 Test Spec**：是，见 `test-spec.md`。

## 9. 已确认选择与待澄清

### 9.1 已确认

- [x] 1A：AgentScope Java 固定 `2.0.3`；Harness 内部 Reactor，应用边界使用 `CompletableFuture`。
- [x] 2A：Harness 管理模型会话/记忆；自研 Session 仅管理路由、命令和审计。
- [x] 3A：运行平台 Java 化，Skills 转换为 AgentScope 语义，原 Python 交付型 Skill 行为保持。
- [x] 4A：按迁移矩阵完整覆盖源项目能力。
- [x] 5A：单元 + 契约 + 集成 + 四条真实 E2E。
- [x] Java 17、Spring Boot 3.2.x、Maven 多模块、可选 pgvector。

### 9.2 待澄清

- 无阻断问题。本文与 `tasks.md` 修订完成后仍需用户填写第 13 节 HARD-GATE，才可编码。

## 10. 技术决策

| 决策 | 选择 | 原因 |
|---|---|---|
| Agent 框架 | AgentScope Java `2.0.3` `HarnessAgent` | 用户选择 1A；固定可重复版本并使用完整 Workspace/Skill/Sub-Agent 能力 |
| 会话真相源 | Harness AgentState/Memory | 用户选择 2A；避免两套上下文重复注入 |
| Session 路由 | 自研 routing index + audit | 保留 `/new`、verbose、路由兼容和审计，但不拥有模型上下文 |
| Skills | 转换为 AgentScope workspace 语义 | 用户选择 3A；业务行为等价，运行机制适配 |
| 迁移范围 | 全量迁移矩阵 | 用户选择 4A；所有源模块都有明确去向 |
| 测试 | 单元 + 契约 + 集成 + 4 E2E | 用户选择 5A；在成本和跨语言信心之间平衡 |
| 架构 | COLA 五模块 + 依赖倒置 | domain/app 不依赖具体框架和基础设施 |
| Java/Spring | Java 17 + Spring Boot 3.2.x | 与现有环境兼容；不使用 Virtual Threads |
| 异步边界 | App `CompletableFuture`，AgentScope adapter `Mono.toFuture()` | 隔离 Reactor 框架类型 |
| 文件并发 | NIO FileLock + temp/atomic move + flush/fsync | 对齐 Python 文件存储语义 |
| 外部沙盒 | 保留 AIO-Sandbox，通过 AgentScope MCP 接入 | 维持原 Skill 的 Python 代码执行能力 |

## 11. 执行日志

| Task | 状态 | 实际改动文件 | 备注 |
|---|---|---|---|
| Spec P0 决策修订 | 已完成 | `spec.md`、`tasks.md`、`test-spec.md`、`log.md` | 仅文档，不代表允许编码 |
| Task 1 Maven 多模块骨架 | 已完成 | `qingling-team/pom.xml`、五个模块 POM | Java 17、Spring Boot 3.2.12、AgentScope 2.0.3；空骨架编译通过 |
| Task 2 AgentScope 编译探针 | 已完成 | infra 下两个 probe test | 4 项测试通过，未调用真实模型或 MCP |
| Task 3 领域模型与 Gateway | 已完成 | domain model/gateway 与架构测试 | domain 生产代码无 Spring/AgentScope/Reactor/JDBC 依赖 |
| Task 4 Session 路由与审计 | 已完成 | app session 服务、infra 文件 Repository | routing index 保留 Python JSON 结构；审计端口只写且不会回灌模型 |
| Task 5 邮箱、共享工作区与事件 | 已完成 | domain 服务、infra 文件 Repository | 三态并发、权限与路径逃逸、事件 seq 和 Python golden 契约通过 |
| Task 6 Human checkpoint、分类与自评 | 已完成 | domain 分类/自评、app checkpoint、infra JSONL | 分类优先级、幂等 resolve 与五维评分契约通过 |
| Task 7 Cron、wake、heartbeat 与 Cleanup | 已完成 | app 调度/清理、infra 文件实现 | 热重载、去重、错峰和 data root 防护通过 |
| Task 8 workspace 模板转换 | 已完成 | starter workspace-template、infra 初始化器 | 35 个源 Skill 全量映射；managed/seed 幂等升级通过 |
| Task 9 AgentScope Tools 与 Toolkit | 已完成 | infra agentscope/tool | Manager 8 件、其他角色 5 件团队工具；辅助工具最小暴露 |
| Task 10 HarnessAgent、Sub-Agent 与 MCP | 已完成 | infra agentscope 四个核心类及测试 | 四角色无真实模型/MCP smoke、RuntimeContext、Mono→Future 和脱敏 MCP 状态通过；共 76 项测试 |
| Task 11 Runner 完整执行链 | 已完成 | app runner 四个核心组件、RunnerMetrics 与 session 审计扩展 | 同 key 串行、跨 key 并行、team 零外发、附件、Loading/降级、失败恢复通过；共 56 项测试 |
| Task 12 飞书 Listener、Sender 与 Downloader | 已完成 | adapter feishu、infra feishu、domain 附件下载端口 | oapi-sdk-java 2.7.3 Channel/OpenAPI；text/post/image/file 转换、p2p/群白名单/Bot 入群、thread、Loading/PATCH、1/2/4 秒重试、下载与脱敏通过；对应命令共 109 项测试 |
| Task 13 pgvector 记忆索引 | 已完成 | infra memory、可选 DDL、AgentScope Gateway 索引旁路、Maven pgvector-it profile | 摘要/标签、两份 1024 维向量、稳定 ID、幂等写入、空 DSN 跳过及失败降级通过；默认命令共 92 项测试，完整 verify 成功，Docker 不可用时 IT 明确跳过 |

## 12. 审查结论

- 2026-09-25：原 Spec 不满足完整跨语言迁移，存在五项 P0。
- 2026-09-25：用户选择 `1A / 2A / 3A / 4A / 5A`，本次修订将五项 P0 转化为明确范围、职责和验收项。
- 当前结论：**用户已确认，可按 `tasks.md` 进入实现阶段。**

## 13. 确认记录（HARD-GATE）

- **确认时间**：2026-09-25
- **确认人**：用户
- **确认说明**：已阅读修订后的 spec、tasks 与 test-spec，同意进入实现 / 测试；用户原文：“需求确认、开始按照任务执行编码”。
