package com.timeagent.reminders

import com.google.gson.Gson
import com.timeagent.data.ReminderEntity
import com.timeagent.data.StaleChange
import com.timeagent.data.TaskEntity
import com.timeagent.data.TimeRepository
import com.timeagent.domain.command.CommandStatus
import com.timeagent.domain.command.Origin
import com.timeagent.domain.command.ReminderMutation
import com.timeagent.domain.command.WriteReminder
import com.timeagent.domain.command.throwIfRejected
import java.util.Locale
import java.util.UUID

/**
 * Owns the persisted reminder-instance lifecycle.
 *
 * The reminder row stays the user-visible source of truth. Instances only capture one planned fire
 * each, so a repeated or snoozed reminder can be cancelled, rescheduled after boot, and audited
 * without ever rewriting `reminder.remindAt`.
 *
 * [applyMutation] is called by CommandExecutor inside `repository.transaction` +
 * `repository.commandWrite`. Save/Delete delegate to the raw reminder helpers on TimeRepository,
 * while instance-only transitions are journaled as `REMINDER_INSTANCE` so the main history layer can
 * undo them. Fire/Reconcile deliberately do not journal: they are background effects and must not
 * clear the user's redo stack.
 */
class ReminderInstanceRepository(private val repository: TimeRepository) {
    private val coreDao = repository.database.dao()
    private val instanceDao = repository.database.reminderInstanceDao()
    private val gson = Gson()

    companion object {
        /** A queued/inexact broadcast may be late; a restart never replays an older instance. */
        const val GRACE_MS = 10 * 60_000L
        private const val EARLY_TOLERANCE_MS = 5_000L
        private val OWNER_TYPES = setOf(
            "REMINDER", "TASK", "PLAN", "SCHEDULE", "SCHEDULE_BLOCK", "DEADLINE",
            "RECURRING_INSTANCE", "HABIT", "IDEA", "ACTIVITY", "PROJECT", "OCCURRENCE",
        )
    }

    private data class OwnerSnapshot(
        val type: String,
        val id: String?,
        val version: Long,
        val active: Boolean,
    )

    // ---- Public convenience API used by UI, receivers and the coordinator -----------------------

    suspend fun save(
        item: ReminderEntity,
        expectedVersion: Long? = null,
        automatic: Boolean = false,
        requestId: String = "reminder-save:${UUID.randomUUID()}",
    ): ReminderEntity {
        val result = apply(WriteReminder(
            requestId,
            ReminderMutation.Save(item, expectedVersion, automatic),
            if (automatic) Origin.SYSTEM else Origin.UI,
        ))
        return requireNotNull(gson.fromJson(result.payload, ReminderEntity::class.java)) {
            "提醒已保存，但返回内容为空"
        }
    }

    suspend fun delete(
        id: String,
        expectedVersion: Long? = null,
        requestId: String = "reminder-delete:$id:$expectedVersion",
    ) {
        apply(WriteReminder(requestId, ReminderMutation.Delete(id, expectedVersion), Origin.UI))
    }

    suspend fun snooze(
        id: String,
        fireAt: Long,
        expectedVersion: Long? = null,
        requestId: String = "reminder-snooze:$id:$fireAt",
    ): ReminderInstanceEntity {
        val result = apply(WriteReminder(
            requestId,
            ReminderMutation.Snooze(id, expectedVersion, fireAt),
            Origin.NOTIFICATION,
        ))
        return requireNotNull(gson.fromJson(result.payload, ReminderInstanceEntity::class.java)) {
            "稍后提醒已保存，但返回内容为空"
        }
    }

    suspend fun act(
        id: String,
        expectedVersion: Long? = null,
        completeTask: Boolean = false,
        requestId: String = "reminder-act:$id:$expectedVersion:$completeTask",
    ) {
        apply(WriteReminder(
            requestId,
            ReminderMutation.Act(id, expectedVersion, completeTask),
            Origin.NOTIFICATION,
        ))
    }

    /**
     * Claims one due instance and returns everything the notification needs. A duplicate/stale
     * broadcast returns null, so two alarms can never post the same fire twice.
     */
    suspend fun fire(instanceId: String): ReminderFireEvent? {
        val result = repository.commands.executeForRepository(
            WriteReminder("reminder-fire:$instanceId", ReminderMutation.Fire(instanceId), Origin.NOTIFICATION),
        )
        if (result.status != CommandStatus.APPLIED) return null
        return result.payload?.let { runCatching { gson.fromJson(it, ReminderFireEvent::class.java) }.getOrNull() }
    }

    suspend fun reconcile(requestId: String = "reminder-reconcile:${UUID.randomUUID()}"): Int {
        val result = apply(WriteReminder(requestId, ReminderMutation.Reconcile, Origin.SYSTEM))
        return result.payload?.toIntOrNull() ?: 0
    }

    suspend fun snapshot(): List<ReminderInstanceEntity> = repository.transaction { instanceDao.all() }

    suspend fun scheduledSnapshot(): List<ReminderInstanceEntity> = repository.transaction { instanceDao.scheduled() }

    suspend fun instancesForReminder(reminderId: String): List<ReminderInstanceEntity> =
        repository.transaction { instanceDao.forReminder(reminderId) }

    /**
     * Notification tags whose `when` still matches a live delivered instance. The coordinator uses
     * this to dismiss notifications after a reminder is edited, disabled, deleted or acted upon.
     */
    suspend fun notificationTimes(): Map<String, Long> = repository.transaction {
        val reminders = coreDao.reminders().filter { it.enabled }
        val all = instanceDao.all().groupBy { it.reminderId }
        reminders.mapNotNull { reminder ->
            val owner = ownerState(reminder)
            if (!owner.active) return@mapNotNull null
            val current = all[reminder.id].orEmpty().filter { it.reminderVersion == reminder.version }
            if (current.any { it.state == REMINDER_STATE_ACTED || it.state == REMINDER_STATE_DISMISSED }) {
                return@mapNotNull null
            }
            if (current.any { it.kind == REMINDER_INSTANCE_SNOOZE && it.state == REMINDER_STATE_SCHEDULED }) {
                return@mapNotNull null
            }
            if (current.any {
                    it.ownerVersion != owner.version ||
                        it.ownerType != owner.type ||
                        it.ownerId != owner.id
                }
            ) {
                return@mapNotNull null
            }
            val latestFired = current.filter { it.state == REMINDER_STATE_FIRED }.maxByOrNull { it.fireAt }
                ?: return@mapNotNull null
            reminder.id to latestFired.fireAt
        }.toMap()
    }

    // ---- CommandExecutor entry point ------------------------------------------------------------

    /**
     * Runs inside the executor's outer transaction. Returning a value here becomes the command
     * payload; null means the command had no payload.
     */
    suspend fun applyMutation(mutation: ReminderMutation): Any? = when (mutation) {
        is ReminderMutation.Save -> saveRaw(mutation.item, mutation.expectedVersion, mutation.automatic)
        is ReminderMutation.Delete -> { deleteRaw(mutation.id, mutation.expectedVersion); null }
        is ReminderMutation.Snooze -> snoozeRaw(mutation.id, mutation.expectedVersion, mutation.fireAt)
        is ReminderMutation.Act -> { actRaw(mutation.id, mutation.expectedVersion, mutation.completeTask); null }
        is ReminderMutation.Fire -> fireRaw(mutation.instanceId)
        ReminderMutation.Reconcile -> reconcileRaw()
    }

    // ---- User mutations -------------------------------------------------------------------------

    private suspend fun saveRaw(item: ReminderEntity, expectedVersion: Long?, automatic: Boolean): ReminderEntity {
        val normalized = normalize(item)
        val owner = ownerState(normalized)
        require(owner.active || !normalized.enabled) { ownerError(normalized) }
        val saved = repository.saveReminderRaw(
            item = normalized,
            expectedVersion = expectedVersion,
            automatic = automatic,
        )
        cancelScheduled(saved.id, journal = !automatic, action = "提醒已更新，取消旧实例")
        planCanonical(saved, journal = !automatic)
        return saved
    }

    private suspend fun deleteRaw(id: String, expectedVersion: Long?) {
        val reminder = coreDao.reminder(id) ?: throw IllegalArgumentException("提醒不存在")
        if (expectedVersion != null) requireVersion(reminder, expectedVersion)
        cancelScheduled(id, journal = true, action = "提醒已删除，取消后续实例")
        repository.deleteReminderRaw(id = id, expectedVersion = expectedVersion)
    }

    private suspend fun snoozeRaw(id: String, expectedVersion: Long?, fireAt: Long): ReminderInstanceEntity {
        val reminder = requireNotNull(coreDao.reminder(id)) { "提醒不存在" }
        if (expectedVersion != null) requireVersion(reminder, expectedVersion)
        require(reminder.enabled) { "提醒已关闭，请先重新开启" }
        require(fireAt > now()) { "请选择未来的稍后时间" }
        val owner = ownerState(reminder)
        require(owner.active) { "提醒来源已删除或已完成，不能稍后提醒" }
        // Snooze cancels the current primary/repeat tail but never edits reminder.remindAt.
        cancelScheduled(id, journal = true, action = "稍后提醒取代原计划")
        val instanceId = "snooze:${reminder.id}:${reminder.version}:$fireAt:${UUID.randomUUID()}"
        val instance = newInstance(
            reminder = reminder,
            owner = owner,
            id = instanceId,
            fireAt = fireAt,
            kind = REMINDER_INSTANCE_SNOOZE,
            repeatIndex = 0,
            state = REMINDER_STATE_SCHEDULED,
            version = 0,
        )
        put(instance, null, journal = true, action = "稍后提醒")
        return instance
    }

    private suspend fun actRaw(id: String, expectedVersion: Long?, completeTask: Boolean) {
        val reminder = requireNotNull(coreDao.reminder(id)) { "提醒不存在" }
        if (expectedVersion != null) requireVersion(reminder, expectedVersion)
        val owner = ownerState(reminder)
        val all = instanceDao.forReminder(id)
        val currentPrimaryId = primaryInstanceId(reminder)

        // Opening the reminder or pressing 完成 is a handling event: every pending fire is removed.
        all.filter { it.state == REMINDER_STATE_SCHEDULED && it.id != currentPrimaryId }.forEach { instance ->
            val after = instance.copy(state = REMINDER_STATE_CANCELLED, version = instance.version + 1)
            put(after, instance, journal = true, action = "处理提醒，取消后续")
        }
        // Fired notifications for this revision become ACTED; older revisions stay as history.
        all.filter {
            it.state == REMINDER_STATE_FIRED && it.reminderVersion == reminder.version && it.id != currentPrimaryId
        }.forEach { instance ->
            val after = instance.copy(state = REMINDER_STATE_ACTED, version = instance.version + 1)
            put(after, instance, journal = true, action = "处理提醒")
        }
        // Keep a current-revision primary marker even if the primary was already cancelled by a
        // snooze. This suppresses canonical re-planning for this reminder revision.
        val currentPrimary = all.firstOrNull { it.id == currentPrimaryId }
        if (currentPrimary == null) {
            val after = newInstance(
                reminder, owner, currentPrimaryId, reminder.remindAt, REMINDER_INSTANCE_PRIMARY,
                0, REMINDER_STATE_ACTED, 0,
            )
            put(after, null, journal = true, action = "处理提醒")
        } else if (currentPrimary.state != REMINDER_STATE_ACTED) {
            val after = currentPrimary.copy(state = REMINDER_STATE_ACTED, version = currentPrimary.version + 1)
            put(after, currentPrimary, journal = true, action = "处理提醒")
        }

        if (completeTask) {
            val taskId = when {
                owner.type == "TASK" -> owner.id
                reminder.taskId != null -> reminder.taskId
                else -> null
            }
            taskId?.let { coreDao.task(it) }?.takeIf(::isTaskActive)?.let { task ->
                repository.saveTask(task.copy(status = "COMPLETED"), expectedVersion = task.version)
            }
        }
    }

    // ---- Background fire / reconciliation -------------------------------------------------------

    private suspend fun fireRaw(instanceId: String): ReminderFireEvent? {
        val instance = instanceDao.instance(instanceId) ?: return null
        if (instance.state != REMINDER_STATE_SCHEDULED) return null
        val stamp = now()
        // A queued broadcast for a later reschedule must not fire early; a late (Doze) delivery is
        // still honoured because the user did schedule this instance.
        if (instance.fireAt > stamp + EARLY_TOLERANCE_MS) return null
        val reminder = coreDao.reminder(instance.reminderId) ?: return null
        if (!reminder.enabled) return null
        if (instance.reminderVersion != reminder.version) return null
        val owner = ownerState(reminder)
        if (!owner.active || instance.ownerType != owner.type || instance.ownerId != owner.id ||
            instance.ownerVersion != owner.version
        ) {
            return null
        }
        if (owner.type == "HABIT") {
            val deliver = com.timeagent.habits.HabitRepository(repository, repository.applicationContext)
                .shouldDeliverReminder(requireNotNull(owner.id), instance.fireAt)
            repository.applicationContext?.let { context ->
                repository.afterCommit { com.timeagent.habits.HabitReminderRefresh.request(context) }
            }
            if (!deliver) {
                instanceDao.put(instance.copy(state = REMINDER_STATE_CANCELLED, version = instance.version + 1))
                return null
            }
        }
        val fired = instance.copy(state = REMINDER_STATE_FIRED, version = instance.version + 1)
        instanceDao.put(fired)
        // Keep the legacy delivered marker for old UI/read fallbacks. Repeats never consult it.
        if (reminder.deliveredAt == null) {
            try {
                repository.markReminderDeliveredRaw(reminder.id, reminder.remindAt)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
        planCanonical(coreDao.reminder(reminder.id) ?: reminder, journal = false)
        return ReminderFireEvent(
            instanceId = fired.id,
            reminderId = reminder.id,
            title = reminder.title,
            fireAt = fired.fireAt,
            kind = fired.kind,
            repeatIndex = fired.repeatIndex,
        )
    }

    /**
     * Reconciles persisted instances against the current parents. This never calls saveReminderRaw
     * and never journals: a parent edit, completion, deletion, reboot or timezone change can cancel
     * or create instances without appearing in the user's undo stack.
     */
    private suspend fun reconcileRaw(): Int {
        var changed = 0
        val stamp = now()
        val reminders = coreDao.reminders().associateBy { it.id }

        instanceDao.all().filter { it.state == REMINDER_STATE_SCHEDULED }.forEach { instance ->
            val reminder = reminders[instance.reminderId]
            val owner = reminder?.let { ownerState(it) }
            val expectedFireAt = reminder?.let { expectedFireAt(it, instance) }
            val stale = reminder == null ||
                !reminder.enabled ||
                owner == null ||
                !owner.active ||
                instance.reminderVersion != reminder.version ||
                instance.ownerType != owner.type ||
                instance.ownerId != owner.id ||
                instance.ownerVersion != owner.version ||
                (instance.kind != REMINDER_INSTANCE_SNOOZE && expectedFireAt != instance.fireAt) ||
                instance.fireAt <= stamp - GRACE_MS
            if (stale) {
                val after = instance.copy(state = REMINDER_STATE_CANCELLED, version = instance.version + 1)
                instanceDao.put(after)
                changed++
            }
        }

        reminders.values.filter { it.enabled }.forEach { reminder ->
            changed += planCanonical(reminder, journal = false)
        }
        return changed
    }

    // ---- Planning --------------------------------------------------------------------------------

    private suspend fun planCanonical(reminder: ReminderEntity, journal: Boolean): Int {
        if (!reminder.enabled) return 0
        val owner = ownerState(reminder)
        if (!owner.active) return 0
        val versioned = instanceDao.forReminder(reminder.id).filter { it.reminderVersion == reminder.version }
        if (versioned.any { it.state == REMINDER_STATE_ACTED || it.state == REMINDER_STATE_DISMISSED }) return 0
        // A snooze (pending, fired, or even cancelled later) still expresses "remind me at this
        // other time", so it suppresses the original primary/repeat tail for this revision.
        // Editing the reminder bumps its version and gives the new revision a fresh primary.
        if (versioned.any { it.kind == REMINDER_INSTANCE_SNOOZE }) return 0
        val parentChanged = versioned.any { instance ->
            instance.state == REMINDER_STATE_CANCELLED && (
                instance.ownerVersion != owner.version ||
                    instance.ownerType != owner.type ||
                    instance.ownerId != owner.id
                )
        }
        if (parentChanged) return 0

        var changed = 0
        val stamp = now()
        val primaryId = primaryInstanceId(reminder)
        val existingPrimary = versioned.firstOrNull { it.id == primaryId }
        val primaryUsable = existingPrimary != null && existingPrimary.state != REMINDER_STATE_CANCELLED
        if (!primaryUsable && reminder.deliveredAt == null && reminder.remindAt > stamp - GRACE_MS) {
            val after = newInstance(
                reminder = reminder,
                owner = owner,
                id = primaryId,
                fireAt = reminder.remindAt,
                kind = REMINDER_INSTANCE_PRIMARY,
                repeatIndex = 0,
                state = REMINDER_STATE_SCHEDULED,
                version = (existingPrimary?.version ?: -1L) + 1,
            )
            put(
                after,
                existingPrimary,
                journal,
                if (existingPrimary == null) "创建提醒实例" else "恢复提醒实例",
            )
            changed++
        }

        val interval = reminder.repeatIntervalMinutes
        val max = reminder.repeatMaxCount ?: 0
        val lastFired = versioned.filter { it.state == REMINDER_STATE_FIRED }.maxOfOrNull { it.repeatIndex }
        if (interval != null && interval > 0 && max > 0 && lastFired != null && lastFired < max) {
            // Persist only the next repeat; a user-defined count never expands into a huge batch.
            val firstFuture = ((stamp - reminder.remindAt).coerceAtLeast(0) / (interval.toLong() * 60_000L) + 1)
            val index = maxOf(lastFired.toLong() + 1, firstFuture)
            if (index <= max.toLong()) {
                val repeatIndex = index.toInt()
                val fireAt = repeatFireAt(reminder, interval, repeatIndex)
                val id = repeatInstanceId(reminder, repeatIndex)
                val existing = versioned.firstOrNull { it.id == id }
                if (fireAt != null && (existing == null || existing.state == REMINDER_STATE_CANCELLED)) {
                    val after = newInstance(reminder, owner, id, fireAt, REMINDER_INSTANCE_REPEAT,
                        repeatIndex, REMINDER_STATE_SCHEDULED, (existing?.version ?: -1L) + 1)
                    put(after, existing, journal, "创建下一次重复提醒")
                    changed++
                }
            }
        }
        return changed
    }

    private suspend fun cancelScheduled(reminderId: String, journal: Boolean, action: String): Int {
        var changed = 0
        instanceDao.forReminder(reminderId).filter { it.state == REMINDER_STATE_SCHEDULED }.forEach { instance ->
            val after = instance.copy(state = REMINDER_STATE_CANCELLED, version = instance.version + 1)
            put(after, instance, journal, action)
            changed++
        }
        return changed
    }

    private suspend fun put(
        after: ReminderInstanceEntity,
        before: ReminderInstanceEntity?,
        journal: Boolean,
        action: String,
    ) {
        instanceDao.put(after)
        if (journal) repository.journal("REMINDER_INSTANCE", after.id, before, after, action)
    }

    private fun newInstance(
        reminder: ReminderEntity,
        owner: OwnerSnapshot,
        id: String,
        fireAt: Long,
        kind: String,
        repeatIndex: Int,
        state: String,
        version: Long,
    ) = ReminderInstanceEntity(
        id = id,
        reminderId = reminder.id,
        ownerType = owner.type,
        ownerId = owner.id,
        ownerVersion = owner.version,
        reminderVersion = reminder.version,
        fireAt = fireAt,
        kind = kind,
        repeatIndex = repeatIndex,
        state = state,
        requestCode = reminderInstanceRequestCode(id),
        version = version,
    )

    private fun primaryInstanceId(reminder: ReminderEntity) = "primary:${reminder.id}:${reminder.version}"

    private fun repeatInstanceId(reminder: ReminderEntity, index: Int) =
        "repeat:${reminder.id}:${reminder.version}:$index"

    private fun repeatFireAt(reminder: ReminderEntity, intervalMinutes: Int, index: Int): Long? {
        val offset = intervalMinutes.toLong() * 60_000L * index.toLong()
        return runCatching { Math.addExact(reminder.remindAt, offset) }.getOrNull()
    }

    private fun expectedFireAt(reminder: ReminderEntity, instance: ReminderInstanceEntity): Long? {
        return when (instance.kind) {
            REMINDER_INSTANCE_PRIMARY -> reminder.remindAt
            REMINDER_INSTANCE_REPEAT -> {
                val interval = reminder.repeatIntervalMinutes ?: return null
                val max = reminder.repeatMaxCount ?: 0
                if (max <= 0 || interval <= 0 || instance.repeatIndex !in 1..max) null
                else repeatFireAt(reminder, interval, instance.repeatIndex)
            }
            else -> instance.fireAt
        }
    }

    // ---- Parent snapshot -------------------------------------------------------------------------

    private fun normalize(item: ReminderEntity): ReminderEntity {
        val rawType = item.ownerType.trim().uppercase(Locale.ROOT)
        val inferredType = when {
            rawType.isNotBlank() && !(rawType == "REMINDER" && item.ownerId == null && item.taskId != null) -> rawType
            item.taskId != null -> "TASK"
            else -> "REMINDER"
        }
        require(inferredType in OWNER_TYPES) { "提醒来源类型无效" }
        val ownerId = when (inferredType) {
            "REMINDER" -> item.id
            "TASK" -> item.ownerId?.takeIf { it.isNotBlank() } ?: item.taskId
            else -> item.ownerId?.takeIf { it.isNotBlank() }
        }
        require(ownerId != null) { "请选择提醒关联的条目" }
        val interval = item.repeatIntervalMinutes?.takeIf { it > 0 }
        val max = (item.repeatMaxCount ?: 0).coerceAtLeast(0)
        require(item.repeatIntervalMinutes == null || item.repeatIntervalMinutes > 0) { "重复间隔须为正整数分钟" }
        require(item.repeatMaxCount == null || item.repeatMaxCount >= 0) { "重复次数不能为负数" }
        val repeats = interval != null && max > 0
        return item.copy(
            ownerType = inferredType,
            ownerId = ownerId,
            taskId = if (inferredType == "TASK") ownerId else item.taskId,
            repeatIntervalMinutes = if (repeats) interval else null,
            repeatMaxCount = if (repeats) max else null,
        )
    }

    private suspend fun ownerState(reminder: ReminderEntity): OwnerSnapshot {
        val type = reminder.ownerType.uppercase(Locale.ROOT)
        val id = reminder.ownerId?.takeIf { it.isNotBlank() }
            ?: reminder.taskId
            ?: if (type == "REMINDER") reminder.id else null
        fun missing() = OwnerSnapshot(type, id, 0, false)
        return when (type) {
            "REMINDER" -> OwnerSnapshot(type, reminder.id, reminder.version, true)
            "TASK" -> id?.let { coreDao.task(it) }?.let { task ->
                OwnerSnapshot(type, id, task.version, isTaskActive(task))
            } ?: missing()
            "PLAN", "SCHEDULE", "SCHEDULE_BLOCK" -> id?.let { coreDao.plan(it) }?.let { plan ->
                val linkedTask = plan.taskId?.let { coreDao.task(it) }
                val active = plan.deletedAt == null && plan.status == "SCHEDULED" &&
                    (linkedTask == null || isTaskActive(linkedTask))
                OwnerSnapshot(type, id, plan.version, active)
            } ?: missing()
            "OCCURRENCE" -> id?.let { coreDao.occurrence(it) }?.let { occurrence ->
                OwnerSnapshot(type, id, occurrence.version, occurrence.status != "CANCELLED")
            } ?: missing()
            "RECURRING_INSTANCE" -> id?.let { coreDao.recurringInstance(it) }?.let { instance ->
                val task = coreDao.task(instance.taskId)
                OwnerSnapshot(type, id, instance.version, instance.status == "PENDING" && task != null && isTaskActive(task))
            } ?: missing()
            "DEADLINE" -> {
                val task = id?.let { coreDao.task(it) }
                if (task != null) {
                    OwnerSnapshot(type, id, task.version, isTaskActive(task))
                } else {
                    id?.let { coreDao.recurringInstance(it) }?.let { instance ->
                        val linkedTask = coreDao.task(instance.taskId)
                        OwnerSnapshot(
                            type, id, instance.version,
                            instance.status == "PENDING" && linkedTask != null && isTaskActive(linkedTask),
                        )
                    } ?: missing()
                }
            }
            "HABIT" -> id?.let { repository.database.habitDao().habit(it) }?.let { habit ->
                val date = java.time.Instant.ofEpochMilli(reminder.remindAt).atZone(repository.zone).toLocalDate()
                OwnerSnapshot(type, id, habit.version, !habit.archived && habit.isActive(date) && !date.isBefore(habit.start))
            } ?: missing()
            "IDEA" -> id?.let { coreDao.idea(it) }?.let { idea ->
                OwnerSnapshot(type, id, idea.version, idea.deletedAt == null && idea.status != "ARCHIVED")
            } ?: missing()
            "ACTIVITY" -> id?.let { coreDao.activity(it) }?.let { activity ->
                OwnerSnapshot(type, id, activity.version, activity.archivedAt == null)
            } ?: missing()
            "PROJECT" -> id?.let { coreDao.project(it) }?.let { project ->
                OwnerSnapshot(type, id, project.version, project.deletedAt == null)
            } ?: missing()
            else -> OwnerSnapshot(type, id, 0, id != null)
        }
    }

    private fun isTaskActive(task: TaskEntity) =
        task.deletedAt == null && task.status != "COMPLETED" && task.status != "CANCELLED"

    private fun ownerError(reminder: ReminderEntity): String = when (reminder.ownerType) {
        "TASK" -> "关联任务不存在、已删除或已完成"
        "PLAN", "SCHEDULE", "SCHEDULE_BLOCK" -> "关联日程不存在或已取消"
        "RECURRING_INSTANCE", "DEADLINE" -> "关联的期限事项已处理"
        else -> "提醒关联的条目已不存在"
    }

    private fun requireVersion(reminder: ReminderEntity, expectedVersion: Long) {
        if (reminder.version != expectedVersion) throw StaleChange()
    }

    private suspend fun apply(command: WriteReminder): com.timeagent.domain.command.CommandResult {
        val result = repository.commands.executeForRepository(command)
        result.throwIfRejected()
        return result
    }

    private fun now(): Long = repository.now().toEpochMilli()
}
