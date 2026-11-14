package app.memoryvault.memory

import app.memoryvault.sync.LinkDecision
import app.memoryvault.sync.LinkProtocolException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable enum class MemoryKind { PHOTO, NOTE, NAVIGATION, VISIT }
@Serializable enum class MemoryTimeBasis { PHOTO_CAPTURE, NOTE_EXPERIENCE, NAV_OBSERVED, NAV_ARRIVED, UNDATED_RECENT, VISIT }
@Serializable data class MemoryCandidate(
    val id: String, val kind: MemoryKind, val evidenceDate: String?, val timeBasis: MemoryTimeBasis,
    val description: String, val occurredAt: String?, val observedAt: String?, val placeName: String?,
    val latitude: Double?, val longitude: Double?,
) { override fun toString() = "MemoryCandidate([private])" }
@Serializable data class AssociationRequest(
    val requestId: String, val noteRevision: Int, val userRevision: Int, val contextHash: String,
    val capturedAt: String, val text: String, val timeScope: ExperienceTimeScope, val candidates: List<MemoryCandidate>,
) { override fun toString() = "AssociationRequest([private])" }
@Serializable data class AssociationSpan(val targetId: String, val kind: MemoryKind, val startCp: Int, val endCp: Int)
@Serializable data class AssociationResponse(
    val requestId: String, val noteRevision: Int, val userRevision: Int, val contextHash: String,
    val decision: LinkDecision, val links: List<AssociationSpan>,
)
@Serializable data class PhotoDescriptionRequest(
    val requestId: String, val imageSha256: String, val mimeType: String, val imageBase64: String,
) { override fun toString() = "PhotoDescriptionRequest([private])" }
@Serializable data class PhotoDescriptionResponse(
    val requestId: String, val imageSha256: String, val analysisVersion: String,
    val description: String, val labels: List<String>,
) { override fun toString() = "PhotoDescriptionResponse([private])" }

/** Local-only reference is never serialized into either API's payload. */
@Serializable data class MemorySource(val candidate: MemoryCandidate, val localRef: String, val fingerprint: String) {
    override fun toString() = "MemorySource([private])"
}
@Serializable data class AssociationSnapshot(val timeScope: ExperienceTimeScope, val sources: List<MemorySource>)

object MemoryWire {
    const val MODEL = "gpt-6.1-terra"
    const val PHOTO_VERSION = "visual-v1"
    const val LINK_VERSION = "memory-links-v2"
    val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false; encodeDefaults = true }
    private val integer = Regex("-?(?:0|[1-9][0-9]*)")
    private val hash = Regex("[a-f0-9]{64}")
    fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    fun digest(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))
    fun timestamp(millis: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        Instant.ofEpochMilli(millis).atZone(ExperienceTimeResolver.zone))
    fun contextHash(snapshot: AssociationSnapshot): String = digest(json.encodeToString(
        Context(snapshot.timeScope, snapshot.sources.map { it.candidate })))
    @Serializable private data class Context(val timeScope: ExperienceTimeScope, val candidates: List<MemoryCandidate>)

    fun encode(request: AssociationRequest): String = guarded {
        require(string(request.requestId, 128) && string(request.contextHash, 128))
        require(request.noteRevision >= 0 && request.userRevision >= 0 && string(request.text, 4000))
        OffsetDateTime.parse(request.capturedAt)
        require(request.candidates.size <= 20 && request.candidates.map { it.id }.distinct().size == request.candidates.size)
        val date = request.timeScope.eventDate
        if (request.timeScope.mode == ExperienceTimeMode.UNRESOLVED) require(date == null && request.candidates.isEmpty())
        else require(date != null && LocalDate.parse(date).toString() == date)
        require(request.candidates.count { it.kind == MemoryKind.PHOTO } <= 10)
        request.candidates.forEach { c ->
            require(string(c.id, 128) && string(c.description, if (c.kind == MemoryKind.NOTE) 4000 else 1000) && c.evidenceDate == date && date != null)
            require(c.placeName == null || string(c.placeName, 200))
            require((c.latitude == null) == (c.longitude == null))
            require(c.latitude == null || c.latitude.isFinite() && c.latitude in -90.0..90.0)
            require(c.longitude == null || c.longitude.isFinite() && c.longitude in -180.0..180.0)
            c.occurredAt?.let { OffsetDateTime.parse(it) }; c.observedAt?.let { OffsetDateTime.parse(it) }
            require(when (c.kind) {
                MemoryKind.PHOTO -> c.timeBasis == MemoryTimeBasis.PHOTO_CAPTURE
                MemoryKind.NOTE -> c.timeBasis == MemoryTimeBasis.NOTE_EXPERIENCE
                MemoryKind.VISIT -> c.timeBasis == MemoryTimeBasis.VISIT
                MemoryKind.NAVIGATION -> c.timeBasis in setOf(MemoryTimeBasis.NAV_OBSERVED, MemoryTimeBasis.NAV_ARRIVED)
            })
        }
        json.encodeToString(request).also { require(it.toByteArray(Charsets.UTF_8).size <= 65_536) }
    }

    fun encode(request: PhotoDescriptionRequest): String = guarded {
        require(string(request.requestId, 128) && hash.matches(request.imageSha256) && request.mimeType == "image/jpeg")
        val bytes = java.util.Base64.getDecoder().decode(request.imageBase64)
        require(bytes.size in 1..524_288 && sha256(bytes) == request.imageSha256)
        json.encodeToString(request).also { require(it.toByteArray(Charsets.UTF_8).size <= 1_048_576) }
    }

    fun response(request: AssociationRequest, body: String): AssociationResponse = guarded {
        val tree = strictObject(body)
        tree.integer("noteRevision"); tree.integer("userRevision")
        tree.getValue("links").jsonArray.forEach { it.jsonObject.integer("startCp"); it.jsonObject.integer("endCp") }
        json.decodeFromJsonElement<AssociationResponse>(tree).also { validate(request, it) }
    }
    fun validate(request: AssociationRequest, result: AssociationResponse) = guarded {
        require(result.requestId == request.requestId && result.noteRevision == request.noteRevision &&
            result.userRevision == request.userRevision && result.contextHash == request.contextHash)
        require(result.links.size <= 10)
        require(if (result.decision == LinkDecision.SUGGEST) result.links.isNotEmpty() else result.links.isEmpty())
        require(result.links.distinct().size == result.links.size)
        require(result.links.map { it.targetId }.distinct().size == result.links.size)
        result.links.forEach { span ->
            require(request.candidates.any { it.id == span.targetId && it.kind == span.kind && it.evidenceDate == request.timeScope.eventDate })
            val quote = evidence(request.text, span.startCp, span.endCp)
            require(quote.isNotBlank())
            val start = request.text.indexOf(quote)
            require(start >= 0 && request.text.indexOf(quote, start + 1) < 0)
        }
    }
    fun photoResponse(request: PhotoDescriptionRequest, body: String): PhotoDescriptionResponse = guarded {
        json.decodeFromJsonElement<PhotoDescriptionResponse>(strictObject(body)).also {
            require(it.requestId == request.requestId && it.imageSha256 == request.imageSha256 && it.analysisVersion == PHOTO_VERSION)
            require(string(it.description, 500) && it.labels.size <= 12 && it.labels.all { label -> string(label, 40) })
        }
    }
    fun evidence(text: String, startCp: Int, endCp: Int): String = guarded {
        require(startCp >= 0 && endCp > startCp && endCp <= text.codePointCount(0, text.length))
        text.substring(text.offsetByCodePoints(0, startCp), text.offsetByCodePoints(0, endCp))
    }
    fun string(value: String, max: Int) = value.isNotBlank() && value.codePointCount(0, value.length) <= max &&
        value.codePoints().noneMatch { it in 0xD800..0xDFFF }
    private fun JsonObject.integer(key: String) {
        val p = getValue(key).jsonPrimitive
        require(!p.isString && integer.matches(p.content) && p.content.toIntOrNull() != null)
    }
    /** Reject duplicate/escaped duplicate keys and bound nesting before deserializing. */
    private fun strictObject(body: String): JsonObject {
        require(body.toByteArray(Charsets.UTF_8).size <= 65_536)
        val keys = ArrayDeque<MutableSet<String>>()
        var depth = 0
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '{' -> { require(++depth <= 32); keys.addLast(mutableSetOf()) }
                '[' -> require(++depth <= 32)
                '}' -> { require(depth-- > 0 && keys.isNotEmpty()); keys.removeLast() }
                ']' -> require(depth-- > 0)
                '"' -> {
                    val start = i++
                    while (i < body.length) {
                        if (body[i] == '\\') i += 2 else if (body[i] == '"') break else i++
                    }
                    require(i < body.length)
                    var next = i + 1
                    while (next < body.length && body[next].isWhitespace()) next++
                    if (next < body.length && body[next] == ':') {
                        require(keys.isNotEmpty() && keys.last().add(json.decodeFromString<String>(body.substring(start, i + 1))))
                    }
                }
            }
            i++
        }
        require(depth == 0 && keys.isEmpty())
        return json.parseToJsonElement(body).jsonObject
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (_: Exception) { throw LinkProtocolException() }
}
