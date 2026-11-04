package com.timeagent.fish

import android.content.Context
import android.content.Intent
import android.os.Build
import com.timeagent.assistant.AssistantSettings
import com.timeagent.runtime.RuntimeState
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Owns the lifecycle of the single DSH runtime: key/arch/rootfs gates, the foreground
 * service, the state machine from spec 07 §2.1, and the bounded auto-restart policy.
 *
 * It never starts a second fish and never stores a second copy of the shared memory.
 */
class FishRuntimeController(
    context: Context,
    private val scope: CoroutineScope,
    private val buildConfig: suspend (apiKey: String, model: String) -> JSONObject,
) {
    private val appContext = context.applicationContext
    private val settings = AssistantSettings(appContext)
    private val preferences = appContext.getSharedPreferences("fish_runtime", Context.MODE_PRIVATE)
    private val lifecycle = Mutex()
    private val lock = Any()

    private val _autoStart = MutableStateFlow(preferences.getBoolean(KEY_AUTO_START, true))
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _status = MutableStateFlow<FishRuntimeStatus>(FishRuntimeStatus.Stopped)
    val status: StateFlow<FishRuntimeStatus> = _status.asStateFlow()

    /** Legacy shape used by existing fish screens and FishRuntimeService. */
    private val _state = MutableStateFlow(FishRuntimeStatus.Stopped.toView())
    val state: StateFlow<FishRuntimeView> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _restarting = MutableStateFlow(false)
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

    private val _restartSuspended = MutableStateFlow(false)
    val restartSuspended: StateFlow<Boolean> = _restartSuspended.asStateFlow()

    private val _consecutiveFailures = MutableStateFlow(0)
    val consecutiveFailures: StateFlow<Int> = _consecutiveFailures.asStateFlow()

    private val _protocol = MutableStateFlow<FishProtocol?>(null)
    val protocol: StateFlow<FishProtocol?> = _protocol.asStateFlow()

    val rpc = FishRpcClient(appContext, scope)

    /** Raw host frames, kept for callers that used AgentRuntime.events directly. */
    val events: kotlinx.coroutines.flow.SharedFlow<JSONObject> = rpc.events

    private var wantRunning = false
    private var attemptFailed = false
    private var failures = 0
    private var suspended = false
    private var busy = false
    private var restartJob: Job? = null
    @Volatile private var userStopped = false

    init {
        scope.launch {
            rpc.state.collect { applyRuntimeState(it) }
        }
        scope.launch {
            rpc.events.collect { event ->
                if (event.optString("type") == "ready") onProtocolReady(event)
            }
        }
        scope.launch { refreshEnvironment() }
    }

    /** Recomputes blockers after the Key, model or rootfs changed. */
    suspend fun refreshEnvironment() {
        lifecycle.withLock {
        val current = _status.value
        if (current.active) return
        if (!supportedDevice()) {
            publish(FishRuntimeStatus.UnsupportedDevice)
            return
        }
        val key = readKey()
        // A start may have begun while the encrypted key was being read.
        if (_status.value.active) return
        if (key.error != null) {
            publish(FishRuntimeStatus.NeedsKey("无法读取已保存的 API Key：${key.error}"))
            return
        }
        if (key.value.isNullOrBlank()) {
            publish(FishRuntimeStatus.NeedsKey("请先在模型设置中填写 API Key；全部本地业务仍可使用。"))
            return
        }
        publish(
            when (rootfsState()) {
                RootfsState.MISSING -> FishRuntimeStatus.NotInstalled
                RootfsState.DAMAGED -> FishRuntimeStatus.Failed(
                    FishRuntimeFailure.ROOTFS_CORRUPT,
                    "已有运行目录但安装标记缺失；为保护现有会话和共同记忆不会覆盖，请查看运行日志",
                )
                RootfsState.READY -> FishRuntimeStatus.Stopped
            },
        )
        }
    }

    /** Manual start (resets the retry counter) or on-demand auto start. */
    suspend fun start(manual: Boolean = true) {
        lifecycle.withLock {
            if (!supportedDevice()) {
                publish(FishRuntimeStatus.UnsupportedDevice)
                return
            }
            val current = _status.value
            if (current is FishRuntimeStatus.Ready || current is FishRuntimeStatus.Busy ||
                current is FishRuntimeStatus.Starting || current is FishRuntimeStatus.Installing ||
                current is FishRuntimeStatus.Stopping
            ) {
                return
            }
            if (!manual && current is FishRuntimeStatus.Failed && current.reason.terminal) return

            if (manual) {
                resetFailures()
                userStopped = false
            }
            synchronized(lock) {
                wantRunning = true
                attemptFailed = false
                restartJob?.cancel()
                restartJob = null
            }
            _restarting.value = false

            val key = readKey()
            if (key.error != null) {
                stopWanting()
                publish(FishRuntimeStatus.NeedsKey("无法读取已保存的 API Key：${key.error}"))
                return
            }
            val apiKey = key.value
            if (apiKey.isNullOrBlank()) {
                stopWanting()
                publish(FishRuntimeStatus.NeedsKey("请先在模型设置中填写 API Key；全部本地业务仍可使用。"))
                return
            }

            when (rootfsState()) {
                RootfsState.DAMAGED -> {
                    stopWanting()
                    publish(FishRuntimeStatus.Failed(
                        FishRuntimeFailure.ROOTFS_CORRUPT,
                        "已有运行目录但安装标记缺失；现有会话和共同记忆不会被覆盖",
                    ))
                    return
                }
                RootfsState.MISSING -> publish(FishRuntimeStatus.Installing("准备运行环境", null))
                RootfsState.READY -> publish(FishRuntimeStatus.Starting)
            }

            try {
                appContext.startForegroundService(Intent(appContext, FishRuntimeService::class.java))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(FishRuntimeFailure.UNKNOWN, "无法启动前台服务：${error.message ?: error.javaClass.simpleName}")
                return
            }

            try {
                rpc.start(buildConfig(apiKey, settings.model()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(classifyRuntimeFailure(error.message), error.message ?: "运行环境启动失败")
                return
            }

            val raw = rpc.state.value
            if (raw.error != null) {
                fail(classifyRuntimeFailure(raw.error, exitCodeFrom(raw.error)), raw.error)
            } else {
                applyRuntimeState(raw)
            }
        }
    }

    /** Retry is always manual and clears the three-failure counter. */
    fun retry() {
        scope.launch { start(manual = true) }
    }

    /**
     * On-demand auto start. It deliberately does nothing while the runtime is already
     * moving, blocked by Key/arch, or when automatic restart was suspended.
     */
    fun ensureRunning(reason: String) {
        if (!_autoStart.value || userStopped) return
        val current = _status.value
        if (current.active) return
        if (current is FishRuntimeStatus.NeedsKey || current is FishRuntimeStatus.UnsupportedDevice) return
        if (current is FishRuntimeStatus.Failed && (current.reason.terminal || suspended)) return
        val blocked = synchronized(lock) {
            failures >= MAX_FAILURES || suspended
        }
        if (blocked) return
        log("按需自动启动：$reason")
        scope.launch { start(manual = false) }
    }

    suspend fun stop(userIntent: Boolean = true) {
        // A failed runtime may still hold a wedged process. When an automatic restart is
        // pending, its cleanup owns the process; otherwise release it quietly so the
        // failure reason stays visible.
        if (_status.value is FishRuntimeStatus.Failed) {
            val restartPending = synchronized(lock) {
                wantRunning && _autoStart.value && !suspended && failures < MAX_FAILURES
            }
            if (restartPending) return
            try {
                rpc.stop()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
            appContext.stopService(Intent(appContext, FishRuntimeService::class.java))
            return
        }
        val skip = synchronized(lock) {
            when (_status.value) {
                // Starting/Installing are not user-stoppable in the current UI; a call here
                // is the previous foreground service finishing onDestroy after a restart.
                FishRuntimeStatus.Starting -> true
                is FishRuntimeStatus.Installing -> true
                is FishRuntimeStatus.Stopping -> true
                FishRuntimeStatus.Stopped -> !wantRunning
                is FishRuntimeStatus.NeedsKey -> true
                FishRuntimeStatus.UnsupportedDevice -> true
                FishRuntimeStatus.NotInstalled -> true
                else -> {
                    wantRunning = false
                    if (userIntent) userStopped = true
                    restartJob?.cancel()
                    restartJob = null
                    _restarting.value = false
                    false
                }
            }
        }
        if (skip) return
        publish(FishRuntimeStatus.Stopping)
        try {
            rpc.stop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        appContext.stopService(Intent(appContext, FishRuntimeService::class.java))
        publish(FishRuntimeStatus.Stopped)
    }

    fun setBusy(value: Boolean) {
        synchronized(lock) { busy = value }
        val current = _status.value
        when {
            value && current is FishRuntimeStatus.Ready -> publish(FishRuntimeStatus.Busy)
            !value && current is FishRuntimeStatus.Busy -> publish(FishRuntimeStatus.Ready)
        }
    }

    fun setAutoStart(enabled: Boolean) {
        _autoStart.value = enabled
        preferences.edit().putBoolean(KEY_AUTO_START, enabled).apply()
        if (!enabled) {
            synchronized(lock) {
                wantRunning = false
                restartJob?.cancel()
                restartJob = null
            }
            _restarting.value = false
            log("已关闭需要时自动启动")
        } else {
            userStopped = false
            log("已开启需要时自动启动")
        }
    }

    private fun onProtocolReady(event: JSONObject) {
        val negotiated = rpc.negotiate(event)
        _protocol.value = negotiated
        if (!negotiated.supported) {
            stopWanting()
            publish(FishRuntimeStatus.Failed(FishRuntimeFailure.PROTOCOL_INCOMPATIBLE, negotiated.reason))
            log("协议协商失败：${negotiated.reason}")
            scope.launch {
                try {
                    rpc.stop()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
                appContext.stopService(Intent(appContext, FishRuntimeService::class.java))
            }
            return
        }
        resetFailures()
        log(
            "协议协商通过：${negotiated.dshVersion} / ${negotiated.memory}" +
                if (negotiated.sessionId.isBlank()) "" else " / ${negotiated.sessionId}",
        )
        if (rpc.state.value.ready) publish(if (synchronized(lock) { busy }) FishRuntimeStatus.Busy else FishRuntimeStatus.Ready)
    }

    private fun applyRuntimeState(raw: RuntimeState) {
        val current = _status.value
        if (current is FishRuntimeStatus.Stopping) return

        if (raw.error != null) {
            if (current is FishRuntimeStatus.Failed && current.reason.terminal) return
            fail(classifyRuntimeFailure(raw.error, exitCodeFrom(raw.error)), raw.error)
            return
        }

        if (raw.ready) {
            if (current is FishRuntimeStatus.Failed && current.reason.terminal) return
            if (_protocol.value?.supported == false) return
            resetFailures()
            val busyNow = synchronized(lock) { busy }
            if (current is FishRuntimeStatus.Ready && !busyNow) return
            if (current is FishRuntimeStatus.Busy && busyNow) return
            publish(if (busyNow) FishRuntimeStatus.Busy else FishRuntimeStatus.Ready)
            return
        }

        when {
            raw.progress != null -> publish(FishRuntimeStatus.Installing(raw.stage, raw.progress))
            raw.stage == "准备中" -> publish(FishRuntimeStatus.Installing(raw.stage, null))
            raw.stage == "运行环境就绪" -> publish(FishRuntimeStatus.Installing(raw.stage, 1f))
            raw.stage == "启动中" -> if (current !is FishRuntimeStatus.Starting) publish(FishRuntimeStatus.Starting)
            raw.stage == "停止中" -> if (current !is FishRuntimeStatus.Failed) publish(FishRuntimeStatus.Stopping)
            raw.stage == "已停止" -> {
                if (current is FishRuntimeStatus.Failed || current is FishRuntimeStatus.Stopped ||
                    current is FishRuntimeStatus.NeedsKey || current is FishRuntimeStatus.UnsupportedDevice ||
                    current is FishRuntimeStatus.NotInstalled
                ) {
                    return
                }
                val shouldRestart = synchronized(lock) {
                    wantRunning && _autoStart.value && !suspended && failures < MAX_FAILURES
                }
                if (shouldRestart) {
                    fail(FishRuntimeFailure.PROCESS_EXIT, "运行时进程已退出", 0)
                } else {
                    stopWanting()
                    publish(FishRuntimeStatus.Stopped)
                }
            }
            raw.stage == "未准备" -> Unit
            else -> Unit
        }
    }

    private fun fail(reason: FishRuntimeFailure, detail: String, exitCode: Int? = null) {
        var restart = false
        var nowSuspended = false
        synchronized(lock) {
            if (!attemptFailed) {
                failures += 1
                attemptFailed = true
            }
            if (reason.terminal) wantRunning = false
            if (failures >= MAX_FAILURES) {
                suspended = true
                wantRunning = false
            }
            restart = wantRunning && _autoStart.value && !suspended && !reason.terminal &&
                failures < MAX_FAILURES
            nowSuspended = suspended
            _consecutiveFailures.value = failures
            _restartSuspended.value = suspended
        }
        val detailWithPolicy = if (nowSuspended && !restart) {
            "$detail；连续失败 $MAX_FAILURES 次，已停止自动重启，可在运行设置手动重试"
        } else {
            detail
        }
        publish(FishRuntimeStatus.Failed(reason, detailWithPolicy, exitCode))
        if (restart) {
            scheduleRestart()
        } else if (nowSuspended) {
            log("连续失败 $MAX_FAILURES 次，已停止自动重启；可在运行设置手动重试")
        }
    }

    private fun scheduleRestart() {
        var waitMillis = RESTART_BACKOFF_MS
        synchronized(lock) {
            if (restartJob?.isActive == true) return
            waitMillis = failures.coerceAtLeast(1).toLong() * RESTART_BACKOFF_MS
            _restarting.value = true
            restartJob = scope.launch {
                delay(waitMillis)
                // Terminate a process that survived the failure before opening a new one.
                try {
                    rpc.stop()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
                appContext.stopService(Intent(appContext, FishRuntimeService::class.java))
                synchronized(lock) {
                    _restarting.value = false
                    restartJob = null
                }
                val allowed = synchronized(lock) {
                    wantRunning && _autoStart.value && !suspended && failures < MAX_FAILURES
                }
                if (allowed) {
                    log("自动重启（第 ${failures + 1} 次尝试）")
                    start(manual = false)
                }
            }
        }
        log("将在 ${waitMillis / 1000} 秒后自动重启")
    }

    private fun resetFailures() {
        synchronized(lock) {
            failures = 0
            attemptFailed = false
            suspended = false
            _consecutiveFailures.value = 0
            _restartSuspended.value = false
        }
    }

    private fun stopWanting() {
        synchronized(lock) {
            wantRunning = false
            attemptFailed = false
            restartJob?.cancel()
            restartJob = null
        }
        _restarting.value = false
    }

    @Synchronized
    private fun publish(next: FishRuntimeStatus) {
        val previous = _status.value
        _status.value = next
        _state.value = next.toView()
        val significant = previous::class != next::class ||
            (next is FishRuntimeStatus.Failed && next != previous) ||
            (next is FishRuntimeStatus.NeedsKey && next != previous)
        if (significant) log(describe(next))
    }

    @Synchronized
    private fun log(line: String) {
        val stamped = "${LocalTime.now().format(TIME)} $line"
        val next = (_log.value + stamped).takeLast(80)
        _log.value = next
    }

    private fun describe(status: FishRuntimeStatus): String = when (status) {
        FishRuntimeStatus.NotInstalled -> "运行环境尚未安装"
        is FishRuntimeStatus.Installing -> "准备运行环境：${status.stage}"
        FishRuntimeStatus.Stopped -> "已停止"
        FishRuntimeStatus.Starting -> "启动中"
        FishRuntimeStatus.Ready -> "运行中"
        FishRuntimeStatus.Busy -> "运行中，正在处理"
        FishRuntimeStatus.Stopping -> "停止中"
        is FishRuntimeStatus.Failed -> "失败（${status.reason.label}）：${status.detail}"
        is FishRuntimeStatus.NeedsKey -> "需要 API Key：${status.detail}"
        FishRuntimeStatus.UnsupportedDevice -> "当前设备不支持（非 arm64-v8a）"
    }

    private fun supportedDevice(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private fun rootfsState(): RootfsState {
        val rootfs = File(appContext.filesDir, "fish/linux/ubuntu")
        if (!rootfs.exists()) return RootfsState.MISSING
        if (!rootfs.isDirectory) return RootfsState.DAMAGED
        return if (File(rootfs, ROOTFS_MARKER).isFile) RootfsState.READY else RootfsState.DAMAGED
    }

    private suspend fun readKey(): KeyRead = try {
        KeyRead(settings.readApiKey(), null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        KeyRead(null, error.message ?: "密钥读取失败")
    }

    private data class KeyRead(val value: String?, val error: String?)

    private enum class RootfsState { MISSING, READY, DAMAGED }

    private companion object {
        const val KEY_AUTO_START = "auto-start"
        const val ROOTFS_MARKER = ".timeagent-rootfs.json"
        const val MAX_FAILURES = 3
        const val RESTART_BACKOFF_MS = 2_000L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
