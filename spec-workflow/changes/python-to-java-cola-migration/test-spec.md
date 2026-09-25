# 测试 Spec — xiaopaw-team Python → Java（COLA + AgentScope）完整迁移

> status: apply  
> created: 2026-09-13  
> updated: 2026-09-25  
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

## 8. 明确不在默认单元测试中验证

| 项目 | 验证位置 |
|---|---|
| 飞书公网 WebSocket 长时间稳定性 | 预发布 soak/运维验证；单测只验证 SDK adapter 与重连策略 |
| Qwen 输出内容完全确定 | E2E 验证结构和业务不变量，不断言逐字文本 |
| PostgreSQL/pgvector | `pgvector-it` profile |
| AIO-Sandbox 真实命令执行 | `e2e` profile |

## 9. 执行计划与命令

- [ ] 固化 Python golden fixtures。
- [ ] 实现 P0 单元与架构测试。
- [ ] 实现契约测试和 IT-01～IT-06。
- [ ] 运行默认 `mvn verify`。
- [ ] 运行可选 pgvector profile。
- [ ] 运行四条真实 E2E 并保存证据。
- [ ] 把未覆盖风险和失败证据同步到 `spec.md`/`log.md`。

```bash
cd /Users/qingling/workspace/IdeaProjects/QinglingWake/qingling-team
mvn verify
mvn -Pcontract-it verify
mvn -Ppgvector-it verify
mvn -Pe2e -De2e.scenario=happy-path verify
mvn -Pe2e -De2e.scenario=checkpoint-revise verify
mvn -Pe2e -De2e.scenario=qa-defect-rd-fix verify
mvn -Pe2e -De2e.scenario=code-fail-recovery verify
```

## 10. 测试证据（实施后填写）

| 时间 | 命令/场景 | 结果 | 报告/关键输出 |
|---|---|---|---|
| — | — | — | — |
| 2026-09-25 | `mvn -pl qingling-team-domain -am test` | 通过（4 tests） | 领域模型契约 + ArchUnit |
| 2026-09-25 | `mvn -pl qingling-team-app,qingling-team-infra -am test` | 通过（14 tests） | Session/Slash/原子索引/JSONL + AgentScope probes |
| 2026-09-25 | `mvn test` | 通过（全 6 模块，14 tests） | Reactor Summary 全部 SUCCESS |
