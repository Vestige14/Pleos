package app.memoryvault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Recording stays first when the available content width or larger text needs a column. */
@Composable
internal fun P2RecordShareActions(
    showShare: Boolean,
    record: @Composable (Modifier) -> Unit,
    share: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (showShare && maxWidth >= 720.dp && LocalDensity.current.fontScale <= 1.2f) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                record(Modifier.weight(1.2f))
                share(Modifier.weight(1f))
            }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            record(Modifier.fillMaxWidth())
            if (showShare) share(Modifier.fillMaxWidth())
        }
    }
}
