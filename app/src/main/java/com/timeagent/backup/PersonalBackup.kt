package com.timeagent.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.timeagent.core.workout.ExerciseDefinition
import com.timeagent.data.PersonalDataGate
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.workout.data.WorkoutDatabase
import com.timeagent.di.AppEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class BackupPreview(val createdAt: Long, val appVersion: String, val mainVersion: Int, val workoutVersion: Int, val mainCounts: Map<String, Int>, val workoutCounts: Map<String, Int>, val customExerciseCount: Int, val unlinkedNoteCount: Int = 0, val attachmentFileCount: Int = 0, val missingAttachmentNames: List<String> = emptyList())
data class BackupRestoreResult(val message: String)
internal data class BackupPreference(val type: String, val value: String)
internal data class BackupPayload(val createdAt: Long, val appVersion: String, val mainName: String, val workoutName: String, val main: BackupDatabase, val workout: BackupDatabase, val preferences: Map<String, Map<String, BackupPreference>>, val customExercises: String?, val attachmentFiles: Map<String,String>? = null)
private data class BackupEnvelope(val format: Int, val sha256: String, val payload: String)

class PersonalBackup(
    context: Context,
    private val main: TimeAgentDatabase,
    private val workout: WorkoutDatabase = AppEntryPoint.from(context).workoutDatabase(),
    private val rollbackFile: File = File(context.noBackupFilesDir, ROLLBACK_NAME),
) {
    private val context = context.applicationContext
    internal var afterFirstDatabaseRestored: (() -> Unit)? = null
    internal var afterAttachmentFilesRestored: (() -> Unit)? = null
    internal var beforeRollback: (() -> Unit)? = null

    suspend fun create(): ByteArray = withContext(Dispatchers.IO) { PersonalDataGate.access { encode(capture(privateSettings = false)).also { preview(it) } } }

    fun preview(bytes: ByteArray): BackupPreview {
        val original = decode(bytes)
        val payload = compatible(original)
        validatePublicSettings(payload)
        BackupTables.preflight(payload.main, main.openHelper.readableDatabase.backupSql(), BackupTables.main, false)
        BackupTables.preflight(payload.workout, workout.openHelper.readableDatabase.backupSql(), BackupTables.workout, true)
        AttachmentBackup.validate(payload.main,payload.attachmentFiles)
        val custom = validateCustom(payload.customExercises)
        return BackupPreview(payload.createdAt, payload.appVersion, original.main.version, payload.workout.version, payload.main.tables.mapValues { it.value.rows.size }, payload.workout.tables.mapValues { it.value.rows.size }, custom, BackupTables.unlinkedNotes(payload.main), payload.attachmentFiles.orEmpty().size, AttachmentBackup.missing(payload.main,payload.attachmentFiles))
    }

    suspend fun restore(bytes: ByteArray): BackupRestoreResult = withContext(Dispatchers.IO) { PersonalDataGate.access {
        check(!atomicExists(rollbackFile)) { "上次恢复尚未完成回退，请重新打开应用" }
        preview(bytes)
        val target = compatible(decode(bytes))
        val original = capture(privateSettings = true)
        val missing = AttachmentBackup.missing(target.main,target.attachmentFiles)
        val attachmentMessage = "附件原件 ${target.attachmentFiles.orEmpty().size} 份已恢复" + if(missing.isEmpty()) "。" else "；${missing.size} 份原件缺失，仅保留元数据与文字：${missing.joinToString("、")}。"
        val message = attachmentMessage + "已恢复主库 ${target.main.tables.values.sumOf { it.rows.size }} 行、训练库 ${target.workout.tables.values.sumOf { it.rows.size }} 行及个人设置。GB 文件、Health Connect、通知与精确闹钟授权请重新核对；API Key 需要在新手机重新填写。"
        // Once live mutation starts, cancellation cannot leave a half-restored pair behind.
        withContext(NonCancellable) {
            liveRestoreInProgress = true
            try {
                atomicWrite(rollbackFile, encode(original))
                try {
                    applyLive(target, privateSettings = false, injectFailure = true)
                    check(context.getSharedPreferences("backup_status", 0).edit().putString("result", message).commit()) { "恢复结果保存失败" }
                    deleteMarker(rollbackFile)
                } catch (failure: Throwable) {
                    try { beforeRollback?.invoke(); applyLive(original, privateSettings = true, injectFailure = false); deleteMarker(rollbackFile) }
                    catch (rollbackFailure: Throwable) { PersonalDataGate.recoveryRequired = true; failure.addSuppressed(rollbackFailure); throw IllegalStateException("恢复中断且回退未完成，请重新打开应用后重试；原数据回退文件已保留", failure) }
                    context.getSharedPreferences("backup_status", 0).edit().putString("result", "恢复未完成，已回退到操作前的完整数据。").commit()
                    throw failure
                }
            } finally { liveRestoreInProgress = false }
        }
        BackupRestoreResult(message)
    } }

    private suspend fun capture(privateSettings: Boolean): BackupPayload {
        val mainData = main.withTransaction { BackupTables.capture(main.openHelper.writableDatabase.backupSql(), BackupTables.main) }
        val workoutData = workout.withTransaction { BackupTables.capture(workout.openHelper.writableDatabase.backupSql(), BackupTables.workout) }
        return BackupPayload(System.currentTimeMillis(), context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown",
            requireNotNull(main.openHelper.databaseName) { "完整备份需要本地数据库文件" }, requireNotNull(workout.openHelper.databaseName), mainData, workoutData,
            readPreferences(context, privateSettings), readCustom(context), AttachmentBackup.capture(File(context.filesDir,"attachments"),mainData,privateSettings))
    }

    private fun compatible(payload: BackupPayload): BackupPayload {
        val live = main.openHelper.readableDatabase
        val expected = BackupTables.mainForVersion(payload.main.version)
        require(payload.main.tables.keys == expected.toSet()) { "旧版备份数据表不完整或包含未知表" }
        require(payload.main.version <= live.version) { "备份来自更高版本" }
        val added = BackupTables.main.filterNot { it in expected }.associateWith { name ->
            val columns = live.query("PRAGMA table_info(`$name`)").use { c -> buildList { while(c.moveToNext()) add(c.getString(1)) } }
            BackupTable(columns,emptyList())
        }
        return payload.copy(main=BackupDatabase(live.version,payload.main.tables+added))
    }

    private suspend fun applyLive(payload: BackupPayload, privateSettings: Boolean, injectFailure: Boolean) {
        main.withTransaction { BackupTables.replace(main.openHelper.writableDatabase.backupSql(), payload.main, BackupTables.main) }
        if (injectFailure) afterFirstDatabaseRestored?.invoke()
        workout.withTransaction { BackupTables.replace(workout.openHelper.writableDatabase.backupSql(), payload.workout, BackupTables.workout) }
        writeCustom(context, payload.customExercises)
        writePreferences(context, payload.preferences, privateSettings)
        AttachmentBackup.replace(File(context.filesDir,"attachments"),payload.main,payload.attachmentFiles,rollback=privateSettings)
        if(injectFailure) afterAttachmentFilesRestored?.invoke()
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        private const val ROLLBACK_NAME = "personal-restore-rollback.json"
        private val gson = Gson()
        @Volatile private var liveRestoreInProgress = false
        private val recoveryLock = Any()
        private val publicKeys = mapOf(
            "appearance" to setOf("theme"), "timeline_colors_v1" to emptySet(),
            "workout" to setOf("pinned", "sound", "keepAwake"),
            "health_sync" to setOf("enabled", "sleep", "exercise", "windowDays", "preferredSleepOrigin"),
            "assistant-settings" to setOf("model"),
        )
        private val deviceKeys = setOf("gbExportUri", "lastSyncAt", "lastResult", "readReport")
        private fun allowed(file: String, key: String, privateSettings: Boolean) =
            file in publicKeys && (file == "timeline_colors_v1" || key in publicKeys.getValue(file) || privateSettings && file == "health_sync" && key in deviceKeys)

        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun encode(payload: BackupPayload): ByteArray {
            val content = gson.toJson(payload)
            return gson.toJson(BackupEnvelope(1, sha(content.toByteArray(Charsets.UTF_8)), content)).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "备份超过 32 MiB，未导出" } }
        }
        private fun decode(bytes: ByteArray): BackupPayload = try {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "备份为空或超过 32 MiB" }
            val envelope = gson.fromJson(String(bytes, Charsets.UTF_8), BackupEnvelope::class.java)
            require(envelope.format == 1) { "不支持此备份格式版本" }
            require(envelope.sha256 == sha(envelope.payload.toByteArray(Charsets.UTF_8))) { "备份内容校验失败，文件可能损坏" }
            gson.fromJson(envelope.payload, BackupPayload::class.java).also { payload ->
                require(payload.createdAt > 0 && payload.appVersion.isNotBlank())
                require(payload.main.tables.isNotEmpty() && payload.workout.tables.isNotEmpty())
            }
        } catch (failure: Exception) { throw IllegalArgumentException(failure.message ?: "备份格式无效或文件不完整", failure) }

        private fun validatePublicSettings(payload: BackupPayload) {
            payload.preferences.forEach { (name, values) -> values.forEach { (key, value) ->
                require(allowed(name, key, false)) { "备份包含不允许迁移的设置" }
                preferenceValue(value)
                val expected = when {
                    name == "timeline_colors_v1" || name == "appearance" || key in setOf("preferredSleepOrigin", "model") -> "string"
                    key == "pinned" -> "set"
                    key == "windowDays" -> "int"
                    else -> "boolean"
                }
                require(value.type == expected) { "个人设置 $key 的值类型不兼容" }
                if (name == "appearance") require(value.value in setOf("SYSTEM", "LIGHT", "DARK"))
                if (key == "windowDays") require(value.value.toInt() in setOf(7, 14, 30))
            } }
            require(payload.preferences.keys == publicKeys.keys) { "备份个人设置不完整" }
        }
        private fun readPreferences(context: Context, privateSettings: Boolean): Map<String, Map<String, BackupPreference>> = publicKeys.mapValues { (file, _) ->
            context.getSharedPreferences(file, 0).all.filterKeys { allowed(file, it, privateSettings) }.mapValues { (_, value) -> when (value) {
                is String -> BackupPreference("string", value)
                is Boolean -> BackupPreference("boolean", value.toString())
                is Int -> BackupPreference("int", value.toString())
                is Long -> BackupPreference("long", value.toString())
                is Float -> BackupPreference("float", value.toString())
                is Set<*> -> BackupPreference("set", gson.toJson(value.map { require(it is String); it }.sortedBy { it.toString() }))
                else -> error("不支持的个人设置类型")
            } }
        }
        private fun preferenceValue(value: BackupPreference): Any = when (value.type) {
            "string" -> value.value
            "boolean" -> value.value.toBooleanStrict()
            "int" -> value.value.toInt()
            "long" -> value.value.toLong()
            "float" -> value.value.toFloat().also { require(it.isFinite()) }
            "set" -> JsonParser.parseString(value.value).asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }.toSet()
            else -> throw IllegalArgumentException("个人设置类型无效")
        }
        private fun writePreferences(context: Context, values: Map<String, Map<String, BackupPreference>>, privateSettings: Boolean) {
            publicKeys.keys.forEach { file ->
                val prefs = context.getSharedPreferences(file, 0)
                val editor = prefs.edit()
                prefs.all.keys.filter { allowed(file, it, privateSettings) || !privateSettings && file == "health_sync" && it in deviceKeys }.forEach(editor::remove)
                values.getValue(file).forEach { (key, encoded) ->
                    require(allowed(file, key, privateSettings))
                    when (val value = preferenceValue(encoded)) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
                    }
                }
                check(editor.commit()) { "个人设置写入失败" }
            }
        }
        private fun customFile(context: Context) = File(context.filesDir, "workout-custom-exercises.json")
        private fun atomicExists(file: File) = file.exists() || File(file.path + ".bak").exists()
        private fun deleteMarker(file: File) {
            AtomicFile(file).delete()
            if (atomicExists(file)) { PersonalDataGate.recoveryRequired = true; error("恢复回退标记未能清除，请重新打开应用完成回退") }
        }
        private fun readCustom(context: Context): String? = AtomicFile(customFile(context)).let { if (atomicExists(it.baseFile)) it.openRead().bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() } else null }
        private fun validateCustom(raw: String?): Int {
            if (raw == null) return 0
            require(raw.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "自定义动作文件过大" }
            val definitions: List<ExerciseDefinition> = gson.fromJson(raw, object : TypeToken<List<ExerciseDefinition>>() {}.type)
            require(definitions.map { it.id }.toSet().size == definitions.size)
            definitions.forEach { require(it.id.startsWith("custom:") && it.name.isNotBlank() && it.primaryMuscles.isNotEmpty()) { "自定义动作内容无效" } }
            return definitions.size
        }
        private fun writeCustom(context: Context, raw: String?) { validateCustom(raw); if (raw == null) AtomicFile(customFile(context)).delete() else atomicWrite(customFile(context), raw.toByteArray(Charsets.UTF_8)) }
        private fun atomicWrite(file: File, bytes: ByteArray) {
            val target = AtomicFile(file)
            val stream = target.startWrite()
            try { stream.write(bytes); stream.fd.sync(); target.finishWrite(stream) } catch (failure: Throwable) { target.failWrite(stream); throw failure }
        }

        /** Called before opening Room, including receiver entry points. A second interruption is retryable. */
        fun recoverIfNeeded(context: Context) = recoverIfNeeded(context, File(context.noBackupFilesDir, ROLLBACK_NAME))
        internal fun recoverIfNeeded(context: Context, marker: File) = synchronized(recoveryLock) {
            if (liveRestoreInProgress || !atomicExists(marker)) return@synchronized
            val bytes = AtomicFile(marker).openRead().use { it.readBytes() }
            val payload = decode(bytes)
            fun open(name: String): SQLiteDatabase {
                require(name.matches(Regex("[A-Za-z0-9_.-]+\\.db"))) { "回退数据库路径无效" }
                val path = context.getDatabasePath(name)
                require(path.isFile && path.parentFile?.canonicalFile == context.getDatabasePath("time-agent.db").parentFile?.canonicalFile)
                return SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).apply { setForeignKeyConstraintsEnabled(true) }
            }
            fun restore(db: SQLiteDatabase, data: BackupDatabase, tables: List<String>) {
                db.beginTransaction()
                try { BackupTables.replace(db.backupSql(), data, tables); db.setTransactionSuccessful() } finally { db.endTransaction() }
            }
            val mainTables = BackupTables.mainForVersion(payload.main.version)
            open(payload.mainName).use { restore(it, payload.main, mainTables) }
            open(payload.workoutName).use { restore(it, payload.workout, BackupTables.workout) }
            writeCustom(context, payload.customExercises)
            writePreferences(context, payload.preferences, true)
            AttachmentBackup.replace(File(context.filesDir,"attachments"),payload.main,payload.attachmentFiles,rollback=true)
            deleteMarker(marker)
            PersonalDataGate.recoveryRequired = false
            context.getSharedPreferences("backup_status", 0).edit().putString("result", "上次恢复发生中断，现已自动回退到操作前的完整数据。").commit()
        }
    }
}
