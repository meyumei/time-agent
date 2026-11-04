package com.timeagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StructuredDraftPreview(
    title: String,
    before: String,
    after: String,
    warnings: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (before.isNotBlank()) {
                Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_structured_draft_preview_001), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(before) }
                HorizontalDivider()
            }
            Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_structured_draft_preview_002), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(after) }
            warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            actions()
        }
    }
}
