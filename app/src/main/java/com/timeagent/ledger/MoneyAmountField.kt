package com.timeagent.ledger

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/** Keep partial input while typing; format valid integer cents after focus leaves the field. */
@Composable
fun MoneyAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowNegative: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
) {
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    var hadFocus by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { focusState ->
            if (hadFocus && !focusState.isFocused) {
                normalizeAmountText(latestValue, allowNegative)?.let { normalized ->
                    if (normalized != latestValue) latestOnValueChange(normalized)
                }
            }
            hadFocus = focusState.isFocused
        },
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        trailingIcon = if (allowNegative) {
            {
                TextButton(
                    enabled = enabled,
                    onClick = {
                        onValueChange(if (value.startsWith("-")) value.removePrefix("-") else "-$value")
                    },
                ) { Text(androidx.compose.ui.res.stringResource(com.timeagent.R.string.text_money_amount_field_001)) }
            }
        } else null,
    )
}

private fun normalizeAmountText(value: String, allowNegative: Boolean): String? {
    val minor = if (allowNegative) Money.parseSignedToMinor(value) else Money.parseToMinor(value)
    return minor?.let { Money.plain(it) }
}
