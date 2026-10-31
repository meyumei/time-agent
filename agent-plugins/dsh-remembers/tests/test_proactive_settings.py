"""主动话题设置（/proactive + Scheduler）的合成测试。

全部在进程内跑：不监听端口、不连真实网络、不碰手机/QQ/真实数据目录。
Handler 的路由被直接驱动（_do_GET / _do_POST），配置写到临时目录。

覆盖：
  1. GET /proactive 原样返回配置对象，缺字段回落默认，未知键不丢
  2. POST /proactive 接受 App 提交的子集并整体保存，没提交的字段保持原值
  3. 非法输入 HTTP 400 + 明确指出字段，且文件一个字节都不改（原子保存）
  4. 打开 B 站浏览 ≠ 授权写操作（biliComment / bili_tools.json 不动）
  5. Scheduler：跨午夜窗口、start==end 全天、安静窗口不唤醒也不探索、关闭开关生效

运行（在 agent-plugins/dsh-remembers 下）：
    python -m unittest discover -s tests -p test_proactive_settings.py -v
"""

from __future__ import annotations

import datetime
import io
import json
import os
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE = os.path.join(os.path.dirname(HERE), "service")
if SERVICE not in sys.path:
    sys.path.insert(0, SERVICE)

import scheduler  # noqa: E402
import server  # noqa: E402


class _Harness(server.Handler):
    """直接驱动 Handler 路由，不发真实 HTTP 请求。"""

    def __init__(self, path: str, payload: dict | None = None):
        self.path = path
        raw = b"" if payload is None else json.dumps(payload).encode("utf-8")
        self.headers = {"Content-Length": str(len(raw))}
        self.rfile = io.BytesIO(raw)
        self.wfile = io.BytesIO()
        self._responded = False
        self.status = None

    # BaseHTTPRequestHandler 的底层输出全部落到内存
    def send_response(self, code, message=None):  # noqa: D102
        self.status = int(code)

    def send_header(self, key, value):  # noqa: D102
        pass

    def end_headers(self):  # noqa: D102
        pass

    def log_message(self, fmt, *args):  # noqa: D102
        pass

    @property
    def response(self) -> dict:
        return json.loads(self.wfile.getvalue().decode("utf-8"))


class ProactiveTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.cfg_path = os.path.join(self.tmp.name, "proactive.json")
        self.tools_path = os.path.join(self.tmp.name, "bili_tools.json")

        self._orig_proactive = server.Handler.PROACTIVE_FILE
        self._orig_tools = server.Handler.BILI_TOOLS_FILE
        self.addCleanup(self._restore_server_paths)
        server.Handler.PROACTIVE_FILE = self.cfg_path
        server.Handler.BILI_TOOLS_FILE = self.tools_path

        # 调度器优先读 CMEM_DATA_DIR，测试期间必须让位给临时目录
        self._env_cmem = os.environ.pop("CMEM_DATA_DIR", None)
        self.addCleanup(self._restore_env)

        self._orig_now = scheduler._now
        self.addCleanup(self._restore_now)

    def _restore_server_paths(self):
        server.Handler.PROACTIVE_FILE = self._orig_proactive
        server.Handler.BILI_TOOLS_FILE = self._orig_tools

    def _restore_env(self):
        if self._env_cmem is not None:
            os.environ["CMEM_DATA_DIR"] = self._env_cmem

    def _restore_now(self):
        scheduler._now = self._orig_now

    # -- 文件助手 ---------------------------------------------------------
    def _write_cfg(self, data: dict):
        with open(self.cfg_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    def _read_cfg(self) -> dict:
        with open(self.cfg_path, encoding="utf-8") as f:
            return json.load(f)

    def _raw(self) -> bytes:
        with open(self.cfg_path, "rb") as f:
            return f.read()

    # -- API 助手 ---------------------------------------------------------
    def _get(self) -> dict:
        h = _Harness("/proactive")
        h._do_GET()
        self.assertEqual(h.status, 200, h.response)
        return h.response

    def _post(self, payload: dict) -> tuple[dict, int]:
        h = _Harness("/proactive", payload)
        h._do_POST()
        return h.response, h.status

    # -- 调度器助手 -------------------------------------------------------
    def _sched(self, cfg: dict):
        self._write_cfg(cfg)
        s = scheduler.Scheduler(store=None, data_dir=self.tmp.name)
        self.wakes: list[dict] = []

        def _fake_inject(msg, silent=False, source_url=""):
            self.wakes.append({"msg": msg, "silent": silent, "source_url": source_url})

        s._inject = _fake_inject
        return s

    @staticmethod
    def _at(hour: int) -> None:
        scheduler._now = lambda: datetime.datetime(2026, 10, 29, hour, 0, 0)


class GetProactiveTest(ProactiveTestBase):
    def test_get_returns_config_object_with_defaults_and_unknown_keys(self):
        self._write_cfg({
            "startHour": 9,
            "endHour": 22,
            "biliBrowsePerDay": 5,
            "x-custom": {"keep": True},
        })
        resp = self._get()
        # 原配置对象：不是 {"ok": true, "config": {...}} 这种包装
        self.assertNotIn("ok", resp)
        self.assertEqual(resp["startHour"], 9)
        self.assertEqual(resp["endHour"], 22)
        self.assertEqual(resp["biliBrowsePerDay"], 5)
        self.assertEqual(resp["x-custom"], {"keep": True}, "未知键不能在读的时候被吃掉")
        # 旧配置里没有的字段回落到默认值
        self.assertTrue(resp["enabled"])
        self.assertFalse(resp["biliBrowseEnabled"], "平台浏览未启用时保持关闭")
        self.assertEqual(resp["minChatBeforeWake"], 0)
        self.assertEqual(resp["biliBrowseLimit"], 3)
        self.assertEqual(resp["biliMinGapMinutes"], 60)


class PostProactiveTest(ProactiveTestBase):
    def test_platform_switch_updates_the_scheduler_setting_without_enabling_writes(self):
        self._write_cfg({"biliBrowseEnabled": False, "biliComment": False})
        h = _Harness("/bili/toggle", {"enabled": True})
        h._bili_status = lambda: {"ok": True, "enabled": self._read_cfg()["biliBrowseEnabled"]}
        h._do_POST()
        self.assertEqual(h.status, 200)
        self.assertTrue(self._read_cfg()["biliBrowseEnabled"])
        self.assertFalse(self._read_cfg()["biliComment"])

    def test_post_android_subset_is_saved_as_one_package(self):
        self._write_cfg({
            "wakePreset": "warm",
            "silentActions": [{"text": "发呆", "enabled": True}],
            "biliComment": False,
            "x-custom": "keep",
        })
        payload = {
            "enabled": True,
            "talkEnabled": False,
            "startHour": 10,
            "endHour": 23,
            "minGapMinutes": 45,
            "maxGapMinutes": 120,
            "biliBrowseEnabled": False,
            "biliBrowsePerDay": 1,
        }
        resp, status = self._post(payload)
        self.assertEqual(status, 200, resp)
        self.assertTrue(resp["ok"], "成功响应必须带 ok:true")
        for key, value in payload.items():
            self.assertEqual(resp[key], value, f"响应里应回显 {key}")

        saved = self._read_cfg()
        for key, value in payload.items():
            self.assertEqual(saved[key], value, f"{key} 应该落盘")
        # 没提交的字段保持原值，不能因为整包保存被重置
        self.assertEqual(saved["wakePreset"], "warm")
        self.assertEqual(saved["silentActions"], [{"text": "发呆", "enabled": True}])
        self.assertEqual(saved["x-custom"], "keep")
        # 打开浏览 ≠ 授权写操作：评论开关和白名单都不能被顺手打开
        self.assertFalse(saved["biliComment"])
        self.assertFalse(os.path.exists(self.tools_path), "POST /proactive 不能碰 BILI 工具授权文件")

    def test_post_rejects_bad_values_atomically(self):
        self._write_cfg({
            "startHour": 9,
            "endHour": 22,
            "minGapMinutes": 30,
            "maxGapMinutes": 180,
            "biliBrowsePerDay": 2,
        })
        before = self._raw()
        cases = [
            ({"startHour": 25}, "startHour"),
            ({"endHour": -1}, "endHour"),
            ({"minGapMinutes": "abc"}, "minGapMinutes"),
            ({"maxGapMinutes": 3}, "maxGapMinutes"),
            ({"biliBrowsePerDay": 99}, "biliBrowsePerDay"),
            ({"biliBrowseLimit": 0}, "biliBrowseLimit"),
            ({"biliMinGapMinutes": 0}, "biliMinGapMinutes"),
            ({"minChatBeforeWake": -5}, "minChatBeforeWake"),
            ({"enabled": "yes"}, "enabled"),
            # 和现有 maxGapMinutes=180 冲突：整包不合法
            ({"minGapMinutes": 600}, "minGapMinutes"),
            # 一半合法一半不合法：合法的那个也不能先写进去
            ({"startHour": 10, "biliBrowsePerDay": 99}, "biliBrowsePerDay"),
        ]
        for payload, field in cases:
            with self.subTest(payload=payload):
                resp, status = self._post(payload)
                self.assertEqual(status, 400, f"{payload} 应该被拒绝，实际 {status} {resp}")
                self.assertFalse(resp["ok"])
                self.assertIn(field, resp["error"], "400 的文案要点名是哪个字段")
                self.assertEqual(self._raw(), before, "校验失败时文件必须原封不动")


class SchedulerWindowTest(ProactiveTestBase):
    def test_master_off_or_quiet_window_blocks_all_background_nudges(self):
        for cfg in (
            {"enabled": False, "startHour": 0, "endHour": 0},
            {"enabled": True, "startHour": 8, "endHour": 18},
        ):
            sched = self._sched(cfg)
            self._at(23)
            sched._load_state = lambda: {}
            sched._save_state = lambda state: None
            for name in ("_tick_wake", "_tick_bili", "_tick_diary", "_tick_evolve"):
                setattr(sched, name, lambda *args: self.wakes.append("unexpected"))
            sched._tick()
            self.assertEqual(self.wakes, [])

    def test_window_cross_midnight_all_day_and_quiet_hours(self):
        cfg = {
            "enabled": True,
            "talkEnabled": True,
            "startHour": 22,
            "endHour": 6,           # 跨午夜：22:00 - 次日 06:00
            "minGapMinutes": 30,
            "maxGapMinutes": 60,
            "minChatBeforeWake": 0,
            "biliBrowseEnabled": True,
            "biliBrowsePerDay": 3,
            "biliMinGapMinutes": 60,
            "biliMaxGapMinutes": 120,
        }
        sched = self._sched(cfg)

        for hour, active in ((23, True), (3, True), (12, False), (6, False)):
            with self.subTest(hour=hour):
                self._at(hour)
                self.wakes.clear()
                st = {"last_wake": 0, "wake_interval": 60,
                      "bili_last": 0, "bili_interval": 60, "bili_count": 0}
                woke = sched._tick_wake(dict(st), time.time())
                browsed = sched._tick_bili(dict(st), time.time())
                self.assertEqual(woke, active, f"{hour} 点唤醒判断错了")
                self.assertEqual(browsed, active, f"{hour} 点探索判断错了（要和窗口一致）")
                self.assertEqual(len(self.wakes), 2 if active else 0,
                                 "安静窗口里不该叫醒，也不该探索")

        # start == end → 全天
        self._write_cfg(dict(cfg, startHour=0, endHour=0))
        for hour in (0, 4, 13, 23):
            with self.subTest(all_day_hour=hour):
                self._at(hour)
                st = {"last_wake": 0, "wake_interval": 60}
                self.assertTrue(sched._tick_wake(dict(st), time.time()),
                                "start==end 表示全天，任何整点都该在窗口内")

    def test_off_switches_are_never_ignored(self):
        base = {
            "startHour": 8,
            "endHour": 23,
            "minGapMinutes": 30,
            "maxGapMinutes": 60,
            "minChatBeforeWake": 0,
            "biliShareToOwner": False,
            "biliComment": False,
        }
        self._at(10)

        # 总开关关掉（布尔/字符串两种写法都不能被当成"开着"）
        for enabled in (False, "false", 0):
            with self.subTest(enabled=enabled):
                sched = self._sched(dict(base, enabled=enabled, biliBrowseEnabled=True))
                self.wakes.clear()
                st = {"last_wake": 0, "wake_interval": 60,
                      "bili_last": 0, "bili_interval": 60, "bili_count": 0}
                self.assertFalse(sched._tick_wake(dict(st), time.time()))
                self.assertFalse(sched._tick_bili(dict(st), time.time()))
                self.assertEqual(self.wakes, [])

        # 只关 B 站浏览：还会醒，但不探索
        sched = self._sched(dict(base, enabled=True, biliBrowseEnabled=False))
        self.wakes.clear()
        st = {"last_wake": 0, "wake_interval": 60,
              "bili_last": 0, "bili_interval": 60, "bili_count": 0}
        self.assertTrue(sched._tick_wake(dict(st), time.time()))
        self.assertFalse(sched._tick_bili(dict(st), time.time()))
        self.assertEqual(len(self.wakes), 1)

        # 每天次数为 0：不探索
        sched = self._sched(dict(base, enabled=True, biliBrowseEnabled=True, biliBrowsePerDay=0))
        self.wakes.clear()
        self.assertFalse(sched._tick_bili({"bili_last": 0, "bili_interval": 60, "bili_count": 0},
                                          time.time()))
        self.assertEqual(self.wakes, [])

        # talkEnabled 关掉：仍会醒，但只能是静默提示（不能发消息）
        sched = self._sched(dict(base, enabled=True, talkEnabled=False, biliBrowseEnabled=False))
        self.wakes.clear()
        self.assertTrue(sched._tick_wake({"last_wake": 0, "wake_interval": 60}, time.time()))
        self.assertEqual(len(self.wakes), 1)
        self.assertTrue(self.wakes[0]["silent"], "talkEnabled=false 时唤醒必须是静默的")


if __name__ == "__main__":
    unittest.main()
