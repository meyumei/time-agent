package com.timeagent.organization

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.timeagent.data.CategoryEntity
import com.timeagent.data.TagEntity
import com.timeagent.data.TimeAgentDatabase
import com.timeagent.data.TimeRecordEntity
import com.timeagent.data.TimeRepository
import com.timeagent.ui.TimeAgentTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val OrganizationPalette: List<Long> = listOf(
    0xFF5756B7L, 0xFF3978B5L, 0xFF26806AL, 0xFFB5642DL,
    0xFFB53D6AL, 0xFF6B5B95L, 0xFF4B7F52L, 0xFF8A6A1FL,
)

private val recordDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val splitTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Not exported. Main links it from Settings with startActivity(Intent(this, OrganizationActivity::class.java)).
 * Each visit opens its own database instance, so returning always reads the current file state.
 */
class OrganizationActivity : ComponentActivity() {
    private lateinit var database: TimeAgentDatabase
    private lateinit var repository: TimeRepository
    private lateinit var organization: OrganizationRepository
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clock = java.time.Clock.systemDefaultZone()
        database = TimeAgentDatabase.open(this)
        repository = TimeRepository(database, clock)
        organization = OrganizationRepository(database, clock)
        setContent {
            TimeAgentTheme {
                OrganizationScreen(
                    repository = repository,
                    organization = organization,
                    refreshTick = resumeTick,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }

    override fun onDestroy() {
        if (isFinishing) database.close()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrganizationScreen(
    repository: TimeRepository,
    organization: OrganizationRepository,
    refreshTick: Int,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    var data by remember { mutableStateOf<OrganizationSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedTaskIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRecordIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedCategoryIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var pickingItems by remember { mutableStateOf(false) }
    var creatingCategory by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var categoryDeleteIds by remember { mutableStateOf<List<String>?>(null) }
    var categoryMergeOpen by remember { mutableStateOf(false) }
    var categoryAssignOpen by remember { mutableStateOf(false) }
    var creatingTag by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<TagEntity?>(null) }
    var tagDeleteIds by remember { mutableStateOf<List<String>?>(null) }
    var tagMergeOpen by remember { mutableStateOf(false) }
    var tagAddOpen by remember { mutableStateOf(false) }
    var tagRemoveOpen by remember { mutableStateOf(false) }
    var mergePreview by remember { mutableStateOf<MergePreview?>(null) }
    var splitTarget by remember { mutableStateOf<TimeRecordEntity?>(null) }

    LaunchedEffect(refreshTick) {
        runCatching { organization.snapshot() }
            .onSuccess { data = it }
            .onFailure { error = it.message?.takeIf { message -> message.isNotBlank() } ?: "读取分类和标签失败，请返回后重新打开" }
    }

    fun perform(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                block()
                data = organization.snapshot()
                onSuccess()
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "操作未完成，请重试"
            } finally {
                busy = false
            }
        }
    }

    val snapshot = data
    BackHandler { if (!busy) onClose() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("分类与记录整理", maxLines = 1, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { TextButton(onClick = onClose, enabled = !busy) { Text("返回") } },
            actions = {
                TextButton(
                    onClick = { perform { repository.undoLastAction() } },
                    enabled = !busy && snapshot?.history?.any { !it.undone } == true,
                ) { Text("撤销") }
                TextButton(
                    onClick = { perform { repository.redoLastAction() } },
                    enabled = !busy && snapshot?.history?.any { it.undone && it.redoable } == true,
                ) { Text("重做") }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = { error = null }) { Text("关闭错误提示") }
                    }
                }
            }
            if (snapshot == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.size(12.dp))
                        Text("正在读取分类和标签…", style = MaterialTheme.typography.bodyMedium)
                        if (error != null) {
                            Spacer(Modifier.size(8.dp))
                            Button(onClick = { perform { } }, enabled = !busy) { Text("重新读取") }
                        }
                    }
                }
            } else {
                TabRow(selectedTabIndex = tab) {
                    listOf("分类", "标签", "记录整理").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> CategoriesTab(
                            data = snapshot,
                            busy = busy,
                            selectedTaskCount = snapshot.tasks.count { it.id in selectedTaskIds && it.deletedAt == null },
                            selectedRecordCount = snapshot.records.count { it.id in selectedRecordIds },
                            selectedCategoryIds = selectedCategoryIds,
                            onOpenPicker = { error = null; pickingItems = true },
                            onClearItems = { selectedTaskIds = emptySet(); selectedRecordIds = emptySet() },
                            onToggleCategory = { id -> selectedCategoryIds = toggle(selectedCategoryIds, id) },
                            onCreate = { error = null; creatingCategory = true },
                            onEdit = { error = null; editingCategory = it },
                            onArchive = { item -> perform { organization.setCategoryArchived(item.id, !item.isArchived) } },
                            onDelete = { item -> error = null; categoryDeleteIds = listOf(item.id) },
                            onAssignCategory = { error = null; categoryAssignOpen = true },
                            onMerge = { error = null; categoryMergeOpen = true },
                            onBatchArchive = {
                                perform { organization.setCategoriesArchived(selectedCategoryIds.toList(), true) }
                            },
                            onBatchDelete = { error = null; categoryDeleteIds = selectedCategoryIds.toList() },
                        )

                        1 -> TagsTab(
                            data = snapshot,
                            busy = busy,
                            selectedTaskCount = snapshot.tasks.count { it.id in selectedTaskIds && it.deletedAt == null },
                            selectedRecordCount = snapshot.records.count { it.id in selectedRecordIds },
                            selectedTagIds = selectedTagIds,
                            onOpenPicker = { error = null; pickingItems = true },
                            onClearItems = { selectedTaskIds = emptySet(); selectedRecordIds = emptySet() },
                            onToggleTag = { id -> selectedTagIds = toggle(selectedTagIds, id) },
                            onCreate = { error = null; creatingTag = true },
                            onEdit = { error = null; editingTag = it },
                            onDelete = { item -> error = null; tagDeleteIds = listOf(item.id) },
                            onAddTags = { error = null; tagAddOpen = true },
                            onRemoveTags = { error = null; tagRemoveOpen = true },
                            onMerge = { error = null; tagMergeOpen = true },
                            onBatchDelete = { error = null; tagDeleteIds = selectedTagIds.toList() },
                        )

                        else -> RecordsTab(
                            data = snapshot,
                            zone = zone,
                            busy = busy,
                            selectedRecordIds = selectedRecordIds,
                            onToggleRecord = { id -> selectedRecordIds = toggle(selectedRecordIds, id) },
                            onSplit = { record -> error = null; splitTarget = record },
                            onPreviewMerge = {
                                mergePreview = null
                                perform { mergePreview = organization.previewMerge(selectedRecordIds.toList()) }
                            },
                        )
                    }
                }
            }
        }
    }

    if (snapshot != null) {
        if (pickingItems) {
            ItemPickerDialog(
                data = snapshot,
                zone = zone,
                selectedTaskIds = selectedTaskIds,
                selectedRecordIds = selectedRecordIds,
                onToggleTask = { id -> selectedTaskIds = toggle(selectedTaskIds, id) },
                onToggleRecord = { id -> selectedRecordIds = toggle(selectedRecordIds, id) },
                onDismiss = { pickingItems = false },
            )
        }

        if (creatingCategory || editingCategory != null) {
            val editing = editingCategory
            NameColorDialog(
                title = if (editing == null) "新建分类" else "编辑分类",
                initialName = editing?.name.orEmpty(),
                initialColor = editing?.color ?: OrganizationPalette.first(),
                nameLabel = "分类名称",
                busy = busy,
                error = error,
                onDismiss = { creatingCategory = false; editingCategory = null },
                onConfirm = { name, color ->
                    perform(onSuccess = { creatingCategory = false; editingCategory = null }) {
                        if (editing == null) organization.createCategory(name, color)
                        else organization.updateCategory(editing.id, name, color)
                    }
                },
            )
        }

        categoryDeleteIds?.let { ids ->
            DeleteCategoryDialog(
                categories = snapshot.categories,
                ids = ids,
                busy = busy,
                error = error,
                onDismiss = { categoryDeleteIds = null },
                onConfirm = { moveTo ->
                    perform(onSuccess = {
                        categoryDeleteIds = null
                        selectedCategoryIds = selectedCategoryIds - ids.toSet()
                    }) { organization.deleteCategories(ids, moveTo) }
                },
            )
        }

        if (categoryMergeOpen) {
            MergeCategoryDialog(
                categories = snapshot.categories,
                sourceIds = selectedCategoryIds,
                busy = busy,
                error = error,
                onDismiss = { categoryMergeOpen = false },
                onConfirm = { targetId ->
                    perform(onSuccess = {
                        categoryMergeOpen = false
                        selectedCategoryIds = emptySet()
                    }) { organization.mergeCategories(selectedCategoryIds.toList(), targetId) }
                },
            )
        }

        if (categoryAssignOpen) {
            BulkCategoryDialog(
                categories = snapshot.categories,
                busy = busy,
                error = error,
                onDismiss = { categoryAssignOpen = false },
                onConfirm = { categoryId ->
                    perform(onSuccess = { categoryAssignOpen = false }) {
                        organization.setCategoryFor(selectedTaskIds.toList(), selectedRecordIds.toList(), categoryId)
                    }
                },
            )
        }

        if (creatingTag || editingTag != null) {
            val editing = editingTag
            NameColorDialog(
                title = if (editing == null) "新建标签" else "编辑标签",
                initialName = editing?.name.orEmpty(),
                initialColor = editing?.color ?: OrganizationPalette.first(),
                nameLabel = "标签名称",
                busy = busy,
                error = error,
                onDismiss = { creatingTag = false; editingTag = null },
                onConfirm = { name, color ->
                    perform(onSuccess = { creatingTag = false; editingTag = null }) {
                        if (editing == null) organization.createTag(name, color)
                        else organization.updateTag(editing.id, name, color)
                    }
                },
            )
        }

        tagDeleteIds?.let { ids ->
            DeleteTagDialog(
                tags = snapshot.tags,
                ids = ids,
                busy = busy,
                error = error,
                onDismiss = { tagDeleteIds = null },
                onConfirm = {
                    perform(onSuccess = {
                        tagDeleteIds = null
                        selectedTagIds = selectedTagIds - ids.toSet()
                    }) { organization.deleteTags(ids) }
                },
            )
        }

        if (tagMergeOpen) {
            MergeTagDialog(
                tags = snapshot.tags,
                sourceIds = selectedTagIds,
                busy = busy,
                error = error,
                onDismiss = { tagMergeOpen = false },
                onConfirm = { targetId ->
                    perform(onSuccess = {
                        tagMergeOpen = false
                        selectedTagIds = emptySet()
                    }) { organization.mergeTags(selectedTagIds.toList(), targetId) }
                },
            )
        }

        if (tagAddOpen || tagRemoveOpen) {
            val adding = tagAddOpen
            BulkTagDialog(
                tags = snapshot.tags,
                adding = adding,
                busy = busy,
                error = error,
                onDismiss = { tagAddOpen = false; tagRemoveOpen = false },
                onConfirm = { picked ->
                    perform(onSuccess = { tagAddOpen = false; tagRemoveOpen = false }) {
                        organization.assignTags(
                            taskIds = selectedTaskIds.toList(),
                            recordIds = selectedRecordIds.toList(),
                            addTagIds = if (adding) picked else emptySet(),
                            removeTagIds = if (adding) emptySet() else picked,
                        )
                    }
                },
            )
        }

        mergePreview?.let { preview ->
            MergeRecordsDialog(
                preview = preview,
                zone = zone,
                busy = busy,
                error = error,
                onDismiss = { mergePreview = null },
                onConfirm = {
                    perform(onSuccess = {
                        mergePreview = null
                        selectedRecordIds = emptySet()
                    }) { organization.mergeRecords(preview.recordIds, preview.versions) }
                },
            )
        }

        splitTarget?.let { record ->
            SplitRecordDialog(
                record = record,
                zone = zone,
                busy = busy,
                error = error,
                onDismiss = { splitTarget = null },
                onConfirm = { splitAt ->
                    perform(onSuccess = { splitTarget = null }) {
                        organization.splitRecord(record.id, record.version, splitAt)
                    }
                },
            )
        }
    }
}

// --------------------------------------------------------------------- category tab

@Composable
private fun CategoriesTab(
    data: OrganizationSnapshot,
    busy: Boolean,
    selectedTaskCount: Int,
    selectedRecordCount: Int,
    selectedCategoryIds: Set<String>,
    onOpenPicker: () -> Unit,
    onClearItems: () -> Unit,
    onToggleCategory: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: (CategoryEntity) -> Unit,
    onArchive: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    onAssignCategory: () -> Unit,
    onMerge: () -> Unit,
    onBatchArchive: () -> Unit,
    onBatchDelete: () -> Unit,
) {
    val taskCounts = data.tasks.filter { it.deletedAt == null }.groupingBy { it.categoryId }.eachCount()
    val recordCounts = data.records.groupingBy { it.categoryId }.eachCount()
    val activityCounts = data.activities.groupingBy { it.categoryId }.eachCount()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分类", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onCreate, enabled = !busy) { Text("新建分类") }
                }
                Text("归档或删除分类都不会删除任务、记录和笔记。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SelectionSummary(
                taskCount = selectedTaskCount,
                recordCount = selectedRecordCount,
                busy = busy,
                onOpenPicker = onOpenPicker,
                onClear = onClearItems,
            ) {
                TextButton(
                    onClick = onAssignCategory,
                    enabled = !busy && (selectedTaskCount > 0 || selectedRecordCount > 0),
                ) { Text("批量设置分类") }
            }
        }
        if (data.categories.isEmpty()) {
            item { Text("还没有分类，先新建一个分类。", style = MaterialTheme.typography.bodyMedium) }
        }
        items(data.categories, key = { it.id }) { item ->
            CategoryRow(
                item = item,
                taskCount = taskCounts[item.id] ?: 0,
                recordCount = recordCounts[item.id] ?: 0,
                activityCount = activityCounts[item.id] ?: 0,
                selected = item.id in selectedCategoryIds,
                busy = busy,
                onToggleSelected = { onToggleCategory(item.id) },
                onEdit = { onEdit(item) },
                onArchive = { onArchive(item) },
                onDelete = { onDelete(item) },
            )
        }
        if (selectedCategoryIds.isNotEmpty()) {
            item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已选 ${selectedCategoryIds.size} 个分类", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = onMerge, enabled = !busy) { Text("合并所选分类") }
                            TextButton(onClick = onBatchArchive, enabled = !busy) { Text("批量归档") }
                            TextButton(onClick = onBatchDelete, enabled = !busy) { Text("批量删除") }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "删除分类时可以选择把内容移到另一个分类，或只取消归类。合并会把所选分类的内容改到目标分类。归档分类不再出现在新建选择中，历史内容保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoryRow(
    item: CategoryEntity,
    taskCount: Int,
    recordCount: Int,
    activityCount: Int,
    selected: Boolean,
    busy: Boolean,
    onToggleSelected: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() }, enabled = !busy)
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(item.color)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name + if (item.isArchived) "（已归档）" else "",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "任务 $taskCount · 记录 $recordCount · 活动 $activityCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onEdit, enabled = !busy) { Text("编辑分类") }
                TextButton(onClick = onArchive, enabled = !busy) { Text(if (item.isArchived) "恢复分类" else "归档分类") }
                TextButton(onClick = onDelete, enabled = !busy) { Text("删除分类") }
            }
        }
    }
}

// -------------------------------------------------------------------------- tag tab

@Composable
private fun TagsTab(
    data: OrganizationSnapshot,
    busy: Boolean,
    selectedTaskCount: Int,
    selectedRecordCount: Int,
    selectedTagIds: Set<String>,
    onOpenPicker: () -> Unit,
    onClearItems: () -> Unit,
    onToggleTag: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: (TagEntity) -> Unit,
    onDelete: (TagEntity) -> Unit,
    onAddTags: () -> Unit,
    onRemoveTags: () -> Unit,
    onMerge: () -> Unit,
    onBatchDelete: () -> Unit,
) {
    val taskCounts = data.taskTagIds.values.flatten().groupingBy { it }.eachCount()
    val recordCounts = data.recordTagIds.values.flatten().groupingBy { it }.eachCount()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("标签", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onCreate, enabled = !busy) { Text("新建标签") }
                }
                Text("标签必须实际贴到任务或记录上；删除标签不会删除任务和记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SelectionSummary(
                taskCount = selectedTaskCount,
                recordCount = selectedRecordCount,
                busy = busy,
                onOpenPicker = onOpenPicker,
                onClear = onClearItems,
            ) {
                TextButton(
                    onClick = onAddTags,
                    enabled = !busy && (selectedTaskCount > 0 || selectedRecordCount > 0),
                ) { Text("为所选添加标签") }
                TextButton(
                    onClick = onRemoveTags,
                    enabled = !busy && (selectedTaskCount > 0 || selectedRecordCount > 0),
                ) { Text("从所选移除标签") }
            }
        }
        if (data.tags.isEmpty()) {
            item { Text("还没有标签，先新建一个标签。", style = MaterialTheme.typography.bodyMedium) }
        }
        items(data.tags, key = { it.id }) { item ->
            TagRow(
                item = item,
                taskCount = taskCounts[item.id] ?: 0,
                recordCount = recordCounts[item.id] ?: 0,
                selected = item.id in selectedTagIds,
                busy = busy,
                onToggleSelected = { onToggleTag(item.id) },
                onEdit = { onEdit(item) },
                onDelete = { onDelete(item) },
            )
        }
        if (selectedTagIds.isNotEmpty()) {
            item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已选 ${selectedTagIds.size} 个标签", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = onMerge, enabled = !busy) { Text("合并所选标签") }
                            TextButton(onClick = onBatchDelete, enabled = !busy) { Text("批量删除标签") }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "合并标签会把所选标签的关联改到目标标签，再删除原标签。批量删除只移除标签和关联，可用撤销恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TagRow(
    item: TagEntity,
    taskCount: Int,
    recordCount: Int,
    selected: Boolean,
    busy: Boolean,
    onToggleSelected: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() }, enabled = !busy)
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(item.color)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "任务 $taskCount · 记录 $recordCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = onEdit, enabled = !busy) { Text("编辑标签") }
                TextButton(onClick = onDelete, enabled = !busy) { Text("删除标签") }
            }
        }
    }
}

// ------------------------------------------------------------------- record tidying

@Composable
private fun RecordsTab(
    data: OrganizationSnapshot,
    zone: ZoneId,
    busy: Boolean,
    selectedRecordIds: Set<String>,
    onToggleRecord: (String) -> Unit,
    onSplit: (TimeRecordEntity) -> Unit,
    onPreviewMerge: () -> Unit,
) {
    val tagsById = data.tags.associate { it.id to it.name }
    val categoriesById = data.categories.associate { it.id to it.name }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("记录整理", style = MaterialTheme.typography.titleMedium)
            Text(
                "合并只支持同一任务、同一分类、时间连续或重叠的已完成记录；不会填补中间空档。拆分保留原记录编号作为前半段。操作都不会修改任何计划。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已选 ${selectedRecordIds.size} 条记录用于合并", style = MaterialTheme.typography.labelLarge)
                    Text("勾选至少两条记录后先预览，确认合并前后的时长。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onPreviewMerge, enabled = !busy && selectedRecordIds.size >= 2) { Text("预览合并记录") }
                }
            }
        }
        if (data.records.isEmpty()) {
            item { Text("还没有实际记录。", style = MaterialTheme.typography.bodyMedium) }
        }
        items(data.records, key = { it.id }) { record ->
            RecordRow(
                record = record,
                zone = zone,
                categoryName = categoriesById[record.categoryId] ?: "未分类",
                tagNames = data.recordTagIds[record.id].orEmpty().mapNotNull { tagsById[it] },
                selected = record.id in selectedRecordIds,
                busy = busy,
                onToggle = { onToggleRecord(record.id) },
                onSplit = { onSplit(record) },
            )
        }
    }
}

@Composable
private fun RecordRow(
    record: TimeRecordEntity,
    zone: ZoneId,
    categoryName: String,
    tagNames: List<String>,
    selected: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onSplit: () -> Unit,
) {
    val end = record.endAt
    val running = end == null
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = !busy && !running)
                Column(Modifier.weight(1f)) {
                    Text(
                        record.title.ifBlank { record.notes.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "未命名记录" } },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (end == null) "从 ${recordTime(record.startAt, zone)} 开始 · 计时中 · $categoryName"
                        else "${recordTime(record.startAt, zone)} – ${recordTime(end, zone)} · ${durationText(end - record.startAt)} · $categoryName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (record.source == "HEALTH_SYNC") {
                        Text("同步来源记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (tagNames.isNotEmpty()) {
                        Text(tagNames.joinToString("、"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (running) {
                        Text("计时中的记录不能合并或拆分。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (!running) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onSplit, enabled = !busy) { Text("拆分为两段") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- dialogs

@Composable
private fun SelectionSummary(
    taskCount: Int,
    recordCount: Int,
    busy: Boolean,
    onOpenPicker: () -> Unit,
    onClear: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("批量选择：$taskCount 个任务 · $recordCount 条记录", style = MaterialTheme.typography.labelLarge)
            Text(
                "先勾选任务或实际记录，再执行批量分类或标签操作。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenPicker, enabled = !busy) { Text("选择任务和记录") }
                TextButton(onClick = onClear, enabled = !busy && (taskCount > 0 || recordCount > 0)) { Text("清空所选") }
                actions()
            }
        }
    }
}

@Composable
private fun ItemPickerDialog(
    data: OrganizationSnapshot,
    zone: ZoneId,
    selectedTaskIds: Set<String>,
    selectedRecordIds: Set<String>,
    onToggleTask: (String) -> Unit,
    onToggleRecord: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val categories = data.categories.associate { it.id to it.name }
    val tasks = data.tasks.filter { it.deletedAt == null }.sortedBy { it.title }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("选择任务和记录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "已选 ${selectedTaskIds.size} 个任务 · ${selectedRecordIds.size} 条记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item { Text("任务", style = MaterialTheme.typography.labelLarge) }
                    items(tasks, key = { "task-${it.id}" }) { task ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggleTask(task.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = task.id in selectedTaskIds, onCheckedChange = { onToggleTask(task.id) })
                            Column(Modifier.weight(1f)) {
                                Text(task.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    categories[task.categoryId] ?: "未分类",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item { Text("实际记录", style = MaterialTheme.typography.labelLarge) }
                    items(data.records, key = { "record-${it.id}" }) { record ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggleRecord(record.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = record.id in selectedRecordIds, onCheckedChange = { onToggleRecord(record.id) })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    record.title.ifBlank { "未命名记录" },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${recordTime(record.startAt, zone)} · ${categories[record.categoryId] ?: "未分类"}" +
                                        if (record.endAt == null) " · 计时中" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("完成选择") }
            }
        }
    }
}

@Composable
private fun NameColorDialog(
    title: String,
    initialName: String,
    initialColor: Long,
    nameLabel: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var color by remember(initialColor) { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(nameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("颜色", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    OrganizationPalette.forEach { value ->
                        Box(
                            Modifier
                                .padding(4.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(value))
                                .border(
                                    width = if (value == color) 3.dp else 1.dp,
                                    color = if (value == color) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable { color = value },
                        )
                    }
                }
                Text("名称必填，且不能与现有${nameLabel.removeSuffix("名称")}重名。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DialogError(error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = { onConfirm(name, color) }) { Text("保存") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteCategoryDialog(
    categories: List<CategoryEntity>,
    ids: List<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    val deleting = categories.filter { it.id in ids }
    val targets = categories.filter { it.id !in ids && !it.isArchived }
    var moveTo by remember(ids) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (ids.size > 1) "批量删除分类" else "删除分类") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("将删除：${deleting.joinToString("、") { it.name }}")
                Text(
                    "任务、实际记录、笔记和活动模板都会保留，不会删除任何内容。请选择这些内容的去向：",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilterChip(selected = moveTo == null, onClick = { moveTo = null }, label = { Text("仅取消归类") })
                targets.forEach { item ->
                    FilterChip(
                        selected = moveTo == item.id,
                        onClick = { moveTo = item.id },
                        label = { Text("移到「${item.name}」") },
                    )
                }
                if (targets.isEmpty()) {
                    Text("没有其他可用分类，将只取消归类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DialogError(error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onConfirm(moveTo) }) { Text("确认删除分类") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MergeCategoryDialog(
    categories: List<CategoryEntity>,
    sourceIds: Set<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val sources = categories.filter { it.id in sourceIds }
    val targets = categories.filter { it.id !in sourceIds && !it.isArchived }
    var targetId by remember(sourceIds) { mutableStateOf<String?>(targets.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("合并分类") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("所选分类：${sources.joinToString("、") { it.name }}")
                Text(
                    "合并后，任务、实际记录和活动模板会改用目标分类，原分类删除；笔记保留。",
                    style = MaterialTheme.typography.bodySmall,
                )
                targets.forEach { item ->
                    FilterChip(
                        selected = targetId == item.id,
                        onClick = { targetId = item.id },
                        label = { Text("目标：${item.name}") },
                    )
                }
                if (targets.isEmpty()) {
                    Text("没有可用的目标分类，请先新建或恢复一个分类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                DialogError(error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && targetId != null, onClick = { targetId?.let(onConfirm) }) { Text("确认合并分类") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BulkCategoryDialog(
    categories: List<CategoryEntity>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var targetId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("批量设置分类") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "把所选任务和实际记录设为同一分类。只改分类，不改标题、时间、备注或计划。",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilterChip(selected = targetId == null, onClick = { targetId = null }, label = { Text("不设置分类") })
                categories.filter { !it.isArchived }.forEach { item ->
                    FilterChip(
                        selected = targetId == item.id,
                        onClick = { targetId = item.id },
                        label = { Text(item.name) },
                    )
                }
                if (categories.none { !it.isArchived }) {
                    Text("没有可用分类，将只取消归类。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DialogError(error)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { onConfirm(targetId) }) { Text("应用分类") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteTagDialog(
    tags: List<TagEntity>,
    ids: List<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val deleting = tags.filter { it.id in ids }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (ids.size > 1) "批量删除标签" else "删除标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("将删除：${deleting.joinToString("、") { it.name }}")
                Text(
                    "只删除标签和标签关联；任务、实际记录和笔记都会保留。撤销可以恢复标签和原有关联。",
                    style = MaterialTheme.typography.bodySmall,
                )
                DialogError(error)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onConfirm) { Text("确认删除标签") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MergeTagDialog(
    tags: List<TagEntity>,
    sourceIds: Set<String>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val sources = tags.filter { it.id in sourceIds }
    val targets = tags.filter { it.id !in sourceIds }
    var targetId by remember(sourceIds) { mutableStateOf<String?>(targets.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("合并标签") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("所选标签：${sources.joinToString("、") { it.name }}")
                Text(
                    "这些标签在任务和记录上的关联会改到目标标签，然后删除所选标签。",
                    style = MaterialTheme.typography.bodySmall,
                )
                targets.forEach { item ->
                    FilterChip(
                        selected = targetId == item.id,
                        onClick = { targetId = item.id },
                        label = { Text("目标：${item.name}") },
                    )
                }
                if (targets.isEmpty()) {
                    Text("没有可用的目标标签，请先新建一个标签。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                DialogError(error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && targetId != null, onClick = { targetId?.let(onConfirm) }) { Text("确认合并标签") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BulkTagDialog(
    tags: List<TagEntity>,
    adding: Boolean,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var picked by remember(adding) { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (adding) "为所选添加标签" else "从所选移除标签") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (adding) "所选任务和实际记录会加上勾选的标签。" else "所选任务和实际记录会移除勾选的标签，其他标签保留。",
                    style = MaterialTheme.typography.bodySmall,
                )
                tags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in picked,
                        onClick = { picked = if (tag.id in picked) picked - tag.id else picked + tag.id },
                        label = { Text(tag.name) },
                    )
                }
                if (tags.isEmpty()) {
                    Text("还没有标签，请先在标签页新建标签。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DialogError(error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && picked.isNotEmpty(), onClick = { onConfirm(picked) }) {
                Text(if (adding) "添加标签" else "移除标签")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MergeRecordsDialog(
    preview: MergePreview,
    zone: ZoneId,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("确认合并记录") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("将保留最早的记录，并删除其余 ${preview.recordIds.size - 1} 条记录。")
                Text("原记录合计：${durationText(preview.originalTotalMillis)}")
                Text("合并后跨度：${durationText(preview.endAt - preview.startAt)}")
                Text("时间范围：${recordTime(preview.startAt, zone)} – ${recordTime(preview.endAt, zone)}")
                Text("保留标题：${preview.title.ifBlank { "（无标题）" }}")
                if (preview.notes.isNotBlank()) Text("备注文字会按时间顺序保留。")
                Text("标签会合并到保留记录；不会修改任何计划。")
                if (preview.movesNote) Text("已有的一条笔记会移动到保留记录。")
                DialogError(error)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onConfirm) { Text("确认合并") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SplitRecordDialog(
    record: TimeRecordEntity,
    zone: ZoneId,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val end = record.endAt ?: return
    val midpoint = record.startAt + (end - record.startAt) / 2
    val midpointTime = Instant.ofEpochMilli(midpoint).atZone(zone)
    var date by remember(record.id) { mutableStateOf(midpointTime.toLocalDate().toString()) }
    var time by remember(record.id) { mutableStateOf(midpointTime.format(splitTimeFormat)) }
    val parsed = runCatching {
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
    val valid = parsed != null && parsed > record.startAt && parsed < end
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("拆分为两段") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("原记录：${recordTime(record.startAt, zone)} – ${recordTime(end, zone)} · ${durationText(end - record.startAt)}")
                Text("原记录编号和时间范围保留为前半段；新记录保存后半段。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("拆分日期 YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("拆分时间 HH:mm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    parsed == null -> Text("请按 YYYY-MM-DD 和 HH:mm 填写日期和时间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    parsed <= record.startAt || parsed >= end -> Text("拆分时间必须严格位于记录开始和结束之间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> {
                        Text("前半段：${recordTime(record.startAt, zone)} – ${recordTime(parsed, zone)} · ${durationText(parsed - record.startAt)}")
                        Text("后半段：${recordTime(parsed, zone)} – ${recordTime(end, zone)} · ${durationText(end - parsed)}")
                    }
                }
                DialogError(error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && valid && parsed != null, onClick = { parsed?.let(onConfirm) }) { Text("确认拆分") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DialogError(error: String?) {
    if (error != null) {
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------- helpers

private fun toggle(source: Set<String>, id: String): Set<String> =
    if (id in source) source - id else source + id

private fun recordTime(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(recordDateTime)

private fun durationText(millis: Long): String {
    val safe = millis.coerceAtLeast(0)
    val minutes = safe / 60_000L
    if (minutes == 0L && safe > 0) return "不足 1 分钟"
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0 && rest > 0 -> "$hours 小时 $rest 分"
        hours > 0 -> "$hours 小时"
        else -> "$rest 分钟"
    }
}
