package com.timeagent.fish

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.timeagent.takeFishDestination
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FishGlobalCompanionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `global companion declares the special overlay permission`() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.SYSTEM_ALERT_WINDOW))
    }

    @Test fun `presentation service is private and separate from the model runtime`() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, "com.timeagent.fish.FishOverlayService"), 0)
        assertFalse(info.exported)
        assertNotEquals(FishRuntimeService::class.java.name, info.name)
    }

    @Test fun `opening the fish preserves activities with unfinished editors`() {
        val intent = FishOverlayService.openIntent(context, "chat")
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertNotEquals(0, intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        assertEquals("chat", intent.getStringExtra(FishOverlayService.EXTRA_DESTINATION))
    }

    @Test fun `consumed navigation cannot replay after activity recreation`() {
        val intent = FishOverlayService.openIntent(context, "appearance")
        assertEquals("appearance", takeFishDestination(intent))
        assertNull(takeFishDestination(intent))
        assertFalse(intent.hasExtra(FishOverlayService.EXTRA_DESTINATION))
    }
}
