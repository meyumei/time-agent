package com.timeagent.onboarding

import android.content.Context
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.ProjectEntity
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TaskPlacementEntity
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TimeRepository
import com.timeagent.domain.command.CommandResult
import com.timeagent.domain.command.CommandStatus
import com.timeagent.domain.command.Origin
import com.timeagent.domain.command.SampleDataMutation
import com.timeagent.domain.command.WriteSampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class SampleBatchStatus(
    val batchId: String,
    val total: Int,
    val edited: Int,
)

data class SampleDataStatus(
    val total: Int = 0,
    val tasks: Int = 0,
    val plans: Int = 0,
    val records: Int = 0,
    val projects: Int = 0,
    val edited: Int = 0,
    val batches: List<SampleBatchStatus> = emptyList(),
) {
    val loaded: Boolean get() = total > 0
}

data class SampleEntityPreview(
    val entityType: String,
    val entityId: String,
    val title: String,
    val version: Long,
    val initialVersion: Long,
)

data class SampleCleanupPreview(
    val total: Int,
    val edited: List<SampleEntityPreview>,
) {
    val hasEdited: Boolean get() = edited.isNotEmpty()
}

data class SampleMutationResult(
    val action: String,
    val batchId: String? = null,
    val created: Int = 0,
    val deleted: Int = 0,
    val kept: Int = 0,
    val message: String,
)

/**
 * Owns the onboarding sample week.
 *
 * All writes enter through [WriteSampleData] and are executed by CommandExecutor. The repository
 * only provides the body of that command; `commandWrite` wraps every row in one transaction and one
 * undo batch. Loading never updates existing rows and never touches user templates. Cleanup only
 * removes rows listed in `sample_records`; rows the user edited (version > initialVersion) are kept
 * when asked, and their markers are removed so they become ordinary user data.
 *
 * Calendar sync, public export and backup must call [calendarExportable], [exportExclusions] or
 * [backupExclusions]. Statistics other than review should pass their snapshot through
 * [statisticsSnapshot]; review intentionally keeps the full snapshot.
 */
class SampleDataRepository(
    private val repository: TimeRepository,
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
) {
    private val sampleDao = repository.database.sampleDataDao()
    private val dao = repository.database.dao()
    private val zone get() = repository.zone
    private val now get() = repository.now()

    /** Live status for settings and onboarding; entity edits also invalidate this flow. */
    fun observe(): Flow<SampleDataStatus> = repository.database.invalidationTracker
        .createFlow("sample_records", "tasks", "schedule_blocks", "time_records", "projects")
        .map { status() }
        .flowOn(Dispatchers.IO)

    suspend fun status(): SampleDataStatus {
        val markers = sampleDao.all()
        val versions = versionsFor(markers)
        val edited = markers.count { currentVersion(it, versions) > it.initialVersion }
        return SampleDataStatus(
            total = markers.size,
            tasks = markers.count { it.entityType == SampleEntityTypes.TASK },
            plans = markers.count { it.entityType == SampleEntityTypes.PLAN },
            records = markers.count { it.entityType == SampleEntityTypes.RECORD },
            projects = markers.count { it.entityType == SampleEntityTypes.PROJECT },
            edited = edited,
            batches = markers.groupBy { it.batchId }.map { (batchId, rows) ->
                SampleBatchStatus(batchId, rows.size, rows.count {
                    currentVersion(it, versions) > it.initialVersion
                })
            }.sortedBy { it.batchId },
        )
    }

    suspend fun cleanupPreview(batchId: String? = null): SampleCleanupPreview {
        val markers = markers(batchId)
        val versions = versionsFor(markers)
        val edited = markers.filter { currentVersion(it, versions) > it.initialVersion }
            .map { SampleEntityPreview(it.entityType, it.entityId, displayTitle(it), currentVersion(it, versions), it.initialVersion) }
            .sortedWith(compareBy({ it.entityType }, { it.title }))
        return SampleCleanupPreview(markers.size, edited)
    }

    suspend fun sampleIds(): SampleDataIds {
        val markers = sampleDao.all()
        fun ids(type: String) = markers.filter { it.entityType == type }.mapTo(mutableSetOf()) { it.entityId }
        return SampleDataIds(
            tasks = ids(SampleEntityTypes.TASK),
            plans = ids(SampleEntityTypes.PLAN),
            records = ids(SampleEntityTypes.RECORD),
            projects = ids(SampleEntityTypes.PROJECT),
        )
    }

    suspend fun loadWeek(
        requestId: String = "sample-load:${UUID.randomUUID()}",
        weekStart: LocalDate? = null,
    ): CommandResult = repository.commands.execute(
        WriteSampleData(
            requestId = requestId,
            mutation = SampleDataMutation.LoadWeek(weekStart?.toString()),
            origin = Origin.UI,
        ),
    )

    suspend fun cleanup(
        keepEdited: Boolean,
        batchId: String? = null,
        requestId: String = "sample-cleanup:${UUID.randomUUID()}",
    ): CommandResult = repository.commands.execute(
        WriteSampleData(
            requestId = requestId,
            mutation = SampleDataMutation.Cleanup(keepEdited, batchId),
            origin = Origin.UI,
        ),
    )

    /** CommandExecutor entry point. Runs inside its outer transaction and undo collector. */
    suspend fun undo(token: String): CommandResult = repository.commands.execute(
        com.timeagent.domain.command.UndoCommand("sample-undo:${UUID.randomUUID()}", token),
    )

    suspend fun applyMutation(mutation: SampleDataMutation): SampleMutationResult = when (mutation) {
        is SampleDataMutation.LoadWeek -> loadWeekRaw(mutation)
        is SampleDataMutation.Cleanup -> cleanupRaw(mutation)
    }

    // ---- Calendar / export / backup / statistics filters --------------------------------------

    suspend fun calendarExportable(plans: List<ScheduleBlockEntity>): List<ScheduleBlockEntity> =
        calendarExportable(plans, sampleIds())

    fun calendarExportable(plans: List<ScheduleBlockEntity>, ids: SampleDataIds): List<ScheduleBlockEntity> =
        plans.filterNot { it.id in ids.plans }

    suspend fun calendarExportableRecords(records: List<TimeRecordEntity>): List<TimeRecordEntity> {
        val ids = sampleIds().records
        return records.filterNot { it.id in ids }
    }

    suspend fun exportExclusions(): SampleExportExclusions {
        val ids = sampleIds()
        return SampleExportExclusions(
            rowIds = mapOf(
                "tasks" to ids.tasks,
                "schedule_blocks" to ids.plans,
                "time_records" to ids.records,
                "projects" to ids.projects,
                "task_placements" to ids.tasks,
            ),
        )
    }

    /** Same row-level contract as export: backup must skip sample rows and the marker table. */
    suspend fun backupExclusions(): SampleExportExclusions = exportExclusions()

    /**
     * Drops sample rows from ordinary statistics. Review/report pages may deliberately use the
     * untouched snapshot; every other counter (today totals, goals, suggestions, project progress)
     * should use this one.
     */
    suspend fun statisticsSnapshot(snapshot: LocalSnapshot): LocalSnapshot {
        val ids = sampleIds()
        return snapshot.copy(
            tasks = snapshot.tasks.filterNot { it.id in ids.tasks },
            plans = snapshot.plans.filterNot { it.id in ids.plans },
            records = snapshot.records.filterNot { it.id in ids.records },
            projects = snapshot.projects.filterNot { it.id in ids.projects },
            placements = snapshot.placements.filterNot { it.taskId in ids.tasks },
            weeklyGoals = snapshot.weeklyGoals.filterNot { it.projectId in ids.projects },
            suggestions = snapshot.suggestions.filterNot { it.taskId in ids.tasks },
        )
    }

    // ---- Load ---------------------------------------------------------------------------------

    private suspend fun loadWeekRaw(mutation: SampleDataMutation.LoadWeek): SampleMutationResult {
        require(sampleDao.count() == 0) { "已有示例数据，请先清除后再加载" }
        val today = now.atZone(zone).toLocalDate()
        val weekStart = mutation.weekStart?.let(LocalDate::parse)
            ?: today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val batchId = "sample:${UUID.randomUUID()}"
        val stamp = now.toEpochMilli()

        val project = repository.saveProject(
            ProjectEntity(
                id = "sample-project:${UUID.randomUUID()}",
                name = "示例项目 · 生活整理",
                dueAt = at(weekStart.plusDays(6), 20, 0),
                status = "ACTIVE",
            ),
        )
        mark(SampleEntityTypes.PROJECT, project.id, batchId, project.version)

        val tasks = listOf(
            sampleTask("整理桌面与文件", 45, weekStart.plusDays(0), 20, 0),
            sampleTask("阅读 30 分钟", 30, weekStart.plusDays(1), 21, 0, status = "COMPLETED"),
            sampleTask("完成示例项目", 60, weekStart.plusDays(4), 18, 0),
            sampleTask("周复盘", 30, weekStart.plusDays(6), 20, 30),
            sampleTask("给下周列三件事", 15, weekStart.plusDays(6), 21, 0),
        ).map { repository.saveTask(it) }
        tasks.forEach { mark(SampleEntityTypes.TASK, it.id, batchId, it.version) }

        repository.savePlacement(TaskPlacementEntity(tasks[2].id, project.id))
        repository.savePlacement(TaskPlacementEntity(tasks[3].id, project.id))

        val plans = mutableListOf<ScheduleBlockEntity>()
        fun addPlan(date: LocalDate, startHour: Int, startMinute: Int, minutes: Int, title: String, task: TaskEntity?) {
            val start = at(date, startHour, startMinute)
            plans += ScheduleBlockEntity(
                id = "sample-plan:${UUID.randomUUID()}",
                taskId = task?.id,
                startAt = start,
                endAt = start + minutes * 60_000L,
                title = title,
                kind = "TIMED",
                timezoneId = zone.id,
                notes = "示例日程，可随时清除",
                source = "SAMPLE",
            )
        }

        repeat(7) { offset ->
            val date = weekStart.plusDays(offset.toLong())
            val weekday = date.dayOfWeek.value <= 5
            addPlan(date, 9, 30, 60, if (weekday) "晨间专注" else "周末阅读", if (weekday) tasks[0] else tasks[1])
            addPlan(date, 19, 30, 30, "散步", null)
            if (date == weekStart) addPlan(date, 14, 0, 60, "整理文件", tasks[0])
            if (date == weekStart.plusDays(5)) addPlan(date, 10, 0, 90, "推进示例项目", tasks[2])
            if (date == weekStart.plusDays(6)) addPlan(date, 20, 0, 30, "本周复盘", tasks[3])
        }
        plans.forEach { plan ->
            repository.savePlanRaw(plan, expectedTaskVersion = plan.taskId?.let { id -> tasks.first { it.id == id }.version })
            val saved = requireNotNull(dao.plan(plan.id)) { "示例日程保存失败" }
            mark(SampleEntityTypes.PLAN, saved.id, batchId, saved.version)
        }

        var records = 0
        for (offset in 0..6) {
            val date = weekStart.plusDays(offset.toLong())
            if (date > today) continue
            val morningStart = at(date, 9, 40)
            val morningEnd = morningStart + 25 * 60_000L
            if (morningEnd <= stamp) {
                val record = TimeRecordEntity(
                    id = "sample-record:${UUID.randomUUID()}",
                    taskId = tasks[1].id,
                    startAt = morningStart,
                    endAt = morningEnd,
                    notes = "示例：实际比计划晚开始 10 分钟",
                    source = "SAMPLE",
                    title = "阅读 30 分钟",
                )
                repository.saveRecordRaw(record)
                val saved = requireNotNull(dao.record(record.id)) { "示例实际记录保存失败" }
                mark(SampleEntityTypes.RECORD, saved.id, batchId, saved.version)
                records++
            }
            val eveningStart = at(date, 19, 35)
            val eveningEnd = eveningStart + 20 * 60_000L
            if (eveningEnd <= stamp) {
                val record = TimeRecordEntity(
                    id = "sample-record:${UUID.randomUUID()}",
                    taskId = null,
                    startAt = eveningStart,
                    endAt = eveningEnd,
                    notes = "示例：散步实际记录",
                    source = "SAMPLE",
                    title = "散步",
                )
                repository.saveRecordRaw(record)
                val saved = requireNotNull(dao.record(record.id)) { "示例实际记录保存失败" }
                mark(SampleEntityTypes.RECORD, saved.id, batchId, saved.version)
                records++
            }
        }

        val created = 1 + tasks.size + plans.size + records
        return SampleMutationResult(
            action = "LOAD",
            batchId = batchId,
            created = created,
            message = "已加载示例一周：${tasks.size} 个任务、${plans.size} 个日程、$records 条实际记录、1 个项目。可随时在设置中清除。",
        )
    }

    private fun sampleTask(
        title: String,
        minutes: Int,
        dueDate: LocalDate,
        dueHour: Int,
        dueMinute: Int,
        status: String = "TODO",
    ) = TaskEntity(
        id = "sample-task:${UUID.randomUUID()}",
        title = title,
        description = "示例数据，可在设置中清除",
        estimatedMinutes = minutes,
        dueAt = at(dueDate, dueHour, dueMinute),
        status = status,
        source = "SAMPLE",
    )

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private suspend fun mark(type: String, id: String, batchId: String, version: Long) {
        val marker = SampleRecordEntity(type, id, batchId, version)
        sampleDao.put(marker)
        repository.journal("SAMPLE_MARKER", "$type:$id", null, marker, "标记示例记录")
    }

    // ---- Cleanup ------------------------------------------------------------------------------

    private data class CleanupPlan(
        val markers: List<SampleRecordEntity>,
        val kept: Set<String>,
        val deleted: List<SampleRecordEntity>,
    )

    private suspend fun cleanupRaw(mutation: SampleDataMutation.Cleanup): SampleMutationResult {
        val plan = planCleanup(mutation.keepEdited, mutation.batchId)
        if (plan.markers.isEmpty()) {
            return SampleMutationResult("CLEANUP", message = "没有需要清除的示例数据")
        }

        val sampleTaskIds = plan.markers.filter { it.entityType == SampleEntityTypes.TASK }.mapTo(mutableSetOf()) { it.entityId }
        val sampleProjectIds = plan.markers.filter { it.entityType == SampleEntityTypes.PROJECT }.mapTo(mutableSetOf()) { it.entityId }
        val deletedTaskIds = plan.deleted.filter { it.entityType == SampleEntityTypes.TASK }.mapTo(mutableSetOf()) { it.entityId }
        val deletedProjectIds = plan.deleted.filter { it.entityType == SampleEntityTypes.PROJECT }.mapTo(mutableSetOf()) { it.entityId }
        val keptKeys = plan.kept

        // A placement between two kept sample rows stays as user data. Any placement touching a
        // deleted endpoint goes first so later task/project deletes cannot hit a RESTRICT foreign key.
        dao.placements().filter { it.taskId in sampleTaskIds }.forEach { placement ->
            val taskKept = key(SampleEntityTypes.TASK, placement.taskId) in keptKeys
            val projectKept = placement.projectId == null ||
                placement.projectId !in sampleProjectIds ||
                key(SampleEntityTypes.PROJECT, placement.projectId) in keptKeys
            if (!taskKept || !projectKept) {
                val before = dao.placement(placement.taskId) ?: return@forEach
                repository.nextVersion("PLACEMENT", placement.taskId, before.version)
                dao.removePlacement(placement.taskId)
                repository.journal("PLACEMENT", placement.taskId, before, null, "清除示例任务归属")
            }
        }

        plan.deleted.filter { it.entityType == SampleEntityTypes.RECORD }.forEach { marker ->
            val before = dao.record(marker.entityId) ?: return@forEach
            repository.nextVersion("RECORD", marker.entityId, before.version)
            dao.removeRecord(marker.entityId)
            repository.journal("RECORD", marker.entityId, before, null, "清除示例实际记录")
        }
        plan.deleted.filter { it.entityType == SampleEntityTypes.PLAN }.forEach { marker ->
            val before = dao.plan(marker.entityId) ?: return@forEach
            repository.nextVersion("PLAN", marker.entityId, before.version)
            dao.removePlan(marker.entityId)
            repository.journal("PLAN", marker.entityId, before, null, "清除示例日程")
        }
        plan.deleted.filter { it.entityType == SampleEntityTypes.TASK }.forEach { marker ->
            val before = dao.task(marker.entityId) ?: return@forEach
            repository.nextVersion("TASK", marker.entityId, before.version)
            dao.removeTask(marker.entityId)
            repository.journal("TASK", marker.entityId, before, null, "清除示例任务")
        }
        plan.deleted.filter { it.entityType == SampleEntityTypes.PROJECT }.forEach { marker ->
            val before = dao.project(marker.entityId) ?: return@forEach
            repository.nextVersion("PROJECT", marker.entityId, before.version)
            dao.removeProject(marker.entityId)
            repository.journal("PROJECT", marker.entityId, before, null, "清除示例项目")
        }

        plan.markers.forEach {
            sampleDao.remove(it.entityType, it.entityId)
            repository.journal("SAMPLE_MARKER", "${it.entityType}:${it.entityId}", it, null, "解除示例标记")
        }
        val removed = plan.deleted.size
        val kept = plan.markers.size - removed
        return SampleMutationResult(
            action = "CLEANUP",
            batchId = mutation.batchId,
            deleted = removed,
            kept = kept,
            message = buildString {
                append("已清除 $removed 条示例记录")
                if (kept > 0) append("，保留 $kept 条为我的数据")
                append("。")
            },
        )
    }

    private suspend fun planCleanup(keepEdited: Boolean, batchId: String?): CleanupPlan {
        val markers = markers(batchId)
        if (markers.isEmpty()) return CleanupPlan(emptyList(), emptySet(), emptyList())
        val versions = versionsFor(markers)
        val kept = mutableSetOf<String>()
        if (keepEdited) {
            markers.filter { currentVersion(it, versions) > it.initialVersion }.forEach { kept += key(it.entityType, it.entityId) }
        }

        val reminders = dao.reminders()
        val attachments = dao.attachments()
        val notes = dao.notes()
        val placements = dao.placements()
        val recurringInstances = dao.recurringInstances()
        val recurringRules = dao.recurringRules()
        val weeklyGoals = dao.weeklyGoals()
        val healthItems = dao.healthItems()
        val quickEntries = repository.database.quickEntryDao().entries()
        val templateItems = repository.database.quickEntryDao().templates().flatMap {
            repository.database.quickEntryDao().items(it.id)
        }
        val focusSessions = repository.database.focusDao().all()
        val habits = repository.database.habitDao().habits()
        val contexts = repository.database.entryContextDao().all()
        val taskTags = repository.database.organizationDao().taskTags()
        val recordTags = repository.database.organizationDao().recordTags()
        val sampleKeys = markers.mapTo(mutableSetOf()) { key(it.entityType, it.entityId) }
        val sampleTaskIds = markers.filter { it.entityType == SampleEntityTypes.TASK }.mapTo(mutableSetOf()) { it.entityId }
        val sampleProjectIds = markers.filter { it.entityType == SampleEntityTypes.PROJECT }.mapTo(mutableSetOf()) { it.entityId }
        val userPlans = dao.plans().filter { key(SampleEntityTypes.PLAN, it.id) !in sampleKeys }
        val userRecords = dao.records().filter { key(SampleEntityTypes.RECORD, it.id) !in sampleKeys }
        val userPlacements = placements.filter { it.taskId !in sampleTaskIds }

        var changed = true
        while (changed) {
            changed = false
            markers.forEach { marker ->
                val markerKey = key(marker.entityType, marker.entityId)
                if (markerKey !in kept) return@forEach
                when (marker.entityType) {
                    SampleEntityTypes.PLAN -> {
                        val taskId = dao.plan(marker.entityId)?.taskId
                        if (taskId != null && key(SampleEntityTypes.TASK, taskId) in sampleKeys && kept.add(key(SampleEntityTypes.TASK, taskId))) {
                            changed = true
                        }
                    }
                    SampleEntityTypes.RECORD -> {
                        val taskId = dao.record(marker.entityId)?.taskId
                        if (taskId != null && key(SampleEntityTypes.TASK, taskId) in sampleKeys && kept.add(key(SampleEntityTypes.TASK, taskId))) {
                            changed = true
                        }
                    }
                }
            }
            markers.filter { key(it.entityType, it.entityId) !in kept }.forEach { marker ->
                val id = marker.entityId
                val blocked = when (marker.entityType) {
                    SampleEntityTypes.TASK ->
                        userPlans.any { it.taskId == id } ||
                            userRecords.any { it.taskId == id } ||
                            userPlacements.any { it.parentTaskId == id } ||
                            reminders.any { it.taskId == id } ||
                            attachments.any { it.taskId == id } ||
                            taskTags.any { it.taskId == id } ||
                            quickEntries.any { it.taskId == id } ||
                            focusSessions.any { it.taskId == id } ||
                            recurringInstances.any { it.taskId == id } ||
                            notes.any { it.targetType == "TASK" && it.targetId == id }
                    SampleEntityTypes.PLAN ->
                        notes.any { it.targetType == "PLAN" && it.targetId == id } ||
                            contexts.any { it.entityType == "PLAN" && it.entityId == id } ||
                            focusSessions.any { it.scheduleBlockId == id } ||
                            reminders.any { it.ownerType in setOf("PLAN", "SCHEDULE", "SCHEDULE_BLOCK") && it.ownerId == id }
                    SampleEntityTypes.RECORD ->
                        notes.any { it.targetType == "RECORD" && it.targetId == id } ||
                            healthItems.any { it.recordId == id } ||
                            recordTags.any { it.recordId == id } ||
                            contexts.any { it.entityType == "RECORD" && it.entityId == id } ||
                            reminders.any { it.ownerType == "RECORD" && it.ownerId == id }
                    SampleEntityTypes.PROJECT ->
                        userPlacements.any { it.projectId == id } ||
                            attachments.any { it.projectId == id } ||
                            recurringRules.any { it.projectId == id } ||
                            weeklyGoals.any { it.projectId == id } ||
                            quickEntries.any { it.projectId == id } ||
                            templateItems.any { it.projectId == id } ||
                            contexts.any { it.projectId == id } ||
                            habits.any { it.source == "ACTUAL_BY_PROJECT" && it.sourceRef == id } ||
                            notes.any { it.targetType == "PROJECT" && it.targetId == id }
                    else -> false
                }
                if (blocked && kept.add(key(marker.entityType, id))) changed = true
            }
        }

        val deleted = markers.filter { key(it.entityType, it.entityId) !in kept }
        return CleanupPlan(markers, kept, deleted)
    }

    // ---- Small helpers ------------------------------------------------------------------------

    private suspend fun markers(batchId: String?): List<SampleRecordEntity> =
        if (batchId == null) sampleDao.all() else sampleDao.batch(batchId)

    private suspend fun versionsFor(markers: List<SampleRecordEntity>): Map<String, Long?> {
        if (markers.isEmpty()) return emptyMap()
        val tasks = dao.tasks().associate { it.id to it.version }
        val plans = dao.plans().associate { it.id to it.version }
        val records = dao.records().associate { it.id to it.version }
        val projects = dao.projects().associate { it.id to it.version }
        return markers.associate { marker ->
            key(marker.entityType, marker.entityId) to when (marker.entityType) {
                SampleEntityTypes.TASK -> tasks[marker.entityId]
                SampleEntityTypes.PLAN -> plans[marker.entityId]
                SampleEntityTypes.RECORD -> records[marker.entityId]
                SampleEntityTypes.PROJECT -> projects[marker.entityId]
                else -> null
            }
        }
    }

    private fun currentVersion(marker: SampleRecordEntity, versions: Map<String, Long?>): Long =
        versions[key(marker.entityType, marker.entityId)] ?: -1L

    private suspend fun displayTitle(marker: SampleRecordEntity): String = when (marker.entityType) {
        SampleEntityTypes.TASK -> dao.task(marker.entityId)?.title ?: "已删除的任务"
        SampleEntityTypes.PLAN -> dao.plan(marker.entityId)?.let { plan ->
            plan.displayTitle(plan.taskId?.let { dao.task(it) })
        } ?: "已删除的日程"
        SampleEntityTypes.RECORD -> dao.record(marker.entityId)?.let { record ->
            record.title.ifBlank { record.notes }.ifBlank { "实际记录" }
        } ?: "已删除的实际记录"
        SampleEntityTypes.PROJECT -> dao.project(marker.entityId)?.name ?: "已删除的项目"
        else -> marker.entityId
    }

    private fun key(type: String, id: String) = "$type:$id"
}
