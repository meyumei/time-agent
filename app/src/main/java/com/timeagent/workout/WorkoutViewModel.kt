package com.timeagent.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.timeagent.core.workout.*
import com.timeagent.data.TimeRecordEntity
import com.timeagent.domain.command.WorkoutTimerConflict
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
    var timerConflict by mutableStateOf(false)
        private set
    private var pendingStart: PendingStart? = null

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

    private fun sessionVersion(id: String): Long? = _sessions.value.firstOrNull { it.id == id }?.updatedAt

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

    fun start(onStarted: (String) -> Unit) = startWorkout(onStarted) { timer, requestId ->
        repository.start(switchTimer = timer, requestId = requestId)
    }

    fun startWithExercises(exercises: List<ExerciseDefinition>, onStarted: (String) -> Unit) = startWorkout(onStarted) { timer, requestId ->
        repository.start(exercises = exercises, switchTimer = timer, requestId = requestId)
    }

    fun fromTemplate(id: String, onStarted: (String) -> Unit) = startWorkout(onStarted) { timer, requestId ->
        repository.startFromTemplate(id, switchTimer = timer, requestId = requestId)
    }

    fun repeat(id: String, onStarted: (String) -> Unit) = startWorkout(onStarted) { timer, requestId ->
        repository.repeatSession(id, switchTimer = timer, requestId = requestId)
    }

    fun confirmStartSwitch() {
        val pending = pendingStart ?: return
        timerConflict = false
        attemptStart(pending)
    }

    fun cancelStartSwitch() {
        timerConflict = false
        pendingStart = null
    }

    private fun startWorkout(onStarted: (String) -> Unit, call: suspend (TimeRecordEntity?, String) -> WorkoutSession) {
        if (pendingStart != null) return
        val pending = PendingStart(UUID.randomUUID().toString(), null, call, onStarted)
        pendingStart = pending
        attemptStart(pending)
    }

    private fun attemptStart(pending: PendingStart) {
        viewModelScope.launch {
            serial.withLock {
                _busy.value = true
                try {
                    val id = pending.call(pending.timer, pending.requestId).id
                    pendingStart = null
                    timerConflict = false
                    pending.onStarted(id)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (error is WorkoutTimerConflict) {
                        if (pending.timer == null) {
                            pendingStart = pending.copy(timer = error.timer)
                            timerConflict = true
                        } else {
                            pendingStart = null
                            timerConflict = false
                            _error.value = "计时状态已变化，请重新开始训练"
                        }
                    } else {
                        pendingStart = null
                        timerConflict = false
                        _error.value = error.message ?: "训练开始失败，动作选择已保留"
                    }
                } finally { _busy.value = false }
            }
        }
    }

    fun pause(session: WorkoutSession) = perform {
        if (session.status == WorkoutSessionStatus.PAUSED) repository.resume(session.id, session.updatedAt)
        else repository.pause(session.id, session.updatedAt)
    }

    fun update(id: String, transform: (WorkoutSession) -> WorkoutSession) = perform { repository.mutate(id, sessionVersion(id), transform) }

    fun addExercises(id: String, exercises: List<ExerciseDefinition>, replaceId: String? = null) = perform {
        repository.mutate(id, sessionVersion(id)) { session ->
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
        val expectedUpdatedAt = sessionVersion(sessionId)
        val current = repository.getSession(sessionId)?.movements?.find { it.id == movementId }?.sets?.find { it.id == set.id }
            ?: throw IllegalArgumentException("训练组已移除")
        if (current.isCompleted) repository.mutate(sessionId, expectedUpdatedAt) { session ->
            session.copy(restEndsAt = null, pausedRestRemainingMillis = null, movements = session.movements.map { movement ->
                if (movement.id == movementId) movement.copy(sets = movement.sets.map { if (it.id == set.id) it.copy(completedAt = null) else it }) else movement
            })
        } else repository.completeSet(sessionId, movementId, set.id, expectedUpdatedAt)
    }

    fun copyPrevious(sessionId: String, movement: WorkoutMovement) = perform {
        val previous = repository.lastMovement(movement.exercise.id, sessionId) ?: throw IllegalArgumentException("这个动作还没有历史记录")
        repository.mutate(sessionId, sessionVersion(sessionId)) { session -> session.copy(movements = session.movements.map { current ->
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

    fun finish(id: String, onFinished: () -> Unit) {
        val version = sessionVersion(id)
        perform(onFinished) { repository.finish(id, expectedUpdatedAt = version) }
    }
    fun discard(id: String, onDone: () -> Unit) {
        val version = sessionVersion(id)
        perform(onDone) { repository.discard(id, version) }
    }
    fun saveTemplate(id: String, title: String) = perform { repository.saveTemplate(id, title) }
    fun deleteTemplate(id: String) {
        val version = _templates.value.firstOrNull { it.id == id }?.updatedAt
        perform { repository.deleteTemplate(id, version) }
    }
    fun saveCustom(exercise: ExerciseDefinition) = perform { withContext(Dispatchers.IO) { catalog.saveCustom(exercise) } }

    fun saveTemplateDefinition(template: WorkoutTemplate, onSaved: () -> Unit) = perform(onSaved) { repository.saveTemplateDefinition(template) }
}

private data class PendingStart(
    val requestId: String,
    val timer: TimeRecordEntity?,
    val call: suspend (TimeRecordEntity?, String) -> WorkoutSession,
    val onStarted: (String) -> Unit,
)
