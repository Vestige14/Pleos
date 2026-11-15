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

internal enum class P2ActionLayout { SIDE_BY_SIDE, STACKED }

internal fun actionLayoutFor(contentWidthDp: Float, fontScale: Float): P2ActionLayout {
    val minPairWidth = 2f * 256f * fontScale.coerceAtLeast(1f) + 16f
    return if (contentWidthDp >= minPairWidth) P2ActionLayout.SIDE_BY_SIDE else P2ActionLayout.STACKED
}

/** Keeps two primary actions full-size when split windows or large text reduce their reading room. */
@Composable
fun P2ActionPair(
    modifier: Modifier = Modifier,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = actionLayoutFor(maxWidth.value, LocalDensity.current.fontScale)
        if (layout == P2ActionLayout.SIDE_BY_SIDE) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}
