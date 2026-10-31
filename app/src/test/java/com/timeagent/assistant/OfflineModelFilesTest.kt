package com.timeagent.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineModelFilesTest {
    @Test fun `actual bundled archive passes checksum extraction and subsequent opens reuse complete model`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = OfflineModelFiles.prepare(context)
        assertEquals(OfflineModelFiles.SHA256, java.io.File(model, ".ready").readText())
        assertTrue(java.io.File(model, "am/final.mdl").length() > 10_000_000)
        val modified = java.io.File(model, "am/final.mdl").lastModified()
        assertEquals(model, OfflineModelFiles.prepare(context))
        assertEquals(modified, java.io.File(model, "am/final.mdl").lastModified())
    }
    @Test fun `model extractor rejects path traversal before writing outside its directory`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("${OfflineModelFiles.MODEL}/../escape")); it.write(byteArrayOf(1)); it.closeEntry() }
        val target = Files.createTempDirectory("voice-model-test").toFile()
        assertThrows(IllegalArgumentException::class.java) { OfflineModelFiles.unpack(ByteArrayInputStream(bytes.toByteArray()), target) }
        assertTrue(target.listFiles().isNullOrEmpty())
    }
}
