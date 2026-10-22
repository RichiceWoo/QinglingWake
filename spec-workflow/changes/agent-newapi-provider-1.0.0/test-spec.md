# 测试 Spec — Agent 模型供应商切换与 Memory 独立配置 1.0.0

> status: apply
> created: 2026-10-21

## 0. 测试原则

- **展示证据**：每条命令记录实际 exit code、测试数和失败摘要，不在未执行时标记通过。
- **协议先行**：NewAPI 当前不可达，使用本地 HTTP Stub Server 验证 OpenAI 兼容协议。
- **不触碰真实凭据**：单元/集成测试使用占位 Key，不读取和打印真实 SK。
- **默认兼容**：DashScope 是默认 Provider，既有回归不得因 NewAPI 接入被放宽。
- **Memory 独立**：任何 Agent Provider 组合都不得改变 Memory DashScope 的配置来源和向量维度。
- **E2E 隔离**：本变更实现和常规验证阶段不执行 Task 18 E2E。

## 1. 测试框架

| 项目 | 值 |
|------|-----|
| JUnit | 5 |
| Mock | Mockito，与项目现有依赖一致 |
| HTTP 协议测试 | JDK 本地 HTTP Server 或项目已有的无外网 Stub |
| 模块 | `qingling-team-starter`、`qingling-team-infra` |
| 真实网络 | 默认禁止；只有 Task 8 单次最小探针可使用 |

## 2. 覆盖范围

### P0 — 必须通过

| 类/组件 | 方法或场景 | 输入 | Mock/前置 | 预期 |
|---------|------------|------|-------------|------|
| 配置绑定 | 默认 Provider | 未配置 Provider | 占位 DashScope Key | 解析为 `dashscope`，保持旧模式 |
| 配置校验 | NewAPI 有效配置 | provider/key/base-url/model 齐全 | 无外网 | 启动校验通过 |
| 配置校验 | NewAPI 缺 Key/Base URL/model | 缺任一项 | 无 | 在外部组件构造前 fail-fast |
| 配置校验 | 非法 Provider | `unknown` | 无 | 拒绝启动，不隐式选择 Key |
| URL 归一化 | NewAPI `/v1` | 有/无尾随斜杠 | 无 | 唯一派生 `/v1/chat/completions` |
| URL 安全 | user-info/query/fragment | 恶意或误配 URL | 无 | 拒绝启动，异常不回显凭据 |
| Provider Model 工厂 | DashScope 分支 | provider=dashscope | 占位 Key | 构造 DashScope Model |
| Provider Model 工厂 | NewAPI 分支 | provider=newapi | 占位 SK/Base URL | 构造 OpenAI 兼容 Model，不构造 DashScope Model |
| Function Calling 探针 | NewAPI 成功 | HTTP 200 + 指定 tool_call | 本地 Stub | 返回 available |
| Function Calling 探针 | 文本回答假阳性 | HTTP 200 但无 tool_calls | 本地 Stub | `function_call_missing` |
| Function Calling 探针 | 鉴权失败 | HTTP 401/403 | 本地 Stub | 非重试失败，无凭据泄漏 |
| Function Calling 探针 | 路径/模型错误 | HTTP 404/400 | 本地 Stub | 报告直接状态，不回退 DashScope |
| Function Calling 探针 | 传输超时 | 无响应 | 本地 Stub | 有界等待，返回 `transport_error` |
| Function Calling 请求 | 标准字段 | NewAPI 模式 | 捕获请求 JSON | 不包含 `enable_thinking` 等 DashScope 专用字段 |
| 免费模型选择 | NewAPI 禁用 | provider=newapi + enabled=true | 无 | fail-fast，不轮询 DashScope 候选表 |
| Memory 解耦 | Agent 使用 NewAPI | NewAPI Agent + DashScope Memory | Stub/捕获参数 | Memory 仅获取独立 DashScope 配置 |
| Memory 关闭 | db-dsn 为空 | 无 Memory Key | 无 | 不因 Memory Key 缺失阻止 Agent 启动，无远程请求 |
| Memory 开启 | db-dsn 非空 | 缺 Memory DashScope Key | 无 | fail-fast |
| 脱敏 | Key/Header/响应 | `sk-test-secret` | 捕获日志与证据 | 任何输出不包含原文 |
| 默认回归 | 旧 DashScope 配置 | 不设 Provider | 既有测试 | 行为与改造前一致 |

### P1 — 建议

| 类/组件 | 场景 | 预期 |
|---------|------|------|
| OpenAI 兼容 Model | 本地 Stub 的非流式返回 | AgentScope 可正常解析 content 和 tool_calls |
| OpenAI 兼容 Model | 本地 Stub 的流式返回 | 不因 `reasoning_content` 存在或缺失失败 |
| E2E Driver | Provider 凭据分流 | NewAPI 不再要求 DashScope Agent Key，但 Memory 开启时仍单独要求 |
| 证据摘要 | 失败报告 | 保留 provider/model/status，删除 Key/Header/响应正文 |

### P2 — 可选

- 为未来其他 OpenAI 兼容网关保留内部扩展点，但本版本配置值只公开 `newapi`。
- 指标中按 Provider 统计成功/失败，不使用 Key 或完整 URL 作为 label。

### 不测试（本阶段）

- 不测试 NewAPI 的实时可达性：当前已知不可触达，等待 Task 8 和用户单独授权。
- 不运行 Task 18 真实 E2E：本变更只在最小真实探针通过后才具备后续前置，且 E2E 需再次明确授权。
- 不测试父子 Agent 跨 Provider 调度：不在 1.0.0 范围。
- 不测试 NewAPI Embedding：Memory 明确继续使用 DashScope。

## 3. 执行计划

- [x] 运行现有配置、模型选择、Memory 和 E2E Driver 单元测试，记录基线。
- [x] 为 Provider 配置和工厂新增测试。
- [x] 用本地 Stub 覆盖 NewAPI Chat Completions 和 Function Calling 协议。
- [x] 验证 Memory 与 Agent Provider 完全解耦。
- [x] 执行完整 `mvn verify`。
- [x] 执行 `git diff --check`。
- [ ] 在地址可达且获得单独授权后，只执行一次真实 Function Calling 最小探针。
- [x] 不在本变更自动执行 E2E。

## 4. 常用命令

```bash
cd /Users/qingling/workspace/IdeaProjects/QinglingWake/qingling-team

mvn -pl qingling-team-infra,qingling-team-starter -am -DskipITs test
mvn verify
git diff --check
```

真实探针的具体命令必须在实现完成后由新增的受控测试入口提供，不得将 SK 直接写入命令历史或 Maven 参数。

## 5. 实际结果

| 时间 | 命令 | 结果 | 证据/备注 |
|------|------|------|-----------|
| 2026-10-21 | 未执行 | 待确认 | 本轮只创建变更文档，未运行测试、真实探针或 E2E |
| 2026-10-22 | `mvn -pl qingling-team-starter -am -DskipITs -Dtest=RuntimeBootstrapTest,DashScopeModelSelectorTest -Dsurefire.failIfNoSpecifiedTests=false test` | BUILD SUCCESS；16 tests，0 failure/error/skip | 覆盖默认 DashScope、NewAPI 独立 Key、空/危险/损坏 URL、显式模型、免费选择禁用、Memory 独立 Key 和地址归一化 |
| 2026-10-22 | `mvn -pl qingling-team-starter -am -DskipITs test` | BUILD SUCCESS；六个 Reactor 模块全部成功；starter 57 tests，0 failure/error/skip | 未访问真实 NewAPI，未运行 E2E |
| 2026-10-22 | `mvn -pl qingling-team-starter -am -DskipITs -Dtest=AgentModelFactoryTest,ModelAvailabilitySelectorTest -Dsurefire.failIfNoSpecifiedTests=false test` | BUILD SUCCESS；11 tests，0 failure/error/skip | 覆盖两个 Model 分支和本地 Stub 标准 Function Calling 协议 |
| 2026-10-22 | `mvn -pl qingling-team-infra,qingling-team-starter -am -DskipITs test` | BUILD SUCCESS；六个 Reactor 模块全部成功；starter 65 tests，0 failure/error/skip | 覆盖 Memory 独立配置、E2E Provider 凭据分流与脱敏；未访问真实 NewAPI，未运行 E2E |
| 2026-10-22 | `mvn verify` | BUILD SUCCESS；六个 Reactor 模块全部成功；292 tests，0 failure/error/skip；总耗时 16.628s | 默认回归；未启用真实探针或 E2E profile |
| 2026-10-22 | `git diff --check` | exit 0 | 无空白错误 |
