package com.timeagent.recurring

import com.google.gson.Gson
import com.timeagent.core.*
import com.timeagent.data.*
import com.timeagent.domain.command.throwIfRejected
import com.timeagent.memory.PersonalMemoryRules
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecurringService(private val repository: TimeRepository) {

    private val gson = Gson()

    private fun dao() = repository.database.dao()
    private fun span(start:Long,end:Long)=TimeSpan(Instant.ofEpochMilli(start),Instant.ofEpochMilli(end))
    private var checkedMove:RecurringMovePreview?=null
    private fun today(): LocalDate = repository.now().atZone(repository.zone).toLocalDate()

    internal suspend fun adjustTimelineRaw(command: com.timeagent.domain.command.AdjustRecurringTimeline): List<Conflict> {
        val instance = dao().recurringInstance(command.instanceId) ?: throw StaleChange()
        val before = dao().plan(command.plan.id) ?: throw StaleChange()
        val task = dao().task(instance.taskId) ?: throw StaleChange()
        val rule = dao().recurringRule(instance.ruleId) ?: throw StaleChange()
        if (before.id != "recurring-plan:${instance.id}" || before.version != command.expectedPlanVersion ||
            instance.version != command.expectedInstanceVersion || task.version != command.expectedTaskVersion ||
            rule.version != command.expectedRuleVersion) throw StaleChange()
        val from = LocalDate.parse(instance.originalDate)
        if (command.includingFuture && (command.preview == null || previewRuleChange(rule.id, from) != command.preview))
            throw StaleChange()
        val pattern = RecurringValidation.pattern(rule)
        val zone = ZoneId.of(pattern.zoneId)
        val start = Instant.ofEpochMilli(before.startAt).atZone(zone)
        val target = Instant.ofEpochMilli(command.plan.startAt).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), target.toLocalDate())
        val minutes = target.hour * 60 + target.minute - (start.hour * 60 + start.minute)
        val duration = if (before.kind == "POINT") pattern.durationMinutes
            else ((command.plan.endAt - command.plan.startAt) / 60_000).toInt()
        fun shifted(stamp: Long): Long = Instant.ofEpochMilli(stamp).atZone(zone)
            .plusDays(days).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
        val protected = if (command.includingFuture) dao().recurringInstances().filter {
            it.ruleId == rule.id && appliesFrom(it, from) && (it.id == instance.id || !untouched(it))
        } else emptyList()
        val due = if (task.dueAt == before.endAt) command.plan.endAt else task.dueAt?.let(::shifted)
        val savedTask = repository.saveTask(task.copy(dueAt = due,
            estimatedMinutes = if (command.includingFuture) duration else task.estimatedMinutes), task.version)
        repository.savePlanRaw(command.plan, before.version, savedTask.version)
        val sourcePattern = RecurringValidation.pattern(gson.fromJson(instance.ruleSnapshot, RecurringRuleEntity::class.java))
        resyncReminder(instance.id, task.id, task.title,
            Slot(due ?: command.plan.startAt, command.plan.startAt, command.plan.startAt), sourcePattern)
        repository.saveRecurringInstance(instance.copy(scheduledAt = command.plan.startAt, manualOverride = true,
            taskVersion = savedTask.version, planVersion = dao().plan(before.id)?.version,
            scheduleNote = if (command.includingFuture) "此次及以后调整；保留原始规则依据" else "仅此次调整"), instance.version)
        if (command.includingFuture) {
            // A previous "only this occurrence" move must not become the rule's date anchor.
            val weeklyCount = pattern.frequency == "WEEKLY_COUNT"
            val ruleDays = if (weeklyCount) 0L else java.time.temporal.ChronoUnit.DAYS.between(from, target.toLocalDate())
            val effectiveFrom = if (weeklyCount) from else target.toLocalDate()
            val changed = pattern.copy(
                // Keep 31st/leap-day anchors when a clipped instance changes only its time.
                startDate = if (ruleDays == 0L) pattern.startDate else target.toLocalDate().toString(),
                weekdays = pattern.weekdays.map { Math.floorMod(it - 1 + ruleDays.toInt(), 7) + 1 },
                minuteOfDay = if (weeklyCount) null else target.hour * 60 + target.minute, durationMinutes = duration,
                untilDate = pattern.untilDate?.let { LocalDate.parse(it).plusDays(ruleDays).toString() },
                effectiveFrom = effectiveFrom.toString())
            // The selected identity remains an exception; later untouched seeds stay automatic.
            saveRuleRaw(rule.copy(patternJson = gson.toJson(changed)), rule.version, from,
                RuleChangeScope.INCLUDING_PENDING, null, effectiveFrom)
            for (kept in protected) {
                val shiftedDate = LocalDate.parse(kept.originalDate).plusDays(ruleDays)
                val seeds = RecurringRules.expand(changed.copy(paused = false), shiftedDate, shiftedDate)
                for (seed in seeds.filter { it.ordinal == kept.ordinal }) {
                    val duplicate = dao().recurringInstance(seed.key) ?: continue
                    if (duplicate.id == kept.id || protected.any { it.id == duplicate.id } || !untouched(duplicate)) continue
                    retire(duplicate, "SKIPPED", "保留已调整或手工修改的原实例，不另建重复日程")
                    val retired = requireNotNull(dao().recurringInstance(duplicate.id))
                    repository.saveRecurringInstance(retired.copy(manualOverride = true), retired.version)
                }
            }
        }
        return if (command.plan.kind == "POINT") emptyList()
            else repository.conflicts(span(command.plan.startAt, command.plan.endAt), command.plan.id)
    }

    suspend fun saveRule(item: RecurringRuleEntity, expectedVersion: Long?, from: LocalDate, requestId: String, scope: RuleChangeScope = RuleChangeScope.INCLUDING_PENDING, preview: RuleChangePreview? = null) {
        repository.commands.executeForRepository(com.timeagent.domain.command.SaveRecurringRule(requestId, item, expectedVersion, from, scope, preview)).throwIfRejected()
    }

    internal suspend fun saveRuleRaw(item: RecurringRuleEntity, expectedVersion: Long?, from: LocalDate, scope: RuleChangeScope, preview: RuleChangePreview?,
        effectiveFromOverride: LocalDate? = null) {
        repository.transaction {
            if (preview != null && previewRuleChange(item.id, from) != preview) throw StaleChange()
            val effectiveFrom = effectiveFromOverride ?: if (scope == RuleChangeScope.FUTURE_ONLY) firstUnmaterializedDate(item.id, from) else from
            val pattern=RecurringValidation.pattern(item).copy(effectiveFrom=effectiveFrom.toString())
            val saved=repository.saveRecurringRule(item.copy(patternJson=gson.toJson(pattern)),expectedVersion)
            // FUTURE_ONLY 不改写任何已落地实例、任务、计划、提醒及其快照；只有尚未生成的 seed 使用新规则
            if(scope==RuleChangeScope.INCLUDING_PENDING) {
                val snapshot=gson.toJson(saved)
                for(instance in dao().recurringInstances().filter{it.ruleId==saved.id && appliesFrom(it,from)}) {
                    if(instance.manualOverride)continue
                    if(!untouched(instance))continue
                    val date=LocalDate.parse(instance.originalDate)
                    val end=if(instance.id.contains(":w:"))date.plusDays(6) else date
                    val seed=RecurringRules.expand(pattern.copy(paused=false),date,end).find{it.key==instance.id}
                    when {
                        pattern.paused || saved.deletedAt!=null -> retire(instance,"SUSPENDED","规则暂停；已有历史保留")
                        seed==null -> retire(instance,"SUPERSEDED","后续规则已替换；原始依据保留")
                        else -> update(saved,pattern,seed,instance,snapshot)
                    }
                }
            }
            if(saved.deletedAt==null)materialize(saved,pattern)
        }
    }

    suspend fun previewRuleChange(ruleId: String, from: LocalDate): RuleChangePreview = repository.transaction {
        var editable=0
        var locked=0
        val versions = mutableListOf<List<Any?>>()
        for(instance in dao().recurringInstances()) {
            if(instance.ruleId!=ruleId || !appliesFrom(instance,from))continue
            val eligible = untouched(instance)
            if(eligible)editable++ else locked++
            versions += listOf(instance.id, instance.version, eligible, dao().task(instance.taskId)?.version,
                dao().plan("recurring-plan:${instance.id}")?.version, dao().placement(instance.taskId)?.version)
        }
        RuleChangePreview(editable,locked,firstUnmaterializedDate(ruleId, from),
            gson.toJson(versions.sortedBy { it.first().toString() }))
    }
    private suspend fun firstUnmaterializedDate(ruleId: String, from: LocalDate): LocalDate =
        maxOf(from, dao().recurringInstances().filter { it.ruleId == ruleId }.maxOfOrNull {
            LocalDate.parse(it.originalDate).plusDays(if (it.id.contains(":w:")) 7 else 1)
        } ?: from)
    suspend fun refresh()=withContext(Dispatchers.IO) { PersonalDataGate.access {
        val rules=dao().recurringRules().filter{it.deletedAt==null && !it.reference.startsWith("CALENDAR_SYNC|")}
        if(rules.isEmpty())return@access
        val signature=gson.toJson(rules.sortedBy{it.id}.map { rule ->
            val pattern=RecurringValidation.pattern(rule)
            listOf(rule.id,rule.version.toString(),repository.now().atZone(ZoneId.of(pattern.zoneId)).toLocalDate().toString())
        })
        val key="recurring-refresh:"+java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(""){"%02x".format(it)}
        repository.assistantTransaction(key) {
            for(rule in rules) {
                if(rule.projectId!=null && dao().project(rule.projectId)?.deletedAt!=null)continue
                materialize(rule,RecurringValidation.pattern(rule))
            }
        }
    } }
    private suspend fun materialize(rule:RecurringRuleEntity,p:RecurringPattern) {
        val day=repository.now().atZone(ZoneId.of(p.zoneId)).toLocalDate()
        val end=day.plusDays(if(rule.domain=="DEADLINE")393 else 28)
        val seeds=if(p.frequency=="ONCE")LocalDate.parse(p.startDate).let { RecurringRules.expand(p,it,it) } else RecurringRules.expand(p,day.minusDays(7),end)
        for(seed in seeds)create(rule,p,seed,gson.toJson(rule))
    }
    private fun appliesFrom(item:RecurringInstanceEntity,from:LocalDate):Boolean {
        val date=LocalDate.parse(item.originalDate)
        return (if(item.id.contains(":w:"))date.plusDays(6) else date)>=from
    }
    private suspend fun untouched(item:RecurringInstanceEntity):Boolean {
        if(item.manualOverride || item.status in setOf("COMPLETED","SKIPPED"))return false
        val task=dao().task(item.taskId)?:return false
        if(task.deletedAt!=null || task.status=="COMPLETED" || task.version!=item.taskVersion || dao().hasRecordsForTask(task.id))return false
        if(dao().plan("recurring-plan:${item.id}")?.version!=item.planVersion || dao().placement(task.id)?.version!=item.placementVersion)return false
        if(dao().placements().any{it.parentTaskId==task.id})return false
        val reminders=dao().reminders().filter{it.taskId==task.id}
        if(reminders.any{it.id!="recurring-reminder:${item.id}"})return false
        val owned=reminders.find{it.id=="recurring-reminder:${item.id}"}
        if(dao().revision("REMINDER_MANUAL_OFF:recurring-reminder:${item.id}")?.revision==1L)return false
        if(owned==null && dao().revision("REMINDER:recurring-reminder:${item.id}")!=null)return false
        if(owned!=null) {
            val source=gson.fromJson(item.ruleSnapshot,RecurringRuleEntity::class.java)
            val lead=RecurringValidation.pattern(source).leadMinutes
            if(lead==null) { if(owned.enabled)return false }
            else {
                val expected=(item.scheduledAt?:task.dueAt?:return false)-lead*60000L
                if(owned.remindAt!=expected || owned.title!=task.title)return false
            }
        }
        return dao().plans().none{it.taskId==task.id && it.id!="recurring-plan:${item.id}" && it.status=="SCHEDULED" && it.deletedAt==null}
    }
    private suspend fun retire(item:RecurringInstanceEntity,status:String,note:String) {
        val task=requireNotNull(dao().task(item.taskId))
        deleteAutoPlan(item.id);disableReminders(task.id)
        val saved=repository.saveTask(task.copy(status="CANCELLED"),task.version)
        repository.saveRecurringInstance(item.copy(status=status,taskVersion=saved.version,planVersion=null,scheduleNote=note),item.version)
    }
    suspend fun act(instanceId: String, expectedVersion: Long, action: String, requestId: String,
                    expectedTaskVersion: Long? = null, expectedPlans: Map<String, Long>? = null) {
        repository.transaction {
            repository.commands.replay(requestId)?.let { return@transaction }
            val instance = dao().recurringInstance(instanceId) ?: throw StaleChange()
            val task = dao().task(instance.taskId) ?: throw StaleChange()
            repository.commands.executeForRepository(com.timeagent.domain.command.ActRecurringInstance(
                requestId, instanceId, expectedVersion, action, expectedTaskVersion ?: task.version,
                expectedPlans ?: activePlans(task.id),
            )).throwIfRejected()
        }
    }

    private suspend fun activePlans(taskId: String): Map<String, Long> = dao().plans()
        .filter { it.taskId == taskId && it.status == "SCHEDULED" && it.deletedAt == null }.associate { it.id to it.version }

    internal suspend fun actRaw(instanceId: String, expectedVersion: Long, action: String, expectedPlans: Map<String, Long>? = null) {
        val instance = dao().recurringInstance(instanceId) ?: throw StaleChange()
        if (instance.version != expectedVersion) throw StaleChange()
        val task = dao().task(instance.taskId) ?: throw StaleChange()
        if (task.deletedAt != null) throw StaleChange()
        if (action.equals("SKIP", true) && expectedPlans != null && activePlans(task.id) != expectedPlans) throw StaleChange()
        require(dao().runningTimer()?.taskId!=task.id) { "请先结束本次计时，再处理状态" }
        if(action.uppercase() in setOf("COMPLETE","SKIP"))disableReminders(task.id)
        when (action.uppercase()) {
            "COMPLETE" -> {
                val saved = repository.saveTask(task.copy(status = "COMPLETED"), task.version)
                repository.saveRecurringInstance(instance.copy(status = "COMPLETED", manualOverride = true, taskVersion = saved.version, scheduleNote="已标记完成；未自动补写实际时长", version = instance.version), instance.version)
            }
            "SKIP" -> {
                val saved = repository.saveTask(task.copy(status = "CANCELLED"), task.version)
                dao().plans().filter{it.taskId==task.id && it.status=="SCHEDULED" && it.deletedAt==null}.forEach{repository.deletePlan(it.id)}
                repository.saveRecurringInstance(instance.copy(status = "SKIPPED", manualOverride = true, scheduledAt=null, taskVersion = saved.version, planVersion = null, scheduleNote="本次已跳过，计划已移出；未补打卡", version = instance.version), instance.version)
            }
            "REOPEN" -> {
                val saved = repository.saveTask(task.copy(status = "TODO"), task.version)
                repository.saveRecurringInstance(instance.copy(status = "PENDING", manualOverride = true, scheduledAt=dao().plan("recurring-plan:${instance.id}")?.startAt, taskVersion = saved.version, scheduleNote="本次已重开；未创建新计划或恢复提醒，请核对", version = instance.version), instance.version)
            }
            else -> throw IllegalArgumentException("未知操作:$action")
        }
    }

    private suspend fun movable(id:String,version:Long):Pair<RecurringInstanceEntity,TaskEntity> {
        val item=dao().recurringInstance(id)?:throw StaleChange()
        if(item.version!=version)throw StaleChange()
        val task=dao().task(item.taskId)?:throw StaleChange()
        require(task.deletedAt==null && task.status in setOf("TODO","IN_PROGRESS")) { "请先重新打开本次事项再改期" }
        require(dao().runningTimer()?.taskId!=task.id) { "请先结束计时再改期" }
        require(dao().plans().none{it.taskId==task.id && it.id!="recurring-plan:$id" && it.status=="SCHEDULED" && it.deletedAt==null}) { "存在其他手动计划，请在任务详情编辑" }
        return item to task
    }
    private fun sorted(items:List<Conflict>)=items.sortedBy{it.id}
    suspend fun previewMove(instanceId:String,expectedVersion:Long,targetAt:Long,withPlan:Boolean):List<Conflict> = withContext(Dispatchers.IO) { PersonalDataGate.access {
        require(targetAt>repository.now().toEpochMilli()) { "请选择未来的改期时间" }
        val (item,task)=movable(instanceId,expectedVersion)
        val source=gson.fromJson(item.ruleSnapshot,RecurringRuleEntity::class.java)
        require(source.domain!="DEADLINE" || !withPlan) { "生活期限是时间点，请关闭同时安排时段" }
        if(withPlan)PersonalMemoryRules.violation(dao().memories(),task,Instant.ofEpochMilli(targetAt),Instant.ofEpochMilli(targetAt+task.estimatedMinutes*60000L),repository.zone)?.let{throw IllegalArgumentException("与个人约定冲突：${it.text}")}
        val warnings=if(withPlan)repository.conflicts(span(targetAt,targetAt+task.estimatedMinutes*60000L),"recurring-plan:$instanceId")else emptyList()
        checkedMove=RecurringMovePreview(instanceId,expectedVersion,targetAt,withPlan,task.version,dao().plan("recurring-plan:$instanceId")?.version,warnings)
        warnings
    } }
    suspend fun move(instanceId:String,expectedVersion:Long,targetAt:Long,withPlan:Boolean,expectedConflicts:List<Conflict>,requestId:String) {
        repository.transaction {
            repository.commands.replay(requestId)?.let { return@transaction }
            val check=checkedMove?:throw StaleChange()
            if(check.id!=instanceId || check.version!=expectedVersion || check.at!=targetAt || check.planned!=withPlan || sorted(check.conflicts)!=sorted(expectedConflicts))throw StaleChange()
            repository.commands.executeForRepository(com.timeagent.domain.command.MoveRecurringInstance(requestId, check)).throwIfRejected()
        }
    }
    internal suspend fun moveRaw(check: RecurringMovePreview) {
            val instanceId=check.id; val expectedVersion=check.version; val targetAt=check.at
            val withPlan=check.planned; val expectedConflicts=check.conflicts
            val (item,task)=movable(instanceId,expectedVersion);val old=dao().plan("recurring-plan:$instanceId")
            if(check.id!=instanceId || check.version!=expectedVersion || check.at!=targetAt || check.planned!=withPlan || check.taskVersion!=task.version || check.planVersion!=old?.version || sorted(check.conflicts)!=sorted(expectedConflicts))throw StaleChange()
            require(targetAt>repository.now().toEpochMilli()) { "改期时间已过，请重新检查" }
            val end=targetAt+task.estimatedMinutes*60000L
            val warnings=if(withPlan)repository.conflicts(span(targetAt,end),"recurring-plan:$instanceId")else emptyList()
            if(sorted(warnings)!=sorted(expectedConflicts))throw StaleChange()
            val saved=repository.saveTask(task.copy(dueAt=if(withPlan)end else targetAt),task.version)
            if(withPlan)repository.savePlan(ScheduleBlockEntity("recurring-plan:$instanceId",task.id,targetAt,end),old?.version,saved.version,expectedConflicts)
            else if(old!=null)repository.deletePlan(old.id)
            val source=gson.fromJson(item.ruleSnapshot,RecurringRuleEntity::class.java);val p=RecurringValidation.pattern(source)
            resyncReminder(item.id,saved.id,saved.title,Slot(saved.dueAt!!,if(withPlan)targetAt else null,targetAt),p)
            repository.saveRecurringInstance(item.copy(status="PENDING",scheduledAt=targetAt,manualOverride=true,taskVersion=saved.version,
                planVersion=dao().plan("recurring-plan:$instanceId")?.version,scheduleNote="仅本次改期：${Instant.ofEpochMilli(targetAt).atZone(repository.zone).toLocalDateTime()}；${if(withPlan)"计划已保存" else "未安排时段"}"),item.version)
    }
    private suspend fun create(rule: RecurringRuleEntity, pattern: RecurringPattern, seed: RecurringSeed, snapshot: String) {
        val d = dao()
        if (d.recurringInstance(seed.key) != null) return
        if (d.revision("RECURRING_INSTANCE:${seed.key}") != null) return
        val taskId = "recurring-task:${seed.key}"
        if (d.task(taskId) != null) return
        val slot = slot(rule, pattern, seed)
        val task = repository.saveTask(TaskEntity(id = taskId, title = rule.title, description = describe(rule, seed), estimatedMinutes = pattern.durationMinutes, dueAt = slot.dueAt, source = "RECURRING"))
        val placement=rule.projectId?.let { repository.savePlacement(TaskPlacementEntity(task.id,it)) }
        val (planVersion, note) = arrange("recurring-plan:${seed.key}", task, slot, pattern, null)
        resyncReminder(seed.key, taskId, rule.title, slot, pattern)
        repository.saveRecurringInstance(RecurringInstanceEntity(
            id = seed.key, ruleId = rule.id, taskId = taskId,
            originalDate = (seed.date ?: seed.bucket).toString(), ordinal = seed.ordinal,
            scheduledAt = slot.fixedAt, status = "PENDING", manualOverride = false,
            ruleSnapshot = snapshot, ruleVersion = rule.version, taskVersion = task.version,
            planVersion = planVersion, placementVersion = placement?.version, scheduleNote = note, version = 0))
    }

    private suspend fun update(rule: RecurringRuleEntity, pattern: RecurringPattern, seed: RecurringSeed, instance: RecurringInstanceEntity, snapshot: String) {
        val task = dao().task(instance.taskId) ?: return
        if (task.deletedAt != null || task.status == "COMPLETED") return
        if (instance.manualOverride || instance.status == "COMPLETED" || instance.status == "SKIPPED") return
        if (task.version != instance.taskVersion) return
        val planId = "recurring-plan:${instance.id}"
        val autoPlan = dao().plan(planId)
        if (instance.planVersion != null && (autoPlan == null || autoPlan.version != instance.planVersion)) return
        val slot = slot(rule, pattern, seed)
        val resume = instance.status in setOf("SUSPENDED","SUPERSEDED")
        val saved = repository.saveTask(task.copy(title = rule.title, description = describe(rule, seed), estimatedMinutes = pattern.durationMinutes, dueAt = slot.dueAt, status = if (resume) "TODO" else task.status), task.version)
        var placement=dao().placement(saved.id)
        if(placement?.projectId!=rule.projectId)placement=repository.savePlacement(TaskPlacementEntity(saved.id,rule.projectId),placement?.version)
        val (planVersion, note) = arrange(planId, saved, slot, pattern, autoPlan)
        resyncReminder(instance.id, instance.taskId, rule.title, slot, pattern)
        repository.saveRecurringInstance(instance.copy(status = if (resume) "PENDING" else instance.status, scheduledAt = slot.fixedAt, ruleSnapshot = snapshot, ruleVersion = rule.version, taskVersion = saved.version, planVersion = planVersion, placementVersion=placement?.version, scheduleNote = note, version = instance.version), instance.version)
    }

    // 有冲突或触犯个人记忆规则就不落计划，只留真实说明
    private suspend fun arrange(planId: String, task: TaskEntity, slot: Slot, pattern: RecurringPattern, existing: ScheduleBlockEntity?): Pair<Long?, String> {
        val start = slot.startAt ?: run {
            if (existing != null) repository.deletePlan(planId)
            return null to "仅保存日期 / 频次，未安排时段"
        }
        val end = start + pattern.durationMinutes * 60_000L
        val clashes = repository.conflicts(span(start, end), planId)
        val blocked = clashes.isNotEmpty() || PersonalMemoryRules.violation(dao().memories(), task, Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), repository.zone) != null
        if (blocked) {
            if (existing != null) repository.deletePlan(planId)
            return null to "自动安排存在冲突，需手动安排"
        }
        repository.savePlan(ScheduleBlockEntity(planId, task.id, start, end, version = existing?.version ?: 0), existing?.version, task.version, emptyList())
        return dao().plan(planId)?.version to "固定计划已保存；未计作实际"
    }

    private suspend fun resyncReminder(instanceId: String, taskId: String, title: String, slot: Slot, pattern: RecurringPattern) {
        val id = "recurring-reminder:$instanceId"
        val existing = dao().reminder(id)
        if(existing==null && dao().revision("REMINDER:$id")!=null)return
        if(dao().revision("REMINDER_MANUAL_OFF:$id")?.revision==1L)return
        val lead = pattern.leadMinutes
        val remindAt = if (lead == null) null else (slot.fixedAt ?: slot.dueAt) - lead * 60_000L
        if (remindAt == null || remindAt <= repository.now().toEpochMilli()) {
            if (existing != null && existing.enabled && existing.deliveredAt == null) repository.saveReminder(existing.copy(enabled = false), existing.version,automatic=true)
            return
        }
        if (existing == null) repository.saveReminder(ReminderEntity(id = id, title = title, remindAt = remindAt, taskId = taskId),automatic=true)
        else if (existing.remindAt != remindAt || !existing.enabled || existing.title!=title) repository.saveReminder(existing.copy(title=title,remindAt = remindAt, enabled = true), existing.version,automatic=true)
    }

    private suspend fun deleteAutoPlan(instanceId: String) {
        val id = "recurring-plan:$instanceId"
        if (dao().plan(id) != null) repository.deletePlan(id)
    }

    private suspend fun disableReminders(taskId: String) {
        for (reminder in dao().reminders().filter { it.taskId == taskId && it.enabled }) {
            repository.saveReminder(reminder.copy(enabled = false), reminder.version,automatic=true)
        }
    }

    private data class Slot(val dueAt: Long, val startAt: Long?, val fixedAt:Long?=startAt)

    private fun slot(rule: RecurringRuleEntity, pattern: RecurringPattern, seed: RecurringSeed): Slot {
        val zone = ZoneId.of(pattern.zoneId)
        val day = seed.date ?: seed.bucket
        if (pattern.frequency == "WEEKLY_COUNT") return Slot(endOfDay(minOf(day.plusDays(6),pattern.untilDate?.let(LocalDate::parse)?:day.plusDays(6)), zone), null)
        val point=pattern.minuteOfDay?.let { day.atTime(it/60,it%60).atZone(zone).toInstant().toEpochMilli() }
        if(rule.domain=="DEADLINE")return Slot(point?:endOfDay(day,zone),null,point)
        return Slot(point?.plus(pattern.durationMinutes*60000L)?:endOfDay(day,zone),point)
    }

    private fun endOfDay(date: LocalDate, zone: ZoneId): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    private fun describe(rule: RecurringRuleEntity, seed: RecurringSeed): String {
        val date = (seed.date ?: seed.bucket).toString()
        return buildString {
            append("来自周期规则「${rule.title}」原始日期 $date")
            if (rule.reference.isNotBlank()) append('\n').append(rule.reference.trim())
            if (rule.category.isNotBlank()) append('\n').append(rule.category.trim())
        }
    }
}

enum class RuleChangeScope { FUTURE_ONLY, INCLUDING_PENDING }

data class RuleChangePreview(val editable: Int, val protectedCount: Int, val firstUnmaterializedDate: LocalDate, val versions: String)

data class RecurringMovePreview(val id:String,val version:Long,val at:Long,val planned:Boolean,val taskVersion:Long,val planVersion:Long?,val conflicts:List<Conflict>)
