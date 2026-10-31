package com.timeagent.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.After
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemePreferenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @After fun cleanup() { context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun `explicit theme choice persists across settings instances`() {
        ThemePreference(context).save(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemePreference(context).read())
        ThemePreference(context).save(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, ThemePreference(context).read())
    }
    @Test fun `unknown saved choice falls back to system`() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", "future-theme").commit()
        assertEquals(ThemeMode.SYSTEM, ThemePreference(context).read())
    }
}
