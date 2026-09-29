# Task 18 真实 E2E 运行指南

Task 18 通过 TestAPI 驱动真实 Qwen、四角色 AgentScope 和 AIO-Sandbox，不依赖飞书配置。运行数据与证据均位于 `target/`，不会修改日常 `workspace/`。

## 1. 前置配置

```bash
cd /Users/qingling/workspace/IdeaProjects/QinglingWake/qingling-team

# 必填：阿里云百炼 DashScope API Key。不要写入 config.yaml 或提交到 Git。
export DASHSCOPE_API_KEY='sk-替换为真实值'

# 当前使用百炼节省计划：关闭免费候选探测并显式选择 qwen3.8-max。
export QINGLING_FREE_MODEL_SELECTION_ENABLED=false
export QINGLING_AGENT_MODEL=qwen3.8-max

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

## 3. 分场景执行

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

## 4. 飞书运行配置（不阻塞 Task 18）

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
