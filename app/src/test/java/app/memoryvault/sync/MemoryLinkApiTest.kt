package app.memoryvault.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class MemoryLinkApiTest {
    private val request = LinkRequest("job-1", 1, 0, "hash-1", "2026-11-08T14:58:00+09:00", "바다가 좋았어", listOf(
        LinkCandidate("v2", "해변", "2026-11-08T13:30:00+09:00", "2026-11-08T14:50:00+09:00")))
    private val body = """{"requestId":"job-1","noteRevision":1,"userRevision":0,"contextHash":"hash-1","decision":"SUGGEST","links":[{"visitId":"v2","startCp":0,"endCp":2}]}"""
    private val session = LinkSession("http://127.0.0.1:8000", "synthetic_session_" + "x".repeat(32))

    @Test fun sendsOnlyValidatedJSONAndSessionToOneNonredirectingLoopbackConnection() = runBlocking {
        val conn = FakeConnection(200, body)
        val result = HttpMemoryLinkApi(true) { url -> assertEquals("http://127.0.0.1:8000/v1/memory-links", url.toString()); conn }.link(request, session)
        assertTrue(result is ApiOutcome.Success)
        assertEquals("POST", conn.requestMethod)
        assertFalse(conn.instanceFollowRedirects)
        assertFalse(conn.useCaches)
        assertEquals("application/json; charset=utf-8", conn.getRequestProperty("Content-Type"))
        assertEquals("Bearer synthetic_session_" + "x".repeat(32), conn.getRequestProperty("Authorization"))
        assertEquals(request, kotlinx.serialization.json.Json.decodeFromString<LinkRequest>(conn.sent.toString("UTF-8")))
        assertTrue(conn.disconnected)
        assertFalse(session.toString().contains("synthetic_session"))
    }
    @Test fun statusAloneDoesNotRetryUnknownOutcomeOrFollowRedirects() = runBlocking {
        val unknown = FakeConnection(503, """{"error":{"code":"OUTCOME_UNKNOWN","retryable":false}}""")
        val out = HttpMemoryLinkApi(true) { unknown }.link(request, session) as ApiOutcome.Failed
        assertEquals(LinkError.OUTCOME_UNKNOWN, out.failure.code); assertFalse(out.failure.retryable)
        val redirect = FakeConnection(307, "private redirect body")
        val fail = HttpMemoryLinkApi(true) { redirect }.link(request, session) as ApiOutcome.Failed
        assertFalse(fail.failure.retryable); assertTrue(redirect.disconnected)
    }
    @Test fun connectionRefusedCanRetryButReadTimeoutIsAnUnknownPaidOutcome() = runBlocking {
        val refused = FakeConnection(200, body).apply { thrown = ConnectException("synthetic private detail") }
        val timeout = FakeConnection(200, body).apply { thrown = SocketTimeoutException("synthetic private detail") }
        assertTrue((HttpMemoryLinkApi(true) { refused }.link(request, session) as ApiOutcome.Failed).failure.retryable)
        val failure = (HttpMemoryLinkApi(true) { timeout }.link(request, session) as ApiOutcome.Failed).failure
        assertEquals(LinkError.OUTCOME_UNKNOWN, failure.code); assertFalse(failure.retryable)
    }
    @Test fun malformedAndOversizedAndWrongContentTypeAreReviewFailures() = runBlocking {
        for (conn in listOf(FakeConnection(200, "{private"), FakeConnection(200, "x".repeat(65_537)),
            FakeConnection(200, body).apply { media = "text/html" }, FakeConnection(200, body.replace("hash-1", "wrong")))) {
            val failure = (HttpMemoryLinkApi(true) { conn }.link(request, session) as ApiOutcome.Failed).failure
            assertEquals(LinkError.MALFORMED_RESPONSE, failure.code); assertFalse(failure.retryable)
            assertTrue(conn.disconnected)
        }
    }
    @Test fun cancellationDisconnectsTheInflightHTTPWithoutReturningAResult() = runBlocking {
        val conn = FakeConnection(200, body).apply { block = true }
        var returned = false
        val job = launch { HttpMemoryLinkApi(true) { conn }.link(request, session); returned = true }
        withContext(Dispatchers.IO) { assertTrue(conn.entered.await(5, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertTrue(conn.disconnected); assertFalse(returned)
    }
    @Test fun receivedMalformedUTF8IsAContractReviewInsteadOfTransportUnknown() = runBlocking {
        val conn = FakeConnection(200, body).apply { bytes = byteArrayOf(0xC3.toByte(), 0x28) }
        val failure = (HttpMemoryLinkApi(true) { conn }.link(request, session) as ApiOutcome.Failed).failure
        assertEquals(LinkError.MALFORMED_RESPONSE, failure.code)
        assertFalse(failure.retryable)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun totalDeadlineCancelsTheBlockingConnectionAndStopsAutomaticRetry() = runTest {
        val conn = FakeConnection(200, body).apply { block = true }
        val result = async { HttpMemoryLinkApi(true) { conn }.link(request, session) }
        runCurrent()
        assertTrue(conn.entered.await(5, TimeUnit.SECONDS))
        advanceTimeBy(30_001); runCurrent()
        val failure = (result.await() as ApiOutcome.Failed).failure
        assertEquals(LinkError.OUTCOME_UNKNOWN, failure.code)
        assertFalse(failure.retryable); assertTrue(conn.disconnected)
    }
    @Test fun invalidRequestOrLANEndpointMakesNoConnection() = runBlocking {
        var connections = 0
        val api = HttpMemoryLinkApi(true) { connections++; FakeConnection(200, body) }
        assertTrue(api.link(request.copy(text = " "), session) is ApiOutcome.Failed)
        assertTrue(api.link(request, LinkSession("http://192.168.1.2:8000", "x".repeat(48))) is ApiOutcome.Failed)
        assertEquals(0, connections)
    }

    private class FakeConnection(private val status: Int, private val body: String) : HttpURLConnection(URL("http://localhost")) {
        val sent = ByteArrayOutputStream()
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        @Volatile var disconnected = false
        var thrown: Exception? = null
        var block = false
        var media = "application/json"
        var bytes = body.toByteArray(Charsets.UTF_8)
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; release.countDown() }
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int {
            entered.countDown()
            if (block) release.await(5, TimeUnit.SECONDS)
            thrown?.let { throw it }
            return status
        }
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getErrorStream() = inputStream
        override fun getContentType() = media
        override fun getHeaderField(name: String): String? = if (name.equals("Retry-After", true)) "60" else null
    }
}
