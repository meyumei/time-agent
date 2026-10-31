package com.timeagent.fish

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FishCharacterTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric runs in an isolated JVM/app sandbox; this never touches a device.
        context.getSharedPreferences("fish_character", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "fish-media").deleteRecursively()
    }

    @Test fun `default card exports a valid v2 persona without configuration`() {
        val character = FishCharacter(context)
        assertFalse(character.configured)
        val card = character.card()
        assertEquals("chara_card_v2", card.getString("spec"))
        assertEquals("2.0", card.getString("spec_version"))
        val data = card.getJSONObject("data")
        assertEquals("大肥鱼", data.getString("name"))
        assertTrue(data.getString("scenario").isNotBlank())
        assertTrue(data.getString("first_mes").isNotBlank())
        assertTrue(data.has("extensions"))
        assertEquals(0, data.getJSONObject("extensions").length())
    }

    @Test fun `v2 import edit and export keep unknown fields`() {
        val imported = JSONObject()
            .put("spec", "chara_card_v2")
            .put("spec_version", "2.0")
            .put("x-top", 7)
            .put("data", JSONObject()
                .put("name", "原名字")
                .put("description", "旧介绍")
                .put("personality", "旧性格")
                .put("scenario", "旧关系")
                .put("first_mes", "旧问候")
                .put("mes_example", "旧示例")
                .put("x-data", "kept")
                .put("extensions", JSONObject().put("vendor", JSONObject().put("kept", true))))
        val source = File(context.cacheDir, "v2-card.json").apply { writeText(imported.toString()) }
        val character = FishCharacter(context)
        character.importCard(Uri.fromFile(source))
        assertEquals("原名字", character.profile.value.name)

        character.save(character.profile.value.copy(name = "新名字", personality = "新性格"))
        val exportedFile = File(context.cacheDir, "v2-export.json")
        character.exportCard(Uri.fromFile(exportedFile))
        val exported = JSONObject(exportedFile.readText())
        val data = exported.getJSONObject("data")
        assertEquals("chara_card_v2", exported.getString("spec"))
        assertEquals("2.0", exported.getString("spec_version"))
        assertEquals("新名字", data.getString("name"))
        assertEquals("新性格", data.getString("personality"))
        assertEquals("旧介绍", data.getString("description"))
        assertEquals("旧问候", data.getString("first_mes"))
        assertEquals("kept", data.getString("x-data"))
        assertTrue(data.getJSONObject("extensions").getJSONObject("vendor").getBoolean("kept"))
        assertEquals(7, exported.getInt("x-top"))
    }

    @Test fun `action images keep order and metadata across reload and removal`() {
        val sources = listOf(
            writePng("frame-1.png", 0xFFFF0000.toInt()),
            writePng("frame-2.png", 0xFF00FF00.toInt()),
            writePng("frame-3.png", 0xFF0000FF.toInt()),
        )
        val character = FishCharacter(context)
        character.addAsset("挥手", sources.map { Uri.fromFile(it) }, true, "HAPPY", 120, "CHIBI")

        val imported = character.actions.value.single()
        assertEquals(3, imported.files.size)
        assertEquals("HAPPY", imported.trigger)
        assertEquals(120, imported.frameMs)
        assertEquals("CHIBI", imported.appearance)
        val bytes = sources.map { it.readBytes() }
        imported.files.forEachIndexed { index, path -> assertArrayEquals(bytes[index], File(path).readBytes()) }

        val reloaded = FishCharacter(context)
        val persisted = reloaded.actions.value.single()
        assertEquals(imported.files, persisted.files)
        assertEquals("HAPPY", persisted.trigger)
        assertEquals(120, persisted.frameMs)
        assertEquals("CHIBI", persisted.appearance)

        reloaded.addAsset("点头", listOf(Uri.fromFile(sources.first())), true, "IDLE", 350, "CHIBI")
        reloaded.removeAsset(persisted, true)
        assertTrue(persisted.files.none { File(it).exists() })
        assertTrue(sources.all(File::exists))
        val afterRemoval = FishCharacter(context).actions.value.single()
        assertEquals("点头", afterRemoval.name)
        assertEquals("IDLE", afterRemoval.trigger)
        assertEquals(1, afterRemoval.files.size)
    }

    @Test fun `invalid imports are rejected without corrupting the profile`() {
        val character = FishCharacter(context)

        val badImage = File(context.cacheDir, "bad.png").apply { writeText("not an image") }
        try {
            character.addAsset("坏图", listOf(Uri.fromFile(badImage)), true, "IDLE", 350, "CHIBI")
            fail("unreadable image accepted")
        } catch (_: IllegalStateException) { }
        assertTrue(character.actions.value.isEmpty())

        val badCard = File(context.cacheDir, "bad-card.json").apply {
            writeText(JSONObject().put("name", "坏卡").put("data", "not-an-object").toString())
        }
        try {
            character.importCard(Uri.fromFile(badCard))
            fail("card with non-object data accepted")
        } catch (_: Exception) { }
        assertEquals("大肥鱼", character.profile.value.name)
        val fallback = character.card()
        assertEquals("chara_card_v2", fallback.getString("spec"))
        assertEquals("大肥鱼", fallback.getJSONObject("data").getString("name"))
    }

    @Test fun `partial frame import leaves no copied images or action`() {
        val character = FishCharacter(context)
        val valid = writePng("good-frame.png", 0xFFFF0000.toInt())
        val invalid = File(context.cacheDir, "bad-frame.png").apply { writeText("bad image") }
        try {
            character.addAsset("未完成", listOf(Uri.fromFile(valid), Uri.fromFile(invalid)), true, "HAPPY", 120, "CHIBI")
            fail("invalid second frame accepted")
        } catch (_: IllegalStateException) { }
        assertTrue(character.actions.value.isEmpty())
        assertTrue(File(context.filesDir, "fish-media").listFiles().orEmpty().isEmpty())
        assertTrue(valid.exists())
    }

    private fun writePng(name: String, color: Int): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
