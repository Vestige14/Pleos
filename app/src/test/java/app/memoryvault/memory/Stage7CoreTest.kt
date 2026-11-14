package app.memoryvault.memory

import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import app.memoryvault.sync.LinkDecision
import app.memoryvault.sync.LinkProtocolException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class Stage7CoreTest {
    private val captured = Instant.parse("2026-10-31T15:30:00Z").toEpochMilli()
    @Test fun todayAndEarlierUseCaptureDayAcrossSeoulMidnight() {
        for (text in listOf("오늘 간 바다 예쁘더라", "아까 간 바다", "바다 예쁘더라")) {
            assertEquals(text, ExperienceTimeScope(ExperienceTimeMode.CAPTURE_DAY, "2026-11-01"), ExperienceTimeResolver.resolve(text, captured))
        }
    }
    @Test fun explicitRelativeAndAmbiguousDatesAreConservative() {
        for ((text, date) in mapOf("10/9 바다" to "2026-10-09", "어제 바다" to "2026-10-31", "그제 바다" to "2026-10-30", "2월 29일 바다" to "2024-02-29")) {
            assertEquals(ExperienceTimeScope(ExperienceTimeMode.EXPLICIT_DATE, date), ExperienceTimeResolver.resolve(text, captured))
        }
        for (text in listOf("10/9와 오늘 바다", "예전에 바다", "지난번 바다")) {
            assertEquals(ExperienceTimeMode.UNRESOLVED, ExperienceTimeResolver.resolve(text, captured).mode)
        }
    }
    @Test fun foregroundAndUnknownGenerationCannotRestoreAnOldReviewCapability() {
        val drive = MutableStateFlow(DriveSnapshot(DriveState.PARKED))
        val gate = MemoryReviewGate(drive)
        assertNull(gate.capture())
        gate.start()
        val epoch = requireNotNull(gate.capture())
        drive.value = drive.value.copy(state = DriveState.DRIVING)
        assertFalse(gate.allows(epoch))
        drive.value = DriveSnapshot(DriveState.PARKED, invalidationGeneration = 1)
        assertFalse(gate.allows(epoch))
        gate.start()
        assertTrue(gate.allows(requireNotNull(gate.capture())))
        gate.stop()
        assertNull(gate.capture())
    }
    @Test fun unicodeEvidenceAndEchoAreValidatedWithoutRewritingOriginal() {
        val text = "오늘 바다 🌊 예쁘더라"
        val c = MemoryCandidate("photo:synthetic", MemoryKind.PHOTO, "2026-11-01", MemoryTimeBasis.PHOTO_CAPTURE,
            "바다", "2026-11-01T12:00:00+09:00", null, null, null, null)
        val request = AssociationRequest("req", 1, 0, "hash", MemoryWire.timestamp(captured), text,
            ExperienceTimeScope(ExperienceTimeMode.CAPTURE_DAY, "2026-11-01"), listOf(c))
        val start = text.codePointCount(0, text.indexOf("바다"))
        val response = AssociationResponse("req", 1, 0, "hash", LinkDecision.SUGGEST,
            listOf(AssociationSpan(c.id, c.kind, start, start + 4)))
        MemoryWire.validate(request, response)
        assertEquals("바다 🌊", MemoryWire.evidence(text, start, start + 4))
        try { MemoryWire.validate(request, response.copy(userRevision = 2)); fail("stale echo") }
        catch (_: LinkProtocolException) { }
    }
}
