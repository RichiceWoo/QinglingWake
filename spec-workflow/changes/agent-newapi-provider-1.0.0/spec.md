# Agent 模型供应商切换与 Memory 独立配置 1.0.0

> status: apply
> created: 2026-10-21
> complexity: 🔴复杂
> change-dir: `spec-workflow/changes/agent-newapi-provider-1.0.0/`

## 1. 背景与目标

当前 Qingling Team 的 Agent 模型调用固定使用阿里云百炼 DashScope。本改造新增显式供应商开关，使 Agent 可以在不改动工作流、AgentScope 工具调用和 MCP 沙箱链路的前提下，在 DashScope 与 NewAPI 之间切换。

NewAPI 已知协议为 OpenAI 兼容的 `POST /v1/chat/completions`，使用 `Authorization: Bearer sk-...`，模型名由请求体 `model` 指定。当前确认配置是：

- Base URL：暂时为空，待服务方提供可达 HTTPS 地址
- Chat Completions：由 Base URL 唯一派生 `/v1/chat/completions`
- 首个真实验证模型：`qwen3.8`
- 当前约束：请求域名尚未提供，本变更实现期不得将真实可用性标记为已验证。

改造完成后的可验收结果：

1. `QINGLING_MODEL_PROVIDER=dashscope` 保持现有行为和默认配置兼容。
2. `QINGLING_MODEL_PROVIDER=newapi` 时，Agent 通过独立 NewAPI Base URL 和 SK 调用 OpenAI 兼容接口；首个真实验证模型固定为 `qwen3.8`。
3. Memory 不继承 Agent 的 NewAPI 配置，继续独立使用 DashScope 的密钥、摘要模型和 Embedding 模型。
4. NewAPI 不可达、鉴权失败或不支持 Function Calling 时快速失败，不回退到 DashScope，不启动 E2E。
5. 任何日志、异常和测试证据都不得包含 SK、Authorization Header 或未脱敏响应。

## 2. 代码现状（Research Findings）

### 2.1 相关入口与链路

1. `qingling-team-starter/src/main/resources/application.yml` 中 `qingling.agent.*` 只提供一组 `api-key` 和 DashScope 默认 `model-probe-url`。
2. `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/config/QinglingTeamProperties.java` 的 `Agent` record 把凭据定义为 DashScope API Key，没有 Provider 和 NewAPI 子配置。
3. `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/config/RuntimeConfiguration.java#agentModel` 直接构造 `DashScopeChatModel`。
4. `RuntimeConfiguration#memoryIndexer` 将 Agent API Key 和 Agent 选中模型传给 `MemoryExtractionClient`，Agent 和 Memory 存在不应有的配置耦合。
5. `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/config/DashScopeModelSelector.java` 通过 OpenAI 兼容请求检查 Function Calling，但日志、异常、候选模型和 `enable_thinking` 仍是 DashScope 专用语义。
6. `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/e2e/RealTeamE2EDriver.java#requireModelCredential` 只接受 `DASHSCOPE_API_KEY` 或 `QWEN_API_KEY`。

### 2.2 现有实现

- Maven 仅声明 `agentscope-extensions-model-dashscope`。
- `RuntimeConfiguration#runtimeSettings` 启动时无条件要求 DashScope 凭据。
- `DashScopeChatModel` 使用 DashScope 原生协议路径；仅替换 `baseUrl` 不能保证转换为 NewAPI 的 `/v1/chat/completions`。
- AgentScope 应通过 `agentscope-extensions-model-openai` 中的 OpenAI 兼容 Model 适配 NewAPI。
- `MemoryExtractionClient` 使用 DashScope OpenAI 兼容根地址、`text-embedding-v3` 和固定 1024 维向量。

### 2.3 发现与风险

- NewAPI 的 SK 不得复用 `DASHSCOPE_API_KEY`，否则在两套凭据同时存在时会产生隐式路由。
- Provider 选择必须是显式配置，不能根据“哪个 Key 非空”自动推断。
- NewAPI 可能不识别 DashScope 的 `enable_thinking`，通用探针不得无条件发送该字段。
- HTTP 200 不等于 Function Calling 可用，必须校验 `choices[0].message.tool_calls`。
- NewAPI 当前不可触达，只能先用本地 Stub Server 验证协议；不得伪造真实连通结果。
- Memory 的 Embedding 模型与维度属于存储契约，不能随 Agent Provider 自动切换。
- `QINGLING_SUB_AGENT_MODEL` 已存在，但当前没有独立模型实例链路。本版本不扩展为父子 Agent 跨 Provider 混用。

## 3. 功能点

- [x] 新增 `qingling.agent.provider` / `QINGLING_MODEL_PROVIDER`，支持 `dashscope` 和 `newapi`，默认 `dashscope`。
- [x] 新增独立 `dashscope` 和 `new-api` 凭据与地址配置，不做跨 Provider Key 回退。
- [x] 根据 Provider 构造 `DashScopeChatModel` 或 AgentScope OpenAI 兼容 Model。
- [x] 将模型选择和 Function Calling 探针改造为 Provider 感知的通用组件。
- [x] NewAPI 模式只使用显式模型，不执行 DashScope 免费候选模型轮询。
- [x] Memory 新增独立 DashScope 配置，不继承 Agent Provider、Key 或模型。
- [x] E2E Driver 和证据脱敏逻辑识别 NewAPI Provider，但地址不可达时禁止 E2E。
- [x] README 增加两种 Provider 的配置示例、安全边界和故障诊断。

## 4. 业务规则

1. `provider` 缺省值为 `dashscope`，保证旧部署不因升级自动转向 NewAPI。
2. `provider=dashscope` 必须提供 `DASHSCOPE_API_KEY`，允许保留 `QWEN_API_KEY` 作为旧变量兼容回退。
3. `provider=newapi` 必须同时提供 `NEWAPI_API_KEY`、`NEWAPI_BASE_URL` 和显式 Agent 模型名。
4. NewAPI Base URL 以 `/v1` 为协议根路径；内部统一派生 `/chat/completions`，避免重复 `/v1/v1` 或遗漏路径。
5. Base URL 不得包含 user-info、query 或 fragment；生产配置默认要求 HTTPS。
6. Provider 不支持、凭据缺失或 URL 非法时在 Spring 容器构造 Agent/MCP 前 fail-fast。
7. NewAPI 不可达时不回退到 DashScope，避免实际费用来源和数据边界不可判定。
8. `free-model-selection-enabled=true` 仅支持 DashScope；NewAPI 模式下配置为 true 必须拒绝启动。
9. Agent 父子任务在本版本使用同一 Provider；不支持 DashScope/NewAPI 混合调度。
10. Memory 启用 pgvector 时必须使用独立 DashScope Key；Memory 关闭时不得创建远程模型请求。
11. 配置错误、HTTP 4xx 和不支持 Function Calling 属于非重试失败；只有受控的临时传输失败可依既有运行时策略处理。
12. 日志只能记录 Provider、模型名、脱敏状态和必要的 endpoint host，不记录 SK、Header 和响应原文。

## 5. 数据变更

| 操作 | 表名 | 字段/索引 | 说明 |
|------|------|-----------|------|
| 无 | - | - | 不修改数据库 Schema；Memory 维度和现有 pgvector 契约保持不变 |

## 6. 接口变更

| 操作 | 接口 | 方法 | 变更内容 |
|------|------|------|----------|
| 新增出站 | NewAPI Chat Completions | `POST /v1/chat/completions` | 使用 Bearer SK、显式 model、messages 和 tools |
| 保留出站 | DashScope Model | AgentScope DashScope 适配器 | 默认路径不变 |
| 保留出站 | DashScope Memory Chat/Embedding | OpenAI 兼容 HTTP | 独立 Memory 凭据和模型配置 |
| 无入站变更 | REST / TestAPI / MCP | - | 不修改对外业务协议 |

## 7. 影响范围

- `qingling-team/pom.xml`：统一管理 AgentScope OpenAI 扩展依赖版本。
- `qingling-team-starter`：配置绑定、Provider 校验、Model 工厂、Function Calling 探针、E2E 凭据与证据脱敏。
- `qingling-team-infra`：Memory DashScope 配置解耦，不改变 pgvector 数据契约。
- `README.md` 和配置示例：增加 Provider 选择说明。
- AgentScope MCP、Test Runner、mailbox、dispatch 和 workflow 状态机不在本变更范围内。

## 8. 风险与关注点

- ⚠️ NewAPI 是外部网关，模型名、费用、限流和数据处理边界由该网关及上游渠道决定，上线前需人工复核。
- ⚠️ 当前地址不可触达，不得用增加超时、无限重试或回退其他 Provider 掩盖。
- OpenAI 兼容不代表所有扩展字段都兼容，探针与正式调用必须使用标准字段集。
- NewAPI 返回的 `reasoning_content` 是可选扩展字段，不应成为 AgentScope 主流程成功的必要条件。
- 若 AgentScope `2.0.3` OpenAI 适配器与 NewAPI 实际流式返回不兼容，必须先记录协议差异，不盲目升级 AgentScope。

## 8.5 测试策略

- **测试范围**：配置绑定、Provider 分流、Model 工厂、Function Calling 探针、Memory 解耦、凭据脱敏、默认回归。
- **覆盖率目标**：新增 Provider 分支、校验分支和失败分类均有自动化测试；不以真实 NewAPI 可达性作为单元测试前置。
- **独立 Test Spec**：是，见同目录 `test-spec.md`。

## 9. 待澄清

- [x] 首个真实 NewAPI 验证模型固定为 `qwen3.8`，不使用最初协议示例中的 `coding-x-ai/grok-4.7`。
- [x] 请求域名暂时保持空配置；域名可用前，`provider=newapi` 必须因空 `NEWAPI_BASE_URL` 快速失败。
- [x] 首版接受父 Agent 和子 Agent 使用同一个 Provider。
- [x] 用户已于 2026-10-22 完成 HARD-GATE 确认并授权执行 Task 1。

## 10. 技术决策

| 决策 | 选项 | 原因 |
|------|------|------|
| Provider 开关 | `dashscope` / `newapi`，默认 `dashscope` | 显式、可审计，保持向后兼容 |
| NewAPI 适配 | AgentScope OpenAI 兼容 Model | NewAPI 使用 OpenAI Chat Completions，不应复用 DashScope 原生适配器 |
| 凭据 | Provider 独立环境变量 | 避免隐式选择、误发数据和费用归属不明 |
| Function Calling 验证 | 通用标准探针 | 不依赖文本回答或 HTTP 200 假阳性 |
| NewAPI 候选模型 | 首版禁止自动轮询 | 现有候选表是 DashScope 专用名称 |
| Memory | 独立 DashScope 配置 | 保持 `text-embedding-v3` 和 1024 维 pgvector 契约 |
| 不可达处理 | fail-fast，不自动回退 | 防止掩盖网络/鉴权问题和意外调用其他 Provider |

## 11. 执行日志

| Task | 状态 | 实际改动文件 | 备注 |
|------|------|----------------|------|
| 需求建档 | 已完成 | `spec.md`、`tasks.md`、`test-spec.md`、`log.md` | 仅文档，未修改源码，未调用 NewAPI，未运行 E2E |
| Task 1 | 已完成 | `QinglingTeamProperties.java`、`RuntimeConfiguration.java`、`application.yml`、`RuntimeBootstrapTest.java`、`DashScopeModelSelectorTest.java` | 建立 Provider/凭据/Memory 配置边界和 fail-fast；未接入 NewAPI Model |
| Task 2 | 已完成 | `pom.xml`、starter `pom.xml`、`AgentModelFactory.java`、`RuntimeConfiguration.java`、`AgentModelFactoryTest.java` | 已核对 AgentScope 2.0.3 实际 Builder API；两个 Provider 均可离线构造 |
| Task 3 | 已完成 | `ModelAvailabilitySelector.java`、`ModelAvailabilitySelectorTest.java` 及引用方 | 通用标准请求、本地 Stub、Function Calling 与稳定失败分类已覆盖 |
| Task 4 | 已完成 | `MemoryExtractionClient.java`、`PgVectorMemoryIndexer.java`、`RuntimeConfiguration.java`、`RuntimeBootstrapTest.java` | Memory 关闭不创建远程客户端；开启时只消费独立 DashScope 配置 |
| Task 5 | 已完成 | `RealTeamE2EDriver.java`、`RealTeamE2EDriverTest.java` | Provider 凭据分流、证据字段和 NewAPI/SK/Header 脱敏已覆盖；未运行 E2E |
| Task 6 | 已完成 | `README.md`、`config.yaml.template` | 补充 Provider、空地址、无回退与 Memory 独立边界 |
| Task 7 | 已完成 | 全量验证 | 定向测试、`mvn verify`（292 tests）与 `git diff --check` 全部通过 |
| Task 8 | 未开始 | - | Base URL 为空且未获得真实探针单独授权 |

## 12. 审查结论

Task 1～7 已实现并通过定向测试、292-test `mvn verify` 与 `git diff --check`。由于 Base URL 仍为空，Task 8 真实探针和 Task 18 E2E 均未执行，本文档不表示 NewAPI 真实连通性已通过。

## 13. 确认记录（HARD-GATE）

- **确认时间**：2026-10-22
- **确认人**：用户
- **确认说明**：确认首个真实模型使用 `qwen3.8`、请求域名暂时为空、父子 Agent 共用同一 Provider，并授权从 Task 1 开始执行；未授权真实探针或 E2E。
