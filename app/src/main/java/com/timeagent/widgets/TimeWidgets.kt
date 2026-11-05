package com.timeagent.widgets

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.*
import androidx.glance.layout.*
import androidx.glance.text.*
import com.timeagent.MainActivity
import com.timeagent.R
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.di.AppEntryPoint
import com.timeagent.domain.command.*
import com.timeagent.quickentry.*
import com.timeagent.timer.MainTimerNotice
import com.timeagent.timer.TimerNotifications
import com.timeagent.ui.ThemePreference
import com.timeagent.ui.design.PresentationMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class WidgetData(
    val slot: MainTimerNotice?, val plans: List<ScheduleBlockEntity>, val deadlines: Int,
    val entries: List<QuickEntryEntity>, val companion: Boolean, val error: String?,
    val avatar: Bitmap?, val zone: ZoneId,
    val taskTitles: Map<String, String>,
    val activeEntryId: String?,
    val recentTimerEntry: QuickEntryEntity?,
)

private suspend fun loadWidget(context: Context): WidgetData {
    val repo = AppEntryPoint.from(context).timeRepository()
    return repo.transaction {
        val now = repo.now().toEpochMilli()
        val today = repo.now().atZone(repo.zone).toLocalDate()
        val start = today.atStartOfDay(repo.zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(repo.zone).toInstant().toEpochMilli()
        val dao = repo.database.dao()
        val companion = ThemePreference(context).readAll().presentation == PresentationMode.COMPANION
        val tasks = dao.tasks()
        val slot = TimerNotifications.read(repo, context)
        val recordId = slot?.let { if (it.kind == "FOCUS") "focus:${it.id}" else it.id }
        val activeEntryId = recordId?.let { repo.database.entryContextDao().find("RECORD", it)?.quickEntryId }
        val entries = repo.database.quickEntryDao().entries().sortedWith(
            compareByDescending<QuickEntryEntity> { it.id == activeEntryId }
                .thenByDescending { it.pinned }.thenByDescending { it.usageCount }.thenByDescending { it.lastUsedAt })
        WidgetData(slot,
            dao.activePlans().filter { it.startAt >= now && it.startAt < end }.sortedBy { it.startAt }.take(3),
            tasks.count { it.deletedAt == null && it.status !in setOf("COMPLETED", "CANCELLED") && it.dueAt?.let { due -> due in start until end } == true },
            entries.take(4),
            companion,
            context.getSharedPreferences("widget_status", 0).getString("error", null),
            if (companion) fishAvatar(context) else null, repo.zone, tasks.associate { it.id to it.title },
            activeEntryId,
            entries.filter { it.actionType in listOf(QuickEntryAction.START_TIMER, QuickEntryAction.START_FOCUS) }
                .maxWithOrNull(compareBy<QuickEntryEntity> { it.lastUsedAt ?: Long.MIN_VALUE }
                    .thenBy { it.usageCount }.thenByDescending { it.sortOrder }))
    }
}

private fun fishAvatar(context: Context): Bitmap {
    val resource = if (context.getSharedPreferences("fish_companion", 0).getString("appearance", "CHIBI") == "ELEGANT")
        R.drawable.fish_elegant_atlas else R.drawable.fish_chibi_atlas
    val atlas = BitmapFactory.decodeResource(context.resources, resource,
        BitmapFactory.Options().apply { inScaled = false })
    val cell = atlas.width / 4
    return Bitmap.createScaledBitmap(Bitmap.createBitmap(atlas, 0, 0, cell, cell), 72, 72, true)
}

private fun open(context: Context, destination: String) = actionStartActivity(
    Intent(context, MainActivity::class.java).putExtra("appDestination", destination)
        .setData(android.net.Uri.parse("timeagent://widget/${android.net.Uri.encode(destination)}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))

@Composable
private fun WidgetTitle(context: Context, data: WidgetData, title: String) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (data.companion) Image(ImageProvider(requireNotNull(data.avatar)), "大肥鱼",
            GlanceModifier.size(24.dp).clickable(open(context, "fish-chat")))
        Text(title, GlanceModifier.defaultWeight(), style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold))
        Text("+", GlanceModifier.padding(12.dp).clickable(open(context, "new-schedule")),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 22.sp))
    }
}

@Composable
private fun TimerArea(context: Context, data: WidgetData, glanceId: GlanceId) {
    val slot = data.slot
    if (slot == null) {
        val recent = data.recentTimerEntry
        val action = if (recent == null) open(context, "quick-entries") else startEntryAction(recent, glanceId)
        Text(context.getString(if (recent == null) R.string.widget_configure_timer else R.string.widget_start_recent_timer),
            GlanceModifier.fillMaxWidth().padding(8.dp).clickable(action),
            style = TextStyle(color = GlanceTheme.colors.primary))
    } else {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight().clickable(open(context, slot.destination))) {
                Text(slot.label, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface))
                Text("${Instant.ofEpochMilli(slot.startedAt).atZone(data.zone).format(DateTimeFormatter.ofPattern("HH:mm"))} 开始",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                val views = RemoteViews(context.packageName, R.layout.widget_chronometer)
                views.setChronometer(R.id.widget_chronometer, SystemClock.elapsedRealtime() - slot.elapsedMs, null, slot.running)
                if (!slot.running) views.setTextViewText(R.id.widget_chronometer, "${slot.elapsedMs / 60_000} 分钟 · 已暂停")
                AndroidRemoteViews(views)
            }
            Text("停止", GlanceModifier.padding(10.dp).clickable(actionRunCallback<StopWidgetTimer>(actionParametersOf(
                WidgetKeys.kind to slot.kind, WidgetKeys.id to slot.id, WidgetKeys.version to slot.version,
                WidgetKeys.request to "widget-stop:$glanceId:${slot.kind}:${slot.id}:${slot.version}"))),
                style = TextStyle(color = GlanceTheme.colors.primary))
        }
    }
}

class TodayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(androidx.compose.ui.unit.DpSize(140.dp, 140.dp),
        androidx.compose.ui.unit.DpSize(300.dp, 160.dp), androidx.compose.ui.unit.DpSize(300.dp, 300.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidget(context)
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).padding(12.dp)) {
                    WidgetTitle(context, data, "今天")
                    TimerArea(context, data, id)
                    if (data.plans.isEmpty()) Text("今天没有安排 · 点按添加",
                        GlanceModifier.padding(vertical = 8.dp).clickable(open(context, "new-schedule")),
                        style = TextStyle(color = GlanceTheme.colors.onSurface))
                    data.plans.forEach { plan ->
                        val label = Instant.ofEpochMilli(plan.startAt).atZone(data.zone).format(DateTimeFormatter.ofPattern("HH:mm"))
                        Text("$label  ${plan.title.ifBlank { data.taskTitles[plan.taskId] ?: "日程" }}",
                            GlanceModifier.fillMaxWidth().padding(vertical = 6.dp).clickable(open(context, "item:PLAN:${plan.id}")),
                            maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface))
                    }
                    Text("今日截止 ${data.deadlines} 项", GlanceModifier.padding(top = 6.dp).clickable(open(context, "deadlines")),
                        style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                    WidgetError(context, data.error)
                }
            }
        }
    }
}

class QuickTimerWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(androidx.compose.ui.unit.DpSize(140.dp, 60.dp), androidx.compose.ui.unit.DpSize(300.dp, 60.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidget(context)
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).padding(8.dp)) {
                    if (data.entries.isEmpty() && data.slot != null) TimerArea(context, data, id)
                    else if (data.entries.isEmpty()) Text("设置快捷记录", GlanceModifier.fillMaxSize().clickable(open(context, "quick-entries")),
                        style = TextStyle(color = GlanceTheme.colors.primary))
                    else Row(GlanceModifier.fillMaxWidth()) {
                        val unlistedTimer = data.slot?.takeIf { data.entries.none { entry -> entry.id == data.activeEntryId } }
                        if (unlistedTimer != null) Text("停止 · ${unlistedTimer.label}",
                            GlanceModifier.defaultWeight().padding(8.dp).clickable(
                                actionRunCallback<StopWidgetTimer>(actionParametersOf(
                                    WidgetKeys.kind to unlistedTimer.kind, WidgetKeys.id to unlistedTimer.id,
                                    WidgetKeys.version to unlistedTimer.version,
                                    WidgetKeys.request to "widget-stop:$id:${unlistedTimer.kind}:${unlistedTimer.id}:${unlistedTimer.version}"))),
                            maxLines = 2, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp))
                        data.entries.take(if (unlistedTimer == null) 4 else 3).forEach { entry ->
                            val running = entry.id == data.activeEntryId
                            val slot = data.slot
                            val action = if (running && slot != null) {
                                actionRunCallback<StopWidgetTimer>(actionParametersOf(WidgetKeys.kind to slot.kind,
                                    WidgetKeys.id to slot.id, WidgetKeys.version to slot.version,
                                    WidgetKeys.request to "widget-stop:$id:${slot.kind}:${slot.id}:${slot.version}"))
                            } else if (entry.actionType in listOf(QuickEntryAction.START_TIMER, QuickEntryAction.START_FOCUS)) {
                                startEntryAction(entry, id)
                            } else open(context, "quick-entry:${entry.id}")
                            Text(if (running) "停止 · ${entry.label ?: "快捷记录"}" else entry.label ?: "快捷记录",
                                GlanceModifier.defaultWeight().padding(8.dp).clickable(action),
                                maxLines = 2, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 14.sp))
                        }
                    }
                    WidgetError(context, data.error)
                }
            }
        }
    }
}

class VoiceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Box(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.primaryContainer).clickable(open(context, "voice")),
                    contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.widget_mic), "语音记录", GlanceModifier.size(40.dp))
                }
            }
        }
    }
}

@Composable
private fun WidgetError(context: Context, error: String?) {
    if (error != null) Text("$error · 点此处理", GlanceModifier.clickable(open(context, "widget-action")),
        maxLines = 2, style = TextStyle(color = GlanceTheme.colors.error, fontSize = 12.sp))
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TodayWidget() }
class QuickTimerWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = QuickTimerWidget() }
class VoiceWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = VoiceWidget() }

internal object WidgetKeys {
    val kind = ActionParameters.Key<String>("kind")
    val id = ActionParameters.Key<String>("id")
    val version = ActionParameters.Key<Long>("version")
    val request = ActionParameters.Key<String>("request")
}

private fun startEntryAction(entry: QuickEntryEntity, glanceId: GlanceId): Action =
    actionRunCallback<StartWidgetEntry>(actionParametersOf(
        WidgetKeys.id to entry.id,
        WidgetKeys.version to entry.version,
        WidgetKeys.request to "widget-start:$glanceId:${entry.id}:${entry.version}:${entry.usageCount}",
    ))

class StartWidgetEntry : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.id] ?: return
        val request = parameters[WidgetKeys.request] ?: return
        val repo = AppEntryPoint.from(context).timeRepository()
        val version = parameters[WidgetKeys.version] ?: return
        val result = repo.commands.execute(WriteQuickEntry(request, QuickEntryMutation.Perform(id, version), Origin.WIDGET))
        widgetResult(context, result, id)
    }
}

class StopWidgetTimer : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.id] ?: return
        val version = parameters[WidgetKeys.version] ?: return
        val request = parameters[WidgetKeys.request] ?: return
        val mutation = when (parameters[WidgetKeys.kind]) {
            "TIMER" -> StopTimer(request, id, version, Origin.WIDGET)
            "FOCUS" -> WriteFocus(request, FocusMutation.Finish(id, version), Origin.WIDGET)
            "WORKOUT" -> WriteWorkout(request, WorkoutMutation.Finish(id, expectedUpdatedAt = version), Origin.WIDGET)
            else -> return
        }
        widgetResult(context, AppEntryPoint.from(context).commandExecutor().execute(mutation))
    }
}

private suspend fun widgetResult(context: Context, result: CommandResult, entryId: String? = null) {
    val success = result.status in setOf(CommandStatus.APPLIED, CommandStatus.DUPLICATE)
    context.getSharedPreferences("widget_status", 0).edit()
        .putString("error", if (success) null else result.message)
        .putString("entryId", if (success) null else entryId).apply()
    TodayWidget().updateAll(context); QuickTimerWidget().updateAll(context)
}
