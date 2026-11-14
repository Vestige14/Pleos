package app.memoryvault.sync

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class LinkCandidate(val id: String, val placeName: String, val arrivedAt: String, val leftAt: String) {
    override fun toString() = "LinkCandidate([redacted])"
}
@Serializable
data class LinkRequest(val requestId: String, val noteRevision: Int, val userRevision: Int, val contextHash: String,
    val capturedAt: String, val text: String, val candidates: List<LinkCandidate>) {
    override fun toString() = "LinkRequest([redacted])"
}
@Serializable enum class LinkDecision { SUGGEST, REVIEW, NO_MATCH }
@Serializable data class LinkSpan(val visitId: String, val startCp: Int, val endCp: Int)
@Serializable data class LinkResponse(val requestId: String, val noteRevision: Int, val userRevision: Int,
    val contextHash: String, val decision: LinkDecision, val links: List<LinkSpan>)
class LinkProtocolException : IllegalArgumentException("INVALID_LINK_CONTRACT")
enum class LinkError {
    ADMISSION_REJECTED, USAGE_RECORDING_FAILED, UNAUTHORIZED, CONFIG_REQUIRED, SESSION_STORAGE, INVALID_REQUEST, INVALID_BODY, BODY_TOO_LARGE, INVALID_JSON,
    UNSUPPORTED_MEDIA_TYPE, NOT_FOUND, METHOD_NOT_ALLOWED, HTTP_ERROR, INTERNAL_ERROR,
    REQUEST_ID_CONFLICT, SERVER_CLOSED, CACHE_CAPACITY, SERVER_BUSY, CALL_RATE_LIMIT,
    UPSTREAM_AUTH, UPSTREAM_QUOTA, UPSTREAM_CONFIG, UPSTREAM_RATE_LIMIT, UPSTREAM_UNAVAILABLE,
    OUTCOME_UNKNOWN, ATTEMPTS_EXHAUSTED, MALFORMED_RESPONSE, NETWORK_UNAVAILABLE,
}
data class LinkFailure(val status: Int, val code: LinkError, val retryable: Boolean, val retryAfterMillis: Long = 0)

/** No server text or parser cause escapes this boundary, even in validation exceptions. */
object MemoryLinkProtocol {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
    private val integerToken = Regex("-?(?:0|[1-9][0-9]*)")
    private val seoul = ZoneId.of("Asia/Seoul")
    private val transient = setOf(LinkError.CACHE_CAPACITY, LinkError.SERVER_BUSY, LinkError.CALL_RATE_LIMIT,
        LinkError.UPSTREAM_RATE_LIMIT, LinkError.UPSTREAM_UNAVAILABLE, LinkError.NETWORK_UNAVAILABLE)

    fun encodeRequest(request: LinkRequest): String = sanitized {
        require(validString(request.requestId, 128) && validString(request.contextHash, 128))
        require(request.noteRevision >= 0 && request.userRevision >= 0)
        require(validString(request.text, 4000) && request.candidates.size <= 20)
        require(request.candidates.map { it.id }.distinct().size == request.candidates.size)
        val captured = timestamp(request.capturedAt)
        val date = captured.atZone(seoul).toLocalDate()
        request.candidates.forEach { c ->
            require(validString(c.id, 128) && validString(c.placeName, 200))
            val arrived = timestamp(c.arrivedAt)
            require(arrived <= captured && arrived.atZone(seoul).toLocalDate() == date)
            require(timestamp(c.leftAt) >= arrived)
        }
        json.encodeToString(request).also { require(it.toByteArray(Charsets.UTF_8).size <= 65_536) }
    }

    fun decodeResponse(request: LinkRequest, body: String): LinkResponse = sanitized {
        encodeRequest(request)
        strictBody(body)
        val wire = json.parseToJsonElement(body).jsonObject
        wire.requireInteger("noteRevision")
        wire.requireInteger("userRevision")
        wire.getValue("links").jsonArray.forEach { link ->
            link.jsonObject.requireInteger("startCp")
            link.jsonObject.requireInteger("endCp")
        }
        val result = json.decodeFromJsonElement<LinkResponse>(wire)
        validateResponse(request, result)
        result
    }

    fun validateResponse(request: LinkRequest, result: LinkResponse) = sanitized {
        require(result.requestId == request.requestId && result.noteRevision == request.noteRevision &&
            result.userRevision == request.userRevision && result.contextHash == request.contextHash)
        require(result.links.size <= 10)
        if (result.decision != LinkDecision.SUGGEST) require(result.links.isEmpty())
        else require(result.links.isNotEmpty())
        require(result.links.map { it.visitId }.distinct().size == result.links.size)
        require(result.links.map { it.startCp to it.endCp }.distinct().size == result.links.size)
        val ids = request.candidates.map { it.id }.toSet()
        result.links.forEach {
            require(it.visitId in ids)
            val quote = evidence(request.text, it)
            require(quote.isNotBlank())
            val first = request.text.indexOf(quote)
            require(first >= 0 && request.text.indexOf(quote, first + 1) < 0)
        }
    }

    fun evidence(text: String, span: LinkSpan): String = sanitized {
        require(span.startCp >= 0 && span.endCp > span.startCp && span.endCp <= text.codePointCount(0, text.length))
        text.substring(text.offsetByCodePoints(0, span.startCp), text.offsetByCodePoints(0, span.endCp))
    }

    @Serializable private data class ErrorBody(val error: ErrorValue)
    @Serializable private data class ErrorValue(val code: String, val retryable: Boolean)
    fun decodeFailure(status: Int, body: String, retryAfter: String?, now: Long): LinkFailure {
        if (status == 401) return LinkFailure(status, LinkError.UNAUTHORIZED, false)
        return try {
            strictBody(body)
            val wire = json.parseToJsonElement(body).jsonObject
            val retryable = wire.getValue("error").jsonObject.getValue("retryable").jsonPrimitive
            require(!retryable.isString && retryable.booleanOrNull != null)
            val error = json.decodeFromJsonElement<ErrorBody>(wire).error
            val code = LinkError.valueOf(error.code)
            val retry = error.retryable && code in transient && (status == 429 || status in 500..599)
            LinkFailure(status, code, retry, if (retry) retryDelay(retryAfter, now) else 0)
        } catch (_: Exception) {
            LinkFailure(status, LinkError.MALFORMED_RESPONSE, false)
        }
    }

    // Check integer tokens before serializer coercion; quoted/decimal/exponent values are not integers here.
    private fun JsonObject.requireInteger(name: String) {
        val value = getValue(name).jsonPrimitive
        require(!value.isString && integerToken.matches(value.content) && value.content.toIntOrNull() != null)
    }

    private fun retryDelay(value: String?, now: Long): Long {
        if (value == null || value.length > 128) return 0
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
        return try {
            val deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (deadline - now).coerceAtLeast(0)
        } catch (_: Exception) { 0 }
    }

    private fun validString(value: String, max: Int): Boolean = value.isNotBlank() &&
        value.codePointCount(0, value.length) <= max && value.codePoints().noneMatch { it in 0xD800..0xDFFF }
    private fun timestamp(value: String): Instant {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?(?:Z|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])").matches(value))
        return OffsetDateTime.parse(value).toInstant()
    }

    /** Bound tree parsing before decoding; also reject duplicate object keys. */
    private fun strictBody(body: String) {
        require(body.toByteArray(Charsets.UTF_8).size <= 65_536)
        val stack = ArrayDeque<MutableSet<String>>()
        var depth = 0
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '{' -> { require(++depth <= 32); stack.addLast(mutableSetOf()) }
                '[' -> require(++depth <= 32)
                '}' -> { require(depth-- > 0 && stack.isNotEmpty()); stack.removeLast() }
                ']' -> require(depth-- > 0)
                '"' -> {
                    val start = i++
                    while (i < body.length) {
                        if (body[i] == '\\') i += 2
                        else if (body[i] == '"') break
                        else i++
                    }
                    require(i < body.length)
                    val end = i
                    var next = i + 1
                    while (next < body.length && body[next].isWhitespace()) next++
                    if (next < body.length && body[next] == ':') {
                        require(stack.isNotEmpty())
                        val key = json.decodeFromString<String>(body.substring(start, end + 1))
                        require(stack.last().add(key))
                    }
                }
            }
            i++
        }
        require(depth == 0 && stack.isEmpty())
    }

    private inline fun <T> sanitized(block: () -> T): T = try { block() }
        catch (_: Exception) { throw LinkProtocolException() }
}

/** Demo transport can reach only loopback; release never permits HTTP. No default endpoint. */
object MemoryLinkEndpoint {
    fun validate(value: String, allowLocalHttp: Boolean): URI {
        try {
            val uri = URI(value)
            require(uri.scheme == "https" || uri.scheme == "http" && allowLocalHttp)
            require(uri.host == "localhost" || uri.host == "127.0.0.1")
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            require(uri.port == -1 || uri.port in 1..65535)
            return URI(uri.scheme, null, uri.host, uri.port, "/v1/memory-links", null, null)
        } catch (_: Exception) { throw IllegalArgumentException("INVALID_ENDPOINT") }
    }
}
