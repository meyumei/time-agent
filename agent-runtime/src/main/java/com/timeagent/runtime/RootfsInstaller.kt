package com.timeagent.runtime

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Installs the pinned archives into filesDir/fish/linux/ubuntu.
 *
 * A staging directory is only used for a fresh/incomplete install. A finished rootfs
 * carries a marker with the lock fingerprint; it is never overwritten by a retry.
 */
internal class RootfsInstaller(
    private val context: Context,
    private val lock: RuntimeLock,
    private val onProgress: (String, Float) -> Unit,
) {
    private val fish = File(context.filesDir, "fish")
    private val base = File(fish, "linux")
    private val rootfs = File(base, "ubuntu")
    private val markerName = ".timeagent-rootfs.json"

    private var lastStage = ""
    private var lastProgress = -1f

    fun ensure(): File = synchronized(INSTALL_LOCK) { ensureLocked() }

    private fun ensureLocked(): File {
        RuntimeFiles.ensureDirectory(fish, 0x1C0 /* 0700 */)
        RuntimeFiles.ensureDirectory(base, 0x1C0)
        removeStaleStaging()

        when (inspectRootfs()) {
            RootfsStatus.READY -> {
                report("运行环境就绪", 1f)
                return rootfs
            }
            RootfsStatus.UPDATE_OVERLAY -> {
                val lengths=lock.assets.associate { it.name to assetLength(it.name) }
                verifyAllAssets(lengths)
                val overlay=lock.assets.single { it.name==RuntimeLock.OWNED_OVERLAY }
                context.assets.open("fish/${overlay.name}").use { input ->
                    ArchiveExtractor(rootfs).extract(input,overlay.strip) { }
                }
                verifyRequired(rootfs)
                writeMarker(rootfs)
                report("运行环境就绪",1f)
                return rootfs
            }
            RootfsStatus.INCOMPLETE -> throw IOException("已有运行目录但安装标记缺失，现有会话和记忆未覆盖")
            RootfsStatus.MISSING -> Unit
        }

        val staging = File(base, ".ubuntu-${UUID.randomUUID()}")
        if (!staging.mkdirs()) throw IOException("ROOTFS_STAGING_CREATE_FAILED")
        Os.chmod(staging.path, 0x1C0)
        try {
            val lengths = lock.assets.associate { it.name to assetLength(it.name) }
            val weights = lock.assets.associate { it.name to lengths.getValue(it.name).coerceAtLeast(1L) }
            val total = weights.values.sum().coerceAtLeast(1L)

            verifyAllAssets(lengths)
            extractAll(staging, weights, total)
            verifyRequired(staging)
            writeMarker(staging)
            publish(staging)
            return rootfs
        } catch (error: Throwable) {
            try {
                RuntimeFiles.deleteTree(staging)
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private enum class RootfsStatus { READY, UPDATE_OVERLAY, INCOMPLETE, MISSING }

    private fun inspectRootfs(): RootfsStatus {
        val rootStat = RuntimeFiles.statOrNull(rootfs) ?: return RootfsStatus.MISSING
        if (!RuntimeFiles.isDirectory(rootStat)) throw IOException("ROOTFS_NOT_A_DIRECTORY")
        val marker = File(rootfs, markerName)
        val markerStat = RuntimeFiles.statOrNull(marker) ?: return RootfsStatus.INCOMPLETE
        if (!RuntimeFiles.isRegular(markerStat)) throw IOException("ROOTFS_MARKER_INVALID")
        if (markerStat.st_size > 64 * 1024) throw IOException("ROOTFS_MARKER_INVALID")
        val installed =
            try {
                JSONObject(String(marker.readBytes(), Charsets.UTF_8))
            } catch (error: Exception) {
                throw IOException("ROOTFS_MARKER_INVALID", error)
            }
        if (installed.optString("fingerprint") != lock.fingerprint()) {
            val archives=installed.optJSONArray("archives")
            if (archives==null || !lock.baseMatches(archives)) throw IOException("ROOTFS_VERSION_MISMATCH")
            return RootfsStatus.UPDATE_OVERLAY
        }
        verifyRequired(rootfs)
        return RootfsStatus.READY
    }

    private fun verifyAllAssets(lengths: Map<String, Long>) {
        val count = lock.assets.size
        for ((index, asset) in lock.assets.withIndex()) {
            val length = lengths.getValue(asset.name)
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            context.assets.open("fish/${asset.name}").use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val amount = input.read(buffer)
                    if (amount < 0) break
                    digest.update(buffer, 0, amount)
                    read += amount
                    if (length > 0) {
                        val fraction = (read.toFloat() / length).coerceIn(0f, 1f)
                        report("校验 ${asset.name}", 0.02f + 0.10f * (index + fraction) / count)
                    }
                }
            }
            val actual = RuntimeLock.hex(digest.digest())
            if (!actual.equals(asset.sha256, ignoreCase = true)) {
                throw IOException("ASSET_HASH_MISMATCH:${asset.name}")
            }
            report("校验 ${asset.name}", 0.02f + 0.10f * (index + 1) / count)
        }
    }

    private fun extractAll(staging: File, weights: Map<String, Long>, total: Long) {
        var completed = 0L
        val totalWeight = total.toFloat()
        for (asset in lock.assets) {
            val weight = weights.getValue(asset.name)
            val start = 0.12f + 0.83f * completed.toFloat() / totalWeight
            val span = 0.83f * weight.toFloat() / totalWeight
            val stage = "解压 ${asset.name}"
            report(stage, start)
            val counting = CountingInputStream(context.assets.open("fish/${asset.name}"))
            try {
                ArchiveExtractor(staging).extract(counting, asset.strip) {
                    val fraction =
                        if (weight > 1L) (counting.count.toFloat() / weight).coerceIn(0f, 1f) else 0f
                    report(stage, start + span * fraction)
                }
            } finally {
                counting.close()
            }
            completed += weight
            report(stage, start + span)
        }
    }

    private fun writeMarker(staging: File) {
        val archives = JSONArray()
        for (asset in lock.assets) {
            archives.put(
                JSONObject()
                    .put("name", asset.name)
                    .put("sha256", asset.sha256)
                    .put("strip", asset.strip),
            )
        }
        val marker =
            JSONObject()
                .put("schema", 1)
                .put("fingerprint", lock.fingerprint())
                .put("archives", archives)
        RuntimeFiles.writeNoFollow(
            staging,
            markerName,
            marker.toString().toByteArray(Charsets.UTF_8),
            0x180 /* 0600 */,
        )
    }

    private fun publish(staging: File) {
        val current = RuntimeFiles.statOrNull(rootfs)
        if (current != null) {
            if (!RuntimeFiles.isDirectory(current)) throw IOException("ROOTFS_PUBLISH_CONFLICT")
            if (RuntimeFiles.statOrNull(File(rootfs, markerName)) != null) {
                throw IOException("ROOTFS_READY_CONFLICT")
            }
            RuntimeFiles.deleteTree(rootfs)
        }
        if (!staging.renameTo(rootfs)) {
            // Another instance may have published the same fingerprint first.
            if (isPublishedByPeer()) {
                RuntimeFiles.deleteTree(staging)
                return
            }
            throw IOException("ROOTFS_PUBLISH_FAILED")
        }
        Os.chmod(rootfs.path, 0x1ED /* 0755 */)
        report("运行环境就绪", 1f)
    }

    private fun isPublishedByPeer(): Boolean {
        if (inspectRootfs() != RootfsStatus.READY) return false
        val marker = File(rootfs, markerName)
        val fingerprint =
            try {
                JSONObject(String(marker.readBytes(), Charsets.UTF_8)).optString("fingerprint")
            } catch (_: Exception) {
                return false
            }
        return fingerprint == lock.fingerprint()
    }

    private fun verifyRequired(stage: File) {
        for (relative in REQUIRED_ENTRIES) {
            // lstat + real parent components: an absolute guest symlink must never
            // be followed against the host while proving the rootfs is complete.
            val stat = RuntimeFiles.statInside(stage, relative)
                ?: throw IOException("ROOTFS_INCOMPLETE:$relative")
            if (!RuntimeFiles.isRegular(stat) && !RuntimeFiles.isSymlink(stat)) {
                throw IOException("ROOTFS_INCOMPLETE:$relative")
            }
            // DSHA's glibc archive ships Python as 0644; its installer adds execute bits.
            if (relative == "usr/bin/python3.12") {
                if (!RuntimeFiles.isRegular(stat)) throw IOException("ROOTFS_PYTHON_NOT_REGULAR")
                val fd = Os.open(File(stage, relative).path,
                    OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
                try { Os.fchmod(fd, (Os.fstat(fd).st_mode and 0x1FF) or 0x49 /* 0111 */) }
                finally { Os.close(fd) }
            }
        }
    }

    private fun removeStaleStaging() {
        val children = base.list() ?: return
        for (name in children) {
            if (name.startsWith(".ubuntu-")) {
                try {
                    RuntimeFiles.deleteTree(File(base, name))
                } catch (error: IOException) {
                    throw IOException("ROOTFS_STAGING_CLEANUP_FAILED", error)
                }
            }
        }
    }

    private fun assetLength(name: String): Long =
        try {
            context.assets.openFd("fish/$name").use { descriptor ->
                if (descriptor.length > 0) descriptor.length else 0L
            }
        } catch (_: IOException) {
            0L
        }

    private fun report(stage: String, progress: Float) {
        val value = progress.coerceIn(0f, 1f)
        if (stage == lastStage && value - lastProgress < 0.005f && value < 0.999f) return
        lastStage = stage
        lastProgress = value
        onProgress(stage, value)
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val amount = super.read(buffer, offset, length)
            if (amount > 0) count += amount
            return amount
        }

        override fun skip(amount: Long): Long {
            val skipped = super.skip(amount)
            if (skipped > 0) count += skipped
            return skipped
        }
    }

    private companion object {
        val INSTALL_LOCK = Any()
        val REQUIRED_ENTRIES =
            listOf(
                "usr/local/bin/node",
                "root/time-agent/host.mjs",
                "usr/bin/python3.12",
            )
    }
}
