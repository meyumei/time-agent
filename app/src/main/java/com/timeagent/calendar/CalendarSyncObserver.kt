package com.timeagent.calendar

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.timeagent.data.TimeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One process observer; provider and local changes share the same debounced work. */
class CalendarSyncObserver(private val context: Context, private val repository: TimeRepository) {
    private var registered = false
    private var job: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { CalendarSyncWorker.request(context) }
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        CalendarSyncWorker.schedule(context)
        refreshPermission()
        job = scope.launch {
            repository.database.invalidationTracker.createFlow("schedule_blocks", "recurring_rules", "time_records", "focus_sessions").collect {
                if (repository.database.calendarSyncDao().states().isNotEmpty() ||
                    CalendarSyncRepository(repository, context).actualCalendarId() != 0L) CalendarSyncWorker.request(context)
            }
        }
    }

    fun refreshPermission() {
        val allowed = CalendarSyncRepository(repository, context).canReadCalendar()
        if (allowed && !registered) {
            context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer)
            registered = true
        } else if (!allowed && registered) {
            context.contentResolver.unregisterContentObserver(observer)
            registered = false
        }
    }
}
