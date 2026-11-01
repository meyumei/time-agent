package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.ui.ItemKind
import com.timeagent.ui.isPoint
import com.timeagent.ui.timeline
import com.timeagent.ui.timelineConflictGroups
import com.timeagent.ui.timelineConflicts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

/** Task deletion keeps, detaches or removes linked schedules as exactly one undoable action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScheduleBehaviorTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"), zone)

    private fun at(time: String, date: LocalDate = LocalDate.of(2026, 11, 2)): Long =
        date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun runRepo(scenario: suspend (TimeRepository, TimeAgentDatabase) -> Unit) = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = TimeAgentDatabase.openInMemory(context)
        try {
            scenario(TimeRepository(db, clock), db)
        } finally {
            db.close()
        }
    }

    private fun task(id: String, title: String) = TaskEntity(id = id, title = title, estimatedMinutes = 45)

    private fun block(
        id: String,
        taskId: String?,
        start: Long,
        end: Long,
        title: String = "",
        kind: String = "TIMED",
        notes: String = "",
        source: String = "MANUAL",
        sourceRef: String? = null,
        timezoneId: String = zone.id,
    ) = ScheduleBlockEntity(
        id = id, taskId = taskId, startAt = start, endAt = end, title = title, kind = kind,
        notes = notes, source = source, sourceRef = sourceRef, timezoneId = timezoneId,
    )

    @Test
    fun keepingSchedulesDetachesThemAndOneUndoRedoMovesBothAssociations() = runRepo { repo, db ->
        repo.saveTask(task("t1", "整理周报"))
        repo.savePlan(block("s-blank", "t1", at("09:00"), at("10:00")))
        repo.savePlan(block("s-custom", "t1", at("11:00"), at("12:00"), title = "深度阅读",
            notes = "第三章", source = "FISH", sourceRef = "fish-1"))

        repo.deleteTask("t1", LinkedScheduleAction.KEEP_INDEPENDENT)

        assertNotNull("任务为软删除", requireNotNull(db.dao().task("t1")).deletedAt)
        val blank = requireNotNull(db.dao().plan("s-blank"))
        val custom = requireNotNull(db.dao().plan("s-custom"))
        assertNull("保留为独立日程", blank.taskId)
        assertNull(custom.taskId)
        assertEquals("空白标题回退为原任务标题", "整理周报", blank.title)
        assertEquals("自定义标题保留", "深度阅读", custom.title)
        assertEquals("第三章", custom.notes)
        assertEquals(at("11:00"), custom.startAt)
        assertEquals(at("12:00"), custom.endAt)
        assertEquals("FISH", custom.source)
        assertEquals("fish-1", custom.sourceRef)
        assertNull("保留的日程不被软删除", custom.deletedAt)
        assertTrue(repo.snapshot().plans.any { it.id == "s-blank" })
        assertTrue(repo.snapshot().plans.any { it.id == "s-custom" })

        repo.undoLastAction()

        assertNull("一次撤销恢复任务", requireNotNull(db.dao().task("t1")).deletedAt)
        assertEquals("t1", requireNotNull(db.dao().plan("s-blank")).taskId)
        assertEquals("t1", requireNotNull(db.dao().plan("s-custom")).taskId)
        assertEquals("第三章", requireNotNull(db.dao().plan("s-custom")).notes)
        assertEquals(at("11:00"), requireNotNull(db.dao().plan("s-custom")).startAt)

        repo.redoLastAction()

        assertNotNull(requireNotNull(db.dao().task("t1")).deletedAt)
        assertNull(requireNotNull(db.dao().plan("s-blank")).taskId)
        assertNull(requireNotNull(db.dao().plan("s-custom")).taskId)
        assertEquals("重做后标题仍为回退值", "整理周报", requireNotNull(db.dao().plan("s-blank")).title)
        assertEquals("重做后自定义标题不变", "深度阅读", requireNotNull(db.dao().plan("s-custom")).title)
    }

    @Test
    fun deletingSchedulesWithTheTaskUndoesEverythingAsOneAction() = runRepo { repo, db ->
        repo.saveTask(task("t2", "周会准备"))
        repo.savePlan(block("a", "t2", at("09:00"), at("10:00"), title = "准备材料"))
        repo.savePlan(block("b", "t2", at("10:00"), at("11:00"), title = "周会"))

        repo.deleteTask("t2", LinkedScheduleAction.DELETE)

        assertNotNull(requireNotNull(db.dao().task("t2")).deletedAt)
        assertNotNull("关联日程一并软删除", requireNotNull(db.dao().plan("a")).deletedAt)
        assertNotNull(requireNotNull(db.dao().plan("b")).deletedAt)
        assertTrue(repo.snapshot().plans.isEmpty())

        repo.undoLastAction()

        assertNull(requireNotNull(db.dao().task("t2")).deletedAt)
        val a = requireNotNull(db.dao().plan("a"))
        val b = requireNotNull(db.dao().plan("b"))
        assertNull(a.deletedAt)
        assertNull(b.deletedAt)
        assertEquals("t2", a.taskId)
        assertEquals("t2", b.taskId)
        assertEquals("一次撤销恢复两条关联日程", 2, repo.snapshot().plans.size)
    }

    @Test
    fun omittingTheChoiceWithLinkedSchedulesFailsAndWritesNothing() = runRepo { repo, db ->
        repo.saveTask(task("t3", "写方案"))
        repo.savePlan(block("p3", "t3", at("14:00"), at("15:00"), title = "方案初稿"))
        val historyBefore = db.dao().history()

        val failure = runCatching { repo.deleteTask("t3") }.exceptionOrNull()

        assertTrue("缺少选择时须拒绝，实际：$failure", failure is IllegalArgumentException)
        assertNull("任务保持活动", requireNotNull(db.dao().task("t3")).deletedAt)
        assertNull("日程保持活动", requireNotNull(db.dao().plan("p3")).deletedAt)
        assertEquals("t3", requireNotNull(db.dao().plan("p3")).taskId)
        assertEquals("历史不新增条目", historyBefore, db.dao().history())
        assertEquals(1, repo.snapshot().tasks.count { it.deletedAt == null })
        assertTrue(repo.snapshot().plans.any { it.id == "p3" })
    }

    @Test
    fun allDayBlockInNewYorkEndsAtExclusiveLocalMidnightAcrossDst() = runRepo { repo, db ->
        val ny = ZoneId.of("America/New_York")
        repo.savePlan(block("dst", null, 0, 0, title = "秋假", kind = "ALL_DAY", timezoneId = ny.id)
            .copy(allDayStartDate = "2026-10-31", allDayEndDate = "2026-11-01"))

        val stored = requireNotNull(db.dao().plan("dst"))
        assertEquals("2026-10-31", stored.allDayStartDate)
        assertEquals("2026-11-01", stored.allDayEndDate)
        assertEquals(Instant.parse("2026-10-31T04:00:00Z").toEpochMilli(), stored.startAt)
        assertEquals(Instant.parse("2026-11-02T05:00:00Z").toEpochMilli(), stored.endAt)
        assertEquals(LocalDateTime.of(2026, 10, 31, 0, 0), Instant.ofEpochMilli(stored.startAt).atZone(ny).toLocalDateTime())
        assertEquals(LocalDateTime.of(2026, 11, 2, 0, 0), Instant.ofEpochMilli(stored.endAt).atZone(ny).toLocalDateTime())
        assertEquals("包含真实结束日且跨夏令时", 49L,
            Duration.between(Instant.ofEpochMilli(stored.startAt), Instant.ofEpochMilli(stored.endAt)).toHours())
    }

    @Test
    fun pointKeepsItsOwnTitleAndNeverJoinsTimelineConflicts() = runRepo { repo, db ->
        repo.saveTask(task("t5", "晨会同步"))
        repo.savePlan(block("linked", "t5", at("09:00"), at("10:00")))
        repo.savePlan(block("independent", null, at("14:00"), at("15:00"), title = "独立专注"))
        repo.savePlan(block("point", null, at("09:30"), at("09:30"), title = "打卡", kind = "POINT"))

        val items = repo.snapshot().timeline(clock.instant())
        val point = items.single { it.id == "point" }
        assertEquals("打卡", point.title)
        assertEquals(ItemKind.PLAN, point.kind)
        assertTrue("POINT 仍是时间轴上的点", point.isPoint)
        assertEquals(at("09:30"), point.span.start.toEpochMilli())
        val storedPoint = requireNotNull(db.dao().plan("point"))
        assertEquals(storedPoint.startAt, storedPoint.endAt)
        assertEquals("关联日程回退为任务标题", "晨会同步", items.single { it.id == "linked" }.title)
        assertEquals("独立日程使用自身标题", "独立专注", items.single { it.id == "independent" }.title)
        assertTrue("POINT 不产生时序冲突", timelineConflicts(items).isEmpty())
        assertTrue("POINT 不进入冲突分组", timelineConflictGroups(items).isEmpty())
    }
}
