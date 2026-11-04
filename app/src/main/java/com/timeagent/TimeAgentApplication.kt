package com.timeagent

import android.app.Application
import com.timeagent.di.AppEntryPoint
import com.timeagent.reminders.ReminderCoordinator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class TimeAgentApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calendarObserver by lazy {
        com.timeagent.calendar.CalendarSyncObserver(this, AppEntryPoint.from(this).timeRepository())
    }

    fun refreshCalendarPermission() {
        calendarObserver.refreshPermission()
    }

    override fun onCreate() {
        super.onCreate()
        // Process-scoped start: activity recreation never re-runs this.
        applicationScope.launch {
            val repository = AppEntryPoint.from(this@TimeAgentApplication).timeRepository()
            ReminderCoordinator(this@TimeAgentApplication, repository).start(applicationScope)
            com.timeagent.widgets.WidgetRefresh.start(this@TimeAgentApplication, repository, applicationScope)
            com.timeagent.habits.HabitReminderRefresh.start(this@TimeAgentApplication, repository, applicationScope)
            com.timeagent.timer.TimerNotifications.start(this@TimeAgentApplication, repository, applicationScope)
            calendarObserver.start(applicationScope)
            com.timeagent.reports.ReportNotifications.restoreSchedules(this@TimeAgentApplication)
            com.timeagent.focus.FocusRuntime(this@TimeAgentApplication, repository).start(applicationScope)
        }
    }
}
