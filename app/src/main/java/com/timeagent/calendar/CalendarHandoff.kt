package com.timeagent.calendar

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

object CalendarHandoff {
    const val OPENED_MESSAGE = "已打开系统日历，请在那里确认；保存未核实。"
    const val UNAVAILABLE_MESSAGE = "没有可用的系统日历应用，请先安装或启用日历。"

    fun createIntent(title: String, startAt: Long, endAt: Long, description: String = ""): Intent {
        require(title.isNotBlank()) { "请填写标题" }
        require(endAt > startAt) { "结束时间需晚于开始时间" }
        return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startAt)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endAt)
            .apply { if (description.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, description) }
    }

    fun open(context: Context, title: String, startAt: Long, endAt: Long, description: String = ""): String = try {
        val intent = createIntent(title, startAt, endAt, description)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        OPENED_MESSAGE
    } catch (_: ActivityNotFoundException) {
        UNAVAILABLE_MESSAGE
    }
}
