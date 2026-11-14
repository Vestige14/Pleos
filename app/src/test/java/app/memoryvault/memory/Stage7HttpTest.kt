package app.memoryvault.memory

import app.memoryvault.sync.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class Stage7HttpTest {
    private val session = LinkSession("http://127.0.0.1:18761", "synthetic_stage7_test_token_0000000")
    private val request = AssociationRequest("req", 1, 0, "hash", "2026-11-01T14:00:00+09:00", "오늘 바다",
        ExperienceTimeScope(ExperienceTimeMode.CAPTURE_DAY, "2026-11-01"), listOf(MemoryCandidate("c", MemoryKind.PHOTO,
            "2026-11-01", MemoryTimeBasis.PHOTO_CAPTURE, "바다", "2026-11-01T12:00:00+09:00", null, null, null, null)))
    private val response = """{"requestId":"req","noteRevision":1,"userRevision":0,"contextHash":"hash","decision":"SUGGEST","links":[{"targetId":"c","kind":"PHOTO","startCp":3,"endCp":5}]}"""
    private class Connection(val code: Int, var body: ByteArray) : HttpURLConnection(URL("http://localhost")) {
        val sent = ByteArrayOutputStream(); var disconnected = false; var failure: Exception? = null
        override fun connect() { }
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int { failure?.let { throw it }; return code }
        override fun getContentType() = "application/json"
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getErrorStream() = inputStream
    }
    @Test fun sharedSessionSendsExactVersionedBodyWithoutLocalReferences() = runBlocking {
        val c = Connection(200, response.toByteArray())
        val api = HttpMemoryAssociationApi(true) { url -> assertEquals("/v2/memory-links", url.path); c }
        assertTrue(api.link(request, session) is MemoryApiResult.Success)
        assertEquals(MemoryWire.json.encodeToString(request), c.sent.toString("UTF-8"))
        assertEquals("Bearer synthetic_stage7_test_token_0000000", c.getRequestProperty("Authorization"))
        assertFalse(c.instanceFollowRedirects); assertTrue(c.disconnected)
    }
    @Test fun duplicateEchoKindsFractionalIntegersAndMalformedUtf8FailClosed() = runBlocking {
        for (body in listOf(response.replace("\"noteRevision\":1", "\"noteRevision\":1.0"),
            response.replace("\"noteRevision\":1", "\"noteRevision\":1,\"noteRevision\":1"),
            response.replace("\"PHOTO\"", "\"NOTE\""), response.replace("\"hash\"", "\"stale\""))) {
            val result = HttpMemoryAssociationApi(true) { Connection(200, body.toByteArray()) }.link(request, session) as MemoryApiResult.Failed
            assertEquals(LinkError.MALFORMED_RESPONSE, result.failure.code)
        }
        val result = HttpMemoryAssociationApi(true) { Connection(200, byteArrayOf(0xC3.toByte(), 0x28)) }.link(request, session) as MemoryApiResult.Failed
        assertEquals(LinkError.MALFORMED_RESPONSE, result.failure.code)
    }
    @Test fun unknownAndRedirectNeverRetryAndGuardMakesZeroPhotoConnections() = runBlocking {
        var calls = 0
        val c = Connection(503, """{"error":{"code":"OUTCOME_UNKNOWN","retryable":false}}""".toByteArray())
        var api = HttpMemoryAssociationApi(true) { calls++; c }
        val out = api.link(request, session) as MemoryApiResult.Failed
        assertEquals(LinkError.OUTCOME_UNKNOWN, out.failure.code); assertFalse(out.failure.retryable); assertEquals(1, calls)
        api = HttpMemoryAssociationApi(true) { calls++; Connection(307, byteArrayOf()) }
        assertFalse((api.link(request, session) as MemoryApiResult.Failed).failure.retryable)
        val bytes = "synthetic".toByteArray()
        val photo = PhotoDescriptionRequest("p", MemoryWire.sha256(bytes), "image/jpeg", java.util.Base64.getEncoder().encodeToString(bytes))
        val before = calls
        assertTrue(api.describe(photo, session) { false } is MemoryApiResult.Failed)
        assertEquals(before, calls)
    }
    @Test fun readTimeoutAfterSendIsUnknownWithoutAutomaticReplay() = runBlocking {
        var calls = 0
        val c = Connection(200, response.toByteArray()).apply { failure = SocketTimeoutException("synthetic") }
        val out = HttpMemoryAssociationApi(true) { calls++; c }.link(request, session) as MemoryApiResult.Failed
        assertEquals(LinkError.OUTCOME_UNKNOWN, out.failure.code); assertFalse(out.failure.retryable); assertEquals(1, calls)
    }
}
