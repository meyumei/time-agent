package com.timeagent.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TimelineColorPreferencesTest {
    @Test fun `module selection survives a fresh preferences instance and reset restores automatic color`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("timeline_colors_v1", Context.MODE_PRIVATE).edit().clear().commit()
        TimelineColorPreferences(context).save("activity:sleep", "violet")
        val restarted = TimelineColorPreferences(context)
        assertEquals("violet", restarted.read()["activity:sleep"])
        assertEquals("violet", TimelinePalette.resolve("activity:sleep", restarted.read()["activity:sleep"]).id)
        restarted.save("activity:sleep", null)
        assertFalse(TimelineColorPreferences(context).read().containsKey("activity:sleep"))
        assertEquals("cyan", TimelinePalette.resolve("activity:sleep").id)
    }

    @Test fun `eight named default choices have separate colors and known modules are assigned intentionally`() {
        assertEquals(8, TimelinePalette.presets.size)
        assertEquals(8, TimelinePalette.presets.map { it.argb }.distinct().size)
        assertEquals("cyan", TimelinePalette.resolve("activity:sleep").id)
        assertEquals("coral", TimelinePalette.resolve("activity:fitness").id)
        assertNotEquals(TimelinePalette.resolve("activity:fitness-chest"), TimelinePalette.resolve("activity:fitness-back"))
    }
}
