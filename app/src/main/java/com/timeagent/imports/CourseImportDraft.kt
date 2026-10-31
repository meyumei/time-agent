package com.timeagent.imports

import com.timeagent.core.*
import java.time.LocalTime

/** Editable source fields stay separate from the dated, normalized import bundle. */
data class EditableCourseRow(
    val name: String = "", val teacher: String = "", val location: String = "",
    val dayOfWeek: String = "", val periods: String = "", val weeks: String = "",
    val externalKey: String? = null, val campus: String = "",
)

data class CourseImportDraft(
    val rows: List<EditableCourseRow>,
    val sourceId: String = "scut-undergraduate",
    val termIdHint: String = "",
    val termNameHint: String = "导入学期",
    val warnings: List<String> = emptyList(),
) {
    fun normalize(term: ImportTerm, periods: Map<Int, PeriodTime>): ImportBundle = CourseNormalizer.normalize(
        sourceId, term, periods, rows.mapIndexed { index, row ->
            try {
                val span = TimetableFields.periods(row.periods)
                ImportedCourse(row.name.trim(), row.teacher.trim(), listOf(row.campus.trim(), row.location.trim()).filter { it.isNotEmpty() }.joinToString(" · "),
                    TimetableFields.day(row.dayOfWeek), span.first, span.last, TimetableFields.weeks(row.weeks), row.externalKey)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("第 ${index + 1} 行：${e.message}", e)
            }
        },
    )

    val suggestedTeachingWeeks: Int get() = rows.flatMap { runCatching { TimetableFields.weeks(it.weeks) }.getOrDefault(emptySet()) }.maxOrNull() ?: 18
}

object TimetableFields {
    private fun basic(raw: String) = raw.trim().replace('（', '(').replace('）', ')').replace('，', ',').replace('、', ',')
        .replace('－', '-').replace('–', '-').replace('—', '-').replace('～', '-').replace('~', '-').replace("至", "-")

    fun weeks(raw: String): Set<Int> {
        val text = basic(raw).replace(Regex("\\s+"), "").removePrefix("周数:").removePrefix("周数：")
        require(text.isNotEmpty()) { "请填写教学周，如 1-16周、1-16周(单)" }
        val result = linkedSetOf<Int>()
        for (part in text.split(',')) {
            val match = Regex("第?(\\d{1,2})(?:-(\\d{1,2}))?周?(?:\\(([单双])(?:周)?\\))?").matchEntire(part)
                ?: throw IllegalArgumentException("不能识别周次「$part」，请按 1-16周(单),18周 填写")
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toIntOrNull() ?: start
            require(start in 1..60 && end in start..60) { "周次范围须在 1–60 内，且结束周不早于开始周" }
            val parity = match.groupValues[3]
            val segment = (start..end).filter { parity.isEmpty() || it % 2 == if (parity == "单") 1 else 0 }
            require(segment.isNotEmpty()) { "周次「$part」与单双周条件不一致" }
            result += segment
        }
        return result
    }

    fun periods(raw: String): IntRange {
        val text = basic(raw).replace(Regex("\\s+"), "").removePrefix("(").removeSuffix(")").removePrefix("第").removeSuffix("节")
        val match = Regex("(\\d{1,2})(?:-(\\d{1,2}))?").matchEntire(text)
            ?: throw IllegalArgumentException("请填写节次，如 1-2、5-8")
        val first = match.groupValues[1].toInt()
        val last = match.groupValues[2].toIntOrNull() ?: first
        require(first in 1..50 && last in first..50) { "节次须在 1–50 之间，且结束节不早于开始节" }
        return first..last
    }

    fun day(raw: String): Int {
        val value = raw.trim().removePrefix("星期").removePrefix("周")
        val day = value.toIntOrNull() ?: mapOf("一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7)[value]
        require(day != null && day in 1..7) { "星期须填写 1–7（周一到周日）" }
        return day
    }

    /** No school timing defaults: every used period must be entered and reviewed. */
    fun periodTimes(raw: String): Map<Int, PeriodTime> {
        val result = linkedMapOf<Int, PeriodTime>()
        for ((index, line) in raw.lines().withIndex()) {
            if (line.isBlank()) continue
            val match = Regex("\\s*(\\d{1,2})\\s*[=＝]\\s*(\\d{1,2}:\\d{2})\\s*[-–－]\\s*(\\d{1,2}:\\d{2})\\s*").matchEntire(line)
                ?: throw IllegalArgumentException("节次时间第 ${index + 1} 行格式错误，请填写 1=08:00-08:45")
            val number = match.groupValues[1].toInt()
            require(number in 1..50 && number !in result) { "节次编号须为 1–50，不能重复" }
            fun time(value: String): LocalTime = try { LocalTime.parse(value.padStart(5, '0')) } catch (_: Exception) { throw IllegalArgumentException("时间无效：$value") }
            val start = time(match.groupValues[2])
            val end = time(match.groupValues[3])
            require(end > start) { "第 $number 节结束时间必须晚于开始时间" }
            result[number] = PeriodTime(start, end)
        }
        require(result.isNotEmpty()) { "请填写学校节次时间，并核对后再预览" }
        return result
    }
}

internal object CourseCellFields {
    fun labeled(text: String, label: String): String = Regex("${Regex.escape(label)}\\s*[:：]\\s*([^/\\n]*)")
        .find(text)?.groupValues?.get(1)?.trim().orEmpty()

    fun fromScutText(text: String, name: String, day: String, periods: String): EditableCourseRow = EditableCourseRow(
        name.trim(), labeled(text, "教师"), labeled(text, "地点"), day, periods, labeled(text, "周数"),
        labeled(text, "教学班").takeIf { it.isNotBlank() }, labeled(text, "校区"),
    )

    fun termHint(text: String): Pair<String, String> {
        val match = Regex("(\\d{4})\\s*[-－]\\s*(\\d{4})\\s*学年第\\s*([123])\\s*学期").find(text) ?: return "" to "导入学期"
        return "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}" to match.value
    }
}
