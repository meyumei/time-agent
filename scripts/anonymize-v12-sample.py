#!/usr/bin/env python3
"""Rebuild and anonymize offline, checkpointed v12/v1 SQLite exports."""

import argparse
import calendar
from contextlib import ExitStack, closing
from datetime import date, timedelta
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import tempfile
import uuid
from zoneinfo import available_timezones


ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "app/schemas/com.timeagent.data.TimeAgentDatabase/12.json"
DAY = 86_400_000
FREE_TEXT = set("""name title description notes teacher location locationOverride
teacherOverride content body detail text sourceText keyword reference
category scheduleNote originalName ocrText merchant note sourceLabel icon""".split())
TIMESTAMPS = set("""dueAt createdAt updatedAt completedAt deletedAt startAt endAt
startedAt archivedAt remindAt deliveredAt lastSyncedAt decidedAt proposedStartAt
proposedEndAt expiresAt snoozedUntil validFrom validUntil scheduledAt purgedAt
occurredAt confirmedAt endedAt pausedAt restEndsAt""".split())
DATES = set("firstWeekStart date forDate weekStart startDate untilDate effectiveFrom originalDate".split())
ACTIVITIES = {"sleep", "fitness", "fitness-chest", "fitness-back", "fitness-shoulders", "fitness-arms"}
ENTITY_TABLE = dict(
    TASK="tasks", PLAN="schedule_blocks", RECORD="time_records", ACTIVITY="activity_templates",
    REMINDER="reminders", IDEA="ideas", NOTE="notes", OCCURRENCE="course_occurrences",
    PROJECT="projects", PLACEMENT="task_placements", GOAL="weekly_goals",
    MEMORY="personal_memories", SUGGESTION="agent_suggestions", ATTACHMENT="attachments",
    RECURRING_RULE="recurring_rules", RECURRING_INSTANCE="recurring_instances",
    LEDGER_ACCOUNT="ledger_accounts", LEDGER_CATEGORY="ledger_categories",
    PAYMENT_CAPTURE="payment_captures", LEDGER_TRANSACTION="ledger_transactions",
    ORG_CATEGORY="categories", ORG_TAG="tags", ORG_TASK_TAGS="tasks",
    ORG_RECORD_TAGS="time_records", ORG_HEALTH_ITEM="health_sync_items",
)
ENUMS = {
    ("tasks", "status"): "TODO IN_PROGRESS COMPLETED CANCELLED",
    ("schedule_blocks", "status"): "SCHEDULED CANCELLED COMPLETED",
    ("course_occurrences", "status"): "SCHEDULED CANCELLED RESCHEDULED",
    ("tasks", "source"): "MANUAL TIMER ASSISTANT RECURRING IMPORT ACTIVITY CHAT_EXTRACT REPLY_COPILOT",
    ("time_records", "source"): "MANUAL TIMER ASSISTANT WORKOUT HEALTH_SYNC IMPORT ACTIVITY",
    ("import_batches", "sourceType"): "JSON PDF SCUT",
    ("import_batches", "status"): "COMPLETED FAILED PENDING",
    ("ideas", "status"): "UNSCHEDULED SCHEDULED ARCHIVED",
    ("health_sync_items", "kind"): "SLEEP EXERCISE",
    ("agent_suggestions", "type"): "OVERDUE_TASK FREE_SLOT SLEEP_SHORT",
    ("agent_suggestions", "status"): "PENDING SNOOZED APPLIED DISMISSED EXPIRED INVALIDATED",
    ("personal_memories", "kind"): "PREFERENCE FACT AGREEMENT",
    ("personal_memories", "sourceType"): "USER_INPUT EXPLICIT OBSERVATION",
    ("personal_memories", "ruleType"): "NONE AVOID_WEEKDAY TIME_WINDOW",
    ("projects", "status"): "ACTIVE PAUSED COMPLETED",
    ("recurring_rules", "domain"): "HABIT DEADLINE",
    ("recurring_instances", "status"): "PENDING COMPLETED SKIPPED SUSPENDED SUPERSEDED",
    ("ledger_accounts", "type"): "CASH BANK WECHAT ALIPAY OTHER",
    ("ledger_accounts", "currency"): "CNY USD EUR GBP JPY HKD TWD AUD CAD SGD KRW",
    ("ledger_categories", "direction"): "EXPENSE INCOME REFUND",
    ("payment_captures", "direction"): "EXPENSE INCOME REFUND",
    ("payment_captures", "status"): "PENDING CONFIRMED IGNORED",
    ("ledger_transactions", "direction"): "EXPENSE INCOME REFUND",
}
INTEGER_RECORDS = set("""count steps floors laps poolLength rounds workSeconds restSeconds
cadence strokeRate elevationGain""".split())
DECIMAL_RECORDS = {"distance", "kcal", "avgSpeed", "bpm"}
RECORD_KEYS = INTEGER_RECORDS | DECIMAL_RECORDS | {"pace"}
WORKOUT_FIELDS = {
    "session": set("""id title startedAt updatedAt status movements notes endedAt pausedAt
totalPausedMillis restEndsAt pausedRestRemainingMillis timelineRecordId templateId
bodyWeightKg intervalTimer setTimer""".split()),
    "template": set("id title movements notes createdAt updatedAt".split()),
    "movement": set("""id exercise sets restSeconds supersetId notes oneSide singleSide warmupRestSeconds""".split()),
    "exercise": set("""id name primaryMuscles secondaryMuscles equipment metric instructions
animationAsset sourceType recordFieldKeys""".split()),
    "set": set("""id kind weightKg reps durationSeconds completedAt leftWeightKg distanceMeters
caloriesKcal recordValues dropSegments tabata rpe comment""".split()),
    "drop": {"weightKg", "reps", "leftWeightKg"},
    "tabata": {"prepareSeconds", "workSeconds", "restSeconds", "rounds"},
    "interval": {"movementId", "setId", "startedAt", "pausedMillisAtStart", "config"},
    "timer": {"movementId", "setId", "startedAt", "pausedMillisAtStart"},
}
WORKOUT_ENUMS = {
    "status": "ACTIVE PAUSED COMPLETED DISCARDED",
    "metric": "WEIGHT_REPS REPS DURATION BODYWEIGHT_REPS ASSISTED_REPS CARDIO STRETCH TABATA",
    "kind": "NORMAL WARMUP DROP FAILURE",
    "sourceType": "weight generic cardio stretch tabata duration reps bodyweight assisted help plus_weight record times",
}


def require(condition):
    if not condition:
        raise ValueError("unsupported or invalid data contract")


def encode(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), allow_nan=False)


def decode(value):
    return json.loads(value, parse_constant=lambda _: require(False))


def quote(value):
    return '"' + value.replace('"', '""') + '"'


def public_actions():
    actions = set()
    owners = ("data/TimeRepository.kt", "ledger/LedgerRepository.kt",
              "organization/OrganizationRepository.kt", "recurring/RecurringService.kt")
    for owner in owners:
        for line in (ROOT / "app/src/main/java/com/timeagent" / owner).read_text(encoding="utf-8").splitlines():
            if re.search(r"journal\(|putAttachmentEdit\(|action\s*[:=]|actionType\s*=", line):
                for literal in re.findall(r'"((?:[^"\\]|\\.)*)"', line):
                    # Interpolated labels can contain user names and never enter the allowlist.
                    if "$" not in literal:
                        actions.add(decode('"' + literal + '"'))
    return actions


class Anonymizer:
    def __init__(self, schema):
        self.schema = {item["tableName"]: item for item in schema["entities"]}
        self.columns = {
            table: {field["columnName"]: field["affinity"] for field in item["fields"]}
            for table, item in self.schema.items()
        }
        self.salt = secrets.token_bytes(32)
        self.days = self.millis = 0
        self.collecting = False
        self.timestamps, self.calendar_days, self.instances = set(), set(), []
        self.actions = public_actions()
        self.zones = available_timezones() | {"UTC", "GMT", "Asia/Shanghai"}
        catalog = json.loads((ROOT / "app/src/main/assets/workout/catalog.json").read_text("utf-8"))
        self.exercise_ids = {item["id"] for item in catalog}
        self.public_exercise = {}
        for key in ("name", "equipment", "instructions", "animationAsset", "primaryMuscles", "secondaryMuscles"):
            self.public_exercise[key] = {
                value for item in catalog for value in
                (item.get(key, []) if key.endswith("Muscles") else [item.get(key)])
                if isinstance(value, str)
            }

    def digest(self, domain, value):
        require(isinstance(value, str))
        return hmac.new(self.salt, encode([domain, value]).encode(), hashlib.sha256).digest()

    def opaque(self, domain, value):
        if value == "":
            return ""
        return str(uuid.UUID(bytes=self.digest(domain, value)[:16], version=4))

    def text(self, value):
        require(isinstance(value, str))
        return "" if value == "" else "sample-" + self.digest("text", value).hex()[:24]

    def instant(self, value):
        require(type(value) is int)
        if self.collecting and value:
            self.timestamps.add(value)
        # Zero in old entities is an unset/default sentinel.
        return value + self.millis if value else 0

    def day(self, value):
        original = date.fromisoformat(value)
        if self.collecting:
            self.calendar_days.add(original)
        return (original + timedelta(days=self.days)).isoformat()

    def action(self, value):
        require(isinstance(value, str))
        if value in self.actions:
            return value
        prefix = "\u52a9\u624b \u00b7 "
        if value.startswith(prefix):
            return prefix + self.action(value[len(prefix):])
        return self.text(value)

    @staticmethod
    def instance_accepts(item, days):
        rule = decode(item["ruleSnapshot"])
        pattern = decode(rule["patternJson"])
        require(rule["id"] == item["ruleId"] == pattern["id"] and rule["version"] == item["ruleVersion"])
        match = re.fullmatch(r"(.*):(d|w):(\d{4}-\d{2}-\d{2})(?::(\d+))?", item["id"])
        require(match is not None and match[1] == rule["id"])
        delta = timedelta(days=days)
        current = date.fromisoformat(item["originalDate"]) + delta
        key_date = date.fromisoformat(match[3]) + delta
        anchor = date.fromisoformat(pattern["startDate"]) + delta
        effective = date.fromisoformat(pattern.get("effectiveFrom", pattern["startDate"])) + delta
        frequency = pattern["frequency"]
        end = current + timedelta(days=6 if frequency == "WEEKLY_COUNT" else 0)
        if pattern.get("untilDate") is not None:
            end = min(end, date.fromisoformat(pattern["untilDate"]) + delta)
        first = max(current, anchor, effective)
        if first > end:
            return False
        if frequency == "WEEKLY_COUNT":
            return (match[2] == "w" and key_date == first - timedelta(days=first.weekday())
                    and int(match[4] or 0) == item["ordinal"]
                    and 1 <= item["ordinal"] <= pattern.get("weeklyCount", 1))
        if match[2] != "d" or key_date != current or item["ordinal"] != 1:
            return False
        if frequency == "ONCE":
            return current == anchor
        if frequency == "DAILY":
            return True
        if frequency == "WEEKDAYS":
            return current.isoweekday() in pattern["weekdays"]
        day = min(anchor.day, calendar.monthrange(current.year, current.month)[1])
        if frequency == "MONTHLY":
            return current.day == day
        if frequency == "YEARLY":
            return current.month == anchor.month and current.day == day
        return False

    def select_time_shift(self, main, workouts):
        # Probe the same typed traversal, including nested historical JSON, without writing.
        self.collecting = True
        for table in self.columns:
            columns = [row[1] for row in main.execute(f"PRAGMA table_info({quote(table)})")]
            for raw in main.execute(f"SELECT * FROM {quote(table)}"):
                self.row(table, dict(zip(columns, raw)))
        for table, kind in (("workout_sessions", "session"), ("workout_templates", "template")):
            for payload, in workouts.execute(f"SELECT payload FROM {quote(table)}"):
                self.workout(kind, decode(payload))
        self.collecting = False
        require(all(self.instance_accepts(item, 0) for item in self.instances))
        lower, upper = -36525, 36525
        if self.timestamps:
            lower = max(lower, -((min(self.timestamps) - 946684800000) // DAY))
            upper = min(upper, (4133980800000 - max(self.timestamps)) // DAY)
        if self.calendar_days:
            lower = max(lower, (date(2000, 1, 1) - min(self.calendar_days)).days)
            upper = min(upper, (date(2100, 12, 31) - max(self.calendar_days)).days)
        candidates = [week * 7 for week in range(-(-lower // 7), upper // 7 + 1) if week]
        random = secrets.SystemRandom()
        near_past = [days for days in candidates if -364 <= days < 0]
        other_past = [days for days in candidates if days < -364]
        future = [days for days in candidates if days > 0]
        for group in (near_past, other_past, future):
            random.shuffle(group)
            for days in group:
                if all(self.instance_accepts(item, days) for item in self.instances):
                    self.days, self.millis = days, days * DAY
                    return
        raise ValueError("no nonzero whole-week shift preserves the supported date contract")

    def ident(self, table, value):
        require(isinstance(value, str))
        if table == "activity_templates" and value in ACTIVITIES:
            return value
        if table == "workout_exercises":
            if value in self.exercise_ids:
                return value
            if value.startswith("custom:"):
                return "custom:" + self.opaque(table, value)
        for owner, prefix in (("tasks", "recurring-task:"), ("schedule_blocks", "recurring-plan:"),
                              ("reminders", "recurring-reminder:")):
            if table == owner and value.startswith(prefix):
                return prefix + self.ident("recurring_instances", value[len(prefix):])
        if table == "recurring_instances":
            match = re.fullmatch(r"(.*):(d|w):(\d{4}-\d{2}-\d{2})(:\d+)?", value)
            require(match is not None)
            rule, mode, original, ordinal = match.groups()
            return f'{self.ident("recurring_rules", rule)}:{mode}:{self.day(original)}{ordinal or ""}'
        if table == "time_records":
            if value.startswith("workout:"):
                return "workout:" + self.ident("workout_sessions", value[8:])
            match = re.fullmatch(r"health:(sleep|exercise):(.*)", value)
            if match:
                return f'health:{match[1]}:{self.ident("health_sync_items", match[2])}'
        return self.opaque(table, value)

    def entity_table(self, kind):
        require(kind in ENTITY_TABLE)
        return ENTITY_TABLE[kind]

    def entity_id(self, kind, value):
        if kind == "IDEA_REMINDER":
            return self.ident("ideas", value)
        if kind in {"ASSISTANT_BATCH", "COURSE_BATCH"}:
            return self.ident("requests", value)
        return self.ident(self.entity_table(kind), value)

    def revision_key(self, value):
        kind, ident = value.split(":", 1)
        if kind == "REMINDER_DELIVERY":
            ident, stamp = ident.rsplit(":", 1)
            return f'{kind}:{self.ident("reminders", ident)}:{self.instant(int(stamp))}'
        if kind == "REMINDER_MANUAL_OFF":
            return kind + ":" + self.ident("reminders", ident)
        return kind + ":" + self.entity_id(kind, ident)

    def dedup(self, value):
        match = re.fullmatch(r"overdue:(.*):(-?\d+):v(\d+)", value)
        if match:
            return f'overdue:{self.ident("tasks", match[1])}:{self.instant(int(match[2]))}:v{match[3]}'
        match = re.fullmatch(r"free_slot:(\d{4}-\d{2}-\d{2}):(.*):v(\d+)", value)
        if match:
            return f'free_slot:{self.day(match[1])}:{self.ident("tasks", match[2])}:v{match[3]}'
        match = re.fullmatch(r"overdue:(.+):(-?\d+)", value)
        if match:
            return f'overdue:{self.ident("tasks", match[1])}:{self.instant(int(match[2]))}'
        match = re.fullmatch(r"free_slot:(\d{4}-\d{2}-\d{2}):(.+)", value)
        if match:
            return f'free_slot:{self.day(match[1])}:{self.ident("tasks", match[2])}'
        if value.startswith("sleep_short:"):
            return "sleep_short:" + ",".join(self.ident("time_records", item) for item in value[12:].split(","))
        raise ValueError("unsupported dedup contract")

    def pattern(self, value):
        require(isinstance(value, dict))
        allowed = set("""id frequency startDate weekdays weeklyCount minuteOfDay durationMinutes
zoneId untilDate paused effectiveFrom leadMinutes""".split())
        require(not value.keys() - allowed)
        result = {}
        for key, item in value.items():
            if item is None:
                result[key] = None
            elif key == "id":
                result[key] = self.ident("recurring_rules", item)
            elif key in DATES:
                result[key] = self.day(item)
            elif key == "frequency":
                require(item in "ONCE DAILY WEEKDAYS WEEKLY_COUNT MONTHLY YEARLY".split())
                result[key] = item
            elif key == "zoneId":
                require(item in self.zones)
                result[key] = item
            elif key == "weekdays":
                require(isinstance(item, list) and all(type(day) is int and 1 <= day <= 7 for day in item))
                result[key] = item
            else:
                require(type(item) in (int, bool))
                result[key] = item
        return result

    def snapshot(self, kind, raw):
        if raw is None:
            return None
        value = decode(raw)
        if value is None:
            return encode(None)
        if kind in {"ASSISTANT_BATCH", "IDEA_REMINDER"}:
            require(isinstance(value, dict) and value.keys() == {"entries"})
            require(isinstance(value["entries"], list))
            value = {"entries": [self.row("operation_history", item) for item in value["entries"]]}
        elif kind == "COURSE_BATCH":
            require(isinstance(value, dict) and value.keys() == {"occurrences"})
            value = {"occurrences": [self.row("course_occurrences", item) for item in value["occurrences"]]}
        elif kind in {"ORG_TASK_TAGS", "ORG_RECORD_TAGS"}:
            require(isinstance(value, dict) and value.keys() == {"ids"} and isinstance(value["ids"], list))
            value = {"ids": [self.ident("tags", item) for item in value["ids"]]}
        else:
            value = self.row(self.entity_table(kind), value)
        return encode(value)

    def parsed_payment(self, value):
        require(isinstance(value, dict))
        allowed = set("""sourcePackage sourceLabel amountMinor direction merchant referenceId
occurredAt parserVersion""".split())
        require(not value.keys() - allowed)
        result = {}
        for key, item in value.items():
            if item is None:
                result[key] = None
            elif key == "referenceId":
                result[key] = self.ident("external_transaction", item)
            else:
                result[key] = self.field("payment_captures", key, item, value)
        return result

    def field(self, table, key, value, row):
        if value is None:
            return None
        if table == "operation_history":
            kind = row["entityType"]
            if key == "actionType":
                return self.action(value)
            if key == "entityType":
                require(kind in ENTITY_TABLE or kind in {"ASSISTANT_BATCH", "IDEA_REMINDER", "COURSE_BATCH"})
                return kind
            if key == "entityId":
                return self.entity_id(kind, value)
            if key in {"beforeSnapshot", "afterSnapshot"}:
                return self.snapshot(kind, value)
            if key == "id":
                require(type(value) is int)
                return value
        if table == "entity_revisions":
            if key == "key":
                return self.revision_key(value)
            if row["key"].startswith("REMINDER_DELIVERY:"):
                return self.instant(value)
        if key == "patternJson":
            return encode(self.pattern(decode(value)))
        if key == "ruleSnapshot":
            return encode(self.row("recurring_rules", decode(value)))
        if key == "parsedJson":
            return "" if value == "" else encode(self.parsed_payment(decode(value)))
        if key == "dedupKey":
            return self.dedup(value)
        if key == "targetType":
            self.entity_table(value)
            return value
        if key == "targetId":
            return self.ident(self.entity_table(row["targetType"]), value)
        if key == "storageKey":
            return self.opaque("attachment_storage", value) + ".bin"
        if key in {"sha256", "rawTextHash"}:
            return "" if value == "" else self.digest("content_hash", value).hex()
        if key in {"sourcePackage", "originPackage"}:
            return "" if value == "" else "org.sample.p" + self.digest("package", value).hex()[:24]
        if key == "notificationKey":
            return self.ident("notification", value)
        if key == "externalTransactionId":
            return self.ident("external_transaction", value)
        if key == "sourceOccurrenceKey":
            return self.ident("course_occurrences", value)
        if key == "externalKey":
            return self.ident("courses", value)
        if key == "sourceId":
            domain = "time_records" if table == "personal_memories" and row.get("sourceType") == "OBSERVATION" else "import_sources"
            return self.ident(domain, value)
        if key in TIMESTAMPS:
            return self.instant(value)
        if key in DATES:
            return self.day(value)
        if key == "timezoneId":
            require(value in self.zones)
            return value
        if key == "mimeType":
            require(value in {"application/pdf", "application/octet-stream", "image/jpeg",
                              "image/png", "image/webp", "text/plain"})
            return value
        if (table, key) in ENUMS:
            require(value in ENUMS[table, key].split())
            return value
        if key in FREE_TEXT:
            return self.text(value)
        if key == "id":
            return self.ident(table, value)
        references = {"taskId": "tasks", "parentTaskId": "tasks", "recordId": "time_records",
                      "projectId": "projects", "activityId": "activity_templates", "tagId": "tags",
                      "termId": "academic_terms", "courseId": "courses", "ruleId": "recurring_rules",
                      "accountId": "ledger_accounts", "captureId": "payment_captures",
                      "transactionId": "ledger_transactions"}
        if key == "categoryId":
            return self.ident("ledger_categories" if table == "ledger_transactions" else "categories", value)
        if key == "parentId" and table == "activity_templates":
            return self.ident(table, value)
        if key in references:
            return self.ident(references[key], value)
        require(self.columns[table].get(key) in {"INTEGER", "REAL"} or key == "__manualOff")
        require(type(value) in (int, float, bool))
        return value

    def row(self, table, row):
        require(isinstance(row, dict))
        allowed = set(self.columns[table]) | ({"__manualOff"} if table == "reminders" else set())
        require(not row.keys() - allowed)
        if self.collecting and table == "recurring_instances":
            self.instances.append(row)
        return {key: self.field(table, key, value, row) for key, value in row.items()}

    def workout(self, kind, value):
        require(isinstance(value, dict) and not value.keys() - WORKOUT_FIELDS[kind])
        result = {}
        id_domain = {"session": "workout_sessions", "template": "workout_templates",
                     "movement": "workout_movements", "exercise": "workout_exercises", "set": "workout_sets"}
        children = {"exercise": "exercise", "tabata": "tabata", "config": "tabata",
                    "intervalTimer": "interval", "setTimer": "timer"}
        arrays = {"movements": "movement", "sets": "set", "dropSegments": "drop"}
        for key, item in value.items():
            if item is None:
                result[key] = None
            elif key in children:
                result[key] = self.workout(children[key], item)
            elif key in arrays:
                require(isinstance(item, list))
                result[key] = [self.workout(arrays[key], entry) for entry in item]
            elif key == "recordValues":
                require(isinstance(item, dict) and not item.keys() - RECORD_KEYS)
                for record_key, raw in item.items():
                    require(isinstance(raw, str))
                    expression = r"\d+:[0-5]\d" if record_key == "pace" else (
                        r"\d+" if record_key in INTEGER_RECORDS else r"(?:\d+(?:\.\d*)?|\.\d+)")
                    require(not raw.strip() or re.fullmatch(expression, raw.strip()) is not None)
                result[key] = item
            elif key == "recordFieldKeys":
                require(isinstance(item, list) and all(entry in RECORD_KEYS for entry in item))
                result[key] = item
            elif key == "id":
                result[key] = self.ident(id_domain[kind], item)
            elif key in {"movementId", "setId", "templateId", "timelineRecordId", "supersetId"}:
                domain = dict(movementId="workout_movements", setId="workout_sets",
                              templateId="workout_templates", timelineRecordId="time_records",
                              supersetId="workout_supersets")[key]
                result[key] = self.ident(domain, item)
            elif key in TIMESTAMPS:
                result[key] = self.instant(item)
            elif key in WORKOUT_ENUMS:
                require(item in WORKOUT_ENUMS[key].split())
                result[key] = item
            elif key in {"primaryMuscles", "secondaryMuscles"}:
                require(isinstance(item, list))
                result[key] = [entry if entry in self.public_exercise[key] else self.text(entry) for entry in item]
            elif key == "animationAsset":
                # Shipped media paths are public and must still resolve after migration.
                result[key] = item if item in self.public_exercise[key] else (
                    "" if item == "" else "workout/" + self.opaque("animation", item) + ".gif")
            elif key in {"title", "notes", "name", "equipment", "instructions", "comment"}:
                result[key] = item if kind == "exercise" and item in self.public_exercise.get(key, set()) else self.text(item)
            else:
                require(type(item) in (int, float, bool))
                result[key] = item
        return result


def source_connection(path):
    require(path.is_file())
    # Immutable reads avoid sidecar writes; reject uncheckpointed exports instead of losing WAL rows.
    require(not any(Path(str(path) + suffix).exists() and Path(str(path) + suffix).stat().st_size
                    for suffix in ("-wal", "-journal")))
    return sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True)


def rebuild(source, destination, anonymizer, workout=False):
    expected = {"workout_sessions": {"id", "startedAt", "activeSlot", "payload"},
                "workout_templates": {"id", "updatedAt", "payload"}} if workout else anonymizer.columns
    version = source.execute("PRAGMA user_version").fetchone()[0]
    require(version == (1 if workout else 12))
    definitions = source.execute(
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY rowid"
    ).fetchall()
    tables = [name for kind, name, _, _ in definitions if kind == "table"]
    require(set(tables) - {"room_master_table", "android_metadata"} == set(expected))
    require(all(kind in {"table", "index"} for kind, _, _, _ in definitions))
    for table, fields in expected.items():
        require({row[1] for row in source.execute(f"PRAGMA table_info({quote(table)})")} == set(fields))
    counts = {}
    with closing(sqlite3.connect(destination)) as target:
        target.execute(f"PRAGMA user_version={version}")
        for kind, name, _, sql in definitions:
            if kind == "table":
                target.execute(sql)
        for table in tables:
            columns = [row[1] for row in source.execute(f"PRAGMA table_info({quote(table)})")]
            slots = ",".join("?" for _ in columns)
            statement = f'INSERT INTO {quote(table)} ({",".join(map(quote, columns))}) VALUES ({slots})'
            count = 0
            for raw in source.execute(f"SELECT * FROM {quote(table)} ORDER BY rowid"):
                row = dict(zip(columns, raw))
                if table == "room_master_table":
                    require(set(columns) == {"id", "identity_hash"})
                    require(type(row["id"]) is int and re.fullmatch(r"[0-9a-f]{32}", row["identity_hash"]) is not None)
                    if not workout:
                        require(row["identity_hash"] == anonymizer.identity)
                    transformed = row
                elif table == "android_metadata":
                    require(columns == ["locale"])
                    transformed = {"locale": None if row["locale"] is None else "en_US"}
                elif workout:
                    payload = anonymizer.workout("session" if table == "workout_sessions" else "template",
                                                decode(row["payload"]))
                    transformed = dict(row, id=payload["id"], payload=encode(payload))
                    key = "startedAt" if table == "workout_sessions" else "updatedAt"
                    transformed[key] = anonymizer.instant(row[key])
                    require(transformed[key] == payload[key])
                    if table == "workout_sessions":
                        active = payload["status"] in {"ACTIVE", "PAUSED"}
                        require(row["activeSlot"] == 1 if active else row["activeSlot"] is None)
                else:
                    transformed = anonymizer.row(table, row)
                target.execute(statement, tuple(transformed[key] for key in columns))
                count += 1
            counts[table] = count
        for kind, _, _, sql in definitions:
            if kind == "index" and sql:
                target.execute(sql)
        if source.execute("SELECT 1 FROM sqlite_master WHERE name='sqlite_sequence'").fetchone():
            target.execute("DELETE FROM sqlite_sequence")
            sequence = source.execute("SELECT name,seq FROM sqlite_sequence").fetchall()
            require(all(name in tables and type(seq) is int for name, seq in sequence))
            target.executemany("INSERT INTO sqlite_sequence VALUES (?,?)", sequence)
        require(not target.execute("PRAGMA foreign_key_check").fetchall())
        require(target.execute("PRAGMA integrity_check").fetchone()[0] == "ok")
        target.commit()
    return counts


def anonymize(main_source, workout_source, output_dir):
    main_source, workout_source, output_dir = (Path(path).resolve() for path in
                                               (main_source, workout_source, output_dir))
    names = ("v12-sample.db", "workouts-sample.db", "anonymized-manifest.json")
    for source in (main_source, workout_source):
        for name in names:
            target = output_dir / name
            require(target != source and (not target.exists() or not target.samefile(source)))
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
    anonymizer = Anonymizer(schema)
    anonymizer.identity = schema["identityHash"]
    with ExitStack() as stack:
        main = stack.enter_context(closing(source_connection(main_source)))
        workouts = stack.enter_context(closing(source_connection(workout_source)))
        anonymizer.select_time_shift(main, workouts)
        output_dir.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".anonymize-", dir=output_dir) as temporary:
            staging = Path(temporary)
            main_counts = rebuild(main, staging / names[0], anonymizer)
            workout_counts = rebuild(workouts, staging / names[1], anonymizer, workout=True)
            manifest = dict(provenance="anonymized-v12", sanitizationVersion=1,
                            mainSchemaVersion=12, workoutSchemaVersion=1,
                            mainTableCounts=main_counts, workoutTableCounts=workout_counts,
                            attachmentsCopied=False, privateMetadataIncluded=False)
            (staging / names[2]).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n",
                                          encoding="utf-8")
            for name in names:
                os.replace(staging / name, output_dir / name)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--main-source", type=Path, required=True)
    parser.add_argument("--workout-source", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = anonymize(args.main_source, args.workout_source, args.output_dir)
    except Exception:
        # SQLite/JSON exception messages can contain private values or paths.
        print("Anonymization failed: unsupported field, invalid contract, or unavailable offline export.",
              file=__import__("sys").stderr)
        return 1
    print(json.dumps(manifest, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
