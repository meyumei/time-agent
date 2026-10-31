package com.timeagent.workout

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.timeagent.core.workout.ExerciseDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class WorkoutCatalog(context: Context) {
    private val app = context.applicationContext
    private val gson = Gson()
    private val type = object : TypeToken<List<ExerciseDefinition>>() {}.type
    private val customFile = AtomicFile(File(app.filesDir, "workout-custom-exercises.json"))
    private val prefs = app.getSharedPreferences("workout", Context.MODE_PRIVATE)
    private val _exercises = MutableStateFlow<List<ExerciseDefinition>>(emptyList())
    val exercises = _exercises.asStateFlow()
    private val _pinned = MutableStateFlow(prefs.getStringSet("pinned", emptySet())!!.toSet())
    val pinned = _pinned.asStateFlow()
    private val _subfilters = MutableStateFlow<Map<String, List<WorkoutMuscleFilter>>>(emptyMap())
    internal val subfilters = _subfilters.asStateFlow()
    private val _relatedKeys = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    internal val relatedKeys = _relatedKeys.asStateFlow()
    private var bundled: List<ExerciseDefinition> = emptyList()

    @Synchronized fun load() {
        val json = app.assets.open("workout/catalog.json").bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
        bundled = gson.fromJson(json, type)
        _relatedKeys.value = json.associate { item -> item.asJsonObject.let { row ->
            row.get("id").asString to row.getAsJsonObject("sourceMuscleData").entrySet().filter { it.value.asString in setOf("hard", "light") }.map { it.key }.toSet()
        } }
        _subfilters.value = app.assets.open("workout/categories.json").bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject.getAsJsonObject("cates_small_muscle").entrySet().associate { entry ->
                entry.key to entry.value.asJsonArray.map { item -> item.asJsonObject.let { part -> WorkoutMuscleFilter(part.get("key").asString, part.get("label").asString) } }
            }
        }
        _exercises.value = (bundled + readCustom()).distinctBy { it.id }
    }

    suspend fun saveCustom(exercise: ExerciseDefinition) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.timeagent.data.PersonalDataGate.access { saveCustomLocked(exercise) } }
    @Synchronized private fun saveCustomLocked(exercise: ExerciseDefinition) {
        require(exercise.id.startsWith("custom:") && exercise.name.isNotBlank() && exercise.primaryMuscles.isNotEmpty()) { "请填写动作名称和肌群" }
        val items = readCustom().filterNot { it.id == exercise.id } + exercise
        val output = customFile.startWrite()
        try {
            output.write(gson.toJson(items).toByteArray(Charsets.UTF_8))
            customFile.finishWrite(output)
        } catch (error: Exception) { customFile.failWrite(output); throw error }
        _exercises.value = bundled + items
    }

    suspend fun togglePinned(id: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.timeagent.data.PersonalDataGate.access { togglePinnedLocked(id) } }
    @Synchronized private fun togglePinnedLocked(id: String) {
        val next = _pinned.value.toMutableSet().apply { if (!add(id)) remove(id) }.toSet()
        prefs.edit().putStringSet("pinned", next).apply()
        _pinned.value = next
    }

    private fun readCustom(): List<ExerciseDefinition> =
        if (!customFile.baseFile.exists()) emptyList() else customFile.openRead().bufferedReader().use { gson.fromJson(it, type) }
}

internal data class WorkoutMuscleFilter(val key: String, val label: String)
