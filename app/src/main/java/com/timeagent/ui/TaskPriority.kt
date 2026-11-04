package com.timeagent.ui

import com.timeagent.R

internal fun taskPriorityLabelResource(priority: Int): Int = when (priority) {
    -1 -> R.string.task_priority_none
    0 -> R.string.task_priority_low
    2 -> R.string.task_priority_high
    else -> R.string.task_priority_medium
}
