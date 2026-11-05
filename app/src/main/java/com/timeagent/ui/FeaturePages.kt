package com.timeagent.ui

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.timeagent.habits.*
import com.timeagent.onboarding.*
import com.timeagent.reports.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

@Composable
internal fun HabitPage(model: TimeViewModel, onClose: () -> Unit, onOpen: (String, String) -> Unit,
    initialHabitId: String? = null) {
    val context = LocalContext.current.applicationContext
    val repository = remember(model, context) { HabitRepository(model.repository, context) }
    val habits: HabitViewModel = viewModel(factory = HabitViewModel.factory(repository))
    HabitScreen(habits, onClose, onOpen, initialHabitId)
}

@Composable
internal fun ReportPage(model: TimeViewModel, onClose: () -> Unit, period: String? = null, onFishDraft: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val report: ReportModel = viewModel(factory = viewModelFactory { initializer {
        ReportModel(ReportRepository(model.repository.database, model.repository.workoutDatabase),
            context.getSharedPreferences("report_preferences", Context.MODE_PRIVATE))
    } })
    LaunchedEffect(period) {
        period?.split(':', limit = 2)?.takeIf { it.size == 2 }?.let {
            report.showPeriod(ReportKind.valueOf(it[0]), java.time.LocalDate.parse(it[1]))
        }
    }
    val fish = model.fish
    ReportScreen(report, onClose, onAskFish = if (fish == null) null else { snapshot ->
        check(fish.runtime.status.value.ready) { "请先启动大肥鱼并配置自己的 API Key" }
        check(!fish.chat.value.busy) { "大肥鱼正在回复，请稍后重试" }
        val revision = fish.chat.value.completionRevision
        fish.send("@鱼 请对这份本地${snapshot.kind.label}写一段点评，不要修改统计数字或调用写入工具：\n${com.google.gson.Gson().toJson(snapshot)}").join()
        withTimeout(120_000) {
            fish.chat.first { !it.busy && it.completionRevision > revision }
            fish.latestRemoteReply().takeIf { it.isNotBlank() }
        }
    })
}

@Composable
internal fun ExportPage(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val export: ExportModel = viewModel(factory = viewModelFactory { initializer { ExportModel(context) } })
    DataExportScreen(export, onClose)
}

@Composable
internal fun SamplePage(model: TimeViewModel, onClose: () -> Unit, onReplay: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val samples = remember(model, context) { SampleDataRepository(model.repository, context) }
    val sampleModel = remember(samples) { SampleDataModel(context, samples, onReplay) }
    SampleDataScreen(sampleModel, onClose)
}

@Composable
internal fun OnboardingPage(model: TimeViewModel, existingUser: Boolean, onClose: () -> Unit, onAi: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val samples = remember(model, context) { SampleDataRepository(model.repository, context) }
    val onboarding = remember(samples, existingUser) { OnboardingModel(context, samples, existingUser, onAi) }
    OnboardingScreen(onboarding, onClose)
}
