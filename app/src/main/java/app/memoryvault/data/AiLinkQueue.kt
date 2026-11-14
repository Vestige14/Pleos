package app.memoryvault.data

import androidx.room.withTransaction
import app.memoryvault.sync.*
import app.memoryvault.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Room owns leases, admission counts and application of suggestions; scheduling is recoverable. */
class AiLinkQueue(private val db: AppDatabase, private val now: () -> Long = System::currentTimeMillis) : LinkWorkStore {
    companion object {
        private const val LEASE_MS = 120_000L
        private const val MAX_ATTEMPTS = 5
        private val active = setOf(AiLinkJobState.WAITING, AiLinkJobState.RETRY_WAIT, AiLinkJobState.RUNNING)
        private val retryCodes = setOf(LinkError.UPSTREAM_RATE_LIMIT, LinkError.UPSTREAM_UNAVAILABLE,
            LinkError.CACHE_CAPACITY, LinkError.CALL_RATE_LIMIT, LinkError.SERVER_BUSY, LinkError.NETWORK_UNAVAILABLE)
        private val reviewCodes = setOf(LinkError.OUTCOME_UNKNOWN, LinkError.MALFORMED_RESPONSE, LinkError.ATTEMPTS_EXHAUSTED)
        private val configurationCodes = setOf(LinkError.CONFIG_REQUIRED.name, LinkError.SESSION_STORAGE.name, LinkError.UNAUTHORIZED.name)
        private val seoul = ZoneId.of("Asia/Seoul")
        private fun addBounded(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b
    }

    override suspend fun getJob(jobId: String): AiLinkJob? = db.aiLinkDao().getJob(jobId)
    override suspend fun pendingJobs(): List<AiLinkJob> = db.aiLinkDao().getAllJobs().filter { it.promptVersion == "memory-links-v1" && it.state in active }

    override suspend fun claim(jobId: String): LinkLease? = db.withTransaction {
        val job = getJob(jobId) ?: return@withTransaction null
        if (job.promptVersion != "memory-links-v1") return@withTransaction null
        val time = now()
        if (job.state !in active || job.nextAttemptAt > time ||
            job.state == AiLinkJobState.RUNNING && (job.leaseUntil ?: Long.MAX_VALUE) > time) return@withTransaction null
        val request = currentRequest(job) ?: run {
            finish(job, AiLinkJobState.SUPERSEDED, null); return@withTransaction null
        }
        if (job.attempts >= MAX_ATTEMPTS) {
            finish(job, AiLinkJobState.REVIEW_REQUIRED, LinkError.ATTEMPTS_EXHAUSTED.name); return@withTransaction null
        }
        try { MemoryLinkProtocol.encodeRequest(request) }
        catch (_: LinkProtocolException) {
            finish(job, AiLinkJobState.REVIEW_REQUIRED, LinkError.INVALID_REQUEST.name); return@withTransaction null
        }
        val token = UUID.randomUUID().toString()
        db.aiLinkDao().updateJob(job.copy(state = AiLinkJobState.RUNNING, attemptToken = token,
            leaseUntil = addBounded(time, LEASE_MS), lastErrorCode = null))
        LinkLease(jobId, token, request, job.attempts)
    }

    override suspend fun beginAttempt(lease: LinkLease): Boolean = db.withTransaction {
        val job = ownedJob(lease.jobId, lease.attemptToken) ?: return@withTransaction false
        if (currentRequest(job) != lease.request) {
            finish(job, AiLinkJobState.SUPERSEDED, null); return@withTransaction false
        }
        if (job.attempts != lease.attemptsBefore || job.attempts >= MAX_ATTEMPTS) return@withTransaction false
        // Reservation is immediately before HTTP. A crash in this narrow gap counts conservatively.
        db.aiLinkDao().updateJob(job.copy(attempts = job.attempts + 1, lastErrorCode = LinkError.OUTCOME_UNKNOWN.name))
        true
    }

    override suspend fun applyResult(jobId: String, result: LinkResponse, attemptToken: String): ApplyOutcome = db.withTransaction {
        val job = ownedJob(jobId, attemptToken) ?: return@withTransaction ApplyOutcome.IGNORED_STALE
        val request = currentRequest(job) ?: run {
            finish(job, AiLinkJobState.SUPERSEDED, null); return@withTransaction ApplyOutcome.IGNORED_STALE
        }
        try { MemoryLinkProtocol.validateResponse(request, result) }
        catch (_: LinkProtocolException) {
            finish(job, AiLinkJobState.REVIEW_REQUIRED, LinkError.MALFORMED_RESPONSE.name)
            return@withTransaction ApplyOutcome.REVIEW_REQUIRED
        }
        if (result.decision != LinkDecision.SUGGEST) {
            finish(job, AiLinkJobState.REVIEW_REQUIRED, null)
            return@withTransaction ApplyOutcome.REVIEW_REQUIRED
        }
        db.noteLinkDao().deleteAiSuggestions(job.noteId)
        result.links.forEach { span ->
            db.noteLinkDao().insert(NoteVisitLink(job.noteId, span.visitId, span.startCp, span.endCp,
                LinkOrigin.AI, NoteLinkStatus.SUGGESTED, job.jobId))
        }
        // No Note text/visitId nor any USER/confirmed/rejected link is updated.
        finish(job, AiLinkJobState.DONE, null)
        ApplyOutcome.APPLIED
    }

    override suspend fun fail(jobId: String, failure: LinkFailure, attemptToken: String): AiLinkJobState? = db.withTransaction {
        val job = getJob(jobId)?.takeIf { it.promptVersion == "memory-links-v1" && it.acceptsLinkFailure(attemptToken, now(), failure.code) }
            ?: return@withTransaction null
        if (currentRequest(job) == null) {
            finish(job, AiLinkJobState.SUPERSEDED, null); return@withTransaction AiLinkJobState.SUPERSEDED
        }
        val retry = failure.retryable && failure.code in retryCodes && (failure.status == 429 || failure.status in 500..599)
        val state = when {
            job.attempts >= MAX_ATTEMPTS || failure.code in reviewCodes -> AiLinkJobState.REVIEW_REQUIRED
            retry -> AiLinkJobState.RETRY_WAIT
            else -> AiLinkJobState.BLOCKED
        }
        val error = if (retry && job.attempts >= MAX_ATTEMPTS) LinkError.ATTEMPTS_EXHAUSTED.name else failure.code.name
        val delay = maxOf(30_000L * (1L shl (job.attempts - 1).coerceIn(0, 4)), failure.retryAfterMillis.coerceAtLeast(0))
        db.aiLinkDao().updateJob(job.copy(state = state, leaseUntil = null, attemptToken = null, lastErrorCode = error,
            nextAttemptAt = if (state == AiLinkJobState.RETRY_WAIT) addBounded(now(), delay) else job.nextAttemptAt))
        state
    }

    override suspend fun cancel(jobId: String, attemptToken: String) = db.withTransaction {
        val job = ownedJob(jobId, attemptToken) ?: return@withTransaction
        if (currentRequest(job) == null) finish(job, AiLinkJobState.SUPERSEDED, null)
        else if (job.lastErrorCode == LinkError.OUTCOME_UNKNOWN.name)
            finish(job, AiLinkJobState.REVIEW_REQUIRED, LinkError.OUTCOME_UNKNOWN.name)
        else db.aiLinkDao().updateJob(job.copy(state = AiLinkJobState.WAITING, leaseUntil = null, attemptToken = null, nextAttemptAt = now()))
    }

    override suspend fun blockForConfiguration(jobId: String, code: LinkError) = db.withTransaction {
        require(code.name in configurationCodes)
        val job = getJob(jobId) ?: return@withTransaction
        if (job.promptVersion == "memory-links-v1" && job.state in setOf(AiLinkJobState.WAITING, AiLinkJobState.RETRY_WAIT))
            finish(job, AiLinkJobState.BLOCKED, code.name)
    }

    override suspend fun resumeConfigurationBlocked() = db.withTransaction {
        db.aiLinkDao().getAllJobs().filter { it.promptVersion == "memory-links-v1" && it.state == AiLinkJobState.BLOCKED && it.lastErrorCode in configurationCodes }.forEach {
            if (currentRequest(it) == null) finish(it, AiLinkJobState.SUPERSEDED, null)
            else if (it.attempts >= MAX_ATTEMPTS) finish(it, AiLinkJobState.REVIEW_REQUIRED, LinkError.ATTEMPTS_EXHAUSTED.name)
            else db.aiLinkDao().updateJob(it.copy(state = AiLinkJobState.WAITING, nextAttemptAt = now(), lastErrorCode = null))
        }
    }

    private suspend fun ownedJob(jobId: String, token: String): AiLinkJob? = getJob(jobId)?.takeIf {
        it.promptVersion == "memory-links-v1" && it.state == AiLinkJobState.RUNNING && it.attemptToken == token && (it.leaseUntil ?: Long.MIN_VALUE) > now()
    }
    private suspend fun finish(job: AiLinkJob, state: AiLinkJobState, error: String?) {
        db.aiLinkDao().updateJob(job.copy(state = state, attemptToken = null, leaseUntil = null, lastErrorCode = error))
    }
    private suspend fun currentRequest(job: AiLinkJob): LinkRequest? {
        val note = db.noteDao().get(job.noteId) ?: return null
        val policy = db.noteLinkDao().getPolicy(job.noteId) ?: return null
        if (note.kind != NoteKind.VOICE || note.visitId != null || note.revision != job.noteRevision ||
            policy.userLocked || policy.userRevision != job.userRevision || job.promptVersion != "memory-links-v1") return null
        if (db.noteLinkDao().getForNote(job.noteId).any { it.origin == LinkOrigin.USER || it.status != NoteLinkStatus.SUGGESTED }) return null
        val snapshot = db.aiLinkDao().getSnapshot(job.jobId) ?: return null
        val candidates = try { CandidateSnapshot.decode(snapshot.candidatesJson) } catch (_: Exception) { return null }
        if (CandidateSnapshot.contextHash(candidates) != job.contextHash) return null
        val knownIds = db.visitDao().getAll().map { it.id }.toSet()
        if (candidates.any { it.id !in knownIds }) return null
        fun timestamp(millis: Long) = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(seoul))
        if (Instant.ofEpochMilli(note.capturedAt).atZone(seoul).toLocalDate().toString() != note.sourceDate) return null
        return LinkRequest(job.jobId, job.noteRevision, job.userRevision, job.contextHash, timestamp(note.capturedAt), note.text,
            candidates.map { LinkCandidate(it.id, it.placeName, timestamp(it.arrivedAt), timestamp(it.leftAt)) })
    }

    suspend fun enqueueLinkJob(noteId: Long): String = db.withTransaction {
        val note = requireNotNull(db.noteDao().get(noteId)) { "Note does not exist" }
        val policy = requireNotNull(db.noteLinkDao().getPolicy(noteId)) { "Note link policy does not exist" }
        db.aiLinkDao().getForRevision(noteId, note.revision, policy.userRevision)?.let {
            return@withTransaction it.jobId
        }
        val candidates = db.visitDao().getDay(note.sourceDate)
            .filter { it.arrivedAt <= note.capturedAt }
            .map { VisitCandidate(it.id, it.placeName, it.arrivedAt, it.leftAt) }
        val jobId = UUID.randomUUID().toString()
        db.aiLinkDao().insertJob(AiLinkJob(
            jobId = jobId,
            noteId = noteId,
            noteRevision = note.revision,
            userRevision = policy.userRevision,
            contextHash = CandidateSnapshot.contextHash(candidates),
            promptVersion = "memory-links-v1",
            state = AiLinkJobState.WAITING,
            attempts = 0,
            nextAttemptAt = System.currentTimeMillis(),
        ))
        db.aiLinkDao().insertSnapshot(AiLinkRequestSnapshot(jobId, CandidateSnapshot.encode(candidates)))
        jobId
    }
}

/** An explicit UNKNOWN can settle its current attempt after expiry, never a different token. */
internal fun AiLinkJob.acceptsLinkFailure(token: String, time: Long, error: LinkError): Boolean =
    state == AiLinkJobState.RUNNING && attemptToken == token &&
        (error == LinkError.OUTCOME_UNKNOWN || (leaseUntil ?: Long.MIN_VALUE) > time)
