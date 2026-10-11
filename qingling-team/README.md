# Qingling Team

Qingling Team 是一个由 Java 驱动的多智能体软件交付团队。用户只与 Manager 沟通，Manager、PM、RD、QA 通过文件邮箱、事件流和定时唤醒协作，按固定阶段完成需求确认、产品设计、技术设计、代码实现、测试和交付。

本项目从 Python/CrewAI 项目 `xiaopaw-team` 迁移而来。运行平台已改为 Java 17、Spring Boot 3.2.12、Maven 多模块和 AgentScope Java **2.0.3**；生成软件的业务约束没有随运行平台改变，RD/QA Skill 仍以 Python、FastAPI、SQLite、原生 JavaScript 为默认交付栈，并在 Docker AIO-Sandbox 中执行 Python 与 pytest。

> 当前状态：Task 1～17 与 Task 19 已实现并由默认回归覆盖；Task 18 的四条真实模型 E2E 尚未全部通过，因此当前版本不能据此宣称完整迁移验收完成。真实 E2E 的前置条件、执行顺序和证据规则见 [E2E.md](E2E.md)。

## 1. 整体架构

### 1.1 五个 COLA 模块

| 模块 | 职责 | 允许依赖 |
|---|---|---|
| `qingling-team-domain` | 消息、邮箱、checkpoint、工作流状态等领域模型，以及 Repository/Gateway 接口 | JDK 与轻量基础库 |
| `qingling-team-app` | Runner、按 routing key 串行分派、Session 路由、Cron、Cleanup 等用例编排 | domain |
| `qingling-team-infra` | AgentScope、MCP、文件 Repository、pgvector、飞书发送/下载、外部 HTTP | domain |
| `qingling-team-adapter` | 飞书入站、TestAPI、DTO 与外部事件转换 | app + domain |
| `qingling-team-starter` | Spring Boot 入口、配置、Bean 装配、workspace 初始化与生命周期 | adapter + app + infra |

依赖方向固定为：

```text
starter ──> adapter ──> app ──> domain
   │                       ▲
   └────────> infra ───────┘
```

`domain` 不依赖 Spring、AgentScope、飞书 SDK 或 JDBC；`app` 不直接依赖 `infra`；`adapter` 不直接依赖具体基础设施实现。框架和外部服务都在 starter 装配后通过 domain 端口接入。

### 1.2 关键运行组件

- AgentScope `HarnessAgent`：承载 Manager、PM、RD、QA 四个角色的模型会话、压缩、记忆、Skill 与声明式 Sub-Agent。
- Runner：把飞书或 TestAPI 消息转换为领域消息；同一 routing key 串行，不同 routing key 可并行。
- 文件协作面：邮箱使用 `unread → in_progress → done` 三态，项目事件使用 append-only JSONL，共享产物按角色限制写入路径。
- Cron 与 wake：发送团队邮件时创建一次性 wake；四个角色还有错峰 heartbeat，用于恢复遗漏工作。
- Java 工作流状态机：决定阶段、Owner 与允许的回路；模型不能自行跳过代码、测试或证据门禁。
- AIO-Sandbox：独立 Docker/Python 组件，通过 MCP 向 RD/QA 提供受限的 `sandbox_execute_bash`，宿主机任意 Shell 不暴露给模型。

## 2. 端到端工作流程

```text
用户 / 飞书 / TestAPI
        │
        ▼
     Manager ──起草需求──> Human checkpoint
        │                         │
        │                    approve/revise/reject
        ▼                         │
   PM 产品设计 <──────────────────┘
        │ 评审通过
        ▼
   RD 技术设计 ──评审/修订回路──┐
        │                       │
        ▼                       │
   RD 代码实现 + 沙盒 pytest <──┘
        │ 结构化测试证据通过
        ▼
   QA 测试设计
        │
        ▼
   QA 独立执行 + 沙盒 pytest ──发现缺陷──> Manager ──> RD 修复
        │ 全部通过                              │
        ▼                                      └──回到 QA
     Manager 交付 ──> 用户
```

一次典型消息的内部链路是：

1. 飞书 Listener 或 TestAPI 生成 `InboundMessage`。
2. Session 路由把 `routing_key` 映射到 AgentScope session；审计 JSONL 只用于追踪，不重复注入模型上下文。
3. Runner 选择角色并进入该 routing key 的串行队列。
4. Agent 读取项目邮箱和共享产物，按 workspace 中的 `AGENTS.md` 与 Skill 执行任务。
5. `send_mail` 写入接收者邮箱并安排一次性 wake；Cron 到期后把 `team:<role>` 消息重新交给 Runner。
6. `task_done` 必须携带产物和自评。RD/QA 还必须提供真实测试结果；Java 门禁拒绝无测试证据的“成功”。
7. `advance_workflow` 根据当前阶段和信号决定下一阶段。QA 缺陷回到 RD，checkpoint revise 保持人工控制，基础设施故障不应被伪装成业务测试失败。

四个角色的边界如下：

| 角色 | 主要职责 | 主要产物 |
|---|---|---|
| Manager | 唯一对外接口、需求澄清、checkpoint、阶段推进与交付 | `needs/requirements.md`、事件、任务分派、最终回复 |
| PM | 产品设计和产品视角评审 | `design/product_spec.md`、评审记录 |
| RD | 技术设计、Python 应用实现、研发自测与缺陷修复 | `tech/tech_design.md`、`code/`、pytest 证据 |
| QA | 测试设计、代码走查、独立执行和缺陷报告 | `qa/test_plan.md`、`qa/test_report.md`、`qa/defects/` |

## 3. 目录结构

```text
qingling-team/
├── README.md                         项目总览、运行、部署和迁移说明
├── E2E.md                            Task 18 真实 E2E 专项指南
├── pom.xml                           Maven 聚合与测试 profiles
├── sandbox-docker-compose.yaml       外部 AIO-Sandbox
├── contract-fixtures/                Python 基线生成的跨语言契约
├── qingling-team-domain/             领域模型与端口
├── qingling-team-app/                应用编排
├── qingling-team-infra/              AgentScope 与基础设施实现
├── qingling-team-adapter/            飞书/TestAPI 入站适配
├── qingling-team-starter/            可运行 Spring Boot 应用与 workspace 模板
├── workspace/                        运行时生成；角色指令、Skill、状态和共享项目
└── data/                             运行时生成；路由、审计、Cron、checkpoint、日志
```

`workspace/` 和 `data/` 是外部持久化目录，不属于可随 JAR 替换的临时文件。

## 4. 快速开始

### 4.1 环境要求

| 依赖 | 要求 | 用途 |
|---|---|---|
| JDK | 17 | 编译和运行 Java 服务 |
| Maven | 3.8+ | 多模块构建与验证 |
| Docker + Compose | 可用的本地或服务器 daemon | 运行 AIO-Sandbox |
| DashScope API Key | 百炼可用模型凭据 | AgentScope 模型调用 |
| PostgreSQL + pgvector | 可选 | 长期语义记忆 |

### 4.2 准备配置

在项目根目录执行：

```bash
cp qingling-team-starter/src/main/resources/config.yaml.template config.yaml
export DASHSCOPE_API_KEY='在当前终端设置，不要写入配置或提交到 Git'
```

服务启动时会自动读取项目根目录的 `config.yaml`。模型密钥优先使用 `DASHSCOPE_API_KEY`，仅为兼容旧 Python 部署而回退到 `QWEN_API_KEY`。

不要把 DashScope、飞书、百度或 MCP 鉴权凭据写入 `config.yaml`、命令输出、日志或版本库。

### 4.3 启动 AIO-Sandbox

仓库中的 Compose 默认把 `target/e2e-workspace` 挂载到容器 `/workspace`，适合开发和契约验证：

```bash
docker compose -f sandbox-docker-compose.yaml up -d
export QINGLING_WORKSPACE_ROOT=./target/e2e-workspace
```

生产部署必须把 Compose 的宿主挂载目录改为持久化 workspace，并保证 Java 的 `qingling.workspace.root` 与该宿主目录是同一个目录。容器内路径保持 `/workspace`，MCP 地址默认是 `http://localhost:8029/mcp`。

### 4.4 构建并启动

```bash
mvn verify
java -jar qingling-team-starter/target/qingling-team-starter-1.0.0-SNAPSHOT-exec.jar
```

默认行为：

- 飞书关闭，服务不会连接飞书；需要启用时设置 `QINGLING_FEISHU_ENABLED=true`、`FEISHU_APP_ID`、`FEISHU_APP_SECRET`。
- TestAPI 关闭；仅在受控开发环境设置 `QINGLING_TEST_API_ENABLED=true`。
- `/metrics` 开启，输出 Prometheus 文本。
- AIO-Sandbox 开启，启动时会校验 MCP 连接和允许的工具。

开发环境若要通过 TestAPI 发一条消息：

```bash
export QINGLING_TEST_API_ENABLED=true
java -jar qingling-team-starter/target/qingling-team-starter-1.0.0-SNAPSHOT-exec.jar

curl -X POST http://localhost:8080/api/test/message \
  -H 'Content-Type: application/json' \
  -d '{"routing_key":"demo:user-1","content":"请创建一个最小待办应用"}'
```

`POST /api/test/message` 会同步等待 Agent 最终回复；`DELETE /api/test/sessions` 会清理 TestAPI 路由、审计和已知 AgentScope state。它们都不是面向公网的生产管理 API。

## 5. 配置迁移

Java 配置采用 Spring Boot relaxed binding。可以修改 `config.yaml` 的非敏感项，也可以使用环境变量覆盖。

| Python 配置/环境变量 | Java 配置/环境变量 | 迁移说明 |
|---|---|---|
| `workspace` | `qingling.workspace.root` / `QINGLING_WORKSPACE_ROOT` | 改为四角色共用的外部可写根目录 |
| `data_dir` | `qingling.data-dir` / `QINGLING_DATA_DIR` | 保存 session、Cron、checkpoint、审计和日志相关数据 |
| `QWEN_API_KEY` | `DASHSCOPE_API_KEY`，回退 `QWEN_API_KEY` | 密钥只从环境变量读取 |
| CrewAI 模型配置 | `qingling.agent.*` / `QINGLING_AGENT_MODEL` | AgentScope `DashScopeChatModel`；主 Agent 与 Sub-Agent 可分别配置 |
| `feishu.enabled/app_id/app_secret` | `qingling.feishu.enabled` + `QINGLING_FEISHU_ENABLED` / `FEISHU_APP_ID` / `FEISHU_APP_SECRET` | 凭据不进入 YAML |
| `allowed_chats` | `qingling.feishu.allowed-chats` | 空列表表示不限制；入站 adapter 仍做二次校验 |
| `SANDBOX_URL` | `qingling.sandbox.url` / `QINGLING_SANDBOX_URL` | Java 通过 AgentScope MCP 访问外部 AIO-Sandbox |
| 沙盒工具列表 | `qingling.sandbox.enabled-tools` | 默认仅开放 `sandbox_execute_bash` |
| `memory.db_dsn` | `qingling.memory.db-dsn` / `QINGLING_MEMORY_DB_DSN` | 空字符串关闭 pgvector，失败不阻断主链路 |
| TestAPI 开关 | `qingling.debug.test-api-enabled` / `QINGLING_TEST_API_ENABLED` | 默认关闭 |
| Metrics 开关 | `qingling.metrics.enabled` / `QINGLING_METRICS_ENABLED` | 默认开启根路径 `/metrics` |
| `BAIDU_APPBUILDER_API_KEY` | 同名环境变量 | 空值时搜索工具返回可诊断错误 |

完整字段和默认值以 `qingling-team-starter/src/main/resources/config.yaml.template` 为准。AgentScope 版本固定为 `2.0.3`；升级该版本必须重新验证 state 格式、Skill、Sub-Agent、MCP 和四角色工具调用。

## 6. 数据与 workspace 兼容性

迁移保留的是业务和文件契约，不是 Python 对象或 CrewAI 内存对象的二进制兼容。

- `data/sessions/index.json` 保留 routing key、活跃 session 和历史 session 的兼容 JSON 结构。
- `data/sessions/*.jsonl` 是只写审计；AgentScope Harness state 才是模型上下文事实源，审计不会再次拼入 prompt。
- mailbox、events、tasks、checkpoint 和 TestAPI 字段通过 Python golden fixtures 做跨语言契约验证。
- workspace 模板启动时幂等初始化：`managed` 文件只有在用户未修改旧模板时才升级；`MEMORY.md` 等 `seed` 文件创建后永不覆盖。
- Python workspace Skill 的业务目标被保留并转换为 AgentScope Skill/Sub-Agent；RD/QA 生成和测试的仍是 Python 应用产物，不会强制改成 Java。
- 旧 CrewAI 会话内存不能直接导入 AgentScope state。升级时应保留文件产物和审计，但已有活跃对话建议从新 session 继续。

主要运行目录示意：

```text
workspace/
├── manager|pm|rd|qa/
│   ├── AGENTS.md
│   ├── MEMORY.md
│   ├── skills/
│   ├── subagents/
│   └── .agentscope/state/
└── shared/projects/<project_id>/
    ├── needs/  design/  tech/  code/  qa/
    ├── mailboxes/
    └── events.jsonl

data/
├── sessions/index.json
├── sessions/*.jsonl
├── cron/tasks.json
├── checkpoints/
└── logs/
```

## 7. 可选 pgvector

不配置 `QINGLING_MEMORY_DB_DSN` 时，pgvector 完全跳过，不影响核心流程。启用时：

1. 部署方先在目标 PostgreSQL 数据库执行 `CREATE EXTENSION IF NOT EXISTS vector;`。
2. 再执行 `qingling-team-infra/src/main/resources/db/optional/pgvector-memories.sql`。
3. 设置 JDBC DSN，例如 `jdbc:postgresql://db:5432/qingling`。
4. 使用 `mvn -Ppgvector-it verify` 在 Docker 可用环境验证真实 upsert 和向量字段。

应用不会自动创建扩展或生产表。索引写入失败会记录告警并旁路，不能拖垮消息主链路。

## 8. 部署、升级与回滚

推荐把以下内容分开管理：

- 不可变发布物：可执行 starter JAR、`sandbox-docker-compose.yaml`、版本化配置模板。
- 持久化数据：`workspace/`、`data/` 和可选 PostgreSQL 数据库。
- 外部依赖：AIO-Sandbox、DashScope、可选飞书/百度/pgvector。

升级步骤：

1. 在候选版本执行 `mvn verify`；涉及真实工具时再执行 tool-contract，真实 E2E 按 [E2E.md](E2E.md) 的门禁单独执行。
2. 备份 `workspace/`、`data/`、当前 JAR、`config.yaml` 和可选数据库；不要把备份放进待替换目录。
3. 停止 Java 服务，使 RuntimeLifecycle 先关闭入站和定时任务、再有限排空 dispatch，最后关闭 Agent/MCP。
4. 替换 JAR，核对配置模板新增字段；不要覆盖 workspace 中用户修改的 Skill、`MEMORY.md` 或项目产物。
5. 确认 AIO-Sandbox 健康且挂载目录一致，再启动 Java 服务并检查启动日志与 `/metrics`。

回滚步骤：

1. 停止失败版本，保留其脱敏日志和诊断证据。
2. 恢复上一版 JAR 与对应 `config.yaml`。
3. 若新版本只追加了兼容文件数据，通常可直接复用 `workspace/` 和 `data/`；若升级涉及 AgentScope 版本、state 格式或数据库 DDL，则恢复升级前备份。
4. 启动上一版并验证 MCP、Cron、mailbox 和 routing index；不要仅以 HTTP 端口存活作为成功标准。

回滚不会自动撤销外部模型调用，也不会自动删除新版本已生成的项目产物。是否恢复数据必须以升级前备份和版本说明为依据。

## 9. 测试

```bash
# 默认单元、架构和离线集成回归
mvn verify

# Python golden 跨语言契约
mvn -Pcontract-it verify

# 真实 Docker AIO-Sandbox，使用 NoCallModel，不调用百炼
mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify

# 可选 pgvector/Testcontainers
mvn -Ppgvector-it verify
```

真实模型 E2E 不属于默认回归，不能在没有门禁和预算控制时顺手运行。当前四个场景及严格顺序见 [E2E.md](E2E.md)。

## 10. 未完全等价项与已知限制

- CrewAI 已替换为 AgentScope Harness，模型执行轨迹、压缩策略和 state 文件格式不是逐字节等价；对外业务契约和文件格式由测试保证。
- Python `reference/task` Skill 已转换为 AgentScope 原生 Skill 与声明式 Sub-Agent，加载机制不同；`load_skills.yaml` 只保留为迁移清单，不再是运行时真相源。
- AIO-Sandbox 仍是外部 Python/Docker 组件，不是 Java 进程的一部分；容器健康不等于 MCP 命令通道健康，部署必须额外做真实工具探针。
- pgvector 是可选增强，应用不会自动安装扩展或执行生产 DDL；未启用时没有语义检索增强。
- Java Runner 会让当前 dispatch Future 暴露异常，同时恢复同 routing key 后续队列；这比 Python worker 吞掉异常更严格。
- AgentScope 模型上下文与 JSONL 审计刻意分离，旧 CrewAI 活跃会话不能无损续接。
- TestAPI 默认关闭且同步等待回复，不应作为公网生产接口使用。
- Task 18 仍未完成：四条真实百炼 + AgentScope + Docker E2E 尚未形成全部成功证据，不能用默认 `mvn verify` 代替。

## 11. 迁移矩阵与证据

每项 Python 运行能力都有明确 Java 去向或保留策略：

| 源能力 | 处置 | Java 实现或证据 |
|---|---|---|
| 核心消息与协议 | Java 实现 | `domain/model`、`domain/gateway`；领域与 ArchUnit 测试 |
| Session 路由索引 | Java 实现 | `app/session`、`infra/session`；Python golden JSON 契约 |
| 模型会话、压缩、长期记忆 | AgentScope 替代 | `TeamAgentFactory`、角色 `.agentscope/state`；AgentScope 2.0.3 smoke |
| 角色提示注入 | 转换 | starter `workspace-template/*/AGENTS.md`、`MEMORY.md`；模板契约 |
| 四角色 Crew | AgentScope 替代 | `TeamAgentFactory` 创建四个 HarnessAgent；无真实模型构建测试 |
| Task Sub-Crew | AgentScope 替代 | `RoleSubagentFactory` 与 `subagents/*.md`；发现/继承测试 |
| 8 个团队工具 | Java 实现 | `infra/agentscope/tool`；角色工具集和 Python schema 契约 |
| SkillLoader | AgentScope 替代 | workspace 原生 Skill 扫描；`migration-manifest.json` 覆盖 35 个源 Skill |
| IntermediateTool | Java 实现 | `IntermediateArtifactTools`；路径与产物测试 |
| 图片工具 | Java 实现 | `ImageAndSearchTools`；路径穿越、大小和启用范围测试 |
| 百度搜索工具 | Java 实现 | `ImageAndSearchTools`；环境变量凭据与失败返回测试 |
| 邮箱三态机 | Java 实现 | domain 邮箱规则 + `FileMailboxRepository`；并发、锁和 golden 契约 |
| 工作区读写 | Java 实现 | domain 权限 + `FileWorkspaceRepository`；越权与路径逃逸测试 |
| 事件日志 | Java 实现 | `JsonlEventRepository` append-only JSONL；seq 和损坏行恢复测试 |
| Checkpoint/分类 | Java 实现 | app checkpoint + 文件 Repository；分类优先级和幂等 resolve 测试 |
| 自评 | Java 实现 | domain 评分和 app 完成门禁；五维 Python golden 契约 |
| 日志查询 | Java 实现 | `LogQueryService`/CLI；stats、tasks、steps、l1、all-agents 契约 |
| Runner | Java 实现 | `Runner`、`SerialDispatchRegistry`；串行、并行、失败恢复和附件测试 |
| Cron 与 tasks store | Java 实现 | app cron + 文件 Repository；at/every/cron、热重载、wake 去重测试 |
| 飞书监听/发送/下载 | Java 实现 | adapter/infra Feishu；SDK 事件、白名单、卡片、附件和重试测试 |
| LLM | AgentScope 替代 | `DashScopeChatModel` 与 `DashScopeModelSelector`；配置和 Function Calling 探针 |
| pgvector | Java 实现，可选 | `infra/memory` + optional DDL；空 DSN 旁路和 `pgvector-it` |
| Cleanup | Java 实现 | app cleanup + infra 文件执行器；保留期与 data root 防逃逸测试 |
| Metrics/日志 | Java 实现 | Micrometer `/metrics` + Logback JSON；七类指标和凭据掩码测试 |
| TestAPI/CaptureSender | Java 实现 | adapter API + CaptureSender；Python 请求/响应 golden 与清理测试 |
| AIO-Sandbox | 保留外部组件 | Docker Compose + AgentScope MCP；`tool-contract` 真实工具验证 |
| workspace Skills | 转换并保留业务目标 | starter workspace 模板；35 项迁移清单、幂等升级和 Skill 发现测试 |

更细的设计、任务状态与偏差记录位于 `../spec-workflow/changes/python-to-java-cola-migration/`。
