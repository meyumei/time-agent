"""App 主动消息出口：scheduler._inject 必须 POST 到 host 的 /proactive。

运行（在 agent-plugins/dsh-remembers 下）：
    python3 -m unittest discover -s tests -p "test_*.py" -v
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE = os.path.join(os.path.dirname(HERE), "service")
if SERVICE not in sys.path:
    sys.path.insert(0, SERVICE)

import scheduler  # noqa: E402


class _Capture(BaseHTTPRequestHandler):
    seen: list[dict] = []

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8")
        _Capture.seen.append({
            "path": self.path,
            "auth": self.headers.get("Authorization"),
            "body": json.loads(body) if body else None,
        })
        payload = b'{"ok":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):  # 静音
        pass


class BridgeTest(unittest.TestCase):
    def setUp(self):
        _Capture.seen = []
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), _Capture)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.tmp = tempfile.TemporaryDirectory()
        self.env_backup = {
            k: os.environ.get(k)
            for k in ("TIME_AGENT_BRIDGE", "TIME_AGENT_TOKEN", "CMEM_INJECT_PATH")
        }
        os.environ["TIME_AGENT_BRIDGE"] = f"127.0.0.1:{self.port}"
        os.environ["TIME_AGENT_TOKEN"] = "test-token"
        # 就算 QQ 文件通道配了，App 桥优先，不应再写文件
        os.environ["CMEM_INJECT_PATH"] = os.path.join(self.tmp.name, "inject.jsonl")

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        for key, value in self.env_backup.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        self.tmp.cleanup()

    def test_inject_posts_to_bridge_not_file(self):
        sched = scheduler.Scheduler(store=None, data_dir=self.tmp.name)
        sched._inject("（你自己待着）想看看用户在不在。", silent=False)

        self.assertEqual(len(_Capture.seen), 1, "应恰好发出一次 HTTP 回调")
        req = _Capture.seen[0]
        self.assertEqual(req["path"], "/proactive")
        self.assertEqual(req["auth"], "Bearer test-token")
        self.assertEqual(req["body"]["source"], "proactive")
        self.assertEqual(req["body"]["text"], "（你自己待着）想看看用户在不在。")
        self.assertNotIn("sourceUrl", req["body"])
        self.assertFalse(
            os.path.exists(os.environ["CMEM_INJECT_PATH"]),
            "桥可用时不应再写 QQ inject 文件",
        )

    def test_source_url_passthrough(self):
        sched = scheduler.Scheduler(store=None, data_dir=self.tmp.name)
        sched._inject("看到一个视频。", source_url="https://www.bilibili.com/video/BV1xx")
        self.assertEqual(
            _Capture.seen[0]["body"]["sourceUrl"],
            "https://www.bilibili.com/video/BV1xx",
        )


if __name__ == "__main__":
    unittest.main()
