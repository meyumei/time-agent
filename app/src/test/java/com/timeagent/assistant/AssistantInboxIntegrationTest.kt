package com.timeagent.assistant

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantInboxIntegrationTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private lateinit var agent: AssistantConversation
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 21, 10, 0).atZone(zone).toInstant()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        repo = TimeRepository(db, Clock.fixed(now, zone))
        agent = AssistantConversation(repo)
    }
    @After fun cleanup() { db.close() }

    private suspend fun context() = AssistantContext(repo.now(), zone, LocalDate.of(2026, 10, 19), repo.snapshot())
    private suspend fun say(text: String) = agent.submit(text, context())
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun `idea preview is actionable and one undo restores the inbox`() = runBlocking {
        val planner = AssistantPlanner()
        val sentence = "灵感来了想做一个番茄钟小程序"
        val reply = planner.prepare(AssistantCommand.CreateIdea("番茄钟小程序", sentence), context())
        assertTrue(reply is AssistantReply.Preview)
        val preview = (reply as AssistantReply.Preview).value
        assertTrue(preview.mutation is AssistantMutation.SaveIdea)
        val idea = (preview.mutation as AssistantMutation.SaveIdea).idea
        assertEquals("番茄钟小程序", idea.title)
        assertEquals(sentence, idea.content)
        assertEquals("UNSCHEDULED", idea.status)

        assertTrue(repo.assistantTransaction(preview.id) { RepositoryAssistantWriter(repo).write(preview.mutation, false) })
        val saved = db.dao().idea(idea.id)!!
        assertEquals("UNSCHEDULED", saved.status)
        assertEquals(sentence, saved.content)
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().plans().isEmpty())
        assertTrue(db.dao().reminders().isEmpty())

        repo.undoLastAction(preview.id)
        assertNull(db.dao().idea(idea.id))
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().plans().isEmpty())

        assertTrue(planner.prepare(AssistantCommand.CreateIdea("  "), context()) is AssistantReply.Clarification)
    }

    @Test fun `point reminder preview creates no task or plan and one undo restores`() = runBlocking {
        val planner = AssistantPlanner()
        val remindAt = at(22, 15)
        val reply = planner.prepare(AssistantCommand.CreateReminder("健身", remindAt), context())
        assertTrue(reply is AssistantReply.Preview)
        val preview = (reply as AssistantReply.Preview).value
        assertTrue(preview.mutation is AssistantMutation.SaveReminder)
        val reminder = (preview.mutation as AssistantMutation.SaveReminder).reminder
        assertEquals("健身", reminder.title)
        assertEquals(remindAt, reminder.remindAt)
        assertTrue(reminder.enabled)

        assertTrue(repo.assistantTransaction(preview.id) { RepositoryAssistantWriter(repo).write(preview.mutation, false) })
        val saved = db.dao().reminder(reminder.id)!!
        assertEquals(remindAt, saved.remindAt)
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().plans().isEmpty())
        assertTrue(db.dao().ideas().isEmpty())

        repo.undoLastAction(preview.id)
        assertTrue(db.dao().reminders().isEmpty())

        assertTrue(planner.prepare(AssistantCommand.CreateReminder(" ", remindAt), context()) is AssistantReply.Clarification)
    }

    @Test fun `same page saves idea and point reminder while explicit window stays task and plan`() = runBlocking {
        val sentence = "灵感来了想做一个番茄钟小程序"
        say(sentence)
        val idea = db.dao().ideas().single()
        assertEquals("UNSCHEDULED", idea.status)
        assertEquals(sentence, idea.content)
        assertNull(agent.state.value.pending)
        assertNotNull(agent.state.value.undoId)
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().plans().isEmpty())
        assertTrue(db.dao().reminders().isEmpty())

        say("提醒明天下午三点健身")
        val reminder = db.dao().reminders().single()
        assertEquals("健身", reminder.title)
        assertEquals(at(22, 15), reminder.remindAt)
        assertTrue(reminder.enabled)
        assertNull(agent.state.value.pending)
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().plans().isEmpty())

        say("安排明天19点到20点阅读")
        val task = db.dao().tasks().single()
        assertTrue(task.title.contains("阅读"))
        val plan = db.dao().plans().single()
        assertEquals(task.id, plan.taskId)
        assertEquals(at(22, 19), plan.startAt)
        assertEquals(at(22, 20), plan.endAt)
        assertEquals("SCHEDULED", plan.status)
        assertEquals(1, db.dao().ideas().size)
        assertEquals(1, db.dao().reminders().size)
    }

    @Test fun `same page redo after undo restores without writing a new record`() = runBlocking {
        say("灵感来了想做一个番茄钟小程序")
        val ideaId = db.dao().ideas().single().id
        assertEquals(1, db.dao().history().size)
        val actionId = db.dao().history().single().id
        val afterSave = agent.state.value.completionRevision
        assertTrue(afterSave > 0)

        say("撤销")
        assertNull(db.dao().idea(ideaId))
        val afterUndo = agent.state.value.completionRevision
        assertTrue(afterUndo > afterSave)

        say("重做")
        assertNotNull(db.dao().idea(ideaId))
        assertEquals(1, db.dao().ideas().size)
        assertTrue(agent.state.value.completionRevision > afterUndo)
        assertEquals(1, db.dao().history().size)
        assertEquals(actionId, db.dao().history().single().id)
        assertFalse(db.dao().history().single().undone)

        val afterRedo = agent.state.value.completionRevision
        say("撤销")
        assertNull(db.dao().idea(ideaId))
        assertTrue(agent.state.value.completionRevision > afterRedo)

        say("恢复刚才撤销")
        assertNotNull(db.dao().idea(ideaId))
        assertEquals(1, db.dao().ideas().size)
        assertEquals(1, db.dao().history().size)
        assertEquals(actionId, db.dao().history().single().id)
        assertFalse(db.dao().history().single().undone)
        assertTrue(agent.state.value.completionRevision > afterRedo)
    }

    @Test fun `natural followup edits then cancels the same reminder`() = runBlocking {
        say("提醒明天下午三点健身")
        val original = db.dao().reminders().single()

        say("修改刚才的提醒到明天下午四点")
        val changed = db.dao().reminder(original.id)
        assertNotNull("提醒应被原地修改而不是新增", changed)
        val updated = changed!!
        assertEquals(1, db.dao().reminders().size)
        assertEquals("健身", updated.title)
        assertEquals(at(22, 16), updated.remindAt)
        assertTrue(updated.enabled)
        assertTrue(updated.version > original.version)
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().plans().isEmpty())

        say("取消刚才的提醒")
        assertEquals(0, db.dao().reminders().count { it.enabled })
        assertTrue(repo.snapshot().reminders.none { it.enabled })
    }

    @Test fun `reminder without a concrete time is clarified and not saved`() = runBlocking {
        say("提醒健身")
        assertTrue(db.dao().reminders().isEmpty())
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().plans().isEmpty())
        assertNull(agent.state.value.pending)
    }

    @Test fun `natural followup deletes the just saved idea`() = runBlocking {
        say("灵感来了想做一个番茄钟小程序")
        val idea = db.dao().ideas().single()

        say("删除刚才的想法")
        val after = db.dao().idea(idea.id)
        assertTrue(after == null || after.deletedAt != null)
        assertTrue(repo.snapshot().ideas.none { it.id == idea.id && it.deletedAt == null })
        assertTrue(db.dao().tasks().isEmpty())
        assertTrue(db.dao().plans().isEmpty())
    }
}
