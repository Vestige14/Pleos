package app.memoryvault.share.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.memoryvault.R
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.share.ShareExportReceiptStore
import app.memoryvault.share.ShareReceiptVisibility
import app.memoryvault.share.SharedPreferencesShareReceiptStore
import kotlinx.coroutines.flow.StateFlow

/** A factual saved-image hint only: no external action and no claim about an external share outcome. */
@Composable
fun ShareSavedReceiptNotice(snapshots: StateFlow<DriveSnapshot>, date: String,
    receiptStore: ShareExportReceiptStore? = null) {
    val context = LocalContext.current.applicationContext
    val store = receiptStore ?: remember(context) { SharedPreferencesShareReceiptStore.open(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val displayed by snapshots.collectAsStateWithLifecycle()
    val receipt by store.latest.collectAsStateWithLifecycle()
    val visible = ShareReceiptVisibility.forCurrentDay(receipt, date, displayed, snapshots, foreground)
    if (visible != null) Card(Modifier.fillMaxWidth().testTag("share-saved-receipt"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.share_saved_receipt_title), style = MaterialTheme.typography.titleMedium)
            Text(visible.displayName, modifier = Modifier.testTag("share-saved-filename"))
            Text(stringResource(R.string.share_saved_receipt_location), modifier = Modifier.testTag("share-saved-location"))
        }
    }
}
