package com.timeagent.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One reentrant coroutine gate for user writes and paired-database maintenance. */
object PersonalDataGate {
    @Volatile internal var recoveryRequired = false
    private val mutex = Mutex()
    private class Lease : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }
    suspend fun <T> access(block: suspend () -> T): T =
        if (currentCoroutineContext()[Lease] != null) block()
        else mutex.withLock { check(!recoveryRequired) { "数据恢复尚未完成回退，请重新打开应用" }; withContext(Lease()) { block() } }
}
