package com.timeagent.ledger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.backup.recoverPersonalDataBeforeOpen
import com.timeagent.ui.TimeAgentTheme
import com.timeagent.ui.returnToButler
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LedgerActivity : ComponentActivity() {
    @Inject lateinit var database: Lazy<TimeAgentDatabase>
    companion object {
        const val EXTRA_CAPTURE_ID = "ledger_capture_id"
        const val EXTRA_ACTION = "ledger_action"
        const val ACTION_IGNORE = "ignore"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!recoverPersonalDataBeforeOpen()) return
        val repo = LedgerRepository(database.get())
        val captureId = intent?.getStringExtra(EXTRA_CAPTURE_ID)
        val ignore = intent?.getStringExtra(EXTRA_ACTION) == ACTION_IGNORE
        setContent {
            TimeAgentTheme {
                androidx.activity.compose.BackHandler { returnToButler() }
                LedgerScreen(
                    repository = repo,
                    initialCaptureId = captureId,
                    initialIgnore = ignore,
                    onClose = ::returnToButler,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

}
