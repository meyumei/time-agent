package com.timeagent.runtime

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

/** filesDir/fish/config.json, owner-only, replaced atomically. Contents are never logged. */
internal object LocalConfig {
    private const val MAX_BYTES = 4 * 1024 * 1024

    fun write(fishDirectory: File, config: JSONObject): File {
        RuntimeFiles.ensureDirectory(fishDirectory, 0x1C0 /* 0700 */)
        val target = File(fishDirectory, "config.json")
        val temporary = File(fishDirectory, ".config-${UUID.randomUUID()}")
        val bytes = config.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("CONFIG_TOO_LARGE")
        var published = false
        try {
            val descriptor =
                Os.open(
                    temporary.path,
                    OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                        OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                    0x180 /* 0600 */,
                )
            FileOutputStream(descriptor).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Os.chmod(temporary.path, 0x180)
            val existing = RuntimeFiles.statOrNull(target)
            if (existing != null && RuntimeFiles.isDirectory(existing)) {
                throw IOException("CONFIG_PATH_IS_DIRECTORY")
            }
            // rename replaces a stale file or symlink without opening through it.
            Os.rename(temporary.path, target.path)
            published = true
            Os.chmod(target.path, 0x180)
            return target
        } finally {
            if (!published && RuntimeFiles.statOrNull(temporary) != null) {
                try {
                    Os.remove(temporary.path)
                } catch (_: Exception) {
                }
            }
        }
    }
}
