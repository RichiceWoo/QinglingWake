# 任务拆分 — Task 18 E2E 运行时可靠性修复 1.0.1

> 实施顺序：能力调研 → 结构化沙盒执行 → 故障状态机 → mailbox/dispatch/lifecycle → 契约与 E2E 驱动 → 回归与真实 E2E → 回写父需求。
> **Git**：当前不提交或推送，除非用户另行明确要求；本文件勾选表示已有真实证据证明逻辑完成。

## 前置条件

- [x] 两次失败证据已保留，且未启动第三次 E2E。
- [x] 当前显式模型配置为 `qwen3-max`，免费模型选择关闭。
- [x] 新聊天开始后检查 `git status`，保留现有未提交修改，不得 reset 或覆盖。
- [x] 确认没有遗留 Maven/Java E2E 进程；真实测试前确认 Docker 沙盒健康和 8029 可达。
- [x] 不读取或打印 `~/.config/qingling-team/e2e.env` 内容，只允许 `source` 后检查必需变量是否非空。

## Task 1: 审计 AIO-Sandbox 执行与超时语义

- **目标**：用源码、依赖 API、真实 MCP schema 和最小无模型探针确认 `sandbox_execute_bash` 是否支持 execution ID、状态查询、取消、并发隔离和连接重建。
- **涉及文件**:
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/McpSandboxConfiguration.java` — 读取现有注册与包装逻辑
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/ProjectTestAgentTool.java` — 读取现有委托调用
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/contract/RealSandboxToolContractIT.java` — 增加必要的调研型契约测试
- **依赖**: 无
- **验收标准**:
  - 在 `log.md` 记录真实工具 schema、能力边界和选定实现路径。
  - 若原生不支持结果查询，明确采用受控无状态 Test Runner，不能假造 execution ID 能力。
  - 探针不得调用模型。
- **验证命令**: `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify`

- [x] 完成

## Task 2: 实现结构化、可恢复的项目测试执行器

- **目标**：使每次项目测试具有稳定 execution ID、明确终态、进程级超时和结果查询；区分测试失败与基础设施失败。
- **涉及文件**:
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/ProjectTestAgentTool.java` — 重构结构化调用与返回
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/McpSandboxConfiguration.java` — 增加健康状态、熔断与受控重建，或接入新执行适配器
  - `qingling-team-infra/src/test/java/cn/org/chris/wake/infra/agentscope/ProjectTestAgentToolTest.java` — 成功、测试失败、超时、响应丢失、脱敏测试
  - 按 Task 1 结论新增必要的 domain/infra 执行模型和适配器文件
- **关键契约**:

```text
project_id -> execution_id + status + exit_code + pytest_collected
           + pytest_passed + coverage_percent + failure_type
           + retryable + stdout/stderr(受限长度、脱敏)
```

- **依赖**: Task 1
- **验收标准**:
  - 同一项目测试串行执行，不同项目按明确策略隔离。
  - 超时实际终止容器进程组，不遗留 pytest/pip。
  - 传输断开可查询原 execution ID；不能查询时不得盲重试。
  - API Key、Header 和连接秘密不进入结果或日志。
- **验证命令**: `mvn -pl qingling-team-infra -am -Dtest=ProjectTestAgentToolTest,McpSandboxConfigurationTest test`

- [x] 完成

## Task 3: 增加 MCP 熔断、重建和有限重试

- **目标**：首次连接故障后停止向失效会话投递，并为幂等测试执行完成一次受控恢复；连续失败后明确阻断。
- **涉及文件**:
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/McpSandboxConfiguration.java`
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/TeamAgentFactory.java`
  - 对应 infra 测试
- **依赖**: Task 2
- **验收标准**:
  - `CLOSED → OPEN → HALF_OPEN → CLOSED` 或等价状态可测试。
  - OPEN 时新调用快速失败，不再等待完整 MCP 超时。
  - 恢复探针成功才重新开放。
  - 自动恢复最多一次，第二次失败返回明确不可重试结果。
- **验证命令**: `mvn -pl qingling-team-infra -am test`

- [x] 完成

## Task 4: 将工具故障接入 Java 工作流状态机

- **目标**：工具基础设施失败由 Java 记录 incident、保留当前阶段、恢复原 Owner；重试耗尽后进入 `BLOCKED`。
- **涉及文件**:
  - `qingling-team-domain/src/main/java/cn/org/chris/wake/domain/workflow/WorkflowSignal.java` — 增加或归并运行时故障信号
  - `qingling-team-domain/src/main/java/cn/org/chris/wake/domain/workflow/WorkflowStateMachine.java` — 同阶段恢复和阻断规则
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/agentscope/tool/DeterministicWorkflowTools.java` — 只暴露必要的确定性协议
  - `qingling-team-app`/`qingling-team-starter` 中 Agent/tool 边界 — 自动上报，不依赖模型主动发邮件
  - 对应状态机、工具和编排测试
- **依赖**: Task 2、Task 3
- **验收标准**:
  - 业务 pytest 失败仍交给 RD 修复，不误判为基础设施 incident。
  - 可恢复工具故障不推进阶段且只产生一个恢复任务。
  - 同类第二次失败进入 `BLOCKED`，不能进入 QA/交付。
  - 恢复任务带幂等键，重复事件不重复分派。
- **验证命令**: `mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra -am test`

- [x] 完成

## Task 5: 接入 mailbox 租约 watchdog

- **目标**：把已有 `resetStale` 接入正式运行时，并避免回收仍有活跃 dispatch 的邮件。
- **涉及文件**:
  - `qingling-team-domain/src/main/java/cn/org/chris/wake/domain/mailbox/MailboxService.java`
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/persistence/mailbox/FileMailboxRepository.java`
  - `qingling-team-app` 或 `qingling-team-starter` watchdog 编排
  - mailbox、并发和 runtime 测试
- **依赖**: Task 4
- **验收标准**:
  - 超时且无活跃 dispatch 的 `in_progress` 邮件恢复为 `unread`。
  - 活跃任务不得被误回收。
  - 重复 watchdog tick 不产生重复 wake。
  - 旧格式 mailbox 文件仍可读取。
- **验证命令**: `mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra,qingling-team-starter -am test`

- [x] 完成

## Task 6: 修正 dispatch 与运行时关闭顺序

- **目标**：shutdown 先停止任务生产，再有限排空和取消消费者，避免失败后继续产生 heartbeat/工具调用。
- **涉及文件**:
  - `qingling-team-app/src/main/java/cn/org/chris/wake/app/runner/SerialDispatchRegistry.java` — 停止接收、活动快照、有限取消
  - `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/runtime/RuntimeLifecycle.java` — Listener/Cron/dispatch/Agent 正确关闭顺序
  - 对应 app/starter 测试
- **依赖**: Task 3
- **验收标准**:
  - shutdown 开始后新 submit 被明确拒绝。
  - Cron 在 awaitDrained 前停止，且不再创建一次性 wake。
  - 超时后执行有限取消并继续关闭 MCP/Agent，不无限等待。
  - 单测证明 drain 期间没有新增 dispatch。
- **验证命令**: `mvn -pl qingling-team-app,qingling-team-starter -am test`

- [x] 完成

## Task 7: 强化零模型真实工具契约和 E2E 失败诊断

- **目标**：昂贵 E2E 前验证连续工具可靠性；E2E 发生真实终态故障时快速失败并保存证据。
- **涉及文件**:
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/contract/RealSandboxToolContractIT.java`
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/e2e/RealTeamE2EDriver.java`
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/e2e/RealTeamE2EIT.java` 或对应场景入口
  - `qingling-team/E2E.md`
- **依赖**: Task 2～Task 6
- **验收标准**:
  - 零模型契约连续完成至少 10 次无副作用命令和 5 次结构化项目测试。
  - 覆盖一次受控响应断开/连接失效后的结果找回或重建。
  - 验证没有孤儿 pytest/pip/tmux 子进程。
  - E2E 轮询同时检查 incident、MCP、mailbox、dispatch；终态故障不再伪装成下游文件超时。
  - 成功和失败都保存脱敏 summary/model-selection/事件/邮件/Session/MCP 证据。
- **验证命令**:
  - `mvn verify`
  - `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify`

- [x] 完成

## Task 8: 运行真实 Task 18 E2E

- **目标**：从干净场景目录依次完成四条真实百炼 + AgentScope + Docker AIO-Sandbox 场景。
- **依赖**: Task 7；默认 `mvn verify` 和零模型压力契约必须先通过
- **执行顺序**:
  1. `happy-path`
  2. `checkpoint-revise`
  3. `qa-defect-rd-fix`
  4. `code-fail-recovery`
- **验收标准**:
  - 每条场景 Failsafe 成功并生成 `summary.json`。
  - `model-selection.json` 显示显式模型 `qwen3-max`，免费模型选择关闭。
  - 真实模型、真实 AgentScope、真实 Docker 沙盒；不 mock、不 skip、不放宽断言。
  - 任一场景连续两次干净失败即暂停，不启动第三次。
  - 业务排序断言保持“最新在前”，不得删除或弱化。
- **验证命令**:
  - `mvn -Pe2e -De2e.scenario=happy-path verify`
  - `mvn -Pe2e -De2e.scenario=checkpoint-revise verify`
  - `mvn -Pe2e -De2e.scenario=qa-defect-rd-fix verify`
  - `mvn -Pe2e -De2e.scenario=code-fail-recovery verify`

- **当前状态（2026-10-09）**：用户确认后已启动一轮干净 `happy-path`，真实 `qwen3-max`、AgentScope 与 Docker AIO-Sandbox 均已参与。RD 完成 13/13 pytest、覆盖率 94%，但 AgentScope 将 `send_mail.content` 实际序列化为字符串，而 `TaskCompletionEvidenceGate` 只接受 `Map`，导致所有代码阶段 `task_done` 被拒绝；最终 34 分 35 秒后等待 `qa/test_plan.md` 超时。证据位于 `target/e2e-evidence/happy-path`，结束后活动 shell=0 且 pytest/pip/tmux=0。
- **后续场景状态**：因 `happy-path` 失败，未启动 `checkpoint-revise`、`qa-defect-rd-fix`、`code-fail-recovery`；Task 8 保持未完成，需先修复工具 schema/门禁类型契约。
- **修复状态（2026-10-09）**：`CommonTeamTools` 已在 Java 边界把 task_done 的 JSON 对象字符串受控解析为 Map，并使用同一对象执行门禁与邮箱持久化；门禁拒绝由 Java 写入保留事件 `completion_rejected`。Driver 在同阶段同 Owner 连续拒绝至少两次、无活跃 dispatch 且没有更新成功回报时直接失败。定向 18 项、默认 `mvn verify` 210 项和零模型真实 Docker 1 IT 均通过；按用户要求未启动新的 E2E，等待再次确认。

- [ ] 完成

## Task 9: 最终回归、质量审计与父需求回写

- **目标**：验证无回归，并把真实完成情况记录到父需求。
- **涉及文件**:
  - `spec-workflow/changes/task18-e2e-runtime-reliability-1.0.1/{spec.md,tasks.md,test-spec.md,log.md}`
  - `spec-workflow/changes/python-to-java-cola-migration/spec.md`
  - `spec-workflow/changes/python-to-java-cola-migration/test-spec.md`
  - `spec-workflow/changes/python-to-java-cola-migration/log.md`
  - `spec-workflow/changes/python-to-java-cola-migration/tasks.md`
- **依赖**: Task 8
- **验收标准**:
  - 再运行一次完整 `mvn verify`。
  - `git diff --check` 通过。
  - 所有新增/修改源码声明符合 `chinese-code-comments` skill。
  - 本需求四份文档记录实际文件、命令和结果，不伪造通过数。
  - 只有四条真实 E2E 全部通过，才把父 Task 18 标记完成；否则只回写失败诊断并保持未完成。
  - 未经用户明确要求，不 commit、不 push。
- **验证命令**: `mvn verify && git diff --check`

- [ ] 完成

---

## 汇总（/apply 全部完成后填写）

- **变更摘要**：待填写
- **总文件数**：待填写
- **Spec-Plan 偏差记录**：待填写
- **遗留问题**：待填写
