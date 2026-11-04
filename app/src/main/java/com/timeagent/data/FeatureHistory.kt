package com.timeagent.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.timeagent.habits.HabitCheckinEntity
import com.timeagent.habits.HabitEntity
import com.timeagent.onboarding.SampleRecordEntity
import com.timeagent.quickentry.PlanTemplateEntity
import com.timeagent.quickentry.PlanTemplateItemEntity
import com.timeagent.quickentry.QuickEntryEntity

/** Restores feature rows inside the executor's existing history transaction. */
internal suspend fun TimeRepository.restoreFeatureHistory(action: OperationEntity, forward: Boolean): Boolean {
    val types = setOf("QUICK_ENTRY", "PLAN_TEMPLATE", "PLAN_TEMPLATE_ITEM", "HABIT", "HABIT_CHECKIN", "SAMPLE_MARKER", "ENTRY_CONTEXT",
        "CALENDAR_STATE", "CALENDAR_SETTINGS", "CALENDAR_LINK")
    if (action.entityType !in types) return false
    val gson = Gson()
    val target = if (forward) action.afterSnapshot else action.beforeSnapshot
    val expected = if (forward) action.beforeSnapshot else action.afterSnapshot
    fun payload(raw: String?) = raw?.let {
        JsonParser.parseString(it).asJsonObject.apply {
            remove("version"); remove("updatedAt")
            if (action.entityType == "QUICK_ENTRY") { remove("usageCount"); remove("lastUsedAt") }
            if (action.entityType == "CALENDAR_STATE") { remove("lastFullScanAt"); remove("lastSyncAt"); remove("lastError") }
            if (action.entityType == "CALENDAR_SETTINGS" && !has("pastDays")) addProperty("pastDays", 30)
        }
    }
    fun check(current: Any?) {
        if (payload(current?.let(gson::toJson)) != payload(expected)) throw StaleChange()
    }
    val stamp = now().toEpochMilli()
    val quick = database.quickEntryDao()
    val habits = database.habitDao()
    when (action.entityType) {
        "CALENDAR_STATE" -> {
            val calendars = database.calendarSyncDao()
            val id = action.entityId.toLong()
            val current = calendars.state(id); check(current)
            if (target == null) calendars.removeState(id)
            else calendars.putState(gson.fromJson(target, com.timeagent.calendar.CalendarSyncStateEntity::class.java)
                .copy(version = nextVersion(action.entityType, action.entityId, current?.version),
                    lastFullScanAt = current?.lastFullScanAt, lastSyncAt = current?.lastSyncAt, lastError = current?.lastError))
        }
        "CALENDAR_LINK" -> {
            val calendars = database.calendarSyncDao()
            check(calendars.link(action.entityId))
            if (target == null) calendars.removeLink(action.entityId)
            else calendars.putLink(gson.fromJson(target, com.timeagent.calendar.CalendarLinkEntity::class.java))
        }
        "CALENDAR_SETTINGS" -> {
            val context = requireNotNull(applicationContext)
            val settings = com.timeagent.calendar.CalendarSyncRepository(this, context)
            check(com.timeagent.calendar.CalendarSettings(settings.exportCalendarId(), settings.rangeDays(), settings.actualCalendarId(), settings.pastDays()))
            val restored = gson.fromJson(payload(target), com.timeagent.calendar.CalendarSettings::class.java)
            val version = (database.dao().revision("CALENDAR_SETTINGS")?.revision ?: 0L) + 1
            database.dao().putRevision(EntityRevision("CALENDAR_SETTINGS", version))
            afterCommit { com.timeagent.calendar.applySettings(context, restored); com.timeagent.calendar.CalendarSyncWorker.request(context) }
        }
        "QUICK_ENTRY" -> {
            val current = quick.entry(action.entityId); check(current)
            val version = nextVersion(action.entityType, action.entityId, current?.version)
            if (target == null) quick.removeEntry(action.entityId)
            else {
                val restored = gson.fromJson(target, QuickEntryEntity::class.java)
                quick.putEntry(restored.copy(version = version, updatedAt = stamp,
                    usageCount = current?.usageCount ?: restored.usageCount, lastUsedAt = current?.lastUsedAt ?: restored.lastUsedAt))
            }
        }
        "ENTRY_CONTEXT" -> {
            val identity = gson.fromJson(target ?: expected, com.timeagent.quickentry.EntryContextEntity::class.java)
            val contexts = database.entryContextDao()
            check(contexts.find(identity.entityType, identity.entityId))
            if (target == null) contexts.remove(identity.entityType, identity.entityId)
            else contexts.put(gson.fromJson(target, com.timeagent.quickentry.EntryContextEntity::class.java))
        }
        "PLAN_TEMPLATE" -> {
            val current = quick.template(action.entityId); check(current)
            val version = nextVersion(action.entityType, action.entityId, current?.version)
            if (target == null) quick.removeTemplate(action.entityId)
            else quick.putTemplate(gson.fromJson(target, PlanTemplateEntity::class.java).copy(version = version, updatedAt = stamp))
        }
        "PLAN_TEMPLATE_ITEM" -> {
            val current = quick.item(action.entityId); check(current)
            nextVersion(action.entityType, action.entityId)
            if (target == null) quick.removeItem(action.entityId)
            else quick.putItem(gson.fromJson(target, PlanTemplateItemEntity::class.java))
        }
        "HABIT" -> {
            val current = habits.habit(action.entityId); check(current)
            val version = nextVersion(action.entityType, action.entityId, current?.version)
            if (target == null) habits.removeHabit(action.entityId)
            else habits.putHabit(gson.fromJson(target, HabitEntity::class.java).copy(version = version, updatedAt = stamp))
        }
        "HABIT_CHECKIN" -> {
            val identity = gson.fromJson(target ?: expected, HabitCheckinEntity::class.java)
            val current = habits.checkin(identity.habitId, identity.date); check(current)
            val version = nextVersion(action.entityType, action.entityId, current?.version)
            if (target == null) habits.removeCheckin(identity.habitId, identity.date)
            else habits.putCheckin(gson.fromJson(target, HabitCheckinEntity::class.java).copy(version = version, updatedAt = stamp))
        }
        "SAMPLE_MARKER" -> {
            val identity = gson.fromJson(target ?: expected, SampleRecordEntity::class.java)
            val samples = database.sampleDataDao()
            check(samples.find(identity.entityType, identity.entityId))
            if (target == null) samples.remove(identity.entityType, identity.entityId)
            else samples.put(gson.fromJson(target, SampleRecordEntity::class.java))
        }
    }
    return true
}
