package app.memoryvault.memory.ui

import android.content.Intent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.memoryvault.R
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Root adds exactly this entry to its parked screen; every click rechecks live state and generation. */
@Composable fun MemoryAssociationEntry(drive: StateFlow<DriveSnapshot>) {
    val displayed by drive.collectAsStateWithLifecycle()
    val generation = displayed.invalidationGeneration
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (displayed.state == DriveState.PARKED) {
        OutlinedButton(onClick = {
            val current = drive.value
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && current.state == DriveState.PARKED &&
                current.invalidationGeneration == generation) context.startActivity(Intent(context, MemoryReviewActivity::class.java))
        }, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.memory_review_title)) }
    }
}
