package com.timeagent.calendar

import android.content.Context

internal data class CalendarSettings(
    val exportCalendarId: Long,
    val rangeDays: Int,
    val actualCalendarId: Long,
    val pastDays: Int = 30,
)

internal fun applySettings(context: Context, settings: CalendarSettings) {
    context.getSharedPreferences(CalendarSyncRepository.PREFS, Context.MODE_PRIVATE).edit()
        .putLong(CalendarSyncRepository.KEY_EXPORT_CALENDAR, settings.exportCalendarId)
        .putInt(CalendarSyncRepository.KEY_RANGE_DAYS, settings.rangeDays)
        .putInt(CalendarSyncRepository.KEY_PAST_DAYS, settings.pastDays)
        .putLong(CalendarSyncRepository.KEY_ACTUAL_CALENDAR, settings.actualCalendarId).apply()
}
