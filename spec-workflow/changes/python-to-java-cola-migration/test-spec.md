# 测试 Spec — xiaopaw-team Python → Java（COLA + AgentScope）完整迁移

> status: apply  
> created: 2026-09-13  
> updated: 2026-09-30
> selected-strategy: 5A（单元 + 契约 + 集成 + 4 条真实 E2E）

## 0. 测试原则

- **等价需有证据**：不能只证明 Java 自己能运行，还要用 Python 基线 fixture 或相同场景证明数据与业务契约一致。
- **分层执行**：默认构建不访问真实模型、飞书或 pgvector；外部依赖放入显式 Maven profile。
- **失败可诊断**：保存关键日志、测试报告、场景产物路径和耗时；不得用无条件重试掩盖失败。
- **状态隔离**：每个测试使用独立临时 data/workspace/session，执行后不得污染源项目 workspace。
- **凭据安全**：测试报告与日志不得输出 API key、飞书 secret、DSN 密码。

## 1. 测试框架与分层

| 层级 | 工具 | 默认执行 | 说明 |
|---|---|---|---|
| 单元测试 | JUnit 5、AssertJ、Mockito | 是 | domain/app 纯规则与编排 |
| 架构测试 | ArchUnit/Maven Enforcer | 是 | COLA 依赖方向和框架隔离 |
| 文件/HTTP 契约 | JUnit 5、JSON fixtures、MockMvc | 是 | 与 Python 输出比较 |
| 无 LLM 集成测试 | Spring Boot Test、真实临时文件系统、Mock Agent/Feishu | 是 | wake、Runner、Session、workspace |
| pgvector 集成 | Testcontainers PostgreSQL + pgvector | 否，`pgvector-it` | 可选存储能力 |
| 真实 E2E | JUnit 5/Maven Failsafe + Qwen + AIO-Sandbox | 否，`e2e` | 4 条选择 5A 场景 |

覆盖率目标：domain 行覆盖率 ≥ 80%，app 行覆盖率 ≥ 70%；不能用覆盖率替代 P0 场景验收。

## 2. Python 基线与 Golden Fixtures

### 2.1 Fixture 来源

- 源目录：`/Users/qingling/workspace/IdeaProjects/kid0317/xiaopaw-team-main`
- fixture 生成脚本和源项目版本/文件摘要必须随 Java 测试记录。
- fixture 只覆盖稳定契约，不记录时间、UUID 等非确定值；必要时使用固定 clock/id generator。
- 禁止直接修改源项目或让 Java 测试依赖源项目运行目录。

### 2.2 必须建立的契约

| 契约 | Python 出处 | 比较内容 |
|---|---|---|
| 路由键 | `feishu/session_key.py` | p2p/group/thread/team 结果与非法输入 |
| 消息模型 | `models.py` | JSON 字段、空值、attachment、meta |
| Session 路由索引 | `session/manager.py` | active mapping、verbose、new session；审计不参与模型上下文 |
| 邮箱 | `tools/mailbox.py` | 字段、三态、超时恢复、并发写 |
| 事件 | `tools/event_log.py` | action、seq、append-only、非法 action |
| tasks/wake | `cron/tasks_store.py` | at/every/cron JSON、去重、heartbeat |
| checkpoint | `tools/feishu_bridge.py` | JSONL marker、pending/resolve、分类优先级 |
| self score | `tools/self_score.py` | 5 维权重、舍入、非法输入 |
| TestAPI | `api/schemas.py`、`api/test_server.py` | 字段默认值、响应、400/422、附件提示 |
| log query | `tools/log_query.py` | stats/tasks/steps/l1/all-agents JSON 输出 |

## 3. P0 单元与架构测试

### 3.1 Domain

| 组件 | 场景 | 预期 |
|---|---|---|
| `RoutingKeyResolver` | p2p/group/thread/team | 与 Python fixture 一致 |
| `MailboxService` | send → read → markDone | `unread → in_progress → done` |
| `MailboxService` | 非法状态/角色/type/id | 拒绝且不破坏文件 |
| `MailboxService` | reset stale | 超时 in_progress 恢复 unread |
| `WorkspacePolicy` | 角色 owner 权限 | needs/design/tech/code/qa 权限等价 |
| `WorkspacePolicy` | `..`、绝对路径、符号链接逃逸 | 全部拒绝 |
| `EventService` | 并发 append | seq 唯一递增，无破损行 |
| `HumanInputClassifier` | callback、显式 id、最新 pending、关键词、空文本 | 优先级和分类与 Python 一致 |
| `SelfScoreCalculator` | 5 维加权、边界、舍入 | 与 Python fixture 一致 |
| `CronJob` | at/every/cron 校验 | 缺必需字段时报错 |

### 3.2 App

| 组件 | 场景 | 预期 |
|---|---|---|
| `SessionRoutingService` | 首次消息 | 建立 routingKey → AgentScope sessionId |
| `SessionRoutingService` | `/new` | 切换新 sessionId；旧 id 不再被调用 |
| `SlashCommandService` | `/verbose`、`/help`、`/status` | 文本和状态行为等价 |
| `Runner` | 同 key 两条消息 | 严格串行 |
| `Runner` | 不同 key 两条消息 | 允许并行 |
| `Runner` | team role | 选择指定角色且不向人回复 |
| `Runner` | pending wake 去重 | 重复 heartbeat/new_mail 丢弃 |
| `Runner` | Agent 失败 | 记录错误、完成当前队列项、后续消息仍可执行 |
| `WakeScheduler` | 相同 role/project/reason | 复用未到期 wake |
| `CheckpointService` | register/resolve | resolve 幂等且写正确事件 |

### 3.3 架构

- domain 不得依赖 Spring、AgentScope、飞书 SDK、JDBC、adapter/app/infra/starter。
- app 只依赖 domain，不依赖具体 infra。
- adapter 不得依赖具体 infra 实现。
- AgentScope `@Tool` 只允许出现在 infra Agent 适配包。
- app/domain 公共接口不得暴露 Reactor `Mono/Flux`。

## 4. P0 基础设施与契约测试

| 组件 | 场景 | 预期 |
|---|---|---|
| `FileSessionRouteRepository` | 原子更新 | 临时文件成功替换，无半写 index |
| `JsonlConversationAuditRepository` | append | flush/fsync 后记录完整；不提供 Agent history loader |
| `JsonlCheckpointRepository` | 损坏行/resolve marker | 跳过损坏行，正确过滤已解决项 |
| `FileCronJobRepository` | mtime/size 变化 | 下一 tick 重新加载 |
| `WorkspaceTemplateInitializer` | 首次/重复/升级启动 | 首次初始化；重复不覆盖 MEMORY 和用户文件 |
| `RoleToolkitFactory` | manager/其他角色 | 团队工具分别为 8/5 个，参数 schema 正确 |
| `TeamAgentFactory` | 四角色构建 | workspace、model、toolkit、subagents 分角色正确 |
| `AgentScopeAgentGateway` | RuntimeContext | userId/sessionId 正确；审计历史未拼接；Mono 转 future |
| `McpSandboxConfiguration` | 注册成功/失败 | 状态可观测；失败给出脱敏诊断 |
| `FeishuEventConverter` | text/post/image/file/bot-added | InboundMessage 和事件正确 |
| `FeishuSenderGateway` | p2p/group/thread/loading/update | API 参数和重试行为正确 |
| `TestApiController` | 成功/400/422/超时/附件 | 与 Python HTTP contract 一致 |
| `LogQueryService` | 五个命令 | JSON 输出与 fixture 一致 |
| `CleanupService` | 各保留期、data root 防护 | 只清理命中且过期的数据 |

## 5. P0 无真实 LLM 集成测试

### IT-01：自动唤醒闭环

`send_mail(to=pm)` → mailbox 持久化 → `tasks.json` at job → Cron reload/fire → `team:pm` → Runner → Mock AgentGateway。

验收：1 秒调度窗口内只调用一次 PM；邮件状态可被 PM 读取；并发 heartbeat 不造成第二次调用。

### IT-02：AgentScope Session 映射

同 routing key 连续两轮应把相同 sessionId 传给 Harness；执行 `/new` 后必须传新 id。JSONL 审计存在，但 Agent 请求不得包含拼接历史。

### IT-03：workspace 与 Skills

从 classpath 模板初始化外部 workspace；四角色发现各自 reference skills；`code_impl/test_run` 映射到声明式 Sub-Agent；重复初始化不覆盖运行记忆。

### IT-04：附件与 Loading 卡片

飞书附件事件 → Downloader → session uploads → Agent 请求路径提示；先 `sendThinking`，成功后 `updateCard`，失败时降级普通回复。

### IT-05：Human checkpoint

Manager 发 checkpoint → 持久化 pending → 用户 revise/approve → 分类绑定正确 id → resolve/保留状态和事件均正确。

### IT-06：无飞书 TestAPI

Spring Boot `no-feishu` 模式启动；TestAPI 通过 CaptureSender 完成一轮请求并返回 sessionId；DELETE 后再次请求获得新 session。

## 6. 可选 pgvector 集成测试

- PostgreSQL 启用 `vector` extension，执行可选 DDL。
- 相同稳定 id 重复写入只保留一条。
- summary/message vector 维度为 1024。
- `memory.db_dsn` 为空不连接数据库。
- DB 不可用时主 Agent 回复仍成功，仅产生 warning/metric。

## 7. 真实 E2E（选择 5A）

### E2E-01：Happy path

对应源 `test_tc_f_001_happy_path.py`：需求澄清 → checkpoint approve → PM 产品设计 → RD 技术设计/代码 → QA 设计/执行 → 交付。

### E2E-02：Checkpoint revise

对应源 `test_tc_f_004_checkpoint_revise.py`：用户要求修改需求，旧 checkpoint 正确解决/记录，新版本需求再次确认后继续。

### E2E-03：QA defect → RD fix

对应源 `test_tc_f_003_qa_defect_rd_fix.py`：QA 报告缺陷，Manager/RD 修复后回归，最终交付无未解决缺陷。

### E2E-04：Code fail recovery

对应源 `test_tc_f_006_code_fail_recovery.py`：首次 pytest 失败，RD 根据 stderr 修复并在允许次数内通过；task_done 包含 attempts 和 self_score。

### 每条 E2E 共同断言

- 角色调用顺序、mailbox 状态和 events 动作完整。
- checkpoint 不串项目、不串 routing key。
- `needs/design/tech/code/qa` 产物齐全，Python 代码和 pytest 行为保持。
- AgentScope sessionId 稳定，`/new` 场景除外。
- MCP/AIO-Sandbox 实际被调用，工具名不存在旧 CrewAI 残留。
- 最终对人回复成功，敏感配置未出现在日志或产物。

### E2E 前置：零模型真实工具契约

- 使用 `tool-contract` profile 创建真实 RD/QA 声明式 Sub-Agent，但注入 `NoCallModel`，禁止调用百炼。
- 两个子代理都必须继承 `sandbox_execute_bash` 和 `run_project_tests`，且不能重新暴露 Harness 内置文件工具。
- 两个子代理都必须通过真实 Docker MCP 执行无副作用 `pwd && test -d /workspace/shared/projects/tool-contract/code`。
- 此契约失败时禁止进入昂贵 `e2e` profile；通过只证明工具注册、继承和目录挂载，不替代四条业务 E2E。
- RD/QA `send_mail(task_done)` 另由 Java 集成测试验证证据硬门禁；模型不能通过通用 `append_event` 伪造 `workflow_transitioned`。

### 当前执行状态（2026-10-01）

| 场景 | 状态 | 已验证 | 未完成/阻断 |
|---|---|---|---|
| `happy-path` | 最新 `qwen3-max` 干净验证失败 | 零模型真实工具契约先行通过；真实流程完成 checkpoint、PM、RD 技术方案并正确进入代码实现。RD 生成完整 Python 应用和测试，MCP 调用正常，pytest 真实暴露导入错误，完成硬门禁没有接受虚假成功；heartbeat 能恢复 RD 并发出澄清请求 | Manager 可领取 `clarification_request`，但协议不接受 `clarification_response`/`info`，直接 `task_assign` 又被状态机禁止；Manager 只写澄清文档并关闭请求，RD 没有收到继续当前阶段的确定性指令，流程停在 `CODE_IMPLEMENTATION`。诊断位于 `target/e2e-evidence/diagnostics/happy-path-20261001-2119-clarification-dead-end`；无 QA、交付、Failsafe 成功和 `summary.json` |
| `checkpoint-revise` | 未运行 | 驱动与断言已实现 | 等待 happy-path 通过 |
| `qa-defect-rd-fix` | 未运行 | 驱动与断言已实现 | 等待 happy-path 通过 |
| `code-fail-recovery` | 未运行 | 驱动与断言已实现 | 等待 happy-path 通过 |

不得把以下运行作为通过证据：模型额度/账户状态失败、网络重试耗尽、阶段超时、人工终止，或仅生成部分产物但没有 Failsafe 成功与场景 `summary.json`。

## 8. 明确不在默认单元测试中验证

| 项目 | 验证位置 |
|---|---|
| 飞书公网 WebSocket 长时间稳定性 | 预发布 soak/运维验证；单测只验证 SDK adapter 与重连策略 |
| Qwen 输出内容完全确定 | E2E 验证结构和业务不变量，不断言逐字文本 |
| PostgreSQL/pgvector | `pgvector-it` profile |
| AIO-Sandbox 真实命令执行 | `e2e` profile |
| AIO-Sandbox 工具注册、子代理继承与固定目录探针 | `tool-contract` profile（真实 Docker、零模型） |

## 9. 执行计划与命令

- [x] 固化 Python golden fixtures。
- [x] 实现 P0 单元与架构测试。
- [x] 实现契约测试和 IT-01～IT-06。
- [x] 运行默认 `mvn verify`（2026-09-29，155 tests）。
- [ ] 运行可选 pgvector profile。
- [ ] 运行四条真实 E2E 并保存证据。
- [x] 把当前未覆盖风险和失败证据同步到 `spec.md`/`log.md`（2026-09-29；四场景完成后仍需最终更新）。

```bash
cd /Users/qingling/workspace/IdeaProjects/QinglingWake/qingling-team
mvn verify
mvn -Pcontract-it verify
mvn -Ppgvector-it verify
mvn -Ptool-contract -Dtool.contract.workspace="$(pwd)/target/e2e-workspace" verify
mvn -Pe2e -De2e.scenario=happy-path verify
mvn -Pe2e -De2e.scenario=checkpoint-revise verify
mvn -Pe2e -De2e.scenario=qa-defect-rd-fix verify
mvn -Pe2e -De2e.scenario=code-fail-recovery verify
```

## 10. 测试证据（实施后填写）

| 时间 | 命令/场景 | 结果 | 报告/关键输出 |
|---|---|---|---|
| 2026-09-25 | `mvn -pl qingling-team-domain -am test` | 通过（4 tests） | 领域模型契约 + ArchUnit |
| 2026-09-25 | `mvn -pl qingling-team-app,qingling-team-infra -am test` | 通过（14 tests） | Session/Slash/原子索引/JSONL + AgentScope probes |
| 2026-09-25 | `mvn test` | 通过（全 6 模块，14 tests） | Reactor Summary 全部 SUCCESS |
| 2026-09-25 | `mvn -pl qingling-team-domain,qingling-team-infra -am test` | 通过（32 tests） | Task 5 Python golden、邮箱三态/并发/stale、Workspace 权限与逃逸防护、事件 seq/损坏行恢复 |
| 2026-09-25 | `mvn test` | 通过（全 6 模块，36 tests） | Task 1～5 全量回归；Reactor Summary 全部 SUCCESS |
| 2026-09-25 | `mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra -am test` | 通过（57 tests） | Task 6 Python golden、checkpoint 注册/查询/幂等 resolve/并发、输入分类优先级、五维 self-score 计算与提取 |
| 2026-09-25 | `mvn test` | 通过（全 6 模块，57 tests） | Task 1～6 全量回归；Reactor Summary 全部 SUCCESS |
| 2026-09-26 | `mvn -pl qingling-team-starter -am test` | 通过（全 6 模块，140 tests） | Task 16 无飞书启动、缺失模型密钥脱敏 fail-fast、配置映射、四角色 heartbeat/Cron、Runner 排空与运行时关闭；Reactor Summary 全部 SUCCESS，未调用真实模型 |
| 2026-09-26 | `mvn -Pcontract-it verify` | 通过（140 unit + 10 contract/IT） | Python fixture 含 11 个源文件 SHA-256；路由、Session、邮箱、事件、tasks、checkpoint、self_score、TestAPI、workspace 权限、自动唤醒、四角色 Skill 发现及 IT-01～IT-06 全部通过；无真实 LLM、飞书或网络调用 |
| 2026-09-26 | `mvn -pl qingling-team-starter -am test` | 通过（全 6 模块，141 tests） | Task 18 增加 AgentScope 工具调用 Hook，只采集工具名；E2E 将断言 `sandbox_execute_bash` 真实被调用，参数和结果不进入证据 |
| 2026-09-26 | `mvn -Pe2e -De2e.scenario=happy-path verify` | 前置配置阻塞（141 unit 通过，真实 E2E 1 failure、0 skip） | E2E Harness 已编译执行并明确报告缺少 `DASHSCOPE_API_KEY`；Docker daemon 29.3.1 可用，GHCR 已下载多数层但 blob 下载因 EOF 中断；未伪造或跳过真实场景结果 |
| 2026-09-26 | `docker compose -f sandbox-docker-compose.yaml config` | 通过 | GHCR 多次 blob EOF 后切换 AIO-Sandbox 官方中国大陆镜像 `enterprise-public-cn-beijing.cr.volces.com/vefaas-public/all-in-one-sandbox:1.11.0`；固定版本并启用官方要求的容器安全与 shared memory 参数 |
| 2026-09-26 | `mvn -Pe2e -De2e.scenario=happy-path verify` | 环境装配失败（141 unit 通过，E2E 1 error、0 skip） | Key 和沙盒健康检查已通过；真实 E2E 在清理 `/workspace/.cron` 嵌套挂载点时触发 `AccessDeniedException`，未调用模型；已移除冲突挂载并准备重跑 |
| 2026-09-28 | `mvn verify` | 通过（154 tests） | Cron 并发 wake、routing key、任务分派幂等、模型选择、确定性评审、SOP 阶段顺序、Workspace 模板契约及完整默认回归通过；未运行真实 E2E profile |
| 2026-09-28 | `mvn -Pe2e -De2e.scenario=happy-path verify` | 失败，不能计为通过 | 真实流程推进至技术方案两轮评审、RD 代码实现；百炼连接反复 EOF/SSL 握手失败导致 `file:code/main.py` 等待 30 分钟超时；RD 后续还以字符串传入 sandbox timeout，被 MCP schema 拒绝。未生成成功 `summary.json` |
| 2026-09-28 | `WorkspaceTemplateContractTest` + `mvn verify` | 通过（10/10；完整 154 tests） | RD/QA 模板明确 `sandbox_execute_bash` 的 timeout 必须省略或传 JSON 整数，禁止字符串；`git diff --check` 通过 |
| 2026-09-29 | 第二次干净 `happy-path` | 进行中，尚未通过 | 约 6 分钟内完成 checkpoint、PM、RD 技术设计并生成 `code/main.py`；RD 代码任务唯一，QA 测试设计完成；随后百炼返回 `Arrearage`，当前只有 `model-selection.json`，无成功 `summary.json` |
| 2026-09-29 | `qwen3.7-plus` 干净 `happy-path` | 用户主动暂停，不能计为通过 | `model-selection.json` 显示显式 `qwen3.7-plus`、免费选择关闭、无动态探测；完成 checkpoint、PM、RD 技术方案、PM/QA 评审、RD 修订，并真实验证修订后分派“代码实现 (第 1 轮)”而非跳到 QA；同义任务主题仅保留一条开放任务；已生成 `code/app.py`。暂停时终止 screen 及残留 Maven/Java 子进程，无成功 `summary.json`，下次须清理场景目录后重跑 |
| 2026-09-30 | `qwen-coder-plus` 多次干净 `happy-path` | 用户终止本轮测试，不能计为通过 | 首轮未创建项目/需求即发 checkpoint；加固 Manager 需求与批准回复模板后，定向 Workspace 模板契约 13/13 通过。后续运行曾把人工批准误当邮箱消息；最新运行已创建项目和完整五节需求，但先发 checkpoint、后记录 `requirements_drafted`，并再次发送同一 `requirements_review` checkpoint，未进入 PM且无 `summary.json`。三轮诊断分别保存在 `target/e2e-diagnostics/happy-path-qwen-coder-plus-*`；E2E 进程、Docker 沙盒和活动目录已清理，待选择新模型后重跑 |
| 2026-09-30 | `qwen3-max` 干净 `happy-path` 第 1 次 | 失败，不能计为通过 | 显式模型和免费选择开关正确；真实流程完成单一需求 checkpoint、PM、技术方案评审/修订并正确进入 RD 代码实现。RD 修复生成测试时调用 AgentScope 内置 `write_file`，工具长时间无结果并被 pending-tool recovery 标记中断，业务流未恢复；诊断保存于 `target/e2e-diagnostics/happy-path-qwen3max-attempt1-write-file-stall-20260930`，无 `summary.json` |
| 2026-10-01 | MCP 工具白名单定向回归 | 通过（19 tests）但方案被真实 E2E 否定 | `McpSandboxConfigurationTest` 4/4、`RuntimeBootstrapTest` 2/2、`WorkspaceTemplateContractTest` 13/13 通过；随后真实运行证明只配置 `enabled-tools: [sandbox_execute_bash]` 不能同时满足子代理 MCP 继承，也不能移除 Harness 内置文件工具，因此临时配置已撤回 |
| 2026-10-01 | `qwen3-max` 干净 `happy-path` 第 2 次 | 失败并按两次上限终止 | 真实流程完成需求 checkpoint、PM 产品设计和 RD 技术方案，heartbeat 成功恢复 PM/RD 漏发的完成邮件；进入代码实现后 RD 子代理的 `sandbox_execute_bash` 返回 `Tool not found`，虽然生成代码文件但未运行 pytest，仍发送“测试未执行”的 `task_done`，违反共同断言。诊断保存于 `target/e2e-diagnostics/happy-path-qwen3max-attempt2-sandbox-tool-unavailable-20260930`；无 `summary.json`，未启动第 3 次，测试环境已清理 |
| 2026-10-01 | `TeamAgentFactoryTest,McpSandboxConfigurationTest` + `mvn verify` | 通过（定向 8 tests；完整 161 tests） | 父 Agent 与声明式 RD Sub-Agent 均不暴露 Harness 文件工具；RD Sub-Agent 继承 `sandbox_execute_bash`；MCP 注册失败、白名单工具缺失均启动即脱敏失败。完整默认回归六模块全部成功，未运行真实 E2E |
| 2026-10-01 | 工作流/门禁/包装器/模板定向回归 + `mvn verify` | 通过（完整 175 tests） | Java 状态机固定 checkpoint、PM、RD、QA、交付迁移并校验阶段 Owner；通用事件入口拒绝伪造 `workflow_transitioned`；RD/QA `send_mail(task_done)` 硬门禁、`run_project_tests` 结构化证据和 Workspace 模板契约通过。未调用真实模型 |
| 2026-10-01 | `mvn -Ptool-contract -Dtool.contract.workspace=... verify` | 通过（175 unit + 1 real tool contract） | Docker AIO-Sandbox `1.11.0` healthy、127.0.0.1:8029 可达；真实 RD/QA 声明式子代理工具矩阵正确，并分别执行无副作用 `pwd/test -d`。使用 `NoCallModel`，未调用百炼、未消耗模型 Token |
| 2026-10-01 | `RunnerTest` + `mvn verify` | 通过（Runner 6/6；完整 176 tests） | `new_mail` wake 在进入 Agent 前确定性注入真实 project_id、首步 `read_inbox` 和结束 `mark_done`；heartbeat 与普通消息保持原语义。未调用真实模型 |
| 2026-10-01 | 修复后 `qwen3-max` 干净 `happy-path` 第 1 次 | 失败，不能计为通过 | checkpoint 和 PM 已完成；RD 收到不透明 wake 后把 `qingling-team-starter` 当成项目标识，没有领取 `todo-mvp` 邮件。诊断保存于 `target/e2e-evidence/diagnostics/happy-path-20261001-2011`，无成功 `summary.json` |
| 2026-10-01 | 修复后 `qwen3-max` 干净 `happy-path` 第 2 次 | 失败并达到本轮两次上限 | 确定性 wake 生效，PM、产品评审、技术方案和 `TECH_DESIGN → CODE_IMPLEMENTATION` 均完成；RD 生成项目后真实 pytest 在收集阶段失败，硬门禁拒绝虚假完成，随后 Agent 调用链停留 pending。诊断保存于 `target/e2e-evidence/diagnostics/happy-path-20261001-2030`，无成功 `summary.json` |
| 2026-10-01 | `McpSandboxConfigurationTest,TeamAgentFactoryTest` | 通过（9/9） | MCP Mono 已完成且有输出但标记 `RUNNING` 时归一化为 `SUCCESS`；真正空输出 pending 保持不变；工具继承、禁用 Harness 文件工具和注册失败脱敏回归通过 |
| 2026-10-01 | 重启前零模型真实工具契约 | 失败，不能计为通过 | Docker health 与 8029 TCP 均正常，但 `sandbox_execute_bash` 60 秒内没有 MCP item/terminal 信号，证明容器健康检查不能代表 MCP 工具可用 |
| 2026-10-01 | 重启 AIO-Sandbox 后 `mvn -Ptool-contract -Dtool.contract.workspace=... verify` | 通过（177 unit + 1 real tool contract） | RD/QA 真实 Docker MCP 无副作用目录探针严格返回 `SUCCESS`；使用 `NoCallModel`，未调用百炼。`qa-defect-rd-fix` 驱动同步断言 Java 状态机的 Manager→RD `DEFECT_FIX` 分派 |
| 2026-10-01 | 最终默认 `mvn verify` | 通过（177 tests） | 六模块全部 SUCCESS；覆盖确定性 wake、MCP 完成态归一化、Java 工作流状态机、完成证据门禁及更新后的 `qa-defect-rd-fix` 驱动断言。默认 profile 未调用真实模型 |
| 2026-10-01 | 零模型工具契约 + 最新 `qwen3-max` 干净 `happy-path` | 工具契约通过；真实 E2E 失败，不能计为通过 | `177 unit + 1 real tool contract` 先行通过。真实流程推进至 RD 代码阶段，pytest 因 `main.py` 相对导入在顶层加载时报错；RD heartbeat 恢复并发送澄清请求，但 Manager 没有可用的澄清响应协议把指导重新交给 RD。运行已终止且无遗留 E2E 进程；诊断保存于 `target/e2e-evidence/diagnostics/happy-path-20261001-2119-clarification-dead-end` |
| 2026-10-02 | `WorkflowStateMachineTest,DeterministicWorkflowToolsTest,WorkspaceTemplateContractTest` + `mvn verify` | 通过（定向 27 tests；默认 182 tests） | 新增 `clarification_answered` 正式协议：Java 保持当前阶段、固定原 Owner、发送唯一的带恢复标识和澄清正文的 `task_assign` 并安排 wake；重复提交复用开放任务。覆盖空答复、错误 Owner、直发 `clarification_answer` 拒绝，以及 PM/RD/QA 模板恢复契约。本次未运行真实 E2E 或调用模型 |
