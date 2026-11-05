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
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_review_001), style=MaterialTheme.typography.titleMedium)
    TextButton(onClick=onGoals, modifier=Modifier.testTag("review-goals")) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_review_002)) }
    data.projects.forEach { project ->
        val p = projectProgress(data,project.id,weekStart,now,zone)
        if (project.deletedAt == null || p.records.isNotEmpty() || p.completedTasks.isNotEmpty()) {
            Surface(color=MaterialTheme.colorScheme.surfaceContainer, modifier=Modifier.fillMaxWidth(), shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp)) {
                    Text(project.name + if(project.deletedAt != null) "（已删除，历史保留）" else "")
                    project.dueAt?.let { Text("截止 ${Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}",style=MaterialTheme.typography.bodySmall) }
                    TextButton(onClick={ sources="本周完成任务" to p.completedTasks.map { Triple("TASK",it.id,it.title) } }) { Text("本周完成 ${p.completedTasks.size} 项") }
                    TextButton(onClick={ sources="计划投入来源" to p.plans.map { Triple("PLAN",it.id,"${it.displayTitle(data.tasks.find{t->t.id==it.taskId})} · ${Instant.ofEpochMilli(it.startAt).label(zone)}") } }) { Text("计划投入 ${p.plannedMinutes} 分钟") }
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
    Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_review_003),style=MaterialTheme.typography.bodySmall)
    sources?.let { (title,rows) -> AlertDialog(onDismissRequest={sources=null},title={Text(title)},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            if(rows.isEmpty()) Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_project_review_004))
            rows.forEach { (type,id,label) -> TextButton(onClick={sources=null;onOpen(type,id)},modifier=Modifier.testTag("project-source-$id")) {Text(label)} }
        }
    },confirmButton={TextButton(onClick={sources=null}){Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.action_close))}}) }
}

@Composable
fun WeeklyGoalsScreen(
    data: LocalSnapshot, model: TimeViewModel, now: Instant, zone: ZoneId, onClose: () -> Unit,
    onSaved: (com.timeagent.domain.command.CommandResult) -> Unit = {},
) {
    WeeklyGoalEditor(null, data, model, now, zone, onDismiss = onClose, onSaved = onSaved)
}
