package com.timeagent.backup

/** Public backups omit the sample week; private rollback and migration snapshots stay complete. */
internal fun BackupDatabase.withoutSamples(): BackupDatabase {
    val markers = tables["sample_records"] ?: return this
    fun List<BackupCell>.value(table: BackupTable, column: String): String? =
        table.columns.indexOf(column).takeIf { it >= 0 }?.let { this[it].value }
    val byType = markers.rows.groupBy { it.value(markers, "entityType") }.mapValues { (_, rows) ->
        rows.mapNotNull { it.value(markers, "entityId") }.toSet()
    }
    val allIds = byType.values.flatten().toSet()
    if (allIds.isEmpty()) return this
    val mainTypes = mapOf("tasks" to "TASK", "schedule_blocks" to "PLAN", "time_records" to "RECORD", "projects" to "PROJECT")
    val reminders = tables["reminders"]
    val removedReminderIds = reminders?.rows.orEmpty().filter { row ->
        row.value(requireNotNull(reminders), "taskId") in byType["TASK"].orEmpty() ||
            row.value(reminders, "ownerId") in allIds
    }.mapNotNull { it.value(requireNotNull(reminders), "id") }.toSet()
    val filtered = tables.mapValues { (name, table) ->
        table.copy(rows = table.rows.filterNot { row ->
            when (name) {
                "sample_records" -> true
                in mainTypes -> row.value(table, "id") in byType[mainTypes[name]].orEmpty()
                "task_placements", "task_tags" -> row.value(table, "taskId") in byType["TASK"].orEmpty()
                "recurring_instances" -> row.value(table, "taskId") in byType["TASK"].orEmpty()
                "record_tags" -> row.value(table, "recordId") in byType["RECORD"].orEmpty()
                "weekly_goals" -> row.value(table, "projectId") in byType["PROJECT"].orEmpty()
                "reminders" -> row.value(table, "id") in removedReminderIds
                "reminder_instances" -> row.value(table, "reminderId") in removedReminderIds
                "calendar_links" -> row.value(table, "localEntityId") in byType["PLAN"].orEmpty()
                "entry_contexts" -> row.value(table, "entityId") in allIds
                "entity_revisions" -> allIds.any { row.value(table, "key").orEmpty().contains(it) }
                "notes" -> row.value(table, "targetId") in allIds
                "operation_history" -> {
                    row.value(table, "entityId") in allIds || listOf("beforeSnapshot", "afterSnapshot").any { column ->
                        val snapshot = row.value(table, column).orEmpty()
                        allIds.any { id -> snapshot.contains(id) }
                    }
                }
                "command_log" -> allIds.any { row.value(table, "resultJson").orEmpty().contains(it) }
                else -> false
            }
        }.map { row ->
            // A user's own entry survives; only links to excluded sample parents are detached.
            row.mapIndexed { index, cell ->
                val column = table.columns[index]
                val omitted = when (column) {
                    "taskId", "parentTaskId" -> byType["TASK"].orEmpty()
                    "projectId" -> byType["PROJECT"].orEmpty()
                    "scheduleBlockId", "planId" -> byType["PLAN"].orEmpty()
                    "recordId", "actualRecordId" -> byType["RECORD"].orEmpty()
                    else -> emptySet()
                }
                if (cell.value in omitted) BackupCell(android.database.Cursor.FIELD_TYPE_NULL) else cell
            }
        })
    }
    return copy(tables = filtered)
}
