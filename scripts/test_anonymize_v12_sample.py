"""Offline checks using synthetic SQLite sources only."""

from contextlib import closing
import calendar
from datetime import date
import hashlib
import json
from pathlib import Path
import runpy
import sqlite3
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/anonymize-v12-sample.py"
ASSETS = ROOT / "app/src/androidTest/assets/db"
MARKER = "PRIVATE_SENTINEL"
WEEK = 7 * 86_400_000


def insert(db, table, **values):
    for column in db.execute(f'PRAGMA table_info("{table}")'):
        name, affinity, required, default = column[1:5]
        if name not in values:
            values[name] = None if not required else 0 if affinity == "INTEGER" else ""
    columns = ",".join(f'"{name}"' for name in values)
    db.execute(
        f'INSERT INTO "{table}" ({columns}) VALUES ({",".join("?" for _ in values)})',
        tuple(values.values()),
    )


def fixture(directory):
    main = directory / "source.db"
    workouts = directory / "workouts.db"
    for source, target in ((ASSETS / "v12-large.db", main),
                           (ASSETS / "workouts-large.db", workouts)):
        with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as original:
            with closing(sqlite3.connect(target)) as clean:
                for sql, in original.execute(
                    "SELECT sql FROM sqlite_master WHERE type='table' "
                    "AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL ORDER BY rowid"
                ):
                    clean.execute(sql)
                for sql, in original.execute(
                    "SELECT sql FROM sqlite_master WHERE type='index' AND sql IS NOT NULL"
                ):
                    clean.execute(sql)
                identity = original.execute(
                    "SELECT name FROM sqlite_master WHERE name='room_master_table'"
                ).fetchone()
                if identity:
                    clean.executemany("INSERT INTO room_master_table VALUES (?,?)",
                                      original.execute("SELECT * FROM room_master_table"))
                clean.execute(f"PRAGMA user_version={original.execute('PRAGMA user_version').fetchone()[0]}")
                clean.commit()
    seed = runpy.run_path(str(ROOT / "scripts/seed-large-dataset"))
    session = seed["build_workout_session"](4, seed["BASE"])
    session["title"] = MARKER
    session["templateId"] = MARKER + "-template"
    template = {key: session[key] for key in ("title", "movements", "notes")}
    template.update(id=session["templateId"], createdAt=session["startedAt"],
                    updatedAt=session["updatedAt"])
    with closing(sqlite3.connect(workouts)) as db, db:
        insert(db, "workout_sessions", id=session["id"], startedAt=session["startedAt"],
               activeSlot=None, payload=json.dumps(session))
        insert(db, "workout_templates", id=template["id"], updatedAt=template["updatedAt"],
               payload=json.dumps(template))
    with closing(sqlite3.connect(main)) as db, db:
        insert(db, "categories", id=MARKER + "-category", name=MARKER)
        insert(db, "tags", id=MARKER + "-tag", name=MARKER)
        pattern = dict(id=MARKER + "-rule", frequency="MONTHLY", startDate="2024-01-31",
                       weekdays=[], weeklyCount=1, minuteOfDay=480, durationMinutes=30,
                       zoneId="Asia/Shanghai", untilDate="2024-03-31", paused=False,
                       effectiveFrom="2024-01-31", leadMinutes=15)
        rule = dict(id=pattern["id"], title=MARKER, patternJson=json.dumps(pattern),
                    domain="DEADLINE", category="", reference=MARKER, projectId=None,
                    createdAt=1706659200000, updatedAt=1706659200000, version=2, deletedAt=None)
        insert(db, "recurring_rules", **rule)
        instance_id = pattern["id"] + ":d:2024-02-29"
        task_id = "recurring-task:" + instance_id
        task = dict(id=task_id, title=MARKER, description=MARKER, estimatedMinutes=30,
                    dueAt=1709164800000, priority=1, status="TODO", categoryId=MARKER + "-category",
                    createdAt=1706659200000, updatedAt=1709164800000, completedAt=None,
                    source="RECURRING", deletedAt=None, version=3, activityId=None)
        insert(db, "tasks", **task)
        instance = dict(id=instance_id, ruleId=rule["id"], taskId=task_id,
                        originalDate="2024-02-29", ordinal=1, scheduledAt=1709164800000,
                        status="PENDING", manualOverride=1,
                        ruleSnapshot=json.dumps(dict(rule, version=1)), ruleVersion=1,
                        taskVersion=3, planVersion=4, placementVersion=None,
                        scheduleNote=MARKER, version=2)
        insert(db, "recurring_instances", **instance)
        insert(db, "schedule_blocks", id="recurring-plan:" + instance_id, taskId=task_id,
               startAt=1709164800000, endAt=1709166600000, status="SCHEDULED", version=4)
        insert(db, "time_records", id=session["timelineRecordId"], taskId=None,
               startAt=session["startedAt"], endAt=session["endedAt"], source="WORKOUT",
               title=MARKER)
        uid = MARKER + "-uid"
        record_id = "health:sleep:" + uid
        insert(db, "time_records", id=record_id, taskId=None, startAt=1709164800000,
               endAt=1709166600000, source="HEALTH_SYNC", isManuallyEdited=1, title=MARKER)
        insert(db, "health_sync_items", id=uid, kind="SLEEP", title=MARKER,
               startAt=1709164800000, endAt=1709166600000, originPackage=MARKER,
               recordId=record_id, userDeleted=0)
        insert(db, "attachments", id=MARKER + "-attachment", storageKey=MARKER + ".bin",
               originalName=MARKER + ".pdf", mimeType="application/pdf", sizeBytes=100,
               sha256="ab" * 32, ocrText=MARKER, taskId=task_id)
        insert(db, "reminders", id=MARKER + "-reminder", title=MARKER,
               remindAt=1709164800000, taskId=task_id, enabled=1, deliveredAt=1709164800100)
        insert(db, "entity_revisions", key="TASK:" + task_id, revision=3)
        insert(db, "entity_revisions",
               key="REMINDER_DELIVERY:" + MARKER + "-reminder:1709164800000",
               revision=1709164800100)
        insert(db, "entity_revisions", key="REMINDER_MANUAL_OFF:" + MARKER + "-reminder", revision=0)
        insert(db, "task_tags", taskId=task_id, tagId=MARKER + "-tag")
        insert(db, "notes", id=MARKER + "-note", targetType="TASK", targetId=task_id,
               title=MARKER, body=MARKER)
        operations = [
            dict(id=0, actionType="\u4fdd\u5b58\u4efb\u52a1", entityType="TASK", entityId=task_id,
                 beforeSnapshot=json.dumps(dict(task, title=MARKER + "-before")),
                 afterSnapshot=json.dumps(task), createdAt=1709164800000, undone=False, redoable=False),
            dict(id=0, actionType=MARKER, entityType="RECURRING_INSTANCE", entityId=instance_id,
                 beforeSnapshot=None, afterSnapshot=json.dumps(instance),
                 createdAt=1709164800000, undone=False, redoable=False),
            dict(id=0, actionType=MARKER, entityType="ORG_TASK_TAGS", entityId=task_id,
                 beforeSnapshot=json.dumps({"ids": []}),
                 afterSnapshot=json.dumps({"ids": [MARKER + "-tag"]}),
                 createdAt=1709164800000, undone=False, redoable=False),
        ]
        insert(db, "operation_history", id=7, actionType="\u52a9\u624b \u00b7 \u4fdd\u5b58\u4efb\u52a1", entityType="ASSISTANT_BATCH",
               entityId=MARKER + "-request", beforeSnapshot=None,
               afterSnapshot=json.dumps({"entries": operations}), createdAt=1709164800000,
               undone=0, redoable=1)
        insert(db, "ideas", id=MARKER + "-idea", title=MARKER, status="SCHEDULED")
        insert(db, "operation_history", id=8, actionType="\u5b89\u6392\u60f3\u6cd5\u4e3a\u63d0\u9192", entityType="IDEA_REMINDER",
               entityId=MARKER + "-idea", beforeSnapshot=None,
               afterSnapshot=json.dumps({"entries": operations}), createdAt=1709164800000,
               undone=0, redoable=0)
    return main, workouts


class AnonymizeV12Test(unittest.TestCase):
    def test_public_exercise_catalog_keeps_media_and_muscle_references(self):
        module = runpy.run_path(str(SCRIPT))
        schema = json.loads(module["SCHEMA"].read_text(encoding="utf-8"))["database"]
        anonymizer = module["Anonymizer"](schema)
        catalog = json.loads((ROOT / "app/src/main/assets/workout/catalog.json").read_text("utf-8"))
        exercise = {key: value for key, value in catalog[0].items()
                    if key in module["WORKOUT_FIELDS"]["exercise"]}
        result = anonymizer.workout("exercise", exercise)
        for key in ("id", "name", "primaryMuscles", "secondaryMuscles", "equipment", "instructions", "animationAsset"):
            self.assertEqual(exercise.get(key), result.get(key))
        custom = dict(exercise, id="custom:" + MARKER, name=MARKER)
        transformed = anonymizer.workout("exercise", custom)
        self.assertTrue(transformed["id"].startswith("custom:"))
        self.assertNotIn(MARKER, json.dumps(transformed))

    def test_legacy_suggestion_keys_keep_links_without_private_identifiers(self):
        module = runpy.run_path(str(SCRIPT))
        schema = json.loads(module["SCHEMA"].read_text(encoding="utf-8"))["database"]
        anonymizer = module["Anonymizer"](schema)
        anonymizer.days, anonymizer.millis = -7, -WEEK
        task = anonymizer.ident("tasks", MARKER)
        self.assertEqual(f"overdue:{task}:{1793419200000 - WEEK}",
                         anonymizer.dedup(f"overdue:{MARKER}:1793419200000"))
        self.assertEqual(f"free_slot:2026-10-24:{task}",
                         anonymizer.dedup(f"free_slot:2026-10-31:{MARKER}"))

    def run_script(self, main, workouts, output):
        self.assertTrue(SCRIPT.is_file(), "offline anonymizer is missing")
        return subprocess.run(
            [sys.executable, "-B", str(SCRIPT), "--main-source", str(main),
             "--workout-source", str(workouts), "--output-dir", str(output)],
            capture_output=True, text=True,
        )

    def test_nested_history_calendar_ids_and_clean_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main, workouts = fixture(root)
            originals = [hashlib.sha256(path.read_bytes()).digest() for path in (main, workouts)]
            output = root / "out"
            result = self.run_script(main, workouts, output)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual({"v12-sample.db", "workouts-sample.db", "anonymized-manifest.json"},
                             {path.name for path in output.iterdir()})
            for path in output.iterdir():
                self.assertNotIn(MARKER.encode(), path.read_bytes())
                self.assertNotIn(("ab" * 32).encode(), path.read_bytes())
            with closing(sqlite3.connect(main)) as source, \
                    closing(sqlite3.connect(output / "v12-sample.db")) as db:
                db.row_factory = sqlite3.Row
                self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
                self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
                self.assertEqual(12, db.execute("PRAGMA user_version").fetchone()[0])
                self.assertEqual(source.execute("SELECT * FROM room_master_table").fetchall(),
                                 [tuple(row) for row in db.execute("SELECT * FROM room_master_table")])
                for table, in source.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"):
                    self.assertEqual(source.execute(f'SELECT count(*) FROM "{table}"').fetchone()[0],
                                     db.execute(f'SELECT count(*) FROM "{table}"').fetchone()[0])
                task = dict(db.execute("SELECT * FROM tasks").fetchone())
                instance = dict(db.execute("SELECT * FROM recurring_instances").fetchone())
                self.assertEqual("recurring-task:" + instance["id"], task["id"])
                self.assertTrue(instance["id"].endswith(":d:" + instance["originalDate"]))
                shifted_date = date.fromisoformat(instance["originalDate"])
                self.assertTrue(date(2000, 1, 1) <= shifted_date <= date(2100, 12, 31))
                self.assertEqual(0, (shifted_date - date(2024, 2, 29)).days % 7)
                self.assertNotEqual(date(2024, 2, 29), shifted_date)
                self.assertEqual(1, instance["manualOverride"])
                rule = json.loads(instance["ruleSnapshot"])
                self.assertEqual(1, rule["version"])
                self.assertEqual(instance["ruleId"], rule["id"])
                pattern = json.loads(rule["patternJson"])
                self.assertEqual(rule["id"], pattern["id"])
                anchor = date.fromisoformat(pattern["startDate"])
                self.assertEqual(min(anchor.day, calendar.monthrange(shifted_date.year, shifted_date.month)[1]),
                                 shifted_date.day)
                batch = json.loads(db.execute("SELECT afterSnapshot FROM operation_history").fetchone()[0])
                self.assertEqual("\u52a9\u624b \u00b7 \u4fdd\u5b58\u4efb\u52a1",
                                 db.execute("SELECT actionType FROM operation_history WHERE id=7").fetchone()[0])
                self.assertEqual("\u4fdd\u5b58\u4efb\u52a1", batch["entries"][0]["actionType"])
                self.assertEqual(db.execute("SELECT id FROM ideas").fetchone()[0],
                                 db.execute("SELECT entityId FROM operation_history WHERE entityType='IDEA_REMINDER'").fetchone()[0])
                self.assertEqual(task, json.loads(batch["entries"][0]["afterSnapshot"]))
                self.assertEqual(instance, json.loads(batch["entries"][1]["afterSnapshot"]))
                self.assertEqual(db.execute("SELECT tagId FROM task_tags").fetchone()[0],
                                 json.loads(batch["entries"][2]["afterSnapshot"])["ids"][0])
                health = db.execute("SELECT * FROM health_sync_items").fetchone()
                self.assertEqual("health:sleep:" + health["id"], health["recordId"])
                attachment = db.execute("SELECT storageKey FROM attachments").fetchone()[0]
                self.assertRegex(attachment, r"^[0-9a-f-]{36}\.bin$")
                reminder = db.execute("SELECT * FROM reminders").fetchone()
                delivery = db.execute("SELECT key,revision FROM entity_revisions WHERE key LIKE 'REMINDER_DELIVERY:%'").fetchone()
                self.assertEqual(f'REMINDER_DELIVERY:{reminder["id"]}:{reminder["remindAt"]}', delivery["key"])
                self.assertEqual(reminder["deliveredAt"], delivery["revision"])
                with closing(sqlite3.connect(output / "workouts-sample.db")) as training:
                    sid, start, payload = training.execute("SELECT id,startedAt,payload FROM workout_sessions").fetchone()
                    session = json.loads(payload)
                    self.assertEqual(sid, session["id"])
                    self.assertEqual(start, session["startedAt"])
                    self.assertTrue(946684800000 <= start < 4133980800000)
                    self.assertEqual("workout:" + sid, session["timelineRecordId"])
                    self.assertIsNotNone(db.execute("SELECT id FROM time_records WHERE id=?", (session["timelineRecordId"],)).fetchone())
                    self.assertEqual(0, (start - 1793419200000) % WEEK)
                    self.assertEqual(20.0, session["movements"][0]["sets"][0]["weightKg"])
            self.assertEqual(originals, [hashlib.sha256(path.read_bytes()).digest() for path in (main, workouts)])
            manifest = json.loads((output / "anonymized-manifest.json").read_text())
            self.assertEqual("anonymized-v12", manifest["provenance"])
            for forbidden in ("source", "offset", "mapping", "sha256", "referenceTime"):
                self.assertNotIn(forbidden, json.dumps(manifest))

    def test_current_synthetic_assets_and_rerun(self):
        with tempfile.TemporaryDirectory() as directory:
            for _ in range(2):
                result = self.run_script(ASSETS / "v12-large.db", ASSETS / "workouts-large.db",
                                         Path(directory) / "out")
                self.assertEqual(0, result.returncode, result.stderr)
            with closing(sqlite3.connect(Path(directory) / "out/v12-sample.db")) as db:
                self.assertEqual(10000, db.execute("SELECT count(*) FROM time_records").fetchone()[0])
                self.assertEqual(500, db.execute("SELECT count(*) FROM tasks").fetchone()[0])
                self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())

    def test_public_task_sources_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main, workouts = fixture(root)
            for source in ("REPLY_COPILOT", "CHAT_EXTRACT"):
                with closing(sqlite3.connect(main)) as db, db:
                    db.execute("UPDATE tasks SET source=?", (source,))
                result = self.run_script(main, workouts, root / "out")
                self.assertEqual(0, result.returncode, result.stderr)
                with closing(sqlite3.connect(root / "out/v12-sample.db")) as db:
                    self.assertEqual(source, db.execute("SELECT source FROM tasks").fetchone()[0])
            for source in ("help", "plus_weight", "record", "times"):
                with closing(sqlite3.connect(workouts)) as db, db:
                    payload = json.loads(db.execute("SELECT payload FROM workout_sessions").fetchone()[0])
                    for movement in payload["movements"]:
                        movement["exercise"]["sourceType"] = source
                    db.execute("UPDATE workout_sessions SET payload=?", (json.dumps(payload),))
                result = self.run_script(main, workouts, root / "out")
                self.assertEqual(0, result.returncode, result.stderr)
                with closing(sqlite3.connect(root / "out/workouts-sample.db")) as db:
                    payload = json.loads(db.execute("SELECT payload FROM workout_sessions").fetchone()[0])
                    self.assertEqual(source, payload["movements"][0]["exercise"]["sourceType"])

    def test_no_compatible_date_shift_fails_without_publishing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main, workouts = fixture(root)
            with closing(sqlite3.connect(main)) as db, db:
                db.execute("UPDATE tasks SET createdAt=946684800000, dueAt=4133980800000")
            result = self.run_script(main, workouts, root / "out")
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(MARKER, result.stdout + result.stderr)
            self.assertFalse((root / "out/v12-sample.db").exists())

    def test_unknown_json_field_fails_without_private_error_or_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main, workouts = fixture(root)
            with closing(sqlite3.connect(workouts)) as db, db:
                payload = json.loads(db.execute("SELECT payload FROM workout_sessions").fetchone()[0])
                payload[MARKER + "-unknown"] = {"text": MARKER}
                db.execute("UPDATE workout_sessions SET payload=?", (json.dumps(payload),))
            result = self.run_script(main, workouts, root / "out")
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(MARKER, result.stdout + result.stderr)
            self.assertFalse((root / "out/v12-sample.db").exists())

    def test_unknown_column_and_source_output_alias_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main, workouts = fixture(root)
            with closing(sqlite3.connect(main)) as db, db:
                db.execute('ALTER TABLE tasks ADD COLUMN extra TEXT')
                db.execute("UPDATE tasks SET extra=?", (MARKER,))
            result = self.run_script(main, workouts, root / "out")
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn(MARKER, result.stdout + result.stderr)
            self.assertFalse((root / "out/v12-sample.db").exists())
            alias = root / "v12-sample.db"
            main.rename(alias)
            original = alias.read_bytes()
            result = self.run_script(alias, workouts, root)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(original, alias.read_bytes())


if __name__ == "__main__":
    unittest.main()
