"""绑定地址解析。

时间 Agent 的记忆服务只给本机插件用：主代理通过 CMEM_HOST/CMEM_PORT 启动它，
但不允许把 5432 那种「监听所有网卡」的默认行为带进来。CMEM_HOST 只接受回环
地址；其他值一律拒绝并退回 127.0.0.1，避免记忆库意外暴露到局域网。
"""

from __future__ import annotations

LOOPBACK_HOSTS = frozenset({"127.0.0.1", "127.0.0.2", "::1", "localhost"})


def is_loopback(host: str) -> bool:
    raw = str(host or "").strip().lower()
    if raw in LOOPBACK_HOSTS:
        return True
    # 127.0.0.0/8 都是回环；只认这一小段，别把 0.0.0.0 放进来。
    parts = raw.split(".")
    return len(parts) == 4 and parts[0] == "127" and all(p.isdigit() for p in parts)


def resolve_bind_host(raw: str | None) -> tuple[str, str | None]:
    """返回 (实际绑定地址, 拒绝原因或 None)。"""
    if raw is None or str(raw).strip() == "":
        return "127.0.0.1", None
    host = str(raw).strip()
    if is_loopback(host):
        return host, None
    return "127.0.0.1", f"CMEM_HOST={host!r} 不是回环地址，已强制改为 127.0.0.1"
