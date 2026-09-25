# 任务拆分 — xiaopaw-team Python → Java（COLA + AgentScope）完整迁移

> updated: 2026-09-25  
> 执行顺序：依赖验证 → 架构骨架 → 领域契约 → 基础设施 → AgentScope → 应用编排 → 接入 → 验收。  
> 每个 Task 完成后必须运行对应验证；勾选只表示代码和测试均完成。  
> **HARD-GATE**：`spec.md` 第 13 节未确认前，不得执行以下代码任务。

## 前置条件

- [x] JDK `17.0.17` 已安装。
- [x] Maven `3.8.1` 已安装。
- [x] Python 源项目与测试已完成只读分析。
- [x] 用户已选择 P0 决策 `1A / 2A / 3A / 4A / 5A`。
- [x] 用户已填写 `spec.md` 第 13 节 HARD-GATE。

## Task 1：Maven 多模块 COLA 骨架

- **目标**：创建 `qingling-team` 父 POM 与 starter/adapter/app/domain/infra 五模块，并用 Maven Enforcer 固化依赖边界。
- **关键文件**：
  - `qingling-team/pom.xml`
  - `qingling-team/qingling-team-{starter,adapter,app,domain,infra}/pom.xml`
- **依赖约束**：domain 无内部依赖；app → domain；infra → domain；adapter → app + domain；starter → adapter + app + infra。
- **版本**：Java 17、Spring Boot 3.2.x、AgentScope `2.0.3`。
- **验收标准**：空骨架编译通过；禁止依赖方向能被构建规则检测。
- **验证命令**：`mvn -DskipTests compile`

- [x] 完成

## Task 2：AgentScope 2.0.3 编译探针

- **目标**：在正式实现前验证真实 API、依赖和异步边界，消除框架猜测。
- **关键文件**：
  - `qingling-team-infra/src/test/java/cn/org/chris/wake/infra/agentscope/AgentScopeCompileProbeTest.java`
  - `qingling-team-infra/src/test/java/cn/org/chris/wake/infra/agentscope/AgentScopeMcpProbeTest.java`
- **必须验证**：
  - `io.agentscope.harness.agent.HarnessAgent`
  - `DashScopeChatModel`、`Toolkit`、`@Tool`、`@ToolParam`
  - `SubagentDeclaration`
  - `RuntimeContext.userId/sessionId`
  - Agent 调用返回值到 `CompletableFuture` 的 `Mono.toFuture()` 转换
  - MCP server 注册成功/失败状态
- **验收标准**：不调用真实模型的构建测试通过；所有最终采用的 import 和 builder API 有编译证据。
- **验证命令**：`mvn -pl qingling-team-infra -am -Dtest=AgentScopeCompileProbeTest,AgentScopeMcpProbeTest test`

- [x] 完成

## Task 3：领域模型与 Gateway 契约

- **目标**：迁移纯领域模型，并定义基础设施端口；domain 不引入 Spring、AgentScope、飞书 SDK、JDBC。
- **关键文件**：
  - `qingling-team-domain/.../model/InboundMessage.java`、`Attachment.java`、`SessionRoute.java`、`MailMessage.java`、`CronJob.java`、`PendingCheckpoint.java`
  - `qingling-team-domain/.../gateway/AgentGateway.java`、`SenderGateway.java`、`SessionRouteRepository.java`
  - `qingling-team-domain/.../gateway/MailboxRepository.java`、`WorkspaceRepository.java`、`EventRepository.java`、`CheckpointRepository.java`
- **关键约束**：`AgentGateway.execute(...)` 返回 `CompletableFuture<AgentReply>`；domain 不出现 Reactor 类型。
- **验收标准**：模型字段与 Python fixture 对齐；架构测试证明 domain 无框架依赖。
- **验证命令**：`mvn -pl qingling-team-domain -am test`

- [x] 完成

## Task 4：Session 路由、命令与审计存储

- **目标**：实现选择 2A：Harness 拥有模型上下文，自研组件只拥有路由映射、verbose、Slash Command 和审计。
- **关键文件**：
  - `qingling-team-app/.../session/SessionRoutingService.java`
  - `qingling-team-app/.../session/SlashCommandService.java`
  - `qingling-team-infra/.../persistence/session/FileSessionRouteRepository.java`
  - `qingling-team-infra/.../persistence/session/JsonlConversationAuditRepository.java`
- **业务规则**：
  - `/new` 生成新 AgentScope sessionId 并切换 active mapping。
  - JSONL 审计不得作为模型历史再次传给 Harness。
  - `/api/test/sessions` 同时清理测试 routing mapping 和 AgentScope state。
  - index 采用 FileLock + temp + atomic move；审计 JSONL flush/fsync。
- **验收标准**：同 routing key 复用同 sessionId；`/new` 后换 id 且旧上下文不进入新会话。
- **验证命令**：`mvn -pl qingling-team-app,qingling-team-infra -am test`

- [x] 完成

## Task 5：邮箱、共享工作区与事件日志

- **目标**：迁移邮箱三态、项目目录、共享文件权限与 append-only 事件日志。
- **关键文件**：
  - `qingling-team-domain/.../mailbox/MailboxService.java`
  - `qingling-team-domain/.../workspace/WorkspacePolicy.java`
  - `qingling-team-domain/.../event/EventService.java`
  - `qingling-team-infra/.../persistence/{mailbox,workspace,event}/*Repository.java`
- **业务规则**：三态机、reset stale、actor/action 校验、seq、owner 权限、路径/符号链接逃逸防护与 Python 版一致。
- **验收标准**：Python golden fixtures 可被 Java 读取；Java 输出满足相同 JSON 契约。
- **验证命令**：`mvn -pl qingling-team-domain,qingling-team-infra -am test`

- [x] 完成

## Task 6：Human checkpoint、分类与自评

- **目标**：完整迁移 `feishu_bridge.py` 与 `self_score.py`。
- **关键文件**：
  - `qingling-team-app/.../checkpoint/CheckpointService.java`
  - `qingling-team-domain/.../checkpoint/HumanInputClassifier.java`
  - `qingling-team-domain/.../quality/SelfScoreCalculator.java`
  - `qingling-team-infra/.../persistence/checkpoint/JsonlCheckpointRepository.java`
- **业务规则**：callback 优先、显式 checkpoint id、最新 pending 决策词绑定、SOP/新需求/澄清/兜底分类；resolve 幂等；5 维加权和范围校验。
- **验收标准**：源 `test_feishu_bridge.py` 与 `test_self_score.py` 的场景全部有 Java 对应测试。
- **验证命令**：`mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra -am test`

- [x] 完成

## Task 7：Cron、wake、heartbeat 与 Cleanup

- **目标**：迁移 `CronService`、tasks store 和 `CleanupService`。
- **关键文件**：
  - `qingling-team-app/.../cron/CronService.java`、`WakeScheduler.java`
  - `qingling-team-app/.../cleanup/CleanupService.java`
  - `qingling-team-infra/.../persistence/cron/FileCronJobRepository.java`
  - `qingling-team-infra/.../persistence/cleanup/FileCleanupExecutor.java`
- **业务规则**：at/every/cron、时区、mtime/size 热重载、一次性任务、wake 去重、30 秒 heartbeat 与 `0/7/14/21` 首次错峰；按策略清理且不得越出 data root。
- **验收标准**：发邮件后 1 秒内产生目标角色 wake；at + heartbeat 不重复执行；清理规则和权限文件初始化可测试。
- **验证命令**：`mvn -pl qingling-team-app,qingling-team-infra -am test`

- [x] 完成

## Task 8：workspace 模板转换与外部初始化

- **目标**：把原 workspace 转换为 AgentScope 结构，并在启动时幂等初始化到外部可写目录。
- **关键文件**：
  - `qingling-team-starter/src/main/resources/workspace-template/{manager,pm,rd,qa}/AGENTS.md`
  - `qingling-team-starter/src/main/resources/workspace-template/{manager,pm,rd,qa}/skills/**/SKILL.md`
  - `qingling-team-starter/src/main/resources/workspace-template/{manager,pm,rd,qa}/subagents/*.md`
  - `qingling-team-infra/.../workspace/WorkspaceTemplateInitializer.java`
- **转换规则**：
  - 合并/引用 `soul.md`、`agent.md`、`user.md`、`team_protocol.md`。
  - `memory.md` 仅作为初始 seed，不覆盖已存在 `MEMORY.md`。
  - reference skill → AgentScope Skill；task skill → Skill + Sub-Agent。
  - `sandbox_execute_bash` 等旧工具名替换为实际 MCP/AgentScope 工具。
  - `load_skills.yaml` 仅保留为迁移清单或生成期输入，不作为运行时 loader。
  - 保持 code_impl/test_run 生成 Python、pip/pytest 的业务行为。
- **验收标准**：初始化重复执行不覆盖运行数据；当前实际扫描到的 35 个源 `SKILL.md` 逐项有迁移结果，后续新增 Skill 也由清单检查发现；四角色均可发现预期 Skill。
- **验证命令**：`mvn -pl qingling-team-infra,qingling-team-starter -am test`

- [x] 完成

## Task 9：AgentScope Tools 与角色 ToolKit

- **目标**：将团队工具和辅助工具注册为 AgentScope `@Tool`，注解类只位于 infra。
- **关键文件**：
  - `qingling-team-infra/.../agentscope/tool/CommonTeamTools.java`
  - `qingling-team-infra/.../agentscope/tool/ManagerTeamTools.java`
  - `qingling-team-infra/.../agentscope/tool/IntermediateArtifactTools.java`
  - `qingling-team-infra/.../agentscope/tool/ImageAndSearchTools.java`
  - `qingling-team-infra/.../agentscope/tool/RoleToolkitFactory.java`
- **验收标准**：Manager 恰有 8 个团队工具，其他角色有 5 个；辅助工具按角色/Skill 暴露；工具参数 JSON schema 与 Python 名称兼容。
- **验证命令**：`mvn -pl qingling-team-infra -am test`

- [ ] 完成

## Task 10：四角色 HarnessAgent、Sub-Agent 与 MCP

- **目标**：使用 AgentScope `2.0.3` 构造四角色 Agent，并接入转换后的 Skills、Sub-Agent 和 AIO-Sandbox MCP。
- **关键文件**：
  - `qingling-team-infra/.../agentscope/TeamAgentFactory.java`
  - `qingling-team-infra/.../agentscope/AgentScopeAgentGateway.java`
  - `qingling-team-infra/.../agentscope/RoleSubagentFactory.java`
  - `qingling-team-infra/.../agentscope/McpSandboxConfiguration.java`
- **关键签名**：

```java
import io.agentscope.harness.agent.HarnessAgent;

public final class AgentScopeAgentGateway implements AgentGateway {
    @Override
    public CompletableFuture<AgentReply> execute(AgentRequest request);
}
```

- **调用规则**：用 routing service 提供的 sessionId 构造 `RuntimeContext`；不得把审计 JSONL 拼入 prompt；AgentScope `Mono` 在 adapter 内转换为 future。
- **验收标准**：四角色可构建；task skill 能启动声明式 Sub-Agent；MCP 注册状态可观测；无真实模型 smoke 通过。
- **验证命令**：`mvn -pl qingling-team-infra -am test`

- [ ] 完成

## Task 11：Runner 完整执行链

- **目标**：迁移主 Runner 与 `_runner_base.py` 中的有效能力。
- **关键文件**：
  - `qingling-team-app/.../runner/Runner.java`
  - `qingling-team-app/.../runner/RoutingKeyResolver.java`
  - `qingling-team-app/.../runner/SerialDispatchRegistry.java`
  - `qingling-team-app/.../runner/InboundAttachmentService.java`
- **执行顺序**：路由串行化 → wake 去重 → Slash 拦截 → sessionId → 附件落盘 → Loading 卡片 → AgentGateway → 审计 → 更新卡片/发送回复。
- **验收标准**：同 key 串行、不同 key 并行；team wake 不对外回复；附件路径进入 session workspace；失败记录 metrics 且队列继续消费。
- **验证命令**：`mvn -pl qingling-team-app -am test`

- [ ] 完成

## Task 12：飞书 Listener、Sender 与 Downloader

- **目标**：用 `oapi-sdk-java` 实现飞书入站/出站完整契约。
- **关键文件**：
  - `qingling-team-adapter/.../feishu/FeishuWebSocketListener.java`、`FeishuEventConverter.java`
  - `qingling-team-infra/.../feishu/FeishuSenderGateway.java`
  - `qingling-team-infra/.../feishu/FeishuDownloader.java`、`FeishuClientFactory.java`
- **业务规则**：p2p 始终允许；群白名单；Bot 入群；text/post/image/file；thread root；Loading 卡片；PATCH 更新；1/2/4 秒重试；日志脱敏。
- **验收标准**：SDK mock/contract 测试覆盖转换、发送、更新、下载、白名单和重连。
- **验证命令**：`mvn -pl qingling-team-adapter,qingling-team-infra -am test`

- [ ] 完成

## Task 13：pgvector 记忆索引

- **目标**：迁移 `async_index_turn()`，保持非阻塞和失败降级。
- **关键文件**：
  - `qingling-team-infra/.../memory/PgVectorMemoryIndexer.java`
  - `qingling-team-infra/.../memory/MemoryExtractionClient.java`
  - `qingling-team-infra/src/main/resources/db/optional/pgvector-memories.sql`
- **业务规则**：摘要和两份 1024 维向量、稳定幂等 id、`ON CONFLICT DO NOTHING`；DSN 空时跳过，失败只告警。
- **验收标准**：Testcontainers PostgreSQL+pgvector profile 验证 upsert；默认构建不要求数据库。
- **验证命令**：`mvn -pl qingling-team-infra -am test`；可选 `mvn -Ppgvector-it verify`

- [ ] 完成

## Task 14：TestAPI 与 CaptureSender

- **目标**：等价迁移测试 HTTP 接口和无飞书 Sender。
- **关键文件**：
  - `qingling-team-adapter/.../api/TestApiController.java`
  - `qingling-team-adapter/.../api/dto/TestRequest.java`、`TestResponse.java`
  - `qingling-team-adapter/.../api/CaptureSender.java`
- **兼容契约**：字段、默认 msgId/senderId、附件复制、300 秒可配置超时、400/422、响应 `msg_id/reply/session_id/duration_ms/skills_called`。
- **验收标准**：Python/Java HTTP golden contract 一致；`--no-feishu` 下可独立运行。
- **验证命令**：`mvn -pl qingling-team-adapter -am test`

- [ ] 完成

## Task 15：日志查询、Metrics 与结构化日志

- **目标**：迁移 log_query 的 stats/tasks/steps/l1/all-agents，并覆盖原 Prometheus 指标。
- **关键文件**：
  - `qingling-team-app/.../observability/LogQueryService.java`
  - `qingling-team-starter/.../cli/LogQueryCommand.java`
  - `qingling-team-infra/.../observability/MetricsRecorder.java`
  - `qingling-team-starter/src/main/resources/logback-spring.xml`
- **验收标准**：CLI JSON 输出契约与 Python fixture 一致；`/metrics` 暴露 Runner、飞书、HTTP、错误等指标；日志不含密钥。
- **验证命令**：`mvn -pl qingling-team-starter -am test`

- [ ] 完成

## Task 16：Starter、配置与生命周期装配

- **目标**：完成 Spring Boot 启动、配置映射、Bean 装配和优雅停止。
- **关键文件**：
  - `qingling-team-starter/.../QinglingTeamApplication.java`
  - `qingling-team-starter/.../config/QinglingTeamProperties.java`
  - `qingling-team-starter/.../config/RuntimeConfiguration.java`
  - `qingling-team-starter/src/main/resources/application.yml`、`config.yaml.template`
- **启动顺序**：校验配置 → 初始化外部 workspace → 凭据文件 → Repository/Agents → heartbeat → Cron/Cleanup/Metrics → TestAPI/飞书。
- **停止顺序**：停止入站 → 排空 Runner → 停 Cron/后台索引 → 关闭 AgentScope/SDK。
- **验收标准**：无飞书模式启动；缺少必要模型密钥时给出脱敏错误；配置项覆盖 Spec 第 6 节。
- **验证命令**：`mvn -pl qingling-team-starter -am test`

- [ ] 完成

## Task 17：跨语言契约与无 LLM 集成测试

- **目标**：从 Python 基线生成只读 fixtures，验证 Java 数据和行为契约。
- **覆盖**：路由、Session mapping、邮箱、事件、tasks、checkpoint、self_score、TestAPI、workspace 权限、自动唤醒、四角色 Skill 发现。
- **验收标准**：所有 P0 契约/集成场景通过；fixture 生成方式和来源 commit/目录有记录。
- **验证命令**：`mvn -Pcontract-it verify`

- [ ] 完成

## Task 18：四条真实 E2E

- **目标**：使用真实 Qwen + AIO-Sandbox 验证选择 5A 的四条流程。
- **场景**：
  - happy path
  - checkpoint revise
  - QA defect → RD fix
  - code fail recovery
- **验收标准**：每条都验证产物、mailbox、events、checkpoint、AgentScope session 和最终回复；失败不得只以超时忽略。
- **验证命令**：`mvn -Pe2e -De2e.scenario=<name> verify`

- [ ] 完成

## Task 19：迁移收尾与文档

- **目标**：补充 README、运行/部署说明、配置迁移、数据兼容和已知差异。
- **必须记录**：AgentScope `2.0.3`、AIO-Sandbox 外部依赖、Python Skill 产物保留、pgvector 可选 DDL、升级/回滚方式、未完全等价项。
- **验收标准**：`mvn verify` 通过；迁移矩阵每行有实现或明确证据；`spec.md` 执行日志与偏差同步。
- **验证命令**：`mvn verify`

- [ ] 完成

---

## 汇总（全部完成后填写）

- **变更摘要**：
- **总文件数**：
- **测试证据**：
- **Spec-Plan 偏差记录**：
- **遗留问题**：
