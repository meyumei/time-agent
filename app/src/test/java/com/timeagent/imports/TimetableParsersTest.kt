package com.timeagent.imports

import com.timeagent.core.ImportTerm
import com.timeagent.core.PeriodTime
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TimetableParsersTest {
    @Test fun weekRangesApplyParityPerSegmentAndAcceptSingleWeek() {
        assertEquals(setOf(6, 8, 10, 12, 16, 18), TimetableFields.weeks("6-12周(双),16-18周(双)"))
        assertEquals(setOf(1, 3, 5, 9, 10), TimetableFields.weeks("第1-6周（单），9-10周"))
        assertEquals(setOf(13), TimetableFields.weeks("第13周"))
    }

    @Test fun malformedRangesNeverBecomePartialSuccess() {
        listOf("1-6周,暑期", "0-4周", "4-2周", "1-61周", "2周(单)").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { TimetableFields.weeks(bad) }
        }
    }

    @Test fun scutMergedRowsUseCellBoundsForWeekdaysAndPeriods() {
        val runs = mutableListOf<PdfTextRun>()
        val rules = mutableListOf<PdfRule>()
        fun run(text: String, x: Float, y: Float, width: Float = 40f) { runs += PdfTextRun(text, x, y, width, 8f) }
        fun h(y: Float, left: Float, right: Float) { rules += PdfRule(left, y, right, y) }
        fun v(x: Float, top: Float, bottom: Float) { rules += PdfRule(x, top, x, bottom) }
        run("2026-2027学年第1学期", 20f, 35f, 120f)
        // One weekday and one period cell span two rows, as in the SCUT print export.
        run("星期一", 22f, 118f)
        run("1-4", 85f, 118f, 20f)
        run("星期二", 22f, 168f)
        run("5-6", 85f, 168f, 20f)
        for (x in listOf(16f, 68f)) v(x, 100f, 150f)
        for (x in listOf(68f, 136f)) v(x, 100f, 150f)
        v(826f, 100f, 150f)
        for (x in listOf(16f, 68f, 136f, 826f)) v(x, 150f, 190f)
        for (y in listOf(100f, 125f, 150f, 190f)) h(y, 136f, 826f)
        for (y in listOf(100f, 150f, 190f)) h(y, 16f, 136f)
        run("电路实验", 152f, 108f, 80f)
        run("周数:8-14周(双)/校区:校区甲/地点:实验楼A/教师:教师甲/教学班:lab-a/学分:1", 350f, 104f, 450f)
        run("通信实验", 152f, 133f, 80f)
        run("周数:9-15周(单)/地点:待定/教师:教师乙/教学班:lab-b", 350f, 129f, 450f)
        run("工程概论", 152f, 166f, 80f)
        run("周数:第7周/地点:教学楼B/教师:教师丙/教学班:lecture-c", 350f, 162f, 450f)
        run("实践课程：项目设计 教师丁(共1周)/第1周;", 18f, 202f, 400f)
        val result = ScutPdfTimetableParser.parse(listOf(PdfTextPage(842f, 595f, runs, rules)))
        assertEquals(3, result.rows.size)
        assertEquals(listOf("1", "1", "2"), result.rows.map { it.dayOfWeek })
        assertEquals(listOf("1-4", "1-4", "5-6"), result.rows.map { it.periods })
        assertEquals("电路实验", result.rows[0].name)
        assertEquals("实验楼A", result.rows[0].location)
        assertEquals("教师甲", result.rows[0].teacher)
        assertEquals("2026-2027-1", result.termIdHint)
        assertTrue(result.warnings.any { "实践" in it })
    }

    @Test fun gridHtmlHandlesMergedPeriodsAndSeparateTimetableBlocks() {
        val html = """
            <table><tr><th>节次</th><th>星期一</th><th>星期二</th></tr>
            <tr><th>1-2</th><td rowspan="2">
              <div class="timetable_con"><span class="title">高等数学</span><br>
              <span title="节/周">(1-4节)1-8周(单)</span><br>
              <span title="教师">教师甲</span><br><span title="上课地点">教室A</span></div>
              <div class="timetable_con"><span class="title">电路实验</span><br>
              <span title="节/周">(1-4节)2-8周(双)</span><br>
              <span title="教师">教师乙</span><br><span title="上课地点">实验室B</span></div>
            </td><td></td></tr><tr><th>3-4</th><td></td></tr></table>
        """.trimIndent()
        val result = OfficialTimetableParser.parseHtml(html)
        assertEquals(2, result.rows.size)
        assertEquals("1-4", result.rows.first().periods)
        assertEquals("1", result.rows.first().dayOfWeek)
        assertEquals("教师乙", result.rows[1].teacher)
        assertEquals(setOf(2, 4, 6, 8), TimetableFields.weeks(result.rows[1].weeks))
    }

    @Test fun listHtmlPreservesMergedWeekdaysAndDoesNotExecuteScripts() {
        val html = """
            <script>throw Error('this must never execute')</script>
            <table><tr><td rowspan="2">星期三</td><td rowspan="2">5-8</td>
            <td>电路实验 周数:8-14周(双)/地点:实验室/教师:甲/教学班:a</td></tr>
            <tr><td>通信实验 周数:9-15周(单)/地点:待定/教师:乙/教学班:b</td></tr></table>
        """.trimIndent()
        val result = OfficialTimetableParser.parseHtml(html)
        assertEquals(2, result.rows.size)
        assertTrue(result.rows.all { it.dayOfWeek == "3" && it.periods == "5-8" })
        assertEquals("通信实验", result.rows[1].name)
    }

    @Test fun zfResponseUsesOwnFieldMappingAndRejectsEmptyLoginPage() {
        val result = OfficialTimetableParser.parseZfJson("""{"kbList":[{"kcmc":"信号分析","xm":"教师甲","cdmc":"教室甲","xqj":"4","jcs":"3-4","zcd":"1-6周,8-13周","jxbmc":"synthetic-1"}]}""")
        assertEquals("信号分析", result.rows.single().name)
        assertEquals("4", result.rows.single().dayOfWeek)
        assertThrows(IllegalArgumentException::class.java) { OfficialTimetableParser.parseHtml("<form><input type='password'></form>") }
    }

    @Test fun normalizationNeedsExplicitMondayAndEveryUsedPeriodTime() {
        val draft = CourseImportDraft(rows = listOf(EditableCourseRow("示例课", "甲", "A", "1", "1-2", "1-2周")))
        val term = ImportTerm("synthetic-term", LocalDate.of(2026, 9, 7), 18, ZoneId.of("Asia/Shanghai"))
        assertThrows(IllegalArgumentException::class.java) { draft.normalize(term, emptyMap()) }
        val periods = mapOf(1 to PeriodTime(LocalTime.of(8, 0), LocalTime.of(8, 45)), 2 to PeriodTime(LocalTime.of(8, 55), LocalTime.of(9, 40)))
        assertEquals(2, draft.normalize(term, periods).occurrences.size)
        assertThrows(IllegalArgumentException::class.java) { draft.normalize(term.copy(firstWeekStart = term.firstWeekStart.plusDays(1)), periods) }
        assertThrows(IllegalArgumentException::class.java) { TimetableFields.periodTimes("1=08:00-08:45\n1=09:00-09:45") }
    }

    @Test fun officialNavigationRequiresExactHttpsHostAndDefaultPort() {
        assertTrue(OfficialSchoolPolicy.isAllowed("https://sso.scut.edu.cn/cas/login"))
        assertTrue(OfficialSchoolPolicy.isAllowed("https://xsjw2018.jw.scut.edu.cn/"))
        listOf("http://xsjw2018.jw.scut.edu.cn/", "https://sso.scut.edu.cn.evil.test/", "https://evil.test/", "https://sso.scut.edu.cn:444/", "file:///sdcard/a", "javascript:alert(1)", "https://user:password@sso.scut.edu.cn/").forEach {
            assertFalse(it, OfficialSchoolPolicy.isAllowed(it))
        }
    }

    @Test fun verifiedScutPresetOnlyDatesTheKnownTermAndUsesUserSelectedWushanTimes() {
        assertEquals(LocalDate.of(2026, 8, 31), ScutImportPresets.firstMonday("2026-2027-1"))
        assertNull(ScutImportPresets.firstMonday("2027-2028-1"))
        assertNull(ScutImportPresets.firstMonday(""))
        val times = TimetableFields.periodTimes(ScutImportPresets.WUSHAN_PERIODS)
        assertEquals(11, times.size)
        assertEquals(LocalTime.of(8, 0), times.getValue(1).start)
        assertEquals(LocalTime.of(14, 30), times.getValue(5).start)
        assertEquals(LocalTime.of(21, 35), times.getValue(11).end)
    }
}
