package com.timeagent.imports

import com.google.gson.JsonParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale
import kotlin.math.max

object OfficialSchoolPolicy {
    const val LOGIN_URL = "https://sso.scut.edu.cn/cas/login"
    const val TIMETABLE_URL = "https://xsjw2018.jw.scut.edu.cn/"
    private val hosts = setOf("sso.scut.edu.cn", "xsjw2018.jw.scut.edu.cn", "jw.scut.edu.cn", "my.scut.edu.cn")
    fun isAllowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host?.lowercase(Locale.ROOT) in hosts && uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }.getOrDefault(false)
}

/** Application-owned static parsers; no WakeUp code, dynamic scripts, or guessed school endpoints. */
object OfficialTimetableParser {
    private const val MAX_HTML = 2_000_000
    private data class GridCell(val element: Element, val row: Int, val column: Int)

    fun parseHtml(html: String): CourseImportDraft {
        require(html.isNotBlank() && html.length <= MAX_HTML) { "课表页面为空或过大，请打开当前学期课表后重试" }
        val document = Jsoup.parse(html)
        document.select("script,style,input,textarea,select,button,iframe,object,embed").remove()
        document.select("form").unwrap()
        val rows = mutableListOf<EditableCourseRow>()
        val warnings = mutableListOf<String>()
        for (table in document.select("table")) {
            val grid = expand(table)
            if (grid.isEmpty()) continue
            val header = grid.indices.firstOrNull { row -> grid[row].values.count { dayText(it.element.text()) != null } >= 2 }
            val dayColumns = if (header != null) grid[header].mapNotNull { (column, cell) -> dayText(cell.element.text())?.let { column to it } }.toMap() else emptyMap()
            val seen = mutableSetOf<Element>()
            for ((rowNumber, row) in grid.withIndex()) {
                if (rowNumber == header) continue
                for ((column, cell) in row.toSortedMap()) {
                    val element = cell.element
                    if (!seen.add(element)) continue
                    val text = plain(element)
                    if (!looksLikeCourse(element, text)) continue
                    val left = row.filterKeys { it < column }.toSortedMap().values
                    val day = dayColumns[column] ?: left.mapNotNull { dayText(it.element.text()) }.lastOrNull().orEmpty()
                    val period = left.mapNotNull { periodText(it.element.text()) }.lastOrNull().orEmpty()
                    val blocks = element.select(".timetable_con").filter { it.parents().none { ancestor -> ancestor != element && ancestor.hasClass("timetable_con") } }
                    for (block in blocks.ifEmpty { listOf(element) }) {
                        val raw = plain(block)
                        if (!looksLikeCourse(block, raw)) continue
                        val draft = parseCell(block, raw, day, period)
                        if (draft.name.isBlank() || draft.dayOfWeek.isBlank() || draft.periods.isBlank() || draft.weeks.isBlank()) {
                            warnings += "部分网页课程字段未识别，请对照学校课表修正后再预览。"
                        }
                        rows += draft
                    }
                }
            }
        }
        val distinctRows = rows.distinct()
        require(distinctRows.isNotEmpty()) { "当前页面没有识别到课表。请登录并进入本学期完整课表页；如页面结构不支持，可导出 PDF 或手工补录。" }
        require(distinctRows.size <= 1000) { "课程行过多，请仅打开一个学期的课表" }
        if (document.text().contains("实践课程")) warnings += "网页包含实践课程说明，未排具体时段的课程请人工补录。"
        val hint = CourseCellFields.termHint(document.text())
        return CourseImportDraft(distinctRows, termIdHint = hint.first, termNameHint = hint.second, warnings = warnings.distinct())
    }

    /** Known ZF response field mapping, usable only with a timetable response explicitly supplied by the page/user. */
    fun parseZfJson(raw: String): CourseImportDraft {
        require(raw.isNotBlank() && raw.length <= MAX_HTML) { "教务响应为空或过大" }
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse { throw IllegalArgumentException("这不是支持的正方课表响应") }
        val list = root.getAsJsonArray("kbList") ?: throw IllegalArgumentException("响应没有 kbList 课表字段，请返回学校课表页重试")
        require(list.size() in 1..1000) { "教务课表为空或课程过多" }
        fun value(item: com.google.gson.JsonObject, key: String) = item.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val rows = list.map { item ->
            require(item.isJsonObject) { "课表响应的课程行格式无效" }
            val course = item.asJsonObject
            EditableCourseRow(value(course, "kcmc"), value(course, "xm"), value(course, "cdmc"), value(course, "xqj"), value(course, "jcs"), value(course, "zcd"), value(course, "jxbmc").takeIf { it.isNotBlank() }, value(course, "xqmc"))
        }
        return CourseImportDraft(rows, warnings = listOf("请核对课程和学期设置。此字段映射尚待当前学校已登录页面验收。"))
    }

    private fun parseCell(element: Element, text: String, day: String, period: String): EditableCourseRow {
        val explicitTitle = element.selectFirst(".title,.kcmc,[data-course-name]")?.text()?.trim().orEmpty()
        if (Regex("周数\\s*[:：]").containsMatchIn(text)) {
            val before = text.substringBefore(Regex("周数\\s*[:：]").find(text)!!.value).trim()
            val row = CourseCellFields.fromScutText(text, explicitTitle.ifBlank { before }, day, period)
            return row.copy(periods = period.ifBlank { explicitPeriod(text) })
        }
        val name = explicitTitle.ifBlank { text.lines().firstOrNull { it.isNotBlank() }.orEmpty().trim() }
        val timing = element.selectFirst("[title*=节/周],[title*=周次],[title*=上课时间]")?.text().orEmpty().ifBlank { text }
        val weeks = Regex("(?:第)?\\d{1,2}(?:\\s*[-－–至~～]\\s*\\d{1,2})?\\s*周(?:\\s*[（(][单双](?:周)?[）)])?(?:\\s*[,，、]\\s*(?:第)?\\d{1,2}(?:\\s*[-－–至~～]\\s*\\d{1,2})?\\s*周?(?:\\s*[（(][单双](?:周)?[）)])?)*").find(timing)?.value.orEmpty()
        fun field(title: String): String = element.select("[title]").firstOrNull { it.attr("title").contains(title) }?.text()?.trim().orEmpty()
        return EditableCourseRow(name, field("教师").ifBlank { field("老师") }, field("地点").ifBlank { field("教室") }, day, explicitPeriod(timing).ifBlank { period }, weeks, campus = field("校区"))
    }

    private fun explicitPeriod(text: String): String = Regex("[（(]?(?:第)?(\\d{1,2}(?:\\s*[-－–至~～]\\s*\\d{1,2})?)\\s*节[）)]?").find(text)?.groupValues?.get(1).orEmpty()
    private fun looksLikeCourse(element: Element, text: String): Boolean = text.contains("周数") || (Regex("\\d\\s*周").containsMatchIn(text) && (element.select(".title,.timetable_con").isNotEmpty() || text.contains("节")))
    private fun dayText(raw: String): String? = raw.trim().takeIf { Regex("(?:星期|周)[一二三四五六日天1-7]").matches(it) }?.let { TimetableFields.day(it).toString() }
    private fun periodText(raw: String): String? = raw.trim().takeIf { Regex("(?:第)?\\d{1,2}(?:\\s*[-－–至~～]\\s*\\d{1,2})?(?:节)?").matches(it) }?.let { runCatching { TimetableFields.periods(it).let { p -> "${p.first}-${p.last}" } }.getOrNull() }

    private fun plain(element: Element): String {
        val copy = element.clone()
        copy.select("br").forEach { it.before("\n") }
        return copy.wholeText().replace('\u00a0', ' ').trim()
    }

    private fun expand(table: Element): List<Map<Int, GridCell>> {
        val result = mutableListOf<MutableMap<Int, GridCell>>()
        val htmlRows = table.select("tr").filter { it.closest("table") == table }
        require(htmlRows.size <= 1000) { "课表行数过多" }
        for ((r, tr) in htmlRows.withIndex()) {
            while (result.size <= r) result += mutableMapOf<Int, GridCell>()
            var column = 0
            for (element in tr.children().filter { it.tagName() == "td" || it.tagName() == "th" }) {
                while (result[r].containsKey(column)) column++
                val rowSpan = (element.attr("rowspan").toIntOrNull() ?: 1).coerceIn(1, 100)
                val colSpan = (element.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 30)
                require(column + colSpan <= 60 && r + rowSpan <= 1100) { "课表合并单元格范围过大" }
                val cell = GridCell(element, r, column)
                for (targetRow in r until r + rowSpan) {
                    while (result.size <= targetRow) result += mutableMapOf<Int, GridCell>()
                    for (targetColumn in column until column + colSpan) result[targetRow][targetColumn] = cell
                }
                column += max(1, colSpan)
            }
        }
        return result
    }
}
