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
| 2026-10-10 | assess | 完成跨语言迁移与五十余次历史失败的根因复审 | 保留证据表明 Java/COLA 选型不是根因；原 Python Runner 吞没异常、E2E 未强制 QA 报告/delivered 终态等设计是长期放大器；最近失败的直接原因是 Java 迁移后的 AgentScope/MCP 类型、execution 证据和测试目标边界不完整。历史运行并非每次都有可复核证据，因此只对保留证据作确定归因 |
| 2026-10-10 | apply | 加固真实测试证据绑定与角色测试边界 | 新增 `ProjectTestExecutionRegistry`，按 project/role/stage 保存真实终态；RD/QA task_done 逐字段匹配 execution ID，禁止模型自报通过。QA 固定运行 QA 契约，RD 回修同时运行代码与 QA 契约；超时等基础设施终态均进入一次受控恢复，耗尽后保留原失败类型且 `retryable=false` |
| 2026-10-10 | diagnose/apply | 零模型契约发现并修复跨目录同名 pytest 模块冲突 | 首次加强契约因 `code/test_contract.py` 与 `qa/contract_tests/test_contract.py` 的默认导入缓存冲突而失败；固定 pytest `--import-mode=importlib` 后解决，不改测试、不延长超时、不放宽断言 |
| 2026-10-10 | verify | 测试执行与证据边界的非 E2E 门禁通过 | 六类定向测试共 30 项通过；当时 `mvn verify` 通过 212 项；`mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` 通过 1 IT，真实完成 10 次 Shell、5 次结构化测试、一次断线恢复，NoCallModel=0，且无 pending tool 或 pytest/pip/tmux 孤儿进程 |
| 2026-10-10 | diagnose | E2E 启动诊断发现显式模型不可用 | 当前兼容端点对配置的 `qwen3-max` 返回 HTTP 404 `model_not_found`；该结果仅能确定当前密钥、区域、端点与模型组合不可用。诊断在进入业务工作流前人工中止，未启动后续场景，清理后无活动 shell 或 pytest/pip/tmux 进程 |
| 2026-10-10 | apply | 增加 E2E 显式模型 fail-fast 与启动证据 | 新增 `validate-explicit-model` 独立开关，普通启动默认关闭以保持无网络副作用，E2E Driver 强制开启有界 Function Calling 探测；Spring 启动失败也写入脱敏 `summary.json` 和 `failure-summary.json`，不再等待 30 分钟 Agent 超时 |
| 2026-10-10 | verify | 修复后的全部非 E2E 门禁通过 | 模型/启动相关定向 16 项通过；最终 `mvn verify` 通过 215 项；零模型真实 Docker 契约再次通过 1 IT，完成 10 次 Shell、5 次结构化测试与一次断线恢复，无 pending tool 或 pytest/pip/tmux 孤儿进程。遵守用户最新要求，不启动真实 E2E；Task 8、Task 9 与父 Task 18 均保持未完成 |
| 2026-10-11 | diagnose/apply | 补齐连接恢复后取消终态的耗尽迁移 | 首次超时会先取消 Docker 进程；连接重建成功后，同一 execution ID 可能查询到 `CANCELLED`。旧逻辑会直接返回而不写第二次 incident，无法保证 BLOCKED。现将恢复后的所有非 `SUCCEEDED`/`TEST_FAILED` 终态固化为不可重试，并上报 attempt=2 的真实失败分类；取消查询若发现 pytest 已完成，则保留真实业务终态，避免竞态制造虚假超时 |
| 2026-10-11 | verify | 最新非 E2E 回归与真实 Docker 契约通过 | `ProjectTestAgentToolTest,DeterministicWorkflowToolsTest` 16 项通过；`mvn verify` 通过 217 项；零模型真实 Docker 1 IT 再次完成 10 次 Shell、5 次结构化测试和一次断线恢复。AIO 活动会话为 0，未发现 pytest/pip/tmux；未调用百炼、未运行 E2E，Task 8/9 与父 Task 18 继续未完成 |
| 2026-10-11 | e2e/fail | 用户确认后单次干净 `happy-path` 在启动预检快速失败 | 环境配置检查通过后，E2E 专用 Function Calling 探针对显式 `qwen3-max` 收到 HTTP 404；Failsafe 为 1 test、0 failures、1 error、0 skipped。工作流与 AgentScope 角色执行尚未启动，未运行后续三个场景，也未自动重试。`finally` 保存脱敏 `target/e2e-evidence/happy-path/{summary.json,failure-summary.json}`；复核活动 shell=0，pytest/pip/tmux=0。该结果再次确认当前账户/区域/端点/模型组合不可用，Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-11 | probe/pass | `qwen3.8-flash` 最小 Function Calling 探针通过 | 严格只发出一个请求，HTTP 200 并返回预期工具调用；未运行 E2E、未输出凭据。该结果只证明最小 Function Calling 可用，不等价于完整工作流可靠 |
| 2026-10-11 | e2e/fail | `qwen3.8-flash` 单次干净 `happy-path` 在 RD 代码阶段失败 | 仅运行一次，无重试、无后续场景。workflow 正确完成 checkpoint、PM 和 RD 技术设计；进入 `CODE_IMPLEMENTATION/RD` 后，AgentScope `agent_spawn`、`agent_send`、`task_output` 多次达到 5 分钟外层超时，子任务却仍继续写 `code/app` 与 `code/tests`。唯一 Test Runner execution `44068f07-e3ca-470d-bfee-aae24e73eeef` 过早运行并以 `FINISHED/exit_code=5/pytest_collected=0` 结束，task_done 因 execution 归属不匹配被拒绝。运行时没有记录 `tool_execution_failed` 或进入 BLOCKED，Driver 最终仍报告 `code/main.py` 等待 30 分钟超时；Maven 总耗时 44 分 29 秒。脱敏证据位于 `target/e2e-evidence/happy-path`；清理后 AIO session=0，pytest/pip/tmux 与宿主 Maven/Java 均无残留。Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-11 | diagnose/apply | 修复 AgentScope 父子取消、execution 上下文传播和悬挂 dispatch 诊断 | 代码审计确认 `qingling.agent.timeout=30m` 被误用于 `AsyncToolMiddleware` 转后台阈值，超过 AgentScope 固定 5 分钟 ToolExecutor 上限，形成父工具先超时而子任务继续的竞态。新增独立 `async-tool-offload-timeout`（默认 30 秒、最大 4 分钟）；父调用取消、clearSession、close 按 session 取消真实 TaskRepository 非终态子任务并 interrupt 父 Agent；new_mail RuntimeContext 传播 project ID，Test Runner 在工具调用开始冻结 project/role/stage；dispatch 快照记录 active_since，Driver 在失败/取消子任务全部终态而父 dispatch 仍活跃 20 秒后报告直接原因 |
| 2026-10-11 | verify/pause | AgentScope 生命周期修复通过全部非 E2E 门禁 | 联合定向 37 项通过；真实 HarnessAgent/TaskRepository 取消用例所在 `TeamAgentFactoryTest` 4 项通过；最终 `mvn verify` 通过 223 项；零模型真实 Docker 契约 1 IT 通过，完成 10 次 Shell、5 次结构化测试和一次受控断线恢复，无 pending tool 或 pytest/pip/tmux 孤儿进程。未调用百炼、未启动 E2E；Task 8/9 与父 Task 18 保持未完成，等待用户确认下一轮真实 `happy-path` |
| 2026-10-11 | e2e/fail | 修复后单次 `qwen3.8-flash happy-path` 直接识别 AgentScope dispatch 悬挂 | 严格只执行一次，无重试、无后续场景。workflow 完成 checkpoint、PM 产品设计、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`。30 秒 async-tool 卸载真实生效，但后台 `task_output` 自身仍达到 AgentScope 固定 PT5M；两个 `code_impl` 子任务被级联取消为 CANCELLED，父 `team:rd` dispatch 仍 active/pending=1。Driver 在场景 1021 秒时报告直接悬挂原因，Maven 总耗时 17 分 31 秒，不再等待 30 分钟代码/QA 文件超时。无代码产物或 Test Runner execution；`finally` 保存脱敏 `target/e2e-evidence/happy-path`。清理后 AIO session=0，pytest/pip/tmux 与宿主 Maven/Java 均无残留。Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-11 | apply/verify | 修复后台 `task_output` 固定等待与取消子任务后的父 dispatch 悬挂 | 新增 `NonBlockingTaskOutputTool`，保留旧参数但固定委托 `block=false`；`TeamAgentFactory` 在 Harness 构建完成后替换内置同名工具。`AgentScopeAgentGateway` 并行监控当前 session，全部子任务终态且含 FAILED/CANCELLED、父调用超过 2 秒收尾宽限时主动失败并执行一次幂等父子取消。定向 10 项、完整 `mvn verify` 225 项、零模型真实 Docker 1 IT 均通过；未调用百炼、未运行 E2E |
| 2026-10-11 | diagnose/apply/verify | 修复 Task 19 可执行 JAR 对 Failsafe classpath 的覆盖 | Task 19 的无 classifier repackage 把普通 starter JAR 替换为 Boot 布局，Failsafe 因而找不到根路径 `workspace-template/file-manifest.txt`。将 repackage 改为 `exec` classifier 并同步 README 启动命令后，普通 JAR 恢复模板资源，`*-exec.jar` 保持 JarLauncher/Start-Class；`mvn verify` 225 项和零模型真实 Docker 1 IT 均通过 |
| 2026-10-11 | e2e/fail | 最新一次 `qwen3.8-flash happy-path` 在 PM 首次 Shell 调用后快速失败 | 严格只执行一次，无重试、无后续场景。checkpoint 正常批准并迁移到 `PRODUCT_DESIGN/PM`；PM 调用 `sandbox_execute_bash` 时使用宿主机模块目录作为容器 working directory，服务端返回目录不存在。当前 MCP 健康层把该调用参数错误计为基础设施失败并打开 PM client 的 circuit；Driver 在场景 190.2 秒报告 `不可恢复 MCP 故障：circuit 持续 OPEN`，没有伪装成产品或 QA 文件超时。`finally` 保存脱敏 `target/e2e-evidence/happy-path`，summary 显示 dispatch pending=0、AgentScope active=0；外部复核 AIO session=0，宿主和容器均无 pytest/pip/tmux/Maven/Java 残留。Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-12 | apply | 修复 Bash 宿主 cwd 泄漏与 MCP 熔断错误分类 | 生产装配把宿主 workspace 根和容器挂载点注入 MCP 包装层；Bash 缺省 cwd 固定为 `/workspace`，宿主 workspace 子路径及 Maven/AgentScope `user.dir` 映射到容器对应路径，同时重写 ToolUse content。服务端明确的 cwd/参数校验 ERROR 不再增加连接失败计数或打开 circuit，真实连接异常与取消仍保持熔断 |
| 2026-10-12 | verify/pause | cwd 与参数错误分类修复通过三层非 E2E 门禁 | 定向 15 项、完整 `mvn verify` 227 项、零模型真实 Docker 1 IT 全部通过。真实契约 10 次 Shell 均携带宿主 cwd 且实际在 `/workspace` 执行，5 次 pytest 与一次断线恢复通过；结束后 AIO session=0 且无 pytest/pip/tmux/Maven/Java 残留。未调用百炼、未运行 E2E，Task 8/9 与父 Task 18 继续未完成 |
| 2026-10-12 | e2e/fail | cwd 修复后单次 `qwen3.8-flash happy-path` 在 RD 子任务取消恢复链失败 | 前置零模型真实 Docker 1 IT 通过后只运行一次真实场景。workflow 完成 checkpoint、PM 和 RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`，宿主 cwd 已正确归一化，四个 MCP circuit 全程 CLOSED 且失败数为 0。RD 生成应用与测试，但三个 `code_impl` 子任务最终均为 CANCELLED；父 dispatch 能正确终止，mailbox lease 恢复为 unread。10 分钟 heartbeat 的一次自动恢复再次得到同类 CANCELLED，却没有写入 `tool_execution_failed`/BLOCKED，也没有重新领取 RD 任务，Driver 最终仍等待 `qa/test_plan.md` 30 分钟超时。Maven 总耗时 43 分 18 秒；`finally` 保存 `target/e2e-evidence/happy-path`，清理后无 pytest/pip/tmux/Maven/Java 残留。未启动后续场景或第二次 E2E，Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-14 | diagnose/apply | 闭环 RD 子任务取消、incident 恢复和直接诊断 | 审计确认同一逻辑子任务会由一次 `agent_spawn` 和后续 `agent_send` 形成多条 TaskRepository 记录，旧 CANCELLED 记录不能代表最新 continuation；模型主动 `task_cancel` 也缺少来源信息。新增弱引用有界任务语义表与 spawn/send/cancel 工具包装，记录 task kind、取消来源并只以同一 agent 的最新任务作为权威。最新失败/取消由 Gateway 抛出脱敏类型化异常；Runner 将有 project 的 dispatch 失败和可唯一归属的 heartbeat 失败桥接到 Java incident。第一次不同 occurrence 保持 stage/Owner、释放 in_progress assignment 并唤醒原 Owner，第二次进入 BLOCKED，重复 occurrence 幂等；watchdog 跳过 BLOCKED 项目。Driver 增加 Cron 失败 + runtime 空闲快速诊断，并按最新 continuation 判断悬挂；RD 模板限制同一逻辑任务只保留一条 continuation |
| 2026-10-14 | verify/pause | 非 E2E 验证全部通过，按用户要求暂停真实场景 | 定向 56 项通过；`mvn verify` 六模块共 245 tests、0 failure/error/skipped；`mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` 的真实 Docker 1 IT 通过，包含 10 次 Shell、5 次结构化测试、一次受控连接恢复和无 pending/orphan 断言，模型为 NoCallModel。本轮没有加载 E2E profile、没有调用百炼；Task 8/9 与父 Task 18 继续未完成，等待用户确认后再从干净目录运行新的 `happy-path` |
| 2026-10-15 | e2e/fail | 单次 `qwen3.8-flash happy-path` 发现 Test Runner 已完成业务失败被误判为结果不可用 | 仅运行一次。workflow 完成 checkpoint、PM 与 RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；单一 spawn、终态后 continuation、无主动 cancel、watchdog stale lease 恢复及 wake 均真实生效。首个 `run_project_tests` execution `84eab81b-ec29-47ef-9f98-fc3f9246eb03` 在容器状态目录最终为 `FINISHED/exit_code=1`，真实 pytest 为 32 collected、11 passed、21 failed、coverage 72%，但 Java 工具返回 `INFRASTRUCTURE_FAILED/RESULT_UNAVAILABLE/exit_code=null`，把本应交由 RD 修复的业务失败计为基础设施故障，2 秒内依次写入 RECOVERING 和 BLOCKED。Driver 立即报告 `tool=run_project_tests`、execution ID、failure type、stage 和 owner，没有再伪装成 `qa/test_plan.md` 超时。Failsafe 1 failure、0 errors、0 skipped，Maven 29 分 55 秒；脱敏证据保存于 `target/e2e-evidence/happy-path`。未重试、未启动后三个场景；退出后宿主无 Maven/E2E，容器无 pytest/pip/tmux。Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-15 | diagnose/apply | 修复 Test Runner 已完成 `exit_code=1` 的查询与终态协调 | 根因是 status/exit/pytest 指标和最高 32 KiB Base64 stdout/stderr 共用单条 MCP 响应；真实 93 KiB pytest 输出超过通道响应上限后，Java 只能解析部分 stdout，关键 `exit_code=1` 丢失。现先执行固定小型元数据查询，再在已知终态后分别读取每流最多 1 KiB 的脱敏首尾摘录；`FINISHED` 暂缺退出码时标记 FINALIZING 继续轮询，摘录查询失败也保留已知终态，不触发业务结果向基础设施结果回退 |
| 2026-10-15 | verify/pause | 终态协调修复通过三层非 E2E 门禁 | 定向 `McpProjectTestRunnerTest,ProjectTestAgentToolTest` 15 项通过；`mvn verify` 247 项通过；真实 Docker 零模型 1 IT 在 15.45 秒内完成 10 次 Shell、5 次成功测试、一次 100 KiB 大输出业务失败和一次受控连接恢复。大输出执行稳定返回 `TEST_FAILED/PYTEST_FAILURE/exit_code=1`，没有 `RESULT_UNAVAILABLE`，所有 circuit 保持 CLOSED，结束后无 pytest/pip/tmux 孤儿进程。未调用百炼、未运行 E2E；Task 8/9 与父 Task 18 仍未完成 |
| 2026-10-17 | e2e/fail | Test Runner 修复后的单次 `qwen3.8-flash happy-path` 暴露无进展 RUNNING continuation | 严格只执行一次，无重试或后续场景。workflow 正常完成 checkpoint、产品设计和技术设计并进入 `CODE_IMPLEMENTATION/RD`，MCP 全程 CLOSED/0 failures。首个 `code_impl` task `task_99b...` 最终 COMPLETED/delivered；父 RD 在其终态前又登记同 agent/session 的 `task_84b...`，后者真实日志在 00:43:37 后停止，但任务心跳持续刷新 `lastUpdatedAt` 并保持 RUNNING，导致父 dispatch active/pending=1，Java 未生成 incident，Driver 也未判为 hanging_dispatch。最终场景 2468 秒、Maven 41 分 17 秒，以等待 `code/main.py` 30 分钟超时失败。未产生 Test Runner execution。finally 已保存 `target/e2e-evidence/happy-path`；退出后无 Maven/Java、pytest/pip/tmux 进程，Docker healthy，但任务仓库仍遗留 RUNNING/cancelRequested=false/delivered=false 状态。Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-17 | diagnose/apply | 修复 RUNNING 真实进度、continuation 单飞、入口契约与 shutdown 终态 | 审计 AgentScope 2.0.3 后确认 `BackgroundTask.lastCheckedAt` 和 `TaskRecord.lastUpdatedAt` 会被查询/心跳刷新，不能代表业务进展。新增稳定租约指纹（task/record 状态、event seq、confirm 状态、子代理 JSONL 文件数/字节/mtime），同指纹轮询不续租；同 parent user/session/agent 的 spawn/send 通过活跃 TaskRepository 与原子占位单飞，重复调用返回既有 task。Gateway 和 Driver 都按 5 分钟真实静默诊断 `CHILD_STALLED`；RD 入口固定为 `code/main.py` 并拒绝 `code/app/main.py`；关闭时遍历已观察 session，取消并持久化所有非终态 task 为 CANCELLED/FAILED |
| 2026-10-17 | verify/pause | 五项修复通过三层非 E2E 门禁 | 定向 30 + 30 + 10 项全部通过；`mvn verify` 255 项通过；真实 Docker 零模型 1 IT 在 15.25 秒通过，NoCallModel 未调用百炼。`git diff --check` 通过。本轮没有启动 `-Pe2e`；Task 8/9 与父 Task 18 继续未完成，等待用户确认新一轮干净 `happy-path` |
| 2026-10-18 | e2e/fail | 单次 `qwen3.8-flash happy-path` 暴露成功子任务后的父 dispatch 与重复 assignment 协调缺口 | 严格只运行一次，无重试或后续场景。workflow 完成 checkpoint、PM 和 RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；单一 `code_impl` task COMPLETED/delivered，未产生重复 continuation，唯一入口为 `code/main.py`。5 个真实 Test Runner execution 均为 `FINISHED/exit_code=2` 的 pytest 收集业务失败，四路 MCP 始终 CLOSED/0 failures，未生成基础设施 incident。后续 stale lease/heartbeat 留下两条同时 `in_progress` 的 RD assignment，父 `team:rd` dispatch active/pending=1，唯一子任务已成功终态；Driver 的 RUNNING 静默与 FAILED/CANCELLED 诊断均不适用，`hanging_dispatch` 为空，最终仍以等待 `qa/test_plan.md` 30 分钟超时失败。证据跨度 2534 秒、Failsafe 测试 2913 秒、Maven 48 分 42 秒；关闭后 AIO session=0，Docker healthy，宿主与容器无 pytest/pip/tmux/Maven/Java 残留。Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-19 | diagnose/apply | 修复父 dispatch、重复 assignment、watchdog 竞态和测试收集发散 | 父回复等待最新逻辑子任务终态，父 session JSONL 提供独立真实进度并在 5 分钟静默后触发 `PARENT_STALLED`；同 project/stage/from/to 开放 assignment 在文件锁内复用，恢复指令原位刷新，澄清答复不再创建 task assignment。watchdog 同时检查真实父 Agent 活跃状态；Driver 直接报告父停滞和重复 assignment；shutdown drain 从 30 分钟业务 timeout 拆为默认 30 秒。Test Runner 增加 30 秒 collect-only，正式 pytest 限为 240 秒；RD 模板要求测试只引用真实实现符号并优先修复 collection error |
| 2026-10-19 | verify/pause | P0/P1 修复通过全部非 E2E 门禁 | 联合定向 76 项及新增仓储边界 12 项通过；`mvn verify` 263 项通过；真实 Docker 零模型 1 IT 在 15.39 秒通过，使用 NoCallModel，连续 Shell、Test Runner、受控连接恢复和无 pending/orphan 断言全部成功。未调用百炼、未运行 E2E；Task 8/9 与父 Task 18 继续未完成，等待用户确认下一轮干净 `happy-path` |
| 2026-10-20 | e2e/fail | 单次 `qwen3.8-flash happy-path` 暴露 Test Runner 终态竞态与后台 spawn late-arrival | 严格只运行一次，无重试或后续场景。workflow 完成 checkpoint、PM、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；初始 `code_impl` COMPLETED/delivered，入口契约仅有 `code/main.py`。6 个 Docker execution 均为 `FINISHED/exit_code=1`，最佳为 27 collected、24 passed、97% coverage，工具向模型正确返回 `TEST_FAILED/PYTEST_FAILURE`。但 `RUNNING` runner 刚退出、原子 `exit_code` 尚未可见时被判为 ORPHANED/`RESULT_UNAVAILABLE`，同一 execution 恢复为真实业务终态后仍遗留 RECOVERING incident；不同 execution 又各自从 attempt 1 计数。两个先前卸载的后台 spawn 延迟完成并绕过单飞，形成两个同 session 的并发 continuation。Driver 未直接报告这两类故障，最终退化为 `qa/test_plan.md` 30 分钟超时。Failsafe 1 failure、0 errors、0 skipped，Maven 46 分 04 秒；证据保存于 `target/e2e-evidence/happy-path`。shutdown 后遗留任务为 CANCELLED/FAILED，宿主与容器无 pytest/pip/tmux/Maven/Java 残留；Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-21 | diagnose/apply | 修复终态竞态、恢复预算和后台 continuation 单飞 | runner 退出后先进入 5 秒 FINALIZING 宽限，等待原子 `exit_code`；恢复到 SUCCESS/TEST_FAILED 时不再写临时 RECOVERING。工具故障预算改按 stage/Owner/tool/稳定 failure fingerprint 跨 execution 共享，第二次同类失败直接 BLOCKED。取消/offload 的 spawn/send 保留 6 分钟有界 reservation，延迟任务可见后返回原 taskId；若仍形成重复未终态 continuation，Gateway 以 `DUPLICATE_CHILD_TASK` 终止父 dispatch。Driver 新增重复 continuation 和重复 RECOVERING execution 的直接诊断 |
| 2026-10-21 | verify/pause | 本轮全部非 E2E 门禁通过并按要求暂停 | 扩展定向 92 项通过；`mvn verify` 通过 270 项；零模型真实 Docker 1 IT 通过，包含连续 Shell、连续 Test Runner、受控断连恢复和 pending/orphan 清理断言。`RealSandboxToolContractIT` 使用 NoCallModel，未调用百炼。本轮未加载 `-Pe2e`；Task 8/9 与父 Task 18 保持未完成，等待用户确认下一轮真实 `happy-path` |
| 2026-10-22 | gate/pass | 新一轮真实 E2E 前置门禁通过 | 脱敏配置校验确认 provider=dashscope、显式模型 `qwen3.8-flash`、免费选择关闭且 Key 非空；Docker AIO-Sandbox healthy。`mvn verify` 在 14.878 秒通过；零模型真实 Docker `RealSandboxToolContractIT` 1 IT 在 29.927 秒通过 |
| 2026-10-22 | e2e/fail | 单次 `qwen3.8-flash happy-path` 暴露 RUNNING 快速诊断与工具超时竞态 | 严格只运行一次，无重试或后续场景。流程完成 checkpoint、PM、RD/QA 插入评审、RD 技术设计并进入 `CODE_IMPLEMENTATION/RD`；参数错误未打开 MCP circuit，RD 技术设计回合耗尽后同 session 自动续跑并完成 `task_done`。代码阶段唯一 `code_impl` task 在 21:17:45 后无真实 session/产物进展，内部 `sandbox_execute_bash` 于 5 分钟后达到 AgentScope 工具超时；Driver 没有先触发 `CHILD_STALLED`，而以 `code/main.py` 的 30 分钟等待超时失败，真实工具超时随后在 shutdown 显现。Failsafe 1 failure，Maven 48 分 59 秒；未生成代码入口或 Test Runner execution。finally 保存 `target/e2e-evidence/happy-path`；子任务落盘 CANCELLED，RD assignment 回到 unread，AIO shell session=0，宿主/容器无 pytest/pip/tmux/Maven/E2E 残留。Task 8/9 与父 Task 18 保持未完成 |
| 2026-10-23 | apply | 修复 Shell 固定超时与按角色 MCP 故障桥接 | `sandbox_execute_bash` 缺省和最大命令 timeout 固定为 60 秒，Java 传输层增加 5 秒固定宽限；参数错误仍保持 circuit CLOSED。MCP 工具绑定父角色，Gateway 每个父回合最多执行一次连接重建和无副作用探针，不重放原命令；重建完成或失败后均把未知原调用抛为 `MCP_CONNECTION_UNAVAILABLE`，父成功出口用失败计数封住轮询竞态，由 Runner 进入既有 RECOVERING/BLOCKED incident 链。RUNNING/父进度租约收紧为 4 分 30 秒 |
| 2026-10-23 | apply | 修复 Driver 阶段预算与 shutdown 后证据 | Driver 在等待技术、代码、QA 计划和报告前先确认对应 workflow 阶段迁移，防止提前生成的上游文件消耗下游 30 分钟预算；真实 E2E finally 先关闭 Spring/Agent/MCP 并持久化任务终态，再保存证据，summary 新增最终 dispatch、活跃 Agent 和关闭前 MCP 健康快照 |
| 2026-10-23 | verify/pause | 全部非 E2E 门禁通过，等待用户确认下一轮 | 最终定向 46 项通过；`mvn verify` 288 项在 17.804 秒通过；真实 Docker `RealSandboxToolContractIT` 1 IT 在 15.56 秒通过（Maven 31.941 秒），RD/QA 首个 Shell 调用均省略 timeout 并成功，NoCallModel 未调用百炼，结束后无 pytest/pip/tmux 孤儿进程。未运行真实 E2E，Task 8/9 与父 Task 18 保持未完成 |

## 技术决策

| 决策 | 选择 | 放弃的方案 | 原因 |
|------|------|------------|------|
| 根因判断 | MCP/沙盒命令通道可靠性为 P0 | 直接更换更昂贵模型 | 第二次运行证明 `qwen3-max` 能连续修复代码，真正阻断发生在工具通道 |
| 测试执行 | execution ID、结构化终态、可查询/取消 | 单次文本调用 + 超时后盲重试 | 避免响应丢失导致重复副作用或结果不确定 |
| 故障恢复 | 熔断、重建、幂等测试最多恢复一次 | heartbeat 持续重试同一连接 | 防止故障放大和长时间关闭 |
| 流程恢复 | Java 状态机自动记录 incident 并唤醒原 Owner | 完全依赖模型发送澄清 | 模型可能正被 pending tool 阻塞，无法可靠自救 |
| AgentScope 异步工具 | 独立 30 秒转后台阈值，配置上限 4 分钟 | 复用 30 分钟业务 Agent 等待时长 | 转后台必须早于 AgentScope 固定 5 分钟 ToolExecutor 上限，否则父工具先超时、子任务仍继续 |
| shutdown | 先停入站/Cron，再排空/取消 dispatch | drain 后才停 Cron | 当前顺序允许关闭期间继续产生工作 |
| 昂贵 E2E 前置 | 零模型连续真实工具契约 | 仅容器 healthy/TCP/单次 pwd | 最近失败证明浅探针不足以验证命令通道耐久性 |
| Test Runner 路径 | 在 Docker 内以稳定 execution ID 和持久结果文件实现受控无状态适配层 | 把 MCP 顶层 tasks 能力误当作 Bash 工具能力 | `sandbox_execute_bash` 明确不支持 task-augmented execution；适配层只接受已校验 project ID 和固定 pytest 命令，不开放宿主机 Shell |
| Test Runner 终态查询 | 元数据先行，stdout/stderr 分流读取受限首尾摘录 | 在同一 MCP 响应中携带终态字段和大段 Base64 输出 | 终态分类必须以小型持久元数据为准；日志截断或读取失败不得把已知 pytest 业务失败改写成基础设施 incident |
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
| 父 Agent 工具超时后子 Agent 仍继续写共享目录 | 30 分钟业务等待时长曾被误作异步工具转后台阈值；修复后进一步确认，被卸载的 `task_output` 自身仍受固定 PT5M 限制，子任务取消也不会自动结束父 dispatch | 已完成 30 秒卸载、按 session 取消子任务和上下文传播；现又将 `task_output` 替换为非阻塞查询，并由 Gateway 在全部子任务异常终态后主动结束父调用和释放 dispatch | 是 |
| `FINISHED/exit_code=1` 被报为 `RESULT_UNAVAILABLE` | 查询把终态元数据放在大段 Base64 输出的同一 MCP 响应中；通道截断后只剩部分 pytest 文本，退出码不可见 | 元数据与输出分离；先取得 status/exit/pytest/coverage，再按 stdout/stderr 分别读取 1 KiB 首尾摘录，并以 100 KiB 真实失败输出加入零模型契约 | 是 |

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
| Task 8 `qwen3.8-flash` 新失败 | Agent 子任务工具超时应进入统一父子生命周期，并由 Driver 报告直接原因 | 第一轮父工具超时后子任务仍写文件；修复后 E2E 证明卸载和子任务取消生效，但后台 `task_output` 仍 PT5M 超时，两个子任务 CANCELLED 后父 RD dispatch 继续 active | 已用同名非阻塞工具替换内置等待，并在 Java Gateway 中监控异常子任务终态、主动失败父调用；定向、默认和零模型 Docker 验证通过。本轮不运行 E2E，等待用户确认 |
| Task 8 Test Runner 终态误判 | `FINISHED/exit_code=1` 应作为不可重试 pytest 业务失败返回 RD | 真实 93 KiB 输出导致单条 MCP 查询响应截断，Java 丢失退出码并错误进入 RESULT_UNAVAILABLE 恢复/BLOCKED | 已拆分元数据和输出查询，FINALIZING 竞态继续轮询；定向、247 项默认回归及含 100 KiB 失败输出的真实 Docker 契约均通过。本轮不运行 E2E，等待用户确认 |
| Task 8 无进展 continuation 悬挂 | 同一逻辑子代理不能在旧 task 未终态时产生重复 continuation；RUNNING 必须有真实进度租约，shutdown 必须落盘终态 | 2026-10-17 真实 E2E 中首个 task 后续完成，但父 RD 已登记同 agent/session 的第二个 task；第二个 task 日志/产物长期无进展，却因心跳刷新一直 RUNNING。Driver 只识别“子任务已终态但父 dispatch 活跃”，因此退化为 30 分钟文件超时；shutdown 后文件任务仓库仍为 RUNNING | 已修复：稳定状态/事件/JSONL 元数据变化才续租；同 agent/session 以任务仓库和原子占位单飞；Gateway/Driver 对 5 分钟静默直接失败；shutdown 遍历已观察 session 并把遗留任务落成 CANCELLED/FAILED。定向、默认回归与零模型真实 Docker 契约均通过，待真实 E2E 复核 |
| Task 8 成功子任务后的父 dispatch/assignment 悬挂 | 子任务成功并 delivered 后父 dispatch 应及时终止或推进；stale lease 恢复不得同时留下多条 in-progress assignment；Driver 应报告直接原因 | 2026-10-18 真实 E2E 中单一子任务已 COMPLETED/delivered，5 次 Test Runner 业务失败也均正常返回，但运行后段留下两条 RD `in_progress` assignment，父 dispatch active/pending=1。由于没有 RUNNING、FAILED 或 CANCELLED 子任务，现有快速诊断不触发，最终再次退化为 QA 文件 30 分钟超时；shutdown 排空还复用了 30 分钟 Agent 业务超时 | 已修复并通过非 E2E 门禁：父回复等待子任务终态，父 session 独立真实进度租约产生 `PARENT_STALLED`；同 stage/Owner 开放 assignment 文件锁内复用，watchdog 检查真实父 Agent；Driver 直报父停滞/重复 assignment；shutdown drain 独立为 30 秒。待真实 E2E 复核 |
| Task 8 恢复竞态与后台 spawn late-arrival | pytest 进程刚退出但 `exit_code` 尚未可见时应继续 FINALIZING；恢复取得业务终态后不得保留基础设施 incident；同 parent/session/agent 任意时刻最多一个非终态 continuation | 2026-10-20 真实 E2E 中多个 execution 最终均为 `FINISHED/exit_code=1` 且工具正确返回 `TEST_FAILED`，但中间 ORPHANED 快照提前写入 3 条 `RESULT_UNAVAILABLE/RECOVERING`；每个 execution ID 独立 attempt=1。两个已卸载的 `agent_spawn` 又在稍后同时落地，绕过调用入口的单飞检查，Driver 未识别两个同 agent/session 的 RUNNING 任务，最终退化为 QA 文件超时 | 已修复并通过非 E2E 门禁：runner 退出后给予 5 秒原子落盘宽限；只有恢复仍失败才写 RECOVERING/BLOCKED；稳定指纹跨 execution 共享一次预算；取消/offload 保留有界单飞 reservation；Gateway/Driver 对重复未终态任务与重复 RECOVERING 直接失败。扩展定向 92 项、默认 270 项和零模型真实 Docker 1 IT 均通过，待真实 E2E 复核 |
| Task 8 RUNNING 快速诊断与工具超时竞态 | 子任务真实进度停止后应由 Gateway/Driver 直接报告 `CHILD_STALLED`，不得继续等产物总超时；工具超时应先进入 incident/父 dispatch 终态 | 2026-10-22 真实 E2E 中唯一 `code_impl` 的 JSONL/产物在 21:17:45 后停止变化，但 TaskRecord 的运行时更新时间继续刷新；内部 `sandbox_execute_bash` 最终达到 `PT5M`。Driver 快照已经包含 `last_progress_at=21:17:45`，却先以 `file:code/main.py / PT30M` 失败；工具超时在 finally/shutdown 才输出，summary 当时仍记录 MCP CLOSED/0 failures | 已修复并通过非 E2E 门禁：Shell 命令/传输最多 60+5 秒；首次断连按角色重建且不重放，原调用无论恢复结果都进入类型化父 dispatch 失败，并以失败计数封住父回复竞态；真实进度租约收紧到 4 分 30 秒；Driver 从实际 workflow 阶段迁移开始计算产物预算，shutdown 后保存最终健康快照。定向、默认回归和真实 Docker 契约通过，待真实 E2E 复核 |
| Task 1 调研结论 | 优先复用原生 execution ID/查询/取消 | 当前 Bash 工具没有这些能力；`new_session` 仅创建隐藏 Shell session，AgentScope 2.0.3 注册器也不暴露可运行时替换的 client handle | Task 2 实现 Docker 内受控无状态 Test Runner；Task 3 由 Java 显式持有、关闭并重建 MCP client |

## 代码质量备忘

- 使用 `chinese-code-comments` skill；所有新增或修改的源码声明必须有相邻、准确的中文注释。
- 不得让 domain/app 暴露 AgentScope、MCP 或 Reactor 具体类型。
- 不得记录 API Key、鉴权 Header、完整环境文件或未经限制的 stdout/stderr。
- 不得用无限重试、扩大超时或跳过测试掩盖不稳定。
- 实现过程中保留工作区现有修改，不得执行破坏性 reset/checkout。

## 2026-10-24 缺陷 1～3 修复记录

- **缺陷 1**：公开 Gateway 的 4 分 30 秒父子进度租约，Driver 阈值统一派生为租约加 60 秒；只有当前 workflow execution ID 和预期故障指纹均匹配的 `agentscope_dispatch` incident 仍为 attempt 1、retryable、`RECOVERING` 时才不报告终态悬挂，旧阶段/不同指纹 incident 不得续期，`BLOCKED` 或预算耗尽后恢复快速失败。
- **缺陷 2**：新增只保存固定角色活动时间戳的 `ModelActivityRegistry` 与每角色 `ActivityTrackingModel`。角色 Agent 内部包装底层模型，在订阅和每个流式分片刷新时间并委托全部模型元数据；Gateway 与 Factory 共享注册表，停滞判定和诊断共用同一父进度方法，并以“该角色只有一个活跃父回合”为归因守卫。同角色并发结束时封存不可归因活动截止时间，避免旧分片给剩余 session 续租。Spring `Model` Bean 未被包装。
- **缺陷 3**：停滞宽限复查由 nullable `map` 改为 `flatMap` + `Mono.justOrEmpty`，故障自行消失时继续执行；MCP 连接故障保持独立路径。搜索 Gateway 与 Runner 未发现其他同型 nullable Reactor 映射。
- **验证**：定向套件先连续两次各通过 61 项，精确 incident 匹配补强后通过 62 项；最终 `mvn verify` 通过 299 项（15.036 秒）；零模型真实 Docker `RealSandboxToolContractIT` 通过 1 IT（15.52 秒，Maven 34.208 秒），NoCallModel 未调用百炼，连续工具与孤儿进程断言保持通过；最终 `git diff --check` 通过。
- **范围**：按用户要求未运行真实 E2E；当时尚未处理后续确认的跨角色 Bash 会话争用与恢复探针缺陷；Task 8、Task 9 和父需求 Task 18 均保持未完成。

## 2026-10-24 缺陷 4～5 修复记录

- **服务端语义审计**：直接读取真实 AIO-Sandbox 1.11.0 容器源码，确认 `app/mcp/shell.py` 的 `_default_session_id` 为模块级全局状态，`new_session=false` 会让所有 MCP client/Owner 复用同一个 shell；服务端最多允许 10 个会话。当前 Java 运行路径不存在此前假设的按 Owner 空闲保活，该假设及其修法正式废弃。
- **缺陷 4**：在 `McpSandboxConfiguration` 配置实例内增加所有角色共享的公平单许可量，普通 `sandbox_execute_bash` 与 Test Runner 控制命令从取得许可到 MCP 返回期间全局单飞，继续使用 `new_session=false` 而不并发写入默认 PS1 会话。等待许可时取消不计连接失败；传输已经开始后的取消/连接错误继续熔断。工具说明明确把长时间安装与测试导向异步 `run_project_tests`。
- **缺陷 5**：恢复链在同一 shell 单飞许可内移除旧 client、调用既有 `DELETE /v1/shell/sessions` 清理真实服务端会话、注册新 client，并用 `new_session=true` 执行无副作用 `printf` 探针；探针成功后才切换 delegate 与关闭 circuit，未知结果的原始命令仍不重放。恢复失败记录最近尝试时间、耗时和脱敏的“阶段:异常类型”，Driver 证据同步输出，不保存消息、命令或凭据。
- **否决方案**：曾把所有 Bash 改为 `new_session=true`，真实 Docker 契约约 65 秒后因 10 会话上限、淘汰抖动和 tmux/PS1 失步失败；该实现已完全撤销。最终采用共享默认会话的跨角色单飞，仅在恢复探针创建新会话。
- **验证**：定向 `McpSandboxConfigurationTest,McpProjectTestRunnerTest` 通过 21 项；最终 `mvn verify` 通过 301 项（14.910 秒）；最终零模型真实 Docker `RealSandboxToolContractIT` 通过 1 IT（17.58 秒，Maven 34.403 秒），NoCallModel 未调用百炼，连续 10 次 Shell、5 次 Test Runner、受控恢复与 pending/orphan 断言全部通过。
- **范围**：按用户要求未运行真实 E2E；Task 8、Task 9 和父需求 Task 18 均保持未完成。

## 2026-10-24 缺陷 1～5 修复后单次真实 E2E

- **执行约束**：只执行一次干净 `happy-path`，显式模型 `qwen3.8-flash`，真实 DashScope、AgentScope 与 Docker AIO-Sandbox；未自动重试、未启动 checkpoint-revise、qa-defect-rd-fix 或 code-fail-recovery。
- **业务进展**：需求 checkpoint、PM 产品设计、技术设计抽样评审、RD 第 2 轮修订全部完成，状态机进入 `CODE_IMPLEMENTATION/RD`。技术设计长生成跨越旧 4 分 30 秒文件租约后仍健康完成，流式活动信号避免了误杀。
- **测试执行**：前三个 execution 均为 `FINISHED/exit_code=1`，31 collected、21 passed、10 failed、coverage 95%，保持 pytest 业务失败；第四个为 `FINISHED/exit_code=2`，24 collected 后 `test_acceptance_trace.py`、`test_services.py` 导入不存在的 `app` 包导致 2 个收集错误。一次 continuation 因 pending tool 无结果而终态失败，后续 continuation 仍保持单飞。
- **运行时恢复**：RD 在代码阶段产生 attempt 1、retryable、`RECOVERING` 的 `PARENT_STALLED` incident，workflow stage/Owner 未改变，RD assignment 回到 unread。此后 QA 为补交测试设计启动的父 dispatch 丢失 project ID，无法生成项目级 incident；该回合最后进度为 19:30:32Z、无子任务，Driver 到 20:32:52Z 才报告 `team:qa/project_id=""` 悬挂，约 62 分钟延迟，暴露项目无关 dispatch 未进入统一恢复/快速诊断时序。
- **伴随证据**：终止前出现模型 SSE/NDJSON 流被远端关闭、AgentScope 自动重试 2/2 耗尽及 ConversationCompactor 压缩失败；需区分远端模型传输错误与 Java 项目上下文传播/停滞裁决缺口。
- **缺陷 4/5 结果**：四个 Owner 的 MCP circuit 全程 `CLOSED/0 failures`，无恢复尝试；没有 PS1 会话失步、集体 Shell timeout 或永久 OPEN。退出后 AIO-Sandbox healthy、shell session=0，宿主与容器无 pytest/pip/tmux/Maven/Java 残留。
- **终态**：Failsafe 1 test、1 failure、0 errors、0 skipped，测试耗时 7064 秒，Maven 总耗时 1 小时 58 分；证据位于 `target/e2e-evidence/happy-path`。Task 8、Task 9 与父 Task 18 保持未完成。

## 2026-10-24 E2E 后续缺口修复记录（未运行 E2E）

- **项目上下文传播**：`RuntimeIncidentGateway` 提供安全的角色项目解析扩展点，`RoleToolkitFactory` 仅在唯一未 BLOCKED 项目的当前 workflow Owner 与角色匹配时返回 project ID。`Runner` 统一按可信元数据、`new_mail`、唯一 workflow Owner 解析并传播 project ID，团队 heartbeat 与 incident 使用相同结果；归属不唯一时不猜测。
- **停滞诊断**：`AgentScopeAgentGateway` 的判定和证据共用同一父进度快照，分别记录 session 进度、模型活动、有效进度及来源。`RealTeamE2EDriver` 在派生阈值后检测“SerialDispatchRegistry 仍活跃但 Gateway 无对应活动诊断”的 orphan `team:*` dispatch，并直接报告 `active_gateway_diagnostic=none`。
- **pending tool 恢复**：确认 AgentScope 2.0.3 的声明式子代理工厂不继承父 Agent 的 pending-tool recovery 开关。新增 `PendingToolRecoveringStateStore`，读取子代理状态时只为缺失结果的 tool call 追加确定性错误 ToolResult，让后续回合可继续；不重放原工具，不保存敏感正文。
- **唯一产物契约**：`TaskCompletionEvidenceGate` 对 RD、QA 同时拒绝 `code/app/main.py`，并扫描 `code/tests`、`qa/contract_tests` 拒绝 `from app` / `import app`。RD `AGENTS.md` 和 `code_impl` skill 明确 pytest 根目录、`from main import app` 与顶层模块导入规则。
- **验证**：编译门禁通过；定向 71 项通过（5.907 秒）；完整 `mvn verify` 通过 307 项（17.928 秒）；零模型真实 Docker `RealSandboxToolContractIT` 通过 1 IT（17.50 秒，Maven 33.324 秒）；`git diff --check` 通过。
- **范围**：按用户要求没有 source E2E 凭据、没有调用百炼、没有启动真实 E2E；未 commit、未 push，`../tmp.txt` 保持未触碰。Task 8、Task 9 与父需求 Task 18 均保持未完成。

## 2026-10-24 项目上下文与 pending tool 修复后单次真实 E2E

- **执行约束**：只运行一次干净 `happy-path`，DashScope 主/子 Agent 均为 `qwen3.8-flash`，未自动重试、未启动后三个场景，也未放宽断言。
- **正常路径**：checkpoint、PM 产品设计、RD 技术设计均完成；代码只生成 `code/main.py`。真实 Test Runner 共 8 次：一次早期 `exit_code=5`、六次 `exit_code=1`、最后一次 `exit_code=0`，证明 RD 能持续依据业务失败修复。
- **运行时表现**：一次 continuation 的 `agent_send` 达到 AgentScope 固定 `PT5M`，但 assignment 释放后流程继续，没有产生 MCP incident 或永久悬挂；四个 Owner circuit 全程 `CLOSED/0 failures`。
- **完成与评审**：RD 首次 `task_done` 因 content 不是结构化 JSON 被门禁拒绝，第二次重发后进入 PM/QA 双评审。QA 在 09:47:08Z 提交 changes_requested，确认排序实现及对应测试一起违背 id DESC 契约；终止时 PM review 从 09:37:51Z 起仍为 in_progress，但所有 dispatch inactive、AgentScope active 为空。
- **直接失败**：Driver 从更早的阶段等待开始累计 `QA_TEST_DESIGN/PT30M` 绝对预算，代码修复、完成证据重发、双评审和终止前 29 秒的新事件都没有刷新 deadline，最终报“等待真实 E2E 阶段超时”。因此应将“允许整个阶段完成的总预算”与“无真实进展租约”分离，以事件/session/mailbox 的真实推进刷新无进展判断；不得仅扩大超时。
- **结果与清理**：Failsafe 1 failure、0 errors、0 skipped，E2E 2584 秒，Maven 43 分 19 秒。证据位于 `target/e2e-evidence/happy-path`；finally 后 dispatch pending=0、AgentScope active=0、AIO shell session=0，容器无 pytest/pip/tmux，宿主无 E2E Maven/Java 残留。Task 8、Task 9 和父 Task 18 保持未完成。

## 2026-10-25 阶段进度租约与后台任务语义修复（未运行 E2E）

- **父子任务固定超时**：`TaskSemanticsTrackingTool` 对 `agent_spawn`、`agent_send` 强制使用 `timeout_seconds=0`，并同时重建委托参数和 `ToolUseBlock` 输入。模型即使请求 600 秒同步等待，也会从调用开始返回后台 task；Java 仍通过 TaskRepository、单飞注册表和非阻塞 task snapshot 协调后续状态，不再等待到 AgentScope 固定 `PT5M`。
- **阶段等待语义**：`RealTeamE2EDriver` 不再用单一 30 分钟绝对 deadline 判断长阶段。现在保留 90 分钟不可续期硬总预算，并使用晚于 mailbox 正式恢复窗口的约 12 分钟真实无进展租约；事件数、开放邮件状态、dispatch 和 AgentScope 父子进度会刷新租约，Cron heartbeat 与 MCP 健康轮询不会刷新。
- **mailbox 直接诊断**：诊断阈值直接由 `MailboxLeaseWatchdog.DEFAULT_LEASE_TIMEOUT`、`DEFAULT_SCAN_INTERVAL` 和恢复宽限组成。`in_progress` 邮件只有在超出完整窗口且不存在活跃 dispatch、对应项目/Owner Agent 回合或一次性恢复 wake 时，才以 `mailbox 租约悬挂` 直接失败；正常 watchdog 恢复不会再被 Driver 抢跑。
- **验证**：定向 `TaskSemanticsTrackingToolTest,RealTeamE2EDriverTest,MailboxLeaseWatchdogTest` 通过 38 项（3.077 秒）；完整 `mvn verify` 通过 311 项（18.978 秒）；零模型真实 Docker `RealSandboxToolContractIT` 通过 1 IT（17.48 秒，Maven 32.525 秒）；`git diff --check` 通过。
- **范围**：按用户要求没有启动真实 E2E、没有调用百炼、没有 commit 或 push。Task 8、Task 9 与父需求 Task 18 均保持未完成；工作区既有 `CompletableFutureDemo.java` 和 `../tmp.txt` 未触碰。

## 2026-10-25 确定性编排改造非 E2E 门禁完成

- **改造**：专项版本 `agent-workflow-determinism-1.0.0` 已接入 runtime v2 WorkExecution/outbox、Java 完成协调、固定 profile/review、SIMPLE/STANDARD 父角色直做、只读 Todo acceptance 和 Driver v2 直接诊断。
- **真实发现**：首次 Docker 工具契约因 QA 固定验收 fixture 未安装而以 `exit_code=4` 失败；复用公共 `TodoAcceptanceFixture` 并为契约工作区安装只读验收和最小正确应用后才重跑通过。
- **验证**：相关模块 165 项通过；最终 `mvn verify` 394 项通过（18.805s）；修复后 `RealSandboxToolContractIT` 1 IT 通过（37.157s）；`CoordinatedWorkflowContractIT` 1 IT 通过（27.483s）；`git diff --check` 与 orphan 复核通过。
- **门禁**：未 source E2E 凭据、未调用真实模型、未运行真实 E2E。Task 8、Task 9 与父 Task 18 继续未完成，等待用户二次授权。

## 2026-10-25 确定性编排后首次真实 E2E 启动失败

- **执行**：用户二次授权后仅运行一次 `mvn -Pe2e -De2e.scenario=happy-path verify`；显式 `qwen3.8-flash`、免费选择关闭、Docker healthy，没有重试或启动后三个场景。
- **直接原因**：coordinated STANDARD 禁用 Sub-Agent 后，真实 HarnessAgent 的 TaskRepository 为 null；`AgentScopeAgentGateway.adaptAgents` 仍无条件调用 `TaskSemanticsRegistry.forRepository`，Spring startup 报 `NullPointerException: repository 不能为空`。
- **结果**：Failsafe 1 test、0 failures、1 error、0 skipped，E2E 7.139s，Maven 26.359s；未进入 checkpoint、workflow 或角色模型调用。
- **证据与清理**：`target/e2e-evidence/happy-path/{summary.json,failure-summary.json}` 已保存脱敏 startup 证据；宿主和容器无 Maven/E2E/pytest/pip/tmux 残留。Task 8、Task 9 与父 Task 18 继续未完成。

## 2026-10-25 空 TaskRepository 适配修复

- **实现**：Gateway 只在真实 AgentScope 创建了 TaskRepository 时绑定任务语义表；无仓库角色使用空子任务诊断，并在取消时直接中断父 Agent。
- **测试**：新增真实 Spring 启动覆盖；定向测试 26 项和默认回归 396 项全部通过。
- **门禁**：未运行真实 E2E；四场景和父 Task 18 继续未完成。

## 2026-10-25 修复后单次真实 E2E

- **进度**：需求 checkpoint、PM 产品设计、RD 技术设计均成功，已进入代码实现；唯一入口正确，MCP 四路 CLOSED/0 failures。
- **失败**：Driver 在 Manager 正处理 RD 完成消息时，仅检查原 Owner RD 是否仍有 dispatch/AgentScope，误报 `TECH_DESIGN/RUNNING` 孤儿 execution。
- **证据**：`summary.json`/`failure-summary.json` 保存脱敏现场；最终 aggregate 中技术设计为 SUCCEEDED、代码 execution 为 shutdown CANCELLED，证明没有真实丢失状态。
- **清理**：无 Maven/E2E/pytest/pip/tmux 残留；未重试或启动后三个场景。

## 2026-10-25 孤儿诊断交接竞态修复

- Driver 将 runtime execution 与 `in_progress task_assign` 按 execution/project/stage/Owner 精确关联。
- 只有关联成功且 Manager dispatch 正活跃、未超过 5 分 30 秒才给予交接机会；不会让无关活动无限遮蔽孤儿。
- 定向 36 项、默认回归 399 项通过；未运行真实 E2E。

## 2026-10-25 交接诊断修复后单次 E2E

- 命令：`mvn -Pe2e -De2e.scenario=happy-path verify`，仅执行一次。
- 结果：E2E 500.7 秒失败，Maven 总耗时 8 分 40 秒；没有启动后三场景。
- 根因：PM 已产出 `design/product_spec.md`，但 `task_done.content.decision` 与 Java `WorkCompletionDecision` 契约不透明；多次非法枚举仅收到 `No enum constant`，最终回合退出而状态未终态化。
- 诊断：失败时 `PRODUCT_DESIGN/pm` 为 `RUNNING`，assignment 为 `in_progress`，PM/Manager dispatch 与 AgentScope 均不活跃，属于真实孤儿；Manager 交接宽限没有误杀。
- 收尾：四路 MCP `CLOSED/0 failures`；证据已脱敏保存，execution 最终为 `CANCELLED`，未发现 Maven/pytest/pip/tmux 残留。

## 2026-10-25 完成协议枚举与退出保护修复

- 工具 schema、参数描述与非法枚举错误统一公开 `ACCEPTED/DEFECT_FOUND` 及角色规则。
- 新增无敏感内容的 session 注册表，关联 completion rejection 与父 dispatch；成功重试清除，未纠正退出转为 `COMPLETION_PROTOCOL_REJECTED`。
- Runner 既有 incident 链会保持 stage/Owner，并执行一次恢复、第二次失败 BLOCKED，不再留下无 incident 的真孤儿。
- 聚焦测试 50 项、`mvn verify` 402 项通过，`git diff --check` 通过；本轮未运行 E2E。

## 2026-10-25 完成协议修复后单次真实 E2E

- 前置 `mvn -Ptool-contract -Dit.test=RealSandboxToolContractIT verify` 通过：1 IT，0 failure/error/skip。
- 仅执行一次干净 `happy-path`；显式 DashScope `qwen3.8-flash` Function Calling 启动预检返回 `transport_error`。
- Failsafe 1 test、0 failures、1 error、0 skipped；E2E 6.025 秒、Maven 24.792 秒。
- 本轮未进入 workflow、AgentScope 角色执行或 completion 工具，不能验证完成协议修复；未重试或启动后三场景。
- `target/e2e-evidence/happy-path/{summary.json,failure-summary.json}` 已保存脱敏证据；Docker healthy，无 Maven/E2E/pytest/pip/tmux 残留。Task 8 保持未完成。

## 2026-10-25 模型预检可靠性修复与非 E2E 门禁

- 模型预检增加稳定失败分类、最多一次有限恢复、250ms 退避和逐次脱敏证据；HTTP 4xx、响应解析及缺少 Function Calling 等不可恢复结果不重试。
- E2E Driver 启动失败证据新增 attempt、failure type、retryable、elapsed、endpoint host、HTTP status、exception class 和完整脱敏 attempts。
- 独立真实最小探针通过：DashScope `qwen3.8-flash` attempt 1、HTTP 200、4797ms；未启动 workflow 或 E2E。
- 聚焦 67 项、`mvn verify` 409 项、零模型真实 Docker 契约 1 IT 全部通过。
- 本轮按要求未运行 E2E，Task 8 保持未完成。

## 2026-10-25 模型预检修复后单次真实 E2E

- 只运行一次干净 `happy-path`；`qwen3.8-flash` 预检 attempt 1、HTTP 200、2692ms，通过后进入真实 workflow。
- checkpoint、产品设计、技术设计全部 SUCCEEDED；RD 已生成代码、前端与测试，入口唯一为 `code/main.py`。
- RD 最终明确记录“尚未执行 `run_project_tests`，下次唤醒继续”，随后普通文本退出；Java 没有把“未尝试完成协议”提升为失败或安排 continuation。
- Driver 以 execution、mailbox、dispatch、AgentScope 和 Test Runner 证据判定 `CODE_IMPLEMENTATION/RUNNING` 真孤儿；E2E 869.3s、Maven 14:49。
- MCP 全 CLOSED/0 failures，shutdown 将未终态 execution 持久化为 CANCELLED；未重试或启动后三场景，Task 8 保持未完成。

## 2026-10-25 Owner 回合未终态修复

- Owner 领取并启动协调任务即产生 completion obligation；成功 `task_done` 解除，普通文本退出则生成 `OWNER_EXITED_WITH_RUNNING_EXECUTION`。
- Runner 将该类型写入 incident；第一次保持原 stage/Owner/execution 并唤醒原 Owner，第二次同类失败 BLOCKED，重复上报幂等。
- 恢复任务显式携带 `failure_type` 与 `protocol=agent_execution_failed`，不依赖模型自行请求 clarification。
- 聚焦 63 项、默认回归 410 项、零模型真实 Docker 契约 1 IT 通过；未运行真实 E2E，Task 8 保持未完成。

## 2026-10-25 Owner 未终态修复后单次真实 E2E

- 只运行一次干净 `qwen3.8-flash happy-path`；未自动重试或启动后三场景。
- PM attempt=2 后成功；RD 代码 execution 首次 `OWNER_EXITED_WITH_RUNNING_EXECUTION` 自动恢复原 Owner，第二次同类失败在 attempt=2 明确 BLOCKED，恢复预算正确收敛。
- Driver 已进入失败清理，但主线程阻塞于 `CronService.stop()`；线程转储显示 Cron 调度线程持有同一锁，并在 `CronService.fire()` 无界 join dispatch future。
- 约 31 分钟后中断唯一 Maven 进程，exit 130；未伪造 summary。随后删除 1 个 AIO shell session，复核 session=0 且宿主/容器无 pytest/pip/tmux/Maven/E2E 残留。
- Task 8 与父 Task 18 继续未完成；下一轮前必须修复 Cron shutdown 死锁。

## 2026-10-26 Cron、Driver 收尾与 Test Runner 完成门禁修复

- **Cron/关闭**：tick 与 lifecycle 锁分离，dispatch 最长等待 30 分钟，scheduler stop 最长等待 5 秒；业务 Future 由 RuntimeLifecycle/Registry 负责有限排空和取消。
- **证据/看门狗**：Driver 关闭前先取证，shutdown 受 2 分钟看门狗约束，关闭后总会补存证据；场景总预算为 2 小时。
- **恢复/门禁**：RD 代码或缺陷恢复要求 `run_project_tests_then_task_done`；没有 Java 记录的真实 Test Runner execution 时拒绝完成。
- **验证**：定向 80 项、默认 415 项、真实 Docker + NoCallModel 1 IT 通过；本轮未运行 E2E，Task 8、Task 9 和父 Task 18 保持未完成。

## 2026-10-26 单次 E2E bind mount 失配

- 预检一次 TLS 失败后按预算恢复成功；宿主 workflow 进入 PRODUCT_DESIGN/PM。
- Docker 仍持有被移动目录的旧 inode，容器内读取旧 execution；宿主/容器同名 runtime 哈希不同。
- 为避免新旧状态混写主动中止，exit 130；未重试，证据归档于 `invalid-bind-mount-20261026-attempt1`。
- 清理 1 个 session 并重启容器后，mount probe 可见、Docker healthy、session=0、无孤儿；Task 8/9 未完成。

## 2026-10-27 单次有效 E2E

- `qwen3.8-flash` 预检一次通过，bind mount 与 Docker 前置状态有效；只运行一次 `happy-path`。
- checkpoint、产品和技术设计完成；PM 的完成协议拒绝由原 Owner 恢复成功，MCP 未发生故障。
- RD 两个代码回合均在调用 `run_project_tests` 前退出；首次进入 RECOVERING 并自动续跑，第二次进入 BLOCKED，QA 未启动。
- Maven 21:57、E2E 1297 秒；证据位于 `qingling-team/target/e2e-evidence/happy-path`。
- shutdown 后 dispatch pending=0、AgentScope active=0、AIO session=0、无孤儿；未重试，Task 8/9 未完成。

## 2026-10-27 代码阶段有限回合确定性推进修复

- RD 首次测试前移，Test Runner 固定执行项目测试和 Harness acceptance。
- 新增 `TEST_RUNNER_NOT_INVOKED` / `TEST_RUNNER_COMPLETION_PENDING`，恢复任务不再只有泛化的 Owner 未终态原因。
- `run_project_tests_now_then_fix_or_task_done` 恢复被领取后，Java 强制禁止测试前继续写 `code/`；真实工具调用后才解锁。
- attempt 2 已有测试证据时允许一次 attempt 3 收尾；其余第二次失败及 attempt 3 继续 BLOCKED。
- 定向 72 项、默认 420 项通过；Docker 契约首次发现夹具缺口，补齐 acceptance 后 420 unit + 1 IT 通过。
- 按本轮范围未运行真实 E2E，Task 8/9 与父 Task 18 保持未完成。

## 2026-10-30 确定性推进修复后单次真实 E2E

- 只启动一次干净的 `qwen3.8-flash happy-path`，没有启动后三个场景，也没有重试整轮。需求 checkpoint、产品设计和技术设计均成功；PM 首次 `COMPLETION_PROTOCOL_REJECTED` 后由原 Owner 恢复并成功推进。
- RD 代码首回合触发 `TEST_RUNNER_NOT_INVOKED/RECOVERING`，恢复后真实调用 Test Runner；随后触发 `TEST_RUNNER_COMPLETION_PENDING/RECOVERING`。这证明测试优先门禁已生效，但有限恢复没有收敛到完成或 `BLOCKED`。
- 代码阶段共创建 16 个真实 Test Runner execution，全部终态非零：12 个 `exit_code=1`、4 个 `exit_code=2`。最新一次已收敛到 11 collected、9 passed、2 failed、coverage 87%，失败为首页 HTML 契约；模型具备修复进展，但 workflow 从 2026-10-29T15:29:59Z 起一直保持 `CODE_IMPLEMENTATION/RD/RECOVERING`，未进入 QA。
- 恢复期间 Manager 仍产生与 RD 恢复无关的重复 `task_done_received` 协调事件，RD 恢复邮件保持 pending；Driver 没有把“恢复状态下 Test Runner 反复执行但 stage/incident 不收敛”快速判为终态失败。
- 用户确认失败可直接结束后，于 Maven 运行约 1 小时 5 分时发送中断，命令以 exit 130 结束；没有伪造 Failsafe summary。现场归档到 `qingling-team/target/e2e-evidence/diagnostics/happy-path-20261029-manual-stop`。
- AIO shell 清理接口返回 HTTP 200；复核宿主无 E2E Maven/Surefire/Java，容器无 pytest/pip/tmux，Docker healthy。Task 8、Task 9 与父 Task 18 继续未完成。

## 2026-10-31 恢复循环收敛与权威执行绑定

- 新增原子 `claimAndStartWorkItem`：在一次 runtime repository 事务内完成 `RECOVERING` retry、assignment claim 和 dispatch start，命令 ID 提供重复调用幂等。
- `WorkExecution` 持久化当前 attempt 的 `testInvocationCount` 与最新 `testExecutionId`；Test Runner 启动前绑定，最多 3 次，超限在进入 Docker 前返回结构化 `INVOCATION_REJECTED`。
- 完成协议仅接受 `WorkExecution` 当前绑定的最新 Test Runner 终态；旧 execution 即使已完成也不能推进 workflow。
- attempt 2 的受控 continuation 确定性进入 attempt 3；attempt 3 再失败由 runtime incident 转为 `BLOCKED`，incident 携带 attempt、retryable 与失败指纹。
- Driver 增加 2 分钟 `RECOVERING` 快速诊断，直接输出 execution/stage/owner/attempt/test count/assignment/dispatch/agent 证据，避免等待全局超时。
- File mailbox 在同一锁内将原 assignment 转为 `DONE` 后追加恢复 assignment；删除旧入口先释放为 `UNREAD` 的竞态，保证同 execution 只有一个活跃任务。
- 验证：核心定向 101 项、旧入口兼容 23 项、协调器 5 项、完整 `mvn verify` 426 项、零模型真实 Docker 426 unit + 1 IT 全部通过。
- 按用户要求未启动真实 E2E；Task 8、Task 9 与父 Task 18 继续未完成。
