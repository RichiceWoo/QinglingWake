# Task 17 Python 契约基线来源

- 源目录：`/Users/qingling/workspace/IdeaProjects/kid0317/xiaopaw-team-main`
- 源版本：该目录是没有 `.git` 元数据的只读快照，因此 `source_commit` 明确记录为 `null`。
- 版本替代证据：`python-baseline.json._meta.sha256` 记录 Spec 2.2 涉及的 11 个 Python 源文件 SHA-256。
- 生成脚本：`contract-fixtures/generate_contract_fixture.py`
- 生成命令：

```bash
cd qingling-team
python3 contract-fixtures/generate_contract_fixture.py \
  --source /Users/qingling/workspace/IdeaProjects/kid0317/xiaopaw-team-main
```

- 一致性检查：将上述命令追加 `--check`，不会修改源项目，只比较已提交 fixture。
- 不稳定字段处理：固定时间、UUID、sessionId、checkpointId；不访问模型、飞书、数据库或网络。
