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

- [x] 完成

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

- [x] 完成

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

- [x] 完成

## Task 12：飞书 Listener、Sender 与 Downloader

- **目标**：用 `oapi-sdk-java` 实现飞书入站/出站完整契约。
- **关键文件**：
  - `qingling-team-adapter/.../feishu/FeishuWebSocketListener.java`、`FeishuEventConverter.java`
  - `qingling-team-infra/.../feishu/FeishuSenderGateway.java`
  - `qingling-team-infra/.../feishu/FeishuDownloader.java`、`FeishuClientFactory.java`
- **业务规则**：p2p 始终允许；群白名单；Bot 入群；text/post/image/file；thread root；Loading 卡片；PATCH 更新；1/2/4 秒重试；日志脱敏。
- **验收标准**：SDK mock/contract 测试覆盖转换、发送、更新、下载、白名单和重连。
- **验证命令**：`mvn -pl qingling-team-adapter,qingling-team-infra -am test`

- [x] 完成

## Task 13：pgvector 记忆索引

- **目标**：迁移 `async_index_turn()`，保持非阻塞和失败降级。
- **关键文件**：
  - `qingling-team-infra/.../memory/PgVectorMemoryIndexer.java`
  - `qingling-team-infra/.../memory/MemoryExtractionClient.java`
  - `qingling-team-infra/src/main/resources/db/optional/pgvector-memories.sql`
- **业务规则**：摘要和两份 1024 维向量、稳定幂等 id、`ON CONFLICT DO NOTHING`；DSN 空时跳过，失败只告警。
- **验收标准**：Testcontainers PostgreSQL+pgvector profile 验证 upsert；默认构建不要求数据库。
- **验证命令**：`mvn -pl qingling-team-infra -am test`；可选 `mvn -Ppgvector-it verify`

- [x] 完成

## Task 14：TestAPI 与 CaptureSender

- **目标**：等价迁移测试 HTTP 接口和无飞书 Sender。
- **关键文件**：
  - `qingling-team-adapter/.../api/TestApiController.java`
  - `qingling-team-adapter/.../api/dto/TestRequest.java`、`TestResponse.java`
  - `qingling-team-adapter/.../api/CaptureSender.java`
- **兼容契约**：字段、默认 msgId/senderId、附件复制、300 秒可配置超时、400/422、响应 `msg_id/reply/session_id/duration_ms/skills_called`。
- **验收标准**：Python/Java HTTP golden contract 一致；`--no-feishu` 下可独立运行。
- **验证命令**：`mvn -pl qingling-team-adapter -am test`

- [x] 完成

## Task 15：日志查询、Metrics 与结构化日志

- **目标**：迁移 log_query 的 stats/tasks/steps/l1/all-agents，并覆盖原 Prometheus 指标。
- **关键文件**：
  - `qingling-team-app/.../observability/LogQueryService.java`
  - `qingling-team-starter/.../cli/LogQueryCommand.java`
  - `qingling-team-infra/.../observability/MetricsRecorder.java`
  - `qingling-team-starter/src/main/resources/logback-spring.xml`
- **验收标准**：CLI JSON 输出契约与 Python fixture 一致；`/metrics` 暴露 Runner、飞书、HTTP、错误等指标；日志不含密钥。
- **验证命令**：`mvn -pl qingling-team-starter -am test`

- [x] 完成

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

- [x] 完成

## Task 17：跨语言契约与无 LLM 集成测试

- **目标**：从 Python 基线生成只读 fixtures，验证 Java 数据和行为契约。
- **覆盖**：路由、Session mapping、邮箱、事件、tasks、checkpoint、self_score、TestAPI、workspace 权限、自动唤醒、四角色 Skill 发现。
- **验收标准**：所有 P0 契约/集成场景通过；fixture 生成方式和来源 commit/目录有记录。
- **验证命令**：`mvn -Pcontract-it verify`

- [x] 完成

## Task 18：四条真实 E2E

- **目标**：使用真实 Qwen + AIO-Sandbox 验证选择 5A 的四条流程。
- **场景**：
  - happy path
  - checkpoint revise
  - QA defect → RD fix
  - code fail recovery
- **验收标准**：每条都验证产物、mailbox、events、checkpoint、AgentScope session 和最终回复；失败不得只以超时忽略。
- **验证命令**：`mvn -Pe2e -De2e.scenario=<name> verify`

### 当前进度（2026-10-02）

- [x] E2E 基础设施、真实百炼模型、真实 AgentScope、真实 Docker AIO-Sandbox 已接通。
- [x] 修复 Cron 执行旧快照覆盖新 wake，并验证下一角色 wake 能在后续 tick 执行。
- [x] 修复 `send_to_human` 真实 routing key 上下文、任务分派幂等、技术方案修订后代码阶段不可跳过。
- [x] 增加显式模型/免费模型选择开关、Manager 确定性评审工具、QA 共享目录与缺陷单点分派约束。
- [x] 增加 `sandbox_execute_bash.timeout` 必须为 JSON 整数或省略的模板契约；增加同义任务主题幂等回归；完整默认 `mvn verify` 155 项通过。
- [x] 禁用父/子 Harness 内置文件工具；在父 Toolkit 构建期注册白名单 MCP，使声明式 RD/QA Sub-Agent 可继承 `sandbox_execute_bash`；注册失败或工具缺失时启动即脱敏失败。定向 8 项与完整默认 `mvn verify` 161 项通过。
- [x] 增加 Java 完成证据硬门禁、确定性阶段状态机和 `run_project_tests(project_id)`：RD 缺少 `exit_code=0`/pytest 数量/覆盖率、QA 缺少报告/证据矩阵时不能发送成功 `task_done`；checkpoint、PM、RD、QA、交付的目标阶段与 Owner 由 Java 固定，通用 `append_event` 不能伪造迁移；默认 `mvn verify` 175 项通过。
- [x] 增加零模型真实工具契约 profile；真实 Docker AIO-Sandbox 健康时，RD/QA 声明式子代理均验证工具清单并执行无副作用 `pwd/test -d`。进一步把 MCP Mono 已完成且有输出的伪 `RUNNING` 归一化为 `SUCCESS`；容器重启后 `177 unit + 1 tool contract` 通过且未调用百炼。
- [x] 增加“Manager 澄清响应 → Java 状态机重新唤醒原 Owner 继续当前阶段”协议：`clarification_answered` 固定保持当前阶段、按阶段固定原 Owner、发送含 `protocol=clarification_answer`、`resume_current_stage=true` 和答复正文的唯一继续任务；空答复、错误 Owner 与绕过状态机的直发答复均被拒绝。模板已规定 Manager 成功推进后才关闭请求，PM/RD/QA 收到继续任务只恢复原阶段。状态机 6/6、工作流工具 7/7、模板契约 14/14，默认 `mvn verify` 182 项通过；本次按用户要求未重跑 E2E。
- [ ] `happy-path`：最新一次真实运行仍未通过。2026-10-11 在默认 225 项和零模型真实 Docker 1 IT 通过后，仅运行一次干净的显式 `qwen3.8-flash` 场景；需求 checkpoint 正常完成并迁移到 `PRODUCT_DESIGN/PM`。PM 首次 `sandbox_execute_bash` 把宿主机 `qingling-team-starter` 目录作为容器 working directory，AIO-Sandbox 返回目录不存在；当前 MCP 健康层又把该确定性调用参数错误计为连接故障并将 PM client 的 circuit 置为 OPEN。Driver 在 190.2 秒时报告直接 MCP 根因，未伪装成文件超时。证据在 `target/e2e-evidence/happy-path`；未重试或启动后三个场景，清理后 AIO session=0 且无 pytest/pip/tmux/Maven/Java 残留。Task 18 不能标记完成，下轮前需修复沙盒 working directory 归一化与熔断错误分类。
- [x] 2026-10-12 离线修复上述最新阻断：MCP Bash 包装器把宿主 workspace 子路径和 Maven/AgentScope `user.dir` 映射到容器 `/workspace`，缺省 cwd 也固定为挂载根；cwd/参数 validation ERROR 不再增加连接失败或打开 circuit，连接异常与取消仍熔断。定向 15 项、`mvn verify` 227 项、零模型真实 Docker 1 IT 均通过；真实契约 10 次 Shell 故意携带宿主 cwd 且实际 `pwd=/workspace`，5 次 pytest 与一次恢复通过。未运行 E2E，`happy-path` 与 Task 18 仍保持未完成。
- [ ] 2026-10-12 只执行一次修复后的显式 `qwen3.8-flash happy-path`，仍未通过：cwd 修复真实生效、MCP 全程 CLOSED/0 failures，流程进入 `CODE_IMPLEMENTATION/RD`；三个 `code_impl` 子任务均 CANCELLED，父 dispatch 正确终止并恢复邮件为 unread，但一次 heartbeat 自动恢复再次同类失败后没有写入 `tool_execution_failed`/BLOCKED，也没有重新领取 RD 消息，最终 43 分 18 秒后仍报告 `qa/test_plan.md` 超时。未启动第二次 E2E 或后三个场景，退出后无孤儿进程；父 Task 18 保持未完成。
- [x] 2026-10-14 离线闭环上述 RD 取消恢复问题：AgentScope spawn/send/cancel 记录任务种类与取消来源，同一 `agent_id` 只以最新 continuation 为权威；最新失败/取消形成类型化 Agent 故障并由 Runner 接入 Java incident。第一次不同 occurrence 释放当前 assignment、保持 stage/Owner 并唤醒原 Owner，第二次进入 BLOCKED，重复 occurrence 幂等；watchdog 跳过 BLOCKED，Driver 可在 Cron 失败且运行时已空闲时报告直接原因。定向 56 项、`mvn verify` 245 项和零模型真实 Docker 1 IT 均通过；按用户要求未运行 E2E，`happy-path` 与父 Task 18 仍保持未完成。
- [ ] 2026-10-15 仅执行一次上述修复后的显式 `qwen3.8-flash happy-path`，仍未通过：流程正确进入 `CODE_IMPLEMENTATION/RD`，单一 spawn、终态后 continuation、无主动取消和 watchdog lease 恢复均真实生效。首个 Test Runner execution 的容器终态实际为 `FINISHED/exit_code=1`，pytest 32 collected、11 passed、21 failed、coverage 72%，本应交给 RD 修复；Java 却返回 `INFRASTRUCTURE_FAILED/RESULT_UNAVAILABLE/exit_code=null`，并快速写入 RECOVERING、BLOCKED。Driver 直接报告该 incident，没有等待 QA 文件超时。Failsafe 1 failure、0 errors、0 skipped，Maven 29 分 55 秒；未重试或启动后三个场景，清理后无孤儿进程。父 Task 18 保持未完成。
- [x] 2026-10-25 完成确定性编排专项的非 E2E 前置：runtime v2 单一 WorkExecution/outbox、Java 完成协调、固定 `STANDARD + REQUIRED_ONLY`、SIMPLE/STANDARD 父角色直做、只读 Todo acceptance 与 Driver v2 直接诊断均已接入。相关模块 165 项、最终 `mvn verify` 394 项、修复后真实 Docker/NoCallModel 工具契约 1 IT、coordinated IT 1 项及 `git diff --check` 全部通过；首次工具契约真实暴露并修复 QA fixture 未安装。未 source E2E 凭据、未运行新一轮真实 E2E。
- [ ] 2026-10-25 真实 E2E 仍受二次授权门禁约束；`happy-path`、`checkpoint-revise`、`qa-defect-rd-fix`、`code-fail-recovery` 尚未在本版本全部通过，Task 18 不得标记完成。
- [ ] 2026-10-25 用户二次授权后仅执行一次 `qwen3.8-flash happy-path`，在 Spring startup 失败：coordinated STANDARD 禁用 Sub-Agent 后 HarnessAgent 没有 TaskRepository，Gateway 仍无条件绑定 TaskSemanticsRegistry，报 `NullPointerException: repository 不能为空`。Failsafe 1 error、E2E 7.139s、Maven 26.359s；未进入业务 workflow 或角色模型调用，未重试，清理后无孤儿进程。需修复无子任务仓库适配并补真实启动测试，Task 18 保持未完成。
- [ ] `checkpoint-revise`：等待 happy-path 通过后执行。
- [ ] `qa-defect-rd-fix`：等待 happy-path 通过后执行。
- [ ] `code-fail-recovery`：等待 happy-path 通过后执行。
- [ ] 四条场景证据齐全并完成最终默认回归、Spec 日志同步。

- [ ] 完成

## Task 19：迁移收尾与文档

- **目标**：补充 README、运行/部署说明、配置迁移、数据兼容和已知差异。
- **必须记录**：AgentScope `2.0.3`、AIO-Sandbox 外部依赖、Python Skill 产物保留、pgvector 可选 DDL、升级/回滚方式、未完全等价项。
- **验收标准**：`mvn verify` 通过；迁移矩阵每行有实现或明确证据；`spec.md` 执行日志与偏差同步。
- **验证命令**：`mvn verify`

- [x] 新增项目根 `README.md`，覆盖项目定位、COLA 五模块、四角色端到端工作流、启动/部署、配置迁移、数据兼容、可选 pgvector、升级/回滚、测试入口和未完全等价项。
- [x] README 明确 AgentScope `2.0.3`、AIO-Sandbox 外部 Docker/Python 依赖，以及 Python Skill/交付产物继续保留的边界。
- [x] 迁移矩阵 27 行全部给出 Java 实现、AgentScope 替代、外部保留策略或具体测试证据；Task 18 未通过的真实状态没有被文档收尾掩盖。
- [x] starter 增加 Spring Boot `repackage`，以 `exec` classifier 生成含 `JarLauncher` 和 `QinglingTeamApplication` 入口的可执行 JAR，同时保留普通主 JAR供 Failsafe 使用。
- [x] 2026-10-11 执行 `mvn verify`：六模块全部 SUCCESS，225 tests，0 failure / 0 error / 0 skipped；未运行真实 E2E、未调用百炼。

- [x] 完成

---

## 汇总（全部完成后填写）

- **变更摘要**：
- **总文件数**：
- **测试证据**：
- **Spec-Plan 偏差记录**：
- **遗留问题**：

- [x] Task 18：修复无 TaskRepository 的空子任务诊断/取消适配并增加真实 Spring 启动测试
- [ ] Task 18：修复后四条真实 E2E（已执行一次 happy-path，因 Driver 孤儿误判竞态失败，Task 18 保持未完成）
- [x] Task 18：修复 Manager 完成交接期间的孤儿 execution 误判并完成默认回归
- [ ] Task 18：交接诊断修复后的真实 E2E（本轮未运行）

- [x] Task 18：交接诊断修复后执行一次干净 `happy-path` 并保存失败证据
- [x] Task 18：修复 `task_done` 的 `WorkCompletionDecision` 契约可发现性及完成工具失败后的确定性终态
- [ ] Task 18：修复后四条真实 E2E（最新 happy-path 因 PM 完成协议非法枚举留下真孤儿而失败，未重试）

- [x] Task 18：完成协议修复聚焦 50 项和默认回归 402 项通过
- [x] Task 18：E2E 前零模型真实 Docker 契约通过，并只执行一次干净 `happy-path`
- [ ] Task 18：四条真实 E2E（最新 `happy-path` 在模型 Function Calling 启动预检以 `transport_error` 失败，未进入 workflow）

- [x] Task 18：修复模型预检稳定分类、最多一次有限重试和逐次脱敏证据
- [x] Task 18：独立真实 `qwen3.8-flash` 最小 Function Calling 探针通过
- [x] Task 18：聚焦 67 项、默认回归 409 项、零模型真实 Docker 契约 1 IT 通过
- [ ] Task 18：四条真实 E2E（本轮按要求未运行）

- [x] Task 18：模型预检修复后只执行一次干净 `happy-path` 并保存真实失败证据
- [x] Task 18：确认预检、checkpoint、产品设计、技术设计及 MCP 链路正常
- [ ] Task 18：修复未调用 Test Runner/完成工具便退出时的 incident 与确定性 continuation
- [ ] Task 18：四条真实 E2E（最新 `happy-path` 在 CODE_IMPLEMENTATION 形成真孤儿，未重试）

### 2026-10-25 Task 18 Owner 回合未终态修复

- [x] 新增 `OWNER_EXITED_WITH_RUNNING_EXECUTION` 类型化失败并接入 Runner incident
- [x] 首次失败保持原 stage/Owner/execution，向原 Owner 投递 continuation 并自动 wake
- [x] 同一 execution 重领后 attempt=2，第二次同类失败 BLOCKED 且不再唤醒
- [x] 聚焦 63 项、完整默认回归 410 项、零模型真实 Docker 契约 1 IT 通过
- [ ] Task 18：四条真实 E2E（本轮按要求未运行，保持未完成）

### 2026-10-25 Task 18 单次 E2E

- [x] 真实验证 Owner 首次未终态自动续跑、attempt=2 第二次同类失败 BLOCKED
- [x] 清理 AIO session 和宿主/容器残留进程
- [x] 修复 Cron 持锁无界 join 导致 shutdown/finally 永久阻塞
- [ ] Task 18：四条真实 E2E（本轮 `happy-path` 失败且无成功 summary）

### 2026-10-26 Task 18 运行时收尾可靠性修复

- [x] Cron 锁、dispatch 有界等待和 RuntimeLifecycle 取消职责完成分层
- [x] Driver 失败证据顺序、全局 E2E 与 shutdown 看门狗完成
- [x] RD 恢复 required_action 与代码阶段真实 Test Runner 硬门禁完成
- [x] 定向 80 项、`mvn verify` 415 项、零模型真实 Docker 契约 1 IT 通过
- [ ] Task 18：四条真实 E2E 与最终验收（本轮未运行，继续未完成）

### 2026-10-26 Task 18 单次 E2E 环境失配

- [x] 仅启动一次 `happy-path` 并保存预检与宿主 workflow 证据
- [x] 发现容器旧 bind mount 后中止，未将无效运行误记为业务失败
- [x] 清理 session、重启容器并验证挂载、健康与无孤儿
- [ ] Task 18：四条真实 E2E 与最终验收（本轮未产生有效结果）

### 2026-10-27 Task 18 单次有效 E2E

- [x] 仅执行一次真实 `qwen3.8-flash happy-path`，模型与 Docker 前置验证通过
- [x] 验证 checkpoint、PM、RD 技术设计及 PM 完成协议恢复链
- [x] 验证 RD 未终态自动续跑、第二次 BLOCKED、Test Runner 硬门禁与可靠 shutdown
- [ ] 修复 RD 在两次有限回合内仍未调用 Test Runner/完成协议的问题
- [ ] Task 18：四条真实 E2E 与最终验收（happy-path 失败，后三场景未运行）

### 2026-10-27 Task 18 代码阶段确定性推进修复

- [x] RD 最小纵切面后立即执行真实 Test Runner，并固定纳入 Harness acceptance
- [x] 类型化区分未调用测试与测试后未完成
- [x] 首次未测试恢复由 Java 强制先 Test Runner、后写代码
- [x] 已有测试证据时仅增加一次有限收尾 continuation
- [x] 定向 72 项、默认回归 420 项、零模型 Docker 契约 1 IT 通过
- [ ] Task 18：四条真实 E2E 与最终验收（本轮未运行，继续未完成）
