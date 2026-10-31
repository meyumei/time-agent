package com.timeagent.workout

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.core.workout.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal val WorkoutBlue = Color(0xFF0088FF)
internal val WorkoutBackground = Color(0xFFF3F4F5)
internal val WorkoutMuscles = listOf("全部", "置顶", "胸", "背", "腿", "肩", "前锯肌", "斜方肌", "二头", "三头", "小腿", "前臂", "颈部", "臀部", "功能性", "核心稳定", "腹部", "热身动作", "拉伸", "有氧", "组合", "全身", "计时动作", "Tabata", "自重")

internal fun anatomyMuscle(exercise: ExerciseDefinition): String = exercise.primaryMuscles.firstOrNull { it in setOf("胸", "背", "腿", "肩", "前锯肌", "斜方肌", "二头", "三头", "小腿", "前臂", "颈部", "臀部", "核心稳定", "腹部") }
    ?: exercise.primaryMuscles.firstOrNull { it !in WorkoutMuscles }.orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkoutExercisePicker(
    model: WorkoutViewModel,
    selecting: Boolean,
    onBack: () -> Unit,
    onPicked: (List<ExerciseDefinition>) -> Unit,
    onAnatomy: (String) -> Unit,
) {
    val exercises by model.catalog.exercises.collectAsStateWithLifecycle()
    val pinned by model.catalog.pinned.collectAsStateWithLifecycle()
    val subfilters by model.catalog.subfilters.collectAsStateWithLifecycle()
    val relatedKeys by model.catalog.relatedKeys.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf("全部") }
    var subpart by rememberSaveable { mutableStateOf<String?>(null) }
    var equipment by rememberSaveable { mutableStateOf("全部") }
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    var detail by remember { mutableStateOf<ExerciseDefinition?>(null) }
    var custom by remember { mutableStateOf(false) }
    val equipmentTypes = remember(exercises) { listOf("全部") + exercises.map { it.equipment }.distinct().filter { it.isNotBlank() } }
    val filtered = remember(exercises, pinned, query, muscle, subpart, equipment, relatedKeys) {
        exercises.filter { exercise ->
            (muscle == "全部" || muscle == "置顶" && exercise.id in pinned || exercise.primaryMuscles.any { it.contains(muscle) || muscle.contains(it) }) &&
                (subpart == null || subpart in relatedKeys[exercise.id].orEmpty()) &&
                (equipment == "全部" || exercise.equipment == equipment) &&
                (query.isBlank() || (exercise.name + exercise.primaryMuscles.joinToString() + exercise.equipment).contains(query.trim(), ignoreCase = true))
        }.sortedByDescending { it.id in pinned }
    }
    Scaffold(containerColor = WorkoutBackground, topBar = {
        Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回训练") }
                OutlinedTextField(query, { query = it.take(200) }, modifier = Modifier.weight(1f).testTag("workout-exercise-search"), placeholder = { Text("输入动作名字搜索") }, singleLine = true, shape = RoundedCornerShape(30.dp), leadingIcon = { Icon(Icons.Rounded.Search, null) })
                IconButton(onClick = { custom = true }) { Icon(Icons.Rounded.Add, "自定义动作", tint = WorkoutBlue) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${filtered.size} 个动作", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onAnatomy(muscle.takeUnless { it == "全部" || it == "置顶" } ?: "") }) { Text("3D 肌群", color = WorkoutBlue) }
            }
        }
    }, bottomBar = {
        if (selecting) Surface(shadowElevation = 8.dp) {
            Row(Modifier.navigationBarsPadding().fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { chosen = emptyList() }, modifier = Modifier.weight(1f)) { Text("清空选择") }
                Button(onClick = { onPicked(chosen.mapNotNull { id -> exercises.firstOrNull { it.id == id } }) }, enabled = chosen.isNotEmpty(), modifier = Modifier.weight(1f).testTag("workout-picker-done")) { Text("完成（${chosen.size}）") }
            }
        }
    }) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.width(86.dp).fillMaxHeight().background(Color(0xFFEFF0F2))) {
                items(WorkoutMuscles) { name ->
                    Column {
                        Box(Modifier.fillMaxWidth().background(if (muscle == name) Color.White else Color.Transparent).clickable { muscle = name; subpart = null }.padding(horizontal = 12.dp, vertical = 17.dp)) {
                            Text(name, color = if (muscle == name) WorkoutBlue else Color(0xFF72777E), fontWeight = if (muscle == name) FontWeight.Bold else FontWeight.Normal)
                        }
                        if (muscle == name) subfilters[name].orEmpty().forEach { part ->
                            Text(part.label, style = MaterialTheme.typography.labelMedium, color = if (subpart == part.key) WorkoutBlue else Color(0xFF72777E), modifier = Modifier.fillMaxWidth().clickable { subpart = if (subpart == part.key) null else part.key }.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(equipmentTypes.size) { index -> val name = equipmentTypes[index]; FilterChip(selected = equipment == name, onClick = { equipment = name }, label = { Text(name) }) }
                }
                if (filtered.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(if (exercises.isEmpty()) "正在读取本地动作库…" else "没有匹配动作，可以调整筛选或添加自定义动作") }
                LazyVerticalGrid(GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { exercise ->
                        Surface(shape = RoundedCornerShape(20.dp), color = if (exercise.id in chosen) Color(0xFFE3F2FF) else Color.White, modifier = Modifier.fillMaxWidth().testTag("workout-exercise-${exercise.id}").clickable { if (selecting) chosen = if (exercise.id in chosen) chosen - exercise.id else chosen + exercise.id else detail = exercise }) {
                            Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box {
                                    ExercisePicture(exercise, Modifier.fillMaxWidth().height(108.dp))
                                    if (exercise.id in chosen) Icon(Icons.Rounded.CheckCircle, "已选择", tint = WorkoutBlue, modifier = Modifier.align(Alignment.TopEnd).size(22.dp))
                                }
                                Text(exercise.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.heightIn(min = 40.dp))
                                Text(exercise.equipment, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                TextButton(onClick = { detail = exercise }, contentPadding = PaddingValues(0.dp)) { Text("查看动作要点", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                }
            }
        }
    }
    detail?.let { exercise ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(exercise.name) }, text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExercisePicture(exercise, Modifier.fillMaxWidth().height(200.dp))
                Text("主肌群：${exercise.primaryMuscles.joinToString("、")}")
                if (exercise.secondaryMuscles.isNotEmpty()) Text("辅助肌群：${exercise.secondaryMuscles.joinToString("、")}")
                Text("${exercise.equipment} · ${metricLabel(exercise.metric)}", style = MaterialTheme.typography.labelLarge)
                Text(exercise.instructions.ifBlank { "此动作的本地数据没有文字要点，可查看动图或补充自定义笔记。" })
                TextButton(onClick = { detail = null; onAnatomy(anatomyMuscle(exercise)) }) { Text("在 3D 模型中查看肌群") }
            }
        }, confirmButton = { TextButton(onClick = {
            if (selecting) { chosen = (chosen + exercise.id).distinct(); detail = null } else { detail = null; onPicked(listOf(exercise)) }
        }) { Text(if (selecting) "加入选择" else "以此开始训练") } }, dismissButton = { TextButton(onClick = { model.togglePinned(exercise.id) }) { Text(if (exercise.id in pinned) "取消置顶" else "置顶动作") } })
    }
    if (custom) CustomExerciseDialog(onDismiss = { custom = false }, onSave = { model.saveCustom(it); custom = false })
}

@Composable internal fun ExercisePicture(exercise: ExerciseDefinition, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val asset = exercise.animationAsset
    val drawable by produceState<Drawable?>(null, asset) {
        value = withContext(Dispatchers.IO) {
            if (asset.isNullOrBlank()) null else try {
                if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, asset))
                else if (asset.endsWith(".gif", ignoreCase = true)) context.assets.open(asset).use { stream -> android.graphics.Movie.decodeStream(stream)?.let(::WorkoutGifDrawable) }
                else context.assets.open(asset).use { android.graphics.drawable.BitmapDrawable(context.resources, BitmapFactory.decodeStream(it)) }
            } catch (_: Exception) { null }
        }
    }
    DisposableEffect(drawable) { onDispose { (drawable as? Animatable)?.stop(); drawable?.callback = null } }
    if (drawable == null) Box(modifier, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FitnessCenter, exercise.name, tint = WorkoutBlue.copy(alpha = .45f), modifier = Modifier.size(40.dp)) }
    else AndroidView(factory = { ImageView(it).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = exercise.name
        viewTreeObserver.addOnWindowFocusChangeListener { focused -> (this.drawable as? Animatable)?.let { animation -> if (focused) animation.start() else animation.stop() } }
    } }, update = { it.setImageDrawable(drawable); (drawable as? Animatable)?.let { animation -> if (it.hasWindowFocus()) animation.start() else animation.stop() } }, modifier = modifier)
}

@Composable private fun CustomExerciseDialog(onDismiss: () -> Unit, onSave: (ExerciseDefinition) -> Unit) {
    var name by remember { mutableStateOf("") }; var muscle by remember { mutableStateOf("胸") }; var equipment by remember { mutableStateOf("") }; var metric by remember { mutableStateOf(WorkoutMetric.WEIGHT_REPS) }; var instructions by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义动作") }, text = {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(200) }, label = { Text("动作名称") })
            OutlinedTextField(muscle, { muscle = it }, label = { Text("主要肌群") })
            OutlinedTextField(equipment, { equipment = it }, label = { Text("器械") })
            Text("记录类型")
            WorkoutMetric.entries.forEach { type -> Row(Modifier.fillMaxWidth().clickable { metric = type }, verticalAlignment = Alignment.CenterVertically) { RadioButton(metric == type, { metric = type }); Text(metricLabel(type)) } }
            OutlinedTextField(instructions, { instructions = it }, label = { Text("动作要点") })
        }
    }, confirmButton = { TextButton(onClick = { onSave(ExerciseDefinition("custom:${UUID.randomUUID()}", name.trim(), listOf(muscle.trim()), equipment = equipment.trim(), metric = metric, instructions = instructions)) }, enabled = name.isNotBlank() && muscle.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun metricLabel(metric: WorkoutMetric): String = when (metric.name) {
    "WEIGHT_REPS" -> "重量 × 次数"; "REPS" -> "次数"; "DURATION" -> "计时"; "BODYWEIGHT_REPS" -> "自身加重"; "ASSISTED_REPS" -> "自重辅助"; "CARDIO" -> "有氧组合记录"; "STRETCH" -> "拉伸"; "TABATA" -> "Tabata 间歇"; else -> metric.name
}
