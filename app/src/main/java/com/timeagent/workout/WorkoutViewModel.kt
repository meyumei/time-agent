package com.timeagent.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.timeagent.core.workout.*
import com.timeagent.workout.data.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class WorkoutViewModel(val repository: WorkoutRepository, val catalog: WorkoutCatalog) : ViewModel() {
    var templateDraftJson by mutableStateOf<String?>(null)
    var templateBaselineJson: String? = null
    private val serial = Mutex()
    private val _sessions = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private val _templates = MutableStateFlow<List<WorkoutTemplate>>(emptyList())
    val templates = _templates.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()
    private val _previous = MutableStateFlow<Map<String, WorkoutMovement>>(emptyMap())
    val previous = _previous.asStateFlow()

    init {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { catalog.load() } }
            catch (error: Exception) { if (error is CancellationException) throw error; _error.value = "动作库加载失败：${error.message}" }
        }
        viewModelScope.launch {
            repository.observeSessions().collect { _sessions.value = it; _loading.value = false }
        }
        viewModelScope.launch { repository.observeTemplates().collect { _templates.value = it } }
    }

    fun clearError() { _error.value = null }
    fun togglePinned(id: String) = perform { catalog.togglePinned(id) }

    fun perform(onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            serial.withLock {
                _busy.value = true
                try { action(); onSuccess() }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _error.value = error.message ?: "保存失败，训练草稿已保留"
                } finally { _busy.value = false }
            }
        }
    }

    fun start(onStarted: (String) -> Unit) = perform {
        onStarted(repository.start("自由训练").id)
    }

    fun fromTemplate(id: String, onStarted: (String) -> Unit) = perform { onStarted(repository.startFromTemplate(id).id) }
    fun repeat(id: String, onStarted: (String) -> Unit) = perform { onStarted(repository.repeatSession(id).id) }
    fun pause(session: WorkoutSession) = perform {
        if (session.status == WorkoutSessionStatus.PAUSED) repository.resume(session.id) else repository.pause(session.id)
    }

    fun update(id: String, transform: (WorkoutSession) -> WorkoutSession) = perform { repository.mutate(id, transform) }

    fun addExercises(id: String, exercises: List<ExerciseDefinition>, replaceId: String? = null) = perform {
        repository.mutate(id) { session ->
            if (replaceId == null) session.copy(movements = session.movements + exercises.map { WorkoutMovement(exercise = it) })
            else session.copy(movements = session.movements.flatMap { movement ->
                if (movement.id == replaceId) exercises.map { WorkoutMovement(exercise = it, restSeconds = movement.restSeconds) } else listOf(movement)
            })
        }
        refreshPrevious(id)
    }

    fun updateMovement(sessionId: String, movementId: String, transform: (WorkoutMovement) -> WorkoutMovement) = update(sessionId) { session ->
        session.copy(movements = session.movements.map { if (it.id == movementId) transform(it) else it })
    }

    fun updateSet(sessionId: String, movementId: String, setId: String, transform: (WorkoutSet) -> WorkoutSet) = updateMovement(sessionId, movementId) { movement ->
        movement.copy(sets = movement.sets.map { if (it.id == setId) transform(it) else it })
    }

    fun toggleSet(sessionId: String, movementId: String, set: WorkoutSet) = perform {
        val current = repository.getSession(sessionId)?.movements?.find { it.id == movementId }?.sets?.find { it.id == set.id }
            ?: throw IllegalArgumentException("训练组已移除")
        if (current.isCompleted) repository.mutate(sessionId) { session ->
            session.copy(restEndsAt = null, pausedRestRemainingMillis = null, movements = session.movements.map { movement ->
                if (movement.id == movementId) movement.copy(sets = movement.sets.map { if (it.id == set.id) it.copy(completedAt = null) else it }) else movement
            })
        } else repository.completeSet(sessionId, movementId, set.id)
    }

    fun copyPrevious(sessionId: String, movement: WorkoutMovement) = perform {
        val previous = repository.lastMovement(movement.exercise.id, sessionId) ?: throw IllegalArgumentException("这个动作还没有历史记录")
        repository.mutate(sessionId) { session -> session.copy(movements = session.movements.map { current ->
            if (current.id != movement.id) current else current.copy(oneSide = previous.oneSide, singleSide = previous.singleSide,
                sets = previous.sets.filter { it.isCompleted }.map { it.copy(id = UUID.randomUUID().toString(), completedAt = null,
                    durationSeconds = null, distanceMeters = null, caloriesKcal = null, recordValues = emptyMap(), rpe = null, comment = "") }.ifEmpty { listOf(WorkoutSet()) })
        }) }
    }

    fun refreshPrevious(sessionId: String) {
        viewModelScope.launch {
          try {
            val session = repository.getSession(sessionId) ?: return@launch
            _previous.value = session.movements.mapNotNull { movement ->
                repository.lastMovement(movement.exercise.id, sessionId)?.let { movement.exercise.id to it }
            }.toMap()
          } catch (error: Exception) { if (error is CancellationException) throw error; _error.value = "上次记录读取失败：${error.message}" }
        }
    }

    fun finish(id: String, onFinished: () -> Unit) = perform(onFinished) { repository.finish(id) }
    fun discard(id: String, onDone: () -> Unit) = perform(onDone) { repository.discard(id) }
    fun saveTemplate(id: String, title: String) = perform { repository.saveTemplate(id, title) }
    fun deleteTemplate(id: String) = perform { repository.deleteTemplate(id) }
    fun saveCustom(exercise: ExerciseDefinition) = perform { withContext(Dispatchers.IO) { catalog.saveCustom(exercise) } }

    fun saveTemplateDefinition(template: WorkoutTemplate, onSaved: () -> Unit) = perform(onSaved) { repository.saveTemplateDefinition(template) }
}
