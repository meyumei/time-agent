package com.timeagent.health

import android.database.sqlite.SQLiteDatabase
import com.timeagent.data.HealthSample
import com.timeagent.data.HealthSampleKind
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads sleep sessions straight out of a Gadgetbridge database export, bypassing Health Connect
 * entirely. Gadgetbridge stores one minute-level HUAWEI_ACTIVITY_SAMPLE row per reading where
 * RAW_KIND 6 = light sleep, 7 = deep sleep, 8 = REM (device firmware's own stage output; with
 * TruSleep enabled the watch only reports light sleep — see Gadgetbridge Huawei docs).
 *
 * Sessions are aggregated per sleep night (samples from 18:00 to 15:00 the next day belong to the
 * night anchored on the sample's evening). This reproduces the vendor "time in bed" window: brief
 * wake-ups and sensor gaps inside one night never split the session, so a night reads like the
 * official app's total (e.g. 01:44 → 08:25 ≈ 6h41m).
 */
object GadgetbridgeImporter {
    const val ORIGIN_PACKAGE = "nodomain.freeyourgadget.gadgetbridge"
    const val TRIGGER_EXPORT_ACTION = "$ORIGIN_PACKAGE.command.TRIGGER_EXPORT"
    const val ACTIVITY_SYNC_ACTION = "$ORIGIN_PACKAGE.command.ACTIVITY_SYNC"

    private const val KIND_LIGHT = 6
    private const val KIND_DEEP = 7
    private const val KIND_REM = 8
    private val SLEEP_KINDS = listOf(KIND_LIGHT, KIND_DEEP, KIND_REM)

    fun readSleepSessions(dbFile: File, zone: ZoneId, since: Instant, now: Instant): List<HealthSample> {
        if (!dbFile.exists() || dbFile.length() == 0L) return emptyList()
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY) ?: return emptyList()
        try { return readSleepSessions(db, zone, since, now) } finally { db.close() }
    }

    fun readSleepSessions(db: SQLiteDatabase, zone: ZoneId, since: Instant, now: Instant): List<HealthSample> {
        val sinceSeconds = since.epochSecond
        val samples = mutableListOf<Pair<Long, Int>>()
        val query = "SELECT TIMESTAMP, DEVICE_ID, RAW_KIND FROM HUAWEI_ACTIVITY_SAMPLE WHERE TIMESTAMP >= ? AND RAW_KIND IN (6,7,8) ORDER BY TIMESTAMP"
        var deviceId = -1L
        db.rawQuery(query, arrayOf(sinceSeconds.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val ts = cursor.getLong(0)
                deviceId = cursor.getLong(1)
                samples += ts to cursor.getInt(2)
            }
        }
        if (samples.isEmpty()) return emptyList()

        return samples.groupBy { nightAnchor(Instant.ofEpochSecond(it.first), zone) }
            .toSortedMap()
            .mapNotNull { (night, nightSamples) ->
                val sorted = nightSamples.sortedBy { it.first }
                val start = Instant.ofEpochSecond(sorted.first().first)
                val end = Instant.ofEpochSecond(sorted.last().first + 60)
                if (end > now) return@mapNotNull null // The night in progress syncs once it ends.
                if (end.isBefore(since)) return@mapNotNull null
                val deep = sorted.count { it.second == KIND_DEEP }
                val light = sorted.count { it.second == KIND_LIGHT }
                val rem = sorted.count { it.second == KIND_REM }
                val stageSummary = "深 ${deep} 分钟 · 浅 ${light} 分钟 · REM ${rem} 分钟（分段按夜间聚合）"
                HealthSample(
                    uid = "gb-night:$deviceId:$night",
                    kind = HealthSampleKind.SLEEP,
                    title = "睡眠",
                    startAt = start.toEpochMilli(),
                    endAt = end.toEpochMilli(),
                    originPackage = ORIGIN_PACKAGE,
                    detail = stageSummary,
                )
            }
    }

    /** Night anchor: a sample at or after 18:00 anchors its own date; earlier hours anchor the previous day. */
    fun nightAnchor(instant: Instant, zone: ZoneId): LocalDate {
        val date = instant.atZone(zone).toLocalDate()
        return if (instant.atZone(zone).hour >= 18) date else date.minusDays(1)
    }
}
