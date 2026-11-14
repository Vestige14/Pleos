package app.memoryvault.memory

import app.memoryvault.sync.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

sealed interface MemoryApiResult<out T> {
    data class Success<T>(val value: T) : MemoryApiResult<T>
    data class Failed(val failure: LinkFailure) : MemoryApiResult<Nothing>
}
interface MemoryAssociationApi {
    suspend fun link(request: AssociationRequest, session: LinkSession): MemoryApiResult<AssociationResponse>
    suspend fun describe(request: PhotoDescriptionRequest, session: LinkSession, allowed: () -> Boolean): MemoryApiResult<PhotoDescriptionResponse>
}

/** Authenticated v2 on the existing validated loopback origin; one bounded POST and no body logs. */
class HttpMemoryAssociationApi(private val allowLocalHttp: Boolean,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : MemoryAssociationApi {
    companion object {
        private val io = Executors.newFixedThreadPool(2) { Thread(it, "memory-association-http").apply { isDaemon = true } }
    }
    override suspend fun link(request: AssociationRequest, session: LinkSession): MemoryApiResult<AssociationResponse> {
        val body = try { MemoryWire.encode(request) } catch (_: Exception) { return failure(LinkError.INVALID_REQUEST, 400) }
        return post(session, "/v2/memory-links", body, { true }) { MemoryWire.response(request, it) }
    }
    override suspend fun describe(request: PhotoDescriptionRequest, session: LinkSession,
        allowed: () -> Boolean): MemoryApiResult<PhotoDescriptionResponse> {
        val body = try { MemoryWire.encode(request) } catch (_: Exception) { return failure(LinkError.INVALID_REQUEST, 400) }
        return post(session, "/v2/photo-descriptions", body, allowed) { MemoryWire.photoResponse(request, it) }
    }
    private suspend fun <T> post(session: LinkSession, path: String, body: String, allowed: () -> Boolean,
        decode: (String) -> T): MemoryApiResult<T> {
        val url = try { MemoryLinkEndpoint.validate(session.endpoint, allowLocalHttp).resolve(path).toURL() }
            catch (_: Exception) { return failure(LinkError.CONFIG_REQUIRED, 400) }
        val auth = try { session.authorization() } catch (_: Exception) { return failure(LinkError.CONFIG_REQUIRED, 400) }
        val bytes = body.toByteArray(Charsets.UTF_8)
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { continuation ->
                val connection = AtomicReference<HttpURLConnection?>()
                val submitted = AtomicReference<Future<*>?>()
                continuation.invokeOnCancellation { connection.get()?.disconnect(); submitted.get()?.cancel(true) }
                val future = io.submit {
                    var conn: HttpURLConnection? = null
                    val result: MemoryApiResult<T> = try {
                        if (!continuation.isActive) return@submit
                        if (!allowed()) throw MemoryReviewBlocked()
                        conn = open(url)
                        connection.set(conn)
                        if (!continuation.isActive) { conn.disconnect(); return@submit }
                        conn.requestMethod = "POST"; conn.instanceFollowRedirects = false; conn.useCaches = false
                        conn.connectTimeout = 30_000; conn.readTimeout = 30_000; conn.doOutput = true
                        conn.setFixedLengthStreamingMode(bytes.size)
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        conn.setRequestProperty("Accept", "application/json"); conn.setRequestProperty("Authorization", auth)
                        if (!allowed()) throw MemoryReviewBlocked()
                        conn.outputStream.use { output ->
                            if (!allowed()) throw MemoryReviewBlocked()
                            output.write(bytes)
                        }
                        val status = conn.responseCode
                        if (status == 401) failure(LinkError.UNAUTHORIZED, status)
                        else if (status in 300..399) failure(LinkError.HTTP_ERROR, status)
                        else {
                            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                            val response = stream?.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > 65_536) throw LinkProtocolException()
                                    output.write(buffer, 0, count)
                                }
                                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                            }.orEmpty()
                            if (status == 200) {
                                if (!conn.contentType.orEmpty().substringBefore(';').trim().equals("application/json", true)) throw LinkProtocolException()
                                MemoryApiResult.Success(decode(response))
                            } else MemoryApiResult.Failed(MemoryLinkProtocol.decodeFailure(status, response,
                                conn.getHeaderField("Retry-After"), System.currentTimeMillis()))
                        }
                    } catch (_: ConnectException) { MemoryApiResult.Failed(LinkFailure(503, LinkError.NETWORK_UNAVAILABLE, true)) }
                    catch (_: MemoryReviewBlocked) { failure(LinkError.OUTCOME_UNKNOWN) }
                    catch (_: CharacterCodingException) { failure(LinkError.MALFORMED_RESPONSE, 502) }
                    catch (_: IOException) { failure(LinkError.OUTCOME_UNKNOWN) }
                    catch (_: Exception) { failure(LinkError.MALFORMED_RESPONSE, 502) }
                    finally { conn?.disconnect() }
                    if (continuation.isActive) continuation.resume(result)
                }
                submitted.set(future)
                if (!continuation.isActive) future.cancel(true)
            }
        } ?: failure(LinkError.OUTCOME_UNKNOWN)
    }
    private fun failure(code: LinkError, status: Int = 503) = MemoryApiResult.Failed(LinkFailure(status, code, false))
}
