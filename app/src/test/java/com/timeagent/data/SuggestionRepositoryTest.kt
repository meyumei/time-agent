package com.timeagent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SuggestionRepositoryTest {
    private lateinit var db: TimeAgentDatabase
    private lateinit var repo: TimeRepository
    private val now = Instant.parse("2026-10-19T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Shanghai"))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() {
        context.deleteDatabase("suggestion-test.db")
        db = Room.databaseBuilder(context, TimeAgentDatabase::class.java, "suggestion-test.db").allowMainThreadQueries().build()
        repo = TimeRepository(db, clock)
    }
    @After fun teardown() { db.close(); context.deleteDatabase("suggestion-test.db") }

    private fun candidate(key: String, title: String = "建议") =
        AgentSuggestionEntity("s-$key", "OVERDUE_TASK", title, "detail", key, createdAt = now.toEpochMilli())

    @Test fun `same dedup key is recorded only once`() = runBlocking {
        repo.recordSuggestions(listOf(candidate("overdue:t1:100")))
        repo.recordSuggestions(listOf(candidate("overdue:t1:100")))
        assertEquals(1, db.dao().allSuggestions().size)
    }

    @Test fun `dismissed suggestion never comes back`() = runBlocking {
        repo.recordSuggestions(listOf(candidate("free_slot:2026-10-19:900")))
        repo.dismissSuggestion("s-free_slot:2026-10-19:900")
        assertTrue(db.dao().suggestions().isEmpty())
        repo.recordSuggestions(listOf(candidate("free_slot:2026-10-19:900")))
        assertEquals(1, db.dao().allSuggestions().size)
        assertEquals("DISMISSED", db.dao().suggestionByDedup("free_slot:2026-10-19:900")!!.status)
    }

    @Test fun `pending suggestions older than seven days expire`() = runBlocking {
        val stale = AgentSuggestionEntity("old", "FREE_SLOT", "旧建议", "detail", "old-key", createdAt = now.toEpochMilli() - 8L * 24 * 3600_000)
        repo.recordSuggestions(listOf(stale))
        repo.recordSuggestions(listOf(candidate("new-key")))
        val expired = db.dao().suggestionByDedup("old-key")!!
        assertEquals("EXPIRED", expired.status)
        assertEquals(now.toEpochMilli(), expired.decidedAt)
        assertEquals("PENDING", db.dao().suggestionByDedup("new-key")!!.status)
    }

    @Test fun `snapshot exposes pending suggestions for the inbox`() = runBlocking {
        repo.recordSuggestions(listOf(candidate("k1", "任务已截止：写论文")))
        val suggestions = repo.snapshot().suggestions
        assertEquals(1, suggestions.size)
        assertEquals("任务已截止：写论文", suggestions.single().title)
    }
}
