# 变更日志 — Agent 模型供应商切换与 Memory 独立配置 1.0.0

> 本日志不记录任何真实 SK、Authorization Header 或未脱敏外部响应。

## 时间线

| 时间 | 阶段 | 事件 | 备注 |
|------|------|------|------|
| 2026-10-21 | Research | 审计当前 Agent Model、Function Calling 探针、Memory 和 E2E 凭据链路 | 确认当前实现固定使用 DashScope |
| 2026-10-21 | Protocol | 记录 NewAPI OpenAI 兼容协议 | `POST /v1/chat/completions`、Bearer SK、显式 model；未保存真实 SK |
| 2026-10-21 | Constraint | 记录 `https://newapi.ejoy.com/v1` 当前不可触达 | 未发起网络请求，未声称连通成功 |
| 2026-10-21 | Propose | 创建 `agent-newapi-provider-1.0.0` 四件变更集 | 只修改 spec-workflow 文档，等待用户 HARD-GATE 确认 |
| 2026-10-22 | Confirm | 用户确认首个真实模型为 `qwen3.8`、请求域名暂时为空、父子 Agent 共用 Provider | 授权执行 Task 1，未授权真实探针或 E2E |
| 2026-10-22 | Task 1 | 新增 Provider、DashScope/NewAPI、Memory DashScope 类型安全配置和 fail-fast 校验 | 保留 DashScope 默认行为；空 NewAPI 域名只在选择 NewAPI 时拒绝启动 |
| 2026-10-22 | Test | 定向 16 tests 与 starter 全量回归通过 | 六个 Reactor 模块 BUILD SUCCESS；未访问外网 |
| 2026-10-22 | Task 2 | 引入 AgentScope 2.0.3 OpenAI 扩展并新增 Provider Model 工厂 | 通过 `javap` 核对真实 Builder API；未升级 AgentScope |
| 2026-10-22 | Task 3 | 将 DashScope 专用选择器替换为 Provider 感知通用探针 | 本地 Stub 验证路径、Bearer、标准字段、tool_calls、4xx/5xx 与普通文本假阳性 |
| 2026-10-22 | Task 4 | Memory 消费链改为独立 DashScope 配置 | db-dsn 为空时不创建远程客户端；开启时保持 1024 维契约 |
| 2026-10-22 | Task 5 | E2E Driver 增加 Provider 配置分流、摘要和脱敏 | 未运行真实探针或 E2E |
| 2026-10-22 | Task 6 | 更新 README 与配置模板 | NewAPI 示例仅使用 `sk-xxx` 和虚构域名，记录当前地址为空 |
| 2026-10-22 | Test | 模块定向回归通过 | starter 65 tests，0 failure/error/skip；六个 Reactor 模块成功 |
| 2026-10-22 | Task 7 | 完成无真实 NewAPI 依赖的完整验证 | `mvn verify`：292 tests，0 failure/error/skip；`git diff --check`：exit 0 |
| 2026-10-22 | Gate | 保持 Task 8 与 Task 18 E2E 未执行 | NewAPI Base URL 为空，且真实探针需要用户另行明确授权 |

## 技术决策

| 决策 | 选择 | 放弃的方案 | 原因 |
|------|------|------------|------|
| 切换方式 | 显式 `QINGLING_MODEL_PROVIDER` | 根据 Key 是否存在自动推断 | 自动推断会导致调用去向、数据边界和费用归属不明 |
| NewAPI Model | AgentScope OpenAI 兼容扩展 | 仅替换 `DashScopeChatModel` Base URL | DashScope 适配器的原生路径不等于 `/v1/chat/completions` |
| 配置边界 | DashScope/NewAPI/Memory 凭据分离 | 单一通用 API Key | 防止跨 Provider 误用凭据 |
| NewAPI 模型选择 | 显式指定，不轮询 DashScope 候选表 | 复用现有免费模型选择 | 候选名称是供应商专用配置 |
| Memory | 继续独立使用 DashScope | 随 Agent 切换 NewAPI | 保持 Embedding 模型和 1024 维 pgvector 契约 |
| 当前不可达 | 先用本地 Stub，真实探针单独授权 | 延长超时、无限重试或自动回退 | 不用运行时策略掩盖网络或鉴权问题 |

## 踩坑记录

| 问题 | 原因 | 解决方案 | 沉淀到 knowledge？ |
|------|------|----------|-------------------|
| 仅配置 NewAPI Base URL 不能安全复用 DashScope Model | 两者的 AgentScope 适配器和请求路径不同 | 引入 OpenAI 兼容扩展并通过 Provider 工厂分流 | 是，已由构造测试验证 |
| Agent 模型切换可能意外影响 Memory | `memoryIndexer` 原先复用 Agent Key 和模型 | 为 Memory 建立独立 DashScope 配置，并为关闭态使用零远程客户端 | 是，已由启动测试验证 |
| HTTP 200 可能被误判为模型可用 | 模型可能只返回普通文本 | 要求固定 Function Calling 并检查 `tool_calls` | 否，已写入 spec/test-spec |
| 当前 NewAPI 地址不可触达 | 待确认网络、DNS、白名单或服务端状态 | 本阶段只记录制约，不测试、不伪造成功 | 否 |

## 知识发现

- [ ] **OpenAI-compatible provider**: Provider 兼容性应由实际 endpoint、鉴权、Function Calling 和流式响应联合验证，不能只根据 API 名称判定。
- [ ] **Memory provider isolation**: 生成模型和 Embedding/Memory 模型应使用独立的供应商、凭据和失败边界。

## Spec-Code 偏差记录

| 偏差点 | Spec 预期 | 实际情况 | 处理方式 |
|--------|-------------|----------|----------|
| Task 1 阶段性边界 | 配置模型先落地，Model/探针/Memory 消费链按后续任务接入 | Task 2～4 已移除 `Agent#apiKey` 与 `Agent#modelProbeUrl` 过渡访问器 | 已按计划完成，无遗留偏差 |
| Task 5 定向命令 | `mvn -pl qingling-team-starter -Dtest=RealTeamE2EDriverTest test` | 单模块直接执行时 Reactor 依赖未 install，无法解析 | 使用 `-am` 从聚合根执行等价测试；测试范围和断言未放宽 |

## 代码质量备忘

- Task 1～6 已修改配置、运行时、测试和文档；所有新增或修改源码声明均按 `chinese-code-comments` skill 添加相邻、准确的中文注释。
- 实现时不得修改 Task 18 的运行时可靠性断言、超时和状态机边界来迁就 NewAPI。
