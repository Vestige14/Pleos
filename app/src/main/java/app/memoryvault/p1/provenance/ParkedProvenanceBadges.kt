package app.memoryvault.p1.provenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Read-only badges for an already authorized parked screen. Call only inside the existing
 * PARKED, foreground, and invalidation-generation rendering gate. This component is not a
 * replacement for that gate and performs no data access or interaction.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParkedProvenanceBadges(presentation: ProvenancePresentation, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                text = "출처 · ${presentation.sourceLabel}",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
            Text(
                text = "상태 · ${presentation.statusLabel}",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
