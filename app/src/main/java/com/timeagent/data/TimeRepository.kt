package com.timeagent.data

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.timeagent.core.*
import com.timeagent.suggestions.SuggestionAction
import com.timeagent.suggestions.suggestionFreeSlots
import com.timeagent.suggestions.suggestionTaskSlots
import com.timeagent.memory.PersonalMemoryRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import java.time.*
import java.util.UUID

class TimerAlreadyRunning : IllegalStateException("已有计时正在进行，请确认切换后再开始")
class StaleChange : IllegalStateException("数据已经发生变化，请关闭后重新打开再操作")
data class Conflict(val id: String, val title: String, val span: TimeSpan)
data class ImportPreview(val bundle: ImportBundle, val newCount: Int, val duplicateCount: Int, val protectedCount: Int)
data class LocalSnapshot(
    val tasks: List<TaskEntity> = emptyList(), val plans: List<ScheduleBlockEntity> = emptyList(),
    val records: List<TimeRecordEntity> = emptyList(), val courses: List<CourseEntity> = emptyList(),
    val occurrences: List<CourseOccurrenceEntity> = emptyList(), val terms: List<AcademicTermEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(), val history: List<OperationEntity> = emptyList(),
    val imports: List<ImportBatchEntity> = emptyList(), val loading: Boolean = true,
    val activities: List<ActivityTemplateEntity> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(), val ideas: List<IdeaEntity> = emptyList(), val notes: List<NoteEntity> = emptyList(),
    val healthItems: List<HealthSyncItemEntity> = emptyList(), val suggestions: List<AgentSuggestionEntity> = emptyList(),
    val memories: List<PersonalMemoryEntity> = emptyList(),
    val recurringRules:List<RecurringRuleEntity> = emptyList(), val recurringInstances:List<RecurringInstanceEntity> = emptyList(),
    val projects: List<ProjectEntity> = emptyList(), val placements: List<TaskPlacementEntity> = emptyList(), val weeklyGoals: List<WeeklyGoalEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
)

class TimeRepository(private val db: TimeAgentDatabase, private val clock: Clock = Clock.systemDefaultZone()) {
    internal val database: TimeAgentDatabase get() = db
    internal suspend fun <T> transaction(block: suspend () -> T): T = withContext(Dispatchers.IO) { PersonalDataGate.access { db.withTransaction { block() } } }
    private val dao = db.dao()
    private val gson = Gson()
    val zone: ZoneId get() = clock.zone
    fun now(): Instant = clock.instant()
    suspend fun addAttachment(item:AttachmentEntity):AttachmentEntity = transaction {
        require(dao.attachment(item.id)==null) { "附件已存在" }
        require(com.timeagent.attachments.AttachmentStore.validKey(item.storageKey) && item.sizeBytes in 1..com.timeagent.attachments.AttachmentStore.MAX_FILE_BYTES)
        val stamp=now().toEpochMilli()
        val saved=item.copy(createdAt=stamp,updatedAt=stamp,version=nextVersion("ATTACHMENT",item.id))
        dao.putAttachment(saved)
        journal("ATTACHMENT",item.id,saved.copy(deletedAt=stamp),saved,"导入附件（撤销后原件保留在回收站）")
        saved
    }
    private suspend fun attachmentForEdit(id:String,expectedVersion:Long):AttachmentEntity {
        val item=requireNotNull(dao.attachment(id)){"附件不存在"};checkVersion(item.version,expectedVersion);return item
    }
    private suspend fun putAttachmentEdit(before:AttachmentEntity,after:AttachmentEntity,action:String):AttachmentEntity {
        val saved=after.copy(updatedAt=now().toEpochMilli(),version=nextVersion("ATTACHMENT",before.id,before.version))
        dao.putAttachment(saved);journal("ATTACHMENT",before.id,before,saved,action);return saved
    }
    suspend fun editAttachment(id:String,expectedVersion:Long,text:String):AttachmentEntity=transaction {
        val before=attachmentForEdit(id,expectedVersion);require(text.length<=100000){"附件文字最多100000字"}
        putAttachmentEdit(before,before.copy(ocrText=text),"修改附件文字草稿")
    }
    suspend fun linkAttachment(id:String,projectId:String?,taskId:String?,expectedVersion:Long):AttachmentEntity=transaction {
        require(projectId==null || taskId==null){"附件只能关联一个项目或任务"}
        projectId?.let{require(dao.project(it)?.let{p->p.deletedAt==null}==true){"项目不存在或已删除"}}
        taskId?.let{activeTask(it)}
        val before=attachmentForEdit(id,expectedVersion)
        putAttachmentEdit(before,before.copy(projectId=projectId,taskId=taskId),"关联附件")
    }
    suspend fun setAttachmentDeleted(id:String,expectedVersion:Long,deleted:Boolean):AttachmentEntity=transaction {
        val before=attachmentForEdit(id,expectedVersion)
        putAttachmentEdit(before,before.copy(deletedAt=if(deleted)now().toEpochMilli() else null),if(deleted)"移入附件回收站" else "恢复附件元数据")
    }
    suspend fun markAttachmentPurged(id:String,expectedVersion:Long):AttachmentEntity=transaction {
        val before=attachmentForEdit(id,expectedVersion);require(before.deletedAt!=null){"请先移入回收站"}
        val after=before.copy(purgedAt=now().toEpochMilli(),updatedAt=now().toEpochMilli(),version=nextVersion("ATTACHMENT",id,before.version))
        dao.putAttachment(after);dao.clearRedo();after
    }
    suspend fun saveRecurringRule(item:RecurringRuleEntity,expectedVersion:Long?=null):RecurringRuleEntity = transaction {
        com.timeagent.recurring.RecurringValidation.pattern(item)
        val before=dao.recurringRule(item.id);checkVersion(before?.version,expectedVersion)
        require(before?.deletedAt==null) { "规则已删除，请先撤销删除" }
        item.projectId?.let { require(dao.project(it)?.let{p->p.deletedAt==null}==true) { "项目不存在或已删除" } }
        val saved=item.copy(title=item.title.trim(),createdAt=before?.createdAt?:now().toEpochMilli(),updatedAt=now().toEpochMilli(),version=nextVersion("RECURRING_RULE",item.id,before?.version))
        dao.putRecurringRule(saved);journal("RECURRING_RULE",item.id,before,saved,"保存周期规则");saved
    }
    suspend fun saveRecurringInstance(item:RecurringInstanceEntity,expectedVersion:Long?=null):RecurringInstanceEntity = transaction {
        com.timeagent.recurring.RecurringValidation.instance(item)
        val before=dao.recurringInstance(item.id);checkVersion(before?.version,expectedVersion)
        if(before==null && dao.revision("RECURRING_INSTANCE:${item.id}")!=null) throw StaleChange()
        require(dao.recurringRule(item.ruleId)!=null && dao.task(item.taskId)!=null) { "周期来源或原任务不存在" }
        val saved=item.copy(version=nextVersion("RECURRING_INSTANCE",item.id,before?.version))
        dao.putRecurringInstance(saved);journal("RECURRING_INSTANCE",item.id,before,saved,"保存周期实例");saved
    }
    suspend fun saveProject(item: ProjectEntity, expectedVersion: Long? = null): ProjectEntity = transaction {
        require(item.id.isNotBlank() && item.name.trim().length in 1..100) { "请填写项目名称，最多100字" }
        require(item.status in setOf("ACTIVE", "PAUSED", "COMPLETED")) { "项目状态无效" }
        val before = dao.project(item.id); checkVersion(before?.version, expectedVersion)
        require(before?.deletedAt == null) { "项目已删除，请先撤销删除" }
        val saved = item.copy(name = item.name.trim(), deletedAt = before?.deletedAt, createdAt = before?.createdAt ?: now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("PROJECT", item.id, before?.version))
        dao.putProject(saved); journal("PROJECT", item.id, before, saved, "保存项目"); saved
    }
    suspend fun deleteProject(id: String, expectedVersion: Long) = transaction {
        val before = requireNotNull(dao.project(id)); checkVersion(before.version, expectedVersion)
        val saved = before.copy(deletedAt = now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("PROJECT",id,before.version))
        dao.putProject(saved); journal("PROJECT", id, before, saved, "删除项目（保留任务及历史归属）")
    }
    private suspend fun validatePlacement(item: TaskPlacementEntity) {
        require(item.taskId != item.parentTaskId) { "任务不能作为自己的子任务" }
        activeTask(item.taskId)
        item.projectId?.let { require(dao.project(it)?.deletedAt == null && dao.project(it) != null) { "项目不存在或已删除" } }
        val all = dao.placements().filterNot { it.taskId == item.taskId } + item
        if (item.parentTaskId != null) {
            activeTask(item.parentTaskId)
            val parent = requireNotNull(all.find { it.taskId == item.parentTaskId }) { "请先设置父任务归属" }
            require(parent.parentTaskId == null) { "只支持一级子任务" }
            require(parent.projectId == item.projectId) { "父子任务必须属于同一项目，请先设置父任务归属" }
        }
        require(all.none { child -> child.parentTaskId == item.taskId && (item.parentTaskId != null || child.projectId != item.projectId) }) { "请先处理子任务归属；不自动移动整组任务" }
    }
    suspend fun savePlacement(item: TaskPlacementEntity, expectedVersion: Long? = null): TaskPlacementEntity = transaction {
        val before = dao.placement(item.taskId); checkVersion(before?.version, expectedVersion)
        validatePlacement(item)
        val saved = item.copy(version = nextVersion("PLACEMENT",item.taskId,before?.version))
        dao.putPlacement(saved); journal("PLACEMENT",item.taskId,before,saved,"修改项目 / 子任务归属"); saved
    }
    private suspend fun validateGoal(item: WeeklyGoalEntity) {
        require(item.id.isNotBlank() && (item.projectId == null) != (item.activityId == null)) { "周目标须选择一个项目或活动" }
        require(item.targetMinutes in 1..10080 && LocalDate.parse(item.weekStart).dayOfWeek == DayOfWeek.MONDAY) { "目标须为1至10080分钟，起始日期须为周一" }
        item.projectId?.let { require(dao.project(it)?.let { p -> p.deletedAt == null } == true) { "项目不存在或已删除" } }
        item.activityId?.let { require(dao.activity(it)?.let { a -> a.archivedAt == null } == true) { "活动不存在或已归档" } }
        require(dao.weeklyGoals().none { it.id != item.id && it.projectId == item.projectId && it.activityId == item.activityId && it.weekStart == item.weekStart }) { "此目标已有本周投入，请编辑原目标" }
    }
    suspend fun saveWeeklyGoal(item: WeeklyGoalEntity, expectedVersion: Long? = null): WeeklyGoalEntity = transaction {
        val before = dao.weeklyGoal(item.id); checkVersion(before?.version, expectedVersion); validateGoal(item)
        val saved = item.copy(version = nextVersion("GOAL",item.id,before?.version))
        dao.putWeeklyGoal(saved); journal("GOAL",item.id,before,saved,"保存周目标"); saved
    }
    suspend fun saveMemory(item: PersonalMemoryEntity, expectedVersion: Long? = null): PersonalMemoryEntity = transaction {
        PersonalMemoryRules.validate(item)
        val before = dao.memory(item.id)
        checkVersion(before?.version, expectedVersion)
        val saved = item.copy(text = item.text.trim(), createdAt = before?.createdAt ?: now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("MEMORY", item.id, before?.version))
        dao.putMemory(saved); journal("MEMORY", item.id, before, saved, "保存个人记忆")
        saved
    }
    suspend fun deleteMemory(id: String, expectedVersion: Long) = transaction {
        val before = requireNotNull(dao.memory(id)); checkVersion(before.version, expectedVersion)
        val after = before.copy(deletedAt = now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("MEMORY", id, before.version))
        dao.putMemory(after); journal("MEMORY", id, before, after, "删除个人记忆")
    }
    suspend fun rememberExplicit(text: String, requestId: String): PersonalMemoryEntity = transaction {
        val parsed = requireNotNull(PersonalMemoryRules.fromExplicit(text, now())) { "请用明确的“记住……”表达；问句和转述不会保存" }
        var result = dao.memories().firstOrNull { it.deletedAt == null && it.text == parsed.text && it.confirmed }
        assistantTransaction(requestId) {
            if (result == null) result = saveMemory(parsed)
        }
        requireNotNull(result) { "这次记忆操作已经撤销，请重新录入" }
    }
    suspend fun proposeMemoryCandidate(): PersonalMemoryEntity? = transaction {
        val proposedRecordIds = dao.memories().filter { it.sourceType == "OBSERVATION" }.mapNotNull { it.sourceId }.toSet()
        val record = dao.records().firstOrNull { it.id !in proposedRecordIds && it.endAt != null && it.endAt <= now().toEpochMilli() && it.source != "HEALTH_SYNC" && it.title.isNotBlank() && it.title !in setOf("睡眠", "睡觉") } ?: return@transaction null
        saveMemory(PersonalMemoryEntity(UUID.randomUUID().toString(), "PREFERENCE", "我可能偏好${record.title.take(200)}", sourceType = "OBSERVATION", sourceText = "仅依据一条实际记录：${record.title} · ${Instant.ofEpochMilli(record.startAt).atZone(zone)}；不代表长期习惯", sourceId = record.id, confirmed = false))
    }
    suspend fun snapshot(): LocalSnapshot = transaction { readSnapshot() }
    private suspend fun readSnapshot() = LocalSnapshot(dao.tasks(), dao.activePlans(), dao.records(), dao.courses(), dao.occurrences(), dao.terms(), dao.categories(), dao.history(), dao.importBatches(), false, dao.activities(), dao.reminders(), dao.ideas(), dao.notes(), dao.healthItems(), dao.suggestions(), dao.memories(), dao.recurringRules(), dao.recurringInstances(), dao.projects(), dao.placements(), dao.weeklyGoals(), dao.attachments())

    fun observe(): Flow<LocalSnapshot> = db.invalidationTracker.createFlow("tasks", "schedule_blocks", "time_records", "courses", "course_occurrences", "academic_terms", "categories", "tags", "task_tags", "record_tags", "operation_history", "import_batches", "activity_templates", "reminders", "ideas", "notes", "health_sync_items", "agent_suggestions", "personal_memories", "projects", "task_placements", "weekly_goals", "recurring_rules", "recurring_instances", "attachments")
        .map { transaction { readSnapshot() } }
        .flowOn(Dispatchers.IO)

    suspend fun saveIdea(item: IdeaEntity, expectedVersion: Long? = null): IdeaEntity = transaction {
        require(item.id.isNotBlank() && item.title.trim().length in 1..200 && item.content.length <= 20000) { "请填写想法，标题最多200字、内容最多20000字" }
        require(item.status in setOf("UNSCHEDULED", "SCHEDULED", "ARCHIVED")) { "想法状态无效" }
        val before = dao.idea(item.id)
        checkVersion(before?.version, expectedVersion)
        val saved = item.copy(title = item.title.trim(), createdAt = before?.createdAt ?: now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("IDEA", item.id, before?.version))
        dao.putIdea(saved); journal("IDEA", saved.id, before, saved, "保存想法")
        saved
    }

    private suspend fun noteTargetExists(type: String, id: String): Boolean = when (type) {
        "PROJECT" -> dao.project(id) != null
        "ACTIVITY" -> dao.activity(id) != null
        "TASK" -> dao.task(id) != null
        "PLAN" -> dao.plan(id) != null
        "RECORD" -> dao.record(id) != null
        "OCCURRENCE" -> dao.occurrence(id) != null
        "IDEA" -> dao.idea(id) != null
        "REMINDER" -> dao.reminder(id) != null
        else -> false
    }
    suspend fun saveNote(item: NoteEntity, expectedVersion: Long? = null): NoteEntity = transaction {
        require(item.id.isNotBlank() && item.title.length <= 200 && item.body.length <= 100000) { "笔记标题最多200字、内容最多100000字" }
        require(noteTargetExists(item.targetType, item.targetId)) { "笔记所属模块不存在" }
        val before = dao.note(item.id)
        checkVersion(before?.version, expectedVersion)
        require(before == null || before.targetType == item.targetType && before.targetId == item.targetId) { "笔记不能更改所属模块" }
        require(dao.notes().none { it.id != item.id && it.targetType == item.targetType && it.targetId == item.targetId }) { "此模块已有笔记，请重新打开" }
        val saved = item.copy(createdAt = before?.createdAt ?: now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("NOTE", item.id, before?.version))
        dao.putNote(saved); journal("NOTE", saved.id, before, saved, "保存笔记")
        saved
    }

    private fun reminderDeliveryKey(id: String, at: Long) = "REMINDER_DELIVERY:$id:$at"
    suspend fun saveReminder(item: ReminderEntity, expectedVersion: Long? = null, automatic: Boolean = false): ReminderEntity = transaction {
        require(item.id.isNotBlank() && item.title.trim().length in 1..200) { "请填写提醒内容，最多200字" }
        val before = dao.reminder(item.id)
        checkVersion(before?.version, expectedVersion)
        require(item.remindAt > now().toEpochMilli() || before?.remindAt == item.remindAt) { "提醒时间已经过去，请选择未来时间" }
        item.taskId?.let { id ->
            requireNotNull(dao.task(id)?.takeIf { it.deletedAt == null && it.status !in setOf("CANCELLED", "COMPLETED") }) {
                "任务不存在、已删除或已完成"
            }
        }
        val delivered = dao.revision(reminderDeliveryKey(item.id, item.remindAt))?.revision
            ?: before?.deliveredAt?.takeIf { before.remindAt == item.remindAt }
        val policyKey="REMINDER_MANUAL_OFF:${item.id}"
        val beforeOff=dao.revision(policyKey)?.revision?:0L
        require(beforeOff in 0L..1L)
        if(automatic && before==null && dao.revision("REMINDER:${item.id}")!=null)throw StaleChange()
        val afterOff=if(automatic)beforeOff else if(item.enabled)0L else 1L
        val saved = item.copy(title = item.title.trim(), enabled=item.enabled && afterOff!=1L, deliveredAt = delivered, createdAt = before?.createdAt ?: now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("REMINDER", item.id, before?.version))
        fun policySnapshot(value:ReminderEntity?,off:Long)=value?.let{gson.toJsonTree(it).asJsonObject.apply{addProperty("__manualOff",off)}}
        dao.putRevision(EntityRevision(policyKey,afterOff))
        dao.putReminder(saved); journal("REMINDER", saved.id, policySnapshot(before,beforeOff), policySnapshot(saved,afterOff), "保存任务点提醒")
        saved
    }
    suspend fun markReminderDelivered(id: String, expectedRemindAt: Long): Boolean = transaction {
        val item = dao.reminder(id) ?: return@transaction false
        val key = reminderDeliveryKey(id, expectedRemindAt)
        if (!item.enabled || item.deliveredAt != null || item.remindAt != expectedRemindAt || expectedRemindAt > now().toEpochMilli() || dao.revision(key) != null) return@transaction false
        if (item.taskId != null && dao.task(item.taskId)?.let { it.deletedAt == null && it.status !in setOf("CANCELLED", "COMPLETED") } != true) return@transaction false
        val stamp = now().toEpochMilli()
        dao.putRevision(EntityRevision(key, stamp))
        dao.putReminder(item.copy(deliveredAt = stamp))
        true
    }
    suspend fun redoLastAction() = transaction {
        val action = requireNotNull(dao.redoAction()) { "还没有可重做的操作" }
        applyHistory(action, forward = true)
        dao.markRedone(action.id)
    }

    suspend fun initialize() = transaction {
        if (dao.categories().isEmpty()) listOf(CategoryEntity("study", "学习", 0xFF5756B7, 0), CategoryEntity("project", "项目", 0xFF3978B5, 1), CategoryEntity("life", "生活", 0xFF26806A, 2)).forEach { dao.putCategory(it) }
        if (dao.activities().isEmpty()) {
            val stamp = now().toEpochMilli()
            listOf(
                ActivityTemplateEntity("sleep", "睡觉", defaultMinutes = 480, categoryId = "life", sortOrder = 0),
                ActivityTemplateEntity("fitness", "健身", defaultMinutes = 60, categoryId = "life", sortOrder = 1),
                ActivityTemplateEntity("fitness-chest", "练胸", "fitness", 60, "life", 0),
                ActivityTemplateEntity("fitness-back", "练背", "fitness", 60, "life", 1),
                ActivityTemplateEntity("fitness-shoulders", "练肩", "fitness", 60, "life", 2),
                ActivityTemplateEntity("fitness-arms", "练手臂", "fitness", 60, "life", 3),
            ).forEach { dao.putActivity(it.copy(createdAt = stamp, updatedAt = stamp)) }
        }
    }

    suspend fun saveActivity(item: ActivityTemplateEntity, expectedVersion: Long? = null): ActivityTemplateEntity = transaction {
        require(item.id.isNotBlank() && item.name.isNotBlank()) { "活动名称不能为空" }
        require(item.name.trim().length <= 60 && item.defaultMinutes in 1..10080) { "请检查名称长度和建议时长" }
        require(item.parentId != item.id) { "活动不能作为自己的子模块" }
        val before = dao.activity(item.id)
        checkVersion(before?.version, expectedVersion)
        if (item.parentId != null) {
            val parent = requireNotNull(dao.activity(item.parentId)?.takeIf { it.archivedAt == null }) { "父模块不存在或已归档" }
            require(parent.parentId == null) { "目前支持大模块和子模块两级" }
            require(dao.activities().none { it.parentId == item.id && it.archivedAt == null }) { "请先处理当前模块的子模块" }
        }
        require(dao.activities().none { it.id != item.id && it.archivedAt == null && it.parentId == item.parentId && it.name == item.name.trim() }) { "同一层级已有同名活动" }
        val saved = item.copy(name = item.name.trim(), archivedAt = before?.archivedAt, createdAt = before?.createdAt ?: now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("ACTIVITY", item.id, before?.version))
        dao.putActivity(saved)
        journal("ACTIVITY", item.id, before, saved, "保存活动模板")
        saved
    }

    suspend fun archiveActivity(id: String, expectedVersion: Long? = null) = transaction {
        val before = requireNotNull(dao.activity(id)?.takeIf { it.archivedAt == null }) { "活动不存在或已归档" }
        checkVersion(before.version, expectedVersion)
        require(dao.activities().none { it.parentId == id && it.archivedAt == null }) { "请先归档此模块下的子模块" }
        val after = before.copy(archivedAt = now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("ACTIVITY", id, before.version))
        dao.putActivity(after)
        journal("ACTIVITY", id, before, after, "归档活动模板")
    }

    internal suspend fun journal(type: String, id: String, before: Any?, after: Any?, action: String = "保存") {
        val entry = OperationEntity(actionType = action, entityType = type, entityId = id, beforeSnapshot = before?.let { gson.toJson(it) }, afterSnapshot = after?.let { gson.toJson(it) }, createdAt = now().toEpochMilli())
        val collector = currentCoroutineContext()[AssistantJournal]
        if (collector != null) collector.entries += entry else { dao.clearRedo(); dao.putAction(entry) }
    }

    private class AssistantJournal : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<AssistantJournal>
        val entries = mutableListOf<OperationEntity>()
    }
    private data class AssistantBatch(val entries: List<OperationEntity>)

    /** Local typed writes share one transaction and one undo; a replayed request never writes twice. */
    suspend fun assistantTransaction(requestId: String, block: suspend () -> Unit): Boolean = transaction {
        require(requestId.isNotBlank())
        if (dao.actionFor("ASSISTANT_BATCH", requestId) != null) return@transaction false
        check(currentCoroutineContext()[AssistantJournal] == null)
        val collector = AssistantJournal()
        withContext(collector) { block() }
        if (collector.entries.isNotEmpty()) {
            dao.clearRedo()
            dao.putAction(OperationEntity(actionType = "助手 · ${collector.entries.first().actionType}", entityType = "ASSISTANT_BATCH", entityId = requestId, beforeSnapshot = null,
                afterSnapshot = gson.toJson(AssistantBatch(collector.entries)), createdAt = now().toEpochMilli()))
        }
        true
    }

    /**
     * Scheduling an inbox idea as a point reminder is one user action: both writes must undo and redo
     * together, and a failure leaves neither half behind.
     */
    suspend fun scheduleIdeaAsReminder(
        ideaId: String,
        expectedIdeaVersion: Long,
        reminder: ReminderEntity,
    ): Pair<IdeaEntity, ReminderEntity> = transaction {
        val idea = requireNotNull(dao.idea(ideaId)?.takeIf { it.deletedAt == null && it.status != "ARCHIVED" }) {
            "想法不存在或已归档"
        }
        checkVersion(idea.version, expectedIdeaVersion)
        require(reminder.taskId == null) { "想法提醒不关联任务" }
        require(reminder.remindAt > now().toEpochMilli()) { "提醒时间已经过去，请选择未来时间" }
        check(currentCoroutineContext()[AssistantJournal] == null)
        val collector = AssistantJournal()
        val saved = withContext(collector) {
            val savedReminder = saveReminder(reminder.copy(title = reminder.title.trim().ifBlank { idea.title }))
            val savedIdea = saveIdea(idea.copy(status = "SCHEDULED"), idea.version)
            savedIdea to savedReminder
        }
        dao.clearRedo()
        dao.putAction(OperationEntity(actionType = "安排想法为提醒", entityType = "IDEA_REMINDER", entityId = ideaId,
            beforeSnapshot = null, afterSnapshot = gson.toJson(AssistantBatch(collector.entries)), createdAt = now().toEpochMilli()))
        saved
    }

    suspend fun deleteReminder(id: String, expectedVersion: Long? = null) = transaction {
        val before = requireNotNull(dao.reminder(id)) { "提醒不存在" }
        checkVersion(before.version, expectedVersion)
        nextVersion("REMINDER", id, before.version)
        dao.removeReminder(id)
        journal("REMINDER", id, before, null, "删除任务点提醒")
    }
    private fun checkVersion(current: Long?, expected: Long?) { if (expected != null && current != expected) throw StaleChange() }
    internal suspend fun nextVersion(type: String, id: String, current: Long? = null): Long {
        val key = "$type:$id"
        val next = maxOf(dao.revision(key)?.revision ?: -1, current ?: -1) + 1
        dao.putRevision(EntityRevision(key, next))
        return next
    }
    private suspend fun activeTask(id: String): TaskEntity = requireNotNull(dao.task(id)?.takeIf { it.deletedAt == null && it.status != "CANCELLED" }) { "任务不存在或已删除" }

    suspend fun saveTask(task: TaskEntity, expectedVersion: Long? = null): TaskEntity = transaction {
        require(task.id.isNotBlank() && task.title.isNotBlank()) { "任务名称不能为空" }
        require(task.estimatedMinutes in 1..10080 && task.priority in 0..2) { "预计时长或优先级无效" }
        require(task.status in setOf("TODO", "IN_PROGRESS", "COMPLETED", "CANCELLED")) { "任务状态无效" }
        val before = dao.task(task.id)
        checkVersion(before?.version, expectedVersion)
        if (task.activityId != null && task.activityId != before?.activityId) require(dao.activity(task.activityId)?.archivedAt == null && dao.activity(task.activityId) != null) { "活动模板不存在或已归档" }
        if (before != null && expectedVersion == null && before.title == task.title.trim() && before.description == task.description && before.estimatedMinutes == task.estimatedMinutes && before.priority == task.priority && before.dueAt == task.dueAt && before.status == task.status && before.categoryId == task.categoryId && before.activityId == task.activityId) return@transaction before
        val saved = task.copy(title = task.title.trim(), createdAt = before?.createdAt ?: now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("TASK", task.id, before?.version), completedAt = if (task.status == "COMPLETED") before?.completedAt ?: now().toEpochMilli() else null)
        dao.putTask(saved)
        journal("TASK", saved.id, before, saved, if (saved.status == "COMPLETED") "完成任务" else "保存任务")
        saved
    }

    /** Grid creation is one atomic operation; an invalid plan never leaves an orphan task. */
    suspend fun saveTaskAndPlan(task: TaskEntity, planId: String, span: TimeSpan): TaskEntity = transaction {
        dao.plan(planId)?.let { existing ->
            require(existing.taskId == task.id && existing.startAt == span.start.toEpochMilli() && existing.endAt == span.end.toEpochMilli()) { "请求已使用，请重新创建日程" }
            return@transaction requireNotNull(dao.task(task.id))
        }
        require(dao.task(task.id) == null) { "任务已存在，请重新创建日程" }
        val saved = saveTask(task)
        savePlan(ScheduleBlockEntity(planId, saved.id, span.start.toEpochMilli(), span.end.toEpochMilli()))
        saved
    }

    suspend fun saveAssistantEvent(task: TaskEntity, plan: ScheduleBlockEntity, expectedConflicts: List<Conflict>) = transaction {
        if (dao.task(task.id) != null || dao.plan(plan.id) != null) throw StaleChange()
        val saved = saveTask(task)
        savePlan(plan, expectedTaskVersion = saved.version, expectedConflicts = expectedConflicts)
    }

    suspend fun deleteTask(id: String) = transaction {
        val before = activeTask(id)
        require(dao.runningTimer()?.taskId != id) { "请先结束此任务的计时，再删除任务" }
        val after = before.copy(deletedAt = now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("TASK", id, before.version))
        dao.putTask(after)
        journal("TASK", id, before, after, "删除任务")
    }

    suspend fun conflicts(span: TimeSpan, excluding: String? = null): List<Conflict> {
        val tasks = dao.tasks().associateBy { it.id }
        val courses = dao.courses().associateBy { it.id }
        return dao.activePlans().filter { it.id != excluding }.map { Conflict(it.id, tasks[it.taskId]?.title ?: "计划", TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt))) }
            .plus(dao.occurrences().filter { it.status != "CANCELLED" && it.id != excluding }.map { Conflict(it.id, courses[it.courseId]?.name ?: "课程", TimeSpan(Instant.ofEpochMilli(it.startAt), Instant.ofEpochMilli(it.endAt))) })
            .filter { span.overlaps(it.span) }
    }

    suspend fun savePlan(plan: ScheduleBlockEntity, expectedVersion: Long? = null, expectedTaskVersion: Long? = null, expectedConflicts: List<Conflict>? = null): List<Conflict> = transaction {
        val span = TimeSpan(Instant.ofEpochMilli(plan.startAt), Instant.ofEpochMilli(plan.endAt))
        val task = activeTask(plan.taskId)
        PersonalMemoryRules.violation(dao.memories(), task, span.start, span.end, zone)?.let { throw IllegalArgumentException("与个人约定冲突：${it.text}。请调整时段或在个人记忆中停用该约定。") }
        checkVersion(task.version, expectedTaskVersion)
        val before = dao.plan(plan.id)
        checkVersion(before?.version, expectedVersion)
        val warnings = conflicts(span, plan.id)
        if (expectedConflicts != null && warnings.sortedBy { it.id } != expectedConflicts.sortedBy { it.id }) throw StaleChange()
        val saved = plan.copy(createdAt = before?.createdAt ?: now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("PLAN", plan.id, before?.version))
        dao.putPlan(saved)
        journal("PLAN", saved.id, before, saved, "保存计划")
        warnings // A warning never prevents saving a valid plan.
    }

    suspend fun saveRecord(record: TimeRecordEntity, allowFuture: Boolean = false, expectedVersion: Long? = null, expectedTaskVersion: Long? = null) = transaction {
        val end = requireNotNull(record.endAt) { "手动记录必须设置结束时间" }
        TimeSpan(Instant.ofEpochMilli(record.startAt), Instant.ofEpochMilli(end))
        require(allowFuture || end <= now().toEpochMilli()) { "记录包含未来时间，请确认后保存，或将其创建为计划" }
        val before = dao.record(record.id)
        checkVersion(before?.version, expectedVersion)
        if (expectedTaskVersion != null) checkVersion(record.taskId?.let { dao.task(it)?.version }, expectedTaskVersion)
        if (record.taskId != null && (before == null || before.taskId != record.taskId)) activeTask(record.taskId)
        val saved = record.copy(createdAt = before?.createdAt ?: now().toEpochMilli(), updatedAt = now().toEpochMilli(), isManuallyEdited = before != null, version = nextVersion("RECORD", record.id, before?.version))
        dao.putRecord(saved)
        journal("RECORD", saved.id, before, saved, "保存实际记录")
    }

    suspend fun runningTimer() = dao.runningTimer()

    /**
     * Applies one health sync pass in a single transaction. Sync writes are data intake, not user
     * edits: they never journal into undo history and never retire an active redo branch. Manual
     * edits and user deletions of synced records always win over later source changes. Sleep
     * sessions from different source apps that describe the same night (overlap >= 30 min) are
     * deduplicated: already-stored sleep wins, then earlier samples in this batch. When
     * preferredSleepOrigin names a source (by label), sleep from that origin replaces overlapping
     * stored sleep from any other origin — except manually edited or deleted records.
     */
    suspend fun syncHealthSamples(samples: List<HealthSample>, preferredSleepOrigin: String? = null): HealthSyncOutcome = transaction {
        val stamp = now().toEpochMilli()
        val categoryByKind = mapOf(
            HealthSampleKind.SLEEP to dao.activity("sleep")?.categoryId,
            HealthSampleKind.EXERCISE to dao.activity("fitness")?.categoryId,
        )
        val storedSleep = dao.healthItems()
            .filter { it.kind == HealthSampleKind.SLEEP.name && it.recordId != null }
            .mapNotNull { item -> dao.record(item.recordId!!)?.let { Triple(it.startAt, it.endAt ?: 0L, it.id) to item } }
        val storedSleepSpans = storedSleep.map { it.first }.toMutableList()
        val batchSleepSpans = mutableListOf<Triple<Long, Long, String>>()
        var inserted = 0; var updated = 0; var protected = 0; var unchanged = 0; var deduped = 0
        fun overlaps(a: Triple<Long, Long, String>, start: Long, end: Long): Boolean =
            minOf(a.second, end) - maxOf(a.first, start) >= 30 * 60_000L
        fun isPreferred(originPackage: String): Boolean =
            preferredSleepOrigin != null && preferredSleepOrigin.isNotBlank() && healthOriginLabel(originPackage) == preferredSleepOrigin
        // Preferred-origin samples evaluate first so they win within a mixed batch.
        val ordered = samples.sortedWith(compareBy({ !(it.kind == HealthSampleKind.SLEEP && isPreferred(it.originPackage)) }, { it.startAt }))
        ordered.forEach { sample ->
            if (sample.uid.isBlank() || sample.endAt <= sample.startAt) return@forEach
            if (sample.endAt > stamp) return@forEach // An in-progress session syncs after it closes.
            val before = dao.healthItem(sample.uid)
            if (before == null) {
                if (sample.kind == HealthSampleKind.SLEEP) {
                    // A preferred sleep source displaces stored sleep from other origins.
                    if (isPreferred(sample.originPackage)) {
                        val displaced = storedSleep.filter { overlaps(it.first, sample.startAt, sample.endAt) }
                            .filter { healthOriginLabel(it.second.originPackage) != preferredSleepOrigin }
                        if (displaced.isNotEmpty()) {
                            val manuallyKept = displaced.filter { dao.record(it.second.recordId!!)?.isManuallyEdited == true }
                            if (manuallyKept.isEmpty()) {
                                displaced.forEach { (_, item) ->
                                    dao.removeRecord(item.recordId!!)
                                    dao.removeHealthItem(item.id)
                                }
                                val displacedIds = displaced.map { it.first.third }.toSet()
                                val spanIterator = storedSleepSpans.iterator()
                                while (spanIterator.hasNext()) {
                                    if (spanIterator.next().third in displacedIds) spanIterator.remove()
                                }
                            }
                        }
                    }
                    if (storedSleepSpans.any { overlaps(it, sample.startAt, sample.endAt) } || batchSleepSpans.any { overlaps(it, sample.startAt, sample.endAt) }) {
                        deduped++
                        return@forEach
                    }
                }
                val recordId = sample.recordId()
                dao.putRecord(TimeRecordEntity(recordId, null, sample.startAt, sample.endAt, categoryId = categoryByKind[sample.kind], source = "HEALTH_SYNC", createdAt = stamp, updatedAt = stamp, version = nextVersion("RECORD", recordId), title = sample.title, notes = sample.detail))
                dao.putHealthItem(HealthSyncItemEntity(sample.uid, sample.kind.name, sample.title, sample.startAt, sample.endAt, sample.originPackage, recordId, lastSyncedAt = stamp))
                if (sample.kind == HealthSampleKind.SLEEP) batchSleepSpans += Triple(sample.startAt, sample.endAt, recordId)
                inserted++
                return@forEach
            }
            // A restored record (undo of a delete) re-links through the deterministic id.
            val linked = dao.record(before.recordId ?: sample.recordId())
            if (linked == null) {
                if (!before.userDeleted) dao.putHealthItem(before.copy(userDeleted = true, lastSyncedAt = stamp))
                unchanged++
                return@forEach
            }
            if (before.recordId == null || before.userDeleted) dao.putHealthItem(before.copy(recordId = linked.id, userDeleted = false, lastSyncedAt = stamp))
            when {
                linked.isManuallyEdited -> { protected++; dao.putHealthItem(before.copy(recordId = linked.id, userDeleted = false, lastSyncedAt = stamp)) }
                linked.startAt == sample.startAt && linked.endAt == sample.endAt && linked.title == sample.title && linked.notes == sample.detail -> { unchanged++; dao.putHealthItem(before.copy(recordId = linked.id, userDeleted = false, lastSyncedAt = stamp)) }
                else -> {
                    dao.putRecord(linked.copy(startAt = sample.startAt, endAt = sample.endAt, title = sample.title, notes = sample.detail, updatedAt = stamp, version = nextVersion("RECORD", linked.id)))
                    dao.putHealthItem(HealthSyncItemEntity(sample.uid, sample.kind.name, sample.title, sample.startAt, sample.endAt, sample.originPackage, linked.id, lastSyncedAt = stamp))
                    updated++
                }
            }
        }
        HealthSyncOutcome(inserted, updated, protected, unchanged, deduped)
    }

    /**
     * Records system-generated suggestions. Suggestions are data intake like health sync: no undo
     * journal. Dismissed, applied and expired keys stay consumed; rule-invalidated keys may
     * return only after the current rules allow them again. Pending ones older than 7 days expire.
     */
    suspend fun recordSuggestions(candidates: List<AgentSuggestionEntity>) = transaction {
        val stamp = now().toEpochMilli()
        val memories = dao.memories()
        dao.allSuggestions().filter { it.status in setOf("PENDING", "SNOOZED") }.forEach { item ->
            val task = item.taskId?.let { dao.task(it) }
            val staleTask = item.taskId != null && (task == null || task.deletedAt != null || task.status != "TODO" || task.version != item.taskVersion)
            val staleRule = task != null && item.proposedStartAt != null && item.proposedEndAt != null && !PersonalMemoryRules.allows(memories, task, Instant.ofEpochMilli(item.proposedStartAt), Instant.ofEpochMilli(item.proposedEndAt), zone)
            if (item.createdAt < stamp - 7L * 86400_000 || item.expiresAt?.let { it <= stamp } == true || staleTask) dao.putSuggestion(item.copy(status = "EXPIRED", decidedAt = stamp, version = nextVersion("SUGGESTION", item.id, item.version)))
            else if (staleRule) dao.putSuggestion(item.copy(status = "INVALIDATED", decidedAt = stamp, version = nextVersion("SUGGESTION", item.id, item.version)))
            else if (item.status == "SNOOZED" && item.snoozedUntil?.let { it <= stamp } == true) dao.putSuggestion(item.copy(status = "PENDING", snoozedUntil = null, version = nextVersion("SUGGESTION", item.id, item.version)))
        }
        candidates.forEach { candidate ->
            require(candidate.id.isNotBlank() && candidate.type.isNotBlank() && candidate.dedupKey.isNotBlank()) { "建议数据无效" }
            val task = candidate.taskId?.let { dao.task(it) }
            if (candidate.taskId != null && task?.let { it.deletedAt == null && it.status == "TODO" && it.version == candidate.taskVersion } != true) return@forEach
            if (task != null && candidate.proposedStartAt != null && candidate.proposedEndAt != null && !PersonalMemoryRules.allows(memories, task, Instant.ofEpochMilli(candidate.proposedStartAt), Instant.ofEpochMilli(candidate.proposedEndAt), zone)) return@forEach
            val existing = dao.suggestionByDedup(candidate.dedupKey)
            if (existing == null) dao.putSuggestion(candidate)
            else if (existing.status == "INVALIDATED" || existing.status == "PENDING" && candidate.taskId != null &&
                (existing.proposedStartAt != candidate.proposedStartAt || existing.proposedEndAt != candidate.proposedEndAt)) {
                dao.putSuggestion(candidate.copy(id = existing.id, createdAt = existing.createdAt, version = nextVersion("SUGGESTION", existing.id, existing.version)))
            }
        }
    }

    suspend fun dismissSuggestion(id: String) = transaction {
        val before = requireNotNull(dao.allSuggestions().firstOrNull { it.id == id }) { "建议不存在" }
        if (before.status == "PENDING") {
            val after = before.copy(status = "DISMISSED", decidedAt = now().toEpochMilli(), version = nextVersion("SUGGESTION", id, before.version))
            dao.putSuggestion(after); journal("SUGGESTION", id, before, after, "忽略建议")
        }
    }

    private suspend fun checkedSuggestion(id: String, version: Long): AgentSuggestionEntity {
        val card = requireNotNull(dao.suggestion(id)) { "建议不存在" }
        checkVersion(card.version, version)
        require(card.status == "PENDING") { "这条建议已经处理" }
        require((card.expiresAt ?: card.createdAt + 7L * 86400_000) > now().toEpochMilli()) { "建议已经过期，请重新查看事项" }
        return card
    }

    private suspend fun suggestionTask(card: AgentSuggestionEntity): TaskEntity {
        val task = requireNotNull(card.taskId?.let { dao.task(it) }) { "此建议没有可执行的关联事项" }
        require(task.deletedAt == null && task.status == "TODO") { "任务已处理、正在计时或已删除，请重新查看" }
        if (card.taskVersion == null || card.taskVersion != task.version) throw StaleChange()
        return task
    }

    suspend fun suggestionSlots(id: String, expectedVersion: Long): List<TimeSpan> = transaction {
        val card = checkedSuggestion(id, expectedVersion)
        require(card.forDate == now().atZone(zone).toLocalDate().toString()) { "建议日期已过，请重新选择" }
        val task = suggestionTask(card)
        suggestionTaskSlots(readSnapshot(), task, zone, now())
    }

    /** Object binding is stored data; prose never participates in a write. */
    suspend fun executeSuggestion(id: String, expectedVersion: Long, action: SuggestionAction): Boolean = transaction {
        val requestId = "suggestion:$id:$expectedVersion"
        if (dao.actionFor("ASSISTANT_BATCH", requestId) != null) {
            val current = dao.suggestion(id)
            if (current?.status == "PENDING" && current.version != expectedVersion) throw StaleChange()
            return@transaction false
        }
        val card = checkedSuggestion(id, expectedVersion)
        val stamp = now().toEpochMilli()
        val task = if (action is SuggestionAction.Snooze) null else suggestionTask(card)
        assistantTransaction(requestId) {
            val after = card.copy(status = if (action is SuggestionAction.Snooze) "SNOOZED" else "APPLIED", decidedAt = stamp,
                snoozedUntil = (action as? SuggestionAction.Snooze)?.until, version = nextVersion("SUGGESTION", id, card.version))
            dao.putSuggestion(after)
            // Journal first so undo restores the task before refreshing the card's object revision.
            journal("SUGGESTION", id, card, after, "处理建议")
            when (action) {
                is SuggestionAction.Schedule -> {
                    val boundTask = requireNotNull(task)
                    val span = TimeSpan(Instant.ofEpochMilli(action.startAt), Instant.ofEpochMilli(action.endAt))
                    require(card.forDate == now().atZone(zone).toLocalDate().toString()) { "建议日期已过，请重新选择" }
                    require(span.seconds == boundTask.estimatedMinutes * 60L && action.endAt - action.startAt == boundTask.estimatedMinutes * 60_000L) { "时段长度与任务预计时长不一致" }
                    require(suggestionFreeSlots(readSnapshot(), zone, now(), boundTask.estimatedMinutes).any { span.start >= it.start && span.end <= it.end }) { "时段已开始、已有冲突或空闲不足，请换一个时段" }
                    savePlan(ScheduleBlockEntity("suggestion-plan:$id:$expectedVersion", boundTask.id, action.startAt, action.endAt), expectedTaskVersion = boundTask.version, expectedConflicts = emptyList())
                }
                is SuggestionAction.Postpone -> {
                    require(action.dueAt > stamp) { "请选择未来的截止时间" }
                    val boundTask = requireNotNull(task)
                    saveTask(boundTask.copy(dueAt = action.dueAt), boundTask.version)
                }
                SuggestionAction.StartTimer -> startTimer(requireNotNull(task).id, "suggestion-timer:$id:$expectedVersion", expectedTaskVersion = task.version)
                is SuggestionAction.Snooze -> require(action.until > stamp && action.until < (card.expiresAt ?: card.createdAt + 7L * 86400_000)) { "暂缓时间须在建议有效期内" }
            }
        }
    }

    suspend fun startTimer(taskId: String?, requestId: String = UUID.randomUUID().toString(), switchExisting: Boolean = false, expectedTaskVersion: Long? = null): TimeRecordEntity = transaction {
        dao.record(requestId)?.let { return@transaction it } // Stable request ID makes a double tap harmless.
        val task = taskId?.let { activeTask(it) }
        checkVersion(task?.version, expectedTaskVersion)
        val running = dao.runningTimer()
        if (running != null) {
            if (!switchExisting) throw TimerAlreadyRunning()
            stopTimer(running.id)
        }
        val record = TimeRecordEntity(requestId, taskId, now().toEpochMilli(), null, categoryId = task?.categoryId, source = "TIMER", createdAt = now().toEpochMilli(), updatedAt = now().toEpochMilli(), version = nextVersion("RECORD", requestId))
        dao.putRecord(record)
        journal("RECORD", record.id, null, record, "开始计时")
        record
    }

    suspend fun stopTimer(id: String, expectedVersion: Long? = null): TimeRecordEntity = transaction {
        val before = requireNotNull(dao.record(id)) { "计时记录不存在" }
        checkVersion(before.version, expectedVersion)
        if (before.endAt != null) return@transaction before
        val end = now().toEpochMilli()
        require(end > before.startAt) { "请至少计时一秒后结束" }
        val after = before.copy(endAt = end, updatedAt = end, version = nextVersion("RECORD", id, before.version))
        dao.putRecord(after)
        journal("RECORD", id, before, after, "结束计时")
        after
    }

    suspend fun deletePlan(id: String) = transaction {
        val before = requireNotNull(dao.plan(id)) { "计划不存在" }
        nextVersion("PLAN", id, before.version)
        dao.removePlan(id); journal("PLAN", id, before, null, "删除计划")
    }
    suspend fun deleteRecord(id: String, expectedVersion: Long? = null) = transaction {
        val before = requireNotNull(dao.record(id)) { "记录不存在" }
        checkVersion(before.version, expectedVersion)
        require(before.endAt != null) { "请先结束计时" }
        nextVersion("RECORD", id, before.version)
        dao.removeRecord(id); journal("RECORD", id, before, null, "删除记录")
    }
    suspend fun saveOccurrence(occurrence: CourseOccurrenceEntity, expectedVersion: Long? = null, expectedConflicts: List<Conflict>? = null) = transaction {
        TimeSpan(Instant.ofEpochMilli(occurrence.startAt), Instant.ofEpochMilli(occurrence.endAt))
        require(occurrence.status in setOf("SCHEDULED", "CANCELLED", "RESCHEDULED")) { "课程状态无效" }
        val before = requireNotNull(dao.occurrence(occurrence.id)) { "课程实例不存在" }
        checkVersion(before.version, expectedVersion)
        if (expectedConflicts != null && conflicts(TimeSpan(Instant.ofEpochMilli(occurrence.startAt), Instant.ofEpochMilli(occurrence.endAt)), occurrence.id).sortedBy { it.id } != expectedConflicts.sortedBy { it.id }) throw StaleChange()
        val after = occurrence.copy(isManuallyModified = true, updatedAt = now().toEpochMilli(), version = nextVersion("OCCURRENCE", occurrence.id, before.version))
        dao.putOccurrence(after); journal("OCCURRENCE", after.id, before, after, "修改课程实例")
    }

    suspend fun previewImport(bundle: ImportBundle): ImportPreview = transaction {
        val existing = dao.occurrences().associateBy { it.sourceOccurrenceKey }
        ImportPreview(bundle, bundle.occurrences.count { it.key !in existing }, bundle.occurrences.count { it.key in existing }, bundle.occurrences.count { existing[it.key]?.let { item -> item.isManuallyModified || item.status != "SCHEDULED" } == true })
    }

    suspend fun cancelSampleCourses(courses: List<CourseEntity>, occurrences: List<CourseOccurrenceEntity>, requestId: String) = transaction {
        require(requestId.isNotBlank() && occurrences.isNotEmpty()) { "没有可取消的示例课程" }
        dao.actionFor("COURSE_BATCH", requestId)?.let { return@transaction }
        val currentCourses = SampleCourseScope.courses(dao.courses())
        val current = SampleCourseScope.occurrences(currentCourses, dao.occurrences())
        if (currentCourses != courses.sortedBy { it.id } || current != occurrences.sortedBy { it.id }) throw StaleChange()
        val stamp = now().toEpochMilli()
        val changed = current.map { item ->
            item.copy(status = "CANCELLED", isManuallyModified = true, updatedAt = stamp,
                version = nextVersion("OCCURRENCE", item.id, item.version))
        }
        changed.forEach { dao.putOccurrence(it) }
        journal("COURSE_BATCH", requestId, CourseBatchSnapshot(current), CourseBatchSnapshot(changed), "取消 ${changed.size} 次示例课程")
    }

    suspend fun commitImport(preview: ImportPreview, requestId: String = UUID.randomUUID().toString()): ImportBatchEntity = transaction {
        dao.importBatch(requestId)?.let { return@transaction it }
        val bundle = preview.bundle
        require(bundle.courses.isNotEmpty() && bundle.occurrences.isNotEmpty()) { "空课表不能导入" }
        val term = bundle.term
        val priorTerm = dao.terms().firstOrNull { it.id == term.id }
        val termEntity = AcademicTermEntity(term.id, term.name, term.firstWeekStart.toString(), term.teachingWeeks, term.zone.id)
        require(priorTerm == null || priorTerm == termEntity) { "同一学期的起始日或时区已改变，请使用新的学期 ID" }
        dao.putTerm(termEntity)
        val stamp = now().toEpochMilli()
        val definitions = bundle.courses.associateBy { it.key }
        bundle.courses.forEach { course ->
            if (dao.course(course.key) == null) dao.putCourse(CourseEntity(course.key, course.name, course.teacher, course.location, term.id, bundle.sourceId, course.key, stamp, stamp))
        }
        var imported = 0
        bundle.occurrences.forEach { occurrence ->
            if (dao.occurrence(occurrence.key) == null) {
                val importedDefinition = definitions[occurrence.courseKey]
                val storedDefinition = dao.course(occurrence.courseKey)
                val room = occurrence.locationOverride ?: importedDefinition?.location
                val teacher = occurrence.teacherOverride ?: importedDefinition?.teacher
                dao.putOccurrence(CourseOccurrenceEntity(occurrence.key, occurrence.courseKey, occurrence.date.toString(), occurrence.span.start.toEpochMilli(), occurrence.span.end.toEpochMilli(), occurrence.key, locationOverride = room?.takeIf { it != storedDefinition?.location }, teacherOverride = teacher?.takeIf { it != storedDefinition?.teacher }, createdAt = stamp, updatedAt = stamp))
                imported++
            }
        }
        ImportBatchEntity(requestId, bundle.sourceId, startedAt = stamp, completedAt = stamp, importedCount = imported, skippedCount = bundle.occurrences.size - imported).also {
            dao.putBatch(it)
            // Import is a user edit even though it has no undo journal entry; it must retire redo.
            dao.clearRedo()
        }
    }

    suspend fun undoLastAction(expectedAssistantId: String? = null) = transaction {
        val action = requireNotNull(dao.lastAction()) { "还没有可撤销的操作" }
        if (expectedAssistantId != null && (action.entityType != "ASSISTANT_BATCH" || action.entityId != expectedAssistantId)) throw StaleChange()
        applyHistory(action, forward = false)
        dao.markUndone(action.id)
    }

    private suspend fun applyHistory(action: OperationEntity, forward: Boolean) {
        if (action.entityType.startsWith("ORG_")) {
            com.timeagent.organization.OrganizationRepository(db, clock).applyHistory(action, forward)
            return
        }
        if (action.entityType in setOf("LEDGER_ACCOUNT", "LEDGER_CATEGORY", "PAYMENT_CAPTURE", "LEDGER_TRANSACTION")) {
            com.timeagent.ledger.LedgerRepository(db, clock).applyHistory(action, forward)
            return
        }
        val target = if (forward) action.afterSnapshot else action.beforeSnapshot
        val expected = if (forward) action.beforeSnapshot else action.afterSnapshot
        suspend fun hasNotes(type: String) = dao.notes().any { it.targetType == type && it.targetId == action.entityId }
        fun matches(current: Any?, against: String? = expected) {
            // Undo keeps revisions monotonic; compare business payload, not the revision of a later undo.
            fun payload(raw: String?) = raw?.let { JsonParser.parseString(it).asJsonObject.apply {
                remove("version"); remove("updatedAt"); if (action.entityType == "REMINDER") { remove("deliveredAt");remove("__manualOff") }
                if (action.entityType == "SUGGESTION") remove("taskVersion")
                if (action.entityType == "ATTACHMENT") remove("purgedAt")
                if (action.entityType == "RECURRING_INSTANCE") { remove("taskVersion");remove("planVersion");remove("placementVersion") }
                if (action.entityType == "RECORD" && (!has("title") || get("title").isJsonNull)) addProperty("title", "")
            } }
            if (payload(current?.let { gson.toJson(it) }) != payload(against)) throw StaleChange()
        }
        when (action.entityType) {
            "ATTACHMENT" -> {
                val current=dao.attachment(action.entityId);matches(current)
                val restored=gson.fromJson(target,AttachmentEntity::class.java)
                dao.putAttachment(restored.copy(purgedAt=current?.purgedAt,version=nextVersion("ATTACHMENT",action.entityId,current?.version),updatedAt=now().toEpochMilli()))
            }

            "RECURRING_RULE" -> {
                val current=dao.recurringRule(action.entityId);matches(current)
                val revision=nextVersion("RECURRING_RULE",action.entityId,current?.version)
                if(target==null) { if(dao.recurringInstances().any{it.ruleId==action.entityId})throw StaleChange();dao.removeRecurringRule(action.entityId) }
                else { val restored=gson.fromJson(target,RecurringRuleEntity::class.java);com.timeagent.recurring.RecurringValidation.pattern(restored);dao.putRecurringRule(restored.copy(version=revision,updatedAt=now().toEpochMilli())) }
            }
            "RECURRING_INSTANCE" -> {
                val current=dao.recurringInstance(action.entityId);matches(current)
                val revision=nextVersion("RECURRING_INSTANCE",action.entityId,current?.version)
                if(target==null)dao.removeRecurringInstance(action.entityId)
                else { val restored=gson.fromJson(target,RecurringInstanceEntity::class.java);com.timeagent.recurring.RecurringValidation.instance(restored);dao.putRecurringInstance(restored.copy(version=revision)) }
            }
            "PROJECT" -> {
                val current = dao.project(action.entityId); matches(current)
                val revision = nextVersion("PROJECT",action.entityId,current?.version)
                if (target == null) {
                    if (dao.attachments().any { it.projectId == action.entityId } || dao.recurringRules().any { it.projectId == action.entityId } || dao.placements().any { it.projectId == action.entityId } || dao.weeklyGoals().any { it.projectId == action.entityId } || hasNotes("PROJECT")) throw StaleChange()
                    dao.removeProject(action.entityId)
                } else dao.putProject(gson.fromJson(target,ProjectEntity::class.java).copy(version=revision,updatedAt=now().toEpochMilli()))
            }
            "PLACEMENT" -> {
                val current = dao.placement(action.entityId); matches(current)
                val revision = nextVersion("PLACEMENT",action.entityId,current?.version)
                if (target == null) {
                    if (dao.placements().any { it.parentTaskId == action.entityId }) throw StaleChange()
                    dao.removePlacement(action.entityId)
                } else {
                    val restored = gson.fromJson(target,TaskPlacementEntity::class.java)
                    validatePlacement(restored); dao.putPlacement(restored.copy(version=revision))
                }
            }
            "GOAL" -> {
                val current = dao.weeklyGoal(action.entityId); matches(current)
                val revision = nextVersion("GOAL",action.entityId,current?.version)
                if (target == null) dao.removeWeeklyGoal(action.entityId)
                else { val restored = gson.fromJson(target,WeeklyGoalEntity::class.java); validateGoal(restored); dao.putWeeklyGoal(restored.copy(version=revision)) }
            }
            "MEMORY" -> {
                val current = dao.memory(action.entityId); matches(current)
                val revision = nextVersion("MEMORY", action.entityId, current?.version)
                if (target == null) dao.removeMemory(action.entityId)
                else dao.putMemory(gson.fromJson(target, PersonalMemoryEntity::class.java).copy(version = revision, updatedAt = now().toEpochMilli()))
            }
            "SUGGESTION" -> {
                val current = dao.suggestion(action.entityId); matches(current)
                val restored = gson.fromJson(target, AgentSuggestionEntity::class.java)
                dao.putSuggestion(restored.copy(version = nextVersion("SUGGESTION", action.entityId, current?.version),
                    taskVersion = restored.taskId?.let { dao.task(it)?.version }))
            }
            "ASSISTANT_BATCH", "IDEA_REMINDER" -> {
                val entries = gson.fromJson(action.afterSnapshot, AssistantBatch::class.java).entries
                (if (forward) entries else entries.asReversed()).forEach { applyHistory(it, forward) }
                val changedTasks=entries.filter{it.entityType=="TASK"}.map{it.entityId}.toSet()
                if(changedTasks.isNotEmpty()) dao.recurringInstances().filter{it.taskId in changedTasks && !it.manualOverride}.forEach { item ->
                    dao.putRecurringInstance(item.copy(taskVersion=dao.task(item.taskId)?.version?:item.taskVersion,
                        planVersion=dao.plan("recurring-plan:${item.id}")?.version,placementVersion=dao.placement(item.taskId)?.version))
                }
            }
            "ACTIVITY" -> {
                val current = dao.activity(action.entityId); matches(current)
                val version = nextVersion("ACTIVITY", action.entityId, current?.version)
                if (target == null) {
                    if (dao.weeklyGoals().any { it.activityId == action.entityId } || dao.activities().any { it.parentId == action.entityId } || dao.tasks().any { it.activityId == action.entityId } || hasNotes("ACTIVITY")) throw StaleChange()
                    dao.removeActivity(action.entityId)
                } else dao.putActivity(gson.fromJson(target, ActivityTemplateEntity::class.java).copy(version = version, updatedAt = now().toEpochMilli()))
            }
            "TASK" -> {
                val current = dao.task(action.entityId)
                matches(current)
                val version = nextVersion("TASK", action.entityId, current?.version)
                if (target == null) {
                    if (dao.attachments().any { it.taskId == action.entityId } || dao.recurringInstances().any { it.taskId == action.entityId } || dao.placements().any { it.taskId == action.entityId || it.parentTaskId == action.entityId } || dao.plans().any { it.taskId == action.entityId } || dao.records().any { it.taskId == action.entityId } || dao.reminders().any { it.taskId == action.entityId } || hasNotes("TASK")) throw StaleChange()
                    dao.removeTask(action.entityId)
                } else dao.putTask(gson.fromJson(target, TaskEntity::class.java).copy(version = version, updatedAt = now().toEpochMilli()))
            }
            "PLAN" -> {
                val current = dao.plan(action.entityId); matches(current)
                val version = nextVersion("PLAN", action.entityId, current?.version)
                if (target == null) { if (hasNotes("PLAN")) throw StaleChange(); dao.removePlan(action.entityId) }
                else dao.putPlan(gson.fromJson(target, ScheduleBlockEntity::class.java).copy(version = version, updatedAt = now().toEpochMilli()))
            }
            "RECORD" -> {
                val current = dao.record(action.entityId); matches(current)
                val version = nextVersion("RECORD", action.entityId, current?.version)
                val restored = target?.let { gson.fromJson(it, TimeRecordEntity::class.java) }
                if (restored != null && restored.endAt == null && dao.runningTimer()?.let { it.id != restored.id } == true) throw StaleChange()
                if (restored == null) { if (hasNotes("RECORD")) throw StaleChange(); dao.removeRecord(action.entityId) }
                else dao.putRecord(restored.copy(title = restored.title.orEmpty(), version = version, updatedAt = now().toEpochMilli()))
            }
            "OCCURRENCE" -> {
                val current = dao.occurrence(action.entityId); matches(current)
                if (target == null) { if (hasNotes("OCCURRENCE")) throw StaleChange(); dao.removeOccurrence(action.entityId) }
                else dao.putOccurrence(gson.fromJson(target, CourseOccurrenceEntity::class.java).copy(version = nextVersion("OCCURRENCE", action.entityId, current?.version), updatedAt = now().toEpochMilli()))
            }
            "COURSE_BATCH" -> {
                val from = gson.fromJson(expected, CourseBatchSnapshot::class.java).occurrences
                val to = gson.fromJson(target, CourseBatchSnapshot::class.java).occurrences
                val current = from.associate { it.id to dao.occurrence(it.id) }
                from.forEach { matches(current[it.id], gson.toJson(it)) }
                to.forEach { item -> dao.putOccurrence(item.copy(version = nextVersion("OCCURRENCE", item.id, current[item.id]?.version), updatedAt = now().toEpochMilli())) }
            }
            "IDEA" -> {
                val current = dao.idea(action.entityId); matches(current)
                val version = nextVersion("IDEA", action.entityId, current?.version)
                if (target == null) { if (hasNotes("IDEA")) throw StaleChange(); dao.removeIdea(action.entityId) }
                else dao.putIdea(gson.fromJson(target, IdeaEntity::class.java).copy(version = version, updatedAt = now().toEpochMilli()))
            }
            "NOTE" -> {
                val current = dao.note(action.entityId); matches(current)
                val version = nextVersion("NOTE", action.entityId, current?.version)
                if (target == null) dao.removeNote(action.entityId) else {
                    val note = gson.fromJson(target, NoteEntity::class.java)
                    if (!noteTargetExists(note.targetType, note.targetId)) throw StaleChange()
                    dao.putNote(note.copy(version = version, updatedAt = now().toEpochMilli()))
                }
            }
            "REMINDER" -> {
                val current = dao.reminder(action.entityId); matches(current)
                val policyKey="REMINDER_MANUAL_OFF:${action.entityId}"
                fun policy(raw:String?):Long?=raw?.let{JsonParser.parseString(it).asJsonObject.get("__manualOff")?.takeUnless{it.isJsonNull}?.asLong}
                policy(expected)?.let{if((dao.revision(policyKey)?.revision?:0L)!=it)throw StaleChange()}
                val restoredPolicy=policy(target)?:if(target==null)0L else if(gson.fromJson(target,ReminderEntity::class.java).enabled)0L else 1L
                require(restoredPolicy in 0L..1L)
                dao.putRevision(EntityRevision(policyKey,restoredPolicy))
                val version = nextVersion("REMINDER", action.entityId, current?.version)
                if (target == null) { if (hasNotes("REMINDER")) throw StaleChange(); dao.removeReminder(action.entityId) }
                else {
                    val item = gson.fromJson(target, ReminderEntity::class.java)
                    val delivered = dao.revision(reminderDeliveryKey(item.id, item.remindAt))?.revision ?: current?.deliveredAt?.takeIf { current.remindAt == item.remindAt } ?: item.deliveredAt
                    dao.putReminder(item.copy(version = version, deliveredAt = delivered, updatedAt = now().toEpochMilli()))
                }
            }
            else -> error("此操作不能撤销")
        }
    }
}
