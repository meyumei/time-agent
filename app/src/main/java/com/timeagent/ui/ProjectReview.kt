package com.timeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.timeagent.data.*
import com.timeagent.projects.*
import java.time.*

@Composable
fun ProjectReviewSection(data: LocalSnapshot, weekStart: LocalDate, now: Instant, zone: ZoneId, onOpen: (String,String)->Unit, onGoals: ()->Unit) {
    var sources by remember { mutableStateOf<Pair<String,List<Triple<String,String,String>>>?>(null) }
    Text("项目与周目标", style=MaterialTheme.typography.titleMedium)
    TextButton(onClick=onGoals, modifier=Modifier.testTag("review-goals")) { Text("设置 / 编辑每周投入目标") }
    data.projects.forEach { project ->
        val p = projectProgress(data,project.id,weekStart,now,zone)
        if (project.deletedAt == null || p.records.isNotEmpty() || p.completedTasks.isNotEmpty()) {
            Surface(color=MaterialTheme.colorScheme.surfaceContainer, modifier=Modifier.fillMaxWidth(), shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp)) {
                    Text(project.name + if(project.deletedAt != null) "（已删除，历史保留）" else "")
                    project.dueAt?.let { Text("截止 ${Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}",style=MaterialTheme.typography.bodySmall) }
                    TextButton(onClick={ sources="本周完成任务" to p.completedTasks.map { Triple("TASK",it.id,it.title) } }) { Text("本周完成 ${p.completedTasks.size} 项") }
                    TextButton(onClick={ sources="计划投入来源" to p.plans.map { Triple("PLAN",it.id,"${data.tasks.find{t->t.id==it.taskId}?.title ?: "任务"} · ${Instant.ofEpochMilli(it.startAt).label(zone)}") } }) { Text("计划投入 ${p.plannedMinutes} 分钟") }
                    val actual = p.records.map { Triple("RECORD",it.id,"${it.title.ifBlank{data.tasks.find{t->t.id==it.taskId}?.title ?: "实际记录"}} · ${Instant.ofEpochMilli(it.startAt).label(zone)}") }
                    TextButton(onClick={ sources="累计实际投入来源（逐条相加）" to actual }) { Text("累计实际投入 ${p.recordedMinutes} 分钟") }
                    TextButton(onClick={ sources="去重时间来源（合并重叠区间）" to actual }) { Text("去重时间 ${p.elapsedMinutes} 分钟") }
                    TextButton(onClick={ sources="当前待办" to p.pendingTasks.map { Triple("TASK",it.id,it.title) } }) { Text("当前待办 ${p.pendingTasks.size} 项") }
                }
            }
        }
    }
    data.weeklyGoals.filter { it.weekStart == weekStart.toString() }.forEach { goal ->
        val p=goalProgress(data,goal,now,zone)
        val title=goal.projectId?.let{id->data.projects.find{it.id==id}?.name} ?: data.activities.find{it.id==goal.activityId}?.name ?: "原对象已不可用"
        TextButton(onClick={ sources="周目标实际来源" to p.records.map{Triple("RECORD",it.id,it.title.ifBlank{data.tasks.find{t->t.id==it.taskId}?.title ?: "实际记录"})} }) { Text("$title · 目标 ${goal.targetMinutes} 分钟 / 累计 ${p.recordedMinutes} / 去重 ${p.elapsedMinutes}") }
        LinearProgressIndicator(progress={ (p.recordedMinutes.toFloat()/goal.targetMinutes).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
    }
    Text("周从周一开始；计划与实际分开。实际裁剪至当前时刻，含进行中计时；重叠记录累计相加，去重时间取并集。完成子任务不会自动完成父任务或项目。点击数值查看来源。",style=MaterialTheme.typography.bodySmall)
    sources?.let { (title,rows) -> AlertDialog(onDismissRequest={sources=null},title={Text(title)},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            if(rows.isEmpty()) Text("本周未查到相应事项")
            rows.forEach { (type,id,label) -> TextButton(onClick={sources=null;onOpen(type,id)},modifier=Modifier.testTag("project-source-$id")) {Text(label)} }
        }
    },confirmButton={TextButton(onClick={sources=null}){Text("关闭")}}) }
}

@Composable fun WeeklyGoalsScreen(data:LocalSnapshot,model:TimeViewModel,now:Instant,zone:ZoneId,onClose:()->Unit) {
    WeeklyGoalDialog(null,data.projects.filter{it.deletedAt==null},data.activities.filter{it.archivedAt==null},data.weeklyGoals,now,zone,onClose) { goal,version ->
        model.perform(onSuccess=onClose) {model.repository.saveWeeklyGoal(goal,version)}
    }
}
