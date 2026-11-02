package com.timeagent.data

import com.timeagent.ledger.*
import com.timeagent.organization.OrganizationDao
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
interface TimeDao {
    @Query("SELECT * FROM attachments ORDER BY createdAt DESC") suspend fun attachments():List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE id=:id") suspend fun attachment(id:String):AttachmentEntity?
    @Upsert suspend fun putAttachment(item:AttachmentEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM time_records WHERE taskId=:id)") suspend fun hasRecordsForTask(id:String):Boolean
    @Query("SELECT * FROM recurring_rules ORDER BY createdAt") suspend fun recurringRules():List<RecurringRuleEntity>
    @Query("SELECT * FROM recurring_rules WHERE id=:id") suspend fun recurringRule(id:String):RecurringRuleEntity?
    @Upsert suspend fun putRecurringRule(item:RecurringRuleEntity)
    @Query("DELETE FROM recurring_rules WHERE id=:id") suspend fun removeRecurringRule(id:String)
    @Query("SELECT * FROM recurring_instances ORDER BY originalDate, ordinal") suspend fun recurringInstances():List<RecurringInstanceEntity>
    @Query("SELECT * FROM recurring_instances WHERE id=:id") suspend fun recurringInstance(id:String):RecurringInstanceEntity?
    @Upsert suspend fun putRecurringInstance(item:RecurringInstanceEntity)
    @Query("DELETE FROM recurring_instances WHERE id=:id") suspend fun removeRecurringInstance(id:String)
    @Query("SELECT * FROM projects ORDER BY dueAt, name") suspend fun projects(): List<ProjectEntity>
    @Query("SELECT * FROM projects WHERE id=:id") suspend fun project(id: String): ProjectEntity?
    @Upsert suspend fun putProject(item: ProjectEntity)
    @Query("DELETE FROM projects WHERE id=:id") suspend fun removeProject(id: String)
    @Query("SELECT * FROM task_placements") suspend fun placements(): List<TaskPlacementEntity>
    @Query("SELECT * FROM task_placements WHERE taskId=:id") suspend fun placement(id: String): TaskPlacementEntity?
    @Upsert suspend fun putPlacement(item: TaskPlacementEntity)
    @Query("DELETE FROM task_placements WHERE taskId=:id") suspend fun removePlacement(id: String)
    @Query("SELECT * FROM weekly_goals ORDER BY weekStart DESC") suspend fun weeklyGoals(): List<WeeklyGoalEntity>
    @Query("SELECT * FROM weekly_goals WHERE id=:id") suspend fun weeklyGoal(id: String): WeeklyGoalEntity?
    @Upsert suspend fun putWeeklyGoal(item: WeeklyGoalEntity)
    @Query("DELETE FROM weekly_goals WHERE id=:id") suspend fun removeWeeklyGoal(id: String)
    @Query("SELECT * FROM personal_memories ORDER BY updatedAt DESC") suspend fun memories(): List<PersonalMemoryEntity>
    @Query("SELECT * FROM personal_memories WHERE id=:id") suspend fun memory(id: String): PersonalMemoryEntity?
    @Upsert suspend fun putMemory(item: PersonalMemoryEntity)
    @Query("DELETE FROM personal_memories WHERE id=:id") suspend fun removeMemory(id: String)
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC") suspend fun tasks(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun task(id: String): TaskEntity?
    @Query("SELECT * FROM schedule_blocks ORDER BY startAt") suspend fun plans(): List<ScheduleBlockEntity>
    @Query("SELECT * FROM schedule_blocks WHERE id=:id") suspend fun plan(id: String): ScheduleBlockEntity?
    @Query("SELECT schedule_blocks.* FROM schedule_blocks LEFT JOIN tasks ON taskId=tasks.id WHERE schedule_blocks.deletedAt IS NULL AND (schedule_blocks.taskId IS NULL OR (tasks.deletedAt IS NULL AND tasks.status != 'CANCELLED')) AND schedule_blocks.status='SCHEDULED' ORDER BY startAt") suspend fun activePlans(): List<ScheduleBlockEntity>
    @Query("SELECT * FROM command_log WHERE requestId=:requestId") suspend fun commandResult(requestId: String): CommandLogEntity?
    @Insert suspend fun putCommandResult(item: CommandLogEntity)
    @Query("DELETE FROM command_log WHERE createdAt < :cutoff") suspend fun deleteCommandResultsBefore(cutoff: Long)
    @Query("SELECT * FROM time_records ORDER BY startAt DESC") suspend fun records(): List<TimeRecordEntity>
    @Query("SELECT * FROM time_records WHERE id=:id") suspend fun record(id: String): TimeRecordEntity?
    @Query("SELECT * FROM time_records WHERE endAt IS NULL LIMIT 1") suspend fun runningTimer(): TimeRecordEntity?
    @Query("SELECT * FROM courses") suspend fun courses(): List<CourseEntity>
    @Query("SELECT * FROM courses WHERE id=:id") suspend fun course(id: String): CourseEntity?
    @Query("SELECT * FROM course_occurrences ORDER BY startAt") suspend fun occurrences(): List<CourseOccurrenceEntity>
    @Query("SELECT * FROM course_occurrences WHERE id=:id") suspend fun occurrence(id: String): CourseOccurrenceEntity?
    @Query("SELECT * FROM academic_terms ORDER BY firstWeekStart DESC") suspend fun terms(): List<AcademicTermEntity>
    @Query("SELECT * FROM categories ORDER BY sortOrder") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM activity_templates ORDER BY sortOrder, name") suspend fun activities(): List<ActivityTemplateEntity>
    @Query("SELECT * FROM activity_templates WHERE id=:id") suspend fun activity(id: String): ActivityTemplateEntity?
    @Query("SELECT * FROM reminders ORDER BY remindAt") suspend fun reminders(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE id=:id") suspend fun reminder(id: String): ReminderEntity?
    @Upsert suspend fun putReminder(item: ReminderEntity)
    @Query("DELETE FROM reminders WHERE id=:id") suspend fun removeReminder(id: String)
    @Query("SELECT * FROM ideas ORDER BY createdAt DESC") suspend fun ideas(): List<IdeaEntity>
    @Query("SELECT * FROM ideas WHERE id=:id") suspend fun idea(id: String): IdeaEntity?
    @Upsert suspend fun putIdea(item: IdeaEntity)
    @Query("DELETE FROM ideas WHERE id=:id") suspend fun removeIdea(id: String)
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") suspend fun notes(): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE id=:id") suspend fun note(id: String): NoteEntity?
    @Upsert suspend fun putNote(item: NoteEntity)
    @Query("DELETE FROM notes WHERE id=:id") suspend fun removeNote(id: String)
    @Upsert suspend fun putActivity(item: ActivityTemplateEntity)
    @Query("DELETE FROM activity_templates WHERE id=:id") suspend fun removeActivity(id: String)
    @Query("SELECT * FROM operation_history ORDER BY id DESC LIMIT 80") suspend fun history(): List<OperationEntity>
    @Query("SELECT * FROM operation_history WHERE undone=0 ORDER BY id DESC LIMIT 1") suspend fun lastAction(): OperationEntity?
    @Query("SELECT * FROM operation_history WHERE entityType=:type AND entityId=:id LIMIT 1") suspend fun actionFor(type: String, id: String): OperationEntity?
    @Query("SELECT * FROM operation_history WHERE id=:id") suspend fun action(id: Long): OperationEntity?
    @Query("SELECT * FROM import_batches ORDER BY completedAt DESC") suspend fun importBatches(): List<ImportBatchEntity>
    @Query("SELECT * FROM import_batches WHERE id=:id") suspend fun importBatch(id: String): ImportBatchEntity?
    @Query("SELECT * FROM entity_revisions WHERE `key`=:key") suspend fun revision(key: String): EntityRevision?
    @Upsert suspend fun putRevision(item: EntityRevision)
    @Upsert suspend fun putTask(item: TaskEntity)
    @Upsert suspend fun putPlan(item: ScheduleBlockEntity)
    @Upsert suspend fun putRecord(item: TimeRecordEntity)
    @Upsert suspend fun putCourse(item: CourseEntity)
    @Upsert suspend fun putOccurrence(item: CourseOccurrenceEntity)
    @Upsert suspend fun putTerm(item: AcademicTermEntity)
    @Upsert suspend fun putCategory(item: CategoryEntity)
    @Upsert suspend fun putBatch(item: ImportBatchEntity)
    @Insert suspend fun putAction(item: OperationEntity)
    @Query("UPDATE operation_history SET undone=1, redoable=1 WHERE id=:id") suspend fun markUndone(id: Long)
    @Query("SELECT * FROM operation_history WHERE undone=1 AND redoable=1 ORDER BY id ASC LIMIT 1") suspend fun redoAction(): OperationEntity?
    @Query("UPDATE operation_history SET undone=0, redoable=0 WHERE id=:id") suspend fun markRedone(id: Long)
    @Query("UPDATE operation_history SET redoable=0 WHERE redoable=1") suspend fun clearRedo()
    @Query("DELETE FROM tasks WHERE id=:id") suspend fun removeTask(id: String)
    @Query("DELETE FROM schedule_blocks WHERE id=:id") suspend fun removePlan(id: String)
    @Query("DELETE FROM time_records WHERE id=:id") suspend fun removeRecord(id: String)
    @Query("DELETE FROM course_occurrences WHERE id=:id") suspend fun removeOccurrence(id: String)
    @Query("SELECT * FROM health_sync_items ORDER BY startAt DESC LIMIT 200") suspend fun healthItems(): List<HealthSyncItemEntity>
    @Query("SELECT * FROM health_sync_items WHERE id=:id") suspend fun healthItem(id: String): HealthSyncItemEntity?
    @Upsert suspend fun putHealthItem(item: HealthSyncItemEntity)
    @Query("DELETE FROM health_sync_items WHERE id=:id") suspend fun removeHealthItem(id: String)
    @Query("SELECT * FROM agent_suggestions WHERE status='PENDING' ORDER BY createdAt DESC LIMIT 50") suspend fun suggestions(): List<AgentSuggestionEntity>
    @Query("SELECT * FROM agent_suggestions") suspend fun allSuggestions(): List<AgentSuggestionEntity>
    @Query("SELECT * FROM agent_suggestions WHERE id=:id") suspend fun suggestion(id: String): AgentSuggestionEntity?
    @Query("SELECT * FROM agent_suggestions WHERE dedupKey=:key LIMIT 1") suspend fun suggestionByDedup(key: String): AgentSuggestionEntity?
    @Upsert suspend fun putSuggestion(item: AgentSuggestionEntity)
    @Query("UPDATE agent_suggestions SET status=:status, decidedAt=:decidedAt WHERE id=:id") suspend fun updateSuggestionStatus(id: String, status: String, decidedAt: Long?)
}

@Database(entities = [TaskEntity::class, ScheduleBlockEntity::class, CommandLogEntity::class, TimeRecordEntity::class, CategoryEntity::class, TagEntity::class, TaskTagEntity::class, RecordTagEntity::class, AcademicTermEntity::class, CourseEntity::class, CourseOccurrenceEntity::class, OperationEntity::class, ImportBatchEntity::class, EntityRevision::class, ActivityTemplateEntity::class, ReminderEntity::class, IdeaEntity::class, NoteEntity::class, HealthSyncItemEntity::class, AgentSuggestionEntity::class, PersonalMemoryEntity::class, ProjectEntity::class, TaskPlacementEntity::class, WeeklyGoalEntity::class, RecurringRuleEntity::class, RecurringInstanceEntity::class, AttachmentEntity::class, LedgerAccountEntity::class, LedgerCategoryEntity::class, PaymentCaptureEntity::class, LedgerTransactionEntity::class], version = 13, exportSchema = true)
abstract class TimeAgentDatabase : RoomDatabase() {
    abstract fun dao(): TimeDao
    abstract fun organizationDao(): OrganizationDao
    abstract fun ledgerDao(): LedgerDao

    override fun close() = synchronized(Companion) {
        super.close()
        if (instance === this) instance = null
    }

    companion object {
        @Volatile private var instance: TimeAgentDatabase? = null
        val MIGRATION_10_11 = object : Migration(10,11) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `attachments` (`id` TEXT NOT NULL, `storageKey` TEXT NOT NULL, `originalName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `ocrText` TEXT NOT NULL, `taskId` TEXT, `projectId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, `deletedAt` INTEGER, `purgedAt` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_taskId` ON `attachments` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attachments_projectId` ON `attachments` (`projectId`)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9,10) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_rules` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `patternJson` TEXT NOT NULL, `domain` TEXT NOT NULL, `category` TEXT NOT NULL, `reference` TEXT NOT NULL, `projectId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_rules_projectId` ON `recurring_rules` (`projectId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_instances` (`id` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `originalDate` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `scheduledAt` INTEGER, `status` TEXT NOT NULL, `manualOverride` INTEGER NOT NULL, `ruleSnapshot` TEXT NOT NULL, `ruleVersion` INTEGER NOT NULL, `taskVersion` INTEGER NOT NULL, `planVersion` INTEGER, `placementVersion` INTEGER, `scheduleNote` TEXT NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`ruleId`) REFERENCES `recurring_rules`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_instances_ruleId` ON `recurring_instances` (`ruleId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_instances_taskId` ON `recurring_instances` (`taskId`)")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `dueAt` INTEGER, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_placements` (`taskId` TEXT NOT NULL, `projectId` TEXT, `parentTaskId` TEXT, `version` INTEGER NOT NULL, PRIMARY KEY(`taskId`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`parentTaskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_placements_projectId` ON `task_placements` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_placements_parentTaskId` ON `task_placements` (`parentTaskId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `weekly_goals` (`id` TEXT NOT NULL, `projectId` TEXT, `activityId` TEXT, `weekStart` TEXT NOT NULL, `targetMinutes` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`activityId`) REFERENCES `activity_templates`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                listOf("projectId", "activityId", "weekStart").forEach { db.execSQL("CREATE INDEX IF NOT EXISTS `index_weekly_goals_${it}` ON `weekly_goals` (`$it`)") }
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `personal_memories` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceText` TEXT NOT NULL, `sourceId` TEXT, `enabled` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL, `validFrom` INTEGER, `validUntil` INTEGER, `ruleType` TEXT NOT NULL, `keyword` TEXT NOT NULL, `weekday` INTEGER, `startMinute` INTEGER, `endMinute` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `activity_templates` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` TEXT, `defaultMinutes` INTEGER NOT NULL, `categoryId` TEXT, `sortOrder` INTEGER NOT NULL, `archivedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`parentId`) REFERENCES `activity_templates`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_templates_parentId` ON `activity_templates` (`parentId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_templates_categoryId` ON `activity_templates` (`categoryId`)")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `activityId` TEXT REFERENCES `activity_templates`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_activityId` ON `tasks` (`activityId`)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `operation_history` ADD COLUMN `redoable` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `remindAt` INTEGER NOT NULL, `taskId` TEXT, `enabled` INTEGER NOT NULL, `deliveredAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_taskId` ON `reminders` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_remindAt` ON `reminders` (`remindAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ideas` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetId` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_targetType_targetId` ON `notes` (`targetType`, `targetId`)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `time_records` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `health_sync_items` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL, `originPackage` TEXT NOT NULL, `recordId` TEXT, `userDeleted` INTEGER NOT NULL, `lastSyncedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`recordId`) REFERENCES `time_records`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_sync_items_recordId` ON `health_sync_items` (`recordId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_sync_items_startAt` ON `health_sync_items` (`startAt`)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `agent_suggestions` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT NOT NULL, `detail` TEXT NOT NULL, `dedupKey` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `decidedAt` INTEGER, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_suggestions_dedupKey` ON `agent_suggestions` (`dedupKey`)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("taskId TEXT", "taskVersion INTEGER", "proposedStartAt INTEGER", "proposedEndAt INTEGER", "forDate TEXT", "expiresAt INTEGER", "snoozedUntil INTEGER", "version INTEGER NOT NULL DEFAULT 0").forEach {
                    db.execSQL("ALTER TABLE agent_suggestions ADD COLUMN $it")
                }
                // Archive prose-only cards; the engine issues new keys containing object revisions.
                db.execSQL("UPDATE agent_suggestions SET status='EXPIRED' WHERE status='PENDING'")
            }
        }
        fun open(context: Context): TimeAgentDatabase {
            com.timeagent.backup.PersonalBackup.recoverIfNeeded(context)
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, TimeAgentDatabase::class.java, "time-agent.db")
                    // Never use fallbackToDestructiveMigration: future versions must supply explicit migrations.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, LedgerSchema.MIGRATION_11_12, ScheduleMigration.withBackup(context.applicationContext))
                    .enableMultiInstanceInvalidation()
                    .apply { singleThreadedTransactions() }
                    .build().also { instance = it }
            }
        }

        /** In-memory variant for tests; same serialization guarantees as [open]. */
        fun openInMemory(context: Context): TimeAgentDatabase = Room.inMemoryDatabaseBuilder(context.applicationContext, TimeAgentDatabase::class.java)
            .allowMainThreadQueries()
            .apply { singleThreadedTransactions() }
            .build()

        /**
         * Coordinator refreshes and user edits run as concurrent suspending transactions; on a
         * pooled executor their begin/end pairs can interleave on one SQLite connection. Pinning
         * both executors to a single thread serializes every transaction, in tests and production.
         */
        private fun RoomDatabase.Builder<TimeAgentDatabase>.singleThreadedTransactions(): RoomDatabase.Builder<TimeAgentDatabase> {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            setQueryExecutor(executor)
            setTransactionExecutor(executor)
            return this
        }
    }
}
