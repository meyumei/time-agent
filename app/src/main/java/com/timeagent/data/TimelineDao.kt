package com.timeagent.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TimelineDao {

    @Query(BLOCKS_QUERY)
    abstract suspend fun blocks(rangeStart: Long, rangeEnd: Long): List<ScheduleBlockEntity>

    @Query(BLOCKS_QUERY)
    abstract fun observeBlocks(rangeStart: Long, rangeEnd: Long): Flow<List<ScheduleBlockEntity>>

    @Query(RECORDS_QUERY)
    abstract suspend fun records(rangeStart: Long, rangeEnd: Long): List<TimeRecordEntity>

    @Query(RECORDS_QUERY)
    abstract fun observeRecords(rangeStart: Long, rangeEnd: Long): Flow<List<TimeRecordEntity>>

    @Query(OCCURRENCES_QUERY)
    abstract suspend fun occurrences(rangeStart: Long, rangeEnd: Long): List<CourseOccurrenceEntity>

    @Query(TASKS_QUERY)
    abstract suspend fun tasks(ids: List<String>): List<TaskEntity>

    @Query(COURSES_QUERY)
    abstract suspend fun courses(ids: List<String>): List<CourseEntity>

    @Query(REMINDERS_QUERY)
    abstract suspend fun reminders(rangeStart: Long, rangeEnd: Long): List<ReminderEntity>

    companion object {
        const val BLOCKS_QUERY = "SELECT schedule_blocks.* FROM schedule_blocks LEFT JOIN tasks ON tasks.id = schedule_blocks.taskId WHERE :rangeStart < :rangeEnd AND schedule_blocks.deletedAt IS NULL AND schedule_blocks.status = 'SCHEDULED' AND (schedule_blocks.taskId IS NULL OR (tasks.deletedAt IS NULL AND tasks.status != 'CANCELLED')) AND schedule_blocks.startAt < :rangeEnd AND ((schedule_blocks.kind = 'POINT' AND schedule_blocks.startAt >= :rangeStart AND schedule_blocks.startAt < :rangeEnd) OR (schedule_blocks.kind != 'POINT' AND schedule_blocks.startAt < :rangeEnd AND schedule_blocks.endAt > :rangeStart)) ORDER BY schedule_blocks.startAt ASC, schedule_blocks.id ASC"

        const val RECORDS_QUERY = "SELECT * FROM time_records WHERE :rangeStart < :rangeEnd AND startAt < :rangeEnd AND (endAt IS NULL OR endAt > :rangeStart) ORDER BY startAt ASC, id ASC"

        const val OCCURRENCES_QUERY = "SELECT * FROM course_occurrences WHERE :rangeStart < :rangeEnd AND status != 'CANCELLED' AND startAt < :rangeEnd AND endAt > :rangeStart ORDER BY startAt ASC, id ASC"

        const val TASKS_QUERY = "SELECT * FROM tasks WHERE id IN (:ids) ORDER BY id ASC"

        const val COURSES_QUERY = "SELECT * FROM courses WHERE id IN (:ids) ORDER BY id ASC"

        const val REMINDERS_QUERY = "SELECT * FROM reminders WHERE enabled = 1 AND remindAt >= :rangeStart AND remindAt < :rangeEnd ORDER BY remindAt ASC, id ASC"
    }
}
