package com.timeagent.imports

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class PdfTextRun(val text: String, val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y + height / 2
}
data class PdfRule(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
data class PdfTextPage(val width: Float, val height: Float, val runs: List<PdfTextRun>, val rules: List<PdfRule>)

/** SCUT's printable list uses centered, merged weekday/period cells; text order alone loses them. */
object ScutPdfTimetableParser {
    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun containsY(y: Float) = y >= top - .5f && y < bottom + .5f
        fun containsX(x: Float) = x >= left - .5f && x < right + .5f
    }

    fun parse(pages: List<PdfTextPage>): CourseImportDraft {
        require(pages.isNotEmpty()) { "PDF 为空" }
        val rows = mutableListOf<EditableCourseRow>()
        val warnings = mutableListOf<String>()
        val allText = StringBuilder()
        pages.forEachIndexed { pageIndex, page ->
            val runs = textRuns(page.runs)
            val text = runs.sortedWith(compareBy<PdfTextRun> { it.y }.thenBy { it.x }).joinToString("\n") { it.text }
            allText.appendLine(text)
            if (text.contains("实践课程")) warnings += "第 ${pageIndex + 1} 页有未排具体时段的实践课程，请按原 PDF 人工补录；未自动排入日程。"
            val days = runs.filter { Regex("^(?:星期|周)[一二三四五六日天1-7]$").matches(it.text.trim()) }.map { it to bounds(page, it) }
            val periodLabels = runs.filter {
                Regex("^(?:第)?\\d{1,2}(?:[-–－~至]\\d{1,2})?(?:节)?$").matches(it.text.trim()) &&
                    runCatching { TimetableFields.periods(it.text) }.isSuccess
            }.map { it to bounds(page, it) }
            val markers = runs.filter { Regex("周数\\s*[:：]").containsMatchIn(it.text) }
            for (marker in markers) {
                val cell = bounds(page, marker)
                if (cell == null) {
                    warnings += "第 ${pageIndex + 1} 页有课程缺少清晰表格边界，请对照原 PDF 补全星期、节次和名称。"
                }
                val day = days.firstOrNull { (label, box) -> label.x < marker.x && box?.containsY(marker.centerY) == true }
                    ?: days.firstOrNull { (label, box) -> label.y < marker.y && box?.containsX(marker.centerX) == true }
                val period = periodLabels.firstOrNull { (label, box) -> label.x < marker.x && box?.containsY(marker.centerY) == true }
                val dayText = day?.first?.text?.let { runCatching { TimetableFields.day(it).toString() }.getOrDefault("") }.orEmpty()
                val periodText = period?.first?.text?.let { runCatching { TimetableFields.periods(it).let { p -> "${p.first}-${p.last}" } }.getOrDefault("") }.orEmpty()
                val inCell = if (cell != null) runs.filter { cell.containsY(it.centerY) && it.x >= cell.left - .5f && it.x < cell.right } else listOf(marker)
                val leftTitle = inCell.filter { it.x < marker.x - 2f && it.x >= (cell?.left ?: 0f) }.sortedWith(compareBy<PdfTextRun> { it.y }.thenBy { it.x })
                val aboveTitle = inCell.filter { it.y + it.height < marker.y + 1f }.sortedWith(compareBy<PdfTextRun> { it.y }.thenBy { it.x })
                val inlineTitle = marker.text.substringBefore(Regex("周数\\s*[:：]").find(marker.text)?.value ?: "周数:").trim()
                val name = when {
                    leftTitle.isNotEmpty() -> leftTitle.joinToString(" ") { it.text.trim() }
                    aboveTitle.isNotEmpty() -> aboveTitle.joinToString(" ") { it.text.trim() }
                    else -> inlineTitle
                }
                val metadata = inCell.filter { it.x >= marker.x - 1f }.sortedWith(compareBy<PdfTextRun> { it.y }.thenBy { it.x }).joinToString("\n") { it.text }
                val row = CourseCellFields.fromScutText(metadata, name, dayText, periodText)
                if (row.name.isBlank() || row.dayOfWeek.isBlank() || row.periods.isBlank() || row.weeks.isBlank()) {
                    warnings += "第 ${rows.size + 1} 行有字段未识别，请对照原 PDF 修正后预览。"
                }
                rows += row
            }
        }
        require(rows.isNotEmpty()) { "没有识别到支持的课表表格。请使用教务导出的文字版 PDF，或切换手工补录；图片扫描版暂不支持自动识别。" }
        require(rows.size <= 1000) { "课程行过多，请分学期导入" }
        val hint = CourseCellFields.termHint(allText.toString())
        return CourseImportDraft(rows, termIdHint = hint.first, termNameHint = hint.second, warnings = warnings.distinct())
    }

    private fun bounds(page: PdfTextPage, run: PdfTextRun): Bounds? {
        val horizontal = page.rules.filter { abs(it.y1 - it.y2) < .7f && run.centerX >= min(it.x1, it.x2) - .7f && run.centerX <= max(it.x1, it.x2) + .7f }
        val vertical = page.rules.filter { abs(it.x1 - it.x2) < .7f && run.centerY >= min(it.y1, it.y2) - .7f && run.centerY <= max(it.y1, it.y2) + .7f }
        val top = horizontal.filter { it.y1 <= run.centerY }.maxOfOrNull { it.y1 } ?: return null
        val bottom = horizontal.filter { it.y1 > run.centerY }.minOfOrNull { it.y1 } ?: return null
        val left = vertical.filter { it.x1 <= run.centerX }.maxOfOrNull { it.x1 } ?: return null
        val right = vertical.filter { it.x1 > run.centerX }.minOfOrNull { it.x1 } ?: return null
        return Bounds(left, top, right, bottom)
    }

    /** Join actual glyphs by baseline and proximity without joining separate table columns. */
    private fun textRuns(input: List<PdfTextRun>): List<PdfTextRun> {
        val lines = mutableListOf<MutableList<PdfTextRun>>()
        for (run in input.filter { it.text.isNotEmpty() }.sortedBy { it.y }) {
            val line = lines.lastOrNull { abs(it.first().y - run.y) <= 1.8f }
            if (line == null) lines += mutableListOf(run) else line += run
        }
        val result = mutableListOf<PdfTextRun>()
        for (line in lines) {
            var current: PdfTextRun? = null
            for (run in line.sortedBy { it.x }) {
                val previous = current
                if (previous != null && run.x - previous.right <= max(3f, min(previous.height, run.height) * .8f)) {
                    current = previous.copy(text = previous.text + run.text, width = max(previous.right, run.right) - previous.x, height = max(previous.height, run.height))
                } else {
                    current?.takeIf { it.text.isNotBlank() }?.let { result += it.copy(text = it.text.trim()) }
                    current = run
                }
            }
            current?.takeIf { it.text.isNotBlank() }?.let { result += it.copy(text = it.text.trim()) }
        }
        return result
    }
}
