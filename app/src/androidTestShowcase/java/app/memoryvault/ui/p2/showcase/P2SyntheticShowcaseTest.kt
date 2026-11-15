package app.memoryvault.ui.p2.showcase

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.data.DayDraft
import app.memoryvault.data.LinkState
import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.data.Photo
import app.memoryvault.data.Visit
import app.memoryvault.data.VisitCard
import app.memoryvault.domain.DriveState
import app.memoryvault.memory.MemoryAssociationProgress
import app.memoryvault.memory.MemoryCandidate
import app.memoryvault.memory.MemoryKind
import app.memoryvault.memory.MemoryReviewItem
import app.memoryvault.memory.MemoryReviewLink
import app.memoryvault.memory.MemoryReviewState
import app.memoryvault.memory.MemorySource
import app.memoryvault.memory.MemoryTimeBasis
import app.memoryvault.memory.ui.MemoryReviewScreen
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.platform.PhotoAccess
import app.memoryvault.platform.PhotoPermissions
import app.memoryvault.platform.capture.CaptureArtifact
import app.memoryvault.platform.capture.CaptureReviewContent
import app.memoryvault.platform.capture.CaptureReviewState
import app.memoryvault.platform.capture.CaptureState
import app.memoryvault.share.ShareExportResult
import app.memoryvault.share.ShareFlowController
import app.memoryvault.share.ShareInput
import app.memoryvault.share.SharePhotoInput
import app.memoryvault.share.SharePhotoState
import app.memoryvault.share.ShareSelection
import app.memoryvault.share.ShareSelectionStore
import app.memoryvault.share.ShareSessionGate
import app.memoryvault.share.ShareVisitInput
import app.memoryvault.share.ui.ShareFlowScreen
import app.memoryvault.sync.LinkSessionSummary
import app.memoryvault.sync.ui.MemoryLinkSetupScreen
import app.memoryvault.sync.ui.SetupInputState
import app.memoryvault.sync.ui.SetupNotice
import app.memoryvault.ui.DayDraftState
import app.memoryvault.ui.FixtureUi
import app.memoryvault.ui.MemoryVaultScreen
import app.memoryvault.ui.MemoryVaultTheme
import app.memoryvault.ui.PhotoReviewState
import app.memoryvault.ui.PhotoScanStatus
import app.memoryvault.ui.PhotoThumbnailEpoch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in, bounded visual fixture. It neither opens the product database nor starts an external flow. */
class P2SyntheticShowcaseTest {
    @get:Rule val compose = createAndroidComposeRule<ShowcaseHostActivity>()

    @Test fun inspectSyntheticScreens() {
        val mode = InstrumentationRegistry.getArguments().getString("p2_showcase")
        assumeTrue("Pass -e p2_showcase review or probe for this fixture",
            mode == "review" || mode == "probe")
        val session = ShowcaseSession()
        val homeCommitted = CountDownLatch(1)
        if (mode == "probe") session.onHomeCommitted = { homeCommitted.countDown() }
        compose.setContent { session.Content() }
        if (mode == "probe") {
            probeSceneCommit(session, homeCommitted)
        } else {
            awaitFinished(session, TimeUnit.MINUTES.toMillis(8), "Tap '검토 종료' within eight minutes")
        }
    }

    private fun probeSceneCommit(session: ShowcaseSession, homeCommitted: CountDownLatch) {
        val selected = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { session.probeSelectHome() }
                selected.countDown()
                homeCommitted.await(1, TimeUnit.SECONDS)
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            } finally {
                session.finished.countDown()
            }
        }, "p2-showcase-scene-probe").apply { isDaemon = true }
        worker.start()
        try {
            awaitFinished(session, 3_000, "Synthetic HOME probe did not finish within three seconds")
        } finally {
            worker.join(1_500)
            if (worker.isAlive) worker.interrupt()
            worker.join(250)
        }
        assertTrue("Synthetic HOME probe worker did not stop", !worker.isAlive)
        workerFailure.get()?.let { throw AssertionError("Synthetic HOME probe worker failed", it) }
        assertTrue("Synthetic HOME selection did not complete", selected.count == 0L)
        assertTrue("Synthetic HOME selection completed without a HOME composition commit", homeCommitted.count == 0L)
    }

    private fun awaitFinished(session: ShowcaseSession, timeoutMillis: Long, message: String) {
        compose.waitUntil(message, timeoutMillis) { session.finished.count == 0L }
    }
}

private enum class Scene(val title: String) {
    RECORDING_FILM("촬영 · 녹음부터 기억 공유까지 · 세 시나리오"),
    HOME_READY("홈 · 기록과 사진"),
    HOME_LOADING("홈 · 불러오는 중"),
    HOME_ERROR("홈 · 저장소 오류"),
    HOME_EMPTY("홈 · 빈 하루"),
    MEMORY_SUGGESTED("기억 연결 · AI 제안 두 사진"),
    MEMORY_LOADING("기억 연결 · 준비 중"),
    MEMORY_ERROR("기억 연결 · 오류"),
    MEMORY_EMPTY("기억 연결 · 빈 목록"),
    CAPTURE_READY("원음 기록"),
    CAPTURE_ERROR("원음 기록 · 읽기 실패"),
    CAPTURE_EMPTY("원음 기록 · 빈 목록"),
    SHARE_SELECT("공유 · 선택과 미리보기"),
    SHARE_LOADING("공유 · 불러오는 중"),
    SHARE_ERROR("공유 · 선택 저장 오류"),
    AI_SETUP("AI 설정 · 미설정"),
}

private class ShowcaseSession {
    val finished = CountDownLatch(1)
    var onHomeCommitted: (() -> Unit)? = null
    private val home = FixtureUi(DriveState.PARKED)
    private val shareDrive = MutableStateFlow(DriveSnapshot(state = DriveState.PARKED))
    private val shareForeground = MutableStateFlow(true)
    private val shareGate = ShareSessionGate(shareDrive).apply { resume() }
    private var shareEpoch = requireNotNull(shareGate.capture())
    private var shareController = ShareFlowController(MemorySelectionStore())
    private var setupState by mutableStateOf(SetupInputState(editing = true, endpoint = "https://example.invalid"))
    private var scene by mutableStateOf<Scene?>(null)
    private var narrow by mutableStateOf(false)
    private var largeText by mutableStateOf(false)
    private var controlsVisible by mutableStateOf(true)
    private var driveMode by mutableStateOf(DriveState.PARKED)
    private var generation = 0L
    private val syntheticImage = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.rgb(87, 143, 145))
    }.asImageBitmap()

    init {
        home.thumbnailDecoder = { syntheticImage }
        home.day.value = DayDraftState.Ready(syntheticDay())
        home.photoReview.value = PhotoReviewState(PhotoPermissions(PhotoAccess.FULL, true),
            PhotoScanStatus.UPDATED, PhotoThumbnailEpoch(0, 0))
    }

    private fun select(target: Scene) {
        controlsVisible = target != Scene.RECORDING_FILM
        changeDrive(DriveState.PARKED)
        home.day.value = when (target) {
            Scene.HOME_LOADING -> DayDraftState.Loading
            Scene.HOME_ERROR -> DayDraftState.Failed
            Scene.HOME_EMPTY -> DayDraftState.Ready(DayDraft("2026-11-08", emptyList()))
            else -> DayDraftState.Ready(syntheticDay())
        }
        home.photoReview.value = home.photoReview.value.copy(scanStatus =
            if (target == Scene.HOME_ERROR) PhotoScanStatus.STORAGE_UNAVAILABLE else PhotoScanStatus.UPDATED)
        if (target in setOf(Scene.SHARE_SELECT, Scene.SHARE_LOADING, Scene.SHARE_ERROR)) {
            shareController = ShareFlowController(MemorySelectionStore(failLoad = target == Scene.SHARE_ERROR))
            if (target != Scene.SHARE_LOADING) runBlocking { shareController.open(syntheticShareInput()) }
            shareEpoch = requireNotNull(shareGate.capture())
        }
        setupState = SetupInputState(editing = true, endpoint = "https://example.invalid")
        scene = target
    }

    fun probeSelectHome() = select(Scene.HOME_READY)

    private fun changeDrive(next: DriveState) {
        generation++
        driveMode = next
        val snapshot = DriveSnapshot(state = next, invalidationGeneration = generation)
        home.driveSnapshot.value = snapshot
        home.photoReview.value = home.photoReview.value.copy(thumbnailEpoch =
            if (next == DriveState.PARKED) PhotoThumbnailEpoch(0, generation) else null)
        shareDrive.value = snapshot
        if (next == DriveState.PARKED) shareGate.resume() else shareGate.stop()
    }

    private fun cycleDrive() = changeDrive(when (driveMode) {
        DriveState.PARKED -> DriveState.UNKNOWN
        DriveState.UNKNOWN -> DriveState.DRIVING
        else -> DriveState.PARKED
    })

    @Composable fun Content() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = remember(target) { ShowcaseContext(target) }
        val deviceDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalContext provides isolated,
            LocalDensity provides Density(deviceDensity.density, if (largeText) 1.6f else deviceDensity.fontScale),
        ) {
            MemoryVaultTheme {
                val composedScene = scene
                SideEffect { if (composedScene == Scene.HOME_READY) onHomeCommitted?.invoke() }
                BackHandler(enabled = scene != null) { scene = null }
                if (scene == null) Menu() else BoxWithConstraints(Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter) {
                    val contentWidth = if (narrow) minOf(maxWidth, 360.dp) else maxWidth
                    Box(Modifier.width(contentWidth).fillMaxHeight()) { ProductScreen(requireNotNull(scene)) }
                    if (controlsVisible) Controls(Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    @Composable private fun Menu() {
        LazyColumn(Modifier.fillMaxSize().testTag("p2-showcase-menu").padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("기억 저장소", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { narrow = !narrow }) {
                        Text(if (narrow) "좁은 폭 360dp" else "전체 폭")
                    }
                    OutlinedButton(onClick = { largeText = !largeText }) {
                        Text(if (largeText) "큰 글자 1.6배" else "기기 글자 크기")
                    }
                }
            }
            items(Scene.values().toList()) { choice ->
                Button(onClick = { select(choice) }, modifier = Modifier.fillMaxWidth()) { Text(choice.title) }
            }
            item { OutlinedButton(onClick = { finished.countDown() }) { Text("검토 종료") } }
        }
    }

    @Composable private fun Controls(modifier: Modifier) {
        Card(modifier.testTag("p2-showcase-controls")) {
            Column(Modifier.padding(8.dp)) {
                Text("화면 선택", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { scene = null }) { Text("목록") }
                    TextButton(onClick = ::cycleDrive) { Text("상태 ${driveMode.name} → 전환") }
                    TextButton(onClick = { controlsVisible = false }) { Text("조작 숨기기") }
                }
            }
        }
    }

    @Composable private fun ProductScreen(current: Scene) {
        val allowed = driveMode == DriveState.PARKED
        when (current) {
            Scene.RECORDING_FILM -> P2RecordingFilmDemo(onClose = { scene = null })
            Scene.HOME_READY, Scene.HOME_LOADING, Scene.HOME_ERROR, Scene.HOME_EMPTY ->
                MemoryVaultScreen(home, onRequestPermissions = {}, onRequestPhotoPermissions = {},
                    onShareDay = { select(Scene.SHARE_SELECT) })
            Scene.MEMORY_SUGGESTED, Scene.MEMORY_LOADING, Scene.MEMORY_ERROR, Scene.MEMORY_EMPTY ->
                MemoryReviewScreen(when (current) {
                    Scene.MEMORY_SUGGESTED -> MemoryAssociationProgress(listOf(syntheticReviewItem()))
                    Scene.MEMORY_LOADING -> MemoryAssociationProgress(preparing = true)
                    Scene.MEMORY_ERROR -> MemoryAssociationProgress(errorCode = "STORAGE_UNAVAILABLE")
                    else -> MemoryAssociationProgress()
                }, allowed, false, null, null, {}, { scene = null }, {}, { _, _, _ -> }, {},
                    { _, _, _ -> }, {}, {})
            Scene.CAPTURE_READY, Scene.CAPTURE_ERROR, Scene.CAPTURE_EMPTY ->
                CaptureReviewContent(when (current) {
                    Scene.CAPTURE_READY -> CaptureReviewState(visible = true, items = listOf(syntheticCapture()))
                    Scene.CAPTURE_ERROR -> CaptureReviewState(visible = true, failed = true)
                    else -> CaptureReviewState(visible = true)
                }, allowed, {}, {}, {}, { scene = null })
            Scene.SHARE_SELECT, Scene.SHARE_LOADING, Scene.SHARE_ERROR ->
                ShareFlowScreen(shareController, shareGate, shareEpoch, shareDrive, shareForeground,
                    onClose = { if (driveMode == DriveState.PARKED) scene = null },
                    onExport = { ShareExportResult.Blocked }, thumbnail = { syntheticImage })
            Scene.AI_SETUP -> MemoryLinkSetupScreen(setupState, allowed, LinkSessionSummary(false, null),
                onOpen = { setupState = setupState.copy(editing = true) },
                onEndpoint = { setupState = setupState.copy(endpoint = it) },
                onToken = { setupState = setupState.copy(token = it) },
                onSave = { setupState = setupState.copy(notice = SetupNotice.BLOCKED) },
                onBack = { scene = null })
        }
    }
}

private class MemorySelectionStore(private val failLoad: Boolean = false) : ShareSelectionStore {
    private var current: ShareSelection? = null
    override suspend fun load(date: String): ShareSelection? {
        if (failLoad) error("Synthetic selection load failed")
        return current?.takeIf { it.date == date }
    }
    override suspend fun save(selection: ShareSelection) { current = selection }
}

private fun syntheticDay(): DayDraft {
    val date = "2026-11-08"
    val visit = Visit("synthetic-visit", date, "합성 장소 · 긴 장소 이름이 여러 줄로 표시되는지 확인", 0.0, 0.0,
        1794103200000, 1794106800000)
    val voice = Note(91, visit.id, "합성 음성 원문입니다. 긴 문장이 화면 폭과 큰 글자에서도 잘리지 않고 읽혀야 합니다.",
        NoteKind.VOICE, 1794105000000, sourceDate = date)
    val typed = Note(92, visit.id, "직접 입력한 합성 메모", NoteKind.TYPED, 1794105300000, sourceDate = date)
    val photos = listOf(
        Photo("synthetic://photo-one", 1794105300000, null, null, visit.id, LinkState.CANDIDATE),
        Photo("synthetic://photo-two", 1794105400000, null, null, visit.id, LinkState.CONFIRMED),
    )
    return DayDraft(date, listOf(VisitCard(visit, photos, listOf(voice, typed))))
}

private fun syntheticReviewItem(): MemoryReviewItem {
    val original = "합성 음성 원문과 같은 부분을 가리키는 사진 두 장입니다. 출처와 연결 상태를 각각 확인합니다."
    val note = Note(201, null, original, NoteKind.VOICE, 1794105000000, sourceDate = "2026-11-08")
    fun link(key: String, label: String): MemoryReviewLink {
        val candidate = MemoryCandidate("photo:$key", MemoryKind.PHOTO, "2026-11-08",
            MemoryTimeBasis.PHOTO_CAPTURE, label, null, null, label, null, null)
        return MemoryReviewLink(key, candidate.id, candidate.kind, 0, original.codePointCount(0, original.length),
            "SUGGESTED", "AI", MemorySource(candidate, "synthetic:$key", "synthetic"))
    }
    return MemoryReviewItem(note, 0, false, MemoryReviewState(201, "SUGGESTED"),
        listOf(link("photo-one", "첫 번째 합성 사진"), link("photo-two", "두 번째 합성 사진")))
}

private fun syntheticCapture() = CaptureArtifact("synthetic-capture", "fixture-only-no-audio", 1794105000000,
    1794105000000, state = CaptureState.COMMITTED, transcript = "합성 STT 원문입니다. 원음 파일은 이 fixture에 없습니다.")

private fun syntheticShareInput() = ShareInput("2026-11-08", listOf(
    ShareVisitInput("synthetic-visit", "2026-11-08", "합성 장소 · 긴 이름", 1794103200000,
        listOf(SharePhotoInput("synthetic://photo-one", SharePhotoState.CANDIDATE, true),
            SharePhotoInput("synthetic://photo-two", SharePhotoState.CONFIRMED, true))),
    ShareVisitInput("synthetic-visit-two", "2026-11-08", "다른 합성 장소", 1794110400000, emptyList()),
))
