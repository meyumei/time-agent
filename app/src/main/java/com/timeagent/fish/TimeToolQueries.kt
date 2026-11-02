package com.timeagent.fish

import com.google.gson.Gson
import com.timeagent.data.TimeRepository
import org.json.JSONObject
import java.time.*

internal class TimeToolQueries(private val repository: TimeRepository) {
    suspend fun read(name: String, date: String?): JSONObject {
        val data = repository.snapshot()
        val now = repository.now()
        val zone = repository.zone
        fun json(value: Any) = JSONObject(Gson().toJson(value))
        if (name == "time_today" || name == "time_schedule") {
            val date = if (name == "time_today") now.atZone(zone).toLocalDate() else LocalDate.parse(requireNotNull(date))
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val tasks = data.tasks.filter { it.deletedAt == null }.associateBy { it.id }
            fun local(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toOffsetDateTime().toString()
            return json(mapOf("date" to date.toString(), "zone" to zone.id, "now" to now.toString(),
                "plans" to data.plans.filter { it.status == "SCHEDULED" && it.deletedAt == null && it.startAt < end && (if (it.kind == "POINT") it.startAt >= start else it.endAt > start) && (it.taskId == null || tasks[it.taskId] != null) }.map {
                    mapOf("id" to it.id,"version" to it.version,"kind" to it.kind,"taskId" to it.taskId,"title" to it.displayTitle(tasks[it.taskId]),"start" to local(it.startAt),"end" to local(it.endAt)) },
                "courses" to data.occurrences.filter { it.status != "CANCELLED" && it.startAt < end && it.endAt > start }.map {
                    mapOf("id" to it.id,"version" to it.version,"title" to data.courses.find { c -> c.id == it.courseId }?.name,"start" to local(it.startAt),"end" to local(it.endAt)) },
                "tasks" to tasks.values.filter { it.status in setOf("TODO","IN_PROGRESS") && (it.dueAt == null || it.dueAt < end) }.map {
                    mapOf("id" to it.id,"version" to it.version,"title" to it.title,"dueAt" to it.dueAt?.let(::local),"status" to it.status) },
                "actual" to data.records.filter { it.startAt < end && (it.endAt ?: now.toEpochMilli()) > start }.map {
                    mapOf("id" to it.id,"version" to it.version,"title" to (it.title.ifBlank { tasks[it.taskId]?.title ?: "实际记录" }),"start" to local(it.startAt),"end" to it.endAt?.let(::local)) }))
        }
        if (name == "time_tasks") return json(mapOf("tasks" to data.tasks.filter { it.deletedAt == null && it.status in setOf("TODO","IN_PROGRESS") }.map {
            mapOf("id" to it.id,"version" to it.version,"title" to it.title,"status" to it.status,"estimatedMinutes" to it.estimatedMinutes,"dueAt" to it.dueAt) }))
        if (name == "time_projects") return json(mapOf("projects" to data.projects.filter { it.deletedAt == null }.map { project ->
            val ids = data.placements.filter { it.projectId == project.id }.map { it.taskId }.toSet()
            mapOf("id" to project.id,"name" to project.name,"status" to project.status,"tasks" to data.tasks.filter { it.id in ids && it.deletedAt == null }.map {
                mapOf("id" to it.id,"version" to it.version,"title" to it.title,"status" to it.status) })
        }))
        throw IllegalArgumentException("不支持的查询工具：$name")
    }
}
