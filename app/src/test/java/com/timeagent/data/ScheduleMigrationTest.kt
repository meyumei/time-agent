package com.timeagent.data

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScheduleMigrationTest : ScheduleMigrationContract() {
    @Before fun loadTestAssets() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val archive = File(context.cacheDir, "migration-test-assets.apk")
        ZipOutputStream(archive.outputStream()).use { zip ->
            val files = listOf(
                "db/v12-sample.db" to File("src/androidTest/assets/db/v12-sample.db"),
                "com.timeagent.data.TimeAgentDatabase/12.json" to File("schemas/com.timeagent.data.TimeAgentDatabase/12.json"),
                "com.timeagent.data.TimeAgentDatabase/13.json" to File("schemas/com.timeagent.data.TimeAgentDatabase/13.json"),
            )
            for ((name, file) in files) {
                zip.putNextEntry(ZipEntry("assets/$name"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        ReflectionHelpers.callInstanceMethod<Int>(context.assets, "addAssetPath",
            ReflectionHelpers.ClassParameter.from(String::class.java, archive.absolutePath))
    }
}
