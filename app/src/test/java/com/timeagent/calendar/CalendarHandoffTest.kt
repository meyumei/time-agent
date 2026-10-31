package com.timeagent.calendar

import android.content.Intent
import android.provider.CalendarContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalendarHandoffTest {
    @Test fun `handoff uses insert editor with exact times and no private description by default`() {
        val intent = CalendarHandoff.createIntent("报告", 1000L, 2000L)
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("报告", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(1000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L))
        assertEquals(2000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L))
        assertFalse(intent.hasExtra(CalendarContract.Events.DESCRIPTION))
    }

    @Test fun `only user supplied description is handed off`() {
        assertEquals("公开说明", CalendarHandoff.createIntent("报告", 1000L, 2000L, "公开说明").getStringExtra(CalendarContract.Events.DESCRIPTION))
    }
}
