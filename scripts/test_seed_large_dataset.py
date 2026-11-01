import hashlib
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
GENERATOR = ROOT / "scripts/seed-large-dataset"


class LargeDatasetTest(unittest.TestCase):
    def test_scale_relations_and_repeatable_bytes(self):
        self.assertTrue(GENERATOR.is_file(), "T0.3 dataset generator is missing")
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            command = [sys.executable, str(GENERATOR), "--output-dir", str(output)]
            result = subprocess.run(command, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", errors="replace"))
            names = ("v12-sample.db", "workouts-sample.db", "dataset-manifest.json")
            first = {name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in names}
            with closing(sqlite3.connect(output / "v12-sample.db")) as db:
                self.assertEqual(12, db.execute("PRAGMA user_version").fetchone()[0])
                self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
                self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
                for table, count in (
                    ("time_records", 10000), ("schedule_blocks", 2000),
                    ("tasks", 500), ("ledger_transactions", 2000),
                ):
                    self.assertEqual(count, db.execute(f"SELECT count(*) FROM {table}").fetchone()[0])
                self.assertGreater(db.execute("SELECT count(*) FROM recurring_rules").fetchone()[0], 0)
                self.assertGreater(
                    db.execute("SELECT count(*) FROM recurring_instances WHERE manualOverride=1").fetchone()[0], 0
                )
                self.assertEqual(0, db.execute("SELECT count(*) FROM schedule_blocks WHERE taskId IS NULL").fetchone()[0])
            with closing(sqlite3.connect(output / "workouts-sample.db")) as db:
                self.assertEqual(1, db.execute("PRAGMA user_version").fetchone()[0])
                rows = db.execute("SELECT id, payload FROM workout_sessions").fetchall()
                self.assertEqual(200, len(rows))
                metrics = set()
                for ident, payload in rows:
                    session = json.loads(payload)
                    self.assertEqual(ident, session["id"])
                    self.assertEqual("workout:" + ident, session["timelineRecordId"])
                    metrics.update(m["exercise"]["metric"] for m in session["movements"])
                self.assertEqual(8, len(metrics))
            manifest = json.loads((output / "dataset-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("synthetic", manifest["provenance"])
            self.assertFalse(manifest["derivedFromRealExport"])
            result = subprocess.run(command, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", errors="replace"))
            second = {name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in names}
            self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
