package com.timeagent.history

import com.timeagent.core.TimeSpan
import com.timeagent.core.TimeStatistics
import com.timeagent.core.workout.WorkoutRules
import com.timeagent.core.workout.WorkoutSession
import com.timeagent.core.workout.WorkoutSessionStatus
import com.timeagent.data.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

enum class HistoryDomain { ALL, RECORDS, WORKOUTS, NOTES }

data class HistoryFilter(
    val from: LocalDate? = null,
    val through: LocalDate? = null,
    val keyword: String = "",
    val taskId: String? = null,
    val activityId: String? = null,
    val domain: HistoryDomain = HistoryDomain.ALL,
)

data class HistoryAnswer(
    val filter: HistoryFilter,
    val rangeLabel: String,
    val recordedMinutes: Long,
    val elapsedMinutes: Long,
    val workoutCount: Int,
    val workoutMinutes: Long,
    val lastAt: Long?,
    val records: List<TimeRecordEntity>,
    val workouts: List<WorkoutSession>,
    val notes: List<NoteEntity>,
    val text: String,
)

object LocalHistory {

    fun answer(data: LocalSnapshot, workouts: List<WorkoutSession>, filter: HistoryFilter, now: Instant, zone: ZoneId): HistoryAnswer {
        require(filter.from == null || filter.through == null || filter.from <= filter.through) { "起始日期须不晚于结束日期" }
        val kw = filter.keyword.trim()
        val nowMs = now.toEpochMilli()
        val fromInstant = filter.from?.atStartOfDay(zone)?.toInstant()
        val throughEnd = filter.through?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
        val windowEnd = if (throughEnd != null && throughEnd.isBefore(now)) throughEnd else now
        val windowEndMs = windowEnd.toEpochMilli()

        val includeRecords = filter.domain == HistoryDomain.ALL || filter.domain == HistoryDomain.RECORDS
        val includeWorkouts = (filter.domain == HistoryDomain.ALL || filter.domain == HistoryDomain.WORKOUTS) && filter.taskId == null && filter.activityId == null
        val includeKeywordNotes = filter.domain != HistoryDomain.WORKOUTS

        val tasks = data.tasks.associateBy { it.id }
        val activities = data.activities.associateBy { it.id }
        val categories = data.categories.associateBy { it.id }
        val notesByTarget = data.notes.groupBy { it.targetType.uppercase() to it.targetId }

        fun chain(id: String?) = activityChain(id, activities)
        fun noteTexts(type: String, id: String?): List<String> =
            if (id == null) emptyList() else notesByTarget[type to id].orEmpty().flatMap { listOf(it.title, it.body) }

        fun inScope(r: TimeRecordEntity): Boolean {
            if (filter.taskId != null && r.taskId != filter.taskId) return false
            if (filter.activityId != null) {
                val taskActivity = r.taskId?.let { tasks[it] }?.activityId
                if (chain(taskActivity).none { it.id == filter.activityId }) return false
            }
            return true
        }

        // Deleted / archived tasks and activities stay readable here on purpose: history is historical.
        fun haystack(r: TimeRecordEntity): String {
            val task = r.taskId?.let { tasks[it] }
            val trail = chain(task?.activityId)
            val sb = StringBuilder()
            sb.append(r.title).append(' ').append(r.notes).append(' ').append(r.source).append(' ')
            if (task != null) sb.append(task.title).append(' ').append(task.description).append(' ')
            categories[r.categoryId]?.let { sb.append(it.name).append(' ') }
            task?.categoryId?.let { cid -> categories[cid]?.let { sb.append(it.name).append(' ') } }
            trail.forEach { sb.append(it.name).append(' ') }
            noteTexts("RECORD", r.id).forEach { sb.append(it).append(' ') }
            task?.let { t -> noteTexts("TASK", t.id).forEach { sb.append(it).append(' ') } }
            trail.forEach { a -> noteTexts("ACTIVITY", a.id).forEach { sb.append(it).append(' ') } }
            return sb.toString()
        }

        val spans = ArrayList<TimeSpan>()
        val matchedRecords = ArrayList<TimeRecordEntity>()
        if (includeRecords) {
            for (r in data.records) {
                if (!inScope(r)) continue
                if (kw.isNotEmpty() && !haystack(r).contains(kw, ignoreCase = true)) continue
                val span = clipToWindow(r, fromInstant, windowEnd, now) ?: continue
                spans += span
                matchedRecords += r
            }
        }
        val stats = TimeStatistics.calculate(spans)

        val fromDate = filter.from
        val throughDate = filter.through
        val matchedWorkouts = if (!includeWorkouts) emptyList() else workouts.filter { s ->
            s.status == WorkoutSessionStatus.COMPLETED && s.endedAt?.let { it <= nowMs } == true &&
                startDateInRange(s.startedAt, fromDate, throughDate, zone) && (workoutMatchesKeyword(s, kw) || kw.isNotBlank() && noteTexts("RECORD", "workout:${s.id}").any { it.contains(kw, true) })
        }
        val workoutMinutes = matchedWorkouts.sumOf { s ->
            WorkoutRules.elapsedMillis(s, nowMs).coerceAtLeast(0L)
        }

        val effectiveWorkoutMinutes = workoutMinutes / 60000L
        val matchedRecordIds = matchedRecords.map { it.id }.toHashSet()
        val matchedTaskIds = matchedRecords.mapNotNull { it.taskId }.toHashSet()
        val matchedActivityIds = HashSet<String>()
        matchedRecords.forEach { r -> chain(r.taskId?.let { t -> tasks[t] }?.activityId).forEach { matchedActivityIds += it.id } }
        val matchedWorkoutIds = matchedWorkouts.map { it.id }.toHashSet()

        fun insideWindow(stamp: Long) =
            stamp < windowEndMs && (fromInstant == null || stamp >= fromInstant.toEpochMilli())

        val matchedNotes = data.notes.filter { n ->
            val linked = when (n.targetType.uppercase()) {
                "RECORD" -> n.targetId in matchedRecordIds || matchedWorkouts.any { n.targetId == "workout:${it.id}" }
                "TASK" -> n.targetId in matchedTaskIds
                "ACTIVITY" -> n.targetId in matchedActivityIds
                "WORKOUT" -> n.targetId in matchedWorkoutIds
                else -> false
            }
            if (linked) return@filter true
            if (!includeKeywordNotes || filter.taskId != null || filter.activityId != null) return@filter false
            val kwOk = kw.isEmpty() || n.title.contains(kw, true) || n.body.contains(kw, true)
            val stamp = if (n.updatedAt > 0L) n.updatedAt else n.createdAt
            kwOk && insideWindow(stamp)
        }

        val stamps = ArrayList<Long>()
        matchedRecords.forEach { stamps += it.startAt }
        matchedWorkouts.forEach { stamps += it.startedAt }
        if (filter.domain == HistoryDomain.NOTES) matchedNotes.forEach { stamps += if (it.updatedAt > 0L) it.updatedAt else it.createdAt }
        val lastAt = stamps.maxOrNull()

        val label = rangeLabel(filter)
        val text = buildText(
            label, filter, includeWorkouts, stats.recordedMinutes, stats.elapsedMinutes,
            matchedWorkouts.size, effectiveWorkoutMinutes, matchedRecords.size, matchedNotes.size,
            listOfNotNull(
                kw.takeIf { it.isNotEmpty() }?.let { "关键词「$it」" },
                filter.taskId?.let { "任务「${tasks[it]?.title ?: "已移除的任务"}」" },
                filter.activityId?.let { "活动「${activities[it]?.name ?: "已移除的活动"}」" },
            ).joinToString("、"), lastAt, zone,
        )

        return HistoryAnswer(
            filter = filter,
            rangeLabel = label,
            recordedMinutes = stats.recordedMinutes,
            elapsedMinutes = stats.elapsedMinutes,
            workoutCount = matchedWorkouts.size,
            workoutMinutes = effectiveWorkoutMinutes,
            lastAt = lastAt,
            records = matchedRecords,
            workouts = matchedWorkouts,
            notes = matchedNotes,
            text = text,
        )
    }

    /** Half-open [start, end) clipping; open timers end at now; future-start rows are dropped. */
    private fun clipToWindow(r: TimeRecordEntity, from: Instant?, windowEnd: Instant, now: Instant): TimeSpan? {
        val nowMs = now.toEpochMilli()
        if (r.startAt > nowMs) return null
        val end = minOf(r.endAt ?: nowMs, nowMs, windowEnd.toEpochMilli())
        val start = maxOf(r.startAt, from?.toEpochMilli() ?: Long.MIN_VALUE)
        return if (end > start) TimeSpan(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end)) else null
    }

    private fun startDateInRange(startedAt: Long, from: LocalDate?, through: LocalDate?, zone: ZoneId): Boolean {
        val date = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
        if (from != null && date.isBefore(from)) return false
        if (through != null && date.isAfter(through)) return false
        return true
    }

    private fun workoutMatchesKeyword(s: WorkoutSession, kw: String): Boolean {
        if (kw.isEmpty()) return true
        if (s.title.contains(kw, true) || s.notes.contains(kw, true)) return true
        return s.movements.any { m ->
            m.exercise.name.contains(kw, true) || m.notes.contains(kw, true) ||
                m.exercise.equipment.contains(kw, true) ||
                m.exercise.primaryMuscles.any { it.contains(kw, true) } ||
                m.exercise.secondaryMuscles.any { it.contains(kw, true) }
        }
    }

    private fun activityChain(id: String?, byId: Map<String, ActivityTemplateEntity>): List<ActivityTemplateEntity> {
        val out = ArrayList<ActivityTemplateEntity>()
        var cursor = id
        while (cursor != null && out.size < 16) {
            val node = byId[cursor] ?: break
            out += node
            cursor = node.parentId
        }
        return out
    }

    private fun rangeLabel(filter: HistoryFilter): String {
        val f = filter.from
        val t = filter.through
        return when {
            f == null && t == null -> "全部本地历史（不限日期，截止当前时刻）"
            f == null -> "截至 $t（含当天）"
            t == null -> "从 $f 起至当前时刻"
            f == t -> "$f 当天"
            else -> "$f 至 $t（含首尾两天）"
        }
    }

    private fun buildText(
        label: String,
        filter: HistoryFilter,
        includeWorkouts: Boolean,
        recorded: Long,
        elapsed: Long,
        workoutCount: Int,
        workoutMinutes: Long,
        recordCount: Int,
        noteCount: Int,
        scope: String,
        lastAt: Long?,
        zone: ZoneId,
    ): String {
        val sb = StringBuilder()
        sb.append("范围：").append(label).append("。")
        if (scope.isNotEmpty()) sb.append("筛选：").append(scope).append("。")
        lastAt?.let { sb.append("最近匹配记录：").append(Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("。") }
        sb.append("所选日期内，记录累计 ").append(recorded).append(" 分钟（重叠记录分别计入，进行中计时算至现在）。")
        sb.append("去重覆盖 ").append(elapsed).append(" 分钟（重叠部分只算一次）。")
        if (includeWorkouts) {
            sb.append("健身完成 ").append(workoutCount).append(" 次，共 ").append(workoutMinutes)
                .append(" 分钟（按训练开始日期归属，已扣除暂停）。")
            sb.append("训练与计时记录分别列示，不相加。")
        }
        if (filter.domain == HistoryDomain.NOTES) sb.append("笔记只作为线索列出，不折算任何时长。")
        if (recordCount == 0 && workoutCount == 0 && noteCount == 0) {
            sb.append("未查到该范围的记录。")
        } else {
            sb.append("命中计时记录 ").append(recordCount).append(" 条、训练 ").append(workoutCount)
                .append(" 次、笔记 ").append(noteCount).append(" 条。")
        }
        return sb.toString()
    }
}

object HistoryQuestionParser {

    private val imperative = listOf(
        "记录", "记一下", "记下", "帮我记", "安排", "添加", "新建", "创建", "开始",
        "写入", "写下", "补记", "修改", "删除", "设置", "提醒我", "计划",
    )
    private val markers = listOf(
        "多久", "多少分钟", "多少小时", "多少时间", "多长时间", "累计", "总共", "一共", "合计",
        "几次", "几回", "多少次", "上次", "查记录", "找笔记", "查笔记", "总时长", "时长",
        "用时", "花了多少", "用了多少", "什么时候", "哪天", "哪次",
    )
    private val futureWords = listOf("明天", "后天", "大后天", "下周", "下个星期", "下个月", "明年", "待会", "稍后", "接下来")
    private val pastWords = listOf(
        "今天", "昨天", "前天", "上周", "上星期", "本周", "这周", "上个月", "本月", "这个月",
        "最近", "过去", "上次", "之前", "已经",
    )
    private val grammar = listOf(
        "累计", "总共", "一共", "合计", "统计一下", "统计", "多少分钟", "多少个小时", "多少小时",
        "多少时间", "多长时间", "多久", "几次", "几回", "多少次", "什么时候", "什么时间",
        "是哪天", "哪一天", "哪次", "查记录", "找笔记", "查笔记", "查一下", "查查", "看看",
        "请问", "帮我", "给我", "总时长", "时长", "用时", "花了多少", "用了多少",
        "学了", "练了", "看了", "，", ",", "。", "？", "?", "！", "!", "~", "～",
    )
    private val workoutWords = listOf("健身", "训练", "锻炼", "撸铁", "运动", "打卡")
    private val ISO_RANGE = Regex("(\\d{4}-\\d{1,2}-\\d{1,2})\\s*(?:到|至|~|～|—|－)\\s*(\\d{4}-\\d{1,2}-\\d{1,2})")
    private val ISO_DATE = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
    private val RECENT_DAYS = Regex("(最近|过去|近)\\s*(\\d{1,3})\\s*(天|日)")

    fun isHistoryQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (imperative.any { t.startsWith(it) }) return false
        if (markers.none { t.contains(it) }) return false
        if (futureWords.any { t.contains(it) } && pastWords.none { t.contains(it) }) return false
        return true
    }

    fun parse(text: String, now: Instant, zone: ZoneId): HistoryFilter? {
        if (!isHistoryQuestion(text)) return null
        val today = now.atZone(zone).toLocalDate()
        val removals = ArrayList<String>()
        var from: LocalDate? = null
        var through: LocalDate? = null

        val rangeMatch = ISO_RANGE.find(text)
        val singleMatch = ISO_DATE.find(text)
        if (rangeMatch != null) {
            from = isoDate(rangeMatch.groupValues[1])
            through = isoDate(rangeMatch.groupValues[2])
            if (from.isAfter(through)) throw IllegalArgumentException("日期范围无效：$from 晚于 $through")
            removals += rangeMatch.value
        } else if (singleMatch != null) {
            from = isoDate(singleMatch.value)
            through = from
            removals += singleMatch.value
        } else {
            val relative = relativeRange(text, today)
            if (relative != null) {
                from = relative.first
                through = relative.second
                removals += relative.third
            }
        }
        if (text.contains("上次")) removals += "上次"

        val domain = when {
            workoutWords.any { text.contains(it) } || Regex("练(?:胸|背|肩|腿|臂|手臂|腹|臀)").containsMatchIn(text) -> HistoryDomain.WORKOUTS
            text.contains("笔记") -> HistoryDomain.NOTES
            else -> HistoryDomain.RECORDS
        }

        var keyword = removals.fold(text) { acc, token -> acc.replace(token, " ") }
        for (token in grammar) keyword = keyword.replace(token, " ")
        if (domain == HistoryDomain.WORKOUTS) {
            for (token in workoutWords) keyword = keyword.replace(token, " ")
        }
        keyword = keyword.replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('的', '了', '是', '有', '呢', '吗', '啊', '吧', '，', ',', '。', '？', '?', '！', '!')
            .trim()
        if (keyword in setOf("记录", "笔记", "历史", "情况", "数据", "练", "次")) keyword = ""

        return HistoryFilter(from = from, through = through, keyword = keyword, domain = domain)
    }

    private fun isoDate(raw: String): LocalDate = try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("无法解析日期：$raw，请使用 yyyy-MM-dd 格式", e)
    }

    private fun relativeRange(text: String, today: LocalDate): Triple<LocalDate, LocalDate, String>? {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monthStart = today.withDayOfMonth(1)
        val prevMonthStart = monthStart.minusMonths(1)
        RECENT_DAYS.find(text)?.let { m ->
            val n = (m.groupValues[2].toIntOrNull() ?: 1).coerceAtLeast(1)
            return Triple(today.minusDays((n - 1).toLong()), today, m.value)
        }
        val table = listOf(
            "今天" to (today to today),
            "昨天" to (today.minusDays(1) to today.minusDays(1)),
            "前天" to (today.minusDays(2) to today.minusDays(2)),
            "上星期" to (weekStart.minusWeeks(1) to weekStart.minusDays(1)),
            "上周" to (weekStart.minusWeeks(1) to weekStart.minusDays(1)),
            "本周" to (weekStart to today),
            "这周" to (weekStart to today),
            "上个月" to (prevMonthStart to prevMonthStart.withDayOfMonth(prevMonthStart.lengthOfMonth())),
            "上月" to (prevMonthStart to prevMonthStart.withDayOfMonth(prevMonthStart.lengthOfMonth())),
            "本月" to (monthStart to today),
            "这个月" to (monthStart to today),
            "这月" to (monthStart to today),
        )
        for ((token, range) in table) if (text.contains(token)) return Triple(range.first, range.second, token)
        return null
    }
}
