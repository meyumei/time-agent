package com.timeagent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.timeagent.R
import com.timeagent.core.WeekState
import com.timeagent.core.TimelineSelection
import com.timeagent.data.LocalSnapshot
import com.timeagent.data.ReminderEntity
import com.timeagent.data.ScheduleBlockEntity
import com.timeagent.ui.timeline.TimelineDragResult
import java.time.LocalDate

@Composable
internal fun PlanScheduleContent(
    model: TimeViewModel, data: LocalSnapshot, busy: Boolean,
    themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit,
    onSelect: (TimelineItem) -> Unit, onCreate: () -> Unit, onCreateAt: (TimelineSelection) -> Unit,
    onAssistant: () -> Unit, onReminder: (ReminderEntity) -> Unit,
    onDragCommit: (TimelineItem, TimelineDragResult) -> Unit,
    onSaveTemplate: (List<ScheduleBlockEntity>) -> Unit,
) {
    val states = rememberSaveableStateHolder()
    var monthOpen by rememberSaveable { mutableStateOf(false) }
    var weekEpoch by rememberSaveable {
        mutableLongStateOf(WeekState.startOfWeek(model.clock.now().atZone(model.zone).toLocalDate()).toEpochDay())
    }
    var requestedDateText by rememberSaveable { mutableStateOf<String?>(null) }
    var dateRevision by rememberSaveable { mutableLongStateOf(0L) }
    BackHandler(monthOpen) { monthOpen = false }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { monthOpen = !monthOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (monthOpen) R.string.plan_back_to_timeline else R.string.plan_open_month))
            }
        }
        MinuteClockContent(model) { now ->
            val week = WeekState(now.atZone(model.zone).toLocalDate(), LocalDate.ofEpochDay(weekEpoch))
            if (monthOpen) states.SaveableStateProvider("month") {
                TimelineQueryContent(model, week, !data.loading) { monthlyData, reportRange ->
                    PlanScheduleOverview(monthlyData, now, model.zone,
                        onDate = { date ->
                            weekEpoch = WeekState.startOfWeek(date).toEpochDay()
                            requestedDateText = date.toString()
                            dateRevision++
                            monthOpen = false
                        }, onVisibleRange = reportRange)
                }
            } else states.SaveableStateProvider("timeline") {
                TimelineQueryContent(model, week, !data.loading) { timelineData, reportRange ->
                    TimelineScreen(timelineData, week, now, model.zone, onSelect,
                        onWeek = { weekEpoch = LocalDate.ofEpochDay(weekEpoch).plusWeeks(it).toEpochDay() },
                        onCreate = onCreate, onCreateAt = onCreateAt, onAssistant = onAssistant,
                        onUndo = { model.perform { model.repository.undoLastAction() } },
                        onRedo = { model.perform { model.repository.redoLastAction() } },
                        canUndo = data.history.any { !it.undone }, canRedo = data.history.any { it.undone && it.redoable },
                        busy = busy || timelineData.loading, themeMode = themeMode, onThemeMode = onThemeMode,
                        onReminder = onReminder, onDragCommit = onDragCommit, onSaveTemplate = onSaveTemplate,
                        onVisibleRange = reportRange, defaultDayCount = 7, preferenceKey = "plan", navigationOwner = "plan",
                        requestedDate = requestedDateText?.let(LocalDate::parse), dateRequestRevision = dateRevision)
                }
            }
        }
    }
}
