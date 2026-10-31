package com.timeagent.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import com.timeagent.ui.timeline
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantConversationTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private lateinit var agent: AssistantConversation
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 21, 10, 0).atZone(zone).toInstant()
    private var currentTime = now
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        currentTime = now
        repo = TimeRepository(db, object : Clock() { override fun getZone(): ZoneId = this@AssistantConversationTest.zone; override fun withZone(zone: ZoneId): Clock = Clock.fixed(currentTime, zone); override fun instant(): Instant = currentTime }); agent = AssistantConversation(repo)
    }
    @After fun cleanup() { db.close() }
    private suspend fun say(text: String, cloud: ConversationCloud? = null) = agent.submit(text, AssistantContext(repo.now(), zone, LocalDate.of(2026, 10, 19), repo.snapshot()), cloud)
    @Test fun `sleep is actually saved without preview and duplicate speech does not add twice`() = runBlocking {
        say("帮我记录而睡眠一点半到八点八点半")
        val record = db.dao().records().single()
        assertEquals("睡眠", record.notes)
        assertEquals(LocalDateTime.of(2026, 10, 21, 1, 30).atZone(zone).toInstant().toEpochMilli(), record.startAt)
        assertEquals(7 * 3600000L, record.endAt!! - record.startAt)
        assertNull(agent.state.value.pending); assertNotNull(agent.state.value.undoId)
        assertTrue(agent.state.value.turns.last().text.startsWith("已执行"))
        say("帮我记录而睡眠一点半到八点八点半")
        assertEquals(1, db.dao().records().size); assertEquals(1, db.dao().history().size)
        say("撤销这次"); assertTrue(db.dao().records().isEmpty())
    }
    @Test fun `followup modifies and deletes the actual saved record`() = runBlocking {
        say("记录睡眠一点半到八点半")
        val id = db.dao().records().single().id
        say("把刚才睡眠改成两点到九点")
        val changed = db.dao().record(id)!!
        assertEquals(7 * 3600000L, changed.endAt!! - changed.startAt)
        assertEquals(2, Instant.ofEpochMilli(changed.startAt).atZone(zone).hour)
        say("删除刚才的记录"); assertNull(db.dao().record(id))
        say("撤销"); assertEquals(changed.notes, db.dao().record(id)!!.notes)
    }
    @Test fun `new scheduled activity creates task and plan with one undo`() = runBlocking {
        say("安排 明天 19:00 到 20:00 健身")
        assertEquals("健身", db.dao().tasks().single().title)
        assertEquals(1, db.dao().plans().size); assertEquals(1, db.dao().history().size)
        say("撤销"); assertTrue(db.dao().plans().isEmpty()); assertTrue(db.dao().tasks().isEmpty())
    }
    @Test fun `same page followup supplies missing time and writes once`() = runBlocking {
        say("记录睡眠")
        assertTrue(db.dao().records().isEmpty())
        say("一点半到八点半")
        assertEquals(1, db.dao().records().size)
        assertEquals(4, agent.state.value.turns.size)
    }
    @Test fun `spoken study keeps subject in notes with an independent timeline title and one undo`() = runBlocking {
        say("昨天上午9点到中午12点进行学习数字信号处理。")
        val record = db.dao().records().single()
        assertEquals("数字信号处理", record.notes)
        assertEquals("学习", repo.snapshot().timeline(now).single().title)
        say("昨天上午9点到中午12点进行学习数字信号处理。")
        assertEquals(1, db.dao().records().size)
        say("撤销这次")
        assertTrue(db.dao().records().isEmpty())
    }
    @Test fun `bare last night sleep actually saves this mornings exact interval`() = runBlocking {
        say("昨晚睡眠两点到9点")
        val record = db.dao().records().single()
        assertEquals(LocalDateTime.of(2026, 10, 21, 2, 0).atZone(zone).toInstant().toEpochMilli(), record.startAt)
        assertEquals(LocalDateTime.of(2026, 10, 21, 9, 0).atZone(zone).toInstant().toEpochMilli(), record.endAt)
        assertNull(agent.state.value.pending)
    }
    @Test fun `questions about yesterday never create a record or invoke cloud writes`() = runBlocking {
        var calls = 0
        val cloud = ConversationCloud { _, _, _ -> calls++; CloudResult.Success(Interpretation.Parsed(AssistantCommand.CreateTask("不应创建", 30))) }
        say("昨天上午九点到十二点学习数字信号处理？", cloud)
        say("昨天上午九点到十二点为什么没学习？", cloud)
        say("昨天九点到十二点我没有学习", cloud)
        say("昨天九点到十二点我为什么没学习", cloud)
        assertEquals(0, calls)
        assertTrue(db.dao().records().isEmpty())
        assertTrue(db.dao().tasks().isEmpty())
    }
    @Test fun `spoken schedule query with a question mark still answers without writes`() = runBlocking {
        say("明天有什么安排？")
        assertTrue(agent.state.value.turns.last().text.contains("2026-10-22"))
        assertTrue(agent.state.value.turns.last().text.contains("安排"))
        assertTrue(db.dao().records().isEmpty())
    }
    @Test fun `future actual stays pending until explicit confirmation`() = runBlocking {
        say("记录今天19:00到20:00阅读")
        assertTrue(db.dao().records().isEmpty()); assertTrue(agent.state.value.pending!!.needsFutureApproval)
        say("确认"); assertEquals(1, db.dao().records().size)
    }
    @Test fun `do not save cancels the pending action so a later confirmation cannot execute it`() = runBlocking {
        say("记录今天19:00到20:00阅读"); assertNotNull(agent.state.value.pending)
        say("不要保存"); assertNull(agent.state.value.pending)
        say("确认"); assertTrue(db.dao().records().isEmpty())
    }
    @Test fun `cloud receives conversation and clear typed action executes locally`() = runBlocking {
        var count = 0
        val cloud = ConversationCloud { _, _, turns ->
            count++; assertTrue(turns.last().text.isNotBlank())
            if (count == 1) CloudResult.Success(Interpretation.Clarify("任务叫什么？"))
            else CloudResult.Success(Interpretation.Parsed(AssistantCommand.CreateTask("整理笔记", 30)))
        }
        say("记个待办", cloud); assertTrue(db.dao().tasks().isEmpty())
        say("整理笔记", cloud); assertEquals("整理笔记", db.dao().tasks().single().title)
    }
    @Test fun `stale conflict confirmation rolls back newly created task and plan`() = runBlocking {
        repo.saveTask(TaskEntity("t", "已有任务"))
        val start = now.plusSeconds(3600).toEpochMilli()
        repo.savePlan(ScheduleBlockEntity("p", "t", start, start + 3600000))
        say("安排 今天 11:00 到 12:00 健身")
        assertNotNull(agent.state.value.pending)
        repo.savePlan(ScheduleBlockEntity("p2", "t", start, start + 3600000))
        say("确认")
        assertEquals(listOf("已有任务"), db.dao().tasks().map { it.title })
        assertEquals(2, db.dao().plans().size)
        assertFalse(agent.state.value.turns.last().text.startsWith("已执行"))
    }
    @Test fun `undo this action cannot undo someone elses newer edit`() = runBlocking {
        say("新建任务 阅读 半小时")
        repo.saveTask(TaskEntity("other", "稍后手动添加"))
        say("撤销这次")
        assertEquals(2, db.dao().tasks().size)
        assertFalse(agent.state.value.turns.last().text.startsWith("已撤销"))
    }
    @Test fun `repository request replay is idempotent even after undo`() = runBlocking {
        var writes = 0
        suspend fun write() = repo.assistantTransaction("same-request") { writes++; repo.saveTask(TaskEntity("t", "阅读")) }
        assertTrue(write()); assertFalse(write()); assertEquals(1, writes)
        repo.undoLastAction("same-request"); assertFalse(write()); assertEquals(1, writes)
        assertTrue(db.dao().tasks().isEmpty())
    }
    @Test fun `repeating undo this action does not undo an unrelated manual operation`() = runBlocking {
        repo.saveTask(TaskEntity("manual", "手动任务"))
        say("记录睡眠一点半到八点半"); say("撤销这次"); say("撤销这次")
        assertNotNull(db.dao().task("manual")); assertTrue(db.dao().records().isEmpty())
    }
    @Test fun `explicit no save and questions never write even with cloud enabled`() = runBlocking {
        val cloud = ConversationCloud { _, _, _ -> error("Must not ask cloud to execute a negated request") }
        say("记录今天八点到九点睡眠，不要保存", cloud)
        say("记录今天八点到九点睡眠了吗？", cloud)
        assertTrue(db.dao().records().isEmpty()); assertTrue(db.dao().history().isEmpty())
    }
    @Test fun `same words next morning record a different night`() = runBlocking {
        say("记录睡眠一点半到八点半")
        currentTime = now.plusSeconds(86400)
        say("记录睡眠一点半到八点半")
        assertEquals(2, db.dao().records().size)
        assertEquals(2, db.dao().records().map { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() }.distinct().size)
    }
    @Test fun `task identity followup selects existing task instead of making a title containing the id`() = runBlocking {
        repo.saveTask(TaskEntity("a", "阅读")); repo.saveTask(TaskEntity("b", "阅读"))
        say("安排 明天 19:00 到 20:00 阅读"); assertTrue(db.dao().plans().isEmpty())
        say("#a")
        assertEquals(2, db.dao().tasks().size)
        assertEquals("a", db.dao().plans().single().taskId)
    }
    @Test fun `explicit task target cannot delete similarly named sleep record`() = runBlocking {
        repo.saveTask(TaskEntity("sleep-task", "睡眠"))
        say("记录睡眠一点半到八点半")
        say("删除睡眠任务")
        assertEquals("CANCELLED", db.dao().task("sleep-task")!!.status)
        assertEquals(1, db.dao().records().size)
    }
    @Test fun `new conflict invalidates pending course move`() = runBlocking {
        val original = now.minusSeconds(3600).toEpochMilli()
        val target = now.plusSeconds(3600).toEpochMilli()
        db.dao().putTerm(AcademicTermEntity("term", "测试学期", "2026-10-19", 20, zone.id))
        db.dao().putCourse(CourseEntity("c", "数学课", "", "", "term", "source", "c", 0, 0))
        db.dao().putOccurrence(CourseOccurrenceEntity("o", "c", "2026-10-21", original, original + 1800000, "o"))
        repo.saveTask(TaskEntity("t", "原任务"))
        repo.savePlan(ScheduleBlockEntity("p", "t", target, target + 3600000))
        val cloud = ConversationCloud { _, _, _ -> CloudResult.Success(Interpretation.Parsed(AssistantCommand.EditCourse("o", target, target + 3600000))) }
        say("把数学课改到11:00到12:00", cloud)
        assertNotNull(agent.state.value.pending)
        repo.savePlan(ScheduleBlockEntity("p2", "t", target, target + 3600000))
        say("确认")
        assertEquals(original, db.dao().occurrence("o")!!.startAt)
    }
}
