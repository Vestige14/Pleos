package app.memoryvault.platform.capture

import kotlinx.serialization.Serializable

@Serializable
enum class CaptureState { CAPTURING, PENDING, STT_FAILED, NOTE_PENDING, COMMITTED, INTERRUPTED, CONFLICT, BROKEN }

@Serializable
data class CaptureLocation(val latitude: Double, val longitude: Double, val source: String,
    val fixTime: Long, val observedTime: Long, val accuracyMeters: Float?)

@Serializable
data class CaptureArtifact(
    val sessionId: String,
    val privateAudioPath: String,
    val capturedAt: Long,
    val noteCapturedAt: Long,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val byteCount: Long = 0,
    val pcmSha256: String = "",
    val state: CaptureState = CaptureState.CAPTURING,
    val transcript: String? = null,
    val noteId: Long? = null,
    val location: CaptureLocation? = null,
)

data class CaptureNote(val id: Long, val text: String, val capturedAt: Long)
data class CaptureMoment(val wallTime: Long, val elapsedNanos: Long)
data class LocationFix(val latitude: Double, val longitude: Double, val provider: String,
    val wallTime: Long, val elapsedNanos: Long, val accuracyMeters: Float?)

object FreshCaptureLocation {
    /** Both clocks must agree that this fix predates capture by at most thirty seconds. */
    fun select(fixes: List<LocationFix>, now: CaptureMoment): CaptureLocation? = fixes.filter {
        it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0 &&
            it.provider.isNotBlank() && it.wallTime > 0 && it.wallTime <= now.wallTime &&
            now.wallTime - it.wallTime <= 30_000 &&
            it.elapsedNanos > 0 && it.elapsedNanos <= now.elapsedNanos &&
            now.elapsedNanos - it.elapsedNanos <= 30_000_000_000 &&
            (it.accuracyMeters == null || (it.accuracyMeters.isFinite() && it.accuracyMeters >= 0))
    }.maxByOrNull { it.elapsedNanos }?.let {
        CaptureLocation(it.latitude, it.longitude, it.provider, it.wallTime, now.wallTime, it.accuracyMeters)
    }
}
