package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseSingletonTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun concurrentProductionCallersShareTheMainDatabase() = runBlocking {
        val databases = (1..16).map {
            async(Dispatchers.Default) { TimeAgentDatabase.open(context) }
        }.awaitAll()
        try {
            databases.forEach { assertSame(databases.first(), it) }
        } finally {
            databases.distinct().forEach { it.close() }
        }
    }

    @Test
    fun inMemoryDatabasesRemainIndependent() {
        val first = TimeAgentDatabase.openInMemory(context)
        val second = TimeAgentDatabase.openInMemory(context)
        try {
            assertNotSame(first, second)
        } finally {
            first.close()
            second.close()
        }
    }
}
