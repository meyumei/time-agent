package com.timeagent.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * libproot executes from nativeLibraryDir (Android W^X). Its private dependencies are
 * copied to filesDir/fish/lib under the exact loader names proot expects.
 */
internal class NativeSupport(private val context: Context) {

    data class Files(
        val proot: File,
        val loader: File,
        val libraryDirectory: File,
        val temporaryDirectory: File,
        val nativeDirectory: File,
    )

    fun prepare(): Files {
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val proot = find(nativeDirectory, "libproot.so")
        val loader = find(nativeDirectory, "libprootloader.so")
        if (!proot.canExecute()) throw IOException("PROOT_NOT_EXECUTABLE")

        val fish = File(context.filesDir, "fish")
        RuntimeFiles.ensureDirectory(fish, 0x1C0 /* 0700 */)
        val libraryDirectory = File(fish, "lib")
        val temporaryDirectory = File(fish, "tmp")
        RuntimeFiles.ensureDirectory(libraryDirectory, 0x1C0)
        RuntimeFiles.ensureDirectory(temporaryDirectory, 0x1C0)

        copy(find(nativeDirectory, "libtalloc.so"), File(libraryDirectory, "libtalloc.so.2"))
        copy(
            find(nativeDirectory, "libandroidshmem.so"),
            File(libraryDirectory, "libandroid-shmem.so"),
        )
        return Files(proot, loader, libraryDirectory, temporaryDirectory, nativeDirectory)
    }

    private fun find(nativeDirectory: File, name: String): File {
        val direct = File(nativeDirectory, name)
        if (direct.isFile && direct.length() in 1..MAX_BYTES) return direct
        val parent = nativeDirectory.parentFile ?: throw IOException("NATIVE_LIBRARY_MISSING:$name")
        val entries = parent.listFiles()
        if (entries != null) {
            for (directory in entries) {
                if (!directory.isDirectory) continue
                val candidate = File(directory, name)
                if (candidate.isFile && candidate.length() in 1..MAX_BYTES) return candidate
            }
        }
        throw IOException("NATIVE_LIBRARY_MISSING:$name")
    }

    private fun copy(source: File, target: File) {
        val length = source.length()
        if (length !in 1..MAX_BYTES) throw IOException("NATIVE_LIBRARY_INVALID:${source.name}")
        val existing = RuntimeFiles.statOrNull(target)
        if (existing != null && RuntimeFiles.isDirectory(existing)) {
            throw IOException("NATIVE_LIBRARY_TARGET_DIRECTORY")
        }
        val temporary = File(target.parentFile, ".native-${UUID.randomUUID()}")
        var published = false
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val amount = input.read(buffer)
                        if (amount < 0) break
                        output.write(buffer, 0, amount)
                        copied += amount
                        if (copied > MAX_BYTES) throw IOException("NATIVE_LIBRARY_TOO_LARGE")
                    }
                    output.fd.sync()
                    if (copied != length) throw IOException("NATIVE_LIBRARY_COPY_TRUNCATED")
                }
            }
            Os.chmod(temporary.path, 0x180 /* 0600 */)
            Os.rename(temporary.path, target.path)
            published = true
        } finally {
            if (!published && RuntimeFiles.statOrNull(temporary) != null) {
                try {
                    Os.remove(temporary.path)
                } catch (_: Exception) {
                }
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 16L * 1024 * 1024
    }
}
