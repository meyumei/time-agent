package com.timeagent.runtime

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Minimal process owner for the pinned DSH host inside proot.
 *
 * start() is serialized and idempotent while a session is alive; preparation is shown
 * through [state] and failures can be retried. stop() first asks the JS host to exit,
 * then only terminates the exact Process instance this object launched.
 */
class AgentRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val fishDirectory = File(appContext.filesDir, "fish")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val sendLock = Mutex()

    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _events =
        MutableSharedFlow<JSONObject>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
    val events: SharedFlow<JSONObject> = _events.asSharedFlow()

    @Volatile private var session: Session? = null

    suspend fun start(config: JSONObject) {
        lifecycle.withLock {
            val current = session
            if (current != null) {
                if (current.process.isAlive) return
                retire(current)
                session = null
            }

            _state.value = RuntimeState("准备中", 0f)
            var launching = false
            try {
                val configFile = withContext(Dispatchers.IO) { LocalConfig.write(fishDirectory, config) }
                val plan = withContext(Dispatchers.IO) { prepare(configFile) }
                launching = true
                _state.value = RuntimeState("启动中")
                val started = withContext(Dispatchers.IO) { launch(plan) }
                session = started
                observe(started)
            } catch (cancelled: CancellationException) {
                _state.value = RuntimeState("准备失败", null, false, "启动已取消")
                throw cancelled
            } catch (error: Throwable) {
                _state.value =
                    RuntimeState(
                        stage = if (launching) "启动失败" else "准备失败",
                        progress = null,
                        ready = false,
                        error = describe(error),
                    )
            }
        }
    }

    suspend fun send(message: JSONObject) {
        sendLock.withLock {
            val target = session ?: throw IllegalStateException("RUNTIME_NOT_RUNNING")
            if (target.stopping || !target.process.isAlive) {
                throw IllegalStateException("RUNTIME_NOT_RUNNING")
            }
            try {
                target.writer.write(message.toString())
                target.writer.write("\n")
                target.writer.flush()
            } catch (error: IOException) {
                throw IllegalStateException("RUNTIME_WRITE_FAILED", error)
            }
        }
    }

    suspend fun stop() {
        lifecycle.withLock {
            val target = session ?: return
            target.stopping = true
            _state.value = RuntimeState("停止中", null, false, null)
            try {
                // A stalled send must not prevent the terminate fallback below.
                withTimeoutOrNull(2_000L) {
                    sendLock.withLock {
                        try {
                            if (target.process.isAlive) {
                                target.writer.write("{\"action\":\"stop\"}\n")
                                target.writer.flush()
                            }
                        } catch (_: IOException) {
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    if (target.process.isAlive) {
                        val exited =
                            try {
                                target.process.waitFor(5, TimeUnit.SECONDS)
                            } catch (_: InterruptedException) {
                                false
                            }
                        if (!exited) {
                            target.process.destroy()
                            val terminated =
                                try {
                                    target.process.waitFor(2, TimeUnit.SECONDS)
                                } catch (_: InterruptedException) {
                                    false
                                }
                            if (!terminated) {
                                target.process.destroyForcibly()
                                try {
                                    target.process.waitFor(2, TimeUnit.SECONDS)
                                } catch (_: InterruptedException) {
                                }
                            }
                        }
                    }
                }
            } finally {
                closeStreams(target)
                target.stdoutJob?.cancel()
                target.stderrJob?.cancel()
                target.exitJob?.cancel()
                if (session === target) {
                    session = null
                    _state.value = RuntimeState("已停止", null, false, null)
                }
            }
        }
    }

    private fun prepare(configFile: File): LaunchPlan {
        require(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "内置角色运行环境目前支持 arm64-v8a 手机" }
        val lock = RuntimeLock.read(appContext.assets)
        val installer =
            RootfsInstaller(appContext, lock) { stage, progress ->
                _state.value = RuntimeState(stage, progress, false, null)
            }
        val rootfs = installer.ensure()
        val native = NativeSupport(appContext).prepare()
        AndroidDns.write(appContext, rootfs)
        ensureGuestLayout(rootfs)
        val hardlinks = HardlinkProbe.supported(fishDirectory)
        if (!hardlinks) {
            // link2symlink stores host-absolute links; bind that store at the same path.
            RuntimeFiles.ensureDirectory(File(rootfs, ".l2s"), 0x1C0 /* 0700 */)
        }
        return LaunchPlan(rootfs, native, configFile, hardlinks)
    }

    /** proot bind targets must already exist inside the guest; /tmp is node's TMPDIR. */
    private fun ensureGuestLayout(rootfs: File) {
        for (relative in GUEST_DIRECTORIES) {
            RuntimeFiles.ensureDirectory(File(rootfs, relative), 0x1ED /* 0755 */)
        }
        val guestTmp = File(rootfs, "tmp")
        if (RuntimeFiles.statOrNull(guestTmp) == null) {
            RuntimeFiles.ensureDirectory(guestTmp, 0x1FF /* 0777 */)
        }
        // Bind source target: a regular placeholder guarantees the file bind has a
        // real guest leaf; the host config replaces this view at exec time.
        RuntimeFiles.writeNoFollow(
            rootfs,
            "root/time-agent-config.json",
            ByteArray(0),
            0x180 /* 0600 */,
        )
    }

    private fun launch(plan: LaunchPlan): Session {
        val process =
            ProcessBuilder(argv(plan))
                .apply {
                    directory(appContext.filesDir)
                    environment().apply {
                        put("HOME", "/root")
                        put("PATH", "/usr/local/bin:/usr/bin:/bin")
                        put("LANG", "C.UTF-8")
                        put("TZ", java.util.TimeZone.getDefault().id)
                        put("DSH_HOME", "/root/.timeagent-dsh")
                        put("NARB_DISABLE_NATIVE_CACHE", "1")
                        put("SSL_CERT_FILE", "/usr/local/share/time-agent/cacert.pem")
                        put("REQUESTS_CA_BUNDLE", "/usr/local/share/time-agent/cacert.pem")
                        put("NODE_EXTRA_CA_CERTS", "/usr/local/share/time-agent/cacert.pem")
                        put("TMPDIR", "/tmp")
                        put("PROOT_TMP_DIR", plan.native.temporaryDirectory.path)
                        put("PROOT_LOADER", plan.native.loader.path)
                        if (!plan.hardlinks) {
                            put("PROOT_L2S_DIR", File(plan.rootfs, ".l2s").path)
                        }
                        put(
                            "LD_LIBRARY_PATH",
                            plan.native.libraryDirectory.path + ":" +
                                (plan.native.proot.parentFile?.path
                                    ?: plan.native.nativeDirectory.path),
                        )
                        remove("LD_PRELOAD")
                    }
                }
                .start()
        return Session(
            process,
            BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8)),
        )
    }

    private fun argv(plan: LaunchPlan): List<String> {
        val arguments = ArrayList<String>()
        arguments.add(plan.native.proot.path)
        // Private app storage supports hardlinks on normal Android filesystems; only
        // emulate them when the probe failed (link2symlink breaks dsh file writes).
        if (!plan.hardlinks) arguments.add("--link2symlink")
        arguments.add("-L")
        arguments.add("--kill-on-exit")
        arguments.add("-0")
        arguments.add("--rootfs=${plan.rootfs.path}")
        arguments.add("--cwd=/root")
        for ((host, guest) in BINDS) {
            if (File(host).exists()) {
                arguments.add("-b")
                arguments.add("$host:$guest")
            }
        }
        if (!plan.hardlinks) {
            val l2s = File(plan.rootfs, ".l2s")
            arguments.add("-b")
            arguments.add("${l2s.path}:${l2s.path}")
        }
        arguments.add("-b")
        arguments.add("${plan.configFile.path}:/root/time-agent-config.json")
        arguments.add("/usr/local/bin/node")
        arguments.add("/root/time-agent/host.mjs")
        arguments.add("/root/time-agent-config.json")
        return arguments
    }

    private fun observe(target: Session) {
        target.stdoutJob = scope.launch {
            try {
                target.process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        currentCoroutineContext().ensureActive()
                        if (target.stopping || session !== target) break
                        val event = StdoutEvents.parse(line) ?: continue
                        handle(target, event)
                        if (target.stopping || session !== target) break
                        _events.emit(event)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
            }
        }
        target.stderrJob = scope.launch {
            try {
                target.process.errorStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (input.read(buffer) >= 0) {
                        currentCoroutineContext().ensureActive()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
            }
        }
        target.exitJob = scope.launch {
            val code =
                try {
                    target.process.waitFor()
                } catch (_: InterruptedException) {
                    -1
                }
            target.stdoutJob?.join()
            target.stderrJob?.join()
            lifecycle.withLock {
                if (session === target && !target.stopping) {
                    session = null
                    closeQuietly(target.writer)
                    _state.value =
                        RuntimeState(
                            stage = "已停止",
                            progress = null,
                            ready = false,
                            error = if (code == 0) null else "运行时进程退出（code=$code）",
                        )
                }
            }
        }
    }

    private fun handle(target: Session, event: JSONObject) {
        if (session !== target || target.stopping) return
        when (event.optString("type")) {
            "ready" ->
                _state.update {
                    it.copy(stage = "运行中", progress = null, ready = true, error = null)
                }
            "error" -> {
                val message =
                    event.optString("message")
                        .ifBlank { event.optString("error") }
                        .ifBlank { "运行时错误" }
                _state.update {
                    it.copy(stage = "运行错误", progress = null, ready = false, error = message.take(500))
                }
            }
        }
    }

    private fun retire(target: Session) {
        target.stopping = true
        closeStreams(target)
        target.stdoutJob?.cancel()
        target.stderrJob?.cancel()
        target.exitJob?.cancel()
    }

    private fun closeStreams(target: Session) {
        closeQuietly(target.writer)
        try {
            target.process.inputStream.close()
        } catch (_: IOException) {
        }
        try {
            target.process.errorStream.close()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(writer: BufferedWriter) {
        try {
            writer.close()
        } catch (_: IOException) {
        }
    }

    private fun describe(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).replace('\n', ' ').take(300)

    private class Session(val process: Process, val writer: BufferedWriter) {
        @Volatile var stopping = false
        var stdoutJob: Job? = null
        var stderrJob: Job? = null
        var exitJob: Job? = null
    }

    private data class LaunchPlan(
        val rootfs: File,
        val native: NativeSupport.Files,
        val configFile: File,
        val hardlinks: Boolean,
    )

    private companion object {
        val GUEST_DIRECTORIES = listOf("dev", "proc", "sys", "system", "apex", "root")
        val BINDS =
            listOf(
                "/dev" to "/dev",
                "/proc" to "/proc",
                "/sys" to "/sys",
                "/system" to "/system",
                "/apex" to "/apex",
            )
    }
}
