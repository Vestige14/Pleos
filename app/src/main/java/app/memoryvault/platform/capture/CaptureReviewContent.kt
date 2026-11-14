package app.memoryvault.platform.capture

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CaptureReviewContent(state: CaptureReviewState, allowed: Boolean,
    onOpen: () -> Unit, onPlay: (String) -> Unit, onStop: () -> Unit, onBack: () -> Unit,
    locationGranted: Boolean = false, onLocationPermission: () -> Unit = {}) {
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).padding(horizontal = 24.dp),
            contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("원음 · STT 기록", style = MaterialTheme.typography.headlineMedium)
                Text("새 녹음은 실제 시각으로 기록합니다. 원음은 기기에만 보관하며 자동으로 외부에 보내지 않습니다.")
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 64.dp)) { Text("하루 초안으로") }
            }
            if (!allowed) {
                item { Text("기록 확인과 재생은 주차 중 화면을 열었을 때만 가능합니다.") }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (locationGranted) "선택적 녹음 위치 권한이 있습니다." else "위치 권한 없이도 원음과 STT를 저장합니다.")
                        OutlinedButton(onClick = onLocationPermission, modifier = Modifier.heightIn(min = 64.dp)) { Text("녹음 위치 권한 선택") }
                        Text("위치는 녹음 시작 때 신선한 정보가 있는 경우만 기록합니다. 제공되지 않으면 위치 정보 없음으로 남깁니다.")
                        Button(onClick = onOpen, modifier = Modifier.heightIn(min = 64.dp)) {
                            Text(if (state.visible) "기록 새로 확인" else "기록 열기")
                        }
                        if (state.failed) Text("기록을 읽거나 재생하지 못했습니다. 원본 파일은 유지됩니다.", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (state.visible) {
                    if (state.items.isEmpty()) item { Text("아직 원음 기록이 없습니다. 기존 STT 메모는 하루 초안에서 확인합니다.") }
                    items(state.items, key = { it.sessionId }) { artifact ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(captureTime(artifact.capturedAt), style = MaterialTheme.typography.titleMedium)
                                Text(when (artifact.state) {
                                    CaptureState.COMMITTED -> "원음 · STT 저장 완료"
                                    CaptureState.NOTE_PENDING -> "텍스트 저장 확인 필요"
                                    CaptureState.PENDING -> "원음 보관 · STT 완료 대기"
                                    CaptureState.STT_FAILED -> "원음 보관 · STT 미완료"
                                    CaptureState.CAPTURING -> "녹음 파일 준비 중"
                                    CaptureState.INTERRUPTED -> "완료되지 않은 녹음"
                                    CaptureState.CONFLICT -> "원문 연결 확인 필요"
                                    CaptureState.BROKEN -> "음성 파일 확인 필요"
                                })
                                Text(artifact.transcript ?: "STT 텍스트 없음 · 원음 상태를 확인해 주세요.")
                                val fix = artifact.location
                                if (fix == null) Text("위치 정보 없음") else {
                                    Text("위치 ${fix.latitude}, ${fix.longitude}")
                                    Text("출처 ${fix.source} · 측위 ${captureTime(fix.fixTime)} · 확인 ${captureTime(fix.observedTime)}")
                                    Text(fix.accuracyMeters?.let { "정확도 ${it}m" } ?: "정확도 정보 없음")
                                }
                                val playable = artifact.state !in setOf(CaptureState.CAPTURING, CaptureState.INTERRUPTED, CaptureState.BROKEN)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = { onPlay(artifact.sessionId) }, enabled = playable,
                                        modifier = Modifier.heightIn(min = 64.dp)) { Text("원음 재생") }
                                    OutlinedButton(onClick = onStop, enabled = state.playingSession == artifact.sessionId,
                                        modifier = Modifier.heightIn(min = 64.dp)) { Text("재생 중지") }
                                }
                                if (state.playingSession == artifact.sessionId) Text("원음 재생 준비 · 재생 중")
                                if (artifact.state == CaptureState.NOTE_PENDING) Text("앱 재시작 때 이미 저장된 원문과의 연결을 다시 확인합니다. 저장 실패를 완료로 표시하지 않습니다.")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun captureTime(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss '(Asia/Seoul)'")
    .format(Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Seoul")))
