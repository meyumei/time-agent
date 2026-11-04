package com.timeagent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Read-only timeline queries: observe-only, no writes, no legacy snapshot()/observe(). */
fun TimeRepository.observeBlocks(start: Long, end: Long) =
    database.timelineDao().observeBlocks(start, end)

fun TimeRepository.observeRecords(start: Long, end: Long) =
    database.timelineDao().observeRecords(start, end)

fun TimeRepository.observeTimeline(rangeStart: Long, rangeEnd: Long): Flow<LocalSnapshot> =
    database.invalidationTracker
        .createFlow("schedule_blocks", "time_records", "course_occurrences", "courses", "tasks", "reminders", "focus_sessions", "calendar_links", "sample_records")
        .map {
            transaction {
                val dao = database.timelineDao()
                val plans = dao.blocks(rangeStart, rangeEnd)
                val records = dao.records(rangeStart, rangeEnd)
                val occurrences = dao.occurrences(rangeStart, rangeEnd)
                val taskIds = (plans.mapNotNull { it.taskId } + records.mapNotNull { it.taskId }).distinct()
                val courseIds = occurrences.map { it.courseId }.distinct()
                // Android SQLite versions with a 999-parameter limit still support large ranges.
                LocalSnapshot(
                    plans = plans,
                    records = records,
                    occurrences = occurrences,
                    tasks = if (taskIds.isEmpty()) emptyList() else taskIds.chunked(999).flatMap { dao.tasks(it) },
                    courses = if (courseIds.isEmpty()) emptyList() else courseIds.chunked(999).flatMap { dao.courses(it) },
                    reminders = dao.reminders(rangeStart, rangeEnd),
                    focusSessions = records.filter { it.source == "FOCUS" }.map { it.id.removePrefix("focus:") }
                        .chunked(999).flatMap { database.focusDao().sessions(it) },
                    calendarReadOnlyReasons = plans.map { it.id }.chunked(999)
                        .flatMap { database.calendarSyncDao().readOnlyLinks(it) }
                        .associate { it.localEntityId to requireNotNull(it.readOnlyReason) },
                    loading = false,
                    sampleRecords = database.sampleDataDao().all(),
                )
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
