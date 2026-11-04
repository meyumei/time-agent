@file:OptIn(ExperimentalMaterial3Api::class)
package com.timeagent.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.timeagent.ui.design.DesignTokens

data class EntityOption(val id: String, val label: String, val subtitle: String? = null)

@Composable
fun EntityPicker(
    label: String,
    options: List<EntityOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    recentIds: List<String> = emptyList(),
    onCreate: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = options.firstOrNull { it.id == selectedId }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton({ query = ""; open = true }, Modifier.fillMaxWidth().heightIn(min = DesignTokens.touchTarget)) {
            Text(selected?.label ?: "未选择", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState) {
        val keyword = query.trim()
        val byId = options.associateBy { it.id }
        val matched = options.distinctBy { it.id }.filter { keyword.isEmpty() || it.label.contains(keyword, ignoreCase = true) }
        val recent = if (keyword.isEmpty()) recentIds.distinct().mapNotNull { byId[it] } else emptyList()
        val rest = matched.filterNot { option -> recent.any { it.id == option.id } }
        Column(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.spacing16)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选择$label", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (selectedId != null) TextButton({ onSelect(null); open = false }, Modifier.heightIn(min = DesignTokens.touchTarget)) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_entity_picker_001)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索$label") },
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (recent.isNotEmpty()) {
                    item { SectionTitle("最近使用") }
                    items(recent, key = { it.id }) { option -> OptionRow(option, option.id == selectedId) { onSelect(option.id); open = false } }
                    item { SectionTitle("全部") }
                }
                if (rest.isEmpty() && recent.isEmpty()) item {
                    Text("没有匹配的$label", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(DesignTokens.spacing16))
                }
                items(rest, key = { it.id }) { option -> OptionRow(option, option.id == selectedId) { onSelect(option.id); open = false } }
                if (onCreate != null && keyword.isNotEmpty()) item {
                    TextButton({ onCreate(keyword); open = false }, Modifier.fillMaxWidth().heightIn(min = DesignTokens.touchTarget)) {
                        Text("新建“$keyword”")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = DesignTokens.spacing12, bottom = DesignTokens.spacing4),
)

@Composable
private fun OptionRow(option: EntityOption, selected: Boolean, onClick: () -> Unit) {
    val subtitle = option.subtitle
    ListItem(
        headlineContent = { Text(option.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle == null) null else { { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = if (selected) { { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_entity_picker_002), color = MaterialTheme.colorScheme.primary) } } else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = DesignTokens.touchTarget).clickable(onClick = onClick),
    )
}