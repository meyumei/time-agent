package com.timeagent.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import com.timeagent.MainActivity

internal fun ComponentActivity.returnToButler() {
    if (isTaskRoot || intent.hasExtra("ledger_capture_id") || intent.hasExtra("workout_session_id")) startActivity(Intent(this, MainActivity::class.java)
        .putExtra("appDestination", "butler").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    finish()
}
