package com.timeagent.fish

import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FishProfile(
    val name: String = "大肥鱼",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "你的个人管家，与你使用同一份真实日程和共同记忆。",
    val greeting: String = "我在这里。今天想先做什么？",
    val examples: String = "",
)

data class FishAsset(
    val id: String, val name: String, val files: List<String>,
    val trigger: String = "IDLE", val frameMs: Int = 350, val appearance: String = "CHIBI",
)

/** Character Card V2 fields remain portable; images live in this app's private media folder. */
class FishCharacter(private val context: Context) {
    private val prefs = context.getSharedPreferences("fish_character", Context.MODE_PRIVATE)
    private var card = JSONObject(prefs.getString("card", "{}")!!)
    val configured get() = prefs.contains("card")
    private val _profile = MutableStateFlow(readProfile(card))
    val profile = _profile.asStateFlow()
    private val _outfits = MutableStateFlow(listOf(
        FishAsset("red-work", "红色工作服", listOf("asset:fish/wardrobe/red-work.png")),
    ) + readAssets("outfits"))
    val outfits = _outfits.asStateFlow()
    private val _actions = MutableStateFlow(readAssets("actions"))
    val actions = _actions.asStateFlow()
    private val _outfit = MutableStateFlow(prefs.getString("outfit", "classic")!!)
    val outfit = _outfit.asStateFlow()
    private val _preview = MutableStateFlow<FishAsset?>(null)
    val preview = _preview.asStateFlow()

    fun card(): JSONObject {
        val exported = JSONObject(card.toString())
        if (!exported.has("data")) {
            val value = _profile.value
            exported.put("data", JSONObject().put("name", value.name).put("description", value.description)
                .put("personality", value.personality).put("scenario", value.scenario)
                .put("first_mes", value.greeting).put("mes_example", value.examples))
        }
        exported.put("spec", "chara_card_v2").put("spec_version", "2.0")
        val data = exported.getJSONObject("data")
        listOf("creator_notes", "system_prompt", "post_history_instructions", "creator", "character_version").forEach {
            if (!data.has(it)) data.put(it, "")
        }
        listOf("alternate_greetings", "tags").forEach { if (!data.has(it)) data.put(it, JSONArray()) }
        if (!data.has("extensions")) data.put("extensions", JSONObject())
        return exported
    }
    fun save(value: FishProfile) {
        require(value.name.isNotBlank()) { "给管家起一个名字吧" }
        val data = card.optJSONObject("data") ?: JSONObject().also { card.put("data", it) }
        data.put("name", value.name.trim()).put("description", value.description)
            .put("personality", value.personality).put("scenario", value.scenario)
            .put("first_mes", value.greeting).put("mes_example", value.examples)
        persistCard()
    }
    fun importCard(uri: Uri) {
        val raw = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        require(raw.length <= 150000) { "角色卡过大，请导入 JSON 角色档案" }
        val imported = JSONObject(raw)
        val data = if (imported.has("data")) {
            imported.optJSONObject("data") ?: error("角色卡的 data 必须是档案对象")
        } else imported
        require(data.optString("name").isNotBlank()) { "角色卡缺少名字" }
        card = if (imported.has("data")) imported else JSONObject().put("data", imported)
        persistCard()
    }
    fun exportCard(uri: Uri) {
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(card().toString(2)) }
    }
    private fun persistCard() {
        card.put("spec", "chara_card_v2").put("spec_version", "2.0")
        val data = card.getJSONObject("data")
        if (!data.has("extensions")) data.put("extensions", JSONObject())
        prefs.edit().putString("card", card.toString()).apply()
        _profile.value = readProfile(card)
    }
    fun selectOutfit(id: String) { _outfit.value = id; prefs.edit().putString("outfit", id).apply() }
    fun preview(asset: FishAsset?) { _preview.value = asset }
    fun addAsset(name: String, uris: List<Uri>, action: Boolean, trigger: String, frameMs: Int, appearance: String) {
        require(name.isNotBlank() && uris.isNotEmpty()) { "填写名称并选择图片" }
        val folder = File(context.filesDir, "fish-media").apply { mkdirs() }
        val copied = mutableListOf<File>()
        val files = try { uris.map { uri ->
            val target = File(folder, UUID.randomUUID().toString() + ".image")
            copied += target
            context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { target.delete(); error("这张图片无法读取，请选择 PNG、JPEG、GIF 或 WebP") }
            target.absolutePath
        } } catch (error: Exception) {
            copied.forEach { it.delete() }
            throw error
        }
        val value = FishAsset(UUID.randomUUID().toString(), name.trim(), files, trigger, frameMs, appearance)
        if (action) { _actions.value += value; saveAssets("actions", _actions.value) }
        else { _outfits.value += value; saveAssets("outfits", _outfits.value.filter { it.id != "red-work" }) }
    }
    fun removeAsset(value: FishAsset, action: Boolean) {
        if (action) { _actions.value = _actions.value.filter { it.id != value.id }; saveAssets("actions", _actions.value) }
        else {
            _outfits.value = _outfits.value.filter { it.id != value.id }
            saveAssets("outfits", _outfits.value.filter { it.id != "red-work" })
            if (_outfit.value == value.id) selectOutfit("classic")
        }
        if (_preview.value?.id == value.id) preview(null)
        val retained = (_outfits.value + _actions.value).flatMap { it.files }.toSet()
        val folder = File(context.filesDir, "fish-media").canonicalFile
        value.files.filter { it !in retained && !it.startsWith("asset:") }.forEach { path ->
            val file = File(path).canonicalFile
            if (file.parentFile == folder) file.delete()
        }
    }
    fun addReference(name: String, file: String, trigger: String) {
        val value = FishAsset(UUID.randomUUID().toString(), name, listOf("asset:fish/references/$file"), trigger)
        _actions.value += value
        saveAssets("actions", _actions.value)
    }
    private fun saveAssets(key: String, values: List<FishAsset>) {
        val array = JSONArray()
        values.forEach { value -> array.put(JSONObject().put("id", value.id).put("name", value.name)
            .put("files", JSONArray(value.files)).put("trigger", value.trigger)
            .put("frameMs", value.frameMs).put("appearance", value.appearance)) }
        prefs.edit().putString(key, array.toString()).apply()
    }
    private fun readAssets(key: String): List<FishAsset> {
        val array = JSONArray(prefs.getString(key, "[]")!!)
        return (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            val files = value.getJSONArray("files")
            FishAsset(value.getString("id"), value.getString("name"),
                (0 until files.length()).map(files::getString), value.optString("trigger", "IDLE"),
                value.optInt("frameMs", 350), value.optString("appearance", "CHIBI"))
        }
    }
    private fun readProfile(value: JSONObject): FishProfile {
        val data = value.optJSONObject("data") ?: value
        val defaults = FishProfile()
        return FishProfile(data.optString("name", defaults.name), data.optString("description"),
            data.optString("personality"), data.optString("scenario", defaults.scenario),
            data.optString("first_mes", defaults.greeting), data.optString("mes_example"))
    }
}
