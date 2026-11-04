package com.timeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.timeagent.ui.design.PresentationMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHub(
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings-hub"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("settings-title"),
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("settings-back"),
                    ) {
                        Text(
                            text = "返回管家",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("settings-content")
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            HubSection(
                title = "外观",
                description = "调整主题与显示方式。",
                testTag = "settings-group-appearance",
            ) {
                SettingsLink(
                    title = "外观设置",
                    subtitle = "切换模式、深浅色和动态取色。",
                    route = "appearance",
                    onOpen = onOpen,
                )
            }

            HubSection(
                title = "提醒与通知",
                description = "管理通知权限和提醒方式。",
                testTag = "settings-group-reminders",
            ) {
                SettingsLink("计时与快捷通知", "计时操作和锁屏隐私。", "timer-settings", onOpen)
                SettingsLink(
                    title = "通知设置",
                    subtitle = "查看通知与精确闹钟权限。",
                    route = "notification-settings",
                    onOpen = onOpen,
                )
            }

            HubSection(
                title = "AI 与鱼",
                description = "管理 AI 模型、密钥，以及大肥鱼的陪伴与能力。",
                testTag = "settings-group-ai-fish",
            ) {
                SettingsLink(
                    title = "AI 模型与密钥",
                    subtitle = "配置你自带的 AI 模型与密钥。",
                    route = "ai-models",
                    onOpen = onOpen,
                )
                SettingsLink(
                    title = "运行环境",
                    subtitle = "检查并维护大肥鱼运行所需的本地环境。",
                    route = "fish-runtime",
                    onOpen = onOpen,
                )
                SettingsLink(
                    title = "角色与陪伴",
                    subtitle = "调整角色设定、称呼与陪伴方式。",
                    route = "fish-companion",
                    onOpen = onOpen,
                )
                SettingsLink(
                    title = "平台连接",
                    subtitle = "管理可用平台与入口。",
                    route = "fish-platforms",
                    onOpen = onOpen,
                )
                SettingsLink(
                    title = "语音与朗读",
                    subtitle = "设置语音输入、朗读和音色。",
                    route = "fish-voice",
                    onOpen = onOpen,
                )
                SettingsLink(
                    title = "鱼的记忆",
                    subtitle = "查看大肥鱼记住的内容。",
                    route = "fish-memory",
                    onOpen = onOpen,
                )
                SettingsLink(
                    title = "个人规则",
                    subtitle = "维护长期偏好与个人规则。",
                    route = "memory",
                    onOpen = onOpen,
                )
                SettingsLink(
                    title = "主动关心",
                    subtitle = "设置主动提醒的时机与范围。",
                    route = "fish-proactive",
                    onOpen = onOpen,
                )
                SettingsLink(
                    title = "鱼的呈现",
                    subtitle = "调整大肥鱼在页面中的呈现方式。",
                    route = "fish-appearance",
                    onOpen = onOpen,
                )
            }

            HubSection(
                title = "日历",
                description = "把安排发送到系统日历。",
                testTag = "settings-group-calendar",
            ) {
                SettingsLink(
                    title = "日历设置",
                    subtitle = "查看发送到系统日历的使用方式。",
                    route = "calendar-sync",
                    onOpen = onOpen,
                )
            }

            HubSection(
                title = "数据",
                description = "管理备份，以及已有的导入与整理工具。",
                testTag = "settings-group-data",
            ) {
                SettingsLink(
                    title = "备份与恢复",
                    subtitle = "导出、恢复并保护本地数据。",
                    route = "backup",
                    onOpen = onOpen,
                )
                SettingsLink("数据导出", "按时间与类型导出 CSV、JSON 或 ICS。", "export", onOpen)
                SettingsLink("模板与快捷记录", "固定常用计时和批量应用计划。", "quick-entries", onOpen)
                SettingsLink("计划模板", "保存并应用一组日程与任务。", "plan-templates", onOpen)
                SettingsLink("引导与示例数据", "重新查看引导，加载或清除示例。", "sample-data", onOpen)
                SettingsLink(
                    title = "已有工具",
                    subtitle = "课表导入、健康、训练与记录整理。",
                    route = "tools",
                    onOpen = onOpen,
                )
            }

            HubSection(
                title = "关于",
                description = "版本、许可与产品信息。",
                testTag = "settings-group-about",
            ) {
                SettingsLink(
                    title = "关于本应用",
                    subtitle = "查看版本、许可与产品说明。",
                    route = "about",
                    onOpen = onOpen,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ButlerHub(
    onOpen: (String) -> Unit,
    reselectRevision: Int = 0,
    pendingLedgerCount: Int = 0,
    ledgerReadError: String? = null,
) {
    val scroll = rememberScrollState()
    var consumedReselect by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(reselectRevision) }
    androidx.compose.runtime.LaunchedEffect(reselectRevision) {
        if (reselectRevision != consumedReselect) { scroll.scrollTo(0); consumedReselect = reselectRevision }
    }
    val context = LocalContext.current
    val appearance = LocalAppearance.current
    val themePreference = remember(context) { ThemePreference(context) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("butler-hub"),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "管家",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("butler-title"),
                    )
                },
                actions = {
                    TextButton(
                        onClick = { onOpen("settings") },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("butler-settings"),
                    ) {
                        Text(
                            text = "设置",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("butler-content")
                .padding(innerPadding)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            HubSection(
                title = "呈现方式",
                description = "工具模式更直接，陪伴模式更温和；切换只改变呈现，不会重置当前页面。",
                testTag = "butler-presentation",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PresentationModeChip(
                        label = "工具模式",
                        selected = appearance.presentation == PresentationMode.TOOL,
                        onClick = {
                            themePreference.savePresentation(PresentationMode.TOOL)
                        },
                        testTag = "butler-mode-tool",
                        modifier = Modifier.weight(1f),
                    )
                    PresentationModeChip(
                        label = "陪伴模式",
                        selected = appearance.presentation == PresentationMode.COMPANION,
                        onClick = {
                            themePreference.savePresentation(PresentationMode.COMPANION)
                        },
                        testTag = "butler-mode-companion",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            HubSection(
                title = "对话与陪伴",
                description = "继续和大肥鱼聊天，或调整角色与表达。",
                testTag = "butler-group-conversation",
            ) {
                SettingsLink(
                    title = "大肥鱼对话",
                    subtitle = "继续聊天，或查看最近的对话。",
                    route = "fish-chat",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "角色与陪伴",
                    subtitle = "调整角色设定、称呼与陪伴方式。",
                    route = "fish-companion",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "聊天提取",
                    subtitle = "从聊天中提取安排和记录。",
                    route = "extract",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "回复副驾",
                    subtitle = "生成并调整建议回复。",
                    route = "reply",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
            }

            HubSection(
                title = "行动与日程",
                description = "进入训练、课表、活动安排与收件箱。",
                testTag = "butler-group-action",
            ) {
                SettingsLink(
                    title = "训练",
                    subtitle = "查看训练安排与记录。",
                    route = "workout",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "课表与导入",
                    subtitle = "管理课表并导入已有安排。",
                    route = "courses",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "活动模板",
                    subtitle = "管理常用活动与安排模板。",
                    route = "activities",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "收件箱",
                    subtitle = "处理待办、提醒与收件内容。",
                    route = "inbox",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
            }

            HubSection(
                title = "记录与整理",
                description = "管理账目、健康、附件，并整理长期信息。",
                testTag = "butler-group-records",
            ) {
                SettingsLink(
                    title = "支付记账",
                    subtitle = ledgerReadError ?: if (pendingLedgerCount > 0) "$pendingLedgerCount 笔支付待确认" else "记录并查看支付账目。",
                    route = "ledger",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "健康",
                    subtitle = "查看健康数据与日常状态。",
                    route = "health",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "附件",
                    subtitle = "查看并整理对话中的附件。",
                    route = "attachments",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "个人规则",
                    subtitle = "维护长期偏好与个人规则。",
                    route = "memory",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
                SettingsLink(
                    title = "分类标签与记录整理",
                    subtitle = "整理分类、标签与已有记录。",
                    route = "organization",
                    onOpen = onOpen,
                    tagPrefix = "butler-link",
                )
            }
        }
    }
}

@Composable
private fun HubSection(
    title: String,
    description: String,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun SettingsLink(
    title: String,
    subtitle: String? = null,
    route: String,
    onOpen: (String) -> Unit,
    tagPrefix: String = "settings-link",
) {
    OutlinedButton(
        onClick = { onOpen(route) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("$tagPrefix-$route"),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PresentationModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        },
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag(testTag),
        shape = MaterialTheme.shapes.medium,
    )
}
