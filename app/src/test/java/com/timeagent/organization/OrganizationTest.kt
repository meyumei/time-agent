package com.timeagent.organization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.NoteEntity
import com.timeagent.data.HealthSyncItemEntity
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OrganizationTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private lateinit var org: OrganizationRepository
    private val now = Instant.parse("2026-10-19T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setup() {
        db = TimeAgentDatabase.openInMemory(context)
        repo = TimeRepository(db, clock)
        org = OrganizationRepository(db, clock)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `category merge relinks tasks and undo restores the source category`() = runBlocking {
        val source = org.createCategory("阅读", 0xFF5756B7)
        val target = org.createCategory("学习", 0xFF3978B5)
        repo.saveTask(TaskEntity("task-1", "章节笔记", categoryId = source.id))

        assertEquals(1, org.mergeCategories(listOf(source.id), target.id))
        assertEquals(target.id, db.dao().task("task-1")!!.categoryId)
        assertNull(db.organizationDao().category(source.id))

        repo.undoLastAction()
        assertEquals(source.id, db.dao().task("task-1")!!.categoryId)
        assertEquals("阅读", db.organizationDao().category(source.id)!!.name)

        repo.redoLastAction()
        assertEquals(target.id, db.dao().task("task-1")!!.categoryId)
        assertNull(db.organizationDao().category(source.id))
    }

    @Test
    fun `tag merge moves task and record links and undo restores both`() = runBlocking {
        val source = org.createTag("深度", 0xFF3978B5)
        val target = org.createTag("专注", 0xFF26806A)
        repo.saveTask(TaskEntity("task-1", "写报告"))
        repo.saveRecord(
            TimeRecordEntity(
                "r1", "task-1",
                now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli(),
                title = "写报告",
            )
        )
        org.assignTags(listOf("task-1"), listOf("r1"), setOf(source.id), emptySet())

        assertEquals(1, org.mergeTags(listOf(source.id), target.id))
        val taskLinks = db.organizationDao().taskTags().filter { it.taskId == "task-1" }.map { it.tagId }.toSet()
        val recordLinks = db.organizationDao().recordTags().filter { it.recordId == "r1" }.map { it.tagId }.toSet()
        assertEquals(setOf(target.id), taskLinks)
        assertEquals(setOf(target.id), recordLinks)
        assertNull(db.organizationDao().tag(source.id))

        repo.undoLastAction()
        assertEquals(setOf(source.id), db.organizationDao().taskTags().filter { it.taskId == "task-1" }.map { it.tagId }.toSet())
        assertEquals(setOf(source.id), db.organizationDao().recordTags().filter { it.recordId == "r1" }.map { it.tagId }.toSet())
        assertEquals("深度", db.organizationDao().tag(source.id)!!.name)

        repo.redoLastAction()
        assertEquals(setOf(target.id), db.organizationDao().taskTags().filter { it.taskId == "task-1" }.map { it.tagId }.toSet())
        assertNull(db.organizationDao().tag(source.id))
    }

    @Test
    fun `record merge keeps the first id and one undo restores both records and tags`() = runBlocking {
        repo.saveTask(TaskEntity("task-1", "论文阅读"))
        val morning = org.createTag("早晨", 0xFF26806A)
        val deep = org.createTag("精读", 0xFF5756B7)
        repo.saveRecord(
            TimeRecordEntity(
                "r1", "task-1",
                now.minusSeconds(7200).toEpochMilli(), now.minusSeconds(5400).toEpochMilli(),
                title = "论文阅读", notes = "第一节",
            )
        )
        repo.saveRecord(
            TimeRecordEntity(
                "r2", "task-1",
                now.minusSeconds(5400).toEpochMilli(), now.minusSeconds(3600).toEpochMilli(),
                title = "论文阅读", notes = "第二节",
            )
        )
        org.assignTags(emptyList(), listOf("r1"), setOf(morning.id), emptySet())
        org.assignTags(emptyList(), listOf("r2"), setOf(deep.id), emptySet())

        val preview = org.previewMerge(listOf("r1", "r2"))
        assertEquals("r1", preview.firstId)
        assertEquals(3_600_000L, preview.originalTotalMillis)
        assertEquals(3_600_000L, preview.endAt - preview.startAt)

        assertEquals("r1", org.mergeRecords(listOf("r1", "r2"), preview.versions))
        val merged = db.dao().record("r1")!!
        assertNull(db.dao().record("r2"))
        assertEquals(now.minusSeconds(7200).toEpochMilli(), merged.startAt)
        assertEquals(now.minusSeconds(3600).toEpochMilli(), merged.endAt)
        assertTrue(merged.notes.contains("第一节") && merged.notes.contains("第二节"))
        assertEquals(
            setOf(morning.id, deep.id),
            db.organizationDao().recordTags().filter { it.recordId == "r1" }.map { it.tagId }.toSet(),
        )

        repo.undoLastAction()
        assertEquals(now.minusSeconds(5400).toEpochMilli(), db.dao().record("r1")!!.endAt)
        assertNotNull(db.dao().record("r2"))
        assertEquals(setOf(morning.id), db.organizationDao().recordTags().filter { it.recordId == "r1" }.map { it.tagId }.toSet())
        assertEquals(setOf(deep.id), db.organizationDao().recordTags().filter { it.recordId == "r2" }.map { it.tagId }.toSet())

        repo.redoLastAction()
        assertNull(db.dao().record("r2"))
        assertEquals(now.minusSeconds(3600).toEpochMilli(), db.dao().record("r1")!!.endAt)
    }

    @Test
    fun `record split keeps the original id for the first half and undo restores tags`() = runBlocking {
        repo.saveTask(TaskEntity("task-1", "论文阅读"))
        val tag = org.createTag("专注", 0xFF3978B5)
        repo.saveRecord(
            TimeRecordEntity(
                "r1", "task-1",
                now.minusSeconds(7200).toEpochMilli(), now.minusSeconds(3600).toEpochMilli(),
                title = "论文阅读", notes = "整段备注",
            )
        )
        org.assignTags(emptyList(), listOf("r1"), setOf(tag.id), emptySet())
        repo.saveNote(NoteEntity("split-note", "RECORD", "r1", title = "记录笔记", body = "保留原文"))
        val version = db.dao().record("r1")!!.version
        val splitAt = now.minusSeconds(5400).toEpochMilli()

        val newId = org.splitRecord("r1", version, splitAt)
        val first = db.dao().record("r1")!!
        val second = db.dao().record(newId)!!
        assertEquals(splitAt, first.endAt)
        assertEquals(splitAt, second.startAt)
        assertEquals(now.minusSeconds(3600).toEpochMilli(), second.endAt)
        assertEquals(first.notes, second.notes)
        assertEquals(first.title, second.title)
        assertEquals("保留原文", db.dao().notes().single { it.targetId == newId }.body)
        assertEquals(setOf(tag.id), db.organizationDao().recordTags().filter { it.recordId == newId }.map { it.tagId }.toSet())
        assertTrue(db.dao().plans().isEmpty())

        repo.undoLastAction()
        assertEquals(now.minusSeconds(3600).toEpochMilli(), db.dao().record("r1")!!.endAt)
        assertNull(db.dao().record(newId))
        assertEquals(listOf("r1"), db.dao().notes().map { it.targetId })
        assertEquals(setOf(tag.id), db.organizationDao().recordTags().filter { it.recordId == "r1" }.map { it.tagId }.toSet())

        repo.redoLastAction()
        assertNotNull(db.dao().record(newId))
        assertEquals(splitAt, db.dao().record("r1")!!.endAt)
    }

    @Test
    fun `merging a synced record suppresses recreation and undo restores the source link`() = runBlocking {
        repo.saveTask(TaskEntity("task-1", "跑步训练"))
        repo.saveRecord(
            TimeRecordEntity(
                "r1", "task-1",
                now.minusSeconds(7200).toEpochMilli(), now.minusSeconds(5400).toEpochMilli(),
                title = "跑步训练",
            )
        )
        repo.saveRecord(
            TimeRecordEntity(
                "r2", "task-1",
                now.minusSeconds(5400).toEpochMilli(), now.minusSeconds(3600).toEpochMilli(),
                title = "跑步训练",
            )
        )
        db.dao().putHealthItem(
            HealthSyncItemEntity(
                id = "sync-1", kind = "EXERCISE", title = "跑步训练",
                startAt = now.minusSeconds(5400).toEpochMilli(), endAt = now.minusSeconds(3600).toEpochMilli(),
                originPackage = "com.example.health", recordId = "r2",
            )
        )
        val preview = org.previewMerge(listOf("r1", "r2"))
        org.mergeRecords(listOf("r1", "r2"), preview.versions)

        val suppressed = db.dao().healthItem("sync-1")!!
        assertNull(suppressed.recordId)
        assertTrue(suppressed.userDeleted)
        assertTrue(db.dao().record("r1")!!.isManuallyEdited)

        repo.undoLastAction()
        val restored = db.dao().healthItem("sync-1")!!
        assertEquals("r2", restored.recordId)
        assertFalse(restored.userDeleted)
        assertNotNull(db.dao().record("r2"))
        assertEquals(now.minusSeconds(7200).toEpochMilli(), db.dao().record("r1")!!.startAt)
        assertEquals(now.minusSeconds(5400).toEpochMilli(), db.dao().record("r1")!!.endAt)
    }

    @Test
    fun `deleting a category moves records and undo keeps every original item`() = runBlocking {        val source = org.createCategory("临时", 0xFFB5642D)
        val target = org.createCategory("正式", 0xFF4B7F52)
        repo.saveTask(TaskEntity("task-1", "整理材料", categoryId = source.id))
        repo.saveRecord(
            TimeRecordEntity(
                "r1", "task-1",
                now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli(),
                categoryId = source.id, title = "整理材料",
            )
        )

        assertEquals(1, org.deleteCategories(listOf(source.id), target.id))
        assertNotNull(db.dao().task("task-1"))
        assertNotNull(db.dao().record("r1"))
        assertEquals(target.id, db.dao().task("task-1")!!.categoryId)
        assertEquals(target.id, db.dao().record("r1")!!.categoryId)
        assertNull(db.organizationDao().category(source.id))

        repo.undoLastAction()
        assertEquals(source.id, db.dao().task("task-1")!!.categoryId)
        assertEquals(source.id, db.dao().record("r1")!!.categoryId)
        assertNotNull(db.organizationDao().category(source.id))
    }

    @Test
    fun `deleting a tag keeps the other tags on the same task and undo restores the link`() = runBlocking {
        val keep = org.createTag("保留", 0xFF3978B5)
        val drop = org.createTag("删除", 0xFF26806A)
        repo.saveTask(TaskEntity("task-1", "整理笔记"))
        org.assignTags(listOf("task-1"), emptyList(), setOf(keep.id, drop.id), emptySet())

        assertEquals(1, org.deleteTags(listOf(drop.id)))
        assertEquals(
            setOf(keep.id),
            db.organizationDao().taskTags().filter { it.taskId == "task-1" }.map { it.tagId }.toSet(),
        )

        repo.undoLastAction()
        assertEquals(
            setOf(keep.id, drop.id),
            db.organizationDao().taskTags().filter { it.taskId == "task-1" }.map { it.tagId }.toSet(),
        )
    }

    @Test
    fun `merging a tag keeps unrelated tags on the same record and undo restores them`() = runBlocking {
        val keep = org.createTag("保留", 0xFF3978B5)
        val source = org.createTag("来源", 0xFF26806A)
        val target = org.createTag("目标", 0xFFB5642D)
        repo.saveRecord(
            TimeRecordEntity(
                "r1", null,
                now.minusSeconds(600).toEpochMilli(), now.toEpochMilli(),
                title = "整理",
            )
        )
        org.assignTags(emptyList(), listOf("r1"), setOf(keep.id, source.id), emptySet())

        assertEquals(1, org.mergeTags(listOf(source.id), target.id))
        assertEquals(
            setOf(keep.id, target.id),
            db.organizationDao().recordTags().filter { it.recordId == "r1" }.map { it.tagId }.toSet(),
        )

        repo.undoLastAction()
        assertEquals(
            setOf(keep.id, source.id),
            db.organizationDao().recordTags().filter { it.recordId == "r1" }.map { it.tagId }.toSet(),
        )
    }
}
