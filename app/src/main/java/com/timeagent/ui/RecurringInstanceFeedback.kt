package com.timeagent.ui

import android.content.Context
import com.timeagent.R
import com.timeagent.domain.command.CommandResult
import com.timeagent.ui.components.UndoMessage

internal fun recurringInstanceUndo(context: Context, result: CommandResult, action: String): UndoMessage? =
    result.undoToken?.let { token ->
        val message = when (action) {
            "COMPLETE" -> R.string.recurring_instance_completed
            "SKIP" -> R.string.recurring_instance_skipped
            "REOPEN" -> R.string.recurring_instance_reopened
            else -> R.string.recurring_instance_updated
        }
        UndoMessage(result.requestId, context.getString(message), token)
    }
