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
class RecordNarrationIntegrationTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 10, 28, 12, 0).atZone(zone).toInstant()
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private lateinit var agent: AssistantConversation
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TimeAgentDatabase::class.java).allowMainThreadQueries().build()
        repo = TimeRepository(db, Clock.fixed(now, zone))
        agent = AssistantConversation(repo)
    }
    @After fun close() { db.close() }
    private suspend fun say(text: String) = agent.submit(text, AssistantContext(now, zone, LocalDate.of(2026, 10, 26), repo.snapshot()))

    @Test fun `correct training and record subsequent research from original narration with one undo`() = runBlocking {
        repo.saveTask(TaskEntity("training-task", "胸肩三头"))
        repo.saveRecord(TimeRecordEntity("training", "training-task", at(27, 19, 10), at(27, 20, 45), title = "胸肩三头", notes = "保留的训练备注", source = "WORKOUT"))
        val original = db.dao().record("training")!!
        val historyBefore = db.dao().history().size
        val input = "10.27 今天真实的训练时长是从晚上7点到晚上8点半，修改对应时长。从8:50到11点半是科研，备注为配置clash和easy connect以及理解rax实验，继续跑robot rsi实验"
        say(input)
        val training = db.dao().record("training")!!
        assertEquals(at(27, 19), training.startAt)
        assertEquals(at(27, 20, 30), training.endAt)
        assertEquals(original.taskId, training.taskId)
        assertEquals(original.title, training.title)
        assertEquals(original.notes, training.notes)
        val research = db.dao().records().single { it.id != "training" }
        assertEquals("科研", research.title)
        assertEquals(at(27, 20, 50), research.startAt)
        assertEquals(at(27, 23, 30), research.endAt)
        assertEquals("配置clash和easy connect以及理解rax实验，继续跑robot rsi实验", research.notes)
        assertEquals(historyBefore + 1, db.dao().history().size)
        assertNull(agent.state.value.pending)
        assertTrue(agent.state.value.turns.last().text.contains("科研"))
        say(input)
        assertEquals(2, db.dao().records().size)
        assertEquals(historyBefore + 1, db.dao().history().size)
        say("撤销这次")
        assertEquals(1, db.dao().records().size)
        val restored = db.dao().record("training")!!
        assertEquals(original.startAt, restored.startAt)
        assertEquals(original.endAt, restored.endAt)
        assertEquals(original.notes, restored.notes)
        repo.saveRecord(restored.copy(taskId = null, title = "", notes = "健身", source = "MANUAL"), expectedVersion = restored.version)
        val context = AssistantContext(now, zone, LocalDate.of(2026, 10, 26), repo.snapshot())
        val parsed = LocalAssistantParser().parse(input, context) as Interpretation.Parsed
        val preview = (AssistantPlanner().prepare(parsed.command, context) as AssistantReply.Preview).value
        val confirmation = AssistantConfirmation(RepositoryAssistantWriter(repo))
        confirmation.stage(preview)
        assertTrue(confirmation.confirm(preview.id) is CommitResult.Saved)
        assertEquals(at(27, 19), db.dao().record("training")!!.startAt)
        assertEquals(at(27, 20, 30), db.dao().record("training")!!.endAt)
        assertEquals("健身", db.dao().record("training")!!.notes)
        assertEquals(2, db.dao().records().size)
        repo.undoLastAction()
        assertEquals(1, db.dao().records().size)
        assertEquals(original.startAt, db.dao().record("training")!!.startAt)
        assertEquals(original.endAt, db.dao().record("training")!!.endAt)
    }
}
