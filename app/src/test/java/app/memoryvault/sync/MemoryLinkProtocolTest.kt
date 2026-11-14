package app.memoryvault.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class MemoryLinkProtocolTest {
    private val request = LinkRequest("job-1", 1, 0, "hash-1", "2026-11-08T14:58:00+09:00", "😊 바다가 좋았어", listOf(
        LinkCandidate("v2", "방아머리해변", "2026-11-08T13:30:00+09:00", "2026-11-08T14:50:00+09:00"),
    ))
    private val good = """{"requestId":"job-1","noteRevision":1,"userRevision":0,"contextHash":"hash-1","decision":"SUGGEST","links":[{"visitId":"v2","startCp":2,"endCp":4}]}"""

    @Test fun emojiRangesAreCodePointsAndNeverSplitSurrogates() {
        val result = MemoryLinkProtocol.decodeResponse(request, good)
        assertEquals(LinkDecision.SUGGEST, result.decision)
        assertEquals("바다", MemoryLinkProtocol.evidence(request.text, result.links.single()))
        assertEquals("😊", MemoryLinkProtocol.evidence(request.text, LinkSpan("v2", 0, 1)))
        assertEquals("어", MemoryLinkProtocol.evidence(request.text, LinkSpan("v2", 8, 9)))
    }

    @Test fun mismatchedEchoIsNeverAccepted() {
        listOf(good.replace("job-1", "job-2"), good.replace("\"noteRevision\":1", "\"noteRevision\":2"),
            good.replace("\"userRevision\":0", "\"userRevision\":1"), good.replace("hash-1", "hash-2")).forEach(::reject)
    }

    @Test fun outOfCandidateAndInvalidRangesAreRejected() {
        listOf(good.replace("v2", "other"), good.replace("\"startCp\":2", "\"startCp\":-1"),
            good.replace("\"endCp\":4", "\"endCp\":99"), good.replace("\"endCp\":4", "\"endCp\":2"),
            good.replace("\"startCp\":2", "\"startCp\":2.5")).forEach(::reject)
    }

    @Test fun quotedNoteRevisionIsRejected() = reject(good.replace("\"noteRevision\":1", "\"noteRevision\":\"1\""))
    @Test fun quotedUserRevisionIsRejected() = reject(good.replace("\"userRevision\":0", "\"userRevision\":\"0\""))
    @Test fun quotedStartCpIsRejected() = reject(good.replace("\"startCp\":2", "\"startCp\":\"2\""))
    @Test fun quotedEndCpIsRejected() = reject(good.replace("\"endCp\":4", "\"endCp\":\"4\""))

    @Test fun integerFieldsRejectNonIntegerJsonTokens() {
        for ((field, value) in listOf("noteRevision" to 1, "userRevision" to 0, "startCp" to 2, "endCp" to 4)) {
            for (token in listOf("null", "true", "[]", "{}", "$value.0", "${value}e0", "2147483648")) {
                reject(good.replace("\"$field\":$value", "\"$field\":$token"))
            }
        }
    }

    @Test fun responseStringFieldsRequireStringTokensEvenForNumericIdentifiers() {
        val numeric = request.copy(requestId = "123", contextHash = "456",
            candidates = request.candidates.map { it.copy(id = "789") })
        val body = good.replace("job-1", "123").replace("hash-1", "456").replace("v2", "789")
        assertEquals(LinkDecision.SUGGEST, MemoryLinkProtocol.decodeResponse(numeric, body).decision)
        for ((field, value) in listOf("requestId" to "123", "contextHash" to "456", "visitId" to "789")) {
            assertThrows(LinkProtocolException::class.java) {
                MemoryLinkProtocol.decodeResponse(numeric, body.replace("\"$field\":\"$value\"", "\"$field\":$value"))
            }
        }
    }

    @Test fun deeplyNestedResponseArraysAreRejectedWithoutParserErrors() {
        val arrays = "[".repeat(6_000) + "0" + "]".repeat(6_000)
        reject(good.substringBefore("\"links\":") + "\"links\":$arrays}")
    }

    @Test fun deeplyNestedFailureArraysAreMalformedAndNeverRetry() {
        val arrays = "[".repeat(6_000) + "true" + "]".repeat(6_000)
        val failure = MemoryLinkProtocol.decodeFailure(503,
            """{"error":{"code":"UPSTREAM_UNAVAILABLE","retryable":$arrays}}""", "90", 0)
        assertEquals(LinkError.MALFORMED_RESPONSE, failure.code)
        assertFalse(failure.retryable)
        assertEquals(0L, failure.retryAfterMillis)
    }

    @Test fun reviewAndNoMatchHaveNoLinksAndDoNotPretendToSuggest() {
        listOf("REVIEW", "NO_MATCH").forEach { decision ->
            val body = good.substringBefore("\"decision\"") + "\"decision\":\"$decision\",\"links\":[]}"
            assertTrue(MemoryLinkProtocol.decodeResponse(request, body).links.isEmpty())
            reject(good.replace("SUGGEST", decision))
        }
        reject(good.replace("[{\"visitId\":\"v2\",\"startCp\":2,\"endCp\":4}]", "[]"))
    }

    @Test fun malformedUnknownDuplicateAndRepeatedLinkFieldsAreRejected() {
        listOf("", "null", "[]", good.dropLast(1), good.replace("\"SUGGEST\"", "\"CONFIRM\""),
            good.replace("\"decision\":", "\"extra\":1,\"decision\":"),
            good.replace("\"requestId\":", "\"requestId\":\"other\",\"requestId\":"),
            good.replace("\"startCp\":2", "\"startCp\":null"),
            good.replace("\"endCp\":4", "\"endCp\":4,\"quote\":\"private\""),
            good.replace("}]", "},{\"visitId\":\"v2\",\"startCp\":5,\"endCp\":7}]"),
            good.replace("}]", "},{\"visitId\":\"v2\",\"startCp\":2,\"endCp\":4}]"),
            " ".repeat(65_537)).forEach(::reject)
    }

    @Test fun payloadHasOnlyOriginalContextAndNoDeviceDetails() {
        val body = MemoryLinkProtocol.encodeRequest(request)
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("requestId", "noteRevision", "userRevision", "contextHash", "capturedAt", "text", "candidates"), root.keys)
        assertEquals(request, Json.decodeFromString<LinkRequest>(body))
        assertFalse(request.toString().contains(request.text))
        assertFalse(request.toString().contains("방아머리"))
    }

    @Test fun oversizedOrWrongDateOrFutureSnapshotNeverSilentlyTruncates() {
        listOf(request.copy(text = "x".repeat(4001)), request.copy(text = " "),
            request.copy(text = "broken\uD800"), request.copy(candidates = List(21) { request.candidates.single().copy(id = "v$it") }),
            request.copy(candidates = request.candidates + request.candidates),
            request.copy(candidates = listOf(request.candidates.single().copy(arrivedAt = "2026-11-08T15:10:00+09:00"))),
            request.copy(candidates = listOf(request.candidates.single().copy(arrivedAt = "2026-11-07T13:30:00+09:00"))),
            request.copy(candidates = listOf(request.candidates.single().copy(leftAt = "2026-11-08T13:00:00+09:00"))))
            .forEach { assertThrows(LinkProtocolException::class.java) { MemoryLinkProtocol.encodeRequest(it) } }
    }

    @Test fun failuresUseBodyContractAndOnlyAllowExplicitTransientRetries() {
        listOf(429 to "UPSTREAM_RATE_LIMIT", 503 to "UPSTREAM_UNAVAILABLE", 429 to "SERVER_BUSY").forEach { (status, code) ->
            val failure = MemoryLinkProtocol.decodeFailure(status, """{"error":{"code":"$code","retryable":true}}""", "90", 1_000)
            assertEquals(code, failure.code.name)
            assertTrue(failure.retryable)
            assertEquals(90_000L, failure.retryAfterMillis)
        }
        listOf(503 to "OUTCOME_UNKNOWN", 503 to "ATTEMPTS_EXHAUSTED", 502 to "UPSTREAM_QUOTA", 502 to "UPSTREAM_AUTH", 502 to "UPSTREAM_CONFIG").forEach { (status, code) ->
            val f = MemoryLinkProtocol.decodeFailure(status, """{"error":{"code":"$code","retryable":false}}""", null, 1_000)
            assertFalse(f.retryable)
        }
        assertFalse(MemoryLinkProtocol.decodeFailure(503, """{"error":{"code":"OUTCOME_UNKNOWN","retryable":true}}""", null, 1_000).retryable)
        assertFalse(MemoryLinkProtocol.decodeFailure(200, """{"error":{"code":"UPSTREAM_UNAVAILABLE","retryable":true}}""", null, 1_000).retryable)
        assertEquals(LinkError.UNAUTHORIZED, MemoryLinkProtocol.decodeFailure(401, "private body", null, 1_000).code)
        assertEquals(LinkError.MALFORMED_RESPONSE, MemoryLinkProtocol.decodeFailure(503, "private body", null, 1_000).code)
        assertEquals(60_000L, MemoryLinkProtocol.decodeFailure(429, """{"error":{"code":"CALL_RATE_LIMIT","retryable":true}}""", "Thu, 01 Jan 1970 00:01:01 GMT", 1_000).retryAfterMillis)
    }

    @Test fun localAdmissionAndUsageErrorsAreExplicitNonretryable() {
        for ((status, code) in listOf(429 to "ADMISSION_REJECTED", 503 to "USAGE_RECORDING_FAILED")) {
            val failure = MemoryLinkProtocol.decodeFailure(status, """{"error":{"code":"$code","retryable":true}}""", null, 0)
            assertEquals(code, failure.code.name)
            assertFalse(failure.retryable)
        }
    }
    @Test fun failureRetryabilityRequiresBooleanTokenAndNeverCoercesQuotedValues() {
        for (token in listOf("\"true\"", "\"false\"", "1", "0", "null", "[]", "{}")) {
            val body = """{"error":{"code":"UPSTREAM_UNAVAILABLE","retryable":$token}}"""
            val failure = MemoryLinkProtocol.decodeFailure(503, body, "90", 0)
            assertEquals(token, LinkError.MALFORMED_RESPONSE, failure.code)
            assertFalse(failure.retryable)
            assertEquals(0L, failure.retryAfterMillis)
            assertEquals(LinkError.UNAUTHORIZED, MemoryLinkProtocol.decodeFailure(401, body, "90", 0).code)
        }
    }
    @Test fun loopbackEndpointRejectsLANCredentialsPathsRedirectOriginsAndReleaseHTTP() {
        listOf("http://localhost:8000", "http://127.0.0.1:9000/").forEach {
            assertTrue(MemoryLinkEndpoint.validate(it, true).toString().endsWith("/v1/memory-links"))
            assertThrows(IllegalArgumentException::class.java) { MemoryLinkEndpoint.validate(it, false) }
        }
        listOf("http://10.0.2.2:8000", "http://192.168.0.2:8000", "https://example.com", "http://localhost.example.com",
            "http://user:pass@localhost:8000", "http://localhost:8000/x", "http://localhost:8000/?token=x", "http://localhost:8000/#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { MemoryLinkEndpoint.validate(it, true) }
        }
    }

    private fun reject(body: String) {
        val error = assertThrows(LinkProtocolException::class.java) { MemoryLinkProtocol.decodeResponse(request, body) }
        assertEquals("INVALID_LINK_CONTRACT", error.message)
        assertFalse(error.toString().contains("private"))
        assertNull(error.cause)
    }
}
