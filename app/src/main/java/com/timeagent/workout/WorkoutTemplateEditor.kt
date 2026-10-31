package com.timeagent.workout

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timeagent.core.workout.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkoutTemplateEditor(draft: WorkoutTemplate, busy: Boolean, pounds: Boolean,
    onChange: ((WorkoutTemplate) -> WorkoutTemplate) -> Unit, onBack: () -> Unit, onAdd: () -> Unit, onSave: () -> Unit) {
    val focus = LocalFocusManager.current
    fun movement(id: String, transform: (WorkoutMovement) -> WorkoutMovement) { onChange { current -> current.copy(movements = current.movements.map { if (it.id == id) transform(it) else it }) } }
    fun reorder(id: String, delta: Int) { onChange { current ->
        val index = current.movements.indexOfFirst { it.id == id }
        if (index < 0 || index + delta !in current.movements.indices) current
        else current.copy(movements = current.movements.toMutableList().apply { java.util.Collections.swap(this, index, index + delta) })
    } }
    Scaffold(containerColor = WorkoutBackground, topBar = { TopAppBar(title = { Text("训练模板") }, navigationIcon = { IconButton(onClick = { focus.clearFocus(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } }, actions = {
        TextButton(onClick = { focus.clearFocus(); onSave() }, enabled = !busy && draft.movements.isNotEmpty(), modifier = Modifier.testTag("workout-save-template")) { Text("保存") }
    }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(draft.title, { value -> onChange { it.copy(title = value.take(200)) } }, label = { Text("模板名称") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(draft.notes, { value -> onChange { it.copy(notes = value.take(20_000)) } }, label = { Text("训练说明") }, modifier = Modifier.fillMaxWidth()) }
            itemsIndexed(draft.movements, key = { _, item -> item.id }) { index, item ->
                var menu by remember { mutableStateOf(false) }; var note by remember { mutableStateOf(false) }; var cardio by remember { mutableStateOf(false) }
                fun setValue(setId: String, transform: (WorkoutSet) -> WorkoutSet) { movement(item.id) { move -> move.copy(sets = move.sets.map { if (it.id == setId) transform(it) else it }) } }
                val weighted = item.exercise.metric in listOf(WorkoutMetric.WEIGHT_REPS, WorkoutMetric.BODYWEIGHT_REPS, WorkoutMetric.ASSISTED_REPS)
                val factor = if (pounds) 2.2046226218 else 1.0
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExercisePicture(item.exercise, Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) { Text(item.exercise.name, fontWeight = FontWeight.Bold); if (item.supersetId != null) Text("超级组", color = WorkoutBlue, style = MaterialTheme.typography.labelSmall) }
                        Box {
                            IconButton(onClick = { focus.clearFocus(); menu = true }) { Icon(Icons.Rounded.MoreVert, "模板动作设置：${item.exercise.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                if (index > 0) DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; reorder(item.id, -1) })
                                if (index < draft.movements.lastIndex) {
                                    DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; reorder(item.id, 1) })
                                    DropdownMenuItem(text = { Text("与下个动作组成超级组") }, onClick = { menu = false; onChange { current ->
                                        val position = current.movements.indexOfFirst { it.id == item.id }
                                        if (position < 0 || position >= current.movements.lastIndex) current else {
                                            val group = current.movements[position].supersetId ?: UUID.randomUUID().toString()
                                            val other = current.movements[position + 1].supersetId
                                            current.copy(movements = current.movements.mapIndexed { i, move -> if (i == position || i == position + 1 || other != null && move.supersetId == other) move.copy(supersetId = group) else move })
                                        }
                                    } })
                                }
                                if (item.supersetId != null) DropdownMenuItem(text = { Text("解除超级组") }, onClick = { menu = false; onChange { current -> current.copy(movements = current.movements.map { if (it.supersetId == item.supersetId) it.copy(supersetId = null) else it }) } })
                                if (weighted) {
                                    DropdownMenuItem(text = { Text(if (item.singleSide) "关闭左右重量分开记录" else "左右重量分开记录") }, onClick = { menu = false; movement(item.id) { it.copy(singleSide = !it.singleSide) } })
                                    DropdownMenuItem(text = { Text(if (item.oneSide) "取消单侧计量" else "单侧计量（容量乘二）") }, onClick = { menu = false; movement(item.id) { it.copy(oneSide = !it.oneSide) } })
                                }
                                DropdownMenuItem(text = { Text("动作笔记") }, onClick = { menu = false; note = true })
                                DropdownMenuItem(text = { Text("移除动作") }, onClick = { menu = false; onChange { current -> current.copy(movements = current.movements.filterNot { it.id == item.id }) } })
                            }
                        }
                    }
                    if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumericInput(item.restSeconds.toDouble(), "正式休息(秒)", false, Modifier.weight(1f), true) { value -> movement(item.id) { it.copy(restSeconds = value?.toInt() ?: 0) } }
                        NumericInput(item.warmupRestSeconds.toDouble(), "热身休息(秒)", false, Modifier.weight(1f), true) { value -> movement(item.id) { it.copy(warmupRestSeconds = value?.toInt() ?: 0) } }
                    }
                    if (item.exercise.metric == WorkoutMetric.CARDIO) TextButton(onClick = { cardio = true }) { Text("有氧记录项：${item.exercise.recordFieldKeys.ifEmpty { listOf("distance", "kcal", "bpm") }.mapNotNull { CardioFields[it] }.joinToString(" / ")}") }
                    item.sets.forEachIndexed { setIndex, set -> key(set.id) {
                        var kind by remember { mutableStateOf(false) }; var stage by remember { mutableStateOf(false) }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box {
                                    TextButton(onClick = { kind = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(40.dp)) { Text(if (set.kind == WorkoutSetKind.NORMAL) "${setIndex + 1}" else workoutKindLabel(set.kind)) }
                                    DropdownMenu(kind, { kind = false }) { WorkoutSetKind.entries.forEach { value -> DropdownMenuItem(text = { Text(workoutKindLabel(value)) }, onClick = { kind = false; setValue(set.id) { it.copy(kind = value) } }) } }
                                }
                                if (weighted) NumericInput(set.weightKg?.times(factor), if (pounds) "lb" else "kg", false, Modifier.weight(1f)) { value -> setValue(set.id) { it.copy(weightKg = value?.div(factor)) } }
                                when {
                                    weighted || item.exercise.metric == WorkoutMetric.REPS -> NumericInput(set.reps?.toDouble(), "次数", false, Modifier.weight(1f), true) { value -> setValue(set.id) { it.copy(reps = value?.toInt()) } }
                                    item.exercise.metric in listOf(WorkoutMetric.DURATION, WorkoutMetric.CARDIO, WorkoutMetric.STRETCH) -> NumericInput(set.durationSeconds?.toDouble(), "目标秒数(可空)", false, Modifier.weight(1f), true) { value -> setValue(set.id) { it.copy(durationSeconds = value?.toInt()) } }
                                    item.exercise.metric == WorkoutMetric.TABATA -> TextButton(onClick = { stage = true }, modifier = Modifier.weight(1f).testTag("workout-template-tabata")) { val config = set.tabata ?: WorkoutTabata(); Text("${config.workSeconds}练/${config.restSeconds}休 × ${config.rounds}") }
                                    else -> Text(metricLabel(item.exercise.metric), modifier = Modifier.weight(1f))
                                }
                                IconButton(onClick = { movement(item.id) { it.copy(sets = it.sets.filterNot { it.id == set.id }) } }, modifier = Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "移除组") }
                            }
                            if (weighted && item.singleSide) NumericInput(set.leftWeightKg?.times(factor), "左侧 ${if (pounds) "lb" else "kg"}", false) { value -> setValue(set.id) { it.copy(leftWeightKg = value?.div(factor)) } }
                            if (weighted && set.kind == WorkoutSetKind.DROP) {
                                set.dropSegments.forEachIndexed { dropIndex, drop ->
                                    fun dropValue(transform: (WorkoutDropSegment) -> WorkoutDropSegment) { setValue(set.id) { current -> current.copy(dropSegments = current.dropSegments.mapIndexed { i, part -> if (i == dropIndex) transform(part) else part }) } }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("↳")
                                        NumericInput(drop.weightKg?.times(factor), if (pounds) "lb" else "kg", false, Modifier.weight(1f)) { value -> dropValue { it.copy(weightKg = value?.div(factor)) } }
                                        if (item.singleSide) NumericInput(drop.leftWeightKg?.times(factor), "左侧", false, Modifier.weight(1f)) { value -> dropValue { it.copy(leftWeightKg = value?.div(factor)) } }
                                        NumericInput(drop.reps?.toDouble(), "次数", false, Modifier.weight(1f), true) { value -> dropValue { it.copy(reps = value?.toInt()) } }
                                        IconButton(onClick = { setValue(set.id) { it.copy(dropSegments = it.dropSegments.filterIndexed { i, _ -> i != dropIndex }) } }, modifier = Modifier.size(24.dp)) { Icon(Icons.Rounded.Close, "移除递减段") }
                                    }
                                }
                                TextButton(onClick = { setValue(set.id) { it.copy(dropSegments = it.dropSegments + WorkoutDropSegment()) } }) { Text("＋ 递减段") }
                            }
                        }
                        if (stage) TabataDialog(set.tabata ?: WorkoutTabata(), onDismiss = { stage = false }, onSave = { value -> setValue(set.id) { it.copy(tabata = value) }; stage = false })
                    } }
                    TextButton(onClick = { focus.clearFocus(); movement(item.id) { it.copy(sets = it.sets + WorkoutSet(tabata = if (item.exercise.metric == WorkoutMetric.TABATA) WorkoutTabata() else null)) } }) { Text("＋ 添加一组") }
                } }
                if (note) WorkoutTextDialog("动作笔记", item.notes, onDismiss = { note = false }, onSave = { value -> movement(item.id) { it.copy(notes = value) }; note = false })
                if (cardio) CardioFieldsDialog(item.exercise.recordFieldKeys, onDismiss = { cardio = false }, onSave = { value -> movement(item.id) { it.copy(exercise = it.exercise.copy(recordFieldKeys = value)) }; cardio = false })
            }
            item { Button(onClick = { focus.clearFocus(); onAdd() }, modifier = Modifier.fillMaxWidth()) { Text("添加动作") } }
        }
    }
}

internal fun workoutKindLabel(kind: WorkoutSetKind): String = when (kind) {
    WorkoutSetKind.NORMAL -> "正式组"; WorkoutSetKind.WARMUP -> "热身组"; WorkoutSetKind.DROP -> "递减组"; WorkoutSetKind.FAILURE -> "力竭组"
}
