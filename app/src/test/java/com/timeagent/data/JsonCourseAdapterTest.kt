package com.timeagent.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JsonCourseAdapterTest {
    @Test fun `bundled synthetic example produces complete normalized occurrences`() {
        val source = ApplicationProvider.getApplicationContext<Context>().assets.open("course-import-example.json").bufferedReader().use { it.readText() }
        val bundle = JsonCourseAdapter().parse(source)
        assertEquals(3, bundle.courses.size)
        assertEquals(32, bundle.occurrences.size)
        assertEquals("Asia/Shanghai", bundle.term.zone.id)
    }
    @Test fun `empty malformed and oversized input are rejected before import`() {
        for (raw in listOf("", "{}", "<html>请登录</html>", " ".repeat(600000))) {
            assertThrows(Exception::class.java) { JsonCourseAdapter().parse(raw) }
        }
    }
}
