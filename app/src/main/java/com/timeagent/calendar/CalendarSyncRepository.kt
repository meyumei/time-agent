package com.timeagent.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import com.google.gson.Gson
import com.timeagent.core.RecurringPattern
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.data.TimeRepository
import com.timeagent.domain.command.CalendarConflictChoice
import com.timeagent.domain.command.CalendarSyncMutation
import com.timeagent.domain.command.CommandResult
import com.timeagent.domain.command.WriteCalendarSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * N04 two-way system-calendar sync.
 *
 * [applyMutation] is called by CommandExecutor inside `repository.transaction` +
 * `repository.commandWrite`, so this class never opens its own transaction for user mutations.
 * Remote provider calls are *not* transactional with Room. A failed provider write therefore throws
 * with the real reason after the surrounding Room transaction rolls back; the next pass reads the
 * provider again and re-links from `CUSTOM_APP_URI` and `lastRemoteHash` instead of claiming that a
 * write succeeded. [CalendarSyncReport.reconciled] counts mappings repaired that way.
 *
 * Echo suppression: after every provider write the event is read back and its nine-field hash is
 * stored in [CalendarLinkEntity.lastRemoteHash]. A later scan that sees the same hash treats the
 * change as our own write and does not write it back locally.
 */
class CalendarSyncRepository(
    private val repository: TimeRepository,
    private val context: Context,
) {
    companion object {
        const val PREFS = "calendar_sync"
        const val KEY_EXPORT_CALENDAR = "exportCalendarId"
        const val KEY_RANGE_DAYS = "rangeDays"
        const val KEY_PAST_DAYS = "pastDays"
        const val KEY_ACTUAL_CALENDAR = "actualCalendarId"
        const val DEFAULT_RANGE_DAYS = 365
        const val LOCAL_ACCOUNT_NAME = "TimeAgent"
        const val RULE_REFERENCE_PREFIX = "CALENDAR_SYNC"

        private const val MS_PER_DAY = 86_400_000L
    }

    private val dao = repository.database.calendarSyncDao()
    private val coreDao = repository.database.dao()
    private val gson = Gson()
    private val resolver = context.contentResolver
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Public configuration ----------------------------------------------------------------

    fun exportCalendarId(): Long = prefs.getLong(KEY_EXPORT_CALENDAR, 0L)
    fun actualCalendarId(): Long = prefs.getLong(KEY_ACTUAL_CALENDAR, 0L)

    fun rangeDays(): Int = prefs.getInt(KEY_RANGE_DAYS, DEFAULT_RANGE_DAYS)
    fun pastDays(): Int = prefs.getInt(KEY_PAST_DAYS, 30)

    fun canReadCalendar(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWriteCalendar(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    // ---- CommandExecutor entry point ----------------------------------------------------------

    suspend fun applyMutation(mutation: CalendarSyncMutation): Any? = when (mutation) {
        is CalendarSyncMutation.Configure -> { configureRaw(mutation); null }
        is CalendarSyncMutation.SaveSettings -> { saveSettingsRaw(mutation); null }
        is CalendarSyncMutation.CreateLocalCalendar -> createLocalCalendarRaw(mutation.displayName)
        CalendarSyncMutation.CreateActualCalendar -> createLocalCalendarRaw("时间 Agent 实际", actual = true)
        is CalendarSyncMutation.SyncNow -> syncRaw()
        is CalendarSyncMutation.ReportFailure -> {
            dao.states().filter { it.direction != CalendarSyncDirection.OFF || it.calendarId == actualCalendarId() }
                .forEach { markStateError(it.calendarId, mutation.message) }
            null
        }
        is CalendarSyncMutation.ResolveConflict -> { resolveConflictRaw(mutation.linkId, mutation.keep, mutation.expectedVersion); null }
        is CalendarSyncMutation.ResolveLocalDeletion -> { resolveLocalDeletionRaw(mutation.linkId, mutation.deleteRemote, mutation.expectedVersion); null }
        is CalendarSyncMutation.Disconnect -> { disconnectRaw(mutation); null }
    }

    // ---- Convenience API used by the screen ---------------------------------------------------

    suspend fun configure(calendarId: Long, name: String, account: String, direction: String,
        expectedVersion: Long? = null, requestId: String = newRequest("configure")) =
        execute(requestId, CalendarSyncMutation.Configure(calendarId, name, account, direction, expectedVersion))

    suspend fun saveSettings(exportCalendarId: Long, rangeDays: Int, actualCalendarId: Long,
        expectedVersion: Long, pastDays: Int = pastDays(), requestId: String = newRequest("settings")) =
        execute(requestId, CalendarSyncMutation.SaveSettings(exportCalendarId, rangeDays, actualCalendarId, expectedVersion, pastDays))

    suspend fun createLocalCalendar(name: String, requestId: String = newRequest("calendar")) =
        execute(requestId, CalendarSyncMutation.CreateLocalCalendar(name))

    suspend fun createActualCalendar(requestId: String = newRequest("actual-calendar")) =
        execute(requestId, CalendarSyncMutation.CreateActualCalendar)

    suspend fun syncNow(requestId: String = newRequest("sync")): CommandResult {
        val result = execute(requestId, CalendarSyncMutation.SyncNow())
        if (result.status !in setOf(com.timeagent.domain.command.CommandStatus.APPLIED,
                com.timeagent.domain.command.CommandStatus.DUPLICATE)) {
            reportFailure(result.message, "$requestId:failure")
        }
        return result
    }

    suspend fun reportFailure(message: String, requestId: String = newRequest("failure")) =
        execute(requestId, CalendarSyncMutation.ReportFailure(message))

    suspend fun resolveConflict(linkId: String, keep: CalendarConflictChoice, expectedVersion: Long, requestId: String = newRequest("conflict")) =
        execute(requestId, CalendarSyncMutation.ResolveConflict(linkId, keep, expectedVersion))

    suspend fun resolveLocalDeletion(linkId: String, deleteRemote: Boolean, expectedVersion: Long, requestId: String = newRequest("local-delete")) =
        execute(requestId, CalendarSyncMutation.ResolveLocalDeletion(linkId, deleteRemote, expectedVersion))

    suspend fun disconnect(calendarId: Long, keepImported: Boolean, deleteRemoteExports: Boolean, expectedVersion: Long?, requestId: String = newRequest("disconnect")) =
        execute(requestId, CalendarSyncMutation.Disconnect(calendarId, keepImported, deleteRemoteExports, expectedVersion))

    private suspend fun execute(requestId: String, mutation: CalendarSyncMutation): CommandResult =
        repository.commands.execute(WriteCalendarSync(requestId, mutation))

    private fun newRequest(prefix: String) = "calendar-$prefix:${UUID.randomUUID()}"

    /** Read-only state for the settings screen. Direct DB reads; no command. */
    suspend fun snapshot(): CalendarSyncSnapshot = withContext(Dispatchers.IO) {
        val states = dao.states()
        val links = dao.links()
        val calendars = if (canReadCalendar()) readCalendars() else emptyList()
        val exportId = exportCalendarId()
        CalendarSyncSnapshot(
            canRead = canReadCalendar(),
            canWrite = canWriteCalendar(),
            calendars = calendars.map { calendar ->
                CalendarChoice(
                    id = calendar.id,
                    name = calendar.name,
                    account = calendar.account,
                    writable = calendar.writable,
                    direction = states.firstOrNull { it.calendarId == calendar.id }?.direction
                        ?: if (calendar.id == exportId) CalendarSyncDirection.EXPORT else CalendarSyncDirection.OFF,
                    lastSyncAt = states.firstOrNull { it.calendarId == calendar.id }?.lastSyncAt,
                    lastError = states.firstOrNull { it.calendarId == calendar.id }?.lastError,
                    version = states.firstOrNull { it.calendarId == calendar.id }?.version,
                )
            },
            exportCalendarId = exportId,
            rangeDays = rangeDays(),
            actualCalendarId = actualCalendarId(),
            settingsVersion = coreDao.revision("CALENDAR_SETTINGS")?.revision ?: 0L,
            states = states,
            conflicts = links.filter { it.state == CalendarLinkState.CONFLICT }.map { link ->
                val local = link.conflictLocalSnapshot?.let { runCatching { gson.fromJson(it, ScheduleBlockEntity::class.java) }.getOrNull() }
                val remote = link.conflictRemoteSnapshot?.let { runCatching { gson.fromJson(it, CalendarRemoteSnapshot::class.java) }.getOrNull() }
                CalendarConflictView(
                    linkId = link.id,
                    calendarId = link.calendarId,
                    kind = link.conflictKind ?: CalendarConflictKind.BOTH_CHANGED,
                    kept = link.conflictKept,
                    localTitle = local?.title?.ifBlank { "日程" } ?: "本地已删除",
                    localSummary = local?.let { summarize(it) } ?: "本地版本已删除或缺失",
                    remoteTitle = remote?.title ?: "远端事件已删除",
                    remoteSummary = remote?.let { "${Instant.ofEpochMilli(it.begin)} - ${Instant.ofEpochMilli(it.end)}" } ?: "远端事件已删除",
                    canUseLocal = local != null && link.readOnlyReason == null,
                    canUseRemote = remote != null,
                    version = link.version,
                )
            },
            linkCount = links.count { it.state == CalendarLinkState.ACTIVE },
            pastDays = pastDays(),
            readOnlyReasons = links.mapNotNull { it.readOnlyReason }.distinct(),
            series = coreDao.recurringRules().filter { it.domain == "CALENDAR" && it.deletedAt == null }.map { rule ->
                val ref = rule.reference.split('|', limit = 4)
                CalendarSeries(rule.title, ref[1].toLong(), ref[2].toLong(), ref[3],
                    com.google.gson.JsonParser.parseString(rule.patternJson).asJsonObject.get("calendarReadOnlyReason")
                        ?.takeUnless { it.isJsonNull }?.asString)
            },
            lastSyncAt = states.mapNotNull { it.lastSyncAt }.maxOrNull(),
            lastError = states.mapNotNull { it.lastError }.firstOrNull(),
        )
    }

    // ---- Mutation implementations -------------------------------------------------------------

    private suspend fun configureRaw(mutation: CalendarSyncMutation.Configure) {
        require(mutation.calendarId > 0) { "请选择一个系统日历" }
        require(mutation.direction in CalendarSyncDirection.ALL) { "同步方向无效" }
        val before = dao.state(mutation.calendarId)
        if (before?.version != mutation.expectedVersion) throw com.timeagent.data.StaleChange()
        require(mutation.calendarId != actualCalendarId() || !CalendarSyncDirection.imports(mutation.direction)) {
            "实际记录使用单向导出的独立日历，请为计划选择另一个日历"
        }
        val saved =
            CalendarSyncStateEntity(
                calendarId = mutation.calendarId,
                calendarName = mutation.calendarName.trim().take(200).ifBlank { "系统日历" },
                accountName = mutation.accountName.trim().take(200),
                direction = mutation.direction,
                lastFullScanAt = before?.lastFullScanAt,
                lastSyncAt = before?.lastSyncAt,
                lastError = before?.lastError,
                version = (before?.version ?: -1L) + 1,
            )
        dao.putState(saved)
        repository.journal("CALENDAR_STATE", saved.calendarId.toString(), before, saved, "设置日历同步方向")
    }

    private suspend fun saveSettingsRaw(mutation: CalendarSyncMutation.SaveSettings) {
        require(mutation.rangeDays > 0) { "未来同步天数必须大于 0" }
        require(mutation.pastDays >= 0) { "过去同步天数不能小于 0" }
        require(mutation.exportCalendarId >= 0) { "导出目标日历无效" }
        require(mutation.actualCalendarId == 0L || mutation.actualCalendarId != mutation.exportCalendarId) { "实际记录须使用单独的日历" }
        require(mutation.actualCalendarId == 0L || dao.state(mutation.actualCalendarId)?.direction?.let(CalendarSyncDirection::imports) != true) {
            "请先关闭此日历的导入，再将其用于实际记录导出"
        }
        require(mutation.actualCalendarId == 0L || dao.links().none {
            it.localEntityType == "ACTUAL_RECORD" && it.calendarId != mutation.actualCalendarId
        }) { "请先断开原实际记录日历，再选择新的独立日历；断开时可以保留或删除原导出内容" }
        val currentVersion = coreDao.revision("CALENDAR_SETTINGS")?.revision ?: 0L
        if (currentVersion != mutation.expectedVersion) throw com.timeagent.data.StaleChange()
        val before = CalendarSettings(exportCalendarId(), rangeDays(), actualCalendarId(), pastDays())
        val after = CalendarSettings(mutation.exportCalendarId, mutation.rangeDays, mutation.actualCalendarId, mutation.pastDays)
        val calendars = if (after.exportCalendarId != 0L || after.actualCalendarId != 0L) readCalendars() else emptyList()
        for (id in listOf(after.exportCalendarId, after.actualCalendarId).filter { it != 0L }.distinct()) {
            val calendar = requireNotNull(calendars.firstOrNull { it.id == id && it.writable }) { "导出目标已不可写，请重新选择日历" }
            val state = dao.state(id)
            if (state == null || !CalendarSyncDirection.exports(state.direction)) {
                val direction = if (id != after.actualCalendarId && state?.direction == CalendarSyncDirection.IMPORT)
                    CalendarSyncDirection.BIDIRECTIONAL else CalendarSyncDirection.EXPORT
                configureRaw(CalendarSyncMutation.Configure(id, calendar.name, calendar.account, direction, state?.version))
            }
        }
        coreDao.putRevision(com.timeagent.data.EntityRevision("CALENDAR_SETTINGS", currentVersion + 1))
        repository.afterCommit { applySettings(context, after); CalendarSyncWorker.request(context) }
        repository.journal("CALENDAR_SETTINGS", "calendar", before, after, "设置日历导出内容")
    }

    private suspend fun createLocalCalendarRaw(displayName: String, actual: Boolean = false): CalendarSyncStateEntity {
        require(canWriteCalendar()) { "需要写日历权限才能新建本地日历" }
        val name = displayName.trim().take(80).ifBlank { "时间 Agent" }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, if (actual) "time-agent-actual" else "time-agent-local")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF5756B7.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT_NAME)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = try {
            resolver.insert(localCalendarUri(), values)
        } catch (error: SecurityException) {
            throw IllegalArgumentException("系统日历拒绝新建本地日历：缺少写权限，尚未创建", error)
        }
        requireNotNull(uri) { "系统日历没有创建本地日历；请确认系统日历可用且允许写入" }
        val id = ContentUris.parseId(uri)
        require(id > 0) { "系统日历返回了无效的日历编号，尚未创建" }
        val settings = CalendarSettings(if (actual) exportCalendarId() else id, rangeDays(), if (actual) id else actualCalendarId(), pastDays())
        saveSettingsRaw(CalendarSyncMutation.SaveSettings(settings.exportCalendarId, settings.rangeDays, settings.actualCalendarId,
            coreDao.revision("CALENDAR_SETTINGS")?.revision ?: 0L, settings.pastDays))
        return CalendarSyncStateEntity(
            calendarId = id,
            calendarName = name,
            accountName = "本机",
            direction = CalendarSyncDirection.EXPORT,
            version = 0,
        ).also { state ->
            if (dao.state(id) == null) {
                dao.putState(state)
                repository.journal("CALENDAR_STATE", id.toString(), null, state, "启用本地日历同步")
            }
        }
    }

    /**
     * One sync pass. Reads all enabled calendars, reconciles deletions/updates, then pushes local
     * schedule blocks that were never exported or changed since the last successful export.
     */
    private suspend fun syncRaw(): CalendarSyncReport {
        var report = CalendarSyncReport()
        if (!canReadCalendar()) {
            val message = "日历权限已关闭，同步暂停；映射已保留，重新授权后会继续"
            dao.states().forEach { state -> dao.putState(state.copy(lastError = message, version = state.version + 1)) }
            return report.copy(paused = true, error = message)
        }
        val now = repository.now().toEpochMilli()
        val rangeEnd = now + rangeDays() * MS_PER_DAY
        val rangeStart = now - pastDays().toLong() * MS_PER_DAY
        val allStates = dao.states()
        val importing = allStates.filter { CalendarSyncDirection.imports(it.direction) }
        val observed = allStates.filter { it.direction != CalendarSyncDirection.OFF && it.calendarId != actualCalendarId() }
        val targetId = exportCalendarId()
        val targetState = allStates.firstOrNull { it.calendarId == targetId && CalendarSyncDirection.exports(it.direction) }
        val canWrite = canWriteCalendar()

        // Remote snapshot per importing calendar. Non-recurring events are read even outside the
        // range so a moved event is not mistaken for a deleted one.
        val remoteByCalendar = linkedMapOf<Long, List<RemoteInstance>>()
        observed.forEach { state ->
            remoteByCalendar[state.calendarId] = readRemoteInstances(state.calendarId, rangeStart, rangeEnd)
        }
        val remoteIndex = remoteByCalendar.values.flatten().associateBy { it.key }

        // Undo of a remote-deletion soft delete restores the block; recreate the remote event rather
        // than deleting the block again on this pass.
        val revived = mutableSetOf<String>()
        dao.links().filter { it.localEntityType == CALENDAR_LOCAL_ENTITY_TYPE &&
            it.state == CalendarLinkState.DELETED_REMOTE && observed.any { state -> state.calendarId == it.calendarId }
        }.forEach { link ->
            val local = coreDao.plan(link.localEntityId)
            if (local != null && local.deletedAt == null) {
                if (allStates.none { it.calendarId == link.calendarId && CalendarSyncDirection.exports(it.direction) }) {
                    val restored = local.copy(source = "MANUAL", sourceRef = null,
                        version = repository.nextVersion("PLAN", local.id, local.version), updatedAt = now)
                    coreDao.putPlan(restored)
                    repository.journal("PLAN", local.id, local, restored, "恢复已被远端删除的日程，保留为本地")
                    dao.removeLink(link.id)
                    revived += link.id
                    report = report.copy(reconciled = report.reconciled + 1)
                    return@forEach
                }
                dao.putLink(
                    link.copy(
                        state = CalendarLinkState.ACTIVE,
                        eventId = if (link.instanceOriginalStart == null) 0 else link.eventId,
                        lastRemoteHash = "",
                        lastLocalVersion = -1,
                        remoteDeleted = false,
                        version = link.version + 1,
                    ),
                )
                revived += link.id
                report = report.copy(reconciled = report.reconciled + 1)
            }
        }

        // Remote deletions, updates and imports for every importing calendar.
        importing.forEach { state ->
            val remotes = remoteByCalendar[state.calendarId].orEmpty()
            dao.linksForCalendar(state.calendarId).forEach { link ->
                if (link.localEntityType != CALENDAR_LOCAL_ENTITY_TYPE) return@forEach
                if (link.id in revived || link.state == CalendarLinkState.DELETED_LOCAL) return@forEach
                val local = coreDao.plan(link.localEntityId)
                val occurrenceInRange = occurrenceInRange(link, local, rangeStart, rangeEnd)
                if (link.state == CalendarLinkState.ACTIVE && occurrenceInRange && remoteIndex["${link.calendarId}:${link.eventId}:${link.instanceOriginalStart ?: -1L}"] == null) {
                    if (local != null && local.deletedAt == null) {
                        softDeleteLocal(local, link)
                        report = report.copy(deletedLocal = report.deletedLocal + 1)
                    }
                    dao.putLink(
                        link.copy(
                            state = CalendarLinkState.DELETED_REMOTE,
                            remoteDeleted = true,
                            lastSyncedAt = now,
                            version = link.version + 1,
                        ),
                    )
                }
            }
            remotes.filter { it.begin in rangeStart..rangeEnd || it.instanceStart?.let { start -> start in rangeStart..rangeEnd } == true }
                .forEach { remote ->
                    val link = dao.linksForEvent(state.calendarId, remote.masterId)
                        .firstOrNull { it.instanceOriginalStart == remote.instanceStart }
                    if (link == null) {
                        importRemote(remote, now, report)
                        report = report.copy(imported = report.imported + 1)
                    } else {
                        report = handleRemoteChange(link, remote, now, report)
                    }
                }
        }

        // Local deletions of linked events. LOCAL-origin events belong to this app and are removed
        // from the provider automatically; REMOTE-origin events ask first.
        dao.links().forEach { link ->
            if (link.localEntityType != CALENDAR_LOCAL_ENTITY_TYPE ||
                observed.none { it.calendarId == link.calendarId }) return@forEach
            if (link.id in revived) return@forEach
            if (link.state != CalendarLinkState.ACTIVE) return@forEach
            val local = coreDao.plan(link.localEntityId)
            if (local != null && local.deletedAt == null) return@forEach
            val remote = remoteIndex["${link.calendarId}:${link.eventId}:${link.instanceOriginalStart ?: -1L}"]
            if (remote == null) return@forEach // the remote-deletion pass already handled it
            when {
                link.origin == CalendarLinkOrigin.LOCAL && canWrite -> {
                    deleteLinkedEvent(link)
                    dao.putLink(
                        link.copy(state = CalendarLinkState.DELETED_LOCAL, remoteDeleted = true, lastSyncedAt = now, version = link.version + 1),
                    )
                    report = report.copy(deletedRemote = report.deletedRemote + 1)
                }
                link.origin == CalendarLinkOrigin.LOCAL && !canWrite -> {
                    markStateError(link.calendarId, "缺少写日历权限，无法删除远端事件；本地删除已保留")
                    report = report.copy(error = "缺少写日历权限，远端事件未删除；本地删除已保留，授权后会继续")
                }
                else -> {
                    dao.putLink(
                        link.copy(
                            state = CalendarLinkState.CONFLICT,
                            conflictKind = CalendarConflictKind.LOCAL_DELETED_REMOTE_ORIGIN,
                            conflictKept = null,
                            conflictLocalSnapshot = null,
                            conflictRemoteSnapshot = gson.toJson(remote.snapshot()),
                            conflictAt = now,
                            lastSyncedAt = now,
                            version = link.version + 1,
                        ),
                    )
                    report = report.copy(conflicts = report.conflicts + 1)
                }
            }
        }

        // Local -> remote: new local blocks and blocks whose version advanced since the last export.
        if (targetState != null) {
            val exportTarget = targetId.takeIf { it > 0 && canWrite }
            if (exportTarget == null) {
                markStateError(targetId, "请选择可写的导出目标日历")
                report = report.copy(error = "请选择可写的导出目标日历；本地日程尚未导出")
            } else {
                val linked = dao.links().map { it.localEntityId }.toSet()
                coreDao.plans()
                    .filter {
                        it.deletedAt == null && it.status == "SCHEDULED" &&
                            it.source != "CALENDAR" && repository.database.sampleDataDao().find("PLAN", it.id) == null && it.id !in linked &&
                            it.startAt in rangeStart..rangeEnd
                    }
                    .forEach { block ->
                        if (exportLocalBlock(block, exportTarget, now, report)) {
                            report = report.copy(exported = report.exported + 1, reconciled = report.reconciled + (if (existingLocalUriMapping(block.id, exportTarget)) 1 else 0))
                        }
                    }

            }
        }

        if (canWrite) {
        dao.links().filter { it.state == CalendarLinkState.ACTIVE || it.state == CalendarLinkState.CONFLICT }
            .forEach { link ->
                if (link.localEntityType != CALENDAR_LOCAL_ENTITY_TYPE ||
                    allStates.none { it.calendarId == link.calendarId && CalendarSyncDirection.exports(it.direction) }) return@forEach
                if (link.state == CalendarLinkState.CONFLICT) return@forEach
                if (link.readOnlyReason != null) return@forEach
                val block = coreDao.plan(link.localEntityId) ?: return@forEach
                if (block.deletedAt != null || block.status != "SCHEDULED") return@forEach
                if (block.version <= link.lastLocalVersion && link.eventId != 0L) return@forEach
                if (updateRemoteForLocal(block, link, now)) {
                    report = report.copy(updatedRemote = report.updatedRemote + 1)
                }
            }
        }

        val stamp = repository.now().toEpochMilli()
        dao.states().forEach { state ->
            val read = state.direction != CalendarSyncDirection.OFF || state.calendarId == actualCalendarId()
            if (read) {
                dao.putState(
                    state.copy(
                        lastFullScanAt = if (state.direction != CalendarSyncDirection.OFF) stamp else state.lastFullScanAt,
                        lastSyncAt = stamp,
                        lastError = report.error,
                        version = state.version + 1,
                    ),
                )
            }
        }
        val actualCount = CalendarActualExporter(repository, context).sync(actualCalendarId(), rangeStart, rangeEnd)
        return report.copy(exported = report.exported + actualCount)
    }

    // ---- Remote -> local ----------------------------------------------------------------------

    private suspend fun importRemote(remote: RemoteInstance, now: Long, report: CalendarSyncReport) {
        val ruleReason = ensureCalendarRule(remote)
        val planId = calendarPlanId(remote)
        val existing = coreDao.plan(planId)
        val saved = writePlanFromRemote(existing, remote, planId, now)
        dao.putLink(
            CalendarLinkEntity(
                id = calendarLinkId(remote),
                localEntityId = planId,
                calendarId = remote.calendarId,
                eventId = remote.masterId,
                instanceOriginalStart = remote.instanceStart,
                syncAccount = remote.accountName,
                lastLocalVersion = saved.version,
                lastRemoteHash = remote.hash(),
                lastSyncedAt = now,
                origin = CalendarLinkOrigin.REMOTE,
                state = CalendarLinkState.ACTIVE,
                readOnlyReason = ruleReason,
                version = 0,
            ),
        )
    }

    private suspend fun handleRemoteChange(
        link: CalendarLinkEntity,
        remote: RemoteInstance,
        now: Long,
        report: CalendarSyncReport,
    ): CalendarSyncReport {
        val hash = remote.hash()
        if (hash == link.lastRemoteHash) return report
        val ruleReason = ensureCalendarRule(remote)
        val local = coreDao.plan(link.localEntityId)
        if (local == null || local.deletedAt != null) {
            // Remote edited an event the user deleted locally. Ask instead of resurrecting silently.
            dao.putLink(
                link.copy(
                    state = CalendarLinkState.CONFLICT,
                    conflictKind = CalendarConflictKind.LOCAL_DELETED_REMOTE_ORIGIN,
                    conflictRemoteSnapshot = gson.toJson(remote.snapshot()),
                    conflictAt = now,
                    lastRemoteHash = hash,
                    readOnlyReason = ruleReason,
                    version = link.version + 1,
                ),
            )
            return report.copy(conflicts = report.conflicts + 1)
        }
        val localChanged = local.version > link.lastLocalVersion
        if (!localChanged) {
            val saved = writePlanFromRemote(local, remote, local.id, now)
            dao.putLink(
                link.copy(
                    state = CalendarLinkState.ACTIVE,
                    lastLocalVersion = saved.version,
                    lastRemoteHash = hash,
                    lastSyncedAt = now,
                    remoteDeleted = false,
                    readOnlyReason = ruleReason,
                    version = link.version + 1,
                ),
            )
            return report.copy(updated = report.updated + 1)
        }

        // Both changed. Keep the most recent side, record both snapshots, and leave a one-click
        // switch so a wrong automatic choice is never destructive.
        val remoteModified = remote.lastModified.takeIf { it > 0 } ?: link.lastSyncedAt
        val keepLocal = local.updatedAt >= remoteModified && ruleReason == null
        val localSnapshot = gson.toJson(local)
        val remoteSnapshot = gson.toJson(remote.snapshot())
        if (keepLocal) {
            val writeAllowed = canWriteCalendar() && dao.state(link.calendarId)?.direction?.let(CalendarSyncDirection::exports) == true
            try {
                if (writeAllowed) writeRemote(link, local, now)
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "「${local.displayTitle(local.taskId?.let { coreDao.task(it) })}」的日历写入未确认：${error.message ?: "未知错误"}；本地修改保留，下次同步会重新读取系统日历后核对",
                    error,
                )
            }
            val after = coreDao.plan(local.id) ?: local
            dao.putLink(
                link.copy(
                    state = CalendarLinkState.CONFLICT,
                    conflictKind = CalendarConflictKind.BOTH_CHANGED,
                    conflictKept = CalendarLinkOrigin.LOCAL,
                    conflictLocalSnapshot = localSnapshot,
                    conflictRemoteSnapshot = remoteSnapshot,
                    conflictAt = now,
                    readOnlyReason = ruleReason,
                    lastLocalVersion = after.version,
                    lastRemoteHash = if (writeAllowed) requireNotNull(dao.link(link.id)).lastRemoteHash else hash,
                    lastSyncedAt = now,
                    version = link.version + 1,
                ),
            )
        } else {
            val saved = writePlanFromRemote(local, remote, local.id, now)
            dao.putLink(
                link.copy(
                    state = CalendarLinkState.CONFLICT,
                    conflictKind = CalendarConflictKind.BOTH_CHANGED,
                    conflictKept = CalendarLinkOrigin.REMOTE,
                    conflictLocalSnapshot = localSnapshot,
                    conflictRemoteSnapshot = remoteSnapshot,
                    conflictAt = now,
                    readOnlyReason = ruleReason,
                    lastLocalVersion = saved.version,
                    lastRemoteHash = hash,
                    lastSyncedAt = now,
                    version = link.version + 1,
                ),
            )
        }
        return report.copy(conflicts = report.conflicts + 1)
    }

    private suspend fun writePlanFromRemote(
        before: ScheduleBlockEntity?,
        remote: RemoteInstance,
        id: String,
        now: Long,
    ): ScheduleBlockEntity {
        val allDay = remote.allDay
        val zone = if (allDay) ZoneOffset.UTC.id else remote.timezone.ifBlank { repository.zone.id }
        val allDayStart = if (allDay) utcDate(remote.begin).toString() else null
        val allDayEnd = if (allDay) utcDate(remote.end - 1).toString() else null
        val version = repository.nextVersion("PLAN", id, before?.version)
        val saved = ScheduleBlockEntity(
            id = id,
            taskId = null,
            startAt = remote.begin,
            endAt = remote.end,
            status = "SCHEDULED",
            isLocked = before?.isLocked ?: false,
            createdAt = before?.createdAt ?: now,
            updatedAt = now,
            version = version,
            title = remote.title.ifBlank { "系统日历事件" },
            kind = if (allDay) "ALL_DAY" else "TIMED",
            allDayStartDate = allDayStart,
            allDayEndDate = allDayEnd,
            timezoneId = zone,
            notes = remote.description,
            colorOverride = before?.colorOverride,
            source = "CALENDAR",
            sourceRef = remote.sourceRef,
            deletedAt = null,
        )
        // Remote intake is not a user edit: write directly inside the executor transaction and do
        // not clear the user's redo branch.
        coreDao.putPlan(saved)
        return saved
    }

    private suspend fun ensureCalendarRule(remote: RemoteInstance): String? {
        val rrule = remote.rrule?.takeIf { it.isNotBlank() } ?: return null
        val id = calendarRuleId(remote)
        val before = coreDao.recurringRule(id)
        val pattern = parseRrule(remote, id)
        val reason = if (pattern == null) remote.readOnlyReason ?: "原始周期规则已保留；本地模型无法完整表达，按系统展开的实例只读显示。" else pattern.second
        val parsed = pattern?.first ?: RecurringPattern(id, "ONCE",
            Instant.ofEpochMilli(remote.seriesStart).atZone(ZoneId.of(remote.timezone)).toLocalDate().toString(), paused = true)
        val patternJson = gson.toJsonTree(parsed.copy(paused = true)).asJsonObject.apply {
            addProperty("calendarRrule", rrule)
            addProperty("calendarReadOnlyReason", reason)
        }.toString()
        val reference = "$RULE_REFERENCE_PREFIX|${remote.calendarId}|${remote.masterId}|$rrule"
        if (before?.patternJson == patternJson && before.reference == reference &&
            before.title == remote.title.ifBlank { "系统日历事件" }.take(100)) return reason
        val rule = com.timeagent.data.RecurringRuleEntity(
            id = id,
            title = remote.title.ifBlank { "系统日历事件" }.take(100),
            patternJson = patternJson,
            domain = "CALENDAR",
            category = "系统日历",
            reference = reference,
            createdAt = before?.createdAt ?: repository.now().toEpochMilli(),
            updatedAt = repository.now().toEpochMilli(),
            version = repository.nextVersion("RECURRING_RULE", id, before?.version),
            deletedAt = null,
        )
        coreDao.putRecurringRule(rule)
        return reason
    }

    // ---- Local -> remote ----------------------------------------------------------------------

    /** Returns true when a provider write actually happened (as opposed to adopting an orphan). */
    private suspend fun exportLocalBlock(
        block: ScheduleBlockEntity,
        targetCalendarId: Long,
        now: Long,
        report: CalendarSyncReport,
    ): Boolean {
        val existing = findEventByCustomUri(targetCalendarId, customUri(block.id))
        val eventId: Long
        if (existing != null) {
            eventId = existing
            writeRemoteEvent(block, existing, null)
        } else {
            eventId = insertRemoteEvent(block, targetCalendarId)
        }
        val hash = readRemoteHash(eventId, blockHash(block))
        dao.putLink(
            CalendarLinkEntity(
                id = "calendar-link:${sha256("$targetCalendarId:$eventId:${block.id}").take(24)}",
                localEntityId = block.id,
                calendarId = targetCalendarId,
                eventId = eventId,
                instanceOriginalStart = null,
                syncAccount = accountFor(targetCalendarId),
                lastLocalVersion = block.version,
                lastRemoteHash = hash,
                lastSyncedAt = now,
                origin = CalendarLinkOrigin.LOCAL,
                state = CalendarLinkState.ACTIVE,
                version = 0,
            ),
        )
        return true
    }

    private suspend fun updateRemoteForLocal(block: ScheduleBlockEntity, link: CalendarLinkEntity, now: Long): Boolean {
        if (link.instanceOriginalStart != null) {
            writeRecurringException(block, link)
            dao.putLink(link.copy(lastLocalVersion = block.version, lastRemoteHash = exceptionHash(block, link),
                lastSyncedAt = now, state = CalendarLinkState.ACTIVE, remoteDeleted = false, version = link.version + 1))
            return true
        }
        val eventId = if (link.eventId != 0L && remoteEventExists(link.eventId)) {
            writeRemoteEvent(block, link.eventId, link.calendarId)
            link.eventId
        } else {
            insertRemoteEvent(block, link.calendarId)
        }
        val hash = readRemoteHash(eventId, blockHash(block))
        dao.putLink(
            link.copy(
                eventId = eventId,
                lastLocalVersion = block.version,
                lastRemoteHash = hash,
                lastSyncedAt = now,
                state = CalendarLinkState.ACTIVE,
                remoteDeleted = false,
                readOnlyReason = null,
                version = link.version + 1,
            ),
        )
        return true
    }

    /** Used only by conflict resolution, where the local snapshot may not be the current row. */
    private suspend fun writeRemote(link: CalendarLinkEntity, block: ScheduleBlockEntity, now: Long) {
        if (!canWriteCalendar()) throw IllegalArgumentException("需要写日历权限才能保留本地版本")
        if (link.instanceOriginalStart != null) {
            writeRecurringException(block, link)
            dao.putLink(link.copy(lastLocalVersion = block.version, lastRemoteHash = exceptionHash(block, link),
                lastSyncedAt = now, state = CalendarLinkState.ACTIVE, remoteDeleted = false,
                conflictKind = null, conflictKept = null, conflictLocalSnapshot = null,
                conflictRemoteSnapshot = null, conflictAt = null, version = link.version + 1))
            return
        }
        val eventId = if (link.eventId != 0L && remoteEventExists(link.eventId)) {
            writeRemoteEvent(block, link.eventId, link.calendarId)
            link.eventId
        } else {
            insertRemoteEvent(block, if (link.calendarId > 0) link.calendarId else exportCalendarId())
        }
        dao.putLink(
            link.copy(
                eventId = eventId,
                lastLocalVersion = block.version,
                lastRemoteHash = readRemoteHash(eventId, blockHash(block)),
                lastSyncedAt = now,
                state = CalendarLinkState.ACTIVE,
                remoteDeleted = false,
                conflictKind = null,
                conflictKept = null,
                conflictLocalSnapshot = null,
                conflictRemoteSnapshot = null,
                conflictAt = null,
                version = link.version + 1,
            ),
        )
    }

    // ---- Conflict / deletion resolution -------------------------------------------------------

    private suspend fun resolveConflictRaw(linkId: String, keep: CalendarConflictChoice, expectedVersion: Long) {
        val link = requireNotNull(dao.link(linkId)) { "同步记录不存在，可能已断开" }
        if (link.version != expectedVersion) throw com.timeagent.data.StaleChange()
        if (coreDao.plan(link.localEntityId)?.let { it.deletedAt == null && it.version != link.lastLocalVersion } == true)
            throw com.timeagent.data.StaleChange()
        require(link.state == CalendarLinkState.CONFLICT) { "这条记录不是冲突，无需选择版本" }
        val local = link.conflictLocalSnapshot?.let { runCatching { gson.fromJson(it, ScheduleBlockEntity::class.java) }.getOrNull() }
        val remote = link.conflictRemoteSnapshot?.let { runCatching { gson.fromJson(it, CalendarRemoteSnapshot::class.java) }.getOrNull() }
        val now = repository.now().toEpochMilli()
        when (keep) {
            CalendarConflictChoice.LOCAL -> {
                requireNotNull(local) { "本地版本快照缺失，无法恢复" }
                require(link.readOnlyReason == null) { link.readOnlyReason ?: "该日历来源不可修改" }
                val restored = restoreLocalSnapshot(local, now)
                if (dao.state(link.calendarId)?.direction?.let(CalendarSyncDirection::exports) == true) {
                    writeRemote(link, restored, now)
                } else dao.putLink(link.copy(state = CalendarLinkState.ACTIVE, lastLocalVersion = restored.version,
                    conflictKind = null, conflictKept = null, conflictLocalSnapshot = null,
                    conflictRemoteSnapshot = null, conflictAt = null, version = link.version + 1))
            }
            CalendarConflictChoice.REMOTE -> {
                requireNotNull(remote) { "远端版本快照缺失，无法恢复" }
                val current = coreDao.plan(link.localEntityId)
                val saved = if (current == null || current.deletedAt != null) {
                    restoreLocalSnapshot(remote.toPlan(link.localEntityId, now), now)
                } else {
                    val restored = remote.toPlan(current.id, now, current)
                    val after = restored.copy(version = repository.nextVersion("PLAN", current.id, current.version))
                    coreDao.putPlan(after)
                    repository.journal("PLAN", current.id, current, after, "日历冲突：改用系统日历版本")
                    after
                }
                dao.putLink(
                    link.copy(
                        state = CalendarLinkState.ACTIVE,
                        lastLocalVersion = saved.version,
                        lastRemoteHash = remote.hash(),
                        lastSyncedAt = now,
                        remoteDeleted = false,
                        conflictKind = null,
                        conflictKept = null,
                        conflictLocalSnapshot = null,
                        conflictRemoteSnapshot = null,
                        conflictAt = null,
                        version = link.version + 1,
                    ),
                )
                if (link.conflictKept == CalendarLinkOrigin.LOCAL && link.readOnlyReason == null &&
                    dao.state(link.calendarId)?.direction?.let(CalendarSyncDirection::exports) == true) {
                    writeRemote(requireNotNull(dao.link(link.id)), saved, now)
                }
            }
        }
        repository.journal("CALENDAR_LINK", link.id, link, dao.link(link.id), "选择日历冲突版本")
    }

    private suspend fun resolveLocalDeletionRaw(linkId: String, deleteRemote: Boolean, expectedVersion: Long) {
        val link = requireNotNull(dao.link(linkId)) { "同步记录不存在，可能已断开" }
        if (link.version != expectedVersion) throw com.timeagent.data.StaleChange()
        require(link.state == CalendarLinkState.CONFLICT) { "这条记录不需要处理本地删除" }
        require(link.conflictKind == CalendarConflictKind.LOCAL_DELETED_REMOTE_ORIGIN) {
            "这条冲突不是本地删除，请改用版本选择"
        }
        if (deleteRemote) {
            if (!canWriteCalendar()) throw IllegalArgumentException("需要写日历权限才能删除远端事件")
            if (link.eventId != 0L) deleteLinkedEvent(link)
            dao.putLink(
                link.copy(
                    state = CalendarLinkState.DELETED_LOCAL,
                    remoteDeleted = true,
                    conflictKind = null,
                    conflictKept = null,
                    conflictLocalSnapshot = null,
                    conflictRemoteSnapshot = null,
                    conflictAt = null,
                    lastSyncedAt = repository.now().toEpochMilli(),
                    version = link.version + 1,
                ),
            )
        } else {
            // Keep the system event; drop only the mapping so the next scan imports it again as a
            // fresh remote event instead of silently deleting either side.
            dao.removeLink(link.id)
        }
        repository.journal("CALENDAR_LINK", link.id, link, dao.link(link.id), "处理系统日历删除")
    }

    private suspend fun disconnectRaw(mutation: CalendarSyncMutation.Disconnect) {
        if (dao.state(mutation.calendarId)?.version != mutation.expectedVersion) throw com.timeagent.data.StaleChange()
        val now = repository.now().toEpochMilli()
        dao.linksForCalendar(mutation.calendarId).forEach { link ->
            val local = coreDao.plan(link.localEntityId)
            if (link.origin == CalendarLinkOrigin.REMOTE) {
                if (local != null && local.deletedAt == null) {
                    if (mutation.keepImported) {
                        val after = local.copy(
                            source = "MANUAL",
                            sourceRef = null,
                            notes = appendNote(local.notes, "已断开「${mutation.calendarId}」日历同步，保留为本地日程"),
                            updatedAt = now,
                            version = repository.nextVersion("PLAN", local.id, local.version),
                        )
                        coreDao.putPlan(after)
                        repository.journal("PLAN", local.id, local, after, "断开日历同步，保留为本地日程")
                    } else {
                        softDeleteLocal(local, link, "断开日历同步，移入回收站")
                    }
                }
            } else {
                if (mutation.deleteRemoteExports && link.eventId != 0L) {
                    if (!canWriteCalendar()) {
                        throw IllegalArgumentException("需要写日历权限才能删除已导出的系统日历事件")
                    }
                    deleteLinkedEvent(link)
                }
                // Local block stays untouched; it simply stops being linked.
            }
            dao.removeLink(link.id)
            repository.journal("CALENDAR_LINK", link.id, link, null, "断开日历映射")
        }
        if (mutation.calendarId == exportCalendarId() || mutation.calendarId == actualCalendarId())
            saveSettingsRaw(CalendarSyncMutation.SaveSettings(
                if (mutation.calendarId == exportCalendarId()) 0L else exportCalendarId(), rangeDays(),
                if (mutation.calendarId == actualCalendarId()) 0L else actualCalendarId(),
                coreDao.revision("CALENDAR_SETTINGS")?.revision ?: 0L, pastDays()))
        coreDao.recurringRules().filter { it.deletedAt == null &&
            it.reference.startsWith("$RULE_REFERENCE_PREFIX|${mutation.calendarId}|") }.forEach { rule ->
            val after = rule.copy(deletedAt = now, updatedAt = now,
                version = repository.nextVersion("RECURRING_RULE", rule.id, rule.version))
            coreDao.putRecurringRule(after)
            repository.journal("RECURRING_RULE", rule.id, rule, after, "断开系统日历周期")
        }
        val beforeState = dao.state(mutation.calendarId)
        dao.removeState(mutation.calendarId)
        if (beforeState != null) repository.journal("CALENDAR_STATE", mutation.calendarId.toString(), beforeState, null, "断开日历同步")
    }

    // ---- Local soft delete / restore ----------------------------------------------------------

    private suspend fun softDeleteLocal(
        local: ScheduleBlockEntity,
        link: CalendarLinkEntity,
        note: String = "系统日历事件已删除，移入回收站（可撤销）",
    ) {
        if (local.deletedAt != null) return
        val after = local.copy(
            deletedAt = repository.now().toEpochMilli(),
            updatedAt = repository.now().toEpochMilli(),
            version = repository.nextVersion("PLAN", local.id, local.version),
        )
        coreDao.putPlan(after)
        repository.journal("PLAN", local.id, local, after, note)
        if (link.state != CalendarLinkState.DELETED_REMOTE) {
            dao.putLink(
                link.copy(
                    state = CalendarLinkState.DELETED_REMOTE,
                    remoteDeleted = true,
                    lastLocalVersion = after.version,
                    version = link.version + 1,
                ),
            )
        }
    }

    private suspend fun restoreLocalSnapshot(snapshot: ScheduleBlockEntity, now: Long): ScheduleBlockEntity {
        val before = coreDao.plan(snapshot.id)
        val saved = snapshot.copy(
            createdAt = before?.createdAt ?: snapshot.createdAt,
            updatedAt = now,
            version = repository.nextVersion("PLAN", snapshot.id, before?.version),
            deletedAt = null,
        )
        coreDao.putPlan(saved)
        repository.journal("PLAN", snapshot.id, before, saved, "日历冲突：恢复本地版本")
        return saved
    }

    // ---- System calendar access ---------------------------------------------------------------

    private data class RemoteCalendar(val id: Long, val name: String, val account: String, val writable: Boolean)

    private data class EventMeta(
        val id: Long,
        val originalId: Long,
        val originalInstanceTime: Long,
        val exdate: String,
        val customUri: String?,
        val lastSynced: Long,
        val status: Int,
        val rrule: String?,
        val dtStart: Long,
        val dtEnd: Long,
        val timezone: String?,
        val allDay: Boolean,
        val title: String,
        val description: String,
        val location: String,
    )

    private data class RemoteInstance(
        val calendarId: Long,
        val masterId: Long,
        val instanceStart: Long?,
        val begin: Long,
        val end: Long,
        val title: String,
        val allDay: Boolean,
        val timezone: String,
        val rrule: String?,
        val exdate: String?,
        val location: String,
        val description: String,
        val lastModified: Long,
        val readOnlyReason: String?,
        val accountName: String,
        val seriesStart: Long = begin,
    ) {
        val key: String get() = "$calendarId:$masterId:${instanceStart ?: -1L}"
        val sourceRef: String get() = "calendar:$calendarId:$masterId"
        fun hash(): String = remoteHash(title, begin, end, allDay, timezone, rrule, exdate, location, description)
        fun snapshot() = CalendarRemoteSnapshot(
            calendarId = calendarId,
            eventId = masterId,
            instanceOriginalStart = instanceStart,
            title = title,
            begin = begin,
            end = end,
            allDay = allDay,
            timezone = timezone,
            rrule = rrule,
            exdate = exdate,
            location = location,
            description = description,
            lastModified = lastModified,
            readOnlyReason = readOnlyReason,
        )
    }

    private fun readCalendars(): List<RemoteCalendar> {
        val result = mutableListOf<RemoteCalendar>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        requireNotNull(resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)) { "系统日历暂时不可读，请稍后重试" }.use { cursor ->
            while (cursor.moveToNext()) {
                val access = cursor.getInt(3)
                result += RemoteCalendar(
                    id = cursor.getLong(0),
                    name = cursor.getString(1) ?: "系统日历",
                    account = cursor.getString(2).orEmpty(),
                    writable = access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        return result
    }

    private fun readRemoteInstances(calendarId: Long, rangeStart: Long, rangeEnd: Long): List<RemoteInstance> {
        val meta = readEventMeta(calendarId)
        val result = linkedMapOf<String, RemoteInstance>()
        // Non-recurring events outside the range still matter for deletion detection and moves.
        meta.values.filter { it.originalId == 0L && it.rrule.isNullOrBlank() && it.status != CalendarContract.Events.STATUS_CANCELED }
            .forEach { event ->
                if (event.dtStart <= 0) return@forEach
                val end = eventEnd(event)
                val item = RemoteInstance(
                    calendarId = calendarId,
                    masterId = event.id,
                    instanceStart = null,
                    begin = event.dtStart,
                    end = end,
                    title = event.title,
                    allDay = event.allDay,
                    timezone = event.timezone ?: "UTC",
                    rrule = event.rrule,
                    exdate = event.exdate.ifBlank { null },
                    location = event.location,
                    description = event.description,
                    lastModified = event.lastSynced,
                    readOnlyReason = null,
                    accountName = accountFor(calendarId),
                    seriesStart = event.dtStart,
                )
                result[item.key] = item
            }
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, rangeStart)
        ContentUris.appendId(builder, rangeEnd)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.CALENDAR_ID,
        )
        requireNotNull(resolver.query(builder.build(), projection, "${CalendarContract.Instances.CALENDAR_ID}=?",
            arrayOf(calendarId.toString()), null)) { "系统日历事件暂时不可读，未修改本地日程" }.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val eventMeta = meta[eventId]
                if (eventMeta != null && eventMeta.status == CalendarContract.Events.STATUS_CANCELED) continue
                val masterId = if (eventMeta != null && eventMeta.originalId > 0) eventMeta.originalId else eventId
                val master = meta[masterId] ?: eventMeta
                val begin = cursor.getLong(1)
                val end = cursor.getLong(2)
                if (end <= begin) continue
                val instanceStart = when {
                    eventMeta != null && eventMeta.originalId > 0 && eventMeta.originalInstanceTime > 0 -> eventMeta.originalInstanceTime
                    master?.rrule.isNullOrBlank() -> null
                    else -> begin
                }
                val rrule = master?.rrule
                val readOnlyReason = rrule?.takeIf { it.isNotBlank() }?.let { reasonForRrule(it) }
                val item = RemoteInstance(
                    calendarId = calendarId,
                    masterId = masterId,
                    instanceStart = instanceStart,
                    begin = begin,
                    end = end,
                    title = cursor.getString(3) ?: master?.title.orEmpty(),
                    allDay = cursor.getInt(4) != 0,
                    timezone = cursor.getString(5) ?: master?.timezone ?: "UTC",
                    rrule = rrule,
                    exdate = master?.exdate?.ifBlank { null },
                    location = cursor.getString(6) ?: master?.location.orEmpty(),
                    description = cursor.getString(7) ?: master?.description.orEmpty(),
                    lastModified = master?.lastSynced ?: 0,
                    readOnlyReason = readOnlyReason,
                    accountName = accountFor(calendarId),
                    seriesStart = master?.dtStart ?: begin,
                )
                result[item.key] = item
            }
        }
        return result.values.toList()
    }

    private fun readEventMeta(calendarId: Long): Map<Long, EventMeta> {
        val result = mutableMapOf<Long, EventMeta>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.EXDATE,
            CalendarContract.Events.CUSTOM_APP_URI,
            CalendarContract.Events.LAST_SYNCED,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
        )
        val selection = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DELETED}=0 " +
            "AND ${CalendarContract.Events.LAST_SYNCED}=0"
        val cursor = try {
            resolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, arrayOf(calendarId.toString()), null)
        } catch (error: SecurityException) {
            throw IllegalArgumentException("读取系统日历失败：缺少读权限", error)
        }
        requireNotNull(cursor) { "系统日历事件暂时不可读，未修改本地日程" }.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                result[id] = EventMeta(
                    id = id,
                    originalId = it.getLong(1),
                    originalInstanceTime = it.getLong(2),
                    exdate = it.getString(3).orEmpty(),
                    customUri = it.getString(4),
                    lastSynced = 0,
                    status = it.getInt(6),
                    rrule = it.getString(7),
                    dtStart = it.getLong(8),
                    dtEnd = it.getLong(9),
                    timezone = it.getString(11),
                    allDay = it.getInt(12) != 0,
                    title = it.getString(13).orEmpty(),
                    description = it.getString(14).orEmpty(),
                    location = it.getString(15).orEmpty(),
                )
            }
        }
        return result
    }

    private fun eventEnd(meta: EventMeta): Long {
        if (meta.dtEnd > meta.dtStart) return meta.dtEnd
        return meta.dtStart + 60 * 60_000L
    }

    private suspend fun insertRemoteEvent(block: ScheduleBlockEntity, calendarId: Long): Long {
        if (!canWriteCalendar()) throw IllegalArgumentException("需要写日历权限才能导出日程")
        val values = eventValues(block, insert = true).apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.CUSTOM_APP_URI, customUri(block.id))
        }
        val uri = try {
            resolver.insert(CalendarContract.Events.CONTENT_URI, values)
        } catch (error: SecurityException) {
            throw IllegalArgumentException("写入系统日历失败：缺少写权限", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("写入系统日历失败：${error.message ?: "系统日历拒绝了该事件"}", error)
        }
        return requireNotNull(uri) { "写入系统日历失败：系统没有返回事件编号" }.let { ContentUris.parseId(it) }
    }

    private suspend fun writeRemoteEvent(block: ScheduleBlockEntity, eventId: Long, calendarId: Long?) {
        if (!canWriteCalendar()) throw IllegalArgumentException("需要写日历权限才能更新系统日历")
        val values = eventValues(block, insert = false)
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val updated = try {
            resolver.update(uri, values, null, null)
        } catch (error: SecurityException) {
            throw IllegalArgumentException("更新系统日历失败：缺少写权限", error)
        }
        if (updated == 0) {
            throw IllegalArgumentException(
                "更新系统日历失败：事件已不存在（可能已在其他设备删除）；本地修改仍保留，下一次同步会重新建立映射",
            )
        }
    }

    private fun exceptionId(link: CalendarLinkEntity): Long? {
        val selection = "${CalendarContract.Events.ORIGINAL_ID}=? AND " +
            "${CalendarContract.Events.ORIGINAL_INSTANCE_TIME}=? AND ${CalendarContract.Events.DELETED}=0"
        return requireNotNull(resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            selection, arrayOf(link.eventId.toString(), requireNotNull(link.instanceOriginalStart).toString()), null)) {
            "系统日历例外暂时不可读，未修改周期主事件"
        }.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private suspend fun writeRecurringException(block: ScheduleBlockEntity, link: CalendarLinkEntity) {
        val master = requireNotNull(readEventMeta(link.calendarId)[link.eventId]) { "周期主事件已删除，请重新同步" }
        val values = eventValues(block, insert = true).apply {
            put(CalendarContract.Events.CALENDAR_ID, link.calendarId)
            put(CalendarContract.Events.ORIGINAL_ID, link.eventId)
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, requireNotNull(link.instanceOriginalStart))
            put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (master.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_LOCATION, master.location)
            put(CalendarContract.Events.CUSTOM_APP_URI, customUri(block.id))
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
        }
        val existing = exceptionId(link)
        if (existing == null) {
            requireNotNull(resolver.insert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, link.eventId),
                values)) { "系统日历未接收本次例外，周期主事件未修改" }
        } else check(resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing),
            values, null, null) == 1) { "本次例外已变化，请重新同步" }
    }

    private suspend fun exceptionHash(block: ScheduleBlockEntity, link: CalendarLinkEntity): String {
        val master = requireNotNull(readEventMeta(link.calendarId)[link.eventId]) { "周期主事件已删除，请重新同步" }
        return remoteHash(block.displayTitle(block.taskId?.let { coreDao.task(it) }).take(200), block.startAt, block.endAt,
            block.kind == "ALL_DAY", if (block.kind == "ALL_DAY") "UTC" else block.timezoneId,
            master.rrule, master.exdate.ifBlank { null }, master.location, block.notes)
    }

    private fun deleteLinkedEvent(link: CalendarLinkEntity) {
        if (link.instanceOriginalStart == null) {
            deleteRemoteEvent(link.eventId)
            return
        }
        val master = requireNotNull(readEventMeta(link.calendarId)[link.eventId]) { "周期主事件已变化，请重新同步" }
        val values = ContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, link.instanceOriginalStart)
            put(CalendarContract.Events.ORIGINAL_ID, link.eventId)
            put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (master.allDay) 1 else 0)
            put(CalendarContract.Events.CALENDAR_ID, link.calendarId)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
            put(CalendarContract.Events.DTSTART, link.instanceOriginalStart)
            put(CalendarContract.Events.DTEND, link.instanceOriginalStart + (eventEnd(master) - master.dtStart))
            put(CalendarContract.Events.EVENT_TIMEZONE, master.timezone ?: "UTC")
            put(CalendarContract.Events.ALL_DAY, if (master.allDay) 1 else 0)
        }
        val existing = exceptionId(link)
        if (existing == null) requireNotNull(resolver.insert(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, link.eventId), values)) {
            "系统日历未取消本次实例，周期主事件未删除"
        } else check(resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing),
            values, null, null) == 1) { "本次例外已变化，请重新同步" }
    }

    private fun deleteRemoteEvent(eventId: Long) {
        if (eventId <= 0L) return
        if (!canWriteCalendar()) throw IllegalArgumentException("需要写日历权限才能删除系统日历事件")
        try {
            resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
        } catch (error: SecurityException) {
            throw IllegalArgumentException("删除系统日历事件失败：缺少写权限", error)
        }
    }

    private fun remoteEventExists(eventId: Long): Boolean {
        val projection = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DELETED)
        return requireNotNull(resolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                projection,
                null,
                null,
                null,
            )) { "系统日历暂时不可读，未将事件判为删除" }
            .use { cursor -> cursor.moveToFirst() && cursor.getInt(1) == 0 }
    }

    private fun findEventByCustomUri(calendarId: Long, customUri: String): Long? {
        val projection = arrayOf(CalendarContract.Events._ID)
        val selection = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.CUSTOM_APP_URI}=? AND ${CalendarContract.Events.DELETED}=0"
        return requireNotNull(resolver.query(CalendarContract.Events.CONTENT_URI, projection, selection,
            arrayOf(calendarId.toString(), customUri), null)) { "系统日历暂时不可读，未重复创建事件" }
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun existingLocalUriMapping(localId: String, calendarId: Long): Boolean =
        findEventByCustomUri(calendarId, customUri(localId)) != null

    private fun readRemoteHash(eventId: Long, fallback: String): String {
        if (eventId <= 0L) return fallback
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.EXDATE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
        )
        return try {
            resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), projection, null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return fallback
                    remoteHash(
                        cursor.getString(0).orEmpty(),
                        cursor.getLong(1),
                        cursor.getLong(2),
                        cursor.getInt(3) != 0,
                        cursor.getString(4).orEmpty(),
                        cursor.getString(5),
                        cursor.getString(6),
                        cursor.getString(7).orEmpty(),
                        cursor.getString(8).orEmpty(),
                    )
                } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private suspend fun eventValues(block: ScheduleBlockEntity, insert: Boolean): ContentValues {
        val allDay = block.kind == "ALL_DAY"
        val title = block.displayTitle(block.taskId?.let { coreDao.task(it) }).take(200).ifBlank { "日程" }
        return ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, block.startAt)
            put(CalendarContract.Events.DTEND, block.endAt)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else block.timezoneId)
            put(CalendarContract.Events.DESCRIPTION, block.notes)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
            if (insert) put(CalendarContract.Events.HAS_ALARM, 0)
        }
    }

    private fun localCalendarUri(): Uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT_NAME)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private fun accountFor(calendarId: Long): String = runCatching {
        readCalendars().firstOrNull { it.id == calendarId }?.account.orEmpty()
    }.getOrDefault("")

    // ---- RRULE -> local recurring model -------------------------------------------------------

    /**
     * Returns null when the RRULE cannot be represented by [RecurringPattern], and a pair
     * (pattern, reason) otherwise. Complex BYSETPOS/BYDAY-with-ordinal rules stay read-only and are
     * never flattened into a single ordinary schedule block: every occurrence is still imported
     * from CalendarContract.Instances, and the raw rule is kept on the linked rule row.
     */
    private fun parseRrule(remote: RemoteInstance, ruleId: String): Pair<RecurringPattern, String?>? {
        val raw = remote.rrule?.takeIf { it.isNotBlank() } ?: return null
        reasonForRrule(raw)?.let { return null }
        val fields = raw.removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate {
            val parts = it.split('=', limit = 2)
            if (parts.size != 2) return null
            parts[0].uppercase() to parts[1]
        }
        val freq = fields["FREQ"]?.uppercase() ?: return null
        val interval = fields["INTERVAL"]?.toIntOrNull() ?: 1
        if (interval != 1) return null
        if (fields.containsKey("BYSETPOS") || fields.containsKey("BYHOUR") || fields.containsKey("BYMINUTE") ||
            fields.containsKey("BYSECOND") || fields.containsKey("RDATE") || fields.containsKey("EXRULE")
        ) {
            return null
        }
        val zone = if (remote.allDay) ZoneOffset.UTC else runCatching { ZoneId.of(remote.timezone) }.getOrDefault(repository.zone)
        val start = Instant.ofEpochMilli(remote.seriesStart).atZone(zone)
        val startDate = start.toLocalDate()
        if ((freq == "MONTHLY" && startDate.dayOfMonth > 28) ||
            (freq == "YEARLY" && startDate.monthValue == 2 && startDate.dayOfMonth == 29) ||
            remote.end - remote.begin > 1440L * 60_000L) return null
        val byDay = fields["BYDAY"]?.split(',')?.mapNotNull { parseByDay(it) }?.takeIf { it.isNotEmpty() }
        if (fields["BYDAY"] != null && parseByDayList(fields["BYDAY"]!!) == null) return null
        val weekdays = (byDay ?: listOf(startDate.dayOfWeek.value)).distinct()
        val frequency = when (freq) {
            "DAILY" -> if (byDay == null || weekdays.size == 7) "DAILY" else "WEEKDAYS"
            "WEEKLY" -> if (weekdays.size == 7) "DAILY" else "WEEKDAYS"
            "MONTHLY" -> {
                val byMonthDay = fields["BYMONTHDAY"]?.toIntOrNull()
                if (fields["BYDAY"] != null || (byMonthDay != null && byMonthDay != startDate.dayOfMonth)) return null
                "MONTHLY"
            }
            "YEARLY" -> {
                val byMonth = fields["BYMONTH"]?.toIntOrNull()
                val byMonthDay = fields["BYMONTHDAY"]?.toIntOrNull()
                if (fields["BYDAY"] != null) return null
                if (byMonth != null && byMonth != startDate.monthValue) return null
                if (byMonthDay != null && byMonthDay != startDate.dayOfMonth) return null
                "YEARLY"
            }
            else -> return null
        }
        val until = fields["UNTIL"]?.let(::parseUntilDate)
        val count = fields["COUNT"]?.toIntOrNull()
        val untilDate = until ?: count?.let { countUntilDate(startDate, frequency, weekdays, it) }
        if (untilDate != null && untilDate < startDate) return null
        val pattern = RecurringPattern(
            id = ruleId,
            frequency = frequency,
            startDate = startDate.toString(),
            weekdays = if (frequency == "WEEKDAYS") weekdays else emptyList(),
            minuteOfDay = if (remote.allDay) null else start.hour * 60 + start.minute,
            durationMinutes = ((remote.end - remote.begin) / 60_000L).toInt().coerceIn(1, 1440),
            zoneId = zone.id,
            untilDate = untilDate?.toString(),
            paused = true,
        )
        return pattern to null
    }

    private fun reasonForRrule(raw: String): String? {
        val fields = raw.removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0].uppercase() to parts[1] else null
        }.toMap()
        val unsupported = fields.keys - setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH", "COUNT", "UNTIL", "WKST")
        if (unsupported.isNotEmpty()) return "系统日历使用了 ${unsupported.joinToString("/")} 规则，本地模型无法完整表达，按系统展开的实例只读显示。"
        if (fields["FREQ"] !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY"))
            return "此周期频率无法用本地规则表达，按系统展开的实例只读显示。"
        if (fields["BYMONTHDAY"]?.toIntOrNull()?.let { it !in 1..28 } == true ||
            fields["BYMONTHDAY"]?.let { it.toIntOrNull() == null } == true ||
            (fields["BYMONTHDAY"] != null && fields["FREQ"] !in setOf("MONTHLY", "YEARLY")) ||
            (fields["BYMONTH"] != null && fields["FREQ"] != "YEARLY"))
            return "此日期筛选无法用本地周期完整表达，按系统展开的实例只读显示。"
        if (fields["BYSETPOS"] != null) return "系统日历使用了 BYSETPOS 规则，本地周期模型无法完整表达；已按系统展开的实例只读显示。"
        if (fields["BYDAY"] != null) {
            val bad = fields["BYDAY"]!!.split(',').any { it.length > 2 }
            if (bad) return "系统日历使用了带序号的星期规则（如“第 2 个周一”），本地周期模型无法完整表达；已按系统展开的实例只读显示。"
        }
        if (fields["INTERVAL"]?.toIntOrNull()?.let { it != 1 } == true) {
            return "系统日历设置了“每 N 天/周”的间隔，本地周期模型暂不支持；已按系统展开的实例只读显示。"
        }
        if (fields["RDATE"] != null || fields["EXRULE"] != null) {
            return "系统日历使用了 RDATE/EXRULE，本地周期模型无法完整表达；已按系统展开的实例只读显示。"
        }
        return null
    }

    private fun parseByDayList(value: String): List<Int>? {
        val result = mutableListOf<Int>()
        value.split(',').forEach { token ->
            val day = parseByDay(token) ?: return null
            result += day
        }
        return result.distinct()
    }

    private fun parseByDay(token: String): Int? = when (token.trim().uppercase().takeLast(2)) {
        "MO" -> 1
        "TU" -> 2
        "WE" -> 3
        "TH" -> 4
        "FR" -> 5
        "SA" -> 6
        "SU" -> 7
        else -> null
    }

    private fun parseUntilDate(value: String): LocalDate? = runCatching {
        if (value.length >= 8 && value[4].isDigit()) LocalDate.parse(value.substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        else null
    }.getOrNull()

    private fun countUntilDate(start: LocalDate, frequency: String, weekdays: List<Int>, count: Int): LocalDate? {
        if (count <= 0) return null
        return when (frequency) {
            "DAILY" -> start.plusDays(count.toLong() - 1)
            "MONTHLY" -> start.plusMonths(count.toLong() - 1)
            "YEARLY" -> start.plusYears(count.toLong() - 1)
            "WEEKDAYS" -> {
                val first = generateSequence(start) { it.plusDays(1) }.first { it.dayOfWeek.value in weekdays }
                var date = first.plusWeeks((count.toLong() - 1) / weekdays.size)
                var remainder = (count - 1) % weekdays.size
                while (remainder > 0) {
                    date = date.plusDays(1)
                    if (date.dayOfWeek.value in weekdays) remainder--
                }
                date
            }
            else -> null
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun occurrenceInRange(link: CalendarLinkEntity, local: ScheduleBlockEntity?, rangeStart: Long, rangeEnd: Long): Boolean {
        val start = link.instanceOriginalStart ?: local?.startAt ?: return false
        return start in rangeStart..rangeEnd
    }

    private fun calendarPlanId(remote: RemoteInstance): String =
        "calendar-plan:" + sha256("${remote.calendarId}:${remote.masterId}:${remote.instanceStart ?: -1L}").take(24)

    private fun calendarLinkId(remote: RemoteInstance): String =
        "calendar-link:" + sha256("${remote.calendarId}:${remote.masterId}:${remote.instanceStart ?: -1L}").take(24)

    private fun calendarRuleId(remote: RemoteInstance): String =
        "calendar-rule:" + sha256("${remote.calendarId}:${remote.masterId}").take(24)

    private fun customUri(localId: String): String = "timeagent://schedule/${Uri.encode(localId)}"

    private suspend fun markStateError(calendarId: Long, message: String) {
        if (calendarId <= 0L) return
        val state = dao.state(calendarId) ?: return
        dao.putState(state.copy(lastError = message, version = state.version + 1))
    }

    private fun appendNote(notes: String, line: String): String =
        if (notes.isBlank()) line else "$notes\n$line"

    private fun summarize(block: ScheduleBlockEntity): String =
        "${Instant.ofEpochMilli(block.startAt).atZone(ZoneId.of(block.timezoneId)).toLocalDateTime()} – " +
            "${Instant.ofEpochMilli(block.endAt).atZone(ZoneId.of(block.timezoneId)).toLocalDateTime()}"

    private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    private fun blockHash(block: ScheduleBlockEntity): String {
        val allDay = block.kind == "ALL_DAY"
        return remoteHash(
            block.title,
            block.startAt,
            block.endAt,
            allDay,
            if (allDay) "UTC" else block.timezoneId,
            null,
            null,
            "",
            block.notes,
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** JSON snapshot of the remote side stored on a conflict link. */
data class CalendarRemoteSnapshot(
    val calendarId: Long,
    val eventId: Long,
    val instanceOriginalStart: Long?,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val timezone: String,
    val rrule: String?,
    val exdate: String?,
    val location: String,
    val description: String,
    val lastModified: Long,
    val readOnlyReason: String?,
) {
    fun hash(): String = remoteHash(title, begin, end, allDay, timezone, rrule, exdate, location, description)

    fun toPlan(localId: String, now: Long, before: ScheduleBlockEntity? = null): ScheduleBlockEntity = ScheduleBlockEntity(
        id = localId,
        taskId = before?.taskId,
        startAt = begin,
        endAt = end,
        isLocked = before?.isLocked ?: false,
        createdAt = before?.createdAt ?: now,
        updatedAt = now,
        title = title.ifBlank { "系统日历事件" },
        kind = if (allDay) "ALL_DAY" else "TIMED",
        allDayStartDate = if (allDay) Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate().toString() else null,
        allDayEndDate = if (allDay) Instant.ofEpochMilli(end - 1).atZone(ZoneOffset.UTC).toLocalDate().toString() else null,
        timezoneId = if (allDay) "UTC" else timezone,
        notes = description,
        colorOverride = before?.colorOverride,
        source = "CALENDAR",
        sourceRef = "calendar:$calendarId:$eventId",
        deletedAt = null,
    )
}

private fun remoteHash(
    title: String,
    begin: Long,
    end: Long,
    allDay: Boolean,
    timezone: String,
    rrule: String?,
    exdate: String?,
    location: String,
    description: String,
): String {
    // The nine fields mandated by N04. Null and empty are normalized so a provider round-trip that
    // materializes an empty column does not look like a user edit.
    val normalized = listOf(
        title,
        begin.toString(),
        end.toString(),
        allDay.toString(),
        timezone,
        rrule.orEmpty(),
        exdate.orEmpty(),
        location,
        description,
    ).joinToString("\u0001")
    return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Serializable result returned as the command payload. */
data class CalendarSyncReport(
    val imported: Int = 0,
    val updated: Int = 0,
    val deletedLocal: Int = 0,
    val exported: Int = 0,
    val updatedRemote: Int = 0,
    val deletedRemote: Int = 0,
    val conflicts: Int = 0,
    val reconciled: Int = 0,
    val paused: Boolean = false,
    val error: String? = null,
) {
    fun summary(): String = buildString {
        if (paused) append("同步已暂停").append('；')
        append("导入 $imported，更新 $updated，本地回收 $deletedLocal，导出 $exported，远端更新 $updatedRemote，远端删除 $deletedRemote")
        if (reconciled > 0) append("，修复映射 $reconciled")
        if (conflicts > 0) append("，冲突 $conflicts 条待处理")
        error?.let { append("；").append(it) }
    }
}

data class CalendarSyncSnapshot(
    val canRead: Boolean,
    val canWrite: Boolean,
    val calendars: List<CalendarChoice>,
    val exportCalendarId: Long,
    val rangeDays: Int,
    val pastDays: Int,
    val actualCalendarId: Long,
    val settingsVersion: Long,
    val states: List<CalendarSyncStateEntity>,
    val conflicts: List<CalendarConflictView>,
    val linkCount: Int,
    val readOnlyReasons: List<String>,
    val series: List<CalendarSeries>,
    val lastSyncAt: Long?,
    val lastError: String?,
)

data class CalendarChoice(
    val id: Long,
    val version: Long?,
    val name: String,
    val account: String,
    val writable: Boolean,
    val direction: String,
    val lastSyncAt: Long?,
    val lastError: String?,
)

data class CalendarConflictView(
    val linkId: String,
    val version: Long,
    val calendarId: Long,
    val kind: String,
    val kept: String?,
    val localTitle: String,
    val localSummary: String,
    val remoteTitle: String,
    val remoteSummary: String,
    val canUseLocal: Boolean,
    val canUseRemote: Boolean,
)
