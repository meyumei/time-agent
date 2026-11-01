package com.timeagent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timeagent.data.TimeRepository
import com.timeagent.reminders.ReminderIntents
import com.timeagent.ui.*
import com.timeagent.backup.recoverPersonalDataBeforeOpen
import com.timeagent.fish.FishOverlayService
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var repository: Lazy<TimeRepository>
    private var openReminderId by mutableStateOf<String?>(null)
    private var sharedText by mutableStateOf<String?>(null)
    private var fishDestination by mutableStateOf<String?>(null)
    private fun shared(intent: Intent?): String? = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!recoverPersonalDataBeforeOpen()) return
        openReminderId = intent?.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID)
        sharedText = shared(intent)
        fishDestination = takeFishDestination(intent)
        enableEdgeToEdge()
        setContent {
            val model: TimeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = TimeViewModel(repository.get(), applicationContext) as T
            })
            TimeAgentTheme {
                TimeAgentApp(
                    model,
                    openReminderId = openReminderId,
                    onOpenReminderConsumed = { openReminderId = null },
                    sharedText = sharedText,
                    onSharedTextConsumed = { sharedText = null },
                    fishDestination = fishDestination,
                    onFishDestinationConsumed = { fishDestination = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openReminderId = intent.getStringExtra(ReminderIntents.EXTRA_REMINDER_ID)
        sharedText = shared(intent)
        fishDestination = takeFishDestination(intent)
    }

    override fun onStart() {
        super.onStart()
        FishOverlayService.setMainVisible(true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) FishOverlayService.setMainVisible(false)
        super.onStop()
    }
}

internal fun takeFishDestination(intent: Intent?): String? =
    intent?.getStringExtra(FishOverlayService.EXTRA_DESTINATION).also { intent?.removeExtra(FishOverlayService.EXTRA_DESTINATION) }
