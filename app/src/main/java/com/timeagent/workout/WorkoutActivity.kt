package com.timeagent.workout

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeagent.data.TimeRepository
import com.timeagent.workout.data.WorkoutDatabase
import com.timeagent.ui.TimeAgentTheme
import com.timeagent.workout.data.WorkoutRepository
import com.timeagent.backup.recoverPersonalDataBeforeOpen
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WorkoutActivity : ComponentActivity() {
    @Inject lateinit var timeRepository: Lazy<TimeRepository>
    @Inject lateinit var workoutDatabase: Lazy<WorkoutDatabase>
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!recoverPersonalDataBeforeOpen()) return
        enableEdgeToEdge()
        if (getSharedPreferences("workout", MODE_PRIVATE).getBoolean("keepAwake", true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val model: WorkoutViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = WorkoutViewModel(
                    WorkoutRepository(workoutDatabase.get(), timeRepository.get()), WorkoutCatalog(applicationContext),
                ) as T
            })
            TimeAgentTheme(darkTheme = false) {
                WorkoutApp(model, intent.getStringExtra(EXTRA_SESSION_ID), onClose = ::finish)
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && !getSharedPreferences("workout", MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getSharedPreferences("workout", MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 520)
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "workout_session_id"
        fun intent(context: Context, sessionId: String? = null): Intent = Intent(context, WorkoutActivity::class.java).apply {
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
    }
}
