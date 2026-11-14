package app.memoryvault.navigation

import android.util.AtomicFile
import app.memoryvault.memorycontract.NavigationEvidence
import app.memoryvault.memorycontract.NavigationEvidenceKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface NavigationObservationStore {
    fun load(): List<NavigationEvidence>
    fun save(rows: List<NavigationEvidence>)
}

internal object NavigationRetention {
    const val MAX_ROWS = 256
    const val MAX_RECENT = 20
    const val MAX_NAME_CODEPOINTS = 200
    const val MAX_ADDRESS_CODEPOINTS = 500
    const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1_000
    const val MAX_FILE_BYTES = 1_048_576

    fun normalize(place: NavigationPlace?): NavigationPlace? {
        if (place == null || place.name.isBlank() ||
            place.name.codePointCount(0, place.name.length) > MAX_NAME_CODEPOINTS) return null
        val lat = place.latitude
        val lng = place.longitude
        val validCoordinates = lat != null && lng != null && lat.isFinite() && lng.isFinite() &&
            lat in -90.0..90.0 && lng in -180.0..180.0
        return place.copy(
            address = place.address?.takeIf {
                it.isNotBlank() && it.codePointCount(0, it.length) <= MAX_ADDRESS_CODEPOINTS
            },
            latitude = if (validCoordinates) lat else null,
            longitude = if (validCoordinates) lng else null,
        )
    }

    fun bound(rows: List<NavigationEvidence>, now: Long): List<NavigationEvidence> {
        if (now < 0) return emptyList()
        var recentCount = 0
        return rows.asSequence().filter {
            it.observedAtMillis in maxOf(0, now - MAX_AGE_MILLIS)..now
        }.sortedWith(compareByDescending<NavigationEvidence> { it.observedAtMillis }.thenBy { it.id })
            .distinctBy { it.id }
            .filter { it.kind != NavigationEvidenceKind.UNDATED_RECENT || ++recentCount <= MAX_RECENT }
            .take(MAX_ROWS).toList()
    }
}

/** One bounded, atomic, no-backup file; no main Room schema or external storage dependency. */
internal class FileNavigationObservationStore(directory: File) : NavigationObservationStore {
    private val directory = directory
    private val file = AtomicFile(File(directory, "observations-v1.json"))
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    override fun load(): List<NavigationEvidence> {
        val input = try { file.openRead() }
        catch (missing: FileNotFoundException) {
            if (file.baseFile.exists()) throw missing
            return emptyList()
        }
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (output.size() + read > NavigationRetention.MAX_FILE_BYTES) {
                    throw IOException("Navigation observation file exceeds limit")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val stored = json.decodeFromString<StoredObservations>(text)
        require(stored.version == 1 && stored.rows.size <= NavigationRetention.MAX_ROWS)
        return stored.rows.map { it.toEvidence() }
    }

    override fun save(rows: List<NavigationEvidence>) {
        require(rows.size <= NavigationRetention.MAX_ROWS)
        val bytes = json.encodeToString(StoredObservations(rows = rows.map { StoredObservation.from(it) }))
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= NavigationRetention.MAX_FILE_BYTES)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Navigation storage unavailable")
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
}

@Serializable
private data class StoredObservations(val version: Int = 1, val rows: List<StoredObservation>)

@Serializable
private data class StoredObservation(
    val id: String,
    val kind: String,
    val placeName: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val observedAtMillis: Long,
    val eventAtMillis: Long?,
) {
    fun toEvidence(): NavigationEvidence {
        require(id.isNotBlank() && id.length <= 128 && observedAtMillis >= 0 && eventAtMillis == null)
        val place = NavigationPlace(placeName, address, latitude, longitude)
        require(NavigationRetention.normalize(place) == place)
        return NavigationEvidence(
            id, NavigationEvidenceKind.valueOf(kind), placeName, address, latitude, longitude,
            observedAtMillis, eventAtMillis,
        )
    }

    companion object {
        fun from(row: NavigationEvidence) = StoredObservation(
            row.id, row.kind.name, row.placeName, row.address, row.latitude, row.longitude,
            row.observedAtMillis, row.eventAtMillis,
        )
    }
}
