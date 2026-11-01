package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

/**
 * v13 contract: a schedule block without a task is a first-class plan, not a task stub.
 * Every write goes through the existing repository entry points only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IndependentScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), zone)

    private fun at(time: String, date: LocalDate = LocalDate.of(2026, 11, 2)): Long =
        date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun schedule(
        id: String,
        startAt: Long,
        endAt: Long,
        title: String = "独立日程",
        kind: String = "TIMED",
        notes: String = "",
        colorOverride: String? = null,
        source: String = "MANUAL",
        sourceRef: String? = null,
    ) = ScheduleBlockEntity(
        id = id, taskId = null, startAt = startAt, endAt = endAt,
        title = title, kind = kind,
        allDayStartDate = null, allDayEndDate = null,
        timezoneId = zone.id, notes = notes, colorOverride = colorOverride,
        source = source, sourceRef = sourceRef, deletedAt = null,
    )

    private fun runRepo(scenario: suspend (TimeRepository, TimeAgentDatabase) -> Unit) = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = TimeAgentDatabase.openInMemory(context)
        try {
            scenario(TimeRepository(db, clock), db)
        } finally {
            db.close()
        }
    }

    @Test
    fun standalonePlanIsCreatedEditedAndReadWithoutTasks() = runRepo { repo, db ->
        repo.savePlan(schedule("p1", at("09:00"), at("10:00"), title = "晨间阅读", notes = "第三章"))

        assertTrue("独立日程不隐式创建任务", db.dao().tasks().isEmpty())
        val created = repo.snapshot().plans.single { it.id == "p1" }
        assertEquals("晨间阅读", created.title)
        assertEquals("TIMED", created.kind)
        assertNull(created.taskId)
        assertEquals(zone.id, created.timezoneId)
        assertEquals("第三章", created.notes)
        assertEquals("MANUAL", created.source)

        repo.savePlan(created.copy(title = "晨间精读", notes = "第四章"), expectedVersion = created.version)

        val edited = repo.snapshot().plans.single { it.id == "p1" }
        assertEquals("晨间精读", edited.title)
        assertEquals("第四章", edited.notes)
        assertEquals(at("09:00"), edited.startAt)
        assertEquals(at("10:00"), edited.endAt)
        assertTrue(edited.version > created.version)
        assertTrue("编辑独立日程仍不创建任务", db.dao().tasks().isEmpty())
    }

    @Test
    fun deleteUndoRedoPreservesStandaloneMetadata() = runRepo { repo, db ->
        repo.savePlan(schedule("p2", at("14:00"), at("15:00"), title = "写周报", notes = "含数据",
            colorOverride = "#FF8800", source = "FISH", sourceRef = "ref-9"))

        repo.deletePlan("p2")
        assertTrue("删除后活动快照不应包含", repo.snapshot().plans.none { it.id == "p2" })
        val deleted = db.dao().plan("p2")
        assertNotNull("删除为软删除，行仍保留", deleted)
        assertNotNull(deleted!!.deletedAt)

        repo.undoLastAction()
        val restored = repo.snapshot().plans.single { it.id == "p2" }
        assertEquals("写周报", restored.title)
        assertEquals("含数据", restored.notes)
        assertEquals("#FF8800", restored.colorOverride)
        assertEquals("FISH", restored.source)
        assertEquals("ref-9", restored.sourceRef)
        assertEquals(zone.id, restored.timezoneId)
        assertNull(restored.deletedAt)

        repo.redoLastAction()
        assertTrue("重做删除后活动快照不应包含", repo.snapshot().plans.none { it.id == "p2" })

        repo.undoLastAction()
        val restoredAgain = repo.snapshot().plans.single { it.id == "p2" }
        assertEquals("写周报", restoredAgain.title)
        assertEquals("含数据", restoredAgain.notes)
        assertEquals("ref-9", restoredAgain.sourceRef)
        assertEquals("FISH", restoredAgain.source)
    }

    @Test
    fun staleStandaloneEditIsRejectedAndKeepsOriginal() = runRepo { repo, db ->
        repo.savePlan(schedule("p3", at("09:00"), at("10:00"), title = "初稿", notes = "原备注"))
        val first = db.dao().plan("p3")!!
        repo.savePlan(first.copy(title = "二稿"), expectedVersion = first.version)
        val second = db.dao().plan("p3")!!
        assertTrue(second.version > first.version)

        val failure = runCatching {
            repo.savePlan(second.copy(title = "陈旧写入", notes = "被覆盖"), expectedVersion = first.version)
        }.exceptionOrNull()
        assertTrue("过期编辑须抛 StaleChange，实际：$failure", failure is StaleChange)

        val intact = db.dao().plan("p3")!!
        assertEquals("二稿", intact.title)
        assertEquals("原备注", intact.notes)
        assertEquals(second.version, intact.version)
    }

    @Test
    fun overlappingStandaloneTimedBlocksWarnButBothSave() = runRepo { repo, db ->
        repo.savePlan(schedule("a", at("09:00"), at("10:00"), title = "晨会"))

        val warnings = repo.savePlan(schedule("b", at("09:30"), at("10:30"), title = "复盘会"))

        assertEquals(listOf("a"), warnings.map { it.id })
        assertEquals("警告不阻止保存", 2, repo.snapshot().plans.size)
        assertEquals("复盘会", repo.snapshot().plans.single { it.id == "b" }.title)
        assertTrue(repo.snapshot().tasks.isEmpty())
    }

    @Test
    fun pointBlockAcceptsZeroDurationAndIsIgnoredByConflicts() = runRepo { repo, db ->
        repo.savePlan(schedule("timed", at("10:00"), at("11:00"), title = "深度工作"))

        val pointWarnings = repo.savePlan(schedule("point", at("10:30"), at("10:30"), title = "打卡", kind = "POINT"))

        assertTrue("POINT 不参与冲突判定，实际：$pointWarnings", pointWarnings.isEmpty())
        val point = db.dao().plan("point")!!
        assertEquals(point.startAt, point.endAt)
        assertEquals("POINT", point.kind)

        val laterWarnings = repo.savePlan(schedule("timed-2", at("10:15"), at("10:45"), title = "临时讨论"))

        assertTrue("仍应与实体块冲突", laterWarnings.any { it.id == "timed" })
        assertFalse("POINT 不应出现在其它块的冲突里", laterWarnings.any { it.id == "point" })
    }

    @Test
    fun blankStandaloneTitleIsRejectedWithoutWriting() = runRepo { repo, db ->
        val failure = runCatching {
            repo.savePlan(schedule("blank", at("09:00"), at("10:00"), title = "   "))
        }.exceptionOrNull()

        assertTrue("空白标题须被拒绝，实际：$failure", failure is IllegalArgumentException)
        assertNull(db.dao().plan("blank"))
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(repo.snapshot().plans.isEmpty())
    }
}
