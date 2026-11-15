package app.memoryvault.ui.p2.showcase

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.TypedMemoResult
import app.memoryvault.data.*
import app.memoryvault.domain.*
import app.memoryvault.memory.*
import app.memoryvault.memory.ui.MemoryReviewScreen
import app.memoryvault.platform.*
import app.memoryvault.service.*
import app.memoryvault.share.*
import app.memoryvault.share.ui.ShareFlowScreen
import app.memoryvault.ui.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Test-only, explicit prepared-data journey. No microphone, product store, network, or Activity forwarding. */
@Composable
internal fun P2RecordingFilmDemo(onClose: () -> Unit) {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val data = remember { JSONObject(assets.open("p2-film/scenario-data.json").bufferedReader().use { it.readText() }) }
    val scenarios = remember { data.getJSONArray("scenarios").let { array -> (0 until array.length()).map(array::getJSONObject) } }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var page by remember { mutableStateOf("home") }
    var followUp by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()
    val current = selected
    val ui = remember(current) { current?.let { RecordingDemo(it) } }
    val isolated = remember { ShowcaseContext(InstrumentationRegistry.getInstrumentation().targetContext) }
    BackHandler { if (page != "home") page = "home" else if (selected != null) selected = null else onClose() }
    CompositionLocalProvider(LocalContext provides isolated) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { selected = null; page = "home" }) { Text("시나리오 선택") }
                        if (current != null) TextButton(onClick = { page = "home" }) { Text(current.getString("title")) }
                        TextButton(onClick = onClose) { Text("검토 목록") }
                    }
                }
            }
            if (current == null || ui == null) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("기억을 남기고, 다시 만나고, 함께 나누기", style = MaterialTheme.typography.headlineMedium)
                    scenarios.forEachIndexed { index, scenario ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${index + 1}. ${scenario.getString("title")}", style = MaterialTheme.typography.titleLarge)
                                Text(when (scenario.getString("id")) {
                                    "father-son" -> "더현대 서울 · 선물 고민과 다음 날의 반응 → 기억 연결 → 가족 나들이 공유"
                                    "family-day" -> "식당 → 해변 → 카페 · 식당과 해변만 선택해서 공유"
                                    else -> "2026년 가을 · 수목원 방문에서 사진 없는 식당 다시 찾기"
                                })
                                Button(onClick = {
                                    selected = scenario; page = if (scenario.getString("id") == "rediscovery") "find" else "home"
                                    followUp = false; confirmed = false; found = false
                                }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("시작하기") }
                            }
                        }
                    }
                }
            } else {
                val voice by ui.voiceState.collectAsState()
                LaunchedEffect(ui, voice) {
                    if (voice == VoiceServiceState.Listening) {
                        delay(15_000)
                        ui.finishVoice() // Capture-state simulation only; prepared STT still requires a separate tap.
                    }
                }
                val shareStore = remember(current) { FilmSelectionStore() }
                val controller = remember(current) { ShareFlowController(shareStore) }
                val gate = remember(current) { ShareSessionGate(ui.driveSnapshot).apply { resume() } }
                val epoch = remember(gate) { requireNotNull(gate.capture()) }
                val foreground = remember { MutableStateFlow(true) }
                fun openShare(family: Boolean = false) {
                    scope.launch {
                        val scenario = if (family) scenarios.first { it.getString("id") == "family-day" } else current
                        controller.open(filmShareInput(scenario))
                        filmVisits(scenario).forEachIndexed { index, visit ->
                            visit.optString("caption").takeIf { it.isNotBlank() }?.let {
                                controller.setCaption("film-${index + 1}", it)
                            }
                        }
                        val message = if (family) current.optString("share_message") else ""
                        if (message.isNotBlank()) controller.setCaption("film-1", message)
                        page = "share"
                    }
                }
                when (page) {
                    "find" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("그때 갔던 곳, 다시 찾아보기", style = MaterialTheme.typography.headlineMedium)
                        OutlinedTextField(value = current.getString("period"), onValueChange = {}, readOnly = true, label = { Text("기간") })
                        OutlinedTextField(value = current.getString("query"), onValueChange = {}, readOnly = true, label = { Text("장소 단서") })
                        Button(onClick = { found = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("방문 후보 보기") }
                        if (found) {
                            val image by produceState<ImageBitmap?>(null) { value = ui.image("synthetic-film://autumn-garden.png") }
                            image?.let { Image(it, "수목원 사진", Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop) }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("2026-10-18 · 아침고요수목원", style = MaterialTheme.typography.titleLarge)
                                    Text("부모님과 간 날 · 같은 날 이어진 방문: 금강막국수")
                                    Button(onClick = { page = "home" }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("이날의 방문과 식당 보기") }
                                }
                            }
                            Text("2026-10-04 · 아침고요수목원 · 다른 방문 후보")
                        }
                    }
                    "links" -> Box(Modifier.weight(1f)) {
                        MemoryReviewScreen(
                            progress = MemoryAssociationProgress(items = listOf(filmReviewItem(current, confirmed))),
                            allowed = true, busy = false,
                            result = if (confirmed) MemoryReviewResult.SAVED else null, manual = null,
                            onOpen = {}, onClose = { page = "home" }, onRefresh = {},
                            onAction = { _, action, selectedKeys ->
                                if (action == MemoryReviewAction.CONFIRM && selectedKeys.isNotEmpty()) {
                                    confirmed = true; ui.confirmPhoto()
                                }
                            }, onManual = {}, onReanalyze = { _, _, _ -> },
                            onPhoto = { scope.launch { photo = ui.image("synthetic-film://dinosaur-gift.png") } }, onSetup = {},
                        )
                    }
                    "share" -> Box(Modifier.weight(1f)) {
                        ShareFlowScreen(controller, gate, epoch, ui.driveSnapshot, foreground,
                            onClose = { page = "home" }, onExport = { ShareExportResult.Blocked },
                            thumbnail = { ui.image(it) }, onSaveOnly = { ShareExportResult.Blocked }, demoPreviewOnly = true)
                    }
                    else -> {
                        if (current.getString("id") == "father-son") {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (voice == VoiceServiceState.Processing) Button(onClick = { ui.showPreparedVoice(false) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("음성 원문 보기") }
                                    if (ui.prepared) {
                                        Text(if (followUp) "${LocalDate.parse(current.getString("date")).plusDays(1)} · 음성 원문: ${current.getString("next_day_voice")}"
                                            else "음성 원문: ${current.getString("first_voice")}")
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            if (!followUp) OutlinedButton(onClick = { followUp = true; ui.showPreparedVoice(true) }) { Text("다음 날의 기록 보기") }
                                            if (followUp) Button(onClick = { page = "links" }) { Text(if (confirmed) "확인한 기억 연결 보기" else "연결 제안 보기") }
                                            if (confirmed) OutlinedButton(onClick = { openShare(true) }) { Text("관련 가족 나들이 기억 공유") }
                                        }
                                    }
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            MemoryVaultScreen(ui, onRequestPermissions = {}, onRequestPhotoPermissions = {}, onShareDay = { openShare() })
                        }
                    }
                }
            }
        }
        photo?.let { bitmap -> AlertDialog(onDismissRequest = { photo = null }, confirmButton = {
            TextButton(onClick = { photo = null }) { Text("닫기") }
        }, title = { Text("사진 보기") }, text = { Image(bitmap, "공룡 선물 사진", Modifier.fillMaxWidth()) }) }
    }
}

private class RecordingDemo(private val scenario: JSONObject) : MemoryVaultUi {
    override val driveSnapshot = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
    override val voiceState = MutableStateFlow<VoiceServiceState>(VoiceServiceState.Idle)
    override val voiceTiming = MutableStateFlow(VoiceCaptureTiming())
    override val readyNotificationEnabled = MutableStateFlow(false)
    override val photoReview = MutableStateFlow(PhotoReviewState(PhotoPermissions(PhotoAccess.FULL, true),
        PhotoScanStatus.UPDATED, PhotoThumbnailEpoch(0, 0)))
    override val day = MutableStateFlow<DayDraftState>(DayDraftState.Ready(filmDay(scenario)))
    var prepared by mutableStateOf(false)
        private set
    override fun startVoice(): VoiceStartRequest {
        if (voiceState.value in setOf(VoiceServiceState.Listening, VoiceServiceState.Preparing, VoiceServiceState.Processing))
            return VoiceStartRequest.Rejected(VoiceStartBlock.BUSY)
        prepared = false
        voiceTiming.value = VoiceCaptureTiming(System.nanoTime() / 1_000_000)
        voiceState.value = VoiceServiceState.Listening
        return VoiceStartRequest.Dispatched
    }
    override fun finishVoice(): Boolean {
        if (voiceState.value != VoiceServiceState.Listening) return false
        voiceTiming.value = voiceTiming.value.copy(endedAtElapsedMs = System.nanoTime() / 1_000_000)
        voiceState.value = VoiceServiceState.Processing
        return true
    }
    override fun cancelVoice() {
        voiceTiming.value = voiceTiming.value.copy(endedAtElapsedMs = System.nanoTime() / 1_000_000)
        voiceState.value = VoiceServiceState.Finished(VoiceServiceResult.Completed(VoiceMemoOutcome.Cancelled(VoiceCancelReason.USER)))
    }
    fun showPreparedVoice(nextDay: Boolean) {
        prepared = true
        day.value = DayDraftState.Ready(filmDay(scenario, nextDay, includeVoice = true))
        voiceState.value = VoiceServiceState.Finished(VoiceServiceResult.Completed(VoiceMemoOutcome.Saved(if (nextDay) 502 else 501)))
    }
    fun confirmPhoto() {
        val current = (day.value as? DayDraftState.Ready)?.draft ?: return
        day.value = DayDraftState.Ready(current.copy(cards = current.cards.map { it.copy(photos = it.photos.map { p -> p.copy(linkState = LinkState.CONFIRMED) }) }))
    }
    override fun thumbnailAllowed(epoch: PhotoThumbnailEpoch) = driveSnapshot.value.state == DriveState.PARKED && epoch == photoReview.value.thumbnailEpoch
    override suspend fun loadPhotoThumbnail(uri: String, epoch: PhotoThumbnailEpoch): ImageBitmap? = if (thumbnailAllowed(epoch)) image(uri) else null
    suspend fun image(uri: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!uri.startsWith("synthetic-film://")) return@withContext null
        val name = uri.removePrefix("synthetic-film://")
        if (name !in setOf("dinosaur-gift.png", "noodles.png", "beach.png", "cafe.png", "autumn-garden.png")) return@withContext null
        InstrumentationRegistry.getInstrumentation().context.assets.open("p2-film/$name").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
        }
    }
    override fun refreshPhotos() = Unit
    override suspend fun setPhotoState(uri: String, state: LinkState) = PhotoReviewResult.BLOCKED
    override suspend fun addTypedNote(visitId: String, text: String) = TypedMemoResult.NOT_EDITABLE
    override suspend fun updateTypedNote(noteId: Long, text: String) = TypedMemoResult.NOT_EDITABLE
    override fun setReadyNotificationEnabled(enabled: Boolean) = false
    override fun retryVehicleConnection() = Unit
}

private fun filmVisits(scenario: JSONObject): List<JSONObject> {
    if (scenario.getString("id") == "father-son") return listOf(JSONObject().put("name", scenario.getString("place"))
        .put("date", scenario.getString("date")).put("time", "14:00–15:00").put("image", scenario.getString("image")))
    val visits = scenario.getJSONArray("visits")
    return (0 until visits.length()).map(visits::getJSONObject)
}
private fun filmDate(scenario: JSONObject) = scenario.optString("date").ifBlank { scenario.getJSONArray("visits").getJSONObject(0).getString("date") }
private fun filmTime(date: String, time: String = "00:00"): Long = LocalDate.parse(date).atTime(LocalTime.parse(time))
    .atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

private fun filmDay(scenario: JSONObject, nextDay: Boolean = false, includeVoice: Boolean = false): DayDraft {
    val date = filmDate(scenario)
    val cards = filmVisits(scenario).mapIndexed { index, v ->
        val visitDate = v.optString("date").ifBlank { date }
        val range = v.optString("time", "00:00–00:00").split('–')
        val visit = Visit("film-${index + 1}", visitDate, v.getString("name"), 0.0, 0.0,
            filmTime(visitDate, range.first()), filmTime(visitDate, range.last()))
        val image = v.optString("image").takeIf { it.isNotBlank() && it != "null" }
        val photos = if (image == null || scenario.getString("id") == "father-son" && !nextDay) emptyList() else
            listOf(Photo("synthetic-film://$image", visit.arrivedAt, null, null, visit.id, LinkState.CANDIDATE))
        val notes = if (includeVoice && index == 0) listOf(Note(501, visit.id, scenario.getString("first_voice"),
            NoteKind.VOICE, visit.arrivedAt, sourceDate = date)) else emptyList()
        VisitCard(visit, photos, notes)
    }
    val nextDate = LocalDate.parse(date).plusDays(1).toString()
    return DayDraft(date, cards, pendingNotes = if (nextDay) listOf(Note(502, null,
        scenario.getString("next_day_voice"), NoteKind.VOICE, filmTime(nextDate, "10:00"), sourceDate = nextDate)) else emptyList())
}

private fun filmShareInput(scenario: JSONObject) = ShareInput(filmDate(scenario), filmVisits(scenario).mapIndexed { index, v ->
    val date = v.optString("date").ifBlank { filmDate(scenario) }
    val image = v.optString("image").takeIf { it.isNotBlank() && it != "null" }
    ShareVisitInput("film-${index + 1}", date, v.getString("name"), filmTime(date, v.optString("time", "00:00–00:00").substringBefore('–')),
        if (image == null) emptyList() else listOf(SharePhotoInput("synthetic-film://$image", SharePhotoState.CONFIRMED, true)))
})

private class FilmSelectionStore : ShareSelectionStore {
    private var selection: ShareSelection? = null
    override suspend fun load(date: String) = selection?.takeIf { it.date == date }
    override suspend fun save(selection: ShareSelection) { this.selection = selection }
}

private fun filmReviewItem(scenario: JSONObject, confirmed: Boolean): MemoryReviewItem {
    val text = scenario.getString("next_day_voice")
    val date = LocalDate.parse(scenario.getString("date")).plusDays(1).toString()
    val note = Note(502, null, text, NoteKind.VOICE, filmTime(date, "10:00"), sourceDate = date)
    val candidate = MemoryCandidate("film-gift", MemoryKind.PHOTO, date, MemoryTimeBasis.PHOTO_CAPTURE,
        "공룡 선물 · 더현대 서울 방문", null, null, "공룡 선물", null, null)
    val link = MemoryReviewLink("film-gift-link", candidate.id, candidate.kind, 0, text.codePointCount(0, text.length),
        if (confirmed) "CONFIRMED" else "SUGGESTED", "AI", MemorySource(candidate, "synthetic-film://dinosaur-gift.png", "synthetic"))
    return MemoryReviewItem(note, 0, false, MemoryReviewState(note.id, if (confirmed) "CONFIRMED" else "SUGGESTED"), listOf(link))
}
