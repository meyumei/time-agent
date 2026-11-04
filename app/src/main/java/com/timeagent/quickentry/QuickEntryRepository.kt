package com.timeagent.quickentry

import android.content.Context
import com.google.gson.Gson
import com.timeagent.data.ActivityTemplateEntity
import com.timeagent.data.ProjectEntity
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.StaleChange
import com.timeagent.data.TagEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TaskPlacementEntity
import com.timeagent.data.TaskTagEntity
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TimeRepository
import com.timeagent.domain.command.CommandResult
import com.timeagent.domain.command.QuickEntryMutation.DeleteTemplate
import com.timeagent.domain.command.Origin
import com.timeagent.domain.command.QuickEntryMutation.Perform
import com.timeagent.domain.command.QuickEntryMutation
import com.timeagent.domain.command.QuickEntryMutationResult
import com.timeagent.domain.command.QuickEntrySlot
import com.timeagent.domain.command.QuickEntryMutation.Reorder
import com.timeagent.domain.command.QuickEntryMutation.Save
import com.timeagent.domain.command.QuickEntryMutation.SaveTemplate
import com.timeagent.domain.command.QuickEntryMutation.SetPinned
import com.timeagent.domain.command.QuickEntryMutation.Touch
import com.timeagent.domain.command.QuickEntryMutation.ApplyTemplate
import com.timeagent.domain.command.QuickEntryMutation.Delete
import com.timeagent.domain.command.UndoCommand
import com.timeagent.domain.command.WorkoutMutation
import com.timeagent.domain.command.WriteQuickEntry
import com.timeagent.domain.command.throwIfRejected
import com.timeagent.domain.command.FocusMutation
import com.timeagent.focus.FocusRepository
import com.timeagent.workout.data.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val QUICK_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Reference rows needed by the management screens; loaded together so one refresh is coherent. */
data class QuickEntryReferenceData(
    val activities: List<ActivityTemplateEntity> = emptyList(),
    val projects: List<ProjectEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
)

data class QuickEntryDraft(
    val entryId: String,
    val entryVersion: Long,
    val action: QuickEntryAction,
    val title: String,
    val notes: String,
    val activityId: String,
    val activityName: String,
    val categoryId: String?,
    val taskId: String?,
    val projectId: String?,
    val startAt: Long,
    val endAt: Long,
    val allowFuture: Boolean,
    val projectMissing: Boolean,
    val taskMissing: Boolean,
)

sealed interface QuickEntryLaunch {
    data class NeedsConfirmation(
        val entry: QuickEntryEntity,
        val slots: List<QuickEntrySlot>,
    ) : QuickEntryLaunch

    data class StartedTimer(val entry: QuickEntryEntity, val recordId: String) : QuickEntryLaunch

    data class StartedFocus(val entry: QuickEntryEntity, val focusSessionId: String) : QuickEntryLaunch

    data class OpenEditor(val draft: QuickEntryDraft) : QuickEntryLaunch

    data class Failed(val message: String) : QuickEntryLaunch
}

data class TemplatePreviewItem(
    val item: PlanTemplateItemEntity,
    val startAt: Long?,
    val endAt: Long?,
    val projectMissing: Boolean,
    val warnings: List<String>,
)

data class TemplatePreview(
    val template: PlanTemplateEntity,
    val startDate: LocalDate,
    val items: List<TemplatePreviewItem>,
)

data class TemplateApplyOutcome(
    val template: PlanTemplateEntity,
    val startDate: LocalDate,
    val createdScheduleIds: List<String>,
    val createdTaskIds: List<String>,
    val warnings: List<String>,
    val undoToken: String?,
)

data class TemplateSaveOutcome(
    val template: PlanTemplateEntity,
    val items: List<PlanTemplateItemEntity>,
    val undoToken: String?,
)

/**
 * Applies quick-entry/template mutations inside CommandExecutor's transaction and commandWrite
 * block. All public write methods execute [WriteQuickEntry], so requestId idempotency, version
 * checks, journal aggregation and undo all use the shared write path.
 *
 * `context` is only used to obtain the focus boot id and to construct the same workout repository
 * the training screens use when a confirmed switch must end a running workout.
 */
class QuickEntryRepository(
    private val repository: TimeRepository,
    context: Context? = null,
) {
    private val appContext = context?.applicationContext
    private val db = repository.database
    private val dao = db.quickEntryDao()
    private val timeDao = db.dao()
    private val organizationDao = db.organizationDao()
    private val gson = Gson()
    private val focusRepository by lazy { FocusRepository(repository, appContext) }

    fun observeEntries() = dao.observeEntries()

    fun observeTemplates() = dao.observeTemplates()

    suspend fun entries(): List<QuickEntryEntity> = dao.entries()

    suspend fun templates(): List<PlanTemplateEntity> = dao.templates()

    suspend fun items(templateId: String): List<PlanTemplateItemEntity> = dao.items(templateId)

    suspend fun referenceData(): QuickEntryReferenceData = QuickEntryReferenceData(
        activities = timeDao.activities(),
        projects = timeDao.projects(),
        tasks = timeDao.tasks(),
        tags = organizationDao.tags(),
    )

    // ---------------------------------------------------------------------------------------------
    // Public command API
    // ---------------------------------------------------------------------------------------------

    suspend fun saveEntry(
        item: QuickEntryEntity,
        expectedVersion: Long? = null,
        requestId: String = UUID.randomUUID().toString(),
        origin: Origin = Origin.UI,
    ): QuickEntryEntity {
        val (_, body) = execute(Save(item, expectedVersion), origin, requestId)
        return requireNotNull(body.entry) { "快捷记录结果缺失" }
    }

    suspend fun deleteEntry(
        id: String,
        expectedVersion: Long,
        requestId: String = UUID.randomUUID().toString(),
    ): String? = execute(Delete(id, expectedVersion), Origin.UI, requestId).first.undoToken

    suspend fun setPinned(
        id: String,
        expectedVersion: Long,
        pinned: Boolean,
        requestId: String = UUID.randomUUID().toString(),
    ): QuickEntryEntity {
        val (_, body) = execute(SetPinned(id, expectedVersion, pinned), Origin.UI, requestId)
        return requireNotNull(body.entry) { "快捷记录结果缺失" }
    }

    suspend fun reorder(
        orderedIds: List<String>,
        expectedVersions: Map<String, Long> = emptyMap(),
        requestId: String = UUID.randomUUID().toString(),
    ) {
        if (orderedIds.isEmpty()) return
        execute(Reorder(orderedIds, expectedVersions), Origin.UI, requestId)
    }

    suspend fun markUsed(id: String, requestId: String = UUID.randomUUID().toString(), origin: Origin = Origin.UI) {
        execute(Touch(id), origin, requestId)
    }

    suspend fun saveTemplate(
        template: PlanTemplateEntity,
        items: List<PlanTemplateItemEntity>,
        expectedVersion: Long? = null,
        requestId: String = UUID.randomUUID().toString(),
        origin: Origin = Origin.UI,
    ): TemplateSaveOutcome {
        val (command, body) = execute(SaveTemplate(template, items, expectedVersion), origin, requestId)
        return TemplateSaveOutcome(
            template = requireNotNull(body.template) { "模板结果缺失" },
            items = body.items,
            undoToken = command.undoToken,
        )
    }

    suspend fun deleteTemplate(
        id: String,
        expectedVersion: Long,
        requestId: String = UUID.randomUUID().toString(),
    ): String? = execute(DeleteTemplate(id, expectedVersion), Origin.UI, requestId).first.undoToken

    suspend fun applyTemplate(
        templateId: String,
        startDate: LocalDate,
        itemIds: List<String>,
        templateVersion: Long? = null,
        requestId: String = UUID.randomUUID().toString(),
        origin: Origin = Origin.UI,
    ): TemplateApplyOutcome {
        val (command, body) = execute(
            ApplyTemplate(templateId, startDate.toString(), itemIds, templateVersion),
            origin,
            requestId,
        )
        return TemplateApplyOutcome(
            template = requireNotNull(body.template) { "模板结果缺失" },
            startDate = startDate,
            createdScheduleIds = body.createdScheduleIds,
            createdTaskIds = body.createdTaskIds,
            warnings = body.warnings,
            undoToken = command.undoToken,
        )
    }

    suspend fun undo(undoToken: String, requestId: String = UUID.randomUUID().toString()) {
        repository.commands.execute(UndoCommand(requestId, undoToken)).throwIfRejected()
    }

    /**
     * Entry point for the today chips, notifications, voice and widgets. A running main timer,
     * workout or focus is returned as [QuickEntryLaunch.NeedsConfirmation]; calling again with the
     * exact returned slots is the user's confirmation. A changed slot is a conflict.
     */
    suspend fun activate(
        entryId: String,
        requestId: String = UUID.randomUUID().toString(),
        origin: Origin = Origin.UI,
        seenSlots: List<QuickEntrySlot> = emptyList(),
    ): QuickEntryLaunch {
        val entry = dao.entry(entryId) ?: return QuickEntryLaunch.Failed("快捷记录不存在或已删除，尚未执行")
        val action = entry.actionType ?: return QuickEntryLaunch.Failed("快捷记录的动作无效，请重新编辑")
        if (action == QuickEntryAction.CREATE_SCHEDULE || action == QuickEntryAction.CREATE_ACTUAL) {
            return QuickEntryLaunch.OpenEditor(draft(entry, action))
        }
        val slots = try {
            focusRepository.slots().map { QuickEntrySlot(it.kind, it.id, it.version, it.label) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return QuickEntryLaunch.Failed(e.message ?: "无法读取主计时状态，尚未开始")
        }
        if (normalizeSlots(slots) != normalizeSlots(seenSlots)) {
            return QuickEntryLaunch.NeedsConfirmation(entry, slots)
        }
        val body = try {
            execute(Perform(entry.id, entry.version, slots), origin, requestId).second
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return QuickEntryLaunch.Failed(e.message ?: "启动失败，尚未执行")
        }
        return when (action) {
            QuickEntryAction.START_TIMER -> body.recordId?.let { QuickEntryLaunch.StartedTimer(body.entry ?: entry, it) }
                ?: QuickEntryLaunch.Failed("未生成计时记录，请重试")
            QuickEntryAction.START_FOCUS -> body.focusSessionId?.let { QuickEntryLaunch.StartedFocus(body.entry ?: entry, it) }
                ?: QuickEntryLaunch.Failed("未生成专注会话，请重试")
            QuickEntryAction.CREATE_SCHEDULE, QuickEntryAction.CREATE_ACTUAL ->
                QuickEntryLaunch.OpenEditor(draft(body.entry ?: entry, action))
        }
    }

    suspend fun draft(entry: QuickEntryEntity, action: QuickEntryAction = entry.actionType ?: QuickEntryAction.START_TIMER): QuickEntryDraft {
        val activity = timeDao.activity(entry.activityId)
        val zone = repository.zone
        val now = repository.now()
        val minutes = (entry.defaultDurationMinutes ?: activity?.defaultMinutes ?: 30).coerceAtLeast(1)
        val (startAt, endAt, allowFuture) = when (action) {
            QuickEntryAction.CREATE_SCHEDULE -> {
                val local = now.atZone(zone).plusMinutes(15).withSecond(0).withNano(0)
                val rounded = local.plusMinutes(((15 - local.minute % 15) % 15).toLong())
                val start = rounded.toInstant()
                Triple(start.toEpochMilli(), start.plusSeconds(minutes * 60L).toEpochMilli(), true)
            }
            QuickEntryAction.CREATE_ACTUAL -> {
                val end = now
                Triple(end.minusSeconds(minutes * 60L).toEpochMilli(), end.toEpochMilli(), false)
            }
            else -> Triple(now.toEpochMilli(), now.plusSeconds(minutes * 60L).toEpochMilli(), true)
        }
        val project = entry.projectId?.let { timeDao.project(it) }
        val task = entry.taskId?.let { timeDao.task(it) }
        return QuickEntryDraft(
            entryId = entry.id,
            entryVersion = entry.version,
            action = action,
            title = displayLabel(entry, activity),
            notes = "",
            activityId = entry.activityId,
            activityName = activityName(activity),
            categoryId = activity?.categoryId ?: task?.categoryId,
            taskId = task?.takeIf { it.deletedAt == null && it.status != "CANCELLED" }?.id,
            projectId = project?.takeIf { it.deletedAt == null }?.id,
            startAt = startAt,
            endAt = endAt,
            allowFuture = allowFuture,
            projectMissing = entry.projectId != null && (project == null || project.deletedAt != null),
            taskMissing = entry.taskId != null && (task == null || task.deletedAt != null || task.status == "CANCELLED"),
        )
    }

    suspend fun previewTemplate(templateId: String, startDate: LocalDate): TemplatePreview {
        val template = requireNotNull(dao.template(templateId)) { "模板不存在或已删除" }
        val items = dao.items(templateId)
        return TemplatePreview(template, startDate, items.map { previewItem(it, startDate, repository.zone) })
    }

    suspend fun saveDayAsTemplate(
        name: String,
        date: LocalDate,
        description: String = "",
        requestId: String = UUID.randomUUID().toString(),
    ): TemplateSaveOutcome {
        val zone = repository.zone
        fun planZone(plan: ScheduleBlockEntity) = runCatching { ZoneId.of(plan.timezoneId) }.getOrDefault(zone)
        val plans = timeDao.plans()
            .filter { it.deletedAt == null && it.status == "SCHEDULED" }
            .filter { Instant.ofEpochMilli(it.startAt).atZone(planZone(it)).toLocalDate() == date }
            .sortedBy { it.startAt }
        require(plans.isNotEmpty()) { "这一天没有可保存的日程" }
        return saveSelectionAsTemplate(name, plans, description, requestId)
    }

    suspend fun saveSelectionAsTemplate(
        name: String,
        selected: List<ScheduleBlockEntity>,
        description: String = "",
        requestId: String = UUID.randomUUID().toString(),
    ): TemplateSaveOutcome = repository.transaction {
        require(selected.isNotEmpty()) { "请至少选择一个日程" }
        val plans = selected.distinctBy { it.id }.map { seen ->
            val current = requireNotNull(timeDao.plan(seen.id)) { "所选日程已删除，请重新选择" }
            if (current.version != seen.version || current.deletedAt != null) throw StaleChange()
            current
        }.sortedBy { it.startAt }
        val zone = repository.zone
        fun planZone(plan: ScheduleBlockEntity) = ZoneId.of(plan.timezoneId)
        val date = plans.minOf { Instant.ofEpochMilli(it.startAt).atZone(planZone(it)).toLocalDate() }
        val templateId = UUID.randomUUID().toString()
        val snapshots = plans.mapNotNull { plan ->
            val task = plan.taskId?.let { timeDao.task(it) }
            if (plan.taskId != null && (task == null || task.deletedAt != null)) return@mapNotNull null
            val context = repository.database.entryContextDao().find("PLAN", plan.id)
            val projectId = context?.projectId ?: task?.id?.let { timeDao.placement(it)?.projectId }
            val tagIds = context?.tagIds ?: task?.id?.let { organizationDao.taskTags().filter { link -> link.taskId == it }.joinToString(",") { link -> link.tagId } }.orEmpty()
            PlanTemplateItemEntity(
                id = UUID.randomUUID().toString(),
                templateId = templateId,
                kind = TEMPLATE_KIND_SCHEDULE,
                title = plan.displayTitle(task),
                dayOffset = java.time.temporal.ChronoUnit.DAYS.between(date,
                    Instant.ofEpochMilli(plan.startAt).atZone(planZone(plan)).toLocalDate()).toInt(),
                startTime = if (plan.kind == TEMPLATE_SCHEDULE_ALL_DAY) null
                else Instant.ofEpochMilli(plan.startAt).atZone(planZone(plan)).format(QUICK_TIME_FORMAT),
                durationMinutes = when (plan.kind) {
                    TEMPLATE_SCHEDULE_POINT -> null
                    TEMPLATE_SCHEDULE_ALL_DAY -> Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.parse(requireNotNull(plan.allDayStartDate)),
                        LocalDate.parse(requireNotNull(plan.allDayEndDate))) .plus(1L) * 1440L)
                    else -> Math.toIntExact((plan.endAt - plan.startAt) / 60_000L).coerceAtLeast(1)
                },
                scheduleKind = plan.kind,
                projectId = projectId,
                tagIds = tagIds,
                notes = plan.notes,
                reminderOffsets = timeDao.reminders().filter { it.ownerId == plan.id && it.enabled && it.remindAt <= plan.startAt }
                    .joinToString(",") { ((plan.startAt - it.remindAt) / 60_000L).toString() },
            )
        }
        require(snapshots.isNotEmpty()) { "这一天的日程关联任务都已删除，无法保存模板" }
        saveTemplate(
            PlanTemplateEntity(
                id = templateId,
                name = name.trim(),
                description = description.trim(),
            ),
            snapshots,
            expectedVersion = null,
            requestId = requestId,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // CommandExecutor callback
    // ---------------------------------------------------------------------------------------------

    internal suspend fun applyMutation(mutation: QuickEntryMutation, requestId: String): QuickEntryMutationResult =
        when (mutation) {
            is Save -> saveEntryRaw(mutation.item, mutation.expectedVersion)
            is Delete -> deleteEntryRaw(mutation.id, mutation.expectedVersion)
            is SetPinned -> setPinnedRaw(mutation.id, mutation.expectedVersion, mutation.pinned)
            is Reorder -> reorderRaw(mutation.orderedIds, mutation.expectedVersions)
            is Touch -> touchRaw(mutation.id)
            is SaveTemplate -> saveTemplateRaw(mutation.template, mutation.items, mutation.expectedVersion)
            is DeleteTemplate -> deleteTemplateRaw(mutation.id, mutation.expectedVersion)
            is ApplyTemplate -> applyTemplateRaw(mutation, requestId)
            is Perform -> performRaw(mutation, requestId)
            is QuickEntryMutation.CommitDraft -> commitDraftRaw(mutation)
        }

    // ---------------------------------------------------------------------------------------------
    // Quick entry writes
    // ---------------------------------------------------------------------------------------------

    private suspend fun saveEntryRaw(item: QuickEntryEntity, expectedVersion: Long?): QuickEntryMutationResult {
        require(item.id.isNotBlank()) { "快捷记录标识不能为空" }
        val before = dao.entry(item.id)
        if (before == null) {
            if (expectedVersion != null || timeDao.revision("QUICK_ENTRY:${item.id}") != null) throw StaleChange()
        } else {
            if (expectedVersion == null || before.version != expectedVersion) throw StaleChange()
        }
        require(item.action in QUICK_ENTRY_ACTIONS) { "快捷记录动作无效" }
        require(item.defaultDurationMinutes == null || item.defaultDurationMinutes > 0) { "默认时长须大于零" }
        val activity = requireNotNull(timeDao.activity(item.activityId)) { "请选择一个存在的活动" }
        if ((before == null || before.activityId != item.activityId) && activity.archivedAt != null) {
            throw IllegalArgumentException("活动已归档，请选择其他活动")
        }
        if (item.projectId != null) {
            val project = timeDao.project(item.projectId)
            val keepingDeleted = before?.projectId == item.projectId
            require(project != null && (project.deletedAt == null || keepingDeleted)) { "项目不存在或已删除" }
        }
        if (item.taskId != null) {
            val task = timeDao.task(item.taskId)
            val keepingDeleted = before?.taskId == item.taskId
            require(task != null && (task.deletedAt == null || keepingDeleted) && (task.status != "CANCELLED" || keepingDeleted)) {
                "任务不存在或已删除"
            }
        }
        val knownTags = organizationDao.tags().map { it.id }.toSet()
        val stamp = repository.now().toEpochMilli()
        val sortOrder = if (before == null && item.sortOrder <= 0) {
            (dao.entries().maxOfOrNull { it.sortOrder } ?: -1) + 1
        } else item.sortOrder
        val saved = item.copy(
            label = item.label?.trim()?.takeIf { it.isNotEmpty() },
            icon = item.icon.trim().take(16),
            tagIds = item.tagIdList.filter { it in knownTags }.joinToString(","),
            sortOrder = sortOrder,
            createdAt = before?.createdAt ?: stamp,
            updatedAt = stamp,
            version = repository.nextVersion("QUICK_ENTRY", item.id, before?.version),
        )
        dao.putEntry(saved)
        repository.journal("QUICK_ENTRY", item.id, before, saved, if (before == null) "新建快捷记录" else "编辑快捷记录")
        return QuickEntryMutationResult(entry = saved)
    }

    private suspend fun deleteEntryRaw(id: String, expectedVersion: Long): QuickEntryMutationResult {
        val before = requireNotNull(dao.entry(id)) { "快捷记录不存在或已删除" }
        if (before.version != expectedVersion) throw StaleChange()
        repository.nextVersion("QUICK_ENTRY", id, before.version)
        dao.removeEntry(id)
        repository.journal("QUICK_ENTRY", id, before, null, "删除快捷记录")
        return QuickEntryMutationResult(deletedId = id)
    }

    private suspend fun setPinnedRaw(id: String, expectedVersion: Long, pinned: Boolean): QuickEntryMutationResult {
        val before = requireNotNull(dao.entry(id)) { "快捷记录不存在或已删除" }
        if (before.version != expectedVersion) throw StaleChange()
        if (before.pinned == pinned) return QuickEntryMutationResult(entry = before)
        val saved = before.copy(pinned = pinned, updatedAt = repository.now().toEpochMilli(),
            version = repository.nextVersion("QUICK_ENTRY", id, before.version))
        dao.putEntry(saved)
        repository.journal("QUICK_ENTRY", id, before, saved, if (pinned) "固定快捷记录" else "取消固定快捷记录")
        return QuickEntryMutationResult(entry = saved)
    }

    private suspend fun reorderRaw(orderedIds: List<String>, expectedVersions: Map<String, Long>): QuickEntryMutationResult {
        val current = dao.entries()
        val byId = current.associateBy { it.id }
        require(orderedIds.distinct().size == orderedIds.size) { "快捷记录排序包含重复项" }
        require(orderedIds.toSet() == byId.keys) { "快捷记录列表已经变化，请重新排序" }
        val stamp = repository.now().toEpochMilli()
        orderedIds.forEachIndexed { index, id ->
            val before = requireNotNull(byId[id])
            expectedVersions[id]?.let { if (before.version != it) throw StaleChange() }
            if (before.sortOrder == index) return@forEachIndexed
            val after = before.copy(sortOrder = index, updatedAt = stamp,
                version = repository.nextVersion("QUICK_ENTRY", id, before.version))
            dao.putEntry(after)
            repository.journal("QUICK_ENTRY", id, before, after, "调整快捷记录排序")
        }
        return QuickEntryMutationResult()
    }

    private suspend fun touchRaw(id: String): QuickEntryMutationResult {
        val before = dao.entry(id) ?: return QuickEntryMutationResult(deletedId = id)
        val stamp = repository.now().toEpochMilli()
        val saved = before.copy(
            usageCount = if (before.usageCount == Int.MAX_VALUE) Int.MAX_VALUE else before.usageCount + 1,
            lastUsedAt = stamp,
            updatedAt = stamp,
            version = before.version,
        )
        dao.putEntry(saved)
        // Usage is metadata, not a content edit: no journal entry and no undo token.
        return QuickEntryMutationResult(entry = saved)
    }

    private suspend fun performRaw(mutation: Perform, requestId: String): QuickEntryMutationResult {
        val entry = requireNotNull(dao.entry(mutation.entryId)) { "快捷记录不存在或已删除" }
        if (entry.version != mutation.expectedVersion) throw StaleChange()
        val action = entry.actionType ?: throw IllegalArgumentException("快捷记录的动作无效")
        require(action == QuickEntryAction.START_TIMER || action == QuickEntryAction.START_FOCUS) {
            "创建日程或实际记录需要先在编辑预览中确认"
        }
        val slots = focusRepository.slots().map { QuickEntrySlot(it.kind, it.id, it.version, it.label) }
        if (normalizeSlots(slots) != normalizeSlots(mutation.seenSlots)) throw StaleChange()
        switchSlots(slots)

        val activity = timeDao.activity(entry.activityId)
        val label = displayLabel(entry, activity)
        val warnings = mutableListOf<String>()
        if (entry.projectId != null) {
            val project = timeDao.project(entry.projectId)
            if (project == null || project.deletedAt != null) warnings += "项目已删除；本次启动不关联项目"
        }
        if (entry.taskId != null) {
            val task = timeDao.task(entry.taskId)
            if (task == null || task.deletedAt != null || task.status == "CANCELLED") {
                warnings += "关联任务已删除；本次启动不关联任务"
            }
        }
        val taskId = entry.taskId?.takeIf {
            timeDao.task(it)?.let { task -> task.deletedAt == null && task.status != "CANCELLED" } == true
        }
        val result = when (action) {
            QuickEntryAction.START_TIMER -> {
                val recordId = "quick-timer:$requestId"
                if (timeDao.record(recordId) != null) throw StaleChange()
                val stamp = repository.now().toEpochMilli()
                val record = TimeRecordEntity(
                    id = recordId,
                    taskId = taskId,
                    startAt = stamp,
                    endAt = null,
                    categoryId = activity?.categoryId ?: taskId?.let { timeDao.task(it)?.categoryId },
                    source = "QUICK_ENTRY",
                    createdAt = stamp,
                    updatedAt = stamp,
                    version = repository.nextVersion("RECORD", recordId),
                    title = label,
                )
                timeDao.putRecord(record)
                repository.journal("RECORD", recordId, null, record, "快捷开始计时")
                saveContext("RECORD", recordId, entry)
                saveRecordTags(recordId, entry.tagIdList)
                QuickEntryMutationResult(recordId = recordId, warnings = warnings)
            }
            QuickEntryAction.START_FOCUS -> {
                val session = focusRepository.applyMutation(
                    FocusMutation.Start(
                        id = "quick-focus:$requestId",
                        label = label,
                        focusMinutes = (entry.defaultDurationMinutes ?: 25).coerceAtLeast(1),
                        taskId = taskId,
                    ),
                )
                saveContext("RECORD", "focus:${session.id}", entry)
                QuickEntryMutationResult(focusSessionId = session.id, warnings = warnings)
            }
            else -> error("不支持的动作")
        }
        val touched = touchEntryMetadata(entry)
        return result.copy(entry = touched)
    }

    private suspend fun commitDraftRaw(mutation: QuickEntryMutation.CommitDraft): QuickEntryMutationResult {
        val entry = requireNotNull(dao.entry(mutation.entryId)) { "快捷记录已删除，请重新选择预设" }
        if (entry.version != mutation.expectedVersion) throw StaleChange()
        require((mutation.plan == null) != (mutation.record == null)) { "请选择一种记录类型" }
        mutation.plan?.let { plan ->
            require(timeDao.plan(plan.id) == null) { "此草稿已经保存" }
            repository.savePlanRaw(plan.copy(source = "TEMPLATE", sourceRef = entry.id))
            saveContext("PLAN", plan.id, entry)
        }
        mutation.record?.let { record ->
            require(timeDao.record(record.id) == null) { "此草稿已经保存" }
            repository.saveRecordRaw(record.copy(source = "QUICK_ENTRY"), mutation.allowFuture)
            saveContext("RECORD", record.id, entry)
            saveRecordTags(record.id, entry.tagIdList)
        }
        return QuickEntryMutationResult(entry = touchEntryMetadata(entry),
            recordId = mutation.record?.id, createdScheduleIds = listOfNotNull(mutation.plan?.id))
    }

    private suspend fun saveContext(type: String, id: String, entry: QuickEntryEntity) {
        val projectId = entry.projectId?.takeIf { timeDao.project(it)?.let { project -> project.deletedAt == null } == true }
        val saved = EntryContextEntity(type, id, entry.activityId, projectId, entry.tagIds, entry.id)
        val contexts = repository.database.entryContextDao()
        val before = contexts.find(type, id)
        contexts.put(saved)
        repository.journal("ENTRY_CONTEXT", "$type:$id", before, saved, "保存快捷记录归属")
    }

    private suspend fun saveRecordTags(recordId: String, ids: List<String>) {
        val valid = ids.filter { organizationDao.tag(it) != null }.distinct().sorted()
        if (valid.isEmpty()) return
        valid.forEach { organizationDao.addRecordTag(com.timeagent.data.RecordTagEntity(recordId, it)) }
        repository.journal("ORG_RECORD_TAGS", recordId, com.timeagent.organization.OrgTagLinks(),
            com.timeagent.organization.OrgTagLinks(valid), "设置快捷记录标签")
    }

    private suspend fun saveTaskTags(taskId: String, ids: List<String>) {
        val valid = ids.filter { organizationDao.tag(it) != null }.distinct().sorted()
        if (valid.isEmpty()) return
        valid.forEach { organizationDao.addTaskTag(TaskTagEntity(taskId, it)) }
        repository.journal("ORG_TASK_TAGS", taskId, com.timeagent.organization.OrgTagLinks(),
            com.timeagent.organization.OrgTagLinks(valid), "设置模板标签")
    }

    /**
     * Ends exactly the slots the user confirmed. The version of each row is checked again before
     * its write; nothing is stopped merely because a slot happened to exist.
     */
    private suspend fun switchSlots(slots: List<QuickEntrySlot>) {
        slots.forEach { slot ->
            when (slot.kind) {
                "TIMER" -> {
                    val record = timeDao.record(slot.id) ?: throw StaleChange()
                    if (record.endAt != null || record.version != slot.version) throw StaleChange()
                    repository.stopTimerRaw(slot.id, slot.version)
                }
                "WORKOUT" -> {
                    val workoutDb = repository.workoutDatabase ?: throw StaleChange()
                    val row = workoutDb.dao().activeSession() ?: throw StaleChange()
                    if (row.id != slot.id) throw StaleChange()
                    WorkoutRepository(workoutDb, repository, repository.commandClock)
                        .applyMutation(WorkoutMutation.Finish(slot.id, expectedUpdatedAt = slot.version))
                }
                "FOCUS" -> focusRepository.applyMutation(
                    FocusMutation.Finish(slot.id, slot.version, save = true),
                )
            }
        }
        if (timeDao.runningTimer() != null) throw StaleChange()
        if (repository.workoutDatabase?.dao()?.activeSession() != null) throw StaleChange()
        if (focusRepository.active() != null) throw StaleChange()
    }

    private suspend fun touchEntryMetadata(entry: QuickEntryEntity): QuickEntryEntity {
        val before = dao.entry(entry.id) ?: return entry
        val stamp = repository.now().toEpochMilli()
        val saved = before.copy(
            usageCount = if (before.usageCount == Int.MAX_VALUE) Int.MAX_VALUE else before.usageCount + 1,
            lastUsedAt = stamp,
            updatedAt = stamp,
            version = before.version,
        )
        dao.putEntry(saved)
        return saved
    }

    // ---------------------------------------------------------------------------------------------
    // Template writes
    // ---------------------------------------------------------------------------------------------

    private suspend fun saveTemplateRaw(
        template: PlanTemplateEntity,
        items: List<PlanTemplateItemEntity>,
        expectedVersion: Long?,
    ): QuickEntryMutationResult {
        require(template.id.isNotBlank() && template.name.trim().length in 1..100) { "模板名称须为 1 至 100 字" }
        require(template.description.length <= 1000) { "模板说明最多 1000 字" }
        require(items.size <= 200) { "模板最多包含 200 条" }
        require(items.map { it.id }.distinct().size == items.size) { "模板条目编号重复" }
        val before = dao.template(template.id)
        if (before == null) {
            if (expectedVersion != null || timeDao.revision("PLAN_TEMPLATE:${template.id}") != null) throw StaleChange()
        } else {
            if (expectedVersion == null || before.version != expectedVersion) throw StaleChange()
        }
        val normalized = items.map { validateTemplateItem(it, template.id) }
        val stamp = repository.now().toEpochMilli()
        val saved = template.copy(
            name = template.name.trim(),
            description = template.description.trim(),
            createdAt = before?.createdAt ?: stamp,
            updatedAt = stamp,
            version = repository.nextVersion("PLAN_TEMPLATE", template.id, before?.version),
        )
        dao.putTemplate(saved)
        repository.journal("PLAN_TEMPLATE", template.id, before, saved,
            if (before == null) "新建计划模板" else "编辑计划模板")

        val old = dao.items(template.id)
        val keptIds = normalized.map { it.id }.toSet()
        old.filter { it.id !in keptIds }.forEach { removed ->
            repository.nextVersion("PLAN_TEMPLATE_ITEM", removed.id)
            dao.removeItem(removed.id)
            repository.journal("PLAN_TEMPLATE_ITEM", removed.id, removed, null, "删除模板条目")
        }
        normalized.forEach { item ->
            val previous = old.firstOrNull { it.id == item.id }
            repository.nextVersion("PLAN_TEMPLATE_ITEM", item.id)
            dao.putItem(item)
            if (previous != item) {
                repository.journal("PLAN_TEMPLATE_ITEM", item.id, previous, item,
                    if (previous == null) "添加模板条目" else "编辑模板条目")
            }
        }
        return QuickEntryMutationResult(template = saved, items = dao.items(template.id))
    }

    private suspend fun deleteTemplateRaw(id: String, expectedVersion: Long): QuickEntryMutationResult {
        val before = requireNotNull(dao.template(id)) { "模板不存在或已删除" }
        if (before.version != expectedVersion) throw StaleChange()
        val items = dao.items(id)
        items.forEach {
            repository.nextVersion("PLAN_TEMPLATE_ITEM", it.id)
            dao.removeItem(it.id)
            repository.journal("PLAN_TEMPLATE_ITEM", it.id, it, null, "删除模板条目")
        }
        repository.nextVersion("PLAN_TEMPLATE", id, before.version)
        dao.removeTemplate(id)
        repository.journal("PLAN_TEMPLATE", id, before, null, "删除计划模板")
        return QuickEntryMutationResult(deletedId = id, template = before, items = items)
    }

    private suspend fun applyTemplateRaw(mutation: ApplyTemplate, requestId: String): QuickEntryMutationResult {
        val template = requireNotNull(dao.template(mutation.templateId)) { "模板不存在或已删除" }
        mutation.templateVersion?.let { if (template.version != it) throw StaleChange() }
        val allItems = dao.items(template.id)
        require(mutation.itemIds.isNotEmpty()) { "请至少选择一条要应用的内容" }
        require(mutation.itemIds.distinct().size == mutation.itemIds.size) { "应用列表包含重复条目" }
        val selected = mutation.itemIds.map { id ->
            requireNotNull(allItems.firstOrNull { it.id == id }) { "模板条目已经变化，请重新预览" }
        }
        val startDate = LocalDate.parse(mutation.startDate)
        val zone = repository.zone
        val createdSchedules = mutableListOf<String>()
        val createdTasks = mutableListOf<String>()
        val warnings = linkedSetOf<String>()
        val knownTags = organizationDao.tags().map { it.id }.toSet()

        selected.forEach { item ->
            when (item.kind) {
                TEMPLATE_KIND_SCHEDULE -> {
                    val layout = scheduleLayout(item, startDate, zone)
                    val planId = "template-plan:$requestId:${item.id}"
                    val projectId = item.projectId?.let { id -> timeDao.project(id)?.takeIf { it.deletedAt == null }?.id }
                    val taskId = if (projectId != null || item.tagIdList.isNotEmpty()) {
                        val id = "template-owner:$requestId:${item.id}"
                        repository.saveTask(TaskEntity(id, item.title.trim(), description = item.notes,
                            estimatedMinutes = item.durationMinutes ?: 30, source = "TEMPLATE"))
                        if (projectId != null) repository.savePlacement(TaskPlacementEntity(id, projectId))
                        saveTaskTags(id, item.tagIdList.filter { it in knownTags })
                        createdTasks += id
                        id
                    } else null
                    val plan = ScheduleBlockEntity(
                        id = planId,
                        taskId = taskId,
                        startAt = layout.startAt,
                        endAt = layout.endAt,
                        title = item.title.trim(),
                        kind = item.scheduleKind,
                        allDayStartDate = layout.allDayStart,
                        allDayEndDate = layout.allDayEnd,
                        timezoneId = zone.id,
                        notes = item.notes,
                        source = "TEMPLATE",
                        sourceRef = template.id,
                    )
                    repository.savePlanRaw(plan)
                    createdSchedules += planId
                    createTemplateReminders(item, planId, layout.startAt, requestId)
                    if (item.projectId != null && projectId == null) warnings += "日程“${item.title}”的项目已删除"
                }
                TEMPLATE_KIND_TASK -> {
                    val taskId = "template-task:$requestId:${item.id}"
                    val task = TaskEntity(
                        id = taskId,
                        title = item.title.trim(),
                        description = item.notes,
                        estimatedMinutes = (item.durationMinutes ?: 30).coerceAtLeast(1),
                        source = "TEMPLATE",
                    )
                    repository.saveTask(task)
                    createdTasks += taskId
                    val projectId = item.projectId?.takeIf { timeDao.project(it)?.deletedAt == null }
                    if (projectId != null) {
                        repository.savePlacement(TaskPlacementEntity(taskId, projectId))
                    } else if (item.projectId != null) {
                        warnings += "任务“${item.title}”的项目已删除，已创建为无项目任务"
                    }
                    saveTaskTags(taskId, item.tagIdList.filter { it in knownTags })
                    if (item.startTime != null) {
                        val layout = scheduleLayout(item, startDate, zone)
                        val planId = "template-plan:$requestId:${item.id}"
                        repository.savePlanRaw(ScheduleBlockEntity(planId, taskId, layout.startAt, layout.endAt,
                            title = item.title, notes = item.notes, source = "TEMPLATE", sourceRef = template.id))
                        createdSchedules += planId
                        createTemplateReminders(item, planId, layout.startAt, requestId)
                    } else if (item.reminderOffsetList.isNotEmpty()) {
                        val anchor = startDate.plusDays(item.dayOffset.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
                        createTemplateReminders(item, taskId, anchor, requestId, "TASK")
                    }
                }
                else -> throw IllegalArgumentException("模板条目类型无效")
            }
        }
        return QuickEntryMutationResult(
            template = template,
            items = allItems,
            createdScheduleIds = createdSchedules,
            createdTaskIds = createdTasks,
            warnings = warnings.toList(),
        )
    }

    private suspend fun createTemplateReminders(
        item: PlanTemplateItemEntity, ownerId: String, anchor: Long, requestId: String, ownerType: String = "PLAN",
    ) {
        item.reminderOffsetList.forEach { offset ->
            val reminder = com.timeagent.data.ReminderEntity(
                id = "template-reminder:$requestId:${item.id}:$offset",
                title = item.title, remindAt = anchor - offset.toLong() * 60_000L,
                ownerType = ownerType, ownerId = ownerId,
                taskId = ownerId.takeIf { ownerType == "TASK" },
            )
            com.timeagent.reminders.ReminderInstanceRepository(repository).applyMutation(
                com.timeagent.domain.command.ReminderMutation.Save(reminder, null),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private suspend fun execute(
        mutation: QuickEntryMutation,
        origin: Origin,
        requestId: String = UUID.randomUUID().toString(),
    ): Pair<CommandResult, QuickEntryMutationResult> {
        val result = repository.commands.execute(WriteQuickEntry(requestId, mutation, origin))
        result.throwIfRejected()
        val payload = requireNotNull(result.payload) { "快捷记录结果缺失" }
        return result to gson.fromJson(payload, QuickEntryMutationResult::class.java)
    }

    private fun normalizeSlots(slots: List<QuickEntrySlot>): List<String> =
        slots.map { "${it.kind}\u0000${it.id}\u0000${it.version}" }.sorted()

    private suspend fun displayLabel(entry: QuickEntryEntity, activity: ActivityTemplateEntity?): String =
        entry.label?.takeIf { it.isNotBlank() }
            ?: activity?.let { name -> activityName(name) }
            ?: "快捷记录"

    private suspend fun activityName(activity: ActivityTemplateEntity?): String {
        if (activity == null) return ""
        val parent = activity.parentId?.let { timeDao.activity(it) }
        return listOfNotNull(parent?.name?.takeIf { it.isNotBlank() }, activity.name)
            .joinToString(" / ")
    }

    private fun validateTemplateItem(item: PlanTemplateItemEntity, templateId: String): PlanTemplateItemEntity {
        require(item.id.isNotBlank() && item.templateId == templateId) { "模板条目编号无效" }
        require(item.kind in TEMPLATE_ITEM_KINDS) { "模板条目类型无效" }
        require(item.title.trim().length in 1..200) { "模板条目标题须为 1 至 200 字" }
        require(item.notes.length <= 2_000) { "模板条目备注最多 2000 字" }
        require(item.dayOffset >= 0) { "模板条目的天数偏移不能为负数" }
        require(item.scheduleKind in TEMPLATE_SCHEDULE_KINDS) { "日程类型无效" }
        require(item.durationMinutes == null || item.durationMinutes > 0) { "时长须大于零" }
        if (item.kind == TEMPLATE_KIND_SCHEDULE && item.scheduleKind == TEMPLATE_SCHEDULE_ALL_DAY && item.durationMinutes != null) {
            require(item.durationMinutes % 1440 == 0) { "全天日程跨度须为整天" }
        }
        val start = item.startTime?.takeIf { it.isNotBlank() }
        val normalizedStart = start?.let {
            runCatching { LocalTime.parse(it).format(QUICK_TIME_FORMAT) }
                .getOrElse { throw IllegalArgumentException("开始时间须为 HH:mm") }
        }
        if (item.kind == TEMPLATE_KIND_SCHEDULE && item.scheduleKind != TEMPLATE_SCHEDULE_ALL_DAY) {
            require(normalizedStart != null) { "日程模板条目需要开始时间" }
        }
        require(item.reminderOffsets.isBlank() || item.reminderOffsets.split(',').all {
            it.trim().toIntOrNull()?.let { minutes -> minutes >= 0 } == true
        }) { "提前提醒须为非负分钟数，用逗号分隔" }
        return item.copy(
            title = item.title.trim(),
            startTime = normalizedStart,
            notes = item.notes.trim(),
            tagIds = item.tagIdList.joinToString(","),
            reminderOffsets = item.reminderOffsetList.joinToString(","),
        )
    }

    private suspend fun previewItem(item: PlanTemplateItemEntity, startDate: LocalDate, zone: ZoneId): TemplatePreviewItem {
        val warnings = mutableListOf<String>()
        val project = item.projectId?.let { timeDao.project(it) }
        val projectMissing = item.projectId != null && (project == null || project.deletedAt != null)
        if (projectMissing) warnings += "项目已删除，应用时将忽略项目"
        val startAt: Long?
        val endAt: Long?
        if (item.kind == TEMPLATE_KIND_SCHEDULE) {
            when (item.scheduleKind) {
                TEMPLATE_SCHEDULE_ALL_DAY -> {
                    val date = startDate.plusDays(item.dayOffset.toLong())
                    startAt = date.atStartOfDay(zone).toInstant().toEpochMilli()
                    endAt = date.plusDays(allDaySpan(item)).atStartOfDay(zone).toInstant().toEpochMilli()
                }
                TEMPLATE_SCHEDULE_POINT, TEMPLATE_SCHEDULE_TIMED -> {
                    val time = item.startTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    if (time == null) {
                        startAt = null
                        endAt = null
                        warnings += "开始时间格式无效"
                    } else {
                        val start = startDate.plusDays(item.dayOffset.toLong()).atTime(time).atZone(zone).toInstant()
                        startAt = start.toEpochMilli()
                        endAt = if (item.scheduleKind == TEMPLATE_SCHEDULE_POINT) startAt
                        else start.plusSeconds((item.durationMinutes ?: 30).toLong() * 60L).toEpochMilli()
                    }
                }
                else -> {
                    startAt = null
                    endAt = null
                    warnings += "日程类型无效"
                }
            }
        } else {
            startAt = null
            endAt = null
            if (item.startTime != null) {
                val layout = scheduleLayout(item, startDate, zone)
                return TemplatePreviewItem(item, layout.startAt, layout.endAt, projectMissing, warnings)
            }
        }
        return TemplatePreviewItem(item, startAt, endAt, projectMissing, warnings)
    }

    /** Reads through the shared DAO without opening a transaction; preview is a read-only path. */
    private data class ScheduleLayout(val startAt: Long, val endAt: Long, val allDayStart: String?, val allDayEnd: String?)

    private fun allDaySpan(item: PlanTemplateItemEntity): Long = item.durationMinutes?.let { it.toLong() / 1440L } ?: 1L

    private fun scheduleLayout(item: PlanTemplateItemEntity, startDate: LocalDate, zone: ZoneId): ScheduleLayout {
        val date = startDate.plusDays(item.dayOffset.toLong())
        return when (item.scheduleKind) {
            TEMPLATE_SCHEDULE_ALL_DAY -> ScheduleLayout(
                startAt = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                endAt = date.plusDays(allDaySpan(item)).atStartOfDay(zone).toInstant().toEpochMilli(),
                allDayStart = date.toString(),
                allDayEnd = date.plusDays(allDaySpan(item) - 1).toString(),
            )
            TEMPLATE_SCHEDULE_POINT, TEMPLATE_SCHEDULE_TIMED -> {
                val time = item.startTime?.let { LocalTime.parse(it) }
                    ?: throw IllegalArgumentException("日程“${item.title}”缺少开始时间")
                val start = date.atTime(time).atZone(zone).toInstant()
                val end = if (item.scheduleKind == TEMPLATE_SCHEDULE_POINT) start
                else start.plusSeconds((item.durationMinutes ?: 30).toLong() * 60L)
                ScheduleLayout(start.toEpochMilli(), end.toEpochMilli(), null, null)
            }
            else -> throw IllegalArgumentException("日程类型无效")
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Screen state
// -------------------------------------------------------------------------------------------------

data class QuickEntryUiState(
    val entries: List<QuickEntryEntity> = emptyList(),
    val templates: List<PlanTemplateEntity> = emptyList(),
    val templateItems: Map<String, List<PlanTemplateItemEntity>> = emptyMap(),
    val activities: List<ActivityTemplateEntity> = emptyList(),
    val projects: List<ProjectEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    fun activityLabel(id: String): String {
        val activity = activities.firstOrNull { it.id == id } ?: return "活动已删除"
        val parent = activity.parentId?.let { parentId -> activities.firstOrNull { it.id == parentId } }
        val name = listOfNotNull(parent?.name?.takeIf { it.isNotBlank() }, activity.name).joinToString(" / ")
        return if (activity.archivedAt != null) "$name（已归档）" else name
    }

    fun projectLabel(id: String?): String? = when {
        id == null -> null
        else -> projects.firstOrNull { it.id == id }?.takeIf { it.deletedAt == null }?.name ?: "项目已删除"
    }

    fun taskLabel(id: String?): String? = when {
        id == null -> null
        else -> tasks.firstOrNull { it.id == id }?.takeIf { it.deletedAt == null && it.status != "CANCELLED" }?.title
            ?: "任务已删除"
    }
}

/**
 * State holder usable without Hilt. The main app can construct it with a [TimeRepository] and a
 * Context; the required `QuickEntryScreen(model, onClose)` / `PlanTemplateScreen(model, onClose)`
 * signatures stay stable.
 */
class QuickEntryModel(
    val quickEntries: QuickEntryRepository,
    private val timeRepository: TimeRepository,
) {
    constructor(timeRepository: TimeRepository, context: Context) :
        this(QuickEntryRepository(timeRepository, context.applicationContext), timeRepository)

    private val mutableState = MutableStateFlow(QuickEntryUiState())
    val state: StateFlow<QuickEntryUiState> = mutableState

    val zone: ZoneId get() = timeRepository.zone

    suspend fun refresh() {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val references = quickEntries.referenceData()
            val entries = quickEntries.entries()
            val templates = quickEntries.templates()
            val items = templates.associate { it.id to quickEntries.items(it.id) }
            mutableState.update {
                it.copy(
                    entries = entries,
                    templates = templates,
                    templateItems = items,
                    activities = references.activities,
                    projects = references.projects,
                    tasks = references.tasks,
                    tags = references.tags,
                    loading = false,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(loading = false, error = e.message ?: "读取快捷记录失败") }
        }
    }

    suspend fun saveEntry(item: QuickEntryEntity, expectedVersion: Long?): Boolean = action("已保存快捷记录") {
        quickEntries.saveEntry(item, expectedVersion)
        true
    } ?: false

    suspend fun deleteEntry(id: String, expectedVersion: Long): String? = action(null) {
        quickEntries.deleteEntry(id, expectedVersion)
    }

    suspend fun setPinned(id: String, expectedVersion: Long, pinned: Boolean): Boolean =
        action(if (pinned) "已固定" else "已取消固定") {
            quickEntries.setPinned(id, expectedVersion, pinned)
            true
        } ?: false

    suspend fun moveEntry(id: String, direction: Int): Boolean = action(null) {
        val current = quickEntries.entries()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) {
            false
        } else {
            val target = (index + direction).coerceIn(0, current.lastIndex)
            if (target == index) {
                false
            } else {
                val reordered = current.toMutableList().apply { add(target, removeAt(index)) }
                quickEntries.reorder(reordered.map { it.id }, current.associate { it.id to it.version })
                mutableState.update { it.copy(entries = reordered.mapIndexed { i, item -> item.copy(sortOrder = i) }) }
                true
            }
        }
    } ?: false

    suspend fun activate(entryId: String, seenSlots: List<QuickEntrySlot> = emptyList()): QuickEntryLaunch {
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        return try {
            quickEntries.activate(entryId, seenSlots = seenSlots)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            QuickEntryLaunch.Failed(e.message ?: "启动失败，尚未执行")
        } finally {
            mutableState.update { it.copy(busy = false) }
            refresh()
        }
    }

    suspend fun markUsed(entryId: String) {
        try {
            quickEntries.markUsed(entryId)
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Usage metadata must never block the editor save.
        }
    }

    suspend fun saveTemplate(template: PlanTemplateEntity, items: List<PlanTemplateItemEntity>): TemplateSaveOutcome? =
        action(null) { quickEntries.saveTemplate(template, items, template.version.takeIf { template.version > 0 }) }

    suspend fun saveDayAsTemplate(name: String, date: LocalDate, description: String = ""): TemplateSaveOutcome? =
        action("已保存计划模板") { quickEntries.saveDayAsTemplate(name, date, description) }

    suspend fun deleteTemplate(id: String, expectedVersion: Long): String? = action(null) {
        quickEntries.deleteTemplate(id, expectedVersion)
    }

    suspend fun previewTemplate(templateId: String, startDate: LocalDate): TemplatePreview? {
        mutableState.update { it.copy(busy = true, error = null) }
        return try {
            quickEntries.previewTemplate(templateId, startDate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message ?: "读取模板失败") }
            null
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    suspend fun applyTemplate(
        templateId: String,
        startDate: LocalDate,
        itemIds: List<String>,
        templateVersion: Long?,
    ): TemplateApplyOutcome? = action("已应用计划模板") {
        quickEntries.applyTemplate(templateId, startDate, itemIds, templateVersion)
    }

    suspend fun undo(token: String) {
        mutableState.update { it.copy(busy = true, error = null) }
        try {
            quickEntries.undo(token)
            mutableState.update { it.copy(notice = "已撤销") }
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message ?: "撤销失败，数据未改变") }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun notify(message: String) {
        mutableState.update { it.copy(notice = message) }
    }

    fun clearMessages() {
        mutableState.update { it.copy(error = null, notice = null) }
    }

    private suspend fun <T> action(notice: String?, block: suspend () -> T): T? {
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        return try {
            val result = block()
            mutableState.update { it.copy(notice = notice) }
            refresh()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message?.takeIf { it.isNotBlank() } ?: "操作失败，尚未保存") }
            null
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }
}
