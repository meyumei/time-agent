package com.timeagent.runtime

import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Private app storage normally supports hardlinks; only fake it when the probe fails. */
internal object HardlinkProbe {
    fun supported(directory: File): Boolean {
        val probe = File(directory, ".hardlink-${UUID.randomUUID()}")
        if (!probe.mkdirs()) return false
        return try {
            val first = File(probe, "first")
            FileOutputStream(first).use { it.write(1) }
            Os.link(first.path, File(probe, "second").path)
            true
        } catch (_: ErrnoException) {
            false
        } catch (_: IOException) {
            false
        } finally {
            try {
                RuntimeFiles.deleteTree(probe)
            } catch (_: IOException) {
            } catch (_: ErrnoException) {
            }
        }
    }
}
