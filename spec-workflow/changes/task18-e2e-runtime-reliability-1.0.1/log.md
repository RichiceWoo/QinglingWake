# 变更日志 — Task 18 E2E 运行时可靠性修复 1.0.1

> 记录决策、踩坑与知识发现；修复完成后将结论同步到父需求 `python-to-java-cola-migration`。

## 时间线

| 时间 | 阶段 | 事件 | 备注 |
|------|------|------|------|
| 2026-10-07 | diagnose | 第一次干净 `happy-path` 失败 | RD 的 Python 相对导入导致 pytest 收集失败；随后沙盒命令连续 5 分钟超时，并出现百炼 TLS 握手异常；未进入 QA |
| 2026-10-07 | diagnose | AIO-Sandbox 完整重建后零模型工具契约通过 | 说明启动时 Docker、MCP 注册、目录挂载和单次工具调用正常，但不能证明连续调用耐久性 |
| 2026-10-07 | diagnose | 第二次干净 `happy-path` 失败 | RD 将 12 项测试从 8 失败修到 1 失败；第三次 `run_project_tests` 和后续简单 Shell 超时，容器内无 pytest/pip 子进程；最终澄清请求产生过晚，未进入 QA |
| 2026-10-07 | pause | 达到两次失败上限 | 不启动第三次真实 E2E，先修 MCP/运行时可靠性 |
| 2026-10-07 | propose | 创建 1.0.1 独立修复需求 | 新聊天窗口实施；当前窗口不修改源码、不提交、不推送 |
| 2026-10-08 | research | 完成 Task 1 真实 MCP schema 与执行能力审计 | AIO-Sandbox `Sandbox MCP Tools 2.14.7` 的 `sandbox_execute_bash` schema 只有 `cmd/cwd/new_session/timeout/preserve_symlinks/truncate`；task-augmented 调用被服务端明确拒绝，直接调用只返回 `status/output/exit_code/cwd`。顶层 tasks API 不能为该工具提供 execution ID、查询或取消，因此采用受控无状态 Test Runner；探针全程未调用模型 |
| 2026-10-08 | apply/verify | 完成 Task 2 结构化 Docker Test Runner | Java 只接受 project ID 并生成 UUID；Docker `/workspace/.qingling-test-runs/{executionId}` 保存状态、PID、退出码和受限输出；固定目录运行 pytest，`timeout` 终止进程组，支持查询/取消和幂等重复启动；19 项定向测试中的相关场景通过 |
| 2026-10-08 | apply/verify | 完成 Task 3 MCP 熔断、连接重建与一次恢复 | 连接错误后 OPEN 并快速拒绝；恢复时关闭旧 AgentScope client、重新注册、执行 `printf` 探针，成功后 CLOSED；项目测试只用同一 execution ID 恢复一次，第二次同类失败返回 `retryable=false`。infra 全回归为 domain 42 + infra 86 项通过 |
| 2026-10-08 | apply/verify | 完成 Task 4 Java 工作流故障处理 | 工具边界自动上报结构化、幂等 incident；第一次基础设施故障保持阶段/Owner 并唤醒原 Owner，第二次同类失败写入 BLOCKED 且确定性推进接口拒绝进入 QA/交付；pytest 业务失败不触发 incident。`mvn -pl qingling-team-domain,qingling-team-app,qingling-team-infra -am test` 通过：domain 43、app 25、infra 87，共 155 项 |
| 2026-10-08 | apply/verify | 完成 Task 5 mailbox lease watchdog | 新增正式后台 watchdog；`SerialDispatchRegistry.executeIfIdle` 用同一接纳锁消除“检查空闲后立即接纳任务”的竞态，只有 stale 且角色路由空闲时才 reset，并只在实际恢复后安排幂等 wake。补充活跃 dispatch、重复 tick 和旧 mailbox 格式测试。相关模块回归通过：domain 43、app 27、infra 88、adapter 13、starter 25，共 196 项 |
| 2026-10-08 | apply/verify | 完成 Task 6 dispatch 与运行时关闭顺序 | `SerialDispatchRegistry` 增加接纳开关、未完成任务快照和取消传播；`WakeScheduler` 可停止创建一次性 wake；`RuntimeLifecycle` 先关闭所有生产者，再停止接纳并有限排空，超时取消后仍关闭 Agent/MCP，且前序关闭异常不会阻止后续清理。`mvn -pl qingling-team-app,qingling-team-starter -am test` 通过：domain 43、app 30、infra 88、adapter 13、starter 27，共 201 项 |
| 2026-10-08 | diagnose/apply | 定位连续项目测试后的真实命令通道故障 | 压力契约复现第三次结构化测试后简单进程探针超时；容器只读检查发现 `sandbox_execute_bash(new_session=true)` 遗留 `openhands-gem-*` tmux 会话。将 Test Runner 改为 `new_session=false` 的无状态调用，并让 ToolUse `content` 与实际受控参数一致；不延长超时、不跳过检查 |
| 2026-10-08 | verify | 完成 Task 7 零模型真实工具压力契约与 E2E 诊断 | E2E 驱动已同时监测 workflow incident、MCP、mailbox、dispatch，成功/失败均在 `finally` 保存脱敏证据。修复后 `mvn verify` 通过 203 项；随后 `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` 连续两轮通过，每轮真实完成 10 次 Shell、5 次结构化 pytest、一次受控断线恢复，NoCallModel 调用数为 0，且无 pending tool 或 pytest/pip/tmux 孤儿进程 |
| 2026-10-08 | e2e/fail | 修复后第 1 次干净 `happy-path` 失败，撤回 Task 7 完成结论 | RD 已完成代码生成并执行过 pytest，但通用 `sandbox_execute_bash` 创建 3 个空闲 tmux pane 后连续超时；AgentScope 外层 5 分钟超时取消没有经过包装器 `onErrorResume`，MCP 健康仍错误显示 CLOSED，最终驱动等待 `qa/test_plan.md` 30 分钟才失败。`finally` 已保存 `target/e2e-evidence/happy-path/{summary.json,failure-summary.json,...}`；未启动其他场景 |
| 2026-10-08 | apply/verify | 补齐通用 Shell 无状态与外层取消熔断 | MCP 包装器现在覆盖模型/默认参数，所有 `sandbox_execute_bash` 委托固定 `new_session=false`，并同步 ToolUse content；`doOnCancel` 在外层 ToolExecutor 超时取消时打开熔断，E2E 可在持续 OPEN 30 秒后报告直接 MCP 原因。新增 2 项单测，`McpSandboxConfigurationTest` 共 10 项通过；待重新执行完整回归和真实 Docker 压力契约 |
| 2026-10-08 | research/apply | 完成 AIO 服务端 shell 关闭能力审计 | 加强的契约首先发现旧进程检查仅看 transport SUCCESS，会漏报命令 exit 1；改为必须出现成功标记并增加客户端关闭后 Docker 诊断。AIO-Sandbox 1.11.0 MCP 未暴露关闭工具，但原生 `DELETE /v1/shell/sessions` 可调用 `cleanup_all_sessions()` 关闭活动 tmux；默认 shell 预热池另保留 1 个 tmux，因此 E2E 专用 Compose 固定 `SHELL_POOL_SIZE=0` |
| 2026-10-08 | apply/verify | 最终完成 Task 7 真实门禁 | `McpSandboxConfiguration.close()` 幂等关闭所有 AgentScope client 后，调用同 authority 上的固定 AIO 原生清理端点；干净镜像的 pytest/pytest-cov 由契约在连接 MCP 前通过不经 Shell 解析的固定 Docker 参数受控预置。`mvn verify` 通过 206 项；最终真实契约在 21.089 秒内完成 10 次 Shell、5 次 pytest 和 1 次连接恢复，NoCallModel=0，结束后活动 shell=0 且 pytest/pip/tmux=0；允许进入 Task 8 |
| 2026-10-08 | e2e/fail | 最新干净 `happy-path` 暴露完成协议静默死锁 | workflow 正确进入 `CODE_IMPLEMENTATION/RD`；RD 修复代码后多次尝试完成回报，但 Manager 邮箱没有代码阶段 `task_done`，而 `msg-db8f391e` 已被单独 `mark_done`。最终 mailbox/dispatch 均空闲、MCP 全 CLOSED 且失败数为 0，Driver 仍等到 `qa/test_plan.md` 超时。直接原因是完成回报门禁与任务关闭可以被拆开，不是 MCP 传输故障 |
| 2026-10-09 | apply | 收紧流水线完成协议并统一角色模板 | `CommonTeamTools` 先校验并成功发送 `task_done`，随后由 Java 自动完成当前全部 in-progress `task_assign`（含同阶段恢复任务）；门禁失败后单独 `mark_done(task_assign)` 被拒绝，成功后的重复调用保持幂等。RD/QA 模板改为原样携带结构化 Test Runner 字段，消除旧示例遗漏 `exit_code`/pytest 数量导致的门禁冲突 |
| 2026-10-09 | apply/verify | Driver 增加工作流静默死锁直报并完成三层回归 | Driver 在事件、mailbox 和 dispatch 全空闲时检查当前阶段任务与完成邮件关联，直接输出 stage、Owner、assignment ID 和 `task_done_missing`/`workflow_transition_missing`。定向 19 项通过；`mvn verify` 通过 208 项；零模型真实 Docker 契约 1 IT 通过，5 个执行均 FINISHED、活动 shell=0、pytest/pip/tmux=0 |
| 2026-10-09 | pause | 等待用户确认新一轮真实 E2E | 按用户要求未 source 真实模型配置、未调用百炼、未启动任何 `-Pe2e` 场景；Task 8、Task 9 与父 Task 18 均保持未完成 |
| 2026-10-09 | e2e/fail | 用户确认后的干净 `happy-path` 失败，未启动后续场景 | 真实 RD 完成 13/13 pytest、覆盖率 94%，结构化 Test Runner 多个 execution ID 均 FINISHED；但实际 AgentScope ToolUse 把 `send_mail.content` 序列化为 JSON 字符串，`TaskCompletionEvidenceGate` 只接受 `Map`，导致所有代码阶段 `task_done` 被拒绝。watchdog 两次恢复 stale lease，阶段与 Owner 保持 `CODE_IMPLEMENTATION/RD`；最终 34 分 35 秒后仍表面报告 `qa/test_plan.md` 超时。证据位于 `target/e2e-evidence/happy-path`，清理后活动 shell=0 且 pytest/pip/tmux=0 |
| 2026-10-09 | apply | 修复真实 task_done 类型契约与拒绝诊断 | `CommonTeamTools` 仅对 task_done 的 JSON 对象字符串进行受控解析，解析成功后以同一 Map 校验证据并写入 mailbox；普通字符串与其他邮件类型不变。`EventService` 增加不可由通用模型工具伪造的 `completion_rejected` 专用入口，拒绝原因限制为 512 字符 |
| 2026-10-09 | apply/verify | Driver 完成协议 fail-fast 与三层非 E2E 验证通过 | Driver 仅在最新阶段之后、同 stage/Owner 连续至少两次门禁拒绝、全部 dispatch 空闲且不存在更新成功 task_done 时报告直接协议原因。定向 18 项、默认 210 项、零模型真实 Docker 1 IT 全部通过；活动 shell=0、pytest/pip/tmux=0。遵守用户要求，未启动任何新 E2E、未调用百炼 |
| 2026-10-09 | pause | 修复完成后等待用户再次确认 E2E | Task 8、Task 9 与父 Task 18 继续保持未完成；下一次真实运行必须从干净 `happy-path` 开始 |

## 技术决策

| 决策 | 选择 | 放弃的方案 | 原因 |
|------|------|------------|------|
| 根因判断 | MCP/沙盒命令通道可靠性为 P0 | 直接更换更昂贵模型 | 第二次运行证明 `qwen3-max` 能连续修复代码，真正阻断发生在工具通道 |
| 测试执行 | execution ID、结构化终态、可查询/取消 | 单次文本调用 + 超时后盲重试 | 避免响应丢失导致重复副作用或结果不确定 |
| 故障恢复 | 熔断、重建、幂等测试最多恢复一次 | heartbeat 持续重试同一连接 | 防止故障放大和长时间关闭 |
| 流程恢复 | Java 状态机自动记录 incident 并唤醒原 Owner | 完全依赖模型发送澄清 | 模型可能正被 pending tool 阻塞，无法可靠自救 |
| shutdown | 先停入站/Cron，再排空/取消 dispatch | drain 后才停 Cron | 当前顺序允许关闭期间继续产生工作 |
| 昂贵 E2E 前置 | 零模型连续真实工具契约 | 仅容器 healthy/TCP/单次 pwd | 最近失败证明浅探针不足以验证命令通道耐久性 |
| Test Runner 路径 | 在 Docker 内以稳定 execution ID 和持久结果文件实现受控无状态适配层 | 把 MCP 顶层 tasks 能力误当作 Bash 工具能力 | `sandbox_execute_bash` 明确不支持 task-augmented execution；适配层只接受已校验 project ID 和固定 pytest 命令，不开放宿主机 Shell |
| 流水线任务完成 | 成功 `task_done` 自动关闭当前流水线任务，直接 `mark_done(task_assign)` 受限 | 继续依赖提示词规定“先发信再 mark_done” | 模型在完成门禁失败后仍可能调用 mark_done；工具层必须保证失败回报不丢失可重试任务 |

## 踩坑记录

| 问题 | 原因 | 解决方案 | 沉淀到 knowledge？ |
|------|------|----------|-------------------|
| 容器 healthy 但 MCP 命令全部超时 | 健康检查只证明服务/端口存活，不证明长生命周期命令通道可用 | 增加连续真实调用、执行状态和连接恢复契约 | 是 |
| 工具超时最终表现为 `qa/test_plan.md` 不存在 | E2E 只按顺序等待文件，没有观察上游 workflow incident | 驱动并行监控状态、工具、mailbox 和 dispatch，终态故障快速失败 | 是 |
| RD 澄清请求产生时运行已经准备关闭 | 工具失败恢复依赖模型完成后续推理和发信 | Java 在工具边界自动记录故障和恢复原 Owner | 是 |
| E2E 失败后关闭耗时很长 | `RuntimeLifecycle` 在停止 Cron 前等待 dispatch 排空 | 先停止任务生产，再有限排空和取消 | 是 |
| 相同创建时间的待办排序不稳定 | 只按 `created_at DESC`，时间戳并列时数据库顺序未定义 | 使用 `created_at DESC, id DESC`，保留真实业务断言 | 否 |
| 连续测试后命令通道再次超时且出现孤儿 tmux | Test Runner 为每次 `sandbox_execute_bash` 传入 `new_session=true`，AIO-Sandbox 创建持久 tmux 会话 | Test Runner 固定使用 `new_session=false`；后台执行仍由 execution ID 状态目录、`setsid` 和重定向标准流独立保活 | 是 |
| 关闭 AgentScope MCP client 后仍残留 tmux | Streamable HTTP client 关闭不会调用 AIO shell manager 清理；默认预热池还会保留基线会话 | 关闭 client 后调用原生 `DELETE /v1/shell/sessions`，并在专用 Compose 禁用预热池 | 是 |
| 干净镜像不含 pytest，在 MCP 超时内安装会混淆命令可靠性验证 | 镜像只提供 pip，首次网络安装超过 60 秒 MCP 预算 | 契约在 MCP client 创建前使用固定 `docker exec` 参数、有界 pip 超时和显式包源预置；RD/QA 探针与测试仍全部走真实 MCP | 是 |
| RD 已修好代码但 workflow 静默停在代码阶段 | 旧 `code_impl` 示例没有完整携带 Test Runner 结构化字段，完成门禁拒绝后 `mark_done` 仍能独立关闭 task_assign | 对齐模板字段；Java 只在 task_done 通过并写入后自动关闭任务；Driver 识别 done-without-task_done 死锁 | 是 |
| 模板要求结构化 `content`，但真实 ToolUse 始终传入字符串 | AgentScope 对 `Object content` 生成/执行的工具契约没有保留 JSON 对象类型，模型和 mailbox 子代理即使按对象提示调用也会得到字符串 | 已在 Java 边界仅解析 task_done 的 JSON 对象字符串，并让门禁与邮箱共享解析后的 Map；连续门禁拒绝写入保留事件并由 Driver 在 dispatch 空闲后直接失败 | 是 |

## 知识发现

- [ ] **MCP 健康分层**：注册成功、端口可达、单次命令成功、连续命令可靠是四个不同层级 → 建议文件 `knowledge/mcp-runtime-reliability.md`
- [ ] **工具调用幂等恢复**：没有 execution ID 时不能安全盲重试 → 建议文件 `knowledge/tool-call-idempotency.md`
- [ ] **Agent 工作流故障边界**：业务判断可交给模型，基础设施故障必须由确定性运行时处理 → 建议文件 `knowledge/agent-runtime-failure-boundary.md`

## Spec-Code 偏差记录

| 偏差点 | Spec 预期 | 实际情况 | 处理方式 |
|--------|-----------|----------|----------|
| 待实现 | 结构化、可恢复的测试执行 | 当前只有一次 MCP 文本调用和普通错误结果 | Task 1～Task 3 修复 |
| Task 4 已修复 | Java 自动处理工具 incident | `ProjectTestAgentTool` 在基础设施失败时调用确定性工作流接口；incident 使用 execution ID 与 attempt 去重 | 保持当前阶段和 Owner，自动唤醒原 Owner；恢复耗尽后 BLOCKED，禁止推进 QA/交付 |
| Task 5 已修复 | stale lease 正式恢复 | `MailboxLeaseWatchdog` 已由 `RuntimeLifecycle` 启停；扫描全部合法项目和角色 | dispatch 接纳与空闲检查互斥；实际恢复后才注册幂等 wake，旧格式 mailbox 保持兼容 |
| Task 6 已修复 | 停止后不再产生任务 | Listener、watchdog、一次性 wake 与 Cron 均先于 dispatch drain 停止 | 新 submit 明确失败；排空超时取消活跃及排队 Future，再关闭 Agent/MCP |
| Task 7 已修复 | E2E 展示直接根因 | 驱动等待产物时同步检查 workflow incident、MCP 熔断状态、mailbox stale/失败和 dispatch；不可恢复故障立即抛出直接原因 | 成功或失败均在 `finally` 保存脱敏证据，不再把 RD 工具故障伪装成 `qa/test_plan.md` 超时 |
| Task 8 前置修复 | E2E 还需识别无 incident 的完成协议死锁 | `task_done` 与 `task_assign` 关闭已由 Java 绑定；Driver 在当前任务 done、完成邮件缺失且无可运行工作时直接失败 | 模板、工具协议和诊断三层同时修复；等待用户确认后再运行真实 E2E |
| Task 8 新失败 | 完成证据应能通过真实工具契约提交 | `send_mail.content` 的真实 ToolUse 类型为字符串，门禁要求 `Map`，造成不可满足契约；当前任务保持 in_progress，因此旧 done-without-task_done 检测也不会快速失败 | 类型契约和连续拒绝 fail-fast 已修复，并通过定向、默认与零模型 Docker 验证；按用户要求不进入 E2E，Task 8/9 与父 Task 18 保持未完成 |
| Task 1 调研结论 | 优先复用原生 execution ID/查询/取消 | 当前 Bash 工具没有这些能力；`new_session` 仅创建隐藏 Shell session，AgentScope 2.0.3 注册器也不暴露可运行时替换的 client handle | Task 2 实现 Docker 内受控无状态 Test Runner；Task 3 由 Java 显式持有、关闭并重建 MCP client |

## 代码质量备忘

- 使用 `chinese-code-comments` skill；所有新增或修改的源码声明必须有相邻、准确的中文注释。
- 不得让 domain/app 暴露 AgentScope、MCP 或 Reactor 具体类型。
- 不得记录 API Key、鉴权 Header、完整环境文件或未经限制的 stdout/stderr。
- 不得用无限重试、扩大超时或跳过测试掩盖不稳定。
- 实现过程中保留工作区现有修改，不得执行破坏性 reset/checkout。
