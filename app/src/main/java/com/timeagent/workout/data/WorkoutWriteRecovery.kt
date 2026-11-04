package com.timeagent.workout.data

import androidx.core.util.AtomicFile
import androidx.room.withTransaction
import com.google.gson.Gson
import com.timeagent.data.TimeAgentDatabase
import java.io.File

/**
 * Retains only the rows touched by one paired-database command. The commit marker lives in the
 * main transaction; after a process death it tells us whether the workout rows must be restored.
 */
internal class WorkoutWriteRecovery(private val workouts: WorkoutDatabase, val token: String) {
    private val state = Pending(token)
    private val file = pendingFile(workouts)

    fun preserveSession(id: String, row: WorkoutSessionRow?) {
        if (!state.sessions.containsKey(id)) {
            state.sessions[id] = row
            flush()
        }
    }

    fun preserveTemplate(id: String, row: WorkoutTemplateRow?) {
        if (!state.templates.containsKey(id)) {
            state.templates[id] = row
            flush()
        }
    }

    fun clear() { file?.delete() }

    private fun flush() {
        val destination = file ?: return
        val stream = destination.startWrite()
        try {
            stream.write(gson.toJson(state).toByteArray(Charsets.UTF_8))
            destination.finishWrite(stream)
        } catch (error: Throwable) {
            destination.failWrite(stream)
            throw error
        }
    }

    private data class Pending(
        val token: String,
        val sessions: MutableMap<String, WorkoutSessionRow?> = linkedMapOf(),
        val templates: MutableMap<String, WorkoutTemplateRow?> = linkedMapOf(),
    )

    companion object {
        private val gson = Gson().newBuilder().serializeNulls().create()

        private fun pendingFile(workouts: WorkoutDatabase): AtomicFile? {
            val path = workouts.openHelper.writableDatabase.path ?: return null
            return path.takeUnless { it == ":memory:" || it.isBlank() }
                ?.let { AtomicFile(File("$it.command-pending")) }
        }

        /** Must run under PersonalDataGate, before opening the next main transaction or reads. */
        suspend fun recover(main: TimeAgentDatabase, workouts: WorkoutDatabase) {
            val file = pendingFile(workouts) ?: return
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return
            val pending = file.openRead().use {
                gson.fromJson(it.reader(Charsets.UTF_8), Pending::class.java)
            }
            if (main.dao().commandResult(pending.token) == null) {
                workouts.withTransaction {
                    val dao = workouts.dao()
                    // Remove touched rows first, so restoring the old activeSlot cannot collide
                    // with the newly started workout that this same command is rolling back.
                    pending.sessions.keys.forEach { dao.deleteSession(it) }
                    pending.templates.keys.forEach { dao.deleteTemplate(it) }
                    pending.sessions.values.filterNotNull().forEach { dao.putSession(it) }
                    pending.templates.values.filterNotNull().forEach { dao.putTemplate(it) }
                }
            }
            file.delete()
        }
    }
}
