package com.timeagent.reports

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.stream.JsonWriter
import com.timeagent.core.workout.WorkoutSession
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.ledger.LedgerRepository
import com.timeagent.workout.data.WorkoutDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    CSV_ZIP("CSV（zip）", "zip", "application/zip"),
    JSON("JSON", "json", "application/json"),
    ICS("ICS 日程", "ics", "text/calendar"),
}

enum class ExportDataType(val label: String) {
    SCHEDULE("日程"),
    ACTUAL("实际"),
    TASK("任务"),
    PROJECT("项目"),
    RECURRING("周期"),
    LEDGER("记账"),
    WORKOUT("训练"),
    HEALTH("健康"),
    HABITS("习惯"),
    FOCUS("专注"),
}

/**
 * Streams the selected range into a temporary file, then copies it into the user-selected SAF
 * document. The temporary file keeps cancellation atomic: a stopped job never leaves a partially
 * written export in the target document, and the target is removed when the job is cancelled.
 */
class ExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val gson = Gson()
    private val zone: ZoneId = ZoneId.systemDefault()
    private var tempFile: File? = null
    private var startedElapsed = 0L

    override suspend fun doWork(): Result {
        startedElapsed = android.os.SystemClock.elapsedRealtime()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse)
            ?: return Result.failure(workDataOf(KEY_ERROR to "没有选择导出位置"))
        val format = runCatching { ExportFormat.valueOf(inputData.getString(KEY_FORMAT).orEmpty()) }
            .getOrElse { ExportFormat.CSV_ZIP }
        val types = inputData.getStringArray(KEY_TYPES)
            ?.mapNotNull { runCatching { ExportDataType.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?.ifEmpty { ExportDataType.entries.toSet() }
            ?: ExportDataType.entries.toSet()
        val startAt = inputData.getLong(KEY_START_AT, 0L)
        val endAt = inputData.getLong(KEY_END_AT, 0L)
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "time-agent-export.${format.extension}"
        if (endAt <= startAt) return Result.failure(workDataOf(KEY_ERROR to "导出时间范围无效"))

        val temp = File(applicationContext.cacheDir, "report-export/${id}.${format.extension}.part")
        tempFile = temp
        runCatching { temp.parentFile?.mkdirs() }
        ReportNotifications.ensureChannels(applicationContext)
        return try {
            updateProgress(0, "准备导出")
            withContext(Dispatchers.IO) {
                when (format) {
                    ExportFormat.CSV_ZIP -> writeCsvZip(temp, types, startAt, endAt)
                    ExportFormat.JSON -> writeJson(temp, types, startAt, endAt)
                    ExportFormat.ICS -> writeIcs(temp, startAt, endAt)
                }
            }
            checkActive()
            updateProgress(96, "写入所选位置")
            withContext(Dispatchers.IO) { copyToTarget(temp, uri) }
            checkActive()
            updateProgress(100, "导出完成")
            ReportNotifications.notifyExportDone(applicationContext, fileName)
            Result.success(workDataOf(KEY_URI to uri.toString(), KEY_FILE_NAME to fileName))
        } catch (cancelled: CancellationException) {
            val cleanupError = withContext(NonCancellable + Dispatchers.IO) { cleanup(uri, temp) }
            if (cleanupError != null) ReportNotifications.notifyIncompleteExport(applicationContext, cleanupError)
            ReportNotifications.cancelExport(applicationContext)
            throw cancelled
        } catch (error: Throwable) {
            val cleanupError = withContext(NonCancellable + Dispatchers.IO) { cleanup(uri, temp) }
            ReportNotifications.cancelExport(applicationContext)
            Result.failure(
                workDataOf(
                    KEY_ERROR to listOfNotNull(error.message?.takeIf(String::isNotBlank) ?: "导出失败。", cleanupError).joinToString(" "),
                ),
            )
        } finally {
            tempFile = null
            temp.delete()
        }
    }

    private suspend fun updateProgress(percent: Int, stage: String) {
        setProgress(workDataOf(KEY_PROGRESS to percent.coerceIn(0, 100), KEY_STAGE to stage))
        if (android.os.SystemClock.elapsedRealtime() - startedElapsed >= 2_000 && percent < 100)
            ReportNotifications.showExportProgress(applicationContext, id, percent, stage)
    }

    private fun checkActive() {
        if (isStopped) throw CancellationException("导出已取消")
    }

    private fun copyToTarget(temp: File, target: Uri) {
        if (!temp.exists() || temp.length() == 0L) error("导出文件为空")
        val output = applicationContext.contentResolver.openOutputStream(target, "wt")
            ?: applicationContext.contentResolver.openOutputStream(target)
            ?: error("无法打开所选位置，请重新选择")
        output.use { out ->
            temp.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            }
        }
    }

    private fun cleanup(target: Uri, temp: File): String? {
        val warning = try {
            if (android.provider.DocumentsContract.isDocumentUri(applicationContext, target))
                check(android.provider.DocumentsContract.deleteDocument(applicationContext.contentResolver, target)) {
                    "文件提供方未删除文件"
                }
            else check(applicationContext.contentResolver.delete(target, null, null) > 0) { "文件提供方未删除文件" }
            null
        } catch (error: Exception) {
            android.util.Log.e("TimeAgentExport", "Could not remove incomplete document: $target", error)
            "文件提供方未能清理未完成文件，请在所选位置删除该文件后重新导出。"
        }
        temp.delete()
        applicationContext.getSharedPreferences("export_status", 0).edit()
            .putString("cleanup_error", warning).apply()
        return warning
    }

    // ---------------------------------------------------------------------------------------------
    // CSV zip
    // ---------------------------------------------------------------------------------------------

    private suspend fun writeCsvZip(file: File, types: Set<ExportDataType>, startAt: Long, endAt: Long) {
        val db = TimeAgentDatabase.open(applicationContext)
        val workoutDb = if (ExportDataType.WORKOUT in types) WorkoutDatabase.open(applicationContext) else null
        val ledgerRows = if (ExportDataType.LEDGER in types) {
            db.ledgerDao().transactions()
                .filter { it.deletedAt == null && it.occurredAt >= startAt && it.occurredAt < endAt }
        } else emptyList()
        val ledgerRepository = LedgerRepository(db)
        val unavailable = unavailable(db, workoutDb, types)
        val entries = mutableListOf<CsvEntry>()
        if (ExportDataType.SCHEDULE in types) entries += CsvEntry("schedules.csv") { out ->
            writeSchedulesCsv(db, out, startAt, endAt)
        }
        if (ExportDataType.ACTUAL in types) entries += CsvEntry("actuals.csv") { out ->
            writeActualsCsv(db, out, startAt, endAt)
        }
        if (ExportDataType.TASK in types) entries += CsvEntry("tasks.csv") { out ->
            writeTasksCsv(db, out, startAt, endAt)
        }
        if (ExportDataType.PROJECT in types) entries += CsvEntry("projects.csv") { out ->
            writeProjectsCsv(db, out, startAt, endAt)
        }
        if (ExportDataType.RECURRING in types) {
            entries += CsvEntry("recurring_rules.csv") { out -> writeRecurringRulesCsv(db, out, startAt, endAt) }
            entries += CsvEntry("recurring_instances.csv") { out -> writeRecurringInstancesCsv(db, out, startAt, endAt) }
        }
        if (ExportDataType.LEDGER in types) entries += CsvEntry("ledger.csv") { out ->
            val accounts = db.ledgerDao().accounts()
            val categories = db.ledgerDao().categories()
            val captures = db.ledgerDao().captures()
            val sources = db.ledgerDao().sources()
            out.write(ledgerRepository.exportCsv(ledgerRows, accounts, categories, captures, sources).toByteArray(Charsets.UTF_8))
        }
        if (ExportDataType.WORKOUT in types) entries += CsvEntry("workouts.csv") { out ->
            writeWorkoutsCsv(workoutDb, out, startAt, endAt)
        }
        if (ExportDataType.HEALTH in types) entries += CsvEntry("health.csv") { out ->
            writeHealthCsv(db, out, startAt, endAt)
        }
        if (ExportDataType.HABITS in types) {
            entries += CsvEntry("habits.csv") { out -> writeHabitsCsv(db, out) }
            entries += CsvEntry("habit_checkins.csv") { out -> writeHabitCheckinsCsv(db, out, startAt, endAt) }
        }
        if (ExportDataType.FOCUS in types) entries += CsvEntry("focus_sessions.csv") { out ->
            writeFocusCsv(db, out, startAt, endAt)
        }
        supplementary(types, startAt, endAt).forEach { (name, query) ->
            entries += CsvEntry("$name.csv") { out -> writeRawCsv(db, out, query) }
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            entries.forEachIndexed { index, entry ->
                checkActive()
                updateProgress(2 + index * 88 / entries.size.coerceAtLeast(1), "正在写入 ${entry.name}")
                zip.putNextEntry(ZipEntry(entry.name))
                val entryOut = BufferedOutputStream(NonClosingOutputStream(zip))
                entry.write(entryOut)
                entryOut.flush()
                zip.closeEntry()
            }
            checkActive()
            val manifest = mapOf(
                "schemaVersion" to db.openHelper.readableDatabase.version,
                "exportFormatVersion" to SCHEMA_VERSION,
                "format" to "CSV_ZIP",
                "exportedAt" to System.currentTimeMillis(),
                "exportedAtLocal" to localDateTime(System.currentTimeMillis()),
                "range" to rangeMap(startAt, endAt),
                "timezone" to zone.id,
                "files" to entries.map { it.name },
                "unavailable" to unavailable,
            )
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(gson.toJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private data class CsvEntry(val name: String, val write: suspend (OutputStream) -> Unit)

    private fun writeSchedulesCsv(db: TimeAgentDatabase, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("ID", "任务ID", "标题", "开始", "结束", "状态", "类型", "全天开始日期", "全天结束日期", "时区", "备注", "来源", "来源引用", "创建时间", "更新时间", "版本", "删除时间")
        val sql = "SELECT b.id, b.taskId, COALESCE(NULLIF(b.title,''), t.title, '') AS title, " +
            "b.startAt, b.endAt, b.status, b.kind, b.allDayStartDate, b.allDayEndDate, b.timezoneId, " +
            "b.notes, b.source, b.sourceRef, b.createdAt, b.updatedAt, b.version, b.deletedAt " +
            "FROM schedule_blocks b LEFT JOIN tasks t ON t.id=b.taskId " +
            "WHERE b.deletedAt IS NULL AND ((b.endAt > b.startAt AND b.startAt < ? AND b.endAt > ?) " +
            "OR (b.allDayStartDate IS NOT NULL AND b.allDayStartDate <= ? AND COALESCE(b.allDayEndDate,b.allDayStartDate) >= ?)) " +
            "ORDER BY b.startAt"
        writeCsvQuery(db, out, withoutSamples(sql, "PLAN"), arrayOf(endAt, startAt, localDate(endAt - 1), localDate(startAt)), header) { cursor ->
            listOf(
                cursor.str("id"), cursor.str("taskId"), cursor.str("title"),
                localDateTime(cursor.long("startAt")), localDateTime(cursor.long("endAt")),
                cursor.str("status"), cursor.str("kind"), cursor.str("allDayStartDate"),
                cursor.str("allDayEndDate"), cursor.str("timezoneId"), cursor.str("notes"),
                cursor.str("source"), cursor.str("sourceRef"), localDateTime(cursor.long("createdAt")),
                localDateTime(cursor.long("updatedAt")), cursor.long("version").toString(),
                nullableDateTime(cursor, "deletedAt"),
            )
        }
    }

    private fun writeActualsCsv(db: TimeAgentDatabase, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("ID", "任务ID", "标题", "开始", "结束", "分类ID", "备注", "来源", "手工修改", "创建时间", "更新时间", "版本")
        val sql = "SELECT r.id, r.taskId, COALESCE(NULLIF(r.title,''), t.title, '') AS title, " +
            "r.startAt, r.endAt, r.categoryId, r.notes, r.source, r.isManuallyEdited, r.createdAt, r.updatedAt, r.version " +
            "FROM time_records r LEFT JOIN tasks t ON t.id=r.taskId " +
            "WHERE r.startAt < ? AND (r.endAt IS NULL OR r.endAt > ?) ORDER BY r.startAt"
        writeCsvQuery(db, out, withoutSamples(sql, "RECORD"), arrayOf(endAt, startAt), header) { cursor ->
            listOf(
                cursor.str("id"), cursor.str("taskId"), cursor.str("title"),
                localDateTime(cursor.long("startAt")), nullableDateTime(cursor, "endAt"),
                cursor.str("categoryId"), cursor.str("notes"), cursor.str("source"),
                if (cursor.int("isManuallyEdited") == 1) "是" else "否",
                localDateTime(cursor.long("createdAt")), localDateTime(cursor.long("updatedAt")),
                cursor.long("version").toString(),
            )
        }
    }

    private fun writeTasksCsv(db: TimeAgentDatabase, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("ID", "标题", "描述", "预估分钟", "截止", "优先级", "状态", "分类ID", "活动ID", "项目ID", "创建时间", "更新时间", "完成时间", "来源", "删除时间", "版本")
        val sql = "SELECT t.id, t.title, t.description, t.estimatedMinutes, t.dueAt, t.priority, t.status, " +
            "t.categoryId, t.activityId, p.projectId, t.createdAt, t.updatedAt, t.completedAt, t.source, t.deletedAt, t.version " +
            "FROM tasks t LEFT JOIN task_placements p ON p.taskId=t.id " +
            "WHERE (t.createdAt >= ? AND t.createdAt < ?) OR (t.dueAt >= ? AND t.dueAt < ?) " +
            "OR (t.completedAt >= ? AND t.completedAt < ?) OR (t.deletedAt >= ? AND t.deletedAt < ?) " +
            "ORDER BY t.createdAt"
        val args = arrayOf(startAt, endAt, startAt, endAt, startAt, endAt, startAt, endAt)
        writeCsvQuery(db, out, withoutSamples(sql, "TASK"), args, header) { cursor ->
            listOf(
                cursor.str("id"), cursor.str("title"), cursor.str("description"),
                cursor.long("estimatedMinutes").toString(), nullableDateTime(cursor, "dueAt"),
                cursor.long("priority").toString(), cursor.str("status"), cursor.str("categoryId"),
                cursor.str("activityId"), cursor.str("projectId"), localDateTime(cursor.long("createdAt")),
                localDateTime(cursor.long("updatedAt")), nullableDateTime(cursor, "completedAt"),
                cursor.str("source"), nullableDateTime(cursor, "deletedAt"), cursor.long("version").toString(),
            )
        }
    }

    private fun writeProjectsCsv(db: TimeAgentDatabase, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("ID", "名称", "截止", "状态", "创建时间", "更新时间", "删除时间", "版本")
        val sql = "SELECT id, name, dueAt, status, createdAt, updatedAt, deletedAt, version FROM projects " +
            "WHERE (createdAt >= ? AND createdAt < ?) OR (dueAt >= ? AND dueAt < ?) OR (deletedAt >= ? AND deletedAt < ?) " +
            "ORDER BY createdAt"
        val args = arrayOf(startAt, endAt, startAt, endAt, startAt, endAt)
        writeCsvQuery(db, out, withoutSamples(sql, "PROJECT"), args, header) { cursor ->
            listOf(
                cursor.str("id"), cursor.str("name"), nullableDateTime(cursor, "dueAt"),
                cursor.str("status"), localDateTime(cursor.long("createdAt")),
                localDateTime(cursor.long("updatedAt")), nullableDateTime(cursor, "deletedAt"),
                cursor.long("version").toString(),
            )
        }
    }

    private fun writeRecurringRulesCsv(db: TimeAgentDatabase, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("ID", "标题", "规则JSON", "领域", "分类", "引用", "项目ID", "创建时间", "更新时间", "删除时间", "版本")
        val sql = "SELECT id, title, patternJson, domain, category, reference, projectId, createdAt, updatedAt, deletedAt, version " +
            "FROM recurring_rules WHERE deletedAt IS NULL OR (deletedAt >= ? AND deletedAt < ?) ORDER BY createdAt"
        writeCsvQuery(db, out, sql, arrayOf(startAt, endAt), header) { cursor ->
            listOf(
                cursor.str("id"), cursor.str("title"), cursor.str("patternJson"), cursor.str("domain"),
                cursor.str("category"), cursor.str("reference"), cursor.str("projectId"),
                localDateTime(cursor.long("createdAt")), localDateTime(cursor.long("updatedAt")),
                nullableDateTime(cursor, "deletedAt"), cursor.long("version").toString(),
            )
        }
    }

    private fun writeRecurringInstancesCsv(db: TimeAgentDatabase, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("ID", "规则ID", "任务ID", "原始日期", "序号", "计划时间", "状态", "手工例外", "规则快照", "规则版本", "任务版本", "计划版本", "放置版本", "日程备注", "版本")
        val sql = "SELECT id, ruleId, taskId, originalDate, ordinal, scheduledAt, status, manualOverride, ruleSnapshot, " +
            "ruleVersion, taskVersion, planVersion, placementVersion, scheduleNote, version FROM recurring_instances " +
            "WHERE originalDate >= ? AND originalDate <= ? ORDER BY originalDate, ordinal"
        writeCsvQuery(db, out, sql, arrayOf(localDate(startAt), localDate(endAt - 1)), header) { cursor ->
            listOf(
                cursor.str("id"), cursor.str("ruleId"), cursor.str("taskId"), cursor.str("originalDate"),
                cursor.long("ordinal").toString(), nullableDateTime(cursor, "scheduledAt"),
                cursor.str("status"), if (cursor.int("manualOverride") == 1) "是" else "否",
                cursor.str("ruleSnapshot"), cursor.long("ruleVersion").toString(),
                cursor.long("taskVersion").toString(), nullableLong(cursor, "planVersion"),
                nullableLong(cursor, "placementVersion"), cursor.str("scheduleNote"),
                cursor.long("version").toString(),
            )
        }
    }

    private fun writeWorkoutsCsv(workoutDb: WorkoutDatabase?, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("ID", "标题", "状态", "开始", "结束", "训练分钟", "完成组数", "总量kg", "备注")
        out.write(BOM)
        out.writeCsvRow(header)
        if (workoutDb == null) return
        val rows = run {
            workoutDb.openHelper.readableDatabase.query(
                "SELECT id, startedAt, payload FROM workout_sessions ORDER BY startedAt",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("id")) to cursor.getString(cursor.getColumnIndexOrThrow("payload")))
                    }
                }
            }
        }
        rows.forEach { (id, payload) ->
            checkActive()
            val session = gson.fromJson(payload, WorkoutSession::class.java)
            if (session.startedAt >= endAt || (session.endedAt ?: Long.MAX_VALUE) <= startAt) return@forEach
            val activeMinutes = ((session.endedAt ?: session.updatedAt) - session.startedAt - session.totalPausedMillis)
                .coerceAtLeast(0L) / 60_000L
            val sets = session.movements.sumOf { movement -> movement.sets.count { it.isCompleted } }
            val volume = session.movements.sumOf { movement ->
                movement.sets.filter { it.isCompleted }.sumOf { set ->
                    (set.weightKg ?: 0.0) * (set.reps ?: 0)
                }
            }
            out.writeCsvRow(
                listOf(
                    id, session.title, session.status.name, localDateTime(session.startedAt),
                    session.endedAt?.let(::localDateTime).orEmpty(), activeMinutes.toString(),
                    sets.toString(), String.format(Locale.ROOT, "%.2f", volume), session.notes,
                ),
            )
        }
    }

    private fun writeHealthCsv(db: TimeAgentDatabase, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("ID", "类型", "标题", "开始", "结束", "来源包", "关联实际ID", "用户删除", "最后同步")
        val sql = "SELECT id, kind, title, startAt, endAt, originPackage, recordId, userDeleted, lastSyncedAt " +
            "FROM health_sync_items WHERE userDeleted=0 AND startAt < ? AND endAt > ? ORDER BY startAt"
        writeCsvQuery(db, out, sql, arrayOf(endAt, startAt), header) { cursor ->
            listOf(
                cursor.str("id"), cursor.str("kind"), cursor.str("title"),
                localDateTime(cursor.long("startAt")), localDateTime(cursor.long("endAt")),
                cursor.str("originPackage"), cursor.str("recordId"),
                if (cursor.int("userDeleted") == 1) "是" else "否",
                localDateTime(cursor.long("lastSyncedAt")),
            )
        }
    }

    private fun writeHabitsCsv(db: TimeAgentDatabase, out: OutputStream) {
        val header = listOf("ID", "名称", "图标", "颜色", "统计方式", "数据来源", "来源引用", "目标", "周期", "星期", "开始日期", "归档时间", "提醒", "排序", "创建时间", "更新时间", "版本")
        if (!db.hasTable("habits")) {
            out.write(BOM)
            out.writeCsvRow(header)
            return
        }
        val sql = "SELECT id, name, icon, color, metric, source, sourceRef, target, period, activeWeekdays, " +
            "startDate, archivedAt, reminderEnabled, sortOrder, createdAt, updatedAt, version FROM habits " +
            "ORDER BY sortOrder, createdAt"
        writeCsvQuery(db, out, sql, emptyArray(), header) { cursor ->
            listOf(
                cursor.str("id"), cursor.str("name"), cursor.str("icon"), cursor.long("color").toString(),
                cursor.str("metric"), cursor.str("source"), cursor.str("sourceRef"),
                cursor.long("target").toString(), cursor.str("period"), cursor.str("activeWeekdays"),
                cursor.str("startDate"), nullableDateTime(cursor, "archivedAt"),
                if (cursor.int("reminderEnabled") == 1) "是" else "否",
                cursor.long("sortOrder").toString(), localDateTime(cursor.long("createdAt")),
                localDateTime(cursor.long("updatedAt")), cursor.long("version").toString(),
            )
        }
    }

    private fun writeHabitCheckinsCsv(db: TimeAgentDatabase, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("习惯ID", "日期", "值", "备注", "创建时间", "更新时间", "版本")
        if (!db.hasTable("habit_checkins")) {
            out.write(BOM)
            out.writeCsvRow(header)
            return
        }
        val sql = "SELECT habitId, date, value, note, createdAt, updatedAt, version FROM habit_checkins " +
            "WHERE date >= ? AND date <= ? ORDER BY date, habitId"
        writeCsvQuery(db, out, sql, arrayOf(localDate(startAt), localDate(endAt - 1)), header) { cursor ->
            listOf(
                cursor.str("habitId"), cursor.str("date"), cursor.long("value").toString(),
                cursor.str("note"), localDateTime(cursor.long("createdAt")),
                localDateTime(cursor.long("updatedAt")), cursor.long("version").toString(),
            )
        }
    }

    private fun writeFocusCsv(db: TimeAgentDatabase, out: OutputStream, startAt: Long, endAt: Long) {
        val header = listOf("ID", "请求ID", "任务ID", "日程ID", "标签", "模式", "状态", "计划专注分钟", "计划休息分钟", "长休息分钟", "计划轮数", "当前轮", "开始", "结束", "实际记录ID", "专注毫秒", "时区", "完成原因", "需核对", "版本", "专注片段JSON")
        if (!db.hasTable("focus_sessions")) {
            out.write(BOM)
            out.writeCsvRow(header)
            return
        }
        val sql = "SELECT id, requestId, taskId, scheduleBlockId, label, mode, state, plannedFocusMinutes, plannedBreakMinutes, " +
            "longBreakMinutes, roundsPlanned, roundIndex, startedAt, endedAt, actualRecordId, focusedElapsedMs, timezone, " +
            "finishReason, needsReview, version, segmentsJson FROM focus_sessions " +
            "WHERE startedAt < ? AND (endedAt IS NULL OR endedAt > ?) ORDER BY startedAt"
        writeCsvQuery(db, out, sql, arrayOf(endAt, startAt), header) { cursor ->
            listOf(
                cursor.str("id"), cursor.str("requestId"), cursor.str("taskId"), cursor.str("scheduleBlockId"),
                cursor.str("label"), cursor.str("mode"), cursor.str("state"),
                cursor.long("plannedFocusMinutes").toString(), cursor.long("plannedBreakMinutes").toString(),
                cursor.long("longBreakMinutes").toString(), cursor.long("roundsPlanned").toString(),
                cursor.long("roundIndex").toString(), localDateTime(cursor.long("startedAt")),
                nullableDateTime(cursor, "endedAt"), cursor.str("actualRecordId"),
                cursor.long("focusedElapsedMs").toString(), cursor.str("timezone"),
                cursor.str("finishReason"), if (cursor.int("needsReview") == 1) "是" else "否",
                cursor.long("version").toString(), cursor.str("segmentsJson"),
            )
        }
    }

    private fun withoutSamples(sql: String, type: String): String =
        "SELECT exported.* FROM ($sql) exported WHERE NOT EXISTS " +
            "(SELECT 1 FROM sample_records s WHERE s.entityType='$type' AND s.entityId=exported.id)"

    private fun writeCsvQuery(
        db: TimeAgentDatabase,
        out: OutputStream,
        sql: String,
        args: Array<out Any?>,
        header: List<String>,
        values: (Cursor) -> List<String>,
    ) {
        out.write(BOM)
        out.writeCsvRow(header)
        db.openHelper.readableDatabase.query(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                out.writeCsvRow(values(cursor))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------------------------------

    private suspend fun writeJson(file: File, types: Set<ExportDataType>, startAt: Long, endAt: Long) {
        val db = TimeAgentDatabase.open(applicationContext)
        val workoutDb = if (ExportDataType.WORKOUT in types) WorkoutDatabase.open(applicationContext) else null
        val unavailable = unavailable(db, workoutDb, types)
        FileOutputStream(file).use { fileOut ->
            BufferedOutputStream(fileOut).use { buffered ->
                JsonWriter(buffered.writer(Charsets.UTF_8)).use { writer ->
                    writer.setIndent("  ")
                    writer.beginObject()
                    writer.name("schemaVersion").value(db.openHelper.readableDatabase.version)
                    writer.name("exportFormatVersion").value(SCHEMA_VERSION)
                    writer.name("format").value("JSON")
                    writer.name("exportedAt").value(System.currentTimeMillis())
                    writer.name("exportedAtLocal").value(localDateTime(System.currentTimeMillis()))
                    writer.name("timezone").value(zone.id)
                    writer.name("range").beginObject()
                    writer.name("start").value(localDate(startAt))
                    writer.name("end").value(localDate(endAt - 1))
                    writer.name("startAt").value(startAt)
                    writer.name("endAt").value(endAt)
                    writer.endObject()
                    writer.name("types").beginArray()
                    types.sortedBy { it.ordinal }.forEach { writer.value(it.name) }
                    writer.endArray()
                    writer.name("unavailable").beginArray()
                    unavailable.forEach(writer::value)
                    writer.endArray()
                    writer.name("data").beginObject()

                    var index = 0
                    suspend fun progress(label: String) {
                        checkActive()
                        updateProgress(2 + index * 90 / types.size.coerceAtLeast(1), "正在写入 $label")
                        index++
                    }

                    if (ExportDataType.SCHEDULE in types) {
                        progress("日程")
                        writer.name("schedules")
                        writeQueryArray(db, writer, withoutSamples(SCHEDULE_JSON_SQL, "PLAN"), arrayOf(endAt, startAt, localDate(endAt - 1), localDate(startAt)))
                    }
                    if (ExportDataType.ACTUAL in types) {
                        progress("实际")
                        writer.name("actuals")
                        writeQueryArray(db, writer, withoutSamples(ACTUAL_JSON_SQL, "RECORD"), arrayOf(endAt, startAt))
                    }
                    if (ExportDataType.TASK in types) {
                        progress("任务")
                        writer.name("tasks")
                        writeQueryArray(db, writer, withoutSamples(TASK_JSON_SQL, "TASK"), arrayOf(startAt, endAt, startAt, endAt, startAt, endAt, startAt, endAt))
                    }
                    if (ExportDataType.PROJECT in types) {
                        progress("项目")
                        writer.name("projects")
                        writeQueryArray(db, writer, withoutSamples(PROJECT_JSON_SQL, "PROJECT"), arrayOf(startAt, endAt, startAt, endAt, startAt, endAt))
                    }
                    if (ExportDataType.RECURRING in types) {
                        progress("周期")
                        writer.name("recurring").beginObject()
                        writer.name("rules")
                        writeQueryArray(db, writer, RECURRING_RULE_JSON_SQL, arrayOf(startAt, endAt))
                        writer.name("instances")
                        writeQueryArray(db, writer, RECURRING_INSTANCE_JSON_SQL, arrayOf(localDate(startAt), localDate(endAt - 1)))
                        writer.endObject()
                    }
                    if (ExportDataType.LEDGER in types) {
                        progress("记账")
                        writer.name("ledger").beginObject()
                        writer.name("transactions")
                        writeQueryArray(db, writer, LEDGER_JSON_SQL, arrayOf(startAt, endAt))
                        writer.name("accounts")
                        writeQueryArray(db, writer, "SELECT id, name, type, currency, openingBalanceMinor, sortOrder, archivedAt, createdAt, updatedAt, version FROM ledger_accounts ORDER BY sortOrder")
                        writer.name("categories")
                        writeQueryArray(db, writer, "SELECT id, name, direction, icon, sortOrder, archivedAt, createdAt, updatedAt, version FROM ledger_categories ORDER BY sortOrder")
                        writer.endObject()
                    }
                    if (ExportDataType.WORKOUT in types) {
                        progress("训练")
                        writer.name("workouts")
                        writeWorkoutsJson(workoutDb, writer, startAt, endAt)
                    }
                    if (ExportDataType.HEALTH in types) {
                        progress("健康")
                        writer.name("health")
                        writeQueryArray(db, writer, "SELECT id, kind, title, startAt, endAt, originPackage, recordId, lastSyncedAt FROM health_sync_items WHERE userDeleted=0 AND startAt < ? AND endAt > ? ORDER BY startAt", arrayOf(endAt, startAt))
                    }
                    if (ExportDataType.HABITS in types) {
                        progress("习惯")
                        writer.name("habits").beginObject()
                        writer.name("habits")
                        if (db.hasTable("habits")) {
                            writeQueryArray(db, writer, "SELECT * FROM habits ORDER BY sortOrder, createdAt")
                        } else {
                            writer.beginArray(); writer.endArray()
                        }
                        writer.name("checkins")
                        if (db.hasTable("habit_checkins")) {
                            writeQueryArray(db, writer, "SELECT habitId, date, value, note, createdAt, updatedAt, version FROM habit_checkins WHERE date >= ? AND date <= ? ORDER BY date, habitId", arrayOf(localDate(startAt), localDate(endAt - 1)))
                        } else {
                            writer.beginArray(); writer.endArray()
                        }
                        writer.endObject()
                    }
                    if (ExportDataType.FOCUS in types) {
                        progress("专注")
                        writer.name("focus")
                        if (db.hasTable("focus_sessions")) {
                            writeQueryArray(db, writer, "SELECT id, requestId, taskId, scheduleBlockId, label, mode, state, plannedFocusMinutes, plannedBreakMinutes, longBreakMinutes, roundsPlanned, roundIndex, startedAt, endedAt, actualRecordId, focusedElapsedMs, segmentsJson, timezone, finishReason, needsReview, timeChanged, version FROM focus_sessions WHERE startedAt < ? AND (endedAt IS NULL OR endedAt > ?) ORDER BY startedAt", arrayOf(endAt, startAt))
                        } else {
                            writer.beginArray(); writer.endArray()
                        }
                    }
                    supplementary(types, startAt, endAt).forEach { (name, query) ->
                        writer.name(name)
                        writeQueryArray(db, writer, query)
                    }
                    writer.endObject()
                    writer.endObject()
                    writer.flush()
                }
            }
        }
    }

    private fun supplementary(types: Set<ExportDataType>, start: Long, end: Long): Map<String, String> {
        val queries = linkedMapOf<String, String>()
        val records = "SELECT id FROM time_records r WHERE r.startAt < $end AND (r.endAt IS NULL OR r.endAt > $start) " +
            "AND NOT EXISTS(SELECT 1 FROM sample_records s WHERE s.entityType='RECORD' AND s.entityId=r.id)"
        val tasks = "SELECT id FROM tasks t WHERE NOT EXISTS(SELECT 1 FROM sample_records s WHERE s.entityType='TASK' AND s.entityId=t.id)"
        val plans = "SELECT id FROM schedule_blocks p WHERE p.startAt < $end AND p.endAt >= $start " +
            "AND NOT EXISTS(SELECT 1 FROM sample_records s WHERE s.entityType='PLAN' AND s.entityId=p.id)"
        if (types.any { it in setOf(ExportDataType.SCHEDULE, ExportDataType.ACTUAL, ExportDataType.TASK, ExportDataType.FOCUS) }) {
            queries["categories"] = "SELECT * FROM categories"
            queries["tags"] = "SELECT * FROM tags"
            queries["activity_templates"] = "SELECT * FROM activity_templates"
        }
        if (ExportDataType.ACTUAL in types) queries["record_tags"] = "SELECT * FROM record_tags WHERE recordId IN ($records)"
        if (ExportDataType.TASK in types) {
            queries["task_tags"] = "SELECT * FROM task_tags WHERE taskId IN ($tasks)"
            queries["task_placements"] = "SELECT taskId, " +
                "CASE WHEN projectId IN (SELECT entityId FROM sample_records WHERE entityType='PROJECT') THEN NULL ELSE projectId END AS projectId, " +
                "CASE WHEN parentTaskId IN (SELECT entityId FROM sample_records WHERE entityType='TASK') THEN NULL ELSE parentTaskId END AS parentTaskId, version " +
                "FROM task_placements WHERE taskId IN ($tasks)"
        }
        val contexts = mutableListOf<String>()
        if (ExportDataType.ACTUAL in types || ExportDataType.FOCUS in types) contexts += "(entityType='RECORD' AND entityId IN ($records))"
        if (ExportDataType.SCHEDULE in types) contexts += "(entityType='PLAN' AND entityId IN ($plans))"
        if (contexts.isNotEmpty()) queries["entry_contexts"] = "SELECT * FROM entry_contexts WHERE ${contexts.joinToString(" OR ")}"
        if (ExportDataType.SCHEDULE in types || ExportDataType.TASK in types) {
            val reminders = "SELECT id FROM reminders WHERE remindAt >= $start AND remindAt < $end " +
                "AND NOT EXISTS(SELECT 1 FROM sample_records s WHERE s.entityId=reminders.ownerId OR s.entityId=reminders.taskId)"
            queries["reminders"] = "SELECT * FROM reminders WHERE id IN ($reminders)"
            queries["reminder_instances"] = "SELECT * FROM reminder_instances WHERE reminderId IN ($reminders)"
        }
        return queries
    }

    private fun writeRawCsv(db: TimeAgentDatabase, out: OutputStream, query: String) {
        out.write(BOM)
        db.openHelper.readableDatabase.query(query).use { cursor ->
            out.writeCsvRow(cursor.columnNames.toList())
            while (cursor.moveToNext()) {
                checkActive()
                out.writeCsvRow((0 until cursor.columnCount).map { index ->
                    if (cursor.isNull(index)) "" else cursor.getString(index)
                })
            }
        }
    }

    private fun writeQueryArray(
        db: TimeAgentDatabase,
        writer: JsonWriter,
        sql: String,
        args: Array<out Any?> = emptyArray(),
    ) {
        writer.beginArray()
        db.openHelper.readableDatabase.query(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                writer.beginObject()
                for (index in 0 until cursor.columnCount) {
                    writer.name(cursor.getColumnName(index))
                    when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> writer.nullValue()
                        Cursor.FIELD_TYPE_INTEGER -> writer.value(cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> writer.value(cursor.getDouble(index))
                        Cursor.FIELD_TYPE_BLOB -> writer.nullValue()
                        else -> writer.value(cursor.getString(index))
                    }
                }
                writer.endObject()
            }
        }
        writer.endArray()
    }

    private fun writeWorkoutsJson(workoutDb: WorkoutDatabase?, writer: JsonWriter, startAt: Long, endAt: Long) {
        writer.beginArray()
        if (workoutDb == null) {
            writer.endArray()
            return
        }
        run {
            workoutDb.openHelper.readableDatabase.query(
                "SELECT id, startedAt, payload FROM workout_sessions ORDER BY startedAt",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
                    val startedAt = cursor.getLong(cursor.getColumnIndexOrThrow("startedAt"))
                    val payload = cursor.getString(cursor.getColumnIndexOrThrow("payload"))
                    val session = gson.fromJson(payload, WorkoutSession::class.java)
                    val endedAt = session.endedAt
                    if (startedAt >= endAt || (endedAt != null && endedAt <= startAt)) continue
                    writer.beginObject()
                    writer.name("id").value(id)
                    writer.name("startedAt").value(startedAt)
                    writer.name("session").jsonValue(payload)
                    writer.endObject()
                }
            }
        }
        writer.endArray()
    }

    // ---------------------------------------------------------------------------------------------
    // ICS
    // ---------------------------------------------------------------------------------------------

    private suspend fun writeIcs(file: File, startAt: Long, endAt: Long) {
        val db = TimeAgentDatabase.open(applicationContext)
        file.bufferedWriter(Charsets.UTF_8).use { output ->
        fun line(value: String) { output.write(foldIcs(value)); output.write("\r\n") }
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//Time Agent//Report Export//CN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        line("X-WR-CALNAME:时间 Agent 日程")
        val sql = "SELECT b.id, COALESCE(NULLIF(b.title,''), t.title, '日程') AS title, b.startAt, b.endAt, " +
            "b.kind, b.allDayStartDate, b.allDayEndDate, b.notes FROM schedule_blocks b " +
            "LEFT JOIN tasks t ON t.id=b.taskId WHERE b.deletedAt IS NULL AND b.status='SCHEDULED' " +
            "AND ((b.allDayStartDate IS NOT NULL AND b.allDayStartDate <= ? AND COALESCE(b.allDayEndDate,b.allDayStartDate) >= ?) " +
            "OR (b.endAt > b.startAt AND b.startAt < ? AND b.endAt > ?)) ORDER BY b.startAt"
        db.openHelper.readableDatabase.query(
            withoutSamples(sql, "PLAN"), arrayOf(localDate(endAt - 1), localDate(startAt), endAt, startAt),
        ).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                checkActive()
                count++
                if (count % 50 == 0) updateProgress(5 + min(85, count / 10), "正在写入日程")
                val uid = cursor.str("id")
                val title = cursor.str("title").ifBlank { "日程" }
                val allDayStart = cursor.str("allDayStartDate")
                val allDayEnd = cursor.str("allDayEndDate")
                line("BEGIN:VEVENT")
                line("UID:${escapeIcs(uid)}@time-agent")
                line("DTSTAMP:${utcDateTime(System.currentTimeMillis())}")
                if (allDayStart.isNotBlank()) {
                    val start = LocalDate.parse(allDayStart)
                    val end = LocalDate.parse(allDayEnd.ifBlank { allDayStart })
                    line("DTSTART;VALUE=DATE:${start.format(DateTimeFormatter.BASIC_ISO_DATE)}")
                    line("DTEND;VALUE=DATE:${end.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)}")
                } else {
                    line("DTSTART:${utcDateTime(cursor.long("startAt"))}")
                    line("DTEND:${utcDateTime(cursor.long("endAt"))}")
                }
                line("SUMMARY:${escapeIcs(title)}")
                val notes = cursor.str("notes")
                if (notes.isNotBlank()) line("DESCRIPTION:${escapeIcs(notes)}")
                line("STATUS:CONFIRMED")
                line("TRANSP:OPAQUE")
                line("END:VEVENT")
            }
        }
        line("END:VCALENDAR")
        updateProgress(92, "写入 ICS 文件")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun unavailable(db: TimeAgentDatabase, workoutDb: WorkoutDatabase?, types: Set<ExportDataType>): List<String> = buildList {
        if (ExportDataType.HABITS in types && !db.hasTable("habits")) add("习惯表尚未注册（v19 前）")
        if (ExportDataType.FOCUS in types && !db.hasTable("focus_sessions")) add("专注表尚未注册（v19 前）")
        if (ExportDataType.WORKOUT in types && workoutDb == null) add("训练数据库不可用")
    }

    private fun rangeMap(startAt: Long, endAt: Long): Map<String, Any> = mapOf(
        "start" to localDate(startAt),
        "end" to localDate(endAt - 1),
        "startAt" to startAt,
        "endAt" to endAt,
    )

    private fun localDate(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    private fun localDateTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT))

    private fun nullableDateTime(cursor: Cursor, column: String): String {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) "" else localDateTime(cursor.getLong(index))
    }

    private fun nullableLong(cursor: Cursor, column: String): String {
        val index = cursor.getColumnIndexOrThrow(column)
        return if (cursor.isNull(index)) "" else cursor.getLong(index).toString()
    }

    private fun Cursor.str(column: String): String {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) "" else getString(index)
    }

    private fun Cursor.long(column: String): Long {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) 0L else getLong(index)
    }

    private fun Cursor.int(column: String): Int {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) 0 else getInt(index)
    }

    private fun OutputStream.writeCsvRow(values: List<String>) {
        write(values.joinToString(",") { csvCell(it) }.toByteArray(Charsets.UTF_8))
        write('\n'.code)
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    private fun utcDateTime(millis: Long): String =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(millis))

    private fun escapeIcs(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")

    private fun foldIcs(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = StringBuilder()
        var current = StringBuilder()
        var bytes = 0
        var first = true
        line.forEach { character ->
            val size = character.toString().toByteArray(Charsets.UTF_8).size
            if (!first && bytes + size > 74) {
                out.append(current).append("\r\n ")
                current = StringBuilder()
                bytes = 0
            }
            current.append(character)
            bytes += size
            first = false
        }
        out.append(current)
        return out.toString()
    }

    private fun TimeAgentDatabase.hasTable(name: String): Boolean = runCatching {
        openHelper.readableDatabase.query(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(name),
        ).use { it.moveToFirst() }
    }.getOrDefault(false)

    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun close() = flush()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val KEY_URI = "uri"
        const val KEY_FORMAT = "format"
        const val KEY_TYPES = "types"
        const val KEY_START_AT = "startAt"
        const val KEY_END_AT = "endAt"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val EXTRA_WORK_ID = "workId"

        private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        private const val SCHEDULE_JSON_SQL =
            "SELECT b.id, b.taskId, COALESCE(NULLIF(b.title,''), t.title, '') AS title, b.startAt, b.endAt, " +
                "b.status, b.kind, b.allDayStartDate, b.allDayEndDate, b.timezoneId, b.notes, b.source, b.sourceRef, " +
                "b.createdAt, b.updatedAt, b.version, b.deletedAt FROM schedule_blocks b LEFT JOIN tasks t ON t.id=b.taskId " +
                "WHERE b.deletedAt IS NULL AND ((b.endAt > b.startAt AND b.startAt < ? AND b.endAt > ?) " +
                "OR (b.allDayStartDate IS NOT NULL AND b.allDayStartDate <= ? AND COALESCE(b.allDayEndDate,b.allDayStartDate) >= ?)) " +
                "ORDER BY b.startAt"

        private const val ACTUAL_JSON_SQL =
            "SELECT r.id, r.taskId, COALESCE(NULLIF(r.title,''), t.title, '') AS title, r.startAt, r.endAt, " +
                "r.categoryId, r.notes, r.source, r.isManuallyEdited, r.createdAt, r.updatedAt, r.version " +
                "FROM time_records r LEFT JOIN tasks t ON t.id=r.taskId " +
                "WHERE r.startAt < ? AND (r.endAt IS NULL OR r.endAt > ?) ORDER BY r.startAt"

        private const val TASK_JSON_SQL =
            "SELECT t.id, t.title, t.description, t.estimatedMinutes, t.dueAt, t.priority, t.status, t.categoryId, " +
                "t.activityId, p.projectId, t.createdAt, t.updatedAt, t.completedAt, t.source, t.deletedAt, t.version " +
                "FROM tasks t LEFT JOIN task_placements p ON p.taskId=t.id " +
                "WHERE (t.createdAt >= ? AND t.createdAt < ?) OR (t.dueAt >= ? AND t.dueAt < ?) " +
                "OR (t.completedAt >= ? AND t.completedAt < ?) OR (t.deletedAt >= ? AND t.deletedAt < ?) " +
                "ORDER BY t.createdAt"

        private const val PROJECT_JSON_SQL =
            "SELECT id, name, dueAt, status, createdAt, updatedAt, deletedAt, version FROM projects " +
                "WHERE (createdAt >= ? AND createdAt < ?) OR (dueAt >= ? AND dueAt < ?) " +
                "OR (deletedAt >= ? AND deletedAt < ?) ORDER BY createdAt"

        private const val RECURRING_RULE_JSON_SQL =
            "SELECT id, title, patternJson, domain, category, reference, projectId, createdAt, updatedAt, deletedAt, version " +
                "FROM recurring_rules WHERE deletedAt IS NULL OR (deletedAt >= ? AND deletedAt < ?) ORDER BY createdAt"

        private const val RECURRING_INSTANCE_JSON_SQL =
            "SELECT id, ruleId, taskId, originalDate, ordinal, scheduledAt, status, manualOverride, ruleSnapshot, " +
                "ruleVersion, taskVersion, planVersion, placementVersion, scheduleNote, version FROM recurring_instances " +
                "WHERE originalDate >= ? AND originalDate <= ? ORDER BY originalDate, ordinal"

        private const val LEDGER_JSON_SQL =
            "SELECT id, accountId, categoryId, amountMinor, direction, merchant, note, occurredAt, createdAt, " +
                "updatedAt, confirmedAt, version FROM ledger_transactions " +
                "WHERE deletedAt IS NULL AND occurredAt >= ? AND occurredAt < ? ORDER BY occurredAt"
    }
}

/** Cancel action used by the export progress notification. Add it to the manifest next to the app. */
class ExportCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra(ExportWorker.EXTRA_WORK_ID) ?: return
        runCatching { WorkManager.getInstance(context).cancelWorkById(UUID.fromString(raw)) }
    }
}
