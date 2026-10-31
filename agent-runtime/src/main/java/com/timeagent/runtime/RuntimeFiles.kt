package com.timeagent.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Small android.system.Os wrapper: no host write ever follows a symlink. */
internal object RuntimeFiles {
    fun statOrNull(file: File): StructStat? =
        try {
            Os.lstat(file.path)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) null else throw failure(error)
        }

    fun ensureDirectory(file: File, mode: Int = 0x1C0 /* 0700 */) {
        val stat = statOrNull(file)
        if (stat == null) {
            if (!file.mkdirs()) {
                val created = statOrNull(file) ?: throw IOException("DIRECTORY_CREATE_FAILED")
                if (!OsConstants.S_ISDIR(created.st_mode)) throw IOException("DIRECTORY_CREATE_FAILED")
            }
            Os.chmod(file.path, mode)
        } else if (!OsConstants.S_ISDIR(stat.st_mode)) {
            throw IOException("NOT_A_DIRECTORY")
        }
    }

    fun writeNoFollow(root: File, relative: String, bytes: ByteArray, mode: Int) {
        val normalized = GuestPaths.member(relative, 0) ?: throw IOException("EMPTY_PATH")
        val parts = normalized.split('/')
        var parent = root
        for (part in parts.dropLast(1)) {
            val next = File(parent, part)
            val stat = statOrNull(next)
            if (stat == null) {
                Os.mkdir(next.path, 0x1ED /* 0755 */)
            } else if (!OsConstants.S_ISDIR(stat.st_mode)) {
                throw IOException("WRITE_PARENT_LINK_OR_TYPE")
            }
            parent = next
        }
        val target = File(parent, parts.last())
        val existing = statOrNull(target)
        if (existing != null && OsConstants.S_ISDIR(existing.st_mode)) {
            throw IOException("WRITE_TARGET_DIRECTORY")
        }
        val staged = File(parent, ".timeagent-write-${UUID.randomUUID()}")
        var published = false
        try {
            val descriptor =
                Os.open(
                    staged.path,
                    OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                        OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                    0x180 /* 0600 */,
                )
            FileOutputStream(descriptor).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Os.chmod(staged.path, mode and 0x1FF)
            // rename replaces the leaf itself (file or symlink), never follows it.
            Os.rename(staged.path, target.path)
            published = true
        } finally {
            if (!published && statOrNull(staged) != null) {
                try {
                    Os.remove(staged.path)
                } catch (_: ErrnoException) {
                }
            }
        }
    }

    /** lstat of a normalized relative path, refusing to traverse any symlink parent. */
    fun statInside(root: File, relative: String): StructStat? {
        val parts = relative.split('/')
        var current = root
        for ((index, part) in parts.withIndex()) {
            current = File(current, part)
            val stat = statOrNull(current) ?: return null
            if (index < parts.lastIndex && !OsConstants.S_ISDIR(stat.st_mode)) return null
        }
        return statOrNull(current)
    }

    fun deleteTree(file: File) {
        val stat = statOrNull(file) ?: return
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            val children = file.list() ?: throw IOException("DIRECTORY_LIST_FAILED")
            for (child in children) deleteTree(File(file, child))
        }
        try {
            Os.remove(file.path)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ENOENT) throw failure(error)
        }
    }

    fun failure(error: ErrnoException): IOException =
        IOException(error.message ?: "ERRNO_${error.errno}", error)

    fun isDirectory(stat: StructStat): Boolean = OsConstants.S_ISDIR(stat.st_mode)

    fun isRegular(stat: StructStat): Boolean = OsConstants.S_ISREG(stat.st_mode)

    fun isSymlink(stat: StructStat): Boolean = OsConstants.S_ISLNK(stat.st_mode)
}
