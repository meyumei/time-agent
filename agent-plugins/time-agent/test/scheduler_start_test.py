"""The first proactive topic must wait for the configured interval."""
import datetime
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "dsh-remembers/service"))
from scheduler import Scheduler


class SchedulerStartTest(unittest.TestCase):
    def test_fresh_start_waits_and_disabled_or_quiet_window_does_not_wake(self):
        with tempfile.TemporaryDirectory(prefix="fish-scheduler-") as directory:
            scheduler = Scheduler(object(), directory)
            config = {"enabled": True, "startHour": 8, "endHour": 23,
                      "minGapMinutes": 30, "maxGapMinutes": 30}
            scheduler._proactive_cfg = lambda: config
            state = {}
            with patch("scheduler._now", return_value=datetime.datetime(2026, 10, 30, 12)):
                self.assertTrue(scheduler._tick_wake(state, 1800000000))
                self.assertEqual(state["last_wake"], 1800000000)
                self.assertFalse(scheduler._tick_wake(state, 1800000060))
                config["enabled"] = False
                self.assertFalse(scheduler._tick_wake(state, 1800003600))
            config["enabled"] = True
            with patch("scheduler._now", return_value=datetime.datetime(2026, 10, 30, 0)):
                self.assertFalse(scheduler._tick_wake(state, 1800003600))


if __name__ == "__main__":
    unittest.main()
