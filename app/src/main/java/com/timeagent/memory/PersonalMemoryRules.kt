package com.timeagent.memory

import com.timeagent.data.PersonalMemoryEntity
import com.timeagent.data.TaskEntity
import java.time.*
import java.util.UUID

object PersonalMemoryRules {
    fun fromExplicit(input: String, now: Instant): PersonalMemoryEntity? {
        if (input.length > 2000 || Regex("[?？]|(?:吗|可以吗|行不行)[。！!]?$").containsMatchIn(input.trim())) return null
        val match = Regex("^(?:请帮我|帮我|请)?记住[：:,，\\s]*(.+)$").matchEntire(input.trim()) ?: return null
        val content = match.groupValues[1].trim().trimEnd('。')
        if (content.isBlank()) return null
        val rule = Regex("^(?:我)?(?:每)?周([一二三四五六日天])(?:我)?(?:不要|避免|不)(?:再)?(.+)$").matchEntire(content)
        val keyword = rule?.groupValues?.get(2)?.replace(Regex("^(?:安排|进行|做)"), "").orEmpty().trim()
        val kind = when {
            rule != null || Regex("偏好|喜欢|习惯|优先").containsMatchIn(content) -> "PREFERENCE"
            Regex("约定|暂时|以后|期间").containsMatchIn(content) -> "AGREEMENT"
            else -> "FACT"
        }
        return PersonalMemoryEntity(UUID.randomUUID().toString(), kind, content, sourceType = "EXPLICIT", sourceText = input,
            ruleType = if (rule != null && keyword.isNotBlank()) "AVOID_WEEKDAY" else "NONE", keyword = keyword,
            weekday = rule?.groupValues?.get(1)?.let { "一二三四五六日".indexOf(it.replace("天", "日")) + 1 },
            createdAt = now.toEpochMilli(), updatedAt = now.toEpochMilli())
    }

    fun active(card: PersonalMemoryEntity, at: Long): Boolean = card.enabled && card.confirmed && card.deletedAt == null &&
        (card.validFrom == null || card.validFrom <= at) && (card.validUntil == null || at < card.validUntil)

    fun violation(cards: List<PersonalMemoryEntity>, task: TaskEntity, start: Instant, end: Instant, zone: ZoneId): PersonalMemoryEntity? {
        require(end > start)
        return cards.firstOrNull { card ->
            if (!card.enabled || !card.confirmed || card.deletedAt != null || card.ruleType == "NONE" ||
                card.keyword.isNotBlank() && !task.title.contains(card.keyword, ignoreCase = true)) return@firstOrNull false
            val from = maxOf(start, card.validFrom?.let(Instant::ofEpochMilli) ?: start)
            val until = minOf(end, card.validUntil?.let(Instant::ofEpochMilli) ?: end)
            if (from >= until) return@firstOrNull false
            when (card.ruleType) {
                "AVOID_WEEKDAY" -> {
                    var day = from.atZone(zone).toLocalDate()
                    var blocked = false
                    while (day.atStartOfDay(zone).toInstant() < until) {
                        if (day.dayOfWeek.value == card.weekday) { blocked = true; break }
                        day = day.plusDays(1)
                    }
                    blocked
                }
                "TIME_WINDOW" -> {
                    val first = requireNotNull(card.startMinute)
                    val last = requireNotNull(card.endMinute)
                    val day = from.atZone(zone).toLocalDate()
                    listOf(day.minusDays(1), day).none { date ->
                        val windowStart = date.atStartOfDay().plusMinutes(first.toLong()).atZone(zone).toInstant()
                        val windowEnd = (if (last <= first) date.plusDays(1) else date).atStartOfDay().plusMinutes(last.toLong()).atZone(zone).toInstant()
                        from >= windowStart && until <= windowEnd
                    }
                }
                else -> true
            }
        }
    }

    fun allows(cards: List<PersonalMemoryEntity>, task: TaskEntity, start: Instant, end: Instant, zone: ZoneId): Boolean = violation(cards, task, start, end, zone) == null

    fun validate(card: PersonalMemoryEntity) {
        require(card.id.isNotBlank() && card.text.trim().length in 1..2000 && card.sourceText.length <= 4000) { "请填写记忆内容，最多2000字" }
        require(card.kind in setOf("PREFERENCE", "FACT", "AGREEMENT")) { "记忆类型无效" }
        require(card.sourceType in setOf("USER_INPUT", "EXPLICIT", "OBSERVATION")) { "记忆来源无效" }
        require(card.validFrom == null || card.validUntil == null || card.validUntil > card.validFrom) { "失效时间必须晚于生效时间" }
        require(card.keyword.length <= 200) { "适用关键词过长" }
        when (card.ruleType) {
            "NONE" -> Unit
            "AVOID_WEEKDAY" -> require(card.weekday in 1..7 && card.keyword.isNotBlank()) { "请选择星期并填写适用任务关键词" }
            "TIME_WINDOW" -> require(card.startMinute != null && card.endMinute != null && card.startMinute in 0..1439 && card.endMinute in 0..1439 && card.startMinute != card.endMinute) { "请填写有效的每日起止时间" }
            else -> error("排程约定类型无效")
        }
    }
}
