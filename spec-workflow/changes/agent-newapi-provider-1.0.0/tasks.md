# 任务拆分 — Agent 模型供应商切换与 Memory 独立配置 1.0.0

> 执行顺序：Task 1 → Task 8，不得跳过前置验收。
> **HARD-GATE**：用户已于 2026-10-22 确认方案并授权执行 Task 1；后续仍严格按任务顺序推进。
> **Git**：本变更不自动 commit/push，除非用户另行明确要求。

## 前置条件

- [x] `spec.md`、`tasks.md`、`test-spec.md` 已经用户确认。
- [x] 开始实现前已运行 `git status` 和 `git diff`，保留全部现有修改。
- [x] 进入 Task 2 前确认 AgentScope `2.0.3` OpenAI 扩展的实际 Builder API，不凭假设写代码。
- [x] 已明确 NewAPI 请求域名暂时为空，单元和集成测试优先使用本地 Stub Server。

## Task 1: 建立 Provider 配置模型和 fail-fast 校验

- **目标**：将 Agent、DashScope、NewAPI 和 Memory DashScope 配置显式分离。
- **涉及文件**:
  - `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/config/QinglingTeamProperties.java`
  - `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/config/RuntimeConfiguration.java`
  - `qingling-team-starter/src/main/resources/application.yml`
  - 相关配置测试
- **关键内容**:
  - Provider 枚举只接受 `dashscope` 和 `newapi`。
  - DashScope 与 NewAPI 分别持有 Key/Base URL，不跨 Provider 回退。
  - Memory 持有独立 DashScope Key、Base URL、extract model 和 embedding model。
  - NewAPI URL 统一归一化为 `/v1` 根路径。
- **依赖**: 无
- **验收标准**:
  - 默认 Provider 是 DashScope，现有环境变量仍可启动。
  - NewAPI 缺 Key/Base URL/model、URL 携带 user-info/query/fragment 或开启免费模型选择时快速失败。
  - 异常不包含凭据。
- **验证命令**: `mvn -pl qingling-team-starter -am -DskipITs test`

- [x] 完成（2026-10-22；配置与 fail-fast 已落地，NewAPI Model 构造留待 Task 2）

## Task 2: 接入 AgentScope OpenAI 兼容 Model 并建立工厂

- **目标**：根据 Provider 创建正确的 AgentScope Model，不复用 DashScope Model 伪装 NewAPI。
- **涉及文件**:
  - `qingling-team/pom.xml`
  - `qingling-team/qingling-team-starter/pom.xml`
  - `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/config/RuntimeConfiguration.java`
  - 新增 Provider Model 工厂及其测试
- **关键内容**:
  - 引入与 AgentScope `2.0.3` 对齐的 `agentscope-extensions-model-openai`。
  - DashScope 分支继续创建 `DashScopeChatModel`。
  - NewAPI 分支创建 OpenAI 兼容 Model，注入 Base URL、SK、model、token 上限和上下文边界。
- **依赖**: Task 1
- **验收标准**:
  - 两个 Provider 分支可在无真实网络条件下通过构造测试。
  - NewAPI 分支不包含 DashScope 原生 URL 和 `enable_thinking`。
- **验证命令**: `mvn -pl qingling-team-starter -am -DskipITs test`

- [x] 完成（2026-10-22；使用 AgentScope 2.0.3 OpenAI 扩展并通过无网络构造测试）

## Task 3: 通用化模型选择与 Function Calling 探针

- **目标**：将 `DashScopeModelSelector` 重构为 Provider 感知的通用组件。
- **涉及文件**:
  - `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/config/DashScopeModelSelector.java`
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/config/DashScopeModelSelectorTest.java`
  - 重命名后的选择器/探针文件
- **关键内容**:
  - 通用请求只包含 OpenAI 标准的 model/messages/tools/tool_choice/stream/max_tokens。
  - 检查固定函数名的 `tool_calls`，区分 `available`、`http_4xx`、`http_5xx`、`transport_error`、`function_call_missing`。
  - DashScope 可保留候选轮询；NewAPI 只验证显式模型。
- **依赖**: Task 1、Task 2
- **验收标准**:
  - 本地 Stub Server 能验证 URL、Bearer Header、请求体和 Function Calling 结果。
  - 失败日志不输出请求 Header 或响应正文。
- **验证命令**: `mvn -pl qingling-team-starter -am -DskipITs test`

- [x] 完成（2026-10-22；通用探针、本地 Stub、标准字段与稳定失败分类通过）

## Task 4: 将 Memory 与 Agent Provider 完全解耦

- **目标**：Agent 切换 NewAPI 时，Memory 仍仅使用独立 DashScope 配置。
- **涉及文件**:
  - `qingling-team-starter/src/main/java/cn/org/chris/wake/starter/config/RuntimeConfiguration.java`
  - `qingling-team-infra/src/main/java/cn/org/chris/wake/infra/memory/MemoryExtractionClient.java`
  - Memory 配置与客户端测试
- **关键内容**:
  - 禁止将 Agent NewAPI SK/Base URL/model 传入 Memory。
  - pgvector 关闭时不要求 Memory DashScope Key，不产生远程请求。
  - pgvector 开启时校验独立 Memory DashScope Key，保持 1024 维 Embedding 契约。
- **依赖**: Task 1
- **验收标准**:
  - Agent Provider 切换不改变 Memory 的 endpoint、model 和 key 来源。
  - Memory 开关的有效/无效组合均有测试。
- **验证命令**: `mvn -pl qingling-team-infra,qingling-team-starter -am -DskipITs test`

- [x] 完成（2026-10-22；Memory 独立 DashScope 配置与关闭态零远程客户端已落地）

## Task 5: 强化 E2E 配置识别、证据与脱敏

- **目标**：使 E2E Driver 能按 Provider 检查凭据和报告直接原因，但不在本 Task 运行真实 E2E。
- **涉及文件**:
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/e2e/RealTeamE2EDriver.java`
  - `qingling-team-starter/src/test/java/cn/org/chris/wake/starter/e2e/RealTeamE2EDriverTest.java`
  - E2E 配置示例（不写真实 SK）
- **关键内容**:
  - DashScope 检查 DashScope Key；NewAPI 检查 NewAPI Key/Base URL。
  - summary 只记录 Provider、model 和脱敏连通状态。
  - 脱敏覆盖 `NEWAPI_API_KEY`、`sk-...`、Authorization Header。
- **依赖**: Task 1、Task 3
- **验收标准**:
  - 单元测试证明不同 Provider 的凭据检查和脱敏正确。
  - 该 Task 不调用真实 NewAPI，不启动 Task 18 E2E。
- **验证命令**: `mvn -pl qingling-team-starter -Dtest=RealTeamE2EDriverTest test`

- [x] 完成（2026-10-22；E2E Provider 分流、摘要字段和凭据脱敏单测通过，未运行 E2E）

## Task 6: 补充 README 与运维配置说明

- **目标**：使用户可在不查看源码的情况下正确选择 Provider 并识别 Memory 的独立边界。
- **涉及文件**:
  - `qingling-team/README.md`
  - 实际存在的配置模板/部署示例
- **关键内容**:
  - DashScope 与 NewAPI 的最小环境变量示例。
  - 不得在文档中写入真实 SK。
  - 说明当前 NewAPI 不可触达、失败不回退、Memory 继续走 DashScope。
- **依赖**: Task 1-5
- **验收标准**:
  - README 中的配置名与代码一致。
  - 示例只使用 `sk-xxx` 占位符。
- **验证命令**: `git diff --check`

- [x] 完成（2026-10-22；README 与配置模板已补充，示例仅使用占位凭据）

## Task 7: 执行无真实 NewAPI 依赖的完整验证

- **目标**：在 NewAPI 不可达时完成可重复的协议和回归验证。
- **依赖**: Task 1-6
- **验收标准**:
  - 定向测试通过。
  - `mvn verify` 通过。
  - `git diff --check` 通过。
  - 未运行真实 NewAPI 探针和 E2E。
- **验证命令**:
  - `mvn -pl qingling-team-infra,qingling-team-starter -am -DskipITs test`
  - `mvn verify`
  - `git diff --check`

- [x] 完成（2026-10-22；定向测试、292-test `mvn verify` 与 `git diff --check` 全部通过）

## Task 8: 等待地址可达后执行真实最小探针

- **目标**：在用户另行授权、地址可达且 Task 7 全部通过后，只执行一次真实 NewAPI Function Calling 最小探针。
- **依赖**: Task 7、NewAPI 网络可达、用户显式授权
- **验收标准**:
  - 只通过 source 环境文件后检查变量非空，不打印内容。
  - 证明指定模型能返回真实 `tool_calls`。
  - 失败时记录脱敏的 HTTP/传输/协议原因，不启动 E2E。
- **限制**:
  - 当前不执行。
  - Task 8 通过也不自动授权 Task 18 E2E，E2E 仍需用户单独确认。

- [ ] 完成

---

## 汇总（/apply 全部完成后填写）

- **变更摘要**: Task 1～7 已完成；Agent 可显式选择 DashScope 或 NewAPI，Memory 保持独立 DashScope，E2E Driver 已识别 Provider 并强化脱敏。
- **总文件数**: 22 个逻辑文件（含 2 个重命名旧文件、4 个新增源码/测试文件和 4 个变更文档）。
- **Spec-Plan 偏差记录**: Task 5 指定的单模块 Maven 命令无法独立解析未 install 的 Reactor 依赖，实际使用 `-am` 运行等价定向测试；功能范围无偏差。
- **遗留问题**: NewAPI 真实地址仍为空，Task 8 保持未完成；未执行真实 NewAPI 探针或 Task 18 E2E。
