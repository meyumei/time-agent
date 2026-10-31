package com.timeagent.data

import com.timeagent.core.*
import org.json.JSONObject
import java.time.*

/** An application-owned exchange format. It is deliberately not an unverified school parser. */
class JsonCourseAdapter : CourseSourceAdapter {
    override val sourceType = "JSON"
    override fun parse(raw: String): ImportBundle {
        require(raw.isNotBlank() && raw.length <= 512000) { "请选择不超过 500 KB 的 UTF-8 JSON 课表" }
        val root = JSONObject(raw)
        val term = root.getJSONObject("term")
        val config = ImportTerm(term.getString("id"), LocalDate.parse(term.getString("firstWeekStart")), term.getInt("teachingWeeks"), ZoneId.of(term.getString("timezone")), term.optString("name", "导入学期"))
        val periodsJson = root.getJSONArray("periods")
        val periods = (0 until periodsJson.length()).associate { index ->
            val item = periodsJson.getJSONObject(index)
            item.getInt("number") to PeriodTime(LocalTime.parse(item.getString("start")), LocalTime.parse(item.getString("end")))
        }
        require(periods.size == periodsJson.length()) { "节次编号不能重复" }
        val courses = root.getJSONArray("courses")
        return CourseNormalizer.normalize(root.getString("sourceId"), config, periods, (0 until courses.length()).map { index ->
            val item = courses.getJSONObject(index)
            val weeks = item.getJSONArray("weeks")
            ImportedCourse(item.getString("name"), item.optString("teacher"), item.optString("location"), item.getInt("dayOfWeek"), item.getInt("startPeriod"), item.getInt("endPeriod"), (0 until weeks.length()).map { weeks.getInt(it) }.toSet(), item.optString("externalKey").takeIf { it.isNotBlank() })
        })
    }
}
