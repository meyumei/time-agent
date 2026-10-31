package com.timeagent.organization

import com.google.gson.Gson
import com.timeagent.data.ActivityTemplateEntity
import com.timeagent.data.CategoryEntity
import com.timeagent.data.HealthSyncItemEntity
import com.timeagent.data.OperationEntity
import com.timeagent.data.RecordTagEntity
import com.timeagent.data.StaleChange
import com.timeagent.data.TagEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TaskTagEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TimeRepository
import java.time.Clock
import java.util.UUID

private const val MAX_CLASSIFICATION_NAME = 40

/** Everything one screen needs to render classifications and the items they apply to. */
data class OrganizationSnapshot(
    val categories: List<CategoryEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val records: List<TimeRecordEntity> = emptyList(),
    val activities: List<ActivityTemplateEntity> = emptyList(),
    val taskTagIds: Map<String, Set<String>> = emptyMap(),
    val recordTagIds: Map<String, Set<String>> = emptyMap(),
    val history: List<OperationEntity> = emptyList(),
)

/** Snapshot of one side of a task/record tag relation set, used by the undo journal. */
data class OrgTagLinks(val ids: List<String> = emptyList())

data class MergePreview(
    val recordIds: List<String>,
    val versions: Map<String, Long>,
    val firstId: String,
    val startAt: Long,
    val endAt: Long,
    val originalTotalMillis: Long,
    val title: String,
    val notes: String,
    val tagIds: Set<String>,
    val movesNote: Boolean,
)

data class SplitPreview(
    val recordId: String,
    val startAt: Long,
    val splitAt: Long,
    val endAt: Long,
    val firstMillis: Long,
    val secondMillis: Long,
)

/**
 * Categories, tags and time-record tidying. Every mutation goes through TimeRepository.transaction
 * (PersonalDataGate + one database transaction) and journals through TimeRepository.journal.
 * Multi-entity mutations reuse assistantTransaction so one undo restores the whole action.
 *
 * Operation types owned here: ORG_CATEGORY, ORG_TAG, ORG_TASK_TAGS, ORG_RECORD_TAGS,
 * ORG_HEALTH_ITEM. Task/record/activity/note field edits journal as TASK/RECORD/ACTIVITY/NOTE,
 * so the existing undo code handles them. Grouped journal order is chosen so reversed undo
 * restores foreign-key parents (categories, tags, records) before their children.
 */
class OrganizationRepository(
    private val db: TimeAgentDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val repo = TimeRepository(db, clock)
    private val dao = db.dao()
    private val orgDao = db.organizationDao()
    private val gson = Gson()

    suspend fun snapshot(): OrganizationSnapshot = repo.transaction {
        OrganizationSnapshot(
            categories = orgDao.categories(),
            tags = orgDao.tags(),
            tasks = dao.tasks(),
            records = dao.records().sortedByDescending { it.startAt },
            activities = dao.activities(),
            taskTagIds = orgDao.taskTags().groupBy({ it.taskId }, { it.tagId }).mapValues { it.value.toSet() },
            recordTagIds = orgDao.recordTags().groupBy({ it.recordId }, { it.tagId }).mapValues { it.value.toSet() },
            history = dao.history(),
        )
    }

    // ---------------------------------------------------------------- categories

    suspend fun createCategory(name: String, color: Long): CategoryEntity = repo.transaction {
        val clean = cleanName(name, "分类")
        val all = orgDao.categories()
        require(!categoryNameTaken(all, clean)) { "分类名称已存在，请换一个名称" }
        val item = CategoryEntity(
            id = UUID.randomUUID().toString(),
            name = clean,
            color = color,
            sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        repo.nextVersion("ORG_CATEGORY", item.id)
        orgDao.putCategory(item)
        repo.journal("ORG_CATEGORY", item.id, null, item, "新建分类")
        item
    }

    suspend fun updateCategory(id: String, name: String, color: Long): CategoryEntity = repo.transaction {
        val before = requireNotNull(orgDao.category(id)) { "分类不存在，请关闭后重新打开" }
        val clean = cleanName(name, "分类")
        require(!categoryNameTaken(orgDao.categories(), clean, id)) { "分类名称已存在，请换一个名称" }
        if (before.name == clean && before.color == color) return@transaction before
        val after = before.copy(name = clean, color = color)
        repo.nextVersion("ORG_CATEGORY", id)
        orgDao.putCategory(after)
        repo.journal("ORG_CATEGORY", id, before, after, "修改分类")
        after
    }

    suspend fun setCategoryArchived(id: String, archived: Boolean): Unit = repo.transaction {
        val before = requireNotNull(orgDao.category(id)) { "分类不存在，请关闭后重新打开" }
        if (before.isArchived == archived) return@transaction
        val after = before.copy(isArchived = archived)
        repo.nextVersion("ORG_CATEGORY", id)
        orgDao.putCategory(after)
        repo.journal("ORG_CATEGORY", id, before, after, if (archived) "归档分类" else "恢复分类")
    }

    suspend fun setCategoriesArchived(ids: List<String>, archived: Boolean): Int = repo.transaction {
        require(ids.isNotEmpty()) { "请选择要操作分类" }
        val items = ids.distinct().map { requireNotNull(orgDao.category(it)) { "分类不存在，请关闭后重新打开" } }
        val changed = items.filter { it.isArchived != archived }
        if (changed.isEmpty()) return@transaction 0
        val request = UUID.randomUUID().toString()
        repo.assistantTransaction(request) {
            changed.forEach { before ->
                val after = before.copy(isArchived = archived)
                repo.nextVersion("ORG_CATEGORY", before.id)
                orgDao.putCategory(after)
                repo.journal("ORG_CATEGORY", before.id, before, after, if (archived) "批量归档分类" else "批量恢复分类")
            }
        }
        changed.size
    }

    /**
     * Deletes one or more categories. Tasks, actual records and activity templates are never
     * deleted: they either move to [moveToId] or lose the category (detach). `moveToId == null`
     * means detach. The category journal entries are written after the repointing entries, so
     * reversed undo recreates the category before the children point at it again.
     */
    suspend fun deleteCategories(ids: List<String>, moveToId: String?): Int = repo.transaction {
        val distinct = ids.distinct()
        require(distinct.isNotEmpty()) { "请选择要删除的分类" }
        val deleting = distinct.map { requireNotNull(orgDao.category(it)) { "分类不存在，请关闭后重新打开" } }
        val target = moveToId?.let { id ->
            require(id !in distinct) { "不能把内容移动到正在删除的分类" }
            requireNotNull(orgDao.category(id)) { "目标分类不存在，请关闭后重新打开" }
        }
        val stamp = repo.now().toEpochMilli()
        val action = if (target == null) "删除分类时取消归类（内容保留）" else "删除分类时转移到「${target.name}」"
        val request = UUID.randomUUID().toString()
        repo.assistantTransaction(request) {
            repointCategory(distinct.toSet(), target, stamp, action)
            deleting.forEach { before ->
                repo.nextVersion("ORG_CATEGORY", before.id)
                orgDao.removeCategory(before.id)
                repo.journal("ORG_CATEGORY", before.id, before, null, "删除分类")
            }
        }
        deleting.size
    }

    /** Merges [sourceIds] into [targetId]; the target category stays, source categories are removed. */
    suspend fun mergeCategories(sourceIds: List<String>, targetId: String): Int = repo.transaction {
        val sources = sourceIds.distinct().filter { it != targetId }
        require(sources.isNotEmpty()) { "请选择要合并的分类（至少一个不同于目标分类）" }
        val target = requireNotNull(orgDao.category(targetId)) { "目标分类不存在，请关闭后重新打开" }
        require(!target.isArchived) { "目标分类已归档，请先恢复后再合并" }
        val sourceItems = sources.map { requireNotNull(orgDao.category(it)) { "分类不存在，请关闭后重新打开" } }
        val stamp = repo.now().toEpochMilli()
        val request = UUID.randomUUID().toString()
        repo.assistantTransaction(request) {
            repointCategory(sources.toSet(), target, stamp, "合并分类到「${target.name}」")
            sourceItems.forEach { before ->
                repo.nextVersion("ORG_CATEGORY", before.id)
                orgDao.removeCategory(before.id)
                repo.journal("ORG_CATEGORY", before.id, before, null, "合并分类")
            }
        }
        sources.size
    }

    private suspend fun repointCategory(categoryIds: Set<String>, target: CategoryEntity?, stamp: Long, action: String) {
        dao.tasks().filter { it.categoryId in categoryIds }.forEach { before ->
            val after = before.copy(
                categoryId = target?.id,
                updatedAt = stamp,
                version = repo.nextVersion("TASK", before.id, before.version),
            )
            dao.putTask(after)
            repo.journal("TASK", before.id, before, after, action)
        }
        dao.records().filter { it.categoryId in categoryIds }.forEach { before ->
            val after = before.copy(
                categoryId = target?.id,
                isManuallyEdited = true,
                updatedAt = stamp,
                version = repo.nextVersion("RECORD", before.id, before.version),
            )
            dao.putRecord(after)
            repo.journal("RECORD", before.id, before, after, action)
        }
        dao.activities().filter { it.categoryId in categoryIds }.forEach { before ->
            val after = before.copy(
                categoryId = target?.id,
                updatedAt = stamp,
                version = repo.nextVersion("ACTIVITY", before.id, before.version),
            )
            dao.putActivity(after)
            repo.journal("ACTIVITY", before.id, before, after, action)
        }
    }

    // --------------------------------------------------------------------- tags

    suspend fun createTag(name: String, color: Long): TagEntity = repo.transaction {
        val clean = cleanName(name, "标签")
        require(!tagNameTaken(orgDao.tags(), clean)) { "标签名称已存在，请换一个名称" }
        val item = TagEntity(UUID.randomUUID().toString(), clean, color)
        repo.nextVersion("ORG_TAG", item.id)
        orgDao.putTag(item)
        repo.journal("ORG_TAG", item.id, null, item, "新建标签")
        item
    }

    suspend fun updateTag(id: String, name: String, color: Long): TagEntity = repo.transaction {
        val before = requireNotNull(orgDao.tag(id)) { "标签不存在，请关闭后重新打开" }
        val clean = cleanName(name, "标签")
        require(!tagNameTaken(orgDao.tags(), clean, id)) { "标签名称已存在，请换一个名称" }
        if (before.name == clean && before.color == color) return@transaction before
        val after = before.copy(name = clean, color = color)
        repo.nextVersion("ORG_TAG", id)
        orgDao.putTag(after)
        repo.journal("ORG_TAG", id, before, after, "修改标签")
        after
    }

    /**
     * Deletes tags only; tasks, actual records and notes stay untouched. Existing assignments are
     * journalled first and the tag rows last, so undo recreates the tag before restoring links.
     */
    suspend fun deleteTags(ids: List<String>): Int = repo.transaction {
        val distinct = ids.distinct()
        require(distinct.isNotEmpty()) { "请选择要删除的标签" }
        val deleting = distinct.map { requireNotNull(orgDao.tag(it)) { "标签不存在，请关闭后重新打开" } }
        val idSet = distinct.toSet()
        // Only the affected owners, but the full link set of each owner: journalling just the
        // deleted links would wipe every other tag on the same task/record.
        val taskIds = orgDao.taskTags().filter { it.tagId in idSet }.map { it.taskId }.distinct()
        val recordIds = orgDao.recordTags().filter { it.tagId in idSet }.map { it.recordId }.distinct()
        val request = UUID.randomUUID().toString()
        repo.assistantTransaction(request) {
            taskIds.forEach { taskId ->
                val before = taskTagIds(taskId)
                replaceTaskTags(taskId, before, before - idSet, "删除标签时移除任务标签")
            }
            recordIds.forEach { recordId ->
                val before = recordTagIds(recordId)
                replaceRecordTags(recordId, before, before - idSet, "删除标签时移除记录标签")
            }
            deleting.forEach { before ->
                repo.nextVersion("ORG_TAG", before.id)
                orgDao.removeTag(before.id)
                repo.journal("ORG_TAG", before.id, before, null, "删除标签")
            }
        }
        deleting.size
    }

    /** Merges [sourceIds] into [targetId]: assignments keep working through the target tag. */
    suspend fun mergeTags(sourceIds: List<String>, targetId: String): Int = repo.transaction {
        val sources = sourceIds.distinct().filter { it != targetId }
        require(sources.isNotEmpty()) { "请选择要合并的标签（至少一个不同于目标标签）" }
        val target = requireNotNull(orgDao.tag(targetId)) { "目标标签不存在，请关闭后重新打开" }
        val sourceTags = sources.map { requireNotNull(orgDao.tag(it)) { "标签不存在，请关闭后重新打开" } }
        val idSet = sources.toSet()
        val taskIds = orgDao.taskTags().filter { it.tagId in idSet }.map { it.taskId }.distinct()
        val recordIds = orgDao.recordTags().filter { it.tagId in idSet }.map { it.recordId }.distinct()
        val request = UUID.randomUUID().toString()
        repo.assistantTransaction(request) {
            taskIds.forEach { taskId ->
                val before = taskTagIds(taskId)
                replaceTaskTags(taskId, before, (before - idSet) + targetId, "合并标签到「${target.name}」")
            }
            recordIds.forEach { recordId ->
                val before = recordTagIds(recordId)
                replaceRecordTags(recordId, before, (before - idSet) + targetId, "合并标签到「${target.name}」")
            }
            sourceTags.forEach { before ->
                repo.nextVersion("ORG_TAG", before.id)
                orgDao.removeTag(before.id)
                repo.journal("ORG_TAG", before.id, before, null, "合并标签")
            }
        }
        sources.size
    }

    /** Adds and/or removes tags on selected tasks/records as one undoable action. */
    suspend fun assignTags(
        taskIds: List<String>,
        recordIds: List<String>,
        addTagIds: Set<String>,
        removeTagIds: Set<String>,
    ): Int = repo.transaction {
        require(taskIds.isNotEmpty() || recordIds.isNotEmpty()) { "请先选择任务或记录" }
        require(addTagIds.isNotEmpty() || removeTagIds.isNotEmpty()) { "请选择要添加或移除的标签" }
        require(addTagIds.intersect(removeTagIds).isEmpty()) { "同一个标签不能同时添加和移除" }
        addTagIds.forEach { require(orgDao.tag(it) != null) { "标签不存在，请关闭后重新打开" } }
        val tasks = taskIds.distinct().map { requireNotNull(dao.task(it)) { "任务不存在，请关闭后重新打开" } }
        val records = recordIds.distinct().map { record ->
            requireNotNull(dao.record(record)) { "记录不存在，请关闭后重新打开" }
        }
        val taskChanges = tasks.mapNotNull { task ->
            val before = taskTagIds(task.id)
            val after = (before - removeTagIds) + addTagIds
            if (after == before) null else Triple(task.id, before, after)
        }
        val recordChanges = records.mapNotNull { record ->
            val before = recordTagIds(record.id)
            val after = (before - removeTagIds) + addTagIds
            if (after == before) null else Triple(record.id, before, after)
        }
        require(taskChanges.isNotEmpty() || recordChanges.isNotEmpty()) { "所选内容已经符合这些标签，没有需要修改的项目" }
        val request = UUID.randomUUID().toString()
        repo.assistantTransaction(request) {
            taskChanges.forEach { (taskId, before, after) -> replaceTaskTags(taskId, before, after, "批量编辑任务标签") }
            recordChanges.forEach { (recordId, before, after) -> replaceRecordTags(recordId, before, after, "批量编辑记录标签") }
        }
        taskChanges.size + recordChanges.size
    }

    /** Bulk category edit for selected tasks and actual records. `categoryId == null` detaches. */
    suspend fun setCategoryFor(taskIds: List<String>, recordIds: List<String>, categoryId: String?): Int = repo.transaction {
        require(taskIds.isNotEmpty() || recordIds.isNotEmpty()) { "请先选择任务或记录" }
        val target = categoryId?.let { id ->
            requireNotNull(orgDao.category(id)) { "分类不存在，请关闭后重新打开" }
                .also { require(!it.isArchived) { "分类已归档，请先恢复分类" } }
        }
        val stamp = repo.now().toEpochMilli()
        val tasks = taskIds.distinct().map { requireNotNull(dao.task(it)) { "任务不存在，请关闭后重新打开" } }
            .filter { it.categoryId != target?.id }
        val records = recordIds.distinct().map { requireNotNull(dao.record(it)) { "记录不存在，请关闭后重新打开" } }
            .filter { it.categoryId != target?.id }
        require(tasks.isNotEmpty() || records.isNotEmpty()) { "所选内容已经是这个分类，没有需要修改的项目" }
        val request = UUID.randomUUID().toString()
        val action = if (target == null) "批量取消分类" else "批量设置分类为「${target.name}」"
        repo.assistantTransaction(request) {
            tasks.forEach { before ->
                val after = before.copy(
                    categoryId = target?.id,
                    updatedAt = stamp,
                    version = repo.nextVersion("TASK", before.id, before.version),
                )
                dao.putTask(after)
                repo.journal("TASK", before.id, before, after, action)
            }
            records.forEach { before ->
                val after = before.copy(
                    categoryId = target?.id,
                    isManuallyEdited = true,
                    updatedAt = stamp,
                    version = repo.nextVersion("RECORD", before.id, before.version),
                )
                dao.putRecord(after)
                repo.journal("RECORD", before.id, before, after, action)
            }
        }
        tasks.size + records.size
    }

    private suspend fun replaceTaskTags(taskId: String, before: Set<String>, after: Set<String>, action: String) {
        if (before == after) return
        orgDao.clearTaskTags(taskId)
        after.sorted().forEach { orgDao.addTaskTag(TaskTagEntity(taskId, it)) }
        repo.journal("ORG_TASK_TAGS", taskId, OrgTagLinks(before.sorted()), OrgTagLinks(after.sorted()), action)
    }

    private suspend fun replaceRecordTags(recordId: String, before: Set<String>, after: Set<String>, action: String) {
        if (before == after) return
        orgDao.clearRecordTags(recordId)
        after.sorted().forEach { orgDao.addRecordTag(RecordTagEntity(recordId, it)) }
        repo.journal("ORG_RECORD_TAGS", recordId, OrgTagLinks(before.sorted()), OrgTagLinks(after.sorted()), action)
    }

    // ------------------------------------------------------------ record merge

    suspend fun previewMerge(recordIds: List<String>): MergePreview = repo.transaction {
        val ids = recordIds.distinct()
        require(ids.size >= 2) { "请选择至少两条已完成记录再合并" }
        val records = ids.map { requireNotNull(dao.record(it)) { "记录不存在，请关闭后重新打开" } }
        require(records.all { it.endAt != null }) { "计时中的记录不能合并，请先结束计时" }
        val taskId = records.first().taskId
        val categoryId = records.first().categoryId
        require(records.all { it.taskId == taskId && it.categoryId == categoryId }) { "只能合并同一任务、同一分类的记录" }
        val sorted = records.sortedWith(compareBy({ it.startAt }, { it.id }))
        var cursor = sorted.first().startAt
        sorted.forEachIndexed { index, record ->
            if (index > 0) require(record.startAt <= cursor) { "所选记录时间不连续，中间有空档；合并不会自动填补空档" }
            cursor = maxOf(cursor, record.endAt ?: cursor)
        }
        val start = sorted.first().startAt
        val end = sorted.maxOf { it.endAt ?: it.startAt }
        val notes = dao.notes().filter { it.targetType == "RECORD" && it.targetId in ids }
        require(notes.size <= 1) { "所选记录带有多条独立笔记，暂不支持合并；请先整理笔记后再试" }
        MergePreview(
            recordIds = sorted.map { it.id },
            versions = records.associate { it.id to it.version },
            firstId = sorted.first().id,
            startAt = start,
            endAt = end,
            originalTotalMillis = sorted.sumOf { (it.endAt ?: it.startAt) - it.startAt },
            title = sorted.map { it.title.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" / "),
            notes = sorted.map { it.notes.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n\n"),
            tagIds = sorted.flatMap { recordTagIds(it.id) }.toSet(),
            movesNote = notes.singleOrNull()?.targetId?.let { it != sorted.first().id } == true,
        )
    }

    /**
     * Merges finished records of the same task/category into the earliest one. The earliest id is
     * kept; title and note text are preserved, tags are unioned, and a single attached note is
     * moved to the kept record (multiple notes are rejected by previewMerge).
     *
     * Synced sources are protected: the kept record becomes manually edited so a later health
     * sync cannot overwrite the merged span, and every health item pointing at a removed record is
     * marked userDeleted with recordId cleared so the next sync cannot recreate it.
     */
    suspend fun mergeRecords(recordIds: List<String>, expectedVersions: Map<String, Long>): String = repo.transaction {
        val preview = previewMerge(recordIds)
        preview.recordIds.forEach { id ->
            val current = requireNotNull(dao.record(id)) { "记录不存在，请关闭后重新打开" }
            val expected = expectedVersions[id] ?: throw StaleChange()
            if (current.version != expected) throw StaleChange()
        }
        val records = preview.recordIds.map { requireNotNull(dao.record(it)) }
        val first = records.first()
        val others = records.drop(1)
        val stamp = repo.now().toEpochMilli()
        val request = UUID.randomUUID().toString()
        repo.assistantTransaction(request) {
            val after = first.copy(
                startAt = preview.startAt,
                endAt = preview.endAt,
                title = preview.title,
                notes = preview.notes,
                isManuallyEdited = true,
                updatedAt = stamp,
                version = repo.nextVersion("RECORD", first.id, first.version),
            )
            dao.putRecord(after)
            repo.journal("RECORD", first.id, first, after, "合并实际记录")
            val unionTags = records.flatMap { recordTagIds(it.id) }.toSet()
            replaceRecordTags(first.id, recordTagIds(first.id), unionTags, "合并记录时合并标签")
            val movingNote = dao.notes().firstOrNull { it.targetType == "RECORD" && it.targetId in others.map { r -> r.id } }
            if (movingNote != null) {
                val moved = movingNote.copy(
                    targetId = first.id,
                    updatedAt = stamp,
                    version = repo.nextVersion("NOTE", movingNote.id, movingNote.version),
                )
                dao.putNote(moved)
                repo.journal("NOTE", movingNote.id, movingNote, moved, "合并记录时移动笔记")
            }
            others.forEach { other ->
                orgDao.healthItemsForRecord(other.id).forEach { item ->
                    val afterItem = item.copy(recordId = null, userDeleted = true, lastSyncedAt = stamp)
                    dao.putHealthItem(afterItem)
                    repo.journal("ORG_HEALTH_ITEM", item.id, item, afterItem, "合并记录时停用同步来源")
                }
            }
            others.forEach { other ->
                repo.nextVersion("RECORD", other.id, other.version)
                val otherTags = recordTagIds(other.id)
                // Journal before removal: deleting a record cascades its tag rows, and undo must
                // restore them after the record itself is recreated.
                replaceRecordTags(other.id, otherTags, emptySet(), "合并记录时移除被合并记录标签")
                dao.removeRecord(other.id)
                repo.journal("RECORD", other.id, other, null, "合并到保留记录")
            }
        }
        preview.firstId
    }

    // ------------------------------------------------------------ record split

    suspend fun previewSplit(recordId: String, splitAtMillis: Long): SplitPreview = repo.transaction {
        val record = requireNotNull(dao.record(recordId)) { "记录不存在，请关闭后重新打开" }
        val end = requireNotNull(record.endAt) { "计时中的记录不能拆分，请先结束计时" }
        require(splitAtMillis > record.startAt && splitAtMillis < end) { "拆分时间必须严格位于记录开始和结束之间" }
        SplitPreview(recordId, record.startAt, splitAtMillis, end, splitAtMillis - record.startAt, end - splitAtMillis)
    }

    /**
     * Splits one finished record. The original id keeps the first half; a new id holds the second.
     * Tags, task, category, title, notes and source are copied. Both halves are manual edits, so a
     * health-sync source can no longer overwrite the original record. Plans are never touched.
     */
    suspend fun splitRecord(recordId: String, expectedVersion: Long, splitAtMillis: Long): String = repo.transaction {
        val preview = previewSplit(recordId, splitAtMillis)
        val before = requireNotNull(dao.record(recordId))
        if (before.version != expectedVersion) throw StaleChange()
        val stamp = repo.now().toEpochMilli()
        val newId = UUID.randomUUID().toString()
        val firstHalf = before.copy(
            endAt = preview.splitAt,
            isManuallyEdited = true,
            updatedAt = stamp,
            version = repo.nextVersion("RECORD", recordId, before.version),
        )
        val secondHalf = before.copy(
            id = newId,
            startAt = preview.splitAt,
            endAt = preview.endAt,
            isManuallyEdited = true,
            createdAt = stamp,
            updatedAt = stamp,
            version = repo.nextVersion("RECORD", newId),
        )
        val tags = recordTagIds(recordId)
        val request = UUID.randomUUID().toString()
        repo.assistantTransaction(request) {
            dao.putRecord(firstHalf)
            repo.journal("RECORD", recordId, before, firstHalf, "拆分实际记录（前半段）")
            dao.putRecord(secondHalf)
            repo.journal("RECORD", newId, null, secondHalf, "拆分实际记录（后半段）")
            if (tags.isNotEmpty()) replaceRecordTags(newId, emptySet(), tags, "拆分记录时复制标签")
            dao.notes().firstOrNull { it.targetType == "RECORD" && it.targetId == recordId }?.let { note ->
                repo.saveNote(note.copy(id = UUID.randomUUID().toString(), targetId = newId, createdAt = stamp))
            }
        }
        newId
    }

    // ------------------------------------------------------------------ history

    /**
     * Undo/redo for the operation types owned by this repository. Called by TimeRepository
     * .applyHistory for any entityType starting with ORG_, inside the same transaction.
     */
    suspend fun applyHistory(action: OperationEntity, forward: Boolean) {
        val target = if (forward) action.afterSnapshot else action.beforeSnapshot
        val expected = if (forward) action.beforeSnapshot else action.afterSnapshot
        when (action.entityType) {
            "ORG_CATEGORY" -> applyCategoryHistory(action.entityId, target, expected)
            "ORG_TAG" -> applyTagHistory(action.entityId, target, expected)
            "ORG_TASK_TAGS" -> applyTaskTagsHistory(action.entityId, target, expected)
            "ORG_RECORD_TAGS" -> applyRecordTagsHistory(action.entityId, target, expected)
            "ORG_HEALTH_ITEM" -> applyHealthItemHistory(action.entityId, target, expected)
            else -> throw IllegalStateException("此操作不能撤销")
        }
    }

    private suspend fun applyCategoryHistory(id: String, target: String?, expected: String?) {
        val current = orgDao.category(id)
        val expectedItem = expected?.let { gson.fromJson(it, CategoryEntity::class.java) }
        if (!categoryMatches(current, expectedItem)) throw StaleChange()
        if (target == null) {
            val stillUsed = dao.tasks().any { it.categoryId == id } ||
                dao.records().any { it.categoryId == id } ||
                dao.activities().any { it.categoryId == id }
            if (stillUsed) throw StaleChange()
            orgDao.removeCategory(id)
        } else {
            val restored = gson.fromJson(target, CategoryEntity::class.java)
            if (categoryNameTaken(orgDao.categories(), restored.name, restored.id)) throw StaleChange()
            repo.nextVersion("ORG_CATEGORY", restored.id)
            orgDao.putCategory(restored)
        }
    }

    private suspend fun applyTagHistory(id: String, target: String?, expected: String?) {
        val current = orgDao.tag(id)
        val expectedItem = expected?.let { gson.fromJson(it, TagEntity::class.java) }
        if (!tagMatches(current, expectedItem)) throw StaleChange()
        if (target == null) {
            val stillUsed = orgDao.taskTags().any { it.tagId == id } || orgDao.recordTags().any { it.tagId == id }
            if (stillUsed) throw StaleChange()
            orgDao.removeTag(id)
        } else {
            val restored = gson.fromJson(target, TagEntity::class.java)
            if (tagNameTaken(orgDao.tags(), restored.name, restored.id)) throw StaleChange()
            repo.nextVersion("ORG_TAG", restored.id)
            orgDao.putTag(restored)
        }
    }

    private suspend fun applyTaskTagsHistory(taskId: String, target: String?, expected: String?) {
        val expectedIds = parseLinks(expected)
        val targetIds = parseLinks(target)
        if (taskTagIds(taskId) != expectedIds) throw StaleChange()
        if (dao.task(taskId) == null) throw StaleChange()
        if (targetIds.any { orgDao.tag(it) == null }) throw StaleChange()
        orgDao.clearTaskTags(taskId)
        targetIds.sorted().forEach { orgDao.addTaskTag(TaskTagEntity(taskId, it)) }
    }

    private suspend fun applyRecordTagsHistory(recordId: String, target: String?, expected: String?) {
        val expectedIds = parseLinks(expected)
        val targetIds = parseLinks(target)
        if (recordTagIds(recordId) != expectedIds) throw StaleChange()
        if (targetIds.isNotEmpty() && dao.record(recordId) == null) throw StaleChange()
        if (targetIds.any { orgDao.tag(it) == null }) throw StaleChange()
        orgDao.clearRecordTags(recordId)
        targetIds.sorted().forEach { orgDao.addRecordTag(RecordTagEntity(recordId, it)) }
    }

    private suspend fun applyHealthItemHistory(id: String, target: String?, expected: String?) {
        val current = dao.healthItem(id)
        val expectedItem = expected?.let { gson.fromJson(it, HealthSyncItemEntity::class.java) }
        if (!healthItemMatches(current, expectedItem)) throw StaleChange()
        if (target == null) {
            dao.removeHealthItem(id)
            return
        }
        val restored = gson.fromJson(target, HealthSyncItemEntity::class.java)
        if (restored.recordId != null && dao.record(restored.recordId) == null) throw StaleChange()
        dao.putHealthItem(restored)
    }

    // ------------------------------------------------------------------ helpers

    private fun parseLinks(raw: String?): Set<String> =
        raw?.let { gson.fromJson(it, OrgTagLinks::class.java)?.ids?.toSet() } ?: emptySet()

    private suspend fun taskTagIds(taskId: String): Set<String> =
        orgDao.taskTags().filter { it.taskId == taskId }.map { it.tagId }.toSet()

    private suspend fun recordTagIds(recordId: String): Set<String> =
        orgDao.recordTags().filter { it.recordId == recordId }.map { it.tagId }.toSet()

    private fun cleanName(raw: String, label: String): String {
        val name = raw.trim()
        require(name.isNotEmpty()) { "请填写${label}名称" }
        require(name.length <= MAX_CLASSIFICATION_NAME) { "${label}名称最多${MAX_CLASSIFICATION_NAME}字" }
        return name
    }

    private fun categoryNameTaken(all: List<CategoryEntity>, name: String, exceptId: String? = null) =
        all.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    private fun tagNameTaken(all: List<TagEntity>, name: String, exceptId: String? = null) =
        all.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    private fun categoryMatches(current: CategoryEntity?, expected: CategoryEntity?): Boolean {
        if (current == null || expected == null) return current == null && expected == null
        return current.name == expected.name && current.color == expected.color &&
            current.sortOrder == expected.sortOrder && current.isArchived == expected.isArchived
    }

    private fun tagMatches(current: TagEntity?, expected: TagEntity?): Boolean {
        if (current == null || expected == null) return current == null && expected == null
        return current.name == expected.name && current.color == expected.color
    }

    /** lastSyncedAt moves during normal sync and is intentionally not compared. */
    private fun healthItemMatches(current: HealthSyncItemEntity?, expected: HealthSyncItemEntity?): Boolean {
        if (current == null || expected == null) return current == null && expected == null
        return current.kind == expected.kind && current.title == expected.title &&
            current.startAt == expected.startAt && current.endAt == expected.endAt &&
            current.originPackage == expected.originPackage && current.recordId == expected.recordId &&
            current.userDeleted == expected.userDeleted
    }
}
