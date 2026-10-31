package com.timeagent.workout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutAssetsTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Context>().assets

    @Test fun `bundled action catalogue has stable ids muscles and real local media`() {
        assertTrue("训练动作库尚未接入", assets.list("workout")?.contains("catalog.json") == true)
        val items = assets.open("workout/catalog.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
        assertTrue("动作库应覆盖主要训练肌群", items.size() >= 30)
        val ids = items.map { it.asJsonObject.get("id").asString }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(items.all { it.asJsonObject.getAsJsonArray("primaryMuscles").size() > 0 })
        val localMedia = items.mapNotNull { it.asJsonObject.get("animationAsset")?.takeUnless { value -> value.isJsonNull }?.asString }
        assertTrue("必须接入真实动作资产", localMedia.isNotEmpty())
        localMedia.forEach { path -> assets.open(path).use { assertTrue("空媒体: $path", it.read() >= 0) } }
    }

    @Test fun `offline anatomy viewer includes actual mesh and renderer`() {
        assertTrue("3D查看器尚未接入", assets.list("workout/anatomy")?.contains("index.html") == true)
        val html = assets.open("workout/anatomy/index.html").bufferedReader().use { it.readText() }
        assertTrue(html.contains("three"))
        assertTrue(html.contains("glb") || html.contains("gltf") || html.contains("emg"))
        assets.open("workout/anatomy/model-manifest.json").bufferedReader().use {
            val manifest = JsonParser.parseReader(it).asJsonObject
            val models = manifest.getAsJsonArray("models")
            assertTrue(models.size() > 0)
            models.forEach { model -> assets.open("workout/anatomy/" + model.asJsonObject.get("path").asString).use { stream -> assertTrue(stream.available() > 1000) } }
        }
    }
}
