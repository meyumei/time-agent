package com.timeagent.workout

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.timeagent.MainActivity

/** Retains installed notification intents while the training UI lives in the main navigation. */
class WorkoutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("appDestination", sessionId?.let { "workout:$it" } ?: "workout")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        const val EXTRA_SESSION_ID = "workout_session_id"
        fun intent(context: Context, sessionId: String? = null): Intent = Intent(context, WorkoutActivity::class.java).apply {
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
    }
}
