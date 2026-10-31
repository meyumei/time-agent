import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'service'))
from bili_adapter import parse_feed

class BiliAdapterTest(unittest.TestCase):
    def test_retains_real_video_identity_source_and_metadata_boundary(self):
        lines = '1. BV1abc123def 120s 弹幕 13 播放 800 原始标题 @发布者\n看完跑：node bili.mjs debrief BV1abc123def\n'
        self.assertEqual(parse_feed(lines), [{'title':'原始标题','bvid':'BV1abc123def','url':'https://www.bilibili.com/video/BV1abc123def'}])
    def test_diagnostics_and_empty_output_are_not_discovered_content(self):
        self.assertEqual(parse_feed('B站错误 412\n'), [])
