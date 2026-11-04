package com.timeagent.calendar

data class CalendarSeries(val title: String, val calendarId: Long, val eventId: Long,
    val rawRule: String, val readOnlyReason: String?)
