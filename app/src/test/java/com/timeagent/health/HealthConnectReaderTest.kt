package com.timeagent.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HealthConnectReaderTest {
    @Test fun `all health pages are read before returning records`() = runBlocking {
        val requested = mutableListOf<String?>()
        val records = collectHealthRecords { token ->
            requested += token
            when (token) {
                null -> listOf("a", "b") to "next"
                "next" -> listOf("c") to null
                else -> error("Unexpected token")
            }
        }
        assertEquals(listOf(null, "next"), requested)
        assertEquals(listOf("a", "b", "c"), records)
    }

    @Test fun `empty last page terminates without losing earlier records`() = runBlocking {
        assertEquals(listOf(1), collectHealthRecords { token -> if (token == null) listOf(1) to "last" else emptyList<Int>() to "" })
    }

    @Test fun `sleep only permissions do not require exercise authorization`() {
        assertEquals(setOf("android.permission.health.READ_SLEEP"), HealthConnectReader.permissionsFor(true, false))
        assertEquals(setOf("android.permission.health.READ_EXERCISE"), HealthConnectReader.permissionsFor(false, true))
        assertEquals(HealthConnectReader.READ_PERMISSIONS, HealthConnectReader.permissionsFor(true, true))
        assertTrue(HealthConnectReader.permissionsFor(false, false).isEmpty())
    }
}
