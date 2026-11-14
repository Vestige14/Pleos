package app.memoryvault.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.data.AiLinkQueue
import app.memoryvault.data.AppDatabase
import app.memoryvault.data.MemoryRepository
import app.memoryvault.data.NoteKind
import app.memoryvault.data.NoteLinkPolicy
import app.memoryvault.data.NoteVisitLink
import app.memoryvault.data.Visit
import app.memoryvault.domain.AiLinkJobState
import app.memoryvault.domain.LinkOrigin
import app.memoryvault.domain.NoteLinkStatus
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Coordinator-only acceptance: real production HTTP to the local authenticated fake-model server. */
@RunWith(AndroidJUnit4::class)
class MemoryLinkTransportAcceptanceTest {
    @Test fun logicalOfflineSave_thenRealHttp503_reopenAndRetry_preservesOriginalAndSuggestsOnce() = runBlocking {
        withTimeout(70_000L) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val caseId = UUID.randomUUID().toString()
            val testName = "stage56-transport-test-$caseId.db"
            check(testName != "memory-vault.db" && testName.startsWith("stage56-transport-test-"))
            // Inspect only the existing encrypted file; never replace a real configured session.
            val encryptedSession = File(context.noBackupFilesDir, "memory-link/session.enc")
            assertTrue("Coordinator must configure the existing encrypted local session", encryptedSession.isFile)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue("Existing session key must be present; acceptance must not generate one",
                keyStore.containsAlias("memory-link-session-aes-v1"))
            val cipherDigest = MessageDigest.getInstance("SHA-256").digest(encryptedSession.readBytes())
            val storedSessions = MemoryLinkSessionStore(context)
            val checkedSessions = object : LinkSessionSource {
                override fun load(): LinkSession? {
                    val session = requireNotNull(storedSessions.load()) { "LOCAL_SESSION_REQUIRED" }
                    val endpoint = MemoryLinkEndpoint.validate(session.endpoint, allowLocalHttp = true)
                    check(endpoint.scheme == "http" && endpoint.host in setOf("127.0.0.1", "localhost") &&
                        endpoint.port == 18761 && endpoint.path == "/v1/memory-links") { "LOCAL_18761_REQUIRED" }
                    return session
                }
            }
            checkedSessions.load() // Fail before HTTP if the saved endpoint is not the exact local fixture.
            val captured = OffsetDateTime.parse("2026-11-08T14:58:00+09:00").toInstant().toEpochMilli()
            val visitId = "stage56-transport-$caseId"
            val quote = "합성 해변 기록"
            val text = "STAGE56_TRANSPORT_CASE=$caseId; 😀 $quote"
            val sessionId = "stage56-transport-synthetic-$caseId"
            var db = Room.databaseBuilder(context, AppDatabase::class.java, testName).build()
            try {
                val repository = MemoryRepository(db)
                db.visitDao().insertAll(listOf(Visit(visitId, "2026-11-08", "STAGE56 합성 해변",
                    0.0, 0.0, captured - 60_000L, captured - 1_000L)))
                val noteId = repository.saveVoiceMemo(sessionId, text, captured)
                val original = requireNotNull(db.noteDao().get(noteId))
                val originalPolicy = requireNotNull(db.noteLinkDao().getPolicy(noteId))
                val initialJob = db.aiLinkDao().getAllJobs().single()
                val snapshot = db.aiLinkDao().getAllSnapshots().single()
                assertEquals(text, original.text)
                assertEquals(NoteKind.VOICE, original.kind)
                assertNull(original.visitId)
                assertEquals(sessionId, original.voiceSessionId)
                assertEquals(captured, original.capturedAt)
                assertEquals(NoteLinkPolicy(noteId), originalPolicy)
                assertEquals(AiLinkJobState.WAITING, initialJob.state)
                assertEquals(0, initialJob.attempts)

                // Count a delegating boundary; every non-offline result comes from real HttpURLConnection.
                val transport = HttpMemoryLinkApi(allowLocalHttp = true)
                val requests = mutableListOf<LinkRequest>()
                val outcomes = mutableListOf<ApiOutcome>()
                val actualApi = MemoryLinkApi { request, session ->
                    val endpoint = MemoryLinkEndpoint.validate(session.endpoint, allowLocalHttp = true)
                    check(endpoint.scheme == "http" && endpoint.host in setOf("127.0.0.1", "localhost") &&
                        endpoint.port == 18761) { "LOCAL_18761_REQUIRED" }
                    requests += request
                    transport.link(request, session).also { outcomes += it }
                }
                var connected = false
                // Only queue time is injected; no OS network toggle or elapsed-backoff timing claim.
                var queueTime = maxOf(System.currentTimeMillis(), initialJob.nextAttemptAt)
                var queue = AiLinkQueue(db) { queueTime }
                var executor = MemoryLinkExecutor(queue, actualApi, checkedSessions) { connected }
                assertEquals(WorkExecution.RETRY, executor.run(initialJob.jobId))
                assertTrue(requests.isEmpty())
                assertTrue(outcomes.isEmpty())
                assertEquals(initialJob, queue.getJob(initialJob.jobId))
                assertEquals(original, db.noteDao().get(noteId))
                assertTrue(db.noteLinkDao().getForNote(noteId).isEmpty())

                connected = true
                assertEquals(WorkExecution.RETRY, executor.run(initialJob.jobId))
                assertEquals(1, requests.size)
                assertEquals(1, outcomes.size)
                val failed = outcomes.single() as? ApiOutcome.Failed
                    ?: throw AssertionError("Local fixture must first return an actual retryable503")
                assertEquals(503, failed.failure.status)
                assertEquals(LinkError.UPSTREAM_UNAVAILABLE, failed.failure.code)
                assertTrue(failed.failure.retryable)
                val retry = requireNotNull(queue.getJob(initialJob.jobId))
                assertEquals(AiLinkJobState.RETRY_WAIT, retry.state)
                assertEquals(1, retry.attempts)
                assertEquals(LinkError.UPSTREAM_UNAVAILABLE.name, retry.lastErrorCode)
                assertTrue(retry.nextAttemptAt >= queueTime + 30_000L)
                assertNull(retry.attemptToken)
                assertNull(retry.leaseUntil)
                assertEquals(original, db.noteDao().get(noteId))
                assertEquals(originalPolicy, db.noteLinkDao().getPolicy(noteId))
                assertTrue(db.noteLinkDao().getForNote(noteId).isEmpty())

                db.close()
                assertTrue(context.getDatabasePath(testName).isFile)
                db = Room.databaseBuilder(context, AppDatabase::class.java, testName).build()
                assertEquals(original, db.noteDao().get(noteId))
                assertEquals(retry, db.aiLinkDao().getJob(initialJob.jobId))
                assertEquals(snapshot, db.aiLinkDao().getAllSnapshots().single())
                assertEquals(originalPolicy, db.noteLinkDao().getPolicy(noteId))
                queue = AiLinkQueue(db) { queueTime }
                executor = MemoryLinkExecutor(queue, actualApi, checkedSessions) { connected }
                // Before DB backoff expires, a connected executor must still send zero extra requests.
                assertEquals(WorkExecution.RETRY, executor.run(initialJob.jobId))
                assertEquals(1, requests.size)
                queueTime = retry.nextAttemptAt
                assertEquals(WorkExecution.FINISHED, executor.run(initialJob.jobId))
                assertEquals(2, requests.size)
                assertEquals(requests[0], requests[1])
                val success = outcomes.last() as? ApiOutcome.Success
                    ?: throw AssertionError("Actual authenticated local HTTP must return a valid suggestion")
                assertEquals(LinkDecision.SUGGEST, success.result.decision)
                val start = text.codePointCount(0, text.indexOf(quote))
                val end = start + quote.codePointCount(0, quote.length)
                assertEquals(listOf(LinkSpan(visitId, start, end)), success.result.links)
                assertEquals(quote, MemoryLinkProtocol.evidence(text, success.result.links.single()))
                assertEquals(original, db.noteDao().get(noteId))
                assertEquals(originalPolicy, db.noteLinkDao().getPolicy(noteId))
                assertEquals(snapshot, db.aiLinkDao().getAllSnapshots().single())
                val expectedLink = NoteVisitLink(noteId, visitId, start, end, LinkOrigin.AI,
                    NoteLinkStatus.SUGGESTED, initialJob.jobId)
                assertEquals(listOf(expectedLink), db.noteLinkDao().getForNote(noteId))
                val done = requireNotNull(queue.getJob(initialJob.jobId))
                assertEquals(AiLinkJobState.DONE, done.state)
                assertEquals(2, done.attempts)
                assertNull(done.attemptToken)
                assertNull(done.leaseUntil)
                assertNull(done.lastErrorCode)
                assertEquals(WorkExecution.FINISHED, executor.run(initialJob.jobId))
                assertEquals(2, requests.size)
                assertEquals(1, db.noteDao().getAll().size)
                assertEquals(1, db.aiLinkDao().getAllJobs().size)

                db.close()
                db = Room.databaseBuilder(context, AppDatabase::class.java, testName).build()
                assertEquals(original, db.noteDao().get(noteId))
                assertEquals(done, db.aiLinkDao().getJob(initialJob.jobId))
                assertEquals(listOf(expectedLink), db.noteLinkDao().getForNote(noteId))
                assertTrue("Acceptance must leave the configured encrypted session byte-identical",
                    MessageDigest.isEqual(cipherDigest,
                        MessageDigest.getInstance("SHA-256").digest(encryptedSession.readBytes())))
            } finally {
                db.close()
                check(testName.startsWith("stage56-transport-test-") && testName != "memory-vault.db")
                assertTrue("Only this UUID test database must be removed", context.deleteDatabase(testName))
            }
        }
    }
}
