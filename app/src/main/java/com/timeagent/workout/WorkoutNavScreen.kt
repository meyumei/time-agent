package com.timeagent.workout

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeagent.workout.data.WorkoutRepository

@Composable
fun WorkoutNavScreen(repository: WorkoutRepository, initialSessionId: String? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val model: WorkoutViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WorkoutViewModel(repository, WorkoutCatalog(context.applicationContext)) as T
    })
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("workout", 0)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notificationAsked", false)) {
            prefs.edit().putBoolean("notificationAsked", true).apply()
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val wasKeptAwake = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (context.getSharedPreferences("workout", 0).getBoolean("keepAwake", true)) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { if (!wasKeptAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    WorkoutApp(model, initialSessionId, onClose)
}
