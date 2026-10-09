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
| 2026-09-26 | apply | Task 18 真实 E2E 基础设施已实现，等待真实配置后续跑 | 新增隔离 AIO-Sandbox Compose、Maven `e2e` profile、四场景真实 Driver、实际 MCP 工具调用观测和证据归档；141 项默认测试通过，真实场景按预期因缺少 `DASHSCOPE_API_KEY` 失败且 0 skip；Docker 29.3.1 可用，GHCR 已下载多数层，但 blob 下载两次因网络 EOF 中断，镜像尚未完整拉取 |
| 2026-09-26 | apply | Task 18 沙盒改用官方中国大陆镜像 | GHCR 的 `pkg-containers.githubusercontent.com` 多次随机 blob EOF；依据 AIO-Sandbox 官方大陆 Quick Start，Compose 改为火山引擎公共镜像 `all-in-one-sandbox:1.11.0`，并补齐 `seccomp:unconfined`、2GB shared memory、仅本机端口绑定与 `/workspace` 环境 |
| 2026-09-26 | diagnose | 定位 Docker 镜像层 EOF 到 Desktop containerd 链路 | 官方大陆镜像的火山对象存储同样随机 blob EOF；宿主机 curl 对相同签名 blob 返回 HTTP 206 并完整读取，Docker 使用 `io.containerd.snapshotter.v1` 且经内部 3128 代理，故排除仓库权限和本机外网阻断，等待切换经典镜像存储后续拉取 |
| 2026-09-26 | diagnose | Task 18 首次真实运行发现嵌套挂载冲突 | Key、沙盒健康检查均通过；happy-path 在场景目录清理阶段因 `/workspace/.cron` 嵌套 bind mount 带 macOS `deny delete` ACL 而失败，尚未调用模型；移除不必要的 Cron 嵌套挂载后重建容器重试 |
| 2026-09-28 | apply | Task 18 修复真实多 Agent 并发与路由缺陷 | 修复 Cron 旧快照覆盖执行中新增 wake、`send_to_human` 外部 routing key、开放任务幂等、Manager 确定性评审与技术方案后代码阶段；对应回归及完整默认 `mvn verify` 通过 |
| 2026-09-28 | diagnose | 首次干净 happy-path 未通过 | 真实流程完成 checkpoint、PM、技术方案评审/修订并进入 RD 代码；百炼连接多次 EOF/SSL 握手失败导致 `file:code/main.py` 等待 30 分钟超时。RD 随后把 MCP `timeout` 传成字符串而被 schema 拒绝；该运行无成功 `summary.json`，不能计为通过 |
| 2026-09-28 | apply | 固化 AIO-Sandbox timeout 参数契约 | RD/QA Skill 与 Sub-Agent 统一要求省略 timeout 或传 JSON 整数秒，禁止字符串；Workspace 模板契约 10/10、完整默认回归 154 项及 `git diff --check` 通过，未延长 E2E 超时 |
| 2026-09-29 | verify | 第二次干净 happy-path 推进至 QA 设计后受外部账户状态阻断 | 显式模型为 `qwen3.8-max` 且免费选择关闭；约 6 分钟内完成 checkpoint、PM、RD 技术设计和 `code/main.py`，RD 代码与 QA 设计均完成、无重复任务；随后百炼返回 `Arrearage`。当前仅有部分场景证据，无成功 `summary.json`，Task 18 仍未完成 |
| 2026-09-29 | apply | 增强开放任务的同义主题幂等保护 | `技术方案设计任务` 与 `技术方案设计 (第 1 轮)` 等仅后缀不同的开放分派复用同一消息；新增回归测试后完整默认 `mvn verify` 共 155 项通过 |
| 2026-09-29 | verify | `qwen3.7-plus` 干净 happy-path 按用户要求暂停 | 显式模型为 `qwen3.7-plus`、免费选择关闭且无动态探测；真实完成 checkpoint、PM 产品设计、RD 技术方案、PM/QA 评审和 RD 修订，验证修订后正确进入 RD 代码实现且同义任务未重复；生成 `code/app.py` 后用户要求暂停，已终止 screen 与残留 Maven/Java 子进程。该运行无 Failsafe 成功或 `summary.json`，不得计为通过；下次从干净场景目录重跑 |
| 2026-09-30 | diagnose | `qwen3.7-plus` 干净 happy-path 再次未完成 | 重建 AIO-Sandbox 后从保留 inode 的干净 workspace 启动；显式模型与免费选择开关正确。真实流程完成 checkpoint、PM、产品评审、RD 技术方案、RD 代码实现（requirements.txt、10 项 RD pytest 通过）并按 SOP 进入 QA。QA 独立执行因沙盒工具路径反复失配及导入脚本修正循环，20 分钟未生成 `qa/test_report.md`、证据矩阵或最终邮件；已终止残留进程并保存 `target/e2e-diagnostics/happy-path-qwen37plus-qa-stuck-20260930`，不得计为通过，需从干净目录重跑 |
| 2026-09-30 | apply | 加固 Manager 需求 checkpoint 状态机模板 | 明确“创建项目及五节需求 → 写入共享文档 → 记录 `requirements_drafted` → 固定 `requirements_review` checkpoint”的顺序，并禁止把人工 checkpoint 回复伪装成邮箱消息或使用虚假 `msg_id`；Workspace 模板契约定向测试 13/13 通过。该修改后尚未重新完成完整默认回归 |
| 2026-09-30 | diagnose | 终止 `qwen-coder-plus` 多轮 happy-path 并清理环境 | 显式模型 `qwen-coder-plus`、免费选择关闭。首轮未创建项目即发 checkpoint；加固模板后曾误把人工批准当邮箱消息；最新轮创建项目和完整五节需求，但出现 checkpoint 早于 `requirements_drafted` 且重复发送 `requirements_review`，未进入 PM。三轮均无 Failsafe 成功或 `summary.json`，诊断已保存到 `target/e2e-diagnostics/happy-path-qwen-coder-plus-*`。按用户要求终止 screen/Maven/Surefire/Java，停止并移除 AIO-Sandbox 容器与网络，清空活动场景目录并确认 8029 端口关闭；Task 18 保持未完成，待选择新模型后从干净目录重跑 |
| 2026-10-01 | diagnose | `qwen3-max` 第 1 次干净 happy-path 因内置文件工具挂起失败 | 显式模型 `qwen3-max`、免费选择关闭；完成单一需求 checkpoint、真实 routing key、PM、技术方案评审/修订，并正确分派 RD 代码实现。RD 修复测试时调用 Harness 内置 `write_file`，工具长期无结果后被 pending-tool recovery 标记中断，未继续到 QA/交付；无 Failsafe 成功或 `summary.json`。诊断保存于 `target/e2e-diagnostics/happy-path-qwen3max-attempt1-write-file-stall-20260930` |
| 2026-10-01 | verify | MCP `enabled-tools` 白名单定向测试通过但真实语义不满足 | 临时将 `enabled-tools` 限制为 `sandbox_execute_bash`，`McpSandboxConfigurationTest` 4/4、`RuntimeBootstrapTest` 2/2、`WorkspaceTemplateContractTest` 13/13 通过；真实 E2E 证明该配置既未移除 Harness 内置 `read_file/write_file/list_files`，又使 RD 声明式子代理无法取得 `sandbox_execute_bash`，故临时源码配置和断言已撤回，不能作为修复 |
| 2026-10-01 | diagnose | `qwen3-max` 第 2 次干净 happy-path 失败并停止后续重跑 | 第二次真实流程完成需求 checkpoint、PM 产品设计和 RD 技术方案，heartbeat 可恢复角色漏发完成邮件；RD 代码子代理调用 `sandbox_execute_bash` 返回 `Tool not found`，未执行 pytest 却发送带 `pytest_status=not_executed` 的 `task_done`，违反真实沙盒与测试共同断言。诊断保存于 `target/e2e-diagnostics/happy-path-qwen3max-attempt2-sandbox-tool-unavailable-20260930`；无 `summary.json`。遵守用户“两次后不启动第三次”约束，已终止全部 E2E 进程、停止并移除 Docker 沙盒、清空活动目录；Task 18 保持未完成 |
| 2026-10-01 | apply | 修复 Harness 文件工具与声明式 Sub-Agent MCP 继承 | `TeamAgentFactory` 关闭父 Harness 文件工具，AgentScope 自动将该禁用标志传播到声明式子代理；MCP 改为父 Toolkit 构建期同步注册并关闭延迟 ToolsConfig，使子代理的 `tools:` 白名单能复制 `sandbox_execute_bash`。默认配置只开放该沙盒工具，注册失败或工具缺失会启动即脱敏失败；定向 8 项和完整默认 `mvn verify` 161 项通过，尚未据此重跑真实 E2E |
| 2026-10-01 | apply/verify | 增加 Java 阶段状态机、完成硬门禁与零模型真实工具契约 | `advance_workflow` 固定 checkpoint→PM→RD 技术设计→RD 代码→QA 设计/执行→交付及拒绝回路，校验当前 Owner，禁止直接 `task_assign` 和通用 `append_event` 伪造迁移；RD/QA 无测试证据时 `send_mail(task_done)` 被拒绝。新增 `run_project_tests(project_id)` 固定共享代码目录并返回 exit/pytest/coverage。默认 `mvn verify` 175 项通过；Docker healthy 下 `tool-contract` 为 175 unit + 1 IT 通过，真实 RD/QA 子代理完成无副作用目录探针且 `NoCallModel` 零百炼调用。Task 18 因四条真实模型 E2E 尚未通过仍保持未完成 |
| 2026-10-01 | diagnose | Java 门禁修复后第 1 次干净 happy-path 未通过 | 显式 `qwen3-max` 完成 checkpoint 和 PM；PM heartbeat 能恢复漏发完成邮件，但 RD 的 `new_mail` wake 未显式给出真实 project_id，模型把 Maven 模块 `qingling-team-starter` 当项目且未领取 `todo-mvp` 邮件。诊断保存于 `target/e2e-evidence/diagnostics/happy-path-20261001-2011`，无成功 `summary.json` |
| 2026-10-01 | apply/verify | 确定性补全 new_mail wake 上下文 | Runner 仅对团队 cron 的 `__wake__:new_mail:<projectId>` 注入真实 project_id、首步 `read_inbox` 和结束 `mark_done`，heartbeat/普通消息保持原样；Runner 定向测试 6/6、完整默认 `mvn verify` 176 项通过 |
| 2026-10-01 | diagnose | Java 门禁修复后第 2 次干净 happy-path 未通过 | 确定性 wake 生效；真实完成 PM、产品评审、技术方案并由 Java 状态机执行 `TECH_DESIGN → CODE_IMPLEMENTATION`，没有跳过 RD 代码。RD 生成 Python 项目后 pytest 在收集阶段失败，完成硬门禁拒绝了无 `exit_code=0`/pytest/coverage 证据的成功回报；其 Agent 调用链随后停留 pending。诊断保存于 `target/e2e-evidence/diagnostics/happy-path-20261001-2030`，无成功 `summary.json`；达到本轮两次上限，不启动第 3 次 |
| 2026-10-01 | apply/verify | 修复 MCP 已完成结果伪 RUNNING 并强化真实工具契约 | AgentScope 2.0.3 MCP Mono 已完成且有输出时仍可能返回 `RUNNING`，现包装白名单 MCP 工具归一化为 `SUCCESS`，空输出真实 pending 不变；定向 9/9 通过。首次零模型真实工具契约在 Docker healthy、TCP 可达时仍因 MCP 60 秒无响应失败，重启 AIO-Sandbox 后以严格 `SUCCESS` 通过 `177 unit + 1 IT`，证明昂贵 E2E 前必须执行真实工具契约，不能只依赖容器健康检查 |
| 2026-10-01 | verify | 本轮 Task 18 收尾默认回归通过 | 最终 `mvn verify` 共 177 项通过、六模块全部 SUCCESS，包含确定性 wake、MCP 完成态归一化、Java 阶段状态机、RD/QA 完成硬门禁和修正后的 Manager→RD 缺陷修复 E2E 断言；本轮未启动第 3 次真实模型 E2E，Task 18 保持未完成 |
| 2026-10-01 | diagnose | 最新 `qwen3-max` 干净 happy-path 暴露澄清恢复协议缺口 | `177 unit + 1` 零模型真实工具契约先行通过。真实流程完成 checkpoint、PM、RD 技术方案并正确进入代码实现；RD 生成应用和测试，pytest 因相对导入失败，硬门禁拒绝虚假成功。heartbeat 后 RD 发送 `clarification_request`，Manager 领取邮件后发现 `clarification_response`/`info` 非法且直接 `task_assign` 被状态机禁止，最终只写说明文档并关闭请求，无法重新唤醒 RD。运行已终止、无遗留 E2E 进程；诊断保存于 `target/e2e-evidence/diagnostics/happy-path-20261001-2119-clarification-dead-end`，无成功 `summary.json`，Task 18 保持未完成 |
| 2026-10-02 | apply/verify | 增加 Manager 澄清答复的同阶段恢复协议 | 新增 Java `CLARIFICATION_ANSWERED` 信号，固定保持当前阶段并向原 Owner 分派唯一继续任务，正文包含 `protocol=clarification_answer`、`resume_current_stage=true` 与非空答复；阻止 Manager 直接发送 `clarification_answer`。PM/RD/QA 模板仅恢复原阶段，Manager 仅在状态机成功后关闭请求。状态机 6/6、工作流工具 7/7、模板契约 14/14 和默认 `mvn verify` 182 项通过；按用户要求未重跑真实 E2E，Task 18 保持未完成 |

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
