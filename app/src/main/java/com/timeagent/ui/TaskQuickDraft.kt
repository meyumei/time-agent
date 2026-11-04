package com.timeagent.ui

import com.timeagent.assistant.AssistantContext
import com.timeagent.assistant.LocalAssistantParser
import com.timeagent.assistant.SpokenTime
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.ProjectEntity
import com.timeagent.data.TagEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Local quick-add result. This object is a draft only; it never writes business data.
 *
 * [projectId] and [tagIds] are resolved only from exact, unique names supplied by the caller.
 */
data class QuickTaskDraft(
    val title: String,
    val dueAt: Long?,
    val projectId: String?,
    val tagIds: List<String>,
    val raw: String,
)

sealed interface QuickTaskDraftIssue {
    /** A #name matched more than one project/tag. Nothing is selected automatically. */
    data class AmbiguousName(val names: List<String>) : QuickTaskDraftIssue

    /** More than one date was found, so the whole sentence stays as the title. */
    data class MultipleDates(val count: Int) : QuickTaskDraftIssue

    /** The date text was found but could not be turned into a valid date. */
    data object UnreadableDate : QuickTaskDraftIssue

    /** A date and an unclear/ambiguous clock were found together. */
    data object UnreadableTime : QuickTaskDraftIssue

    /** #name did not match any current project/tag; the token stays in the title. */
    data class UnknownNames(val names: List<String>) : QuickTaskDraftIssue
}

data class QuickTaskDraftResult(
    val raw: String,
    val draft: QuickTaskDraft?,
    val dueDate: LocalDate? = null,
    val project: ProjectEntity? = null,
    val tags: List<TagEntity> = emptyList(),
    val issue: QuickTaskDraftIssue? = null,
)

/**
 * Conservative local parser for the task quick-add field.
 *
 * Supports common spoken dates, one optional clock, and exact #project/#tag names. When anything
 * is ambiguous it falls back to the complete raw sentence as the title and never deletes text.
 */
object TaskQuickDraftParser {
    private val tagPattern = Regex("#([^\\s#，,。.；;、:：!?！？]+)")
    private val ambiguousNightClock =
        Regex("(?:晚上|夜里|半夜|午夜)\\s*(?:十二|12)\\s*(?:[点时]|[:：])")

    fun parse(
        input: String,
        now: Instant,
        zone: ZoneId,
        projects: List<ProjectEntity>,
        tags: List<TagEntity>,
        snapshot: LocalSnapshot,
    ): QuickTaskDraftResult {
        val raw = input.trim()
        if (raw.isBlank()) return QuickTaskDraftResult(raw = raw, draft = null)

        val parser = LocalAssistantParser()
        val weekStart = now.atZone(zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val context = AssistantContext(
            now = now,
            zone = zone,
            selectedWeekStart = weekStart,
            snapshot = snapshot,
        )
        val activeProjects = projects.filter { it.deletedAt == null }

        val tokens = tagPattern.findAll(raw).toList()
        val tokenRanges = tokens.map { it.range }
        val appliedRanges = mutableListOf<IntRange>()
        val resolvedTags = mutableListOf<TagEntity>()
        val ambiguous = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        var resolvedProject: ProjectEntity? = null

        tokens.forEach { token ->
            val name = token.groupValues[1]
            val projectMatches = activeProjects.filter { it.name == name }.distinctBy { it.id }
            val tagMatches = tags.filter { it.name == name }.distinctBy { it.id }
            when {
                projectMatches.size + tagMatches.size == 1 -> {
                    val project = projectMatches.firstOrNull()
                    if (project != null) {
                        val current = resolvedProject
                        if (current == null || current.id == project.id) {
                            resolvedProject = project
                            appliedRanges += token.range
                        } else {
                            ambiguous += name
                        }
                    } else {
                        val tag = tagMatches.single()
                        if (resolvedTags.none { it.id == tag.id }) resolvedTags += tag
                        appliedRanges += token.range
                    }
                }

                projectMatches.size + tagMatches.size > 1 -> ambiguous += name
                else -> unknown += name
            }
        }

        // Any ambiguous #name is a hard stop: keep the whole sentence as a plain title.
        if (ambiguous.isNotEmpty()) {
            return rawResult(raw, QuickTaskDraftIssue.AmbiguousName(ambiguous.distinct()))
        }

        // Use the shared token grammar directly so "截止明天…" is still recognized; parseDate
        // remains the single source of truth for turning the token into a LocalDate.
        val dateMatches = SpokenTime.datePattern.findAll(raw).filter { match ->
            tokenRanges.none { match.range.first in it }
        }.toList()
        if (dateMatches.size > 1) {
            return rawResult(raw, QuickTaskDraftIssue.MultipleDates(dateMatches.size))
        }

        var dueAt: Long? = null
        var dueDate: LocalDate? = null
        if (dateMatches.size == 1) {
            val dateMatch = dateMatches.single()
            val date = parser.parseDate(dateMatch.value, context)
                ?: return rawResult(raw, QuickTaskDraftIssue.UnreadableDate)
            dueDate = date

            val clocks = LocalAssistantParser.clockPattern.findAll(raw).filter { clock ->
                clock.range.first !in dateMatch.range &&
                    tokenRanges.none { clock.range.first in it }
            }.toList()
            val unclearNight = ambiguousNightClock.findAll(raw).any { match ->
                tokenRanges.none { match.range.first in it }
            }

            if (clocks.size > 1 || unclearNight) {
                return rawResult(raw, QuickTaskDraftIssue.UnreadableTime)
            }

            if (clocks.isEmpty()) {
                dueAt = date.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
            } else {
                val time = SpokenTime.times(raw, clocks)?.singleOrNull()
                    ?: return rawResult(raw, QuickTaskDraftIssue.UnreadableTime)
                dueAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                appliedRanges += clocks.single().range
            }
            appliedRanges += dateMatch.range
        }

        val drop = BooleanArray(raw.length)
        appliedRanges.forEach { range ->
            for (index in range) if (index in raw.indices) drop[index] = true
        }
        val title = buildString {
            raw.forEachIndexed { index, char -> if (!drop[index]) append(char) }
        }.replace(Regex("\\s+"), " ").trim()

        val draft = QuickTaskDraft(
            title = title.ifBlank { raw },
            dueAt = dueAt,
            projectId = resolvedProject?.id,
            tagIds = resolvedTags.map { it.id },
            raw = raw,
        )
        return QuickTaskDraftResult(
            raw = raw,
            draft = draft,
            dueDate = dueDate,
            project = resolvedProject,
            tags = resolvedTags,
            issue = unknown.distinct().takeIf { it.isNotEmpty() }
                ?.let { QuickTaskDraftIssue.UnknownNames(it) },
        )
    }

    private fun rawResult(raw: String, issue: QuickTaskDraftIssue) = QuickTaskDraftResult(
        raw = raw,
        draft = QuickTaskDraft(
            title = raw,
            dueAt = null,
            projectId = null,
            tagIds = emptyList(),
            raw = raw,
        ),
        issue = issue,
    )
}
