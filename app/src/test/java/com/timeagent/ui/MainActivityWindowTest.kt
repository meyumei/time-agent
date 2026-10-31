package com.timeagent.ui

import android.content.ComponentName
import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.timeagent.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityWindowTest {
    @Test fun `keyboard resizes the content instead of panning messages behind status bars`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activity = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
    }
}
