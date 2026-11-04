package com.timeagent.focus

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.timeagent.domain.command.FocusMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Time arithmetic for focus sessions. Only elapsedRealtime is authoritative. Wall-clock values are
 * persisted for display/records and to detect a manual clock jump; they never extend a focus.
 */
object FocusClock {
    const val PAUSE_LIMIT_MS = 60L * 60_000L
    const val WALL_DRIFT_LIMIT_MS = 2L * 60_000L

    fun nowElapsed(): Long = SystemClock.elapsedRealtime()

    fun bootCount(context: Context?): Int {
        if (context == null) return -1
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    }

    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun plannedFocusMs(session: FocusSessionEntity): Long =
        session.plannedFocusMinutes.coerceAtLeast(1) * 60_000L

    fun breakDurationMs(session: FocusSessionEntity): Long {
        if (session.mode != FocusMode.POMODORO.name) return 0L
        val every = session.longBreakEvery.coerceAtLeast(1)
        val long = session.roundIndex % every == 0
        return (if (long) session.longBreakMinutes else session.plannedBreakMinutes).coerceAtLeast(0) * 60_000L
    }

    fun isTimeLimited(session: FocusSessionEntity): Boolean = session.mode != FocusMode.COUNT_UP.name

    /** Elapsed in the current focus/break phase. A reboot resets the anchor and returns zero. */
    fun phaseElapsedMs(session: FocusSessionEntity, nowElapsed: Long, bootCount: Int = FocusClock.bootCount(null)): Long {
        if (session.bootCount >= 0 && bootCount != session.bootCount) return 0L
        if (session.state != FocusSessionEntity.STATE_FOCUSING && session.state != FocusSessionEntity.STATE_BREAK) return 0L
        return (nowElapsed - session.phaseAnchorElapsedMs).coerceAtLeast(0L)
    }

    /** Current round time, capped for countdown/pomodoro but never capped for count-up. */
    fun roundElapsedNow(session: FocusSessionEntity, nowElapsed: Long, bootCount: Int): Long {
        val raw = session.roundElapsedMs + phaseElapsedMs(session, nowElapsed, bootCount)
        return if (isTimeLimited(session)) raw.coerceAtMost(plannedFocusMs(session)) else raw
    }

    fun focusElapsedNow(session: FocusSessionEntity, nowElapsed: Long, bootCount: Int): Long {
        val round = roundElapsedNow(session, nowElapsed, bootCount)
        return session.focusedElapsedMs + (round - session.roundElapsedMs).coerceAtLeast(0L)
    }

    fun remainingRoundMs(session: FocusSessionEntity, nowElapsed: Long, bootCount: Int): Long =
        (plannedFocusMs(session) - roundElapsedNow(session, nowElapsed, bootCount)).coerceAtLeast(0L)

    fun breakRemainingMs(session: FocusSessionEntity, nowElapsed: Long, bootCount: Int): Long =
        (breakDurationMs(session) - phaseElapsedMs(session, nowElapsed, bootCount)).coerceAtLeast(0L)

    fun pauseRemainingMs(session: FocusSessionEntity, nowElapsed: Long, bootCount: Int): Long {
        if (session.state != FocusSessionEntity.STATE_PAUSED) return 0L
        if (session.bootCount >= 0 && bootCount != session.bootCount) return 0L
        val pausedAt = session.pausedAtElapsedMs ?: return 0L
        return (PAUSE_LIMIT_MS - (nowElapsed - pausedAt).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    /** Milliseconds until the next persisted transition; null means only the user can end it. */
    fun nextBoundaryInMs(session: FocusSessionEntity, nowElapsed: Long, bootCount: Int): Long? {
        if (session.bootCount >= 0 && bootCount != session.bootCount) return 0L
        return when (session.state) {
            FocusSessionEntity.STATE_FOCUSING -> if (isTimeLimited(session)) remainingRoundMs(session, nowElapsed, bootCount) else null
            FocusSessionEntity.STATE_BREAK -> breakRemainingMs(session, nowElapsed, bootCount)
            FocusSessionEntity.STATE_PAUSED ->
                if (session.pausedAtElapsedMs == null) PAUSE_LIMIT_MS else pauseRemainingMs(session, nowElapsed, bootCount)
            else -> null
        }
    }

    /** Wall-clock drift since the last persisted transition; used only to warn, never to count. */
    fun wallDriftMs(session: FocusSessionEntity, nowWall: Long, nowElapsed: Long, bootCount: Int): Long {
        if (session.bootCount >= 0 && bootCount != session.bootCount) return Long.MAX_VALUE
        val expected = session.lastWallAt + (nowElapsed - session.lastElapsedAt)
        return kotlin.math.abs(nowWall - expected)
    }

    fun decodeSegments(session: FocusSessionEntity): List<FocusSegment> = FocusSegments.decode(session.segmentsJson)

    fun segmentsByDay(session: FocusSessionEntity, zone: ZoneId): Map<LocalDate, Long> {
        val result = linkedMapOf<LocalDate, Long>()
        decodeSegments(session).forEach { segment ->
            var remaining = segment.elapsedMs.coerceAtLeast(0L)
            if (remaining == 0L) return@forEach
            var cursor = Instant.ofEpochMilli(segment.startAt).atZone(zone)
            while (remaining > 0L) {
                val date = cursor.toLocalDate()
                val nextMidnight = date.plusDays(1).atStartOfDay(zone)
                val available = Duration.between(cursor, nextMidnight).toMillis().coerceAtLeast(1L)
                val take = minOf(remaining, available)
                result[date] = (result[date] ?: 0L) + take
                remaining -= take
                cursor = nextMidnight
            }
        }
        return result
    }

    fun formatClock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    fun formatDuration(ms: Long): String {
        val minutes = ms.coerceAtLeast(0L) / 60_000L
        if (minutes < 1L) return "不到 1 分钟"
        val hours = minutes / 60L
        val rest = minutes % 60L
        return when {
            hours > 0 && rest > 0 -> "$hours 小时 $rest 分钟"
            hours > 0 -> "$hours 小时"
            else -> "$minutes 分钟"
        }
    }

    fun formatWall(ms: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(ms))
}
