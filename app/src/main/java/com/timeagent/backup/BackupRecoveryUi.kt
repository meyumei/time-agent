package com.timeagent.backup

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timeagent.data.PersonalDataGate
import com.timeagent.ui.TimeAgentTheme

/** A failed automatic rollback keeps the data locked and leaves its preimage available to retry. */
fun ComponentActivity.recoverPersonalDataBeforeOpen(): Boolean {
    val failure = runCatching { PersonalBackup.recoverIfNeeded(applicationContext) }.exceptionOrNull() ?: return true
    PersonalDataGate.recoveryRequired = true
    setContent {
        TimeAgentTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("数据恢复等待完成回退", style = MaterialTheme.typography.headlineSmall)
                    Text("操作前的数据回退文件仍保留。完成回退前暂停写入，避免把两份数据库混用。")
                    Text(failure.message ?: "暂时无法完成本地回退，请稍后重试。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = ::recreate) { Text("重试本地回退") }
                }
            }
        }
    }
    return false
}
