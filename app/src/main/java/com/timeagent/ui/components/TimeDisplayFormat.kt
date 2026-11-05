package com.timeagent.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun rememberTimeDisplayFormatter(includeDate: Boolean = false): DateTimeFormatter {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(is24Hour, locale, includeDate) {
        val date = if (includeDate) "yyyy-MM-dd " else ""
        DateTimeFormatter.ofPattern(date + if (is24Hour) "HH:mm" else "a h:mm", locale)
    }
}
