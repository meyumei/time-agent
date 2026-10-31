package com.timeagent.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timeagent.core.workout.*
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkoutSessionScreen(
    model: WorkoutViewModel, session: WorkoutSession, previous: Map<String, WorkoutMovement>, now: Long, busy: Boolean, pounds: Boolean,
    onBack: () -> Unit, onMinimize: () -> Unit, onAdd: (String?) -> Unit, onSettings: () -> Unit, onAnatomy: (String) -> Unit, onOpenSession: (String) -> Unit,
) {
    val readOnly = session.status == WorkoutSessionStatus.COMPLETED || session.status == WorkoutSessionStatus.DISCARDED
    val summary = WorkoutRules.summary(session, now)
    var editText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var textTarget by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var templateName by remember { mutableStateOf<String?>(null) }
    var bodyWeight by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(session.id) { model.refreshPrevious(session.id) }
    Scaffold(containerColor = WorkoutBackground, topBar = {
        Column(Modifier.background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE1E5E9)))).statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回训练中心") }
                Text(if (readOnly) "训练记录" else "训练中", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (readOnly) TextButton(onClick = { templateName = session.title }) { Text("存为模板") }
                else Button(onClick = { focusManager.clearFocus(); confirmation = "结束训练并保存已完成的 ${summary.completedSets} 组？" to { model.finish(session.id) {} } }, enabled = !busy, modifier = Modifier.testTag("workout-finish"), shape = RoundedCornerShape(30.dp)) { Text("完成") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatWorkoutTime(summary.elapsedMillis), fontSize = 44.sp, fontWeight = FontWeight.Black, modifier = Modifier.testTag("workout-elapsed"))
                if (!readOnly) IconButton(onClick = { model.pause(session) }, enabled = !busy, modifier = Modifier.testTag("workout-pause")) { Icon(if (session.status == WorkoutSessionStatus.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (session.status == WorkoutSessionStatus.PAUSED) "恢复训练" else "暂停训练") }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${summary.workingSets} 组 · ${number(summary.volumeKg)} kg", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("workout-volume"))
                    if (session.movements.any { it.supersetId != null }) Text("已完成 ${summary.completedRounds} 轮（含独立组）", style = MaterialTheme.typography.labelSmall)
                    if (summary.warmupSets > 0) Text("热身 ${summary.warmupSets} 组", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(session.title, modifier = Modifier.fillMaxWidth().clickable(enabled = !readOnly) { textTarget = "title"; editText = "训练标题" to session.title }.padding(vertical = 8.dp), style = MaterialTheme.typography.titleLarge, color = Color(0xFF656D75))
            if (session.status == WorkoutSessionStatus.PAUSED) Text("已暂停 · 训练计时和休息倒计时已冻结", style = MaterialTheme.typography.labelMedium, color = WorkoutBlue)
            if (session.movements.any { it.exercise.metric.name in listOf("BODYWEIGHT_REPS", "ASSISTED_REPS") }) TextButton(onClick = { bodyWeight = true }, enabled = !readOnly) { Text("体重：${session.bodyWeightKg?.let { number(it) + " kg" } ?: "未设置"} · 用于自重/辅助容量") }
        }
    }, bottomBar = {
        if (!readOnly) Surface(shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                WorkoutBottomAction("最小化", Icons.Rounded.OpenInNew, onMinimize)
                WorkoutBottomAction("加动作", Icons.Rounded.AddCircle, { focusManager.clearFocus(); onAdd(null) }, Modifier.testTag("workout-add-exercise"))
                WorkoutBottomAction("写心得", Icons.Rounded.EditNote, { focusManager.clearFocus(); textTarget = "notes"; editText = "训练心得" to session.notes })
                WorkoutBottomAction("设置", Icons.Rounded.Settings, { focusManager.clearFocus(); onSettings() })
            }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (readOnly) item {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${summary.completedMovements} 个动作 · ${summary.workingSets} 个正式组", fontWeight = FontWeight.Bold)
                    Text("训练容量 ${number(summary.volumeKg)} kg · 不计热身组", style = MaterialTheme.typography.bodySmall)
                    Text(if (session.timelineRecordId == null) "时间轴回记尚未完成，训练详情已保留。" else "已关联时间轴实际记录", color = if (session.timelineRecordId == null) MaterialTheme.colorScheme.error else WorkoutBlue)
                    if (session.timelineRecordId == null && session.status == WorkoutSessionStatus.COMPLETED) TextButton(onClick = { model.finish(session.id) {} }, enabled = !busy) { Text("重试回记时间轴") }
                    if (session.notes.isNotBlank()) { HorizontalDivider(); Text(session.notes) }
                    if (session.status == WorkoutSessionStatus.COMPLETED) Button(onClick = { model.repeat(session.id, onOpenSession) }, enabled = !busy) { Text("再练一次") }
                } }
            }
            val rest = WorkoutRules.restRemainingMillis(session, now)
            if (!readOnly && session.setTimer != null) item {
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFE0F1FF), modifier = Modifier.testTag("workout-set-timer")) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("单组秒表", fontWeight = FontWeight.Bold); Text(formatWorkoutTime(WorkoutRules.sessionSetTimerElapsedMillis(session, now)), fontSize = 30.sp, color = WorkoutBlue) }
                        Button(onClick = { model.perform { model.repository.stopSetTimer(session.id) } }, enabled = !busy) { Text("记录时长") }
                    }
                }
            }
            if (!readOnly && rest > 0) item {
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFFE0F1FF), modifier = Modifier.testTag("workout-rest")) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("组间休息", fontWeight = FontWeight.Bold); Text(formatWorkoutTime(rest), fontSize = 28.sp, color = WorkoutBlue) }
                    TextButton(onClick = { model.update(session.id) { it.copy(restEndsAt = it.restEndsAt?.plus(15_000), pausedRestRemainingMillis = it.pausedRestRemainingMillis?.plus(15_000)) } }) { Text("+15秒") }
                    TextButton(onClick = { model.update(session.id) { it.copy(restEndsAt = null, pausedRestRemainingMillis = null) } }) { Text("跳过") }
                } }
            }
            val interval = WorkoutRules.intervalState(session, now)
            if (!readOnly && interval != null) item {
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF162638), modifier = Modifier.testTag("workout-interval")) { Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Tabata · 第 ${interval.round} 轮 · ${when (interval.phase.name) { "PREPARE" -> "准备"; "WORK" -> "训练"; "REST" -> "休息"; else -> "已结束" }}", color = Color.White)
                    Text(formatWorkoutTime(interval.remainingMillis), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { model.perform { model.repository.finishInterval(session.id) } }, enabled = interval.phase.name == "FINISHED") { Text("记录本次间歇", color = Color.White) }
                    TextButton(onClick = { model.update(session.id) { it.copy(intervalTimer = null) } }) { Text("取消阶段计时", color = Color(0xFFB9CDE0)) }
                } }
            }
            if (session.movements.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { Icon(Icons.Rounded.FitnessCenter, null, modifier = Modifier.size(48.dp), tint = WorkoutBlue); Text("添加今天要练的动作"); Button(onClick = { onAdd(null) }) { Text("打开动作库") } } }
            itemsIndexed(session.movements, key = { _, movement -> movement.id }) { index, movement ->
                WorkoutMovementCard(model, session, movement, index, previous[movement.exercise.id], readOnly, pounds, onAnatomy, onReplace = { onAdd(movement.id) }, onNote = { textTarget = movement.id; editText = "${movement.exercise.name} · 笔记" to movement.notes }, onDelete = { confirmation = "移除「${movement.exercise.name}」和它的记录组？" to { model.update(session.id) { it.copy(movements = it.movements.filterNot { item -> item.id == movement.id }) } } })
            }
            if (!readOnly) item {
                TextButton(onClick = { templateName = session.title }) { Text("保存为训练模板") }
                TextButton(onClick = { confirmation = "放弃本次训练？未完成的训练将不写入时间轴。" to { model.discard(session.id, onBack) } }) { Text("放弃训练", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    editText?.let { (title, value) -> WorkoutTextDialog(title, value, onDismiss = { editText = null }, onSave = { text ->
        when (textTarget) { "title" -> model.update(session.id) { it.copy(title = text.trim()) }; "notes" -> model.update(session.id) { it.copy(notes = text) }; else -> textTarget?.let { id -> model.updateMovement(session.id, id) { it.copy(notes = text) } } }; editText = null
    }) }
    templateName?.let { WorkoutTextDialog("模板名称", it, onDismiss = { templateName = null }, onSave = { name -> model.saveTemplate(session.id, name); templateName = null }) }
    confirmation?.let { (message, action) -> AlertDialog(onDismissRequest = { confirmation = null }, text = { Text(message) }, confirmButton = { TextButton(onClick = { confirmation = null; action() }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } }) }
    if (bodyWeight) WorkoutTextDialog("体重（kg）", session.bodyWeightKg?.let(::number).orEmpty(), onDismiss = { bodyWeight = false }, onSave = { value -> model.update(session.id) { it.copy(bodyWeightKg = value.toDoubleOrNull()) }; bodyWeight = false }, numeric = true)
}

@Composable private fun WorkoutMovementCard(model: WorkoutViewModel, session: WorkoutSession, movement: WorkoutMovement, index: Int, previous: WorkoutMovement?, readOnly: Boolean, pounds: Boolean, onAnatomy: (String) -> Unit, onReplace: () -> Unit, onNote: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }; var restEdit by remember { mutableStateOf(false) }; var warmupRestEdit by remember { mutableStateOf(false) }; var fieldOptions by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExercisePicture(movement.exercise, Modifier.size(68.dp).clickable { onAnatomy(anatomyMuscle(movement.exercise)) })
                Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(movement.exercise.name, fontWeight = FontWeight.Bold); Text(movement.exercise.primaryMuscles.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Color.Gray); movement.supersetId?.let { Text("超级组 ${it.takeLast(3).uppercase()}", color = WorkoutBlue, style = MaterialTheme.typography.labelSmall) } }
                if (!readOnly) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "动作设置：${movement.exercise.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("动作笔记") }, onClick = { menu = false; onNote() })
                        DropdownMenuItem(text = { Text("热身休息：${movement.warmupRestSeconds} 秒") }, onClick = { menu = false; warmupRestEdit = true })
                        DropdownMenuItem(text = { Text("使用上次数据") }, onClick = { menu = false; model.copyPrevious(session.id, movement) })
                        DropdownMenuItem(text = { Text("替换动作") }, onClick = { menu = false; onReplace() })
                        if (index > 0) DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; model.update(session.id) { val position = it.movements.indexOfFirst { item -> item.id == movement.id }; if (position <= 0) it else it.copy(movements = it.movements.toMutableList().apply { java.util.Collections.swap(this, position, position - 1) }) } })
                        if (index < session.movements.lastIndex) DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; model.update(session.id) { val position = it.movements.indexOfFirst { item -> item.id == movement.id }; if (position < 0 || position >= it.movements.lastIndex) it else it.copy(movements = it.movements.toMutableList().apply { java.util.Collections.swap(this, position, position + 1) }) } })
                        if (index < session.movements.lastIndex) DropdownMenuItem(text = { Text("与下个动作组成超级组") }, onClick = { menu = false; val group = movement.supersetId ?: UUID.randomUUID().toString(); model.update(session.id) { current -> current.copy(movements = current.movements.mapIndexed { i, item -> if (i == index || i == index + 1) item.copy(supersetId = group) else item }) } })
                        if (movement.supersetId != null) DropdownMenuItem(text = { Text("解除超级组") }, onClick = { menu = false; model.update(session.id) { it.copy(movements = it.movements.map { item -> if (item.supersetId == movement.supersetId) item.copy(supersetId = null) else item }) } })
                        DropdownMenuItem(text = { Text(if (movement.singleSide) "关闭左右重量分开记录" else "左右重量分开记录") }, onClick = { menu = false; model.updateMovement(session.id, movement.id) { it.copy(singleSide = !it.singleSide) } })
                        DropdownMenuItem(text = { Text(if (movement.oneSide) "取消单侧计量" else "单侧计量（容量乘二）") }, onClick = { menu = false; model.updateMovement(session.id, movement.id) { it.copy(oneSide = !it.oneSide) } })
                        DropdownMenuItem(text = { Text("移除动作", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            if (movement.notes.isNotBlank()) Text(movement.notes, style = MaterialTheme.typography.bodySmall, color = Color(0xFF68717C))
            if (movement.supersetId != null) Text("同一轮全部动作完成后计入容量；热身组不计容量。", style = MaterialTheme.typography.labelSmall, color = WorkoutBlue)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metricLabel(movement.exercise.metric), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { restEdit = true }, enabled = !readOnly, contentPadding = PaddingValues(0.dp)) { Text("休息 ${movement.restSeconds} 秒", style = MaterialTheme.typography.labelSmall) }
                if (movement.exercise.metric.name == "CARDIO" && !readOnly) TextButton(onClick = { fieldOptions = true }) { Text("记录项") }
            }
            if (previous != null) Text("上次：" + previous.sets.filter { it.isCompleted }.take(5).joinToString(" / ") { set -> if (movement.exercise.metric == WorkoutMetric.DURATION) "${set.durationSeconds ?: 0}秒" else "${set.weightKg?.let(::number) ?: "自重"} × ${set.reps ?: 0}" }, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            movement.sets.forEachIndexed { setIndex, set -> key(set.id) { WorkoutSetRow(model, session, movement, set, setIndex, readOnly, pounds) } }
            if (!readOnly) TextButton(onClick = { model.updateMovement(session.id, movement.id) { item -> val last = item.sets.lastOrNull(); item.copy(sets = item.sets + (last?.copy(id = UUID.randomUUID().toString(), completedAt = null, durationSeconds = null, distanceMeters = null, caloriesKcal = null, recordValues = emptyMap(), rpe = null, comment = "") ?: WorkoutSet())) } }, modifier = Modifier.fillMaxWidth().testTag("workout-add-set")) { Text("＋ 添加一组") }
        }
    }
    if (restEdit) WorkoutTextDialog("组间休息（秒）", movement.restSeconds.toString(), onDismiss = { restEdit = false }, onSave = { value -> model.updateMovement(session.id, movement.id) { it.copy(restSeconds = value.toIntOrNull() ?: -1) }; restEdit = false }, numeric = true)
    if (warmupRestEdit) WorkoutTextDialog("热身组休息（秒）", movement.warmupRestSeconds.toString(), onDismiss = { warmupRestEdit = false }, onSave = { value -> model.updateMovement(session.id, movement.id) { it.copy(warmupRestSeconds = value.toIntOrNull() ?: -1) }; warmupRestEdit = false }, numeric = true)
    if (fieldOptions) CardioFieldsDialog(movement.exercise.recordFieldKeys, onDismiss = { fieldOptions = false }, onSave = { keys -> model.updateMovement(session.id, movement.id) { it.copy(exercise = it.exercise.copy(recordFieldKeys = keys)) }; fieldOptions = false })
}

@Composable private fun WorkoutSetRow(model: WorkoutViewModel, session: WorkoutSession, movement: WorkoutMovement, set: WorkoutSet, index: Int, readOnly: Boolean, pounds: Boolean) {
    var kindMenu by remember { mutableStateOf(false) }; var tabataEdit by remember { mutableStateOf(false) }; var noteEdit by remember { mutableStateOf(false) }; var rpeEdit by remember { mutableStateOf(false) }
    val weightMetric = movement.exercise.metric.name in listOf("WEIGHT_REPS", "BODYWEIGHT_REPS", "ASSISTED_REPS")
    val timeMetric = movement.exercise.metric.name in listOf("DURATION", "CARDIO")
    val metric = movement.exercise.metric.name
    val focusManager = LocalFocusManager.current
    val factor = if (pounds) 2.2046226218 else 1.0
    Column(Modifier.fillMaxWidth().background(if (set.isCompleted) Color(0xFFE8F5EC) else Color(0xFFF4F5F6), RoundedCornerShape(12.dp)).padding(horizontal = 5.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box {
                TextButton(onClick = { kindMenu = true }, enabled = !readOnly, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(34.dp)) { Text(when (set.kind) { WorkoutSetKind.NORMAL -> "${index + 1}"; WorkoutSetKind.WARMUP -> "热"; WorkoutSetKind.DROP -> "递减"; WorkoutSetKind.FAILURE -> "力竭" }, fontSize = 11.sp) }
                DropdownMenu(kindMenu, { kindMenu = false }) { WorkoutSetKind.entries.forEach { kind -> DropdownMenuItem(text = { Text(when (kind) { WorkoutSetKind.NORMAL -> "正式组"; WorkoutSetKind.WARMUP -> "热身组"; WorkoutSetKind.DROP -> "递减组"; WorkoutSetKind.FAILURE -> "力竭组" }) }, onClick = { kindMenu = false; model.updateSet(session.id, movement.id, set.id) { current -> current.copy(kind = kind) } }) } }
            }
            if (weightMetric) {
                NumericInput(set.weightKg?.times(factor), if (metric == "ASSISTED_REPS") "辅助${if (pounds) "lb" else "kg"}" else if (pounds) "lb" else "kg", readOnly, Modifier.weight(1f).testTag("workout-weight")) { value -> model.updateSet(session.id, movement.id, set.id) { current -> current.copy(weightKg = value?.div(factor)) } }
                NumericInput(set.reps?.toDouble(), "次数", readOnly, Modifier.weight(.8f).testTag("workout-reps"), integer = true) { value -> model.updateSet(session.id, movement.id, set.id) { current -> current.copy(reps = value?.toInt()) } }
            } else if (metric == "REPS") NumericInput(set.reps?.toDouble(), "次数", readOnly, Modifier.weight(1f).testTag("workout-reps"), integer = true) { value -> model.updateSet(session.id, movement.id, set.id) { current -> current.copy(reps = value?.toInt()) } }
            else if (timeMetric) NumericInput(set.durationSeconds?.toDouble(), "时长(秒)", readOnly, Modifier.weight(1f).testTag("workout-duration"), integer = true) { value -> model.updateSet(session.id, movement.id, set.id) { current -> current.copy(durationSeconds = value?.toInt()) } }
            else if (metric == "TABATA") TextButton(onClick = { tabataEdit = true }, enabled = !readOnly, modifier = Modifier.weight(1f)) { val config = set.tabata ?: WorkoutTabata(); Text("${config.workSeconds}练 / ${config.restSeconds}休 × ${config.rounds}", fontSize = 12.sp) }
            else Text(set.durationSeconds?.let { "拉伸 $it 秒" } ?: "拉伸完成后勾选", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (metric == "TABATA" && !readOnly && !set.isCompleted) IconButton(onClick = { model.perform { model.repository.startInterval(session.id, movement.id, set.id) } }, enabled = session.status == WorkoutSessionStatus.ACTIVE, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.PlayArrow, "开始Tabata") }
            IconToggleButton(checked = set.isCompleted, onCheckedChange = { focusManager.clearFocus(); model.toggleSet(session.id, movement.id, set) }, enabled = !readOnly && session.status == WorkoutSessionStatus.ACTIVE, modifier = Modifier.size(36.dp).testTag("workout-complete-set")) { Icon(if (set.isCompleted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, "${if (set.isCompleted) "取消完成" else "完成"}第 ${index + 1} 组", tint = if (set.isCompleted) Color(0xFF28A366) else Color.Gray) }
            if (!readOnly) IconButton(onClick = { model.updateMovement(session.id, movement.id) { it.copy(sets = it.sets.filterNot { item -> item.id == set.id }) } }, modifier = Modifier.size(25.dp)) { Icon(Icons.Rounded.Close, "删除第 ${index + 1} 组", modifier = Modifier.size(15.dp), tint = Color.Gray) }
        }
        if (movement.singleSide && weightMetric) Row(Modifier.padding(start = 39.dp, end = 70.dp)) { NumericInput(set.leftWeightKg?.times(factor), "左侧${if (pounds) "lb" else "kg"}", readOnly, Modifier.fillMaxWidth()) { value -> model.updateSet(session.id, movement.id, set.id) { current -> current.copy(leftWeightKg = value?.div(factor)) } } }
        if (metric == "CARDIO") {
            val keys = movement.exercise.recordFieldKeys.ifEmpty { listOf("distance", "kcal", "bpm") }
            keys.chunked(2).forEach { rowKeys -> Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { rowKeys.forEach { key ->
                WorkoutRecordInput(set.recordValues[key].orEmpty(), CardioFields[key] ?: key, readOnly, Modifier.weight(1f).testTag("workout-cardio-$key"), key == "pace") { value -> model.updateSet(session.id, movement.id, set.id) { current -> current.copy(recordValues = current.recordValues + (key to value)) } }
            } } }
        }
        if (set.kind == WorkoutSetKind.DROP && weightMetric) {
            set.dropSegments.forEachIndexed { dropIndex, drop -> Row(Modifier.padding(start = 28.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("↳${dropIndex + 1}", style = MaterialTheme.typography.labelSmall)
                NumericInput(drop.weightKg?.times(factor), if (pounds) "lb" else "kg", readOnly, Modifier.weight(1f)) { value -> model.updateSet(session.id, movement.id, set.id) { current -> current.copy(dropSegments = current.dropSegments.mapIndexed { i, item -> if (i == dropIndex) item.copy(weightKg = value?.div(factor)) else item }) } }
                NumericInput(drop.reps?.toDouble(), "次数", readOnly, Modifier.weight(1f), true) { value -> model.updateSet(session.id, movement.id, set.id) { current -> current.copy(dropSegments = current.dropSegments.mapIndexed { i, item -> if (i == dropIndex) item.copy(reps = value?.toInt()) else item }) } }
                if (movement.singleSide) NumericInput(drop.leftWeightKg?.times(factor), "左侧", readOnly, Modifier.weight(1f)) { value -> model.updateSet(session.id, movement.id, set.id) { current -> current.copy(dropSegments = current.dropSegments.mapIndexed { i, item -> if (i == dropIndex) item.copy(leftWeightKg = value?.div(factor)) else item }) } }
                if (!readOnly) IconButton(onClick = { model.updateSet(session.id, movement.id, set.id) { current -> current.copy(dropSegments = current.dropSegments.filterIndexed { i, _ -> i != dropIndex }) } }, modifier = Modifier.size(25.dp)) { Icon(Icons.Rounded.Close, "删除递减段") }
            } }
            if (!readOnly) TextButton(onClick = { model.updateSet(session.id, movement.id, set.id) { current -> current.copy(dropSegments = current.dropSegments + WorkoutDropSegment()) } }) { Text("＋ 递减段") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (metric != "TABATA" && !readOnly && !set.isCompleted) IconButton(onClick = { model.perform { model.repository.startSetTimer(session.id, movement.id, set.id) } }, enabled = session.status == WorkoutSessionStatus.ACTIVE, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Timer, "计时第 ${index + 1} 组", modifier = Modifier.size(18.dp)) }
            if (set.durationSeconds != null && weightMetric) Text("${set.durationSeconds} 秒", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { rpeEdit = true }, enabled = !readOnly, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) { Text(set.rpe?.let { "RPE ${number(it)}" } ?: "RPE", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { noteEdit = true }, enabled = !readOnly, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(if (set.comment.isBlank()) "本组心得" else set.comment, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
        }
    }
    if (tabataEdit) TabataDialog(set.tabata ?: WorkoutTabata(), onDismiss = { tabataEdit = false }, onSave = { model.updateSet(session.id, movement.id, set.id) { current -> current.copy(tabata = it) }; tabataEdit = false })
    if (rpeEdit) WorkoutTextDialog("RPE（0～10，可填小数）", set.rpe?.let(::number).orEmpty(), onDismiss = { rpeEdit = false }, onSave = { value -> model.updateSet(session.id, movement.id, set.id) { it.copy(rpe = if (value.isBlank()) null else value.toDoubleOrNull() ?: -1.0) }; rpeEdit = false }, numeric = true)
    if (noteEdit) WorkoutTextDialog("第 ${index + 1} 组心得", set.comment, onDismiss = { noteEdit = false }, onSave = { value -> model.updateSet(session.id, movement.id, set.id) { it.copy(comment = value) }; noteEdit = false })
}

@Composable internal fun NumericInput(value: Double?, label: String, readOnly: Boolean, modifier: Modifier = Modifier, integer: Boolean = false, onValue: (Double?) -> Unit) {
    var text by remember { mutableStateOf(value?.let { if (integer) it.toInt().toString() else number(it) }.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value, focused) { if (!focused) text = value?.let { if (integer) it.toInt().toString() else number(it) }.orEmpty() }
    OutlinedTextField(text, { next -> if (next.length <= 12 && next.count { it == '.' } <= 1 && next.all { it.isDigit() || !integer && it == '.' } && (!integer || next.isEmpty() || (next.toLongOrNull() ?: Long.MAX_VALUE) <= Int.MAX_VALUE)) { text = next; dirty = true } }, label = { Text(label, fontSize = 10.sp) }, readOnly = readOnly, singleLine = true, modifier = modifier.onFocusChanged { state ->
        if (focused && !state.isFocused && dirty) { dirty = false; onValue(text.toDoubleOrNull()) }; focused = state.isFocused
    }, keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(9.dp))
}

@Composable internal fun WorkoutRecordInput(value: String, label: String, readOnly: Boolean, modifier: Modifier, pace: Boolean, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }; var focused by remember { mutableStateOf(false) }; var dirty by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value, focused) { if (!focused) text = value }
    OutlinedTextField(text, { text = it.take(30); dirty = true }, readOnly = readOnly, label = { Text(label, fontSize = 10.sp) }, singleLine = true,
        modifier = modifier.onFocusChanged { state -> if (focused && !state.isFocused && dirty) { dirty = false; onCommit(text) }; focused = state.isFocused },
        keyboardOptions = KeyboardOptions(keyboardType = if (pace) KeyboardType.Text else KeyboardType.Decimal, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
}

@Composable internal fun WorkoutBottomAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = action).padding(horizontal = 16.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, title, tint = if (title == "加动作") WorkoutBlue else Color(0xFF62676C), modifier = Modifier.size(27.dp)); Text(title, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
}

@Composable internal fun WorkoutTextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, numeric: Boolean = false) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(text, { text = it.take(20_000) }, modifier = Modifier.fillMaxWidth().testTag("workout-dialog-input"), minLines = if (numeric) 1 else 2, maxLines = 8, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text)) }, confirmButton = { TextButton(onClick = { onSave(text) }, modifier = Modifier.testTag("workout-dialog-save")) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal val CardioFields = linkedMapOf("distance" to "距离(km)", "kcal" to "消耗(kcal)", "bpm" to "平均心率", "count" to "次数", "steps" to "步数", "floors" to "楼层", "laps" to "圈数", "poolLength" to "泳池长度(米)", "rounds" to "轮数", "cadence" to "步频", "strokeRate" to "划频", "avgSpeed" to "均速(km/h)", "pace" to "配速(分:秒)", "elevationGain" to "爬升(米)", "workSeconds" to "训练(秒)", "restSeconds" to "休息(秒)")

@Composable internal fun CardioFieldsDialog(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var selected by remember { mutableStateOf(initial.ifEmpty { listOf("distance", "kcal", "bpm") }) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("有氧记录项") }, text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) { CardioFields.forEach { (key, label) -> Row(Modifier.fillMaxWidth().clickable { selected = if (key in selected) selected - key else selected + key }, verticalAlignment = Alignment.CenterVertically) { Checkbox(key in selected, { selected = if (key in selected) selected - key else selected + key }); Text(label) } } } }, confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable internal fun TabataDialog(initial: WorkoutTabata, onDismiss: () -> Unit, onSave: (WorkoutTabata) -> Unit) {
    var prepare by remember { mutableStateOf(initial.prepareSeconds.toString()) }; var work by remember { mutableStateOf(initial.workSeconds.toString()) }; var rest by remember { mutableStateOf(initial.restSeconds.toString()) }; var rounds by remember { mutableStateOf(initial.rounds.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Tabata 阶段设置") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("准备(秒)" to (prepare to { v: String -> prepare = v }), "训练(秒)" to (work to { v: String -> work = v }), "休息(秒)" to (rest to { v: String -> rest = v }), "轮数" to (rounds to { v: String -> rounds = v })).forEach { (label, state) ->
                OutlinedTextField(state.first, { if (it.all(Char::isDigit) && it.length < 6) state.second(it) }, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(WorkoutTabata(prepare.toIntOrNull() ?: 0, work.toIntOrNull() ?: 0, rest.toIntOrNull() ?: 0, rounds.toIntOrNull() ?: 0)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun formatWorkoutTime(millis: Long): String { val seconds = (millis.coerceAtLeast(0) + 999) / 1000; return if (seconds >= 3600) "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60) }
internal fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
