# Task 18 真实 E2E 运行指南

Task 18 通过 TestAPI 驱动真实 Qwen、四角色 AgentScope 和 AIO-Sandbox，不依赖飞书配置。运行数据与证据均位于 `target/`，不会修改日常 `workspace/`。

## 1. 前置配置

```bash
cd /Users/qingling/workspace/IdeaProjects/QinglingWake/qingling-team

# 必填：阿里云百炼 DashScope API Key。不要写入 config.yaml 或提交到 Git。
export DASHSCOPE_API_KEY='sk-替换为真实值'

# 当前使用百炼节省计划：关闭免费候选探测并显式选择 qwen3-max。
export QINGLING_FREE_MODEL_SELECTION_ENABLED=false
export QINGLING_AGENT_MODEL=qwen3-max
export QINGLING_SUB_AGENT_MODEL=qwen3-max

# 可选：覆盖沙盒地址。
export QINGLING_SANDBOX_URL='http://localhost:8029/mcp'
```

兼容旧环境变量 `QWEN_API_KEY`，但优先使用 `DASHSCOPE_API_KEY`。

`QINGLING_FREE_MODEL_SELECTION_ENABLED=false` 时，应用直接使用 `QINGLING_AGENT_MODEL`，不会读取免费候选列表，也不会发起任何候选网络探测。启动日志和 `model-selection.json` 会明确记录“免费模型选择关闭”、`selection_mode=explicit` 与最终模型，但不会记录 API Key。

节省计划用完后，如需切换到免费候选模式，显式设置：

```bash
export QINGLING_FREE_MODEL_SELECTION_ENABLED=true
export QINGLING_AGENT_MODEL_CANDIDATES='qwen3.8-27b,qwen3.8-2.4t-a95b,deepseek-v4.1-flash,glm-5.3,kimi-k3,deepseek-v4-flash-0731,qwen3.7-flash-2026-07-15,qwen3.7-flash'
```

只有开关为 `true` 时，应用才按上述顺序发起最小 Function Calling 探测。额度耗尽、未开通、限流、不可访问或工具调用不兼容时继续下一候选；全部失败则明确终止启动，绝不切换到列表外模型。已耗尽免费额度的 `qwen3.8-max-0902` 和 `qwen3.8-flash` 不在默认候选中。旧变量 `QINGLING_DYNAMIC_MODEL_SELECTION_ENABLED` 仅在新开关未设置时作为兼容回退；新开关始终优先。

## 2. 启动沙盒

先启动 Docker Desktop，再执行。Compose 使用 AIO-Sandbox 官方为中国大陆提供的火山引擎公共镜像，并固定为 `1.11.0`，避免 GHCR blob 在大陆网络反复 EOF：

```bash
docker compose -f sandbox-docker-compose.yaml pull
docker compose -f sandbox-docker-compose.yaml up -d --pull never
docker compose -f sandbox-docker-compose.yaml ps
```

沙盒把 `target/e2e-workspace` 挂载为容器内 `/workspace`，仅绑定本机 `127.0.0.1:8029`；按官方要求启用 `seccomp:unconfined` 和 2GB shared memory。

应用只从 MCP 注册 `sandbox_execute_bash`，随后在 Java 侧为 RD/QA 包装 `run_project_tests(project_id)`。包装器固定使用 `/workspace/shared/projects/{projectId}/code`，模型不能提供命令或 cwd，并返回 `exit_code`、pytest 数量与覆盖率证据。声明式 RD/QA Sub-Agent 同时继承原始工具和结构化工具；原始工具只用于无副作用探测与依赖安装。Harness 自带的文件工具和本地 Shell 均关闭。MCP 连接失败或白名单工具缺失会直接阻止启动。

## 3. 零模型真实工具契约

每次昂贵 E2E 前先运行下面的轻量契约。它会创建真实 RD/QA 声明式子代理，核对其工具列表，连续执行 10 次无副作用 Shell 探针和 5 次结构化 `run_project_tests`，并覆盖一次受控 MCP 连接失效、client 重建与恢复探针。契约使用带调用计数断言的 `NoCallModel`，不会调用百炼或消耗 Token；结束前还会确认没有遗留 pytest、pip 或 tmux 进程：

```bash
mvn -Ptool-contract \
  -Dtool.contract.workspace="$(pwd)/target/e2e-workspace" \
  verify
```

此命令失败时禁止继续 `-Pe2e`。它不替代业务 E2E，只负责在最便宜的一层证明 MCP 注册、子代理继承、共享目录挂载、结构化 execution ID、连续调用和连接恢复真实可用。

## 4. 分场景执行

```bash
mvn -Pe2e -De2e.scenario=happy-path verify
mvn -Pe2e -De2e.scenario=checkpoint-revise verify
mvn -Pe2e -De2e.scenario=qa-defect-rd-fix verify
mvn -Pe2e -De2e.scenario=code-fail-recovery verify
```

缺少模型密钥、Docker daemon 未启动、沙盒不可达或业务阶段超时都会明确失败，不会以 JUnit skip 规避。

每条成功场景会在 `target/e2e-evidence/<scenario>/` 保存：

- `model-selection.json`：免费模型开关、`explicit`/`free_dynamic` 选择模式、最终模型和脱敏探测状态；
- `summary.json`：实际选中模型、选择模式、Session、事件动作和 MCP 注册状态；
- `summary.json` 中的 `called_tools`：必须包含真实调用的 `sandbox_execute_bash`，不能只证明 MCP 注册成功；
- `project/`：needs/design/tech/code/qa、mailbox、events 等验收产物；
- `data/`：Session mapping、Cron 和审计数据。

失败场景也会在 `finally` 中保存 `summary.json` 和 `failure-summary.json`，并尽可能复制当时的项目、事件、mailbox、Session/Cron 数据。等待产物期间驱动会同步检查 `tool_execution_failed` incident、MCP 熔断状态、mailbox 三态与 dispatch 积压；不可重试或 `BLOCKED` 的工具故障会立即报告 `tool`、`execution_id`、`failure_type`、stage 和 owner，不再伪装为后续 `qa/test_plan.md` 超时。错误文本和结构化输出在写入证据前会进行凭据脱敏和长度限制。

Java 状态机唯一决定 checkpoint、PM、RD、QA 与交付阶段的下一 Owner 和任务主题。模型只能提交评审结论；直接 `send_mail(type="task_assign")` 会被拒绝。RD 缺少 `exit_code=0`、pytest 数量或覆盖率时，QA 缺少测试报告或证据矩阵时，Java 会拒绝成功 `task_done`。

## 5. 飞书运行配置（不阻塞 Task 18）

在飞书开放平台创建企业自建应用后，按以下顺序配置：

1. 在“添加应用能力”中添加“机器人”。
2. 在“权限管理”中申请租户级权限：
   - `im:message:send_as_bot`：以应用身份创建、回复和更新消息；
   - `im:message.p2p_msg:readonly`：接收用户发给机器人的单聊消息；
   - `im:message.group_at_msg:readonly`：接收群聊中 @ 机器人的消息；
   - `im:resource`：下载用户消息里的图片和文件；
   - 若要接收所有群消息而不是仅 @ 消息，再申请控制台展示的“获取群组中所有消息”权限；
   - 订阅机器人进群事件时，如控制台提示依赖权限，再增加 `im:chat:readonly`。
3. 在“事件与回调/事件订阅”中选择“使用长连接接收事件”，无需填写公网回调 URL。
4. 添加事件 `im.message.receive_v1`（接收消息）和 `im.chat.member.bot.added_v1`（机器人进群）。
5. 在“版本管理与发布”中创建版本、设置可用范围、提交审核并发布；然后私聊机器人，或把机器人加入测试群。
6. 在“凭证与基础信息”中获取下面两项：

- `FEISHU_APP_ID`：应用凭证 App ID；
- `FEISHU_APP_SECRET`：应用凭证 App Secret；

如需限制群聊，还需要准备允许访问的 `chat_id` 列表；首次联调可暂时使用空列表。

本机只通过环境变量提供密钥：

```bash
export QINGLING_FEISHU_ENABLED=true
export FEISHU_APP_ID='cli_xxx'
export FEISHU_APP_SECRET='替换为真实值'
```

`config.yaml` 只保存非敏感配置，例如：

```yaml
qingling:
  feishu:
    enabled: true
    allowed-chats: [] # 空数组表示不限制群聊；生产环境建议填写 chat_id 白名单
```

密钥不得提交到 Git，也不要粘贴到聊天中；应在你本机终端设置环境变量。
