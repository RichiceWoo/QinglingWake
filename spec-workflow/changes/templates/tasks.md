# 任务拆分 — 需求名称

> 建议顺序：数据模型 → 接口协议 → 领域与基础设施 → 应用编排 → Web/WS 入口  
> 每个任务尽量为可独立提交的一小步（例如 3～5 个文件量级）。  
> **Git**：由开发者在本地按需 `commit`；本文件勾选表示逻辑完成。

## 前置条件

- [ ] 依赖项（配置、DB、外部服务）已就绪

## Task 1: 任务名

- **目标**：
- **涉及文件**:
  - `path/to/File.java` — 新增/修改，说明
- **关键签名**（若适用）:

```java
// 示例
public ResultDTO doSomething(Long id, String type);
```

- **依赖**: 无 / Task N
- **验收标准**:
- **验证命令（示例）**: `mvn -pl benefit-starter -am -DskipTests compile`（按实际模块调整）

- [ ] 完成

## Task 2: …

（按需追加）

---

## 汇总（/apply 全部完成后填写）

- **变更摘要**:
- **总文件数**:
- **Spec-Plan 偏差记录**:
- **遗留问题**:
