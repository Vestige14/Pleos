package app.memoryvault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun RecordDatePicker(ui: MemoryVaultUi, date: String) {
    val dates by ui.recordDates.collectAsStateWithLifecycle(initialValue = emptyList())
    val index = dates.indexOf(date)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (dates.size > 1) TextButton(onClick = { dates.getOrNull(index - 1)?.let(ui::selectRecordDate) },
            enabled = index > 0, modifier = Modifier.heightIn(min = 56.dp)) { Text("이전 기록") }
        Text(date, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp))
        if (dates.size > 1) TextButton(onClick = { dates.getOrNull(index + 1)?.let(ui::selectRecordDate) },
            enabled = index >= 0 && index < dates.lastIndex, modifier = Modifier.heightIn(min = 56.dp)) { Text("다음 기록") }
    }
}
