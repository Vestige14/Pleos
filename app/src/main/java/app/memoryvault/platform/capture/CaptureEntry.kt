package app.memoryvault.platform.capture

import android.content.Intent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import kotlinx.coroutines.flow.StateFlow

@Composable
fun CaptureEntry(drive: StateFlow<DriveSnapshot>) {
    val snapshot by drive.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val generation = snapshot.invalidationGeneration
    if (snapshot.state == DriveState.PARKED) {
        OutlinedButton(onClick = {
            val current = drive.value
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                current.state == DriveState.PARKED && current.invalidationGeneration == generation) {
                context.startActivity(Intent(context, CaptureReviewActivity::class.java))
            }
        }, modifier = Modifier.heightIn(min = 64.dp)) { Text("원음 · STT 기록 확인") }
    }
}
