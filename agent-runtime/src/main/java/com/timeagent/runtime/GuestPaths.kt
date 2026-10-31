package com.timeagent.runtime

import java.io.IOException

/**
 * Path rules for the pinned archives and for files the module writes into the rootfs.
 *
 * Archive member names are archive-root relative. Guest-absolute symlinks are legal
 * because proot resolves them inside the rootfs; they must never be followed by host
 * file writes. Keeping this logic free of android.* makes the boundary testable on JVM.
 */
internal object GuestPaths {
    private const val MAX_STRIP = 64

    /** Returns a normalized archive-root-relative path, or null when strip consumed it. */
    fun member(raw: String, strip: Int): String? {
        requireStrip(strip)
        if (raw.isEmpty()) throw IOException("ARCHIVE_PATH_EMPTY")
        if (raw.indexOf('\u0000') >= 0 || raw.indexOf('\\') >= 0) {
            throw IOException("ARCHIVE_PATH_INVALID")
        }
        if (raw.startsWith("/")) throw IOException("ARCHIVE_PATH_ABSOLUTE")
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("ARCHIVE_PATH_ESCAPE")
        if (parts.size <= strip) return null
        return parts.drop(strip).joinToString("/")
    }

    /**
     * Validates a symlink target and returns the original text. Relative targets may
     * climb towards the rootfs root but not above it; absolute targets are guest-rooted.
     */
    fun symlink(member: String, target: String): String {
        if (target.isEmpty() || target.indexOf('\u0000') >= 0 || target.indexOf('\\') >= 0) {
            throw IOException("ARCHIVE_LINK_TARGET_INVALID")
        }
        if (target.startsWith("/")) return target
        var depth = member.split('/').size - 1
        for (part in target.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (depth == 0) throw IOException("ARCHIVE_LINK_ESCAPE")
                    depth--
                }
                else -> depth++
            }
        }
        return target
    }

    /** Resolves a tar hardlink target (archive-root relative) to a rootfs-relative path. */
    fun hardlink(member: String, target: String, strip: Int): String {
        requireStrip(strip)
        if (member.isEmpty()) throw IOException("ARCHIVE_LINK_MEMBER_EMPTY")
        if (target.isEmpty() || target.indexOf('\u0000') >= 0 || target.indexOf('\\') >= 0) {
            throw IOException("ARCHIVE_LINK_TARGET_INVALID")
        }
        val parts = target.trimStart('/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("ARCHIVE_LINK_ESCAPE")
        if (parts.size <= strip) throw IOException("ARCHIVE_LINK_EMPTY")
        return parts.drop(strip).joinToString("/")
    }

    private fun requireStrip(strip: Int) {
        if (strip !in 0..MAX_STRIP) throw IOException("ARCHIVE_STRIP_INVALID")
    }
}
