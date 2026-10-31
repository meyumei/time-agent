package com.timeagent.suggestions

import android.content.Context
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.UUID

/**
 * Follows the reminders coordinator pattern: observe repository changes, re-evaluate rules,
 * persist new candidates. Suggestion writes never touch the undo journal (repository-side).
 * Evaluation runs on the caller's dispatcher (the app's UI scope) and is serialized with a
 * mutex so suggestion writes never race the user's own writes on the shared connection.
 */
class SuggestionCoordinator(
    context: Context,
    private val repository: TimeRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Suppress("unused")
    private val appContext = context.applicationContext
    private var observing: Job? = null
    private val refreshMutex = Mutex()

    @Synchronized
    fun start(scope: CoroutineScope): Job {
        observing?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            launch { while (isActive) { refresh(); delay(60_000) } }
            repository.observe()
                .catch { /* A closed database with the UI must not crash the app scope. */ }
                .collectLatest { refresh() }
        }.also { observing = it }
    }

    suspend fun refresh() = refreshMutex.withLock {
        val snapshot = repository.snapshot()
        if (snapshot.loading) return@withLock
        val candidates = SuggestionEngine.generate(snapshot, repository.zone, repository.now())
        val stamp = repository.now().toEpochMilli()
        repository.recordSuggestions(candidates.map { it.toEntity(UUID.randomUUID().toString(), stamp) })
    }
}
