package com.timeagent.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AssistantSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AssistantSecretCipher { key }
    @After fun cleanup() { java.io.File(context.noBackupFilesDir, "assistant-key.bin").delete(); context.getSharedPreferences("assistant-settings", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `key is encrypted on disk and roundtrips after settings recreation`() = runBlocking {
        val settings = AssistantSettings(context, cipher)
        settings.saveApiKey("example-sensitive-key")
        assertTrue(settings.hasApiKey())
        assertFalse(java.io.File(context.noBackupFilesDir, "assistant-key.bin").readBytes().toString(Charsets.UTF_8).contains("example-sensitive-key"))
        assertEquals("example-sensitive-key", AssistantSettings(context, cipher).readApiKey())
        settings.clearApiKey()
        assertFalse(settings.hasApiKey())
        assertEquals("", settings.readApiKey())
    }
    @Test fun `tampered ciphertext fails closed without exposing key`() = runBlocking {
        val settings = AssistantSettings(context, cipher)
        settings.saveApiKey("example-sensitive-key")
        val file = java.io.File(context.noBackupFilesDir, "assistant-key.bin")
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        try { settings.readApiKey(); fail("tampered secret must be refused") } catch (e: IllegalStateException) { assertFalse(e.message.orEmpty().contains("example-sensitive-key")) }
    }
}
