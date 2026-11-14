package app.memoryvault.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class LinkSession(val endpoint: String, private val token: String) {
    internal fun authorization(): String {
        require(Regex("[A-Za-z0-9_-]{32,256}").matches(token)) { "INVALID_SESSION" }
        return "Bearer $token"
    }
    override fun toString() = "LinkSession([redacted])"
}
sealed interface ApiOutcome {
    data class Success(val result: LinkResponse) : ApiOutcome
    data class Failed(val failure: LinkFailure) : ApiOutcome
}
fun interface MemoryLinkApi { suspend fun link(request: LinkRequest, session: LinkSession): ApiOutcome }

/** One POST, no redirects/SDK retries/body logs; coroutine cancellation disconnects blocking I/O. */
class HttpMemoryLinkApi(private val allowLocalHttp: Boolean,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : MemoryLinkApi {
    companion object {
        private val io = Executors.newFixedThreadPool(4) { Thread(it, "memory-link-http").apply { isDaemon = true } }
    }
    override suspend fun link(request: LinkRequest, session: LinkSession): ApiOutcome {
        val payload = try { MemoryLinkProtocol.encodeRequest(request).toByteArray(Charsets.UTF_8) }
            catch (_: LinkProtocolException) { return failed(400, LinkError.INVALID_REQUEST) }
        val url = try { MemoryLinkEndpoint.validate(session.endpoint, allowLocalHttp).toURL() }
            catch (_: IllegalArgumentException) { return failed(400, LinkError.CONFIG_REQUIRED) }
        val auth = try { session.authorization() }
            catch (_: IllegalArgumentException) { return failed(400, LinkError.CONFIG_REQUIRED) }
        return withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine { continuation ->
                val connection = AtomicReference<HttpURLConnection?>()
                val submitted = AtomicReference<Future<*>?>()
                continuation.invokeOnCancellation {
                    connection.get()?.disconnect()
                    submitted.get()?.cancel(true)
                }
                val future = io.submit {
                    var conn: HttpURLConnection? = null
                    val result = try {
                        if (!continuation.isActive) return@submit
                        conn = open(url)
                        connection.set(conn)
                        if (!continuation.isActive) { conn.disconnect(); return@submit }
                        conn.requestMethod = "POST"
                        conn.instanceFollowRedirects = false
                        conn.useCaches = false
                        conn.connectTimeout = 30_000
                        conn.readTimeout = 30_000
                        conn.doOutput = true
                        // Fixed streaming disables HttpURLConnection's automatic replay for redirects/auth.
                        conn.setFixedLengthStreamingMode(payload.size)
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        conn.setRequestProperty("Accept", "application/json")
                        conn.setRequestProperty("Authorization", auth)
                        conn.outputStream.use { it.write(payload) }
                        val status = conn.responseCode
                        if (status == 401) failed(status, LinkError.UNAUTHORIZED)
                        else if (status in 300..399) failed(status, LinkError.HTTP_ERROR)
                        else {
                            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                            val bytes = stream?.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > 65_536) throw LinkProtocolException()
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            } ?: ByteArray(0)
                            val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                            if (status == 200) {
                                if (!conn.contentType.orEmpty().substringBefore(';').trim().equals("application/json", true)) throw LinkProtocolException()
                                ApiOutcome.Success(MemoryLinkProtocol.decodeResponse(request, body))
                            } else ApiOutcome.Failed(MemoryLinkProtocol.decodeFailure(status, body,
                                conn.getHeaderField("Retry-After"), System.currentTimeMillis()))
                        }
                    } catch (_: ConnectException) {
                        ApiOutcome.Failed(LinkFailure(503, LinkError.NETWORK_UNAVAILABLE, true))
                    } catch (_: CharacterCodingException) {
                        failed(502, LinkError.MALFORMED_RESPONSE)
                    } catch (_: IOException) {
                        failed(503, LinkError.OUTCOME_UNKNOWN)
                    } catch (_: Exception) {
                        failed(502, LinkError.MALFORMED_RESPONSE)
                    } finally {
                        conn?.disconnect()
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
                submitted.set(future)
                if (!continuation.isActive) future.cancel(true)
            }
        } ?: failed(503, LinkError.OUTCOME_UNKNOWN)
    }
    private fun failed(status: Int, code: LinkError) = ApiOutcome.Failed(LinkFailure(status, code, false))
}
