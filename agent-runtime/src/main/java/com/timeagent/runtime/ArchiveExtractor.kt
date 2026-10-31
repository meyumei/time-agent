package com.timeagent.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.UUID
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Trusted-asset tar extraction for one archive into one candidate rootfs.
 *
 * - member names and link targets are constrained by [GuestPaths]
 * - every file is staged beside its destination and renamed over the leaf, never
 *   opened through an existing symlink
 * - symlinks are created last; a later file can never use one as a parent
 * - modes come from the archive (no blanket chmod 777)
 */
internal class ArchiveExtractor(private val root: File) {
    private val verifiedDirectories = HashSet<String>()
    private val directoryModes = LinkedHashMap<String, Int>()
    private val seenFiles = HashSet<String>()
    private val seenDirectories = HashSet<String>()
    private val pendingLinks = ArrayList<PendingLink>()

    private data class PendingLink(val path: String, val target: String, val hard: Boolean)

    fun extract(input: InputStream, strip: Int, checkpoint: () -> Unit) {
        val rootStat = RuntimeFiles.statOrNull(root) ?: throw IOException("ARCHIVE_ROOT_MISSING")
        if (!RuntimeFiles.isDirectory(rootStat)) throw IOException("ARCHIVE_ROOT_LINK_OR_TYPE")

        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val tarInput: InputStream =
            if (first == 0x1f && second == 0x8b) {
                GzipCompressorInputStream(buffered, true)
            } else {
                buffered
            }

        TarArchiveInputStream(tarInput).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name ?: ""
                if (name.isEmpty()) {
                    checkpoint()
                    continue
                }
                val path = GuestPaths.member(name, strip)
                if (path != null) {
                    try {
                        when {
                            entry.isDirectory -> addDirectory(path, mode(entry, 0x1ED))
                            entry.isSymbolicLink -> {
                                claimFile(path)
                                val target = GuestPaths.symlink(path, entry.linkName ?: "")
                                pendingLinks.add(PendingLink(path, target, hard = false))
                            }
                            entry.isLink -> {
                                claimFile(path)
                                val target =
                                    GuestPaths.hardlink(path, entry.linkName ?: "", strip)
                                pendingLinks.add(PendingLink(path, target, hard = true))
                            }
                            entry.isFile -> {
                                claimFile(path)
                                writeFile(tar, entry, path, checkpoint)
                            }
                            else -> Unit // device/fifo entries are not needed by this runtime
                        }
                    } catch (error: IOException) {
                        throw IOException("ARCHIVE_ENTRY:$path:${error.message}", error)
                    }
                }
                checkpoint()
            }
        }

        pendingLinks.forEach(::installLink)
        applyDirectoryModes()
        val finalRoot = RuntimeFiles.statOrNull(root) ?: throw IOException("ARCHIVE_ROOT_CHANGED")
        if (!RuntimeFiles.isDirectory(finalRoot)) throw IOException("ARCHIVE_ROOT_CHANGED")
        Os.chmod(root.path, 0x1ED /* 0755 */)
    }

    private fun claimFile(path: String) {
        if (!seenFiles.add(path) || path in seenDirectories) {
            throw IOException("ARCHIVE_DUPLICATE")
        }
    }

    private fun addDirectory(path: String, mode: Int) {
        if (path in seenFiles) throw IOException("ARCHIVE_DUPLICATE")
        seenDirectories.add(path)
        val destination = File(root, path)
        ensureParents(path)
        val stat = RuntimeFiles.statOrNull(destination)
        if (stat == null) {
            Os.mkdir(destination.path, 0x1C0 /* 0700 */)
        } else if (!RuntimeFiles.isDirectory(stat)) {
            throw IOException("ARCHIVE_DIRECTORY_CONFLICT")
        }
        verifiedDirectories.add(path)
        directoryModes[path] = mode
    }

    private fun writeFile(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        path: String,
        checkpoint: () -> Unit,
    ) {
        val destination = File(root, path)
        val parent = ensureParents(path)
        val existing = RuntimeFiles.statOrNull(destination)
        if (existing != null && RuntimeFiles.isDirectory(existing)) {
            throw IOException("ARCHIVE_FILE_DIRECTORY_CONFLICT")
        }
        val staged = File(parent, ".timeagent-tar-${UUID.randomUUID()}")
        var published = false
        try {
            val descriptor =
                Os.open(
                    staged.path,
                    OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                        OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                    0x180 /* 0600 */,
                )
            var written = 0L
            FileOutputStream(descriptor).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = tar.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    written += count
                    checkpoint()
                }
                output.fd.sync()
            }
            if (written != entry.size) throw IOException("ARCHIVE_SIZE_MISMATCH")
            Os.chmod(staged.path, mode(entry, 0x1A4 /* 0644 */))
            // rename(2) replaces the leaf itself; it never follows a pre-existing symlink.
            Os.rename(staged.path, destination.path)
            published = true
        } finally {
            if (!published && RuntimeFiles.statOrNull(staged) != null) {
                try {
                    Os.remove(staged.path)
                } catch (_: ErrnoException) {
                }
            }
        }
    }

    private fun installLink(link: PendingLink) {
        val destination = File(root, link.path)
        ensureParents(link.path)
        val existing = RuntimeFiles.statOrNull(destination)
        if (link.hard) {
            // Never let a hardlink source traverse an archive symlink: an absolute
            // guest link would otherwise resolve against the host and link host files.
            val source = realSource(link.target)
            val sourceStat = RuntimeFiles.statOrNull(source) ?: throw IOException("ARCHIVE_LINK_SOURCE_MISSING")
            if (!RuntimeFiles.isRegular(sourceStat)) throw IOException("ARCHIVE_LINK_SOURCE_TYPE")
            if (existing != null) {
                if (!RuntimeFiles.isRegular(existing) ||
                    existing.st_dev != sourceStat.st_dev ||
                    existing.st_ino != sourceStat.st_ino
                ) {
                    throw IOException("ARCHIVE_LINK_CONFLICT")
                }
                return
            }
            Os.link(source.path, destination.path)
        } else {
            if (existing != null) {
                if (!RuntimeFiles.isSymlink(existing)) throw IOException("ARCHIVE_LINK_CONFLICT")
                val current = Os.readlink(destination.path)
                if (current != link.target) throw IOException("ARCHIVE_LINK_CONFLICT")
                return
            }
            Os.symlink(link.target, destination.path)
        }
    }

    private fun realSource(relative: String): File {
        var current = root
        val parts = relative.split('/')
        for ((index, part) in parts.withIndex()) {
            current = File(current, part)
            val stat = RuntimeFiles.statOrNull(current) ?: throw IOException("ARCHIVE_LINK_SOURCE_MISSING")
            if (index < parts.lastIndex) {
                if (!RuntimeFiles.isDirectory(stat)) throw IOException("ARCHIVE_LINK_SOURCE_LINK")
            }
        }
        return current
    }

    /** Creates/verifies each real directory component and returns the leaf's parent. */
    private fun ensureParents(path: String): File {
        val parts = path.split('/').dropLast(1)
        var parent = root
        val prefix = StringBuilder()
        for (part in parts) {
            if (prefix.isNotEmpty()) prefix.append('/')
            prefix.append(part)
            val next = File(parent, part)
            val key = prefix.toString()
            if (key !in verifiedDirectories) {
                val stat = RuntimeFiles.statOrNull(next)
                if (stat == null) {
                    Os.mkdir(next.path, 0x1ED /* 0755 */)
                } else if (!RuntimeFiles.isDirectory(stat)) {
                    throw IOException("ARCHIVE_PARENT_LINK_OR_TYPE")
                }
                verifiedDirectories.add(key)
            }
            parent = next
        }
        return parent
    }

    private fun applyDirectoryModes() {
        val paths = directoryModes.keys.sortedByDescending { it.count { char -> char == '/' } }
        for (path in paths) {
            val stat = RuntimeFiles.statOrNull(File(root, path)) ?: continue
            if (RuntimeFiles.isDirectory(stat)) {
                Os.chmod(File(root, path).path, directoryModes.getValue(path))
            }
        }
    }

    private fun mode(entry: TarArchiveEntry, fallback: Int): Int {
        val value = entry.mode and 0x1FF
        return if (value == 0) fallback else value
    }
}
