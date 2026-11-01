package com.timeagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timeagent.core.*
import com.timeagent.data.*
import com.timeagent.assistant.*
import com.timeagent.health.*
import com.timeagent.history.*
import com.timeagent.memory.PersonalMemoryRules
import com.timeagent.workout.data.WorkoutRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.time.Duration

class TimeViewModel(
    val repository: TimeRepository,
    private val appContext: android.content.Context? = null,
    val clock: ClockSource = SystemClockSource(repository::now, android.os.SystemClock::elapsedRealtime),
) : ViewModel() {
    val fish by lazy { appContext?.let { com.timeagent.fish.FishSession.get(it, repository) } }
    val assistant = AssistantConversation(repository)
    private var assistantJob: Job? = null
    private val _historyAnswer = MutableStateFlow<HistoryAnswer?>(null)
    val historyAnswer = _historyAnswer.asStateFlow()
    fun queryHistory(filter: HistoryFilter, input: String? = null) = perform {
        val snapshot = repository.snapshot()
        val workouts = if (appContext == null) emptyList() else WorkoutRepository(appContext, repository).observeSessions().first()
        val answer = withContext(Dispatchers.Default) { LocalHistory.answer(snapshot, workouts, filter, repository.now(), zone) }
        _historyAnswer.value = answer
        if (input != null) assistant.showLocalResult(input, answer.text, "history")
    }
    fun sendToAssistant(text: String, cloud: ConversationCloud? = null) {
        if (assistant.state.value.busy || _busy.value) return
        if (Regex("^(?:请帮我|帮我|请)?记住").containsMatchIn(text.trim())) {
            if (PersonalMemoryRules.fromExplicit(text, repository.now()) == null) { assistant.showLocalResult(text, "这不是明确的记忆指令，未保存。可在个人记忆中核对后录入。", "memory"); return }
            perform {
                val request = "memory:${java.util.UUID.randomUUID()}"
                val saved = repository.rememberExplicit(text, request)
                val wrote = repository.snapshot().history.any { it.entityType == "ASSISTANT_BATCH" && it.entityId == request }
                assistant.showLocalResult(text, "已记住：${saved.text}。${if (saved.ruleType == "NONE") "文字记忆供核对，可设置具体排程约定。" else "对应排程约定已启用。"}", "memory", request.takeIf { wrote })
            }
            return
        }
        if (HistoryQuestionParser.isHistoryQuestion(text)) {
            val filter = runCatching { HistoryQuestionParser.parse(text, repository.now(), zone) }.getOrElse { assistant.showLocalResult(text, it.message ?: "日期范围无效，未查询。", "history"); return }
            if (filter == null) assistant.showLocalResult(text, "请在历史查询中选择日期和关键词。", "history") else queryHistory(filter, text)
            return
        }
        assistantJob = viewModelScope.launch {
            assistant.submit(text, AssistantContext(repository.now(), zone, visibleWeekStart.value, repository.snapshot()), cloud)
        }
    }
    suspend fun prepareForRestore() { assistantJob?.cancelAndJoin(); assistant.cancelPending() }
    val zone = repository.zone
    private val clockRefreshes = MutableSharedFlow<Instant>(extraBufferCapacity = 1)
    val now = merge(clock.minuteTicks, clockRefreshes)
        .stateIn(viewModelScope, SharingStarted.Eagerly, clock.now())
    private val _weekStart = MutableStateFlow(WeekState.startOfWeek(clock.now().atZone(zone).toLocalDate()))
    private val _visibleWeekStart = MutableStateFlow(_weekStart.value)
    val visibleWeekStart = _visibleWeekStart.asStateFlow()
    private val _timelineNavigationRevision = MutableStateFlow(0L)
    val timelineNavigationRevision = _timelineNavigationRevision.asStateFlow()
    val week = combine(_weekStart, now) { selected, now -> WeekState(now.atZone(zone).toLocalDate(), selected) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WeekState(clock.now().atZone(zone).toLocalDate()))
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val data = repository.observe().catch { _error.value = "读取失败：${it.message}，请重新打开应用重试" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalSnapshot())

    init {
        viewModelScope.launch {
            try { repository.initialize(); com.timeagent.recurring.RecurringService(repository).refresh() } catch (e: Exception) { if (e is CancellationException) throw e; _error.value = e.message }
        }
        viewModelScope.launch {
            var lastHour=clock.now().epochSecond/3600
            now.collect { instant ->
                val hour=instant.epochSecond/3600
                if(hour!=lastHour) {
                    lastHour=hour
                    try { com.timeagent.recurring.RecurringService(repository).refresh() }
                    catch(e:Exception) { if(e is CancellationException)throw e;_error.value="周期登记未完成：${e.message}" }
                }
            }
        }
    }
    fun changeWeek(delta: Long) { _weekStart.value = _weekStart.value.plusWeeks(delta); _visibleWeekStart.value = _weekStart.value }
    fun reportVisibleDate(date: LocalDate) { _visibleWeekStart.value = WeekState.startOfWeek(date) }
    fun changeReviewWeek(delta: Long) {
        _weekStart.value = _visibleWeekStart.value.plusWeeks(delta)
        _visibleWeekStart.value = _weekStart.value
        _timelineNavigationRevision.value++
    }
    fun currentWeek() {
        _weekStart.value = WeekState.startOfWeek(clock.now().atZone(zone).toLocalDate())
        _visibleWeekStart.value = _weekStart.value
        _timelineNavigationRevision.value++
    }
    fun refreshClock() { clockRefreshes.tryEmit(clock.now()) }
    fun dismissError() { _error.value = null }
    fun report(message: String) { _error.value = message }

    /** One health sync pass; quiet mode (automatic sync on open) never disturbs the user. */
    fun runHealthSync(reader: HealthDataReader, settings: HealthSyncSettings, quiet: Boolean = false, onDone: (HealthSyncOutcome?) -> Unit = {}) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                onDone(syncHealthOnce(reader, settings))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("HEALTH-SYNC-ERR ${e::class.simpleName}: ${e.message}")
                settings.setLastResult("同步失败：${e.message ?: "读取健康数据失败"}")
                if (!quiet) _error.value = e.message ?: "健康数据同步失败，请重试"
                onDone(null)
            } finally { _busy.value = false }
        }
    }
    private suspend fun syncHealthOnce(reader: HealthDataReader, settings: HealthSyncSettings): HealthSyncOutcome {
        if (!settings.sleepEnabled() && !settings.exerciseEnabled()) error("请至少启用睡眠或健身记录中的一项")
        val until = repository.now()
        val since = until.minus(Duration.ofDays(settings.windowDays().toLong()))
        val samples = mutableListOf<HealthSample>()
        val messages = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val channelReports = mutableListOf<HealthChannelReport>()
        fun reportChannel(channel: String, state: HealthReadState, records: List<HealthSample> = emptyList(), error: String = "") {
            channelReports += HealthChannelReport(channel, state, records.size, records.maxOfOrNull { it.endAt }, error)
            settings.saveReadReport(HealthReadReport(until.toEpochMilli(), channelReports.toList()))
        }
        var readSucceeded = false
        // Gadgetbridge file channel first: nudge GB to refresh its export, then read it directly.
        val gbUri = settings.gbExportUri()
        if (settings.sleepEnabled() && gbUri.isNotBlank()) {
            try {
                val context = appContext ?: error("应用上下文不可用")
                Nudge.triggerExport(context)
                delay(2500)
                val sleep = withContext(Dispatchers.IO) { GadgetbridgeImporter.readSleepSessions(readGbSnapshot(gbUri), zone, since, until) }
                samples += sleep
                reportChannel("Gadgetbridge 睡眠", if (sleep.isEmpty()) HealthReadState.NO_DATA else HealthReadState.READY, sleep)
                readSucceeded = true
                val latest = sleep.maxOfOrNull { it.endAt }?.let { "，最新 ${Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}" }.orEmpty()
                messages += "Gadgetbridge 文件：读取 ${sleep.size} 夜睡眠$latest"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += "Gadgetbridge 文件读取失败：${error.message ?: "请重新选择导出文件"}"
                reportChannel("Gadgetbridge 睡眠", HealthReadState.FAILED, error = error.message.orEmpty())
            }
        } else reportChannel("Gadgetbridge 睡眠", if (settings.sleepEnabled()) HealthReadState.NOT_CONNECTED else HealthReadState.DISABLED)
        val readerState = try { reader.state(settings.sleepEnabled(), settings.exerciseEnabled()) } catch (error: Exception) {
            if (error is CancellationException) throw error
            failures += "Health Connect 状态检查失败：${error.message.orEmpty()}"
            HealthConnectState.Unavailable
        }
        if (readerState == HealthConnectState.Ready) {
            suspend fun read(label: String, block: suspend () -> List<HealthSample>) {
                try {
                    val records = block()
                    samples += records
                    reportChannel("Health Connect $label", if (records.isEmpty()) HealthReadState.NO_DATA else HealthReadState.READY, records)
                    readSucceeded = true
                    messages += "Health Connect $label：读取 ${records.size} 条"
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failures += "Health Connect $label 读取失败：${error.message ?: "请检查授权"}"
                    reportChannel("Health Connect $label", HealthReadState.FAILED, error = error.message.orEmpty())
                }
            }
            if (settings.sleepEnabled()) read("睡眠") { reader.readSleep(since, until) }
            if (settings.exerciseEnabled()) read("健身") { reader.readExercise(since, until) }
        } else {
            reportChannel("Health Connect", if (failures.any { it.startsWith("Health Connect 状态检查失败") }) HealthReadState.FAILED else if (readerState == HealthConnectState.Unavailable) HealthReadState.UNAVAILABLE else HealthReadState.UNAUTHORIZED)
            val unavailable = if (readerState == HealthConnectState.Unavailable) "Health Connect 不可用" else "Health Connect 所选数据未全部授权"
            if (!readSucceeded && failures.isEmpty()) error("$unavailable；可授权或连接 Gadgetbridge 导出文件")
            messages += "$unavailable，未读取其中的${if (settings.exerciseEnabled()) "健身" else "睡眠"}记录；Gadgetbridge 文件单独处理。"
        }
        if (samples.isEmpty() && failures.isNotEmpty()) error(failures.joinToString("\n"))
        if (samples.isEmpty()) messages += "来源中没有可导入的记录；读取权限不会让来源应用自动产生睡眠数据，请检查导出文件是否更新。"
        val outcome = repository.syncHealthSamples(samples, settings.preferredSleepOrigin().ifBlank { null }).copy(sourceMessages = messages + failures)
        settings.markSynced()
        settings.setLastResult(outcome.summary())
        return outcome
    }
    private fun readGbSnapshot(uri: String): java.io.File {
        val context = appContext ?: throw IllegalStateException("应用上下文不可用")
        val dest = java.io.File(context.cacheDir, "gb-export.db")
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取 GB 导出文件，请重新选择")
        check(dest.length() > 0) { "Gadgetbridge 导出文件为空，请先在 Gadgetbridge 运行数据库导出" }
        return dest
    }

    fun perform(onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch {
            try { action(); onSuccess() }
            catch (e: Exception) { if (e is CancellationException) throw e; _error.value = e.message ?: "保存失败，请重试；编辑内容仍保留" }
            finally { _busy.value = false }
        }
    }
}
