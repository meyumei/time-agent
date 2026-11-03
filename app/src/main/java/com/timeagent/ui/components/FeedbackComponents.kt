package com.timeagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun InlineError(message: String?, modifier: Modifier = Modifier, fieldFocus: FocusRequester? = null) {
    LaunchedEffect(message) { if (message != null) fieldFocus?.requestFocus() }
    if (message != null) Text(message, modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun StatusBanner(message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun EmptyState(title: String, description: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier,
    illustration: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        illustration()
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun LoadingSkeleton(loading: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        visible = false
        if (loading) { delay(300); visible = true }
    }
    if (visible && loading) Column(modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Surface(Modifier.fillMaxWidth(if (it == 2) .65f else 1f).height(24.dp),
                shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(title: String, impact: String, confirmLabel: String, onConfirm: () -> Unit,
    onDismiss: () -> Unit, busy: Boolean = false) {
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(impact, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(confirmLabel) }
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}

data class UndoMessage(val id: String, val message: String, val token: String)

@Composable
fun UndoSnackbar(host: SnackbarHostState, message: UndoMessage?, onUndo: (String) -> Unit, onConsumed: (String) -> Unit) {
    LaunchedEffect(message?.id) {
        val item = message ?: return@LaunchedEffect
        host.currentSnackbarData?.dismiss()
        val result = withTimeoutOrNull(8_000) {
            host.showSnackbar(item.message, actionLabel = "撤销", withDismissAction = true, duration = SnackbarDuration.Indefinite)
        }
        if (result == SnackbarResult.ActionPerformed) onUndo(item.token)
        onConsumed(item.id)
    }
}

data class DetailAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(title: String, onDismiss: () -> Unit, actions: List<DetailAction> = emptyList(),
    content: @Composable ColumnScope.() -> Unit) {
    var overflow by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                if (actions.size > 2) Box {
                    TextButton(onClick = { overflow = true }) { Text("更多") }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        actions.drop(2).forEach { action ->
                            DropdownMenuItem(text = { Text(action.label) }, onClick = { overflow = false; action.onClick() })
                        }
                    }
                }
            }
            content()
            actions.take(2).forEach { action ->
                OutlinedButton(onClick = action.onClick, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
            }
        }
    }
}
