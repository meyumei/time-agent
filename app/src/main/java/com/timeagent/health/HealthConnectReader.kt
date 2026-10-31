package com.timeagent.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.timeagent.data.HealthSample
import com.timeagent.data.HealthSampleKind
import java.time.Instant

/** Reads sleep and exercise sessions from Health Connect; source-app agnostic by design. */
class HealthConnectReader(context: Context) : HealthDataReader {
    private val context = context.applicationContext

    private fun clientOrNull(): HealthConnectClient? {
        val status = try { HealthConnectClient.getSdkStatus(context) } catch (_: Exception) { HealthConnectClient.SDK_UNAVAILABLE }
        if (status != HealthConnectClient.SDK_AVAILABLE) return null
        return try { HealthConnectClient.getOrCreate(context) } catch (_: Exception) { null }
    }

    override suspend fun state(): HealthConnectState = state(true, true)

    override suspend fun state(sleep: Boolean, exercise: Boolean): HealthConnectState {
        val client = clientOrNull() ?: return HealthConnectState.Unavailable
        return if (client.permissionController.getGrantedPermissions().containsAll(permissionsFor(sleep, exercise))) HealthConnectState.Ready else HealthConnectState.NeedsPermission
    }

    override suspend fun readSleep(since: Instant, until: Instant): List<HealthSample> {
        val client = clientOrNull() ?: error("Health Connect 当前不可用，请重新打开同步页")
        return collectHealthRecords { token ->
            val response = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(since, until), pageToken = token))
            response.records to response.pageToken
        }
            .filter { it.endTime.isAfter(it.startTime) }
            .map { HealthSample(it.metadata.id, HealthSampleKind.SLEEP, "睡眠", it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.dataOrigin.packageName) }
    }

    override suspend fun readExercise(since: Instant, until: Instant): List<HealthSample> {
        val client = clientOrNull() ?: error("Health Connect 当前不可用，请重新打开同步页")
        return collectHealthRecords { token ->
            val response = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(since, until), pageToken = token))
            response.records to response.pageToken
        }
            .filter { it.endTime.isAfter(it.startTime) }
            .map {
                HealthSample(
                    it.metadata.id, HealthSampleKind.EXERCISE,
                    it.title?.takeIf { value -> value.isNotBlank() } ?: exerciseTitle(it.exerciseType),
                    it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.metadata.dataOrigin.packageName,
                )
            }
    }

    companion object {
        val READ_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )
        fun permissionsFor(sleep: Boolean, exercise: Boolean): Set<String> = buildSet {
            if (sleep) add(HealthPermission.getReadPermission(SleepSessionRecord::class))
            if (exercise) add(HealthPermission.getReadPermission(ExerciseSessionRecord::class))
        }

        fun exerciseTitle(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "跑步"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "跑步机"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "步行"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "力量训练"
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "负重训练"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "骑行"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "游泳"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "瑜伽"
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "篮球"
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "羽毛球"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "徒步"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "划船"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "椭圆机"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "高强度间歇"
            else -> "健身"
        }
    }
}

internal suspend fun <T> collectHealthRecords(readPage: suspend (String?) -> Pair<List<T>, String?>): List<T> {
    val records = mutableListOf<T>()
    var token: String? = null
    do {
        val page = readPage(token)
        records += page.first
        token = page.second?.takeIf { it.isNotBlank() }
    } while (token != null)
    return records
}
