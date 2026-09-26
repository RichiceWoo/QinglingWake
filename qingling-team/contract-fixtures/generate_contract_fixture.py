#!/usr/bin/env python3
"""从只读 Python 源快照生成 Task 17 的确定性跨语言契约 fixture。"""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import hashlib
import importlib.util
import json
import sys
import tempfile
import uuid
from pathlib import Path
from types import ModuleType
from typing import Any


# Java 集成测试读取的默认 fixture 路径。
DEFAULT_OUTPUT = Path(
    "qingling-team-starter/src/test/resources/contract/python-baseline.json"
)

# Spec 第 2.2 节涉及的 Python 稳定契约源文件。
SOURCE_FILES = (
    "xiaopaw_team/feishu/session_key.py",
    "xiaopaw_team/models.py",
    "xiaopaw_team/session/manager.py",
    "xiaopaw_team/session/models.py",
    "xiaopaw_team/tools/mailbox.py",
    "xiaopaw_team/tools/event_log.py",
    "xiaopaw_team/cron/tasks_store.py",
    "xiaopaw_team/tools/feishu_bridge.py",
    "xiaopaw_team/tools/self_score.py",
    "xiaopaw_team/api/schemas.py",
    "xiaopaw_team/tools/log_query.py",
)


def load_module(name: str, path: Path) -> ModuleType:
    """从显式文件路径加载 Python 模块，避免修改或依赖源项目工作目录。"""
    specification = importlib.util.spec_from_file_location(name, path)
    if specification is None or specification.loader is None:
        raise RuntimeError(f"无法加载 Python 基线模块: {path}")
    module = importlib.util.module_from_spec(specification)
    sys.modules[name] = module
    specification.loader.exec_module(module)
    return module


def sha256(path: Path) -> str:
    """计算源文件 SHA-256，供无 Git 元数据的快照做可追溯校验。"""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def routing_fixture(session_key: ModuleType) -> list[dict[str, Any]]:
    """调用 Python 原实现生成 p2p、group 与 thread 路由基线。"""
    cases = (
        ("p2p", "ou_user", "oc_group", None),
        ("group", "ou_user", "oc_group", None),
        ("group", "ou_user", "oc_group", "omt_thread"),
    )
    return [
        {
            "chat_type": chat_type,
            "sender_id": sender_id,
            "chat_id": chat_id,
            "thread_id": thread_id,
            "expected": session_key.resolve_routing_key(
                chat_type, sender_id, chat_id, thread_id
            ),
        }
        for chat_type, sender_id, chat_id, thread_id in cases
    ]


def inbound_fixture(models: ModuleType) -> dict[str, Any]:
    """用 Python dataclass 生成带附件和 meta 的标准入站消息字段基线。"""
    attachment = models.Attachment("file", "file-key-1", "需求.txt")
    inbound = models.InboundMessage(
        routing_key="thread:oc_group:omt_thread",
        content="请评审附件",
        msg_id="om_message",
        root_id="om_root",
        sender_id="ou_user",
        ts=1_795_000_000_000,
        attachment=attachment,
        meta={"source": "feishu"},
    )
    return dataclasses.asdict(inbound)


def task_fixture(tasks_store: ModuleType) -> list[dict[str, Any]]:
    """固定时间与 UUID 后调用 Python tasks_store 生成三种调度 JSON。"""
    fixed_now = 1_795_000_000_000
    identifiers = iter((
        uuid.UUID("10000000-0000-0000-0000-000000000001"),
        uuid.UUID("20000000-0000-0000-0000-000000000002"),
        uuid.UUID("30000000-0000-0000-0000-000000000003"),
    ))
    tasks_store._now_ms = lambda: fixed_now
    tasks_store.uuid.uuid4 = lambda: next(identifiers)
    with tempfile.TemporaryDirectory() as directory:
        tasks_path = Path(directory) / "tasks.json"
        tasks_store.create_job(
            tasks_path,
            name="at-job",
            routing_key="team:pm",
            message="__wake__:new_mail:proj-contract",
            at_ms=fixed_now + 1_000,
        )
        tasks_store.create_job(
            tasks_path,
            name="every-job",
            routing_key="team:rd",
            message="__wake__:heartbeat",
            schedule_kind="every",
            every_ms=30_000,
            delete_after_run=False,
        )
        tasks_store.create_job(
            tasks_path,
            name="cron-job",
            routing_key="team:qa",
            message="nightly",
            schedule_kind="cron",
            expr="0 2 * * *",
            tz="Asia/Shanghai",
            delete_after_run=False,
        )
        return tasks_store.list_jobs(tasks_path)


def build_fixture(source_root: Path) -> dict[str, Any]:
    """装载 Python 基线实现并生成不含随机时间和随机标识的完整 fixture。"""
    session_key = load_module(
        "contract_session_key", source_root / "xiaopaw_team/feishu/session_key.py"
    )
    models = load_module("contract_models", source_root / "xiaopaw_team/models.py")
    self_score = load_module(
        "contract_self_score", source_root / "xiaopaw_team/tools/self_score.py"
    )
    schemas = load_module("contract_schemas", source_root / "xiaopaw_team/api/schemas.py")
    filelock_stub = ModuleType("filelock")
    filelock_stub.FileLock = lambda ignored: contextlib.nullcontext()
    sys.modules.setdefault("filelock", filelock_stub)
    tasks_store = load_module(
        "contract_tasks_store", source_root / "xiaopaw_team/cron/tasks_store.py"
    )
    breakdown = {
        "completeness": 0.9,
        "self_review": 0.8,
        "hard_constraints": 1.0,
        "clarity": 0.7,
        "timeliness": 0.9,
    }
    request = schemas.TestRequest(routing_key="p2p:ou_contract")
    hashes = {relative: sha256(source_root / relative) for relative in SOURCE_FILES}
    return {
        "_meta": {
            "source_directory": str(source_root.resolve()),
            "source_commit": None,
            "source_note": "源目录是无 .git 元数据的只读快照，以关键文件 SHA-256 标识版本",
            "sha256": hashes,
        },
        "routing": routing_fixture(session_key),
        "inbound_message": inbound_fixture(models),
        "session_index": {
            "p2p:ou_contract": {
                "active_session_id": "s-contract002",
                "sessions": [
                    {
                        "id": "s-contract001",
                        "created_at": "2026-09-26T00:00:00Z",
                        "verbose": False,
                        "message_count": 2,
                    },
                    {
                        "id": "s-contract002",
                        "created_at": "2026-09-26T00:01:00Z",
                        "verbose": True,
                        "message_count": 0,
                    },
                ],
            }
        },
        "tasks": task_fixture(tasks_store),
        "self_score": {
            "breakdown": breakdown,
            "expected": self_score.compute_self_score(breakdown),
        },
        "test_api": {
            "request_defaults": request.model_dump(),
            "response_fields": list(schemas.TestResponse.model_fields),
        },
    }


def main() -> int:
    """生成 fixture，或用 --check 验证已提交文件与 Python 当前快照一致。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    arguments = parser.parse_args()
    generated = json.dumps(
        build_fixture(arguments.source), ensure_ascii=False, indent=2, sort_keys=True
    ) + "\n"
    if arguments.check:
        committed = arguments.output.read_text(encoding="utf-8")
        if committed != generated:
            raise SystemExit("已提交 fixture 与 Python 基线不一致，请重新生成")
        return 0
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(generated, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
