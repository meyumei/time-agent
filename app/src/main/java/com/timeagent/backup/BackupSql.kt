package com.timeagent.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.timeagent.core.workout.*

internal data class BackupCell(val type: Int, val value: String? = null)
internal data class BackupTable(val columns: List<String>, val rows: List<List<BackupCell>>)
internal data class BackupDatabase(val version: Int, val tables: Map<String, BackupTable>)

/** Adapts the two SQLite interfaces used by live Room and startup recovery. */
internal class BackupSql(val version: Int, val query: (String) -> Cursor, val exec: (String, Array<Any?>) -> Unit)
internal fun SupportSQLiteDatabase.backupSql() = BackupSql(version, { query(it) }, { sql, args -> if (args.isEmpty()) execSQL(sql) else execSQL(sql, args) })
internal fun SQLiteDatabase.backupSql() = BackupSql(version, { rawQuery(it, null) }, { sql, args -> if (args.isEmpty()) execSQL(sql) else execSQL(sql, args) })

internal object BackupTables {
    val main = listOf("categories", "tags", "academic_terms", "activity_templates", "tasks", "courses", "course_occurrences", "schedule_blocks", "time_records", "task_tags", "record_tags", "reminders", "ideas", "notes", "import_batches", "health_sync_items", "agent_suggestions", "entity_revisions", "operation_history", "personal_memories", "projects", "task_placements", "weekly_goals", "recurring_rules", "recurring_instances", "attachments", "ledger_accounts", "ledger_categories", "payment_captures", "ledger_transactions")
    fun mainForVersion(version: Int): List<String> {
        require(version in 7..12) { "备份数据库版本不受支持" }
        return main.filterNot { (version < 12 && it in setOf("ledger_accounts", "ledger_categories", "payment_captures", "ledger_transactions")) || (version < 11 && it == "attachments") || (version < 8 && it == "personal_memories") || (version < 9 && it in setOf("projects","task_placements","weekly_goals")) || (version < 10 && it in setOf("recurring_rules","recurring_instances")) }
    }
    val workout = listOf("workout_sessions", "workout_templates")
    private val gson = Gson()
    private fun quote(name: String): String { require(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))); return "`$name`" }
    private fun columns(db: BackupSql, table: String): List<String> = db.query("PRAGMA table_info(${quote(table)})").use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(1)) } }

    fun capture(db: BackupSql, expected: List<String>): BackupDatabase {
        var capturedCells = 0L
        var capturedCharacters = 0L
        val actual = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')").use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        require(actual == expected.toSet()) { "存在未支持的用户数据表，拒绝生成不完整备份" }
        return BackupDatabase(db.version, expected.associateWith { table ->
            db.query("SELECT * FROM ${quote(table)}").use { cursor ->
                BackupTable(cursor.columnNames.toList(), buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                        require(++capturedCells <= 400_000) { "本机数据超过当前备份处理范围，未生成不完整备份" }
                        val type = cursor.getType(index)
                        val value = when (type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).also { require(it.isFinite()) }.toString()
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                            Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
                            else -> error("无法备份的数据类型")
                        }
                        capturedCharacters += value?.length ?: 0
                        require(capturedCharacters <= 6_000_000) { "本机数据超过当前备份处理范围，未生成不完整备份" }
                        BackupCell(type, value)
                    })
                })
            }
        })
    }

    fun validateStructure(data: BackupDatabase, live: BackupSql, expected: List<String>) {
        require(data.version == live.version) { "备份数据库版本 ${data.version} 与本机版本 ${live.version} 不兼容" }
        require(data.tables.keys == expected.toSet()) { "备份数据表不完整或包含未知表" }
        require(data.tables.values.sumOf { it.rows.size.toLong() } <= 500_000) { "备份行数超过当前支持范围" }
        expected.forEach { table ->
            val saved = requireNotNull(data.tables[table])
            require(saved.columns == columns(live, table)) { "备份表 $table 的字段不兼容" }
            val types = live.query("PRAGMA table_info(${quote(table)})").use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(2).uppercase() to (cursor.getInt(3) != 0)) } }
            saved.rows.forEach { row ->
                require(row.size == saved.columns.size) { "备份行字段数量错误" }
                row.forEachIndexed { index, cell ->
                    val (type, required) = types[index]
                    require(if (cell.type == Cursor.FIELD_TYPE_NULL) !required else when (type) {
                        "INTEGER" -> cell.type == Cursor.FIELD_TYPE_INTEGER
                        "REAL" -> cell.type in setOf(Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT)
                        "TEXT" -> cell.type == Cursor.FIELD_TYPE_STRING
                        "BLOB" -> cell.type == Cursor.FIELD_TYPE_BLOB
                        else -> false
                    }) { "备份表 $table 的字段类型无效" }
                    cellValue(cell)
                }
            }
        }
    }

    private fun cellValue(cell: BackupCell): Any? = when (cell.type) {
        Cursor.FIELD_TYPE_NULL -> { require(cell.value == null); null }
        Cursor.FIELD_TYPE_INTEGER -> requireNotNull(cell.value).toLong()
        Cursor.FIELD_TYPE_FLOAT -> requireNotNull(cell.value).toDouble().also { require(it.isFinite()) { "备份含无效数字" } }
        Cursor.FIELD_TYPE_STRING -> requireNotNull(cell.value)
        Cursor.FIELD_TYPE_BLOB -> Base64.decode(requireNotNull(cell.value), Base64.NO_WRAP)
        else -> throw IllegalArgumentException("备份字段类型无效")
    }

    /** Caller owns the transaction; deferral also handles self-referencing activity templates. */
    fun replace(db: BackupSql, data: BackupDatabase, tables: List<String>) {
        validateStructure(data, db, tables)
        db.exec("PRAGMA defer_foreign_keys=ON", emptyArray())
        tables.asReversed().forEach { db.exec("DELETE FROM ${quote(it)}", emptyArray()) }
        tables.forEach { table ->
            val saved = requireNotNull(data.tables[table])
            val sql = "INSERT INTO ${quote(table)} (${saved.columns.joinToString(",") { quote(it) }}) VALUES (${saved.columns.joinToString(",") { "?" }})"
            saved.rows.forEach { row -> db.exec(sql, row.map(::cellValue).toTypedArray()) }
        }
        db.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "备份中的数据关联无效" } }
    }

    fun preflight(data: BackupDatabase, live: BackupSql, tables: List<String>, isWorkout: Boolean) {
        validateStructure(data, live, tables)
        SQLiteDatabase.create(null).use { scratch ->
            scratch.setForeignKeyConstraintsEnabled(true)
            tables.forEach { table ->
                live.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='${table}'").use { cursor ->
                    require(cursor.moveToFirst()); scratch.execSQL(cursor.getString(0))
                }
            }
            tables.forEach { table -> live.query("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='$table' AND sql IS NOT NULL").use { cursor -> while (cursor.moveToNext()) scratch.execSQL(cursor.getString(0)) } }
            scratch.version = data.version
            scratch.beginTransaction()
            try {
                replace(scratch.backupSql(), data, tables)
                if (isWorkout) validateWorkouts(scratch) else { validateMain(scratch); validateNotes(scratch) }
                scratch.setTransactionSuccessful()
            } finally { scratch.endTransaction() }
        }
    }

    private fun validateNotes(db: SQLiteDatabase) {
        val targets = mapOf("PROJECT" to "projects", "ACTIVITY" to "activity_templates", "TASK" to "tasks", "PLAN" to "schedule_blocks", "RECORD" to "time_records", "OCCURRENCE" to "course_occurrences", "IDEA" to "ideas", "REMINDER" to "reminders")
        db.rawQuery("SELECT targetType,targetId FROM notes", null).use { cursor ->
            while (cursor.moveToNext()) {
                val table = requireNotNull(targets[cursor.getString(0)]) { "笔记所属类型无效" }
                require(cursor.getString(1).isNotBlank()) { "笔记关联标识无效" }
                // Deleting a record intentionally retains its notes for a later undo.
            }
        }
    }

    fun unlinkedNotes(data: BackupDatabase): Int {
        val targets = mapOf("PROJECT" to "projects", "ACTIVITY" to "activity_templates", "TASK" to "tasks", "PLAN" to "schedule_blocks", "RECORD" to "time_records", "OCCURRENCE" to "course_occurrences", "IDEA" to "ideas", "REMINDER" to "reminders")
        val notes = data.tables.getValue("notes")
        val typeColumn = notes.columns.indexOf("targetType")
        val idColumn = notes.columns.indexOf("targetId")
        val ids = targets.mapValues { (_, table) -> val saved = data.tables.getValue(table); val index = saved.columns.indexOf("id"); saved.rows.map { it[index].value }.toSet() }
        return notes.rows.count { it[idColumn].value !in ids[it[typeColumn].value.orEmpty()].orEmpty() }
    }

    private fun validateMain(db: SQLiteDatabase) {
        fun rejectIf(sql: String, message: String) = db.rawQuery(sql, null).use { require(!it.moveToFirst()) { message } }
        rejectIf("SELECT 1 FROM tasks WHERE trim(title)='' OR estimatedMinutes NOT BETWEEN 1 AND 10080 OR priority NOT BETWEEN 0 AND 2 OR status NOT IN ('TODO','IN_PROGRESS','COMPLETED','CANCELLED') LIMIT 1", "备份含无效任务内容")
        rejectIf("SELECT 1 FROM schedule_blocks WHERE endAt<=startAt OR isLocked NOT IN (0,1) LIMIT 1", "备份计划时段无效")
        rejectIf("SELECT 1 FROM time_records WHERE (endAt IS NOT NULL AND endAt<=startAt) OR isManuallyEdited NOT IN (0,1) LIMIT 1", "备份实际记录时段或手工保护标记无效")
        rejectIf("SELECT 1 FROM time_records WHERE endAt IS NULL LIMIT 1 OFFSET 1", "备份包含多个进行中的计时")
        rejectIf("SELECT 1 FROM course_occurrences WHERE endAt<=startAt OR isManuallyModified NOT IN (0,1) LIMIT 1", "备份课程时段或保护标记无效")
        rejectIf("SELECT 1 FROM reminders WHERE trim(title)='' OR enabled NOT IN (0,1) LIMIT 1", "备份提醒内容无效")
        rejectIf("SELECT 1 FROM health_sync_items WHERE endAt<=startAt OR userDeleted NOT IN (0,1) OR kind NOT IN ('SLEEP','EXERCISE') LIMIT 1", "备份健康来源或删除保护标记无效")
        rejectIf("SELECT 1 FROM operation_history WHERE undone NOT IN (0,1) OR redoable NOT IN (0,1) LIMIT 1", "备份撤销标记无效")
        rejectIf("SELECT 1 FROM entity_revisions WHERE revision<0 LIMIT 1", "备份对象版本无效")
        rejectIf("SELECT 1 FROM entity_revisions WHERE `key` GLOB 'REMINDER_MANUAL_OFF:*' AND revision NOT IN (0,1) LIMIT 1","提醒停用原因标记无效")
        if (db.version >= 12) {
            rejectIf("SELECT 1 FROM ledger_transactions WHERE amountMinor<=0 OR direction NOT IN ('EXPENSE','INCOME','REFUND') OR version<0 LIMIT 1", "备份账目金额或方向无效")
            rejectIf("SELECT 1 FROM payment_captures WHERE amountMinor<=0 OR direction NOT IN ('EXPENSE','INCOME','REFUND') OR status NOT IN ('PENDING','CONFIRMED','IGNORED') LIMIT 1", "备份支付草稿状态无效")
        }
        if (db.version >= 11) {
            rejectIf("SELECT 1 FROM attachments WHERE trim(originalName)='' OR sizeBytes NOT BETWEEN 1 AND 8388608 OR version<0 OR (taskId IS NOT NULL AND projectId IS NOT NULL) LIMIT 1", "备份附件内容或关联无效")
            rejectIf("SELECT 1 FROM attachments a WHERE (taskId IS NOT NULL AND NOT EXISTS(SELECT 1 FROM tasks t WHERE t.id=a.taskId)) OR (projectId IS NOT NULL AND NOT EXISTS(SELECT 1 FROM projects p WHERE p.id=a.projectId)) LIMIT 1", "备份附件关联不存在")
            db.rawQuery("SELECT storageKey,sha256 FROM attachments",null).use { c -> while(c.moveToNext()) {
                require(com.timeagent.attachments.AttachmentStore.validKey(c.getString(0)) && c.getString(1).matches(Regex("[0-9a-f]{64}"))) { "备份附件路径或校验值无效" }
            } }
        }
        if (db.version >= 10) {
            fun rowText(c:Cursor,name:String)=c.getString(c.getColumnIndexOrThrow(name))
            fun rowLong(c:Cursor,name:String)=c.getLong(c.getColumnIndexOrThrow(name))
            fun optional(c:Cursor,name:String):Long?=c.getColumnIndexOrThrow(name).let{if(c.isNull(it))null else c.getLong(it)}
            db.rawQuery("SELECT * FROM recurring_rules",null).use{c->while(c.moveToNext()) {
                com.timeagent.recurring.RecurringValidation.pattern(com.timeagent.data.RecurringRuleEntity(rowText(c,"id"),rowText(c,"title"),rowText(c,"patternJson"),rowText(c,"domain"),rowText(c,"category"),rowText(c,"reference"),version=rowLong(c,"version")))
            }}
            rejectIf("SELECT 1 FROM recurring_instances WHERE manualOverride NOT IN (0,1) OR version<0 LIMIT 1","周期实例版本或手改保护标记无效")
            db.rawQuery("SELECT * FROM recurring_instances",null).use{c->while(c.moveToNext()) {
                com.timeagent.recurring.RecurringValidation.instance(com.timeagent.data.RecurringInstanceEntity(rowText(c,"id"),rowText(c,"ruleId"),rowText(c,"taskId"),rowText(c,"originalDate"),rowLong(c,"ordinal").toInt(),optional(c,"scheduledAt"),rowText(c,"status"),rowLong(c,"manualOverride")==1L,rowText(c,"ruleSnapshot"),rowLong(c,"ruleVersion"),rowLong(c,"taskVersion"),optional(c,"planVersion"),optional(c,"placementVersion"),rowText(c,"scheduleNote"),rowLong(c,"version")))
            }}
        }
        if (db.version >= 9) {
            rejectIf("SELECT 1 FROM projects WHERE trim(name)='' OR length(name)>100 OR status NOT IN ('ACTIVE','PAUSED','COMPLETED') LIMIT 1", "备份项目名称或状态无效")
            rejectIf("SELECT 1 FROM task_placements p LEFT JOIN task_placements parent ON parent.taskId=p.parentTaskId WHERE p.parentTaskId IS NOT NULL AND (p.taskId=p.parentTaskId OR parent.taskId IS NULL OR parent.parentTaskId IS NOT NULL OR p.projectId IS NOT parent.projectId) LIMIT 1", "备份父子任务层级或归属无效")
            rejectIf("SELECT 1 FROM weekly_goals WHERE (projectId IS NULL)=(activityId IS NULL) OR targetMinutes NOT BETWEEN 1 AND 10080 LIMIT 1", "备份周目标对象或时长无效")
            rejectIf("SELECT 1 FROM weekly_goals GROUP BY projectId,activityId,weekStart HAVING COUNT(*)>1 LIMIT 1", "备份含重复周目标")
            db.rawQuery("SELECT weekStart FROM weekly_goals",null).use { c -> while(c.moveToNext()) {
                require(runCatching { java.time.LocalDate.parse(c.getString(0)).dayOfWeek == java.time.DayOfWeek.MONDAY }.getOrDefault(false)) { "周目标起始日期须为周一" }
            } }
        }
        if (db.version >= 8) db.rawQuery("SELECT * FROM personal_memories", null).use { c -> while (c.moveToNext()) {
            fun text(name: String) = c.getString(c.getColumnIndexOrThrow(name))
            fun number(name: String): Long? = c.getColumnIndexOrThrow(name).let { if (c.isNull(it)) null else c.getLong(it) }
            require(number("enabled") in setOf(0L, 1L) && number("confirmed") in setOf(0L, 1L)) { "记忆状态无效" }
            com.timeagent.memory.PersonalMemoryRules.validate(com.timeagent.data.PersonalMemoryEntity(text("id"), text("kind"), text("text"), sourceType = text("sourceType"), sourceText = text("sourceText"), enabled = number("enabled") == 1L, confirmed = number("confirmed") == 1L,
                validFrom = number("validFrom"), validUntil = number("validUntil"), ruleType = text("ruleType"), keyword = text("keyword"), weekday = number("weekday")?.toInt(), startMinute = number("startMinute")?.toInt(), endMinute = number("endMinute")?.toInt()))
        } }
    }

    private fun validateWorkouts(db: SQLiteDatabase) {
        db.rawQuery("SELECT id,startedAt,activeSlot,payload FROM workout_sessions", null).use { cursor -> while (cursor.moveToNext()) {
            val session = gson.fromJson(cursor.getString(3), WorkoutSession::class.java)
            WorkoutRules.validateSession(session)
            require(session.id == cursor.getString(0) && session.startedAt == cursor.getLong(1)) { "训练内容与索引不一致" }
            val active = session.status in setOf(WorkoutSessionStatus.ACTIVE, WorkoutSessionStatus.PAUSED)
            require(if (active) !cursor.isNull(2) && cursor.getInt(2) == 1 else cursor.isNull(2)) { "训练活动状态不一致" }
        } }
        db.rawQuery("SELECT id,payload FROM workout_templates", null).use { cursor -> while (cursor.moveToNext()) {
            val template = gson.fromJson(cursor.getString(1), WorkoutTemplate::class.java)
            WorkoutRules.validateTemplate(template)
            require(template.id == cursor.getString(0)) { "训练模板内容与索引不一致" }
        } }
    }
}
