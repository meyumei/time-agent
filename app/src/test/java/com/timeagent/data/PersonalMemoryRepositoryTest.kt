package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.timeagent.memory.PersonalMemoryRules
import com.timeagent.suggestions.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersonalMemoryRepositoryTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-28T02:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    @Before fun setup() { db = TimeAgentDatabase.openInMemory(ApplicationProvider.getApplicationContext<Context>()); repo = TimeRepository(db, Clock.fixed(now, zone)) }
    @After fun cleanup() = db.close()
    @Test fun `explicit memory is one undo and restored edits cannot reuse old versions`() = runBlocking<Unit> {
        val card = repo.rememberExplicit("记住周三不练腿", "remember-one")
        assertEquals(card.id, repo.rememberExplicit("记住周三不练腿", "remember-one").id)
        assertEquals(1, repo.snapshot().memories.size)
        assertEquals(1, db.dao().history().size)
        repo.undoLastAction("remember-one")
        assertTrue(repo.snapshot().memories.isEmpty())
        repo.redoLastAction()
        assertTrue(repo.snapshot().memories.single().version > card.version)
        assertThrows(StaleChange::class.java) { runBlocking { repo.saveMemory(card.copy(text = "旧编辑"), card.version) } }
    }
    @Test fun `effective memory changes suggestions and schedule commits while deactivation is undoable`() = runBlocking<Unit> {
        val task = repo.saveTask(TaskEntity("legs", "练腿", estimatedMinutes = 60))
        val card = repo.rememberExplicit("记住周三不练腿", "remember-rule")
        assertTrue(SuggestionEngine.generate(repo.snapshot(), zone, now).none { it.type == SuggestionEngine.TYPE_FREE_SLOT })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.savePlan(ScheduleBlockEntity("p", task.id, now.plusSeconds(3600).toEpochMilli(), now.plusSeconds(7200).toEpochMilli())) } }
        repo.saveMemory(card.copy(enabled = false), card.version)
        assertTrue(SuggestionEngine.generate(repo.snapshot(), zone, now).any { it.type == SuggestionEngine.TYPE_FREE_SLOT })
        repo.undoLastAction()
        assertTrue(repo.snapshot().memories.single().enabled)
    }
    @Test fun `single behavior creates only an unconfirmed candidate and deleted candidates stay rejected`() = runBlocking<Unit> {
        repo.saveRecord(TimeRecordEntity("read", null, now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli(), title = "阅读"))
        val candidate = requireNotNull(repo.proposeMemoryCandidate())
        assertFalse(candidate.confirmed)
        assertEquals("OBSERVATION", candidate.sourceType)
        assertFalse(PersonalMemoryRules.active(candidate, now.toEpochMilli()))
        repo.deleteMemory(candidate.id, candidate.version)
        assertNull(repo.proposeMemoryCandidate())
    }
    @Test fun `stale generated card cannot reactivate after new rule invalidates it`() = runBlocking<Unit> {
        repo.saveTask(TaskEntity("legs", "练腿", estimatedMinutes = 60))
        val generated = SuggestionEngine.generate(repo.snapshot(), zone, now).single { it.type == SuggestionEngine.TYPE_FREE_SLOT }.toEntity("card", now.toEpochMilli())
        repo.recordSuggestions(listOf(generated))
        val memory = repo.rememberExplicit("记住周三不练腿", "rule")
        repo.recordSuggestions(listOf(generated))
        assertEquals("INVALIDATED", db.dao().suggestion("card")!!.status)
        repo.saveMemory(memory.copy(enabled = false), memory.version)
        repo.recordSuggestions(listOf(generated))
        assertEquals("PENDING", db.dao().suggestion("card")!!.status)
    }
    @Test fun `memory candidate button moves to next eligible actual record`() = runBlocking<Unit> {
        repo.saveRecord(TimeRecordEntity("first", null, now.minusSeconds(3600).toEpochMilli(), now.toEpochMilli(), title = "阅读"))
        repo.saveRecord(TimeRecordEntity("older", null, now.minusSeconds(7200).toEpochMilli(), now.minusSeconds(3600).toEpochMilli(), title = "散步"))
        assertEquals("first", requireNotNull(repo.proposeMemoryCandidate()).sourceId)
        assertEquals("older", requireNotNull(repo.proposeMemoryCandidate()).sourceId)
        assertNull(repo.proposeMemoryCandidate())
    }
}
