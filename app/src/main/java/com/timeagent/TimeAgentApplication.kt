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

    override fun onCreate() {
        super.onCreate()
        // Process-scoped start: activity recreation never re-runs this.
        applicationScope.launch {
            val repository = AppEntryPoint.from(this@TimeAgentApplication).timeRepository()
            ReminderCoordinator(this@TimeAgentApplication, repository).start(applicationScope)
        }
    }
}
