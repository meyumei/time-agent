"""数据目录。

时间 Agent 的所有记忆都放在 /root/.timeagent-dsh/memory 下（人格、L0-L4、
主动消息状态、调度器状态都在这里）。原版默认是 /data（容器路径），直接搬过来
会在真机上失败，所以把默认值固定成 App 实际使用的目录；CMEM_DATA_DIR 仍可覆盖。
"""

from __future__ import annotations

import os

DEFAULT_DATA_DIR = "/root/.timeagent-dsh/memory"


def resolve_data_dir(raw: str | None = None) -> str:
    value = str(raw or "").strip()
    if not value:
        return DEFAULT_DATA_DIR
    return os.path.abspath(value)


def file_in(data_dir: str, name: str) -> str:
    return os.path.join(data_dir, name)
