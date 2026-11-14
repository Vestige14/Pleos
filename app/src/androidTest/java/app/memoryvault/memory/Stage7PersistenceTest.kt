package app.memoryvault.memory

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.data.*
import app.memoryvault.domain.AiLinkJobState
import app.memoryvault.memorycontract.*
import app.memoryvault.sync.*
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Stage7PersistenceTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun time(day: String) = OffsetDateTime.parse("${day}T14:00:00+09:00").toInstant().toEpochMilli()
    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_1_2, MemoryAssociationMigration.FROM_2_TO_3).build()
    private suspend fun note(db: AppDatabase, text: String = "오늘 합성 바다 🌊", day: String = "2026-11-01"): Note {
        val repository = MemoryRepository(db, enqueueVoiceLink = { MemoryAssociationStore(db).register(it) })
        val id = repository.saveVoiceMemo(UUID.randomUUID().toString(), text, time(day))
        return requireNotNull(db.noteDao().get(id))
    }
    private suspend fun visit(db: AppDatabase): MemorySource {
        val v = Visit("synthetic-v", "2026-11-01", "합성 바다", 0.0, 0.0, time("2026-11-01") - 60000, time("2026-11-01"))
        db.visitDao().insertAll(listOf(v))
        return MemorySource(MemoryCandidate("visit:${MemoryWire.digest(v.id)}", MemoryKind.VISIT, v.date,
            MemoryTimeBasis.VISIT, "시연 방문 합성 바다", MemoryWire.timestamp(v.arrivedAt), null, v.placeName, 0.0, 0.0),
            v.id, MemorySourceFingerprint.visit(v))
    }
    private suspend fun queued(db: AppDatabase, note: Note, now: () -> Long): Pair<MemoryAssociationQueue, AssociationLease> {
        val q = MemoryAssociationQueue(db, now)
        val s = visit(db)
        val snap = AssociationSnapshot(ExperienceTimeResolver.resolve(note.text, note.capturedAt), listOf(s))
        val id = requireNotNull(q.enqueue(note, 0, snap, 1, false, emptySet()) { true })
        assertTrue(AiLinkQueue(db).pendingJobs().none { it.jobId == id })
        assertNull(AiLinkQueue(db).claim(id))
        return q to requireNotNull(q.claim(id))
    }
    private fun response(lease: AssociationLease) = AssociationResponse(lease.job.jobId, lease.request.noteRevision,
        lease.request.userRevision, lease.request.contextHash, LinkDecision.SUGGEST,
        listOf(AssociationSpan(lease.request.candidates.single().id, MemoryKind.VISIT, 3, 8)))
    private suspend fun fixture(block: suspend (AppDatabase) -> Unit) {
        val name = "stage7-test-${UUID.randomUUID()}.db"
        val db = open(name)
        try { block(db) } finally { db.close(); check(name.startsWith("stage7-test-")); context.deleteDatabase(name) }
    }
    @Test fun additiveMigrationFromBothReleasedSchemasPreservesImmutableRows() = runBlocking {
        for (from in listOf(1, 2)) {
            val name = "stage7-test-migration-${UUID.randomUUID()}.db"
            try {
                migration.createDatabase(name, from).apply {
                    execSQL("INSERT INTO visits VALUES ('v','2026-11-01','합성 방문',0,0,100,200)")
                    execSQL("INSERT INTO notes (id,visitId,text,kind,createdAt,captureVisitId,capturedAt,sourceDate,revision,voiceSessionId) VALUES (1,NULL,'  합성 원문 🌊  ','VOICE',150,NULL,150,'2026-11-01',1,'stage7-migration')")
                    execSQL("INSERT INTO note_link_policies VALUES (1,3,1)")
                    execSQL("INSERT INTO note_visit_links VALUES (1,'v',2,4,'USER','CONFIRMED',NULL)")
                    execSQL(if (from == 1) "INSERT INTO photos VALUES ('content://stage7-preserved',150,0,0,'v','CONFIRMED')"
                        else "INSERT INTO photos VALUES ('content://stage7-preserved',150,0,0,'v','CONFIRMED',1)")
                    execSQL("INSERT INTO ai_link_jobs VALUES ('legacy',1,1,0,'hash','memory-links-v1','WAITING',0,0,NULL,NULL,NULL)")
                    execSQL("INSERT INTO ai_link_request_snapshots VALUES ('legacy','[]')")
                    close()
                }
                migration.runMigrationsAndValidate(name, 3, true, AppDatabase.MIGRATION_1_2, MemoryAssociationMigration.FROM_2_TO_3).close()
                val db = open(name)
                try {
                    assertEquals("  합성 원문 🌊  ", db.noteDao().get(1)?.text)
                    assertEquals(NoteLinkPolicy(1, 3, true), db.noteLinkDao().getPolicy(1))
                    assertEquals(LinkState.CONFIRMED, db.photoDao().get("content://stage7-preserved")?.linkState)
                    assertEquals("legacy", db.aiLinkDao().getAllJobs().single().jobId)
                    assertEquals(1, db.noteLinkDao().getForNote(1).size)
                    assertTrue(db.memoryAssociationDao().states().isEmpty())
                } finally { db.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
    @Test fun productionRegistrationIsAtomicIdempotentAndDoesNotCreateV1Jobs() = runBlocking { fixture { db ->
        val s = MemoryAssociationStore(db)
        val repository = MemoryRepository(db, enqueueVoiceLink = { s.register(it) })
        val id = repository.saveVoiceMemo("synthetic-session", "  합성 원문\n", time("2026-11-01"))
        assertEquals(id, repository.saveVoiceMemo("synthetic-session", "중복 final", time("2026-11-01")))
        s.register(id)
        assertEquals(1, db.noteDao().getAll().size); assertEquals(1, db.memoryAssociationDao().states().size)
        assertEquals("  합성 원문\n", db.noteDao().get(id)?.text); assertTrue(db.aiLinkDao().getAllJobs().isEmpty())
    } }
    @Test fun candidateDatesUseOwnTextAndNoArrivalCoordinatesAreInvented() = runBlocking { fixture { db ->
        val own = note(db)
        note(db, "가".repeat(1500) + " 10/9 바다", "2026-11-01")
        db.photoDao().upsert(listOf(Photo("content://synthetic-old", time("2026-10-09"), null, null, null, LinkState.UNLINKED)))
        val provider = object : NavigationEvidenceProvider {
            override fun status() = NavigationEvidenceStatus.AVAILABLE
            override fun snapshot() = listOf(NavigationEvidence("arrival", NavigationEvidenceKind.ARRIVAL_OBSERVED,
                "합성 해변", null, null, null, own.capturedAt, null),
                NavigationEvidence("recent", NavigationEvidenceKind.UNDATED_RECENT, "합성 해변", null, null, null, own.capturedAt, null))
        }
        val batch = MemoryCandidates(db, provider).batch(own, requireNotNull(db.memoryAssociationDao().state(own.id)))
        assertEquals(1, batch.sources.size)
        val c = batch.sources.single().candidate
        assertEquals(MemoryTimeBasis.NAV_ARRIVED, c.timeBasis); assertEquals("2026-11-01", c.evidenceDate)
        assertNull(c.occurredAt); assertNull(c.latitude); assertTrue(c.description.contains("관측"))
    } }
    @Test fun navigationNotReadyIsWaitingRatherThanFinalEmpty() = runBlocking { fixture { db ->
        val n = note(db)
        val q = MemoryAssociationQueue(db)
        assertNull(q.enqueue(n, 0, AssociationSnapshot(ExperienceTimeResolver.resolve(n.text, n.capturedAt), emptyList()),
            0, false, setOf("NAVIGATION_NOT_READY")) { true })
        assertEquals("WAITING", db.memoryAssociationDao().state(n.id)?.status)
        assertTrue(db.aiLinkDao().getAllJobs().isEmpty())
    } }
    @Test fun userCorrectedDateStaysStoredIsFreeAndAllowsManualLink() = runBlocking { fixture { db ->
        val n = note(db); val s = MemoryAssociationStore(db)
        assertEquals(MemoryReviewResult.SAVED, s.reanalyze(n.id, n.revision, 0, false, "2026-10-09") { true })
        val state = requireNotNull(db.memoryAssociationDao().state(n.id))
        val candidates = MemoryCandidates(db, EmptyNavigationEvidenceProvider)
        val batch = candidates.batch(n, state)
        assertTrue(batch.sources.isEmpty()); assertTrue("USER_DATE_CONFLICT" in batch.issues)
        MemoryAssociationQueue(db).enqueue(n, 1, AssociationSnapshot(batch.scope, batch.sources), 0, false, batch.issues) { true }
        assertEquals("REVIEW_REQUIRED", db.memoryAssociationDao().state(n.id)?.status)
        assertEquals(MemoryReviewResult.INVALID, s.reanalyze(n.id, n.revision, 1, false, null) { true })
        val old = Visit("old", "2026-10-09", "합成 옛 해변", 0.0, 0.0, time("2026-10-09") - 60000, time("2026-10-09"))
        db.visitDao().insertAll(listOf(old))
        val choices = candidates.batch(n, requireNotNull(db.memoryAssociationDao().state(n.id)), true, true).sources
        assertEquals(1, choices.size)
        assertEquals(MemoryReviewResult.SAVED, s.correct(n.id, n.revision, 1, choices, setOf(choices.single().candidate.id), 0,
            n.text.codePointCount(0, n.text.length)) { true })
        assertEquals("2026-10-09", db.memoryAssociationDao().state(n.id)?.eventDateOverride)
        assertEquals(n, db.noteDao().get(n.id)); assertTrue(db.aiLinkDao().getAllJobs().isEmpty())
    } }
    @Test fun userDecisionFencesLateResponseAndCannotAutoUnlock() = runBlocking { fixture { db ->
        val n = note(db); val (q, lease) = queued(db, n) { 1000L }
        assertNull(q.claim(lease.job.jobId)); assertTrue(q.begin(lease))
        val s = MemoryAssociationStore(db); val source = visit(db)
        assertEquals(MemoryReviewResult.SAVED, s.correct(n.id, n.revision, 0, listOf(source), setOf(source.candidate.id), 0,
            n.text.codePointCount(0, n.text.length)) { true })
        assertEquals(ApplyOutcome.IGNORED_STALE, q.apply(lease.job.jobId, lease.token, response(lease)))
        assertEquals(MemoryReviewResult.LOCKED, s.reanalyze(n.id, n.revision, 1, false, null) { true })
        assertEquals("USER", db.memoryAssociationDao().links(n.id).single().origin)
        assertEquals(n, db.noteDao().get(n.id))
    } }
    @Test fun reservedExpiredAttemptIsUnknownAndNeverPaidAgain() = runBlocking { fixture { db ->
        var clock = 1000L
        val n = note(db); val (q, lease) = queued(db, n) { clock }
        assertTrue(q.begin(lease)); clock += 120001
        assertNull(q.claim(lease.job.jobId))
        val job = requireNotNull(db.aiLinkDao().getJob(lease.job.jobId))
        assertEquals(AiLinkJobState.REVIEW_REQUIRED, job.state); assertEquals("OUTCOME_UNKNOWN", job.lastErrorCode)
        assertEquals(1, job.attempts); assertNull(q.claim(job.jobId))
    } }
    @Test fun confirmRejectUnlinkAndRestartKeepOriginalAndDecisions() = runBlocking {
        val name = "stage7-test-restart-${UUID.randomUUID()}.db"
        var db = open(name)
        try {
            val n = note(db); val (q, lease) = queued(db, n) { 1000L }
            assertTrue(q.begin(lease)); assertEquals(ApplyOutcome.APPLIED, q.apply(lease.job.jobId, lease.token, response(lease)))
            val s = MemoryAssociationStore(db); var item = s.items().single()
            assertEquals(MemoryReviewResult.SAVED, s.decide(n.id, n.revision, 0, MemoryReviewAction.CONFIRM, setOf(item.links.single().key)) { true })
            db.close(); db = open(name)
            item = MemoryAssociationStore(db).items().single()
            assertEquals("CONFIRMED", item.state.status); assertTrue(item.userLocked); assertEquals(n, item.note)
            assertEquals(MemoryReviewResult.SAVED, MemoryAssociationStore(db).decide(n.id, n.revision, 1, MemoryReviewAction.UNLINK, emptySet()) { true })
            assertEquals("REJECTED", db.memoryAssociationDao().links(n.id).single().status)
            assertEquals(MemoryReviewResult.SAVED, MemoryAssociationStore(db).reanalyze(n.id, n.revision, 2, false, null) { true })
            assertTrue(MemoryCandidates(db, EmptyNavigationEvidenceProvider).batch(n, requireNotNull(db.memoryAssociationDao().state(n.id))).sources.isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun photoCacheSurvivesReopenAndUnknownReceiptDoesNotRetry() = runBlocking {
        val name = "stage7-test-cache-${UUID.randomUUID()}.db"
        var db = open(name); var calls = 0
        val image = SanitizedMemoryPhoto("synthetic-image-bytes".toByteArray(), MemoryWire.sha256("synthetic-image-bytes".toByteArray()))
        val api = object : MemoryAssociationApi {
            override suspend fun link(request: AssociationRequest, session: LinkSession): MemoryApiResult<AssociationResponse> = error("unused")
            override suspend fun describe(request: PhotoDescriptionRequest, session: LinkSession, allowed: () -> Boolean): MemoryApiResult<PhotoDescriptionResponse> {
                calls++
                return MemoryApiResult.Success(PhotoDescriptionResponse(request.requestId, request.imageSha256, "visual-v1", "합성 바다", listOf("파랑")))
            }
        }
        val session = LinkSession("http://127.0.0.1:18761", "synthetic_stage7_test_token_0000000")
        try {
            assertEquals("합성 바다", MemoryPhotoDescriptions(db, api).description(image, session) { true })
            db.close(); db = open(name)
            assertEquals("합성 바다", MemoryPhotoDescriptions(db, api).description(image, session) { true }); assertEquals(1, calls)
            val key = "${image.sha256}|unknown-model|visual-v1"
            db.memoryAssociationDao().savePhoto(MemoryPhotoDescription(key, image.sha256, "unknown-model", "visual-v1", "req", "RUNNING", 1, 0, 1, "old", null, null, "OUTCOME_UNKNOWN"))
            for (i in 1..2) {
                try { MemoryPhotoDescriptions(db, api, model = "unknown-model").description(image, session) { true }; fail("uncertain retry") }
                catch (e: PhotoPreparationException) { assertTrue(e.code.contains("OUTCOME_UNKNOWN")) }
            }
            assertEquals(1, calls)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
