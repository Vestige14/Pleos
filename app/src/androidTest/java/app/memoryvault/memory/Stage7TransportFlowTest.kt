package app.memoryvault.memory

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.MemoryVaultApp
import app.memoryvault.data.*
import app.memoryvault.domain.DriveState
import app.memoryvault.memory.ui.MemoryReviewScreen
import app.memoryvault.sync.*
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Dedicated-device acceptance: real local HTTP and production UI, UUID DB/synthetic pixels only. */
class Stage7TransportFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun syntheticPhotoRealHttpSuggestionConfirmAndRestartWithActualParkedGate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as MemoryVaultApp).container
        withTimeout(5000) { container.driveSnapshot.first { it.state == DriveState.PARKED } }
        val gate = MemoryReviewGate(container.driveSnapshot); gate.start(); val epoch = requireNotNull(gate.capture())
        val name = "stage7-test-http-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val session = requireNotNull(MemoryLinkSessionStore(context).load()) { "Existing local session required" }
        val endpoint = MemoryLinkEndpoint.validate(session.endpoint, true)
        check(endpoint.port == 18761 && endpoint.host in setOf("127.0.0.1", "localhost"))
        val api = HttpMemoryAssociationApi(true)
        try {
            val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val output = ByteArrayOutputStream()
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) } finally { bitmap.recycle() }
            val bytes = output.toByteArray(); val image = SanitizedMemoryPhoto(bytes, MemoryWire.sha256(bytes))
            val descriptions = MemoryPhotoDescriptions(db, api)
            val description = descriptions.description(image, session) { gate.allows(epoch) }
            assertEquals("합성 파란 바다", description)
            val captured = OffsetDateTime.parse("2026-11-01T14:00:00+09:00").toInstant().toEpochMilli()
            val text = "STAGE7_SYNTHETIC 오늘 합성 바다"
            val id = MemoryRepository(db, enqueueVoiceLink = { MemoryAssociationStore(db).register(it) })
                .saveVoiceMemo(UUID.randomUUID().toString(), text, captured)
            val note = requireNotNull(db.noteDao().get(id))
            val photo = Photo("content://stage7-synthetic-http", captured - 60000, null, null, null, LinkState.UNLINKED)
            db.photoDao().upsert(listOf(photo))
            val candidate = MemoryCandidate("photo:synthetic", MemoryKind.PHOTO, "2026-11-01", MemoryTimeBasis.PHOTO_CAPTURE,
                description, MemoryWire.timestamp(photo.takenAt!!), null, null, null, null)
            val snap = AssociationSnapshot(ExperienceTimeResolver.resolve(text, captured), listOf(MemorySource(candidate, photo.uri, MemorySourceFingerprint.photo(photo))))
            val queue = MemoryAssociationQueue(db)
            val jobId = requireNotNull(queue.enqueue(note, 0, snap, 1, false, emptySet()) { gate.allows(epoch) })
            val lease = requireNotNull(queue.claim(jobId)); assertTrue(queue.begin(lease))
            val result = api.link(lease.request, session) as? MemoryApiResult.Success ?: error("Real loopback failed")
            assertEquals(ApplyOutcome.APPLIED, queue.apply(jobId, lease.token, result.value))
            val store = MemoryAssociationStore(db); val item = mutableStateOf(store.items().single())
            compose.setContent { MaterialTheme {
                MemoryReviewScreen(MemoryAssociationProgress(listOf(item.value)), gate.allows(epoch), false, null, null,
                    {}, {}, {}, { latest, action, keys ->
                        runBlocking {
                            assertEquals(MemoryReviewResult.SAVED, store.decide(latest.note.id, latest.note.revision,
                                latest.userRevision, action, keys) { gate.allows(epoch) })
                            item.value = store.items().single()
                        }
                    }, {}, { _, _, _ -> }, {}, {})
            } }
            compose.onAllNodesWithText(text).onFirst().performScrollTo().assertIsDisplayed()
            compose.onNode(isToggleable()).performScrollTo().performClick()
            compose.onNodeWithText("선택한 제안 확정").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("CONFIRMED", store.items().single().state.status)
            assertEquals(note, db.noteDao().get(id))
            db.close(); db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            assertEquals("CONFIRMED", MemoryAssociationStore(db).items().single().state.status)
            assertEquals(description, MemoryPhotoDescriptions(db, api).cached(image.sha256))
            assertEquals(1, db.aiLinkDao().getAllJobs().size)
            // Observe the existing Application provider only. Never initialize a second NaviHelper.
            val provider = container.navigationEvidence
            println("STAGE7_NAVIGATION_STATUS=${provider.status()};ROWS=${provider.snapshot().size}")
            assertTrue(provider.snapshot().all { it.eventAtMillis == null })
            assertTrue(provider.snapshot().filter { it.kind == app.memoryvault.memorycontract.NavigationEvidenceKind.ARRIVAL_OBSERVED }
                .all { it.latitude == null && it.longitude == null })
        } finally { gate.stop(); db.close(); context.deleteDatabase(name) }
    }
}
