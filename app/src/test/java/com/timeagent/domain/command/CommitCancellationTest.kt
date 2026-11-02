package com.timeagent.domain.command

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommitCancellationTest {
    private val command = CreateSchedule("create", ScheduleBlockEntity("plan", null, 1_000, 2_000, title = "日程"))

    private class CommitBoundaryFactory(private val afterEnd: () -> Unit) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
            fun wrap(database: SupportSQLiteDatabase) = object : SupportSQLiteDatabase by database {
                override fun endTransaction() {
                    database.endTransaction()
                    if (!database.inTransaction()) afterEnd()
                }
            }
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase get() = wrap(helper.writableDatabase)
                override val readableDatabase get() = wrap(helper.readableDatabase)
            }
        }
    }

    @Test fun cancellationAtTheCommitBoundaryStillPublishesExactlyOnce() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val armed = AtomicBoolean(false)
        val caller = AtomicReference<Job>()
        val executor = Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(context, TimeAgentDatabase::class.java)
            .openHelperFactory(CommitBoundaryFactory {
                if (armed.compareAndSet(true, false)) caller.get().cancel()
            })
            .setQueryExecutor(executor).setTransactionExecutor(executor)
            .allowMainThreadQueries().build()
        val repo = TimeRepository(db)
        val events = mutableListOf<DataChanged>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { repo.commands.events.collect { events += it } }
        try {
            db.openHelper.writableDatabase
            val execution = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { repo.commands.execute(command) }
            caller.set(execution)
            armed.set(true)
            execution.start()
            execution.join()
            yield()
            assertTrue(execution.isCancelled)
            assertFalse(armed.get())
            assertNotNull("SQLite COMMIT must have completed before cancellation", db.dao().plan("plan"))
            assertNotNull(db.dao().commandResult("create"))
            assertEquals("A committed command must publish even when its caller is cancelled", listOf(DataChanged(setOf("PLAN"))), events)
            assertEquals(CommandStatus.DUPLICATE, repo.commands.execute(command).status)
            yield()
            assertEquals(1, events.size)
        } finally {
            observer.cancelAndJoin()
            db.close()
            executor.shutdownNow()
        }
    }

    @Test fun cancellationBeforeCommitRollsBackAndDoesNotPublish() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = TimeAgentDatabase.openInMemory(context)
        val repo = TimeRepository(db)
        val events = mutableListOf<DataChanged>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { repo.commands.events.collect { events += it } }
        try {
            val execution = launch {
                repo.transaction {
                    assertEquals(CommandStatus.APPLIED, repo.commands.execute(command).status)
                    currentCoroutineContext().cancel()
                    yield()
                }
            }
            execution.join()
            yield()
            assertTrue(execution.isCancelled)
            assertTrue(db.dao().plans().isEmpty())
            assertTrue(db.dao().history().isEmpty())
            assertNull(db.dao().commandResult("create"))
            assertTrue(events.isEmpty())
        } finally {
            observer.cancelAndJoin()
            db.close()
        }
    }
}
