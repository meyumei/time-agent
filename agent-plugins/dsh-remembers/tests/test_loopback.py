"""服务只监听回环，数据目录固定在 /root/.timeagent-dsh/memory。

运行（在 agent-plugins/dsh-remembers 下）：
    python3 -m unittest discover -s tests -p "test_*.py" -v
"""

from __future__ import annotations

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE = os.path.join(os.path.dirname(HERE), "service")
if SERVICE not in sys.path:
    sys.path.insert(0, SERVICE)

import netcfg  # noqa: E402
import paths  # noqa: E402


class LoopbackTest(unittest.TestCase):
    def test_default_is_loopback(self):
        self.assertEqual(netcfg.resolve_bind_host(None), ("127.0.0.1", None))
        self.assertEqual(netcfg.resolve_bind_host(""), ("127.0.0.1", None))
        self.assertEqual(netcfg.resolve_bind_host("127.0.0.1"), ("127.0.0.1", None))

    def test_wildcard_is_rejected(self):
        host, reason = netcfg.resolve_bind_host("0.0.0.0")
        self.assertEqual(host, "127.0.0.1")
        self.assertIsNotNone(reason)
        host, reason = netcfg.resolve_bind_host("192.168.1.10")
        self.assertEqual(host, "127.0.0.1")
        self.assertIsNotNone(reason)

    def test_data_dir_default(self):
        self.assertEqual(paths.resolve_data_dir(None), "/root/.timeagent-dsh/memory")
        self.assertEqual(
            paths.file_in(paths.resolve_data_dir(None), "proactive.json"),
            os.path.join("/root/.timeagent-dsh/memory", "proactive.json"),
        )


if __name__ == "__main__":
    unittest.main()
