package app.memoryvault.memory

import androidx.room.withTransaction
import app.memoryvault.data.*
import app.memoryvault.domain.AiLinkJobState
import app.memoryvault.sync.*
import java.util.UUID
import kotlinx.serialization.encodeToString

data class AssociationLease(val job: AiLinkJob, val request: AssociationRequest, val token: String)

/** v2 uses the existing durable job/snapshot tables and the same lease/backoff/attempt policy. */
class MemoryAssociationQueue(private val db: AppDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val active = setOf(AiLinkJobState.WAITING, AiLinkJobState.RUNNING, AiLinkJobState.RETRY_WAIT)
    suspend fun pending() = db.aiLinkDao().getAllJobs().filter { it.promptVersion == MemoryWire.LINK_VERSION && it.state in active }

    suspend fun enqueue(note: Note, userRevision: Int, snapshot: AssociationSnapshot, nextOffset: Int,
        hasMore: Boolean, issues: Set<String>, allowed: () -> Boolean): String? = db.withTransaction {
        val current = db.noteDao().get(note.id) ?: return@withTransaction null
        val policy = db.noteLinkDao().getPolicy(note.id) ?: return@withTransaction null
        val state = db.memoryAssociationDao().state(note.id) ?: return@withTransaction null
        if (!allowed() || current.revision != note.revision || policy.userRevision != userRevision || policy.userLocked || state.status != "WAITING")
            return@withTransaction null
        val id = UUID.randomUUID().toString()
        val hash = MemoryWire.contextHash(snapshot)
        val request = AssociationRequest(id, current.revision, userRevision, hash, MemoryWire.timestamp(current.capturedAt),
            current.text, snapshot.timeScope, snapshot.sources.map { it.candidate })
        MemoryWire.encode(request)
        val localState = when {
            snapshot.timeScope.mode == ExperienceTimeMode.UNRESOLVED -> "REVIEW_REQUIRED"
            snapshot.sources.isEmpty() && "NAVIGATION_NOT_READY" in issues -> "WAITING"
            snapshot.sources.isEmpty() -> "NO_MATCH"
            else -> null
        }
        if (localState != null) {
            db.memoryAssociationDao().saveState(state.copy(status = localState, activeJobId = null, nextOffset = nextOffset,
                hasMore = hasMore, issues = issues.sorted().joinToString(","), errorCode = null))
            return@withTransaction null
        }
        db.aiLinkDao().insertJob(AiLinkJob(id, note.id, note.revision, userRevision, hash, MemoryWire.LINK_VERSION,
            AiLinkJobState.WAITING, 0, now()))
        db.aiLinkDao().insertSnapshot(AiLinkRequestSnapshot(id, MemoryWire.json.encodeToString(snapshot)))
        db.memoryAssociationDao().saveState(state.copy(status = "WAITING", activeJobId = id, nextOffset = nextOffset,
            hasMore = hasMore, issues = issues.sorted().joinToString(","), errorCode = null))
        id
    }

    suspend fun claim(id: String): AssociationLease? = db.withTransaction {
        val job = db.aiLinkDao().getJob(id) ?: return@withTransaction null
        if (job.promptVersion != MemoryWire.LINK_VERSION || job.state !in active || job.nextAttemptAt > now()) return@withTransaction null
        if (job.state == AiLinkJobState.RUNNING) {
            if ((job.leaseUntil ?: Long.MAX_VALUE) > now()) return@withTransaction null
            // A reserved HTTP that lost its process/result is not evidence of a safe retry.
            if (job.lastErrorCode == LinkError.OUTCOME_UNKNOWN.name) {
                finish(job, AiLinkJobState.REVIEW_REQUIRED, LinkError.OUTCOME_UNKNOWN.name)
                return@withTransaction null
            }
        }
        val request = currentRequest(job) ?: run { finish(job, AiLinkJobState.SUPERSEDED, "STALE_CONTEXT"); return@withTransaction null }
        if (job.attempts >= 5) { finish(job, AiLinkJobState.REVIEW_REQUIRED, "ATTEMPTS_EXHAUSTED"); return@withTransaction null }
        try { MemoryWire.encode(request) } catch (_: Exception) {
            finish(job, AiLinkJobState.REVIEW_REQUIRED, "INVALID_REQUEST"); return@withTransaction null
        }
        val token = UUID.randomUUID().toString()
        db.aiLinkDao().updateJob(job.copy(state = AiLinkJobState.RUNNING, attemptToken = token,
            leaseUntil = now() + 120_000, lastErrorCode = null))
        stateStatus(job, "RUNNING", null)
        AssociationLease(job, request, token)
    }
    suspend fun begin(lease: AssociationLease): Boolean = db.withTransaction {
        val job = owned(lease.job.jobId, lease.token) ?: return@withTransaction false
        if (currentRequest(job) != lease.request || job.attempts != lease.job.attempts || job.attempts >= 5) return@withTransaction false
        db.aiLinkDao().updateJob(job.copy(attempts = job.attempts + 1, lastErrorCode = LinkError.OUTCOME_UNKNOWN.name))
        true
    }
    suspend fun apply(id: String, token: String, result: AssociationResponse): ApplyOutcome = db.withTransaction {
        val job = owned(id, token) ?: return@withTransaction ApplyOutcome.IGNORED_STALE
        val request = currentRequest(job) ?: run {
            finish(job, AiLinkJobState.SUPERSEDED, "STALE_CONTEXT"); return@withTransaction ApplyOutcome.IGNORED_STALE
        }
        try { MemoryWire.validate(request, result) } catch (_: Exception) {
            finish(job, AiLinkJobState.REVIEW_REQUIRED, "MALFORMED_RESPONSE"); return@withTransaction ApplyOutcome.REVIEW_REQUIRED
        }
        if (result.decision != LinkDecision.SUGGEST) {
            finish(job, AiLinkJobState.REVIEW_REQUIRED, null,
                if (result.decision == LinkDecision.NO_MATCH) "NO_MATCH" else "REVIEW_REQUIRED")
            return@withTransaction ApplyOutcome.REVIEW_REQUIRED
        }
        val snapshot = snapshot(job) ?: return@withTransaction ApplyOutcome.IGNORED_STALE
        db.memoryAssociationDao().deleteSuggestions(job.noteId)
        result.links.forEach { span ->
            val source = snapshot.sources.single { it.candidate.id == span.targetId && it.candidate.kind == span.kind }
            db.memoryAssociationDao().saveLink(MemoryAssociationLink(job.noteId, span.targetId, span.kind.name, span.startCp,
                span.endCp, "AI", "SUGGESTED", MemoryWire.json.encodeToString(source), id))
        }
        finish(job, AiLinkJobState.DONE, null, "SUGGESTED")
        ApplyOutcome.APPLIED
    }
    suspend fun fail(id: String, token: String, failure: LinkFailure) = db.withTransaction {
        val job = db.aiLinkDao().getJob(id)?.takeIf {
            it.promptVersion == MemoryWire.LINK_VERSION && it.state == AiLinkJobState.RUNNING && it.attemptToken == token &&
                (failure.code == LinkError.OUTCOME_UNKNOWN || (it.leaseUntil ?: 0) > now())
        } ?: return@withTransaction
        if (currentRequest(job) == null) { finish(job, AiLinkJobState.SUPERSEDED, "STALE_CONTEXT"); return@withTransaction }
        val retry = failure.retryable && failure.code in setOf(LinkError.NETWORK_UNAVAILABLE, LinkError.UPSTREAM_RATE_LIMIT,
            LinkError.UPSTREAM_UNAVAILABLE, LinkError.CACHE_CAPACITY, LinkError.SERVER_BUSY, LinkError.CALL_RATE_LIMIT) &&
            (failure.status == 429 || failure.status in 500..599)
        if (job.attempts >= 5 || failure.code in setOf(LinkError.OUTCOME_UNKNOWN, LinkError.MALFORMED_RESPONSE)) {
            finish(job, AiLinkJobState.REVIEW_REQUIRED, if (retry && job.attempts >= 5) "ATTEMPTS_EXHAUSTED" else failure.code.name)
        } else if (retry) {
            val delay = maxOf(30_000L * (1L shl (job.attempts - 1).coerceIn(0, 4)), failure.retryAfterMillis.coerceAtLeast(0))
            db.aiLinkDao().updateJob(job.copy(state = AiLinkJobState.RETRY_WAIT, attemptToken = null, leaseUntil = null,
                lastErrorCode = failure.code.name, nextAttemptAt = if (delay > Long.MAX_VALUE - now()) Long.MAX_VALUE else now() + delay))
            stateStatus(job, "RETRY_WAIT", failure.code.name)
        } else finish(job, AiLinkJobState.BLOCKED, failure.code.name)
    }
    suspend fun cancel(lease: AssociationLease) = db.withTransaction {
        val job = db.aiLinkDao().getJob(lease.job.jobId)?.takeIf {
            it.state == AiLinkJobState.RUNNING && it.attemptToken == lease.token
        } ?: return@withTransaction
        if (job.lastErrorCode == LinkError.OUTCOME_UNKNOWN.name) finish(job, AiLinkJobState.REVIEW_REQUIRED, "OUTCOME_UNKNOWN")
        else {
            db.aiLinkDao().updateJob(job.copy(state = AiLinkJobState.WAITING, attemptToken = null, leaseUntil = null))
            stateStatus(job, "WAITING", null)
        }
    }
    suspend fun configuration(id: String, error: LinkError) = db.withTransaction {
        val job = db.aiLinkDao().getJob(id) ?: return@withTransaction
        if (job.promptVersion == MemoryWire.LINK_VERSION && job.state in setOf(AiLinkJobState.WAITING, AiLinkJobState.RETRY_WAIT))
            finish(job, AiLinkJobState.BLOCKED, error.name)
    }
    suspend fun sessionUpdated() = db.withTransaction {
        db.aiLinkDao().getAllJobs().filter { it.promptVersion == MemoryWire.LINK_VERSION && it.state == AiLinkJobState.BLOCKED &&
            it.lastErrorCode in setOf("CONFIG_REQUIRED", "SESSION_STORAGE", "UNAUTHORIZED") }.forEach { job ->
            if (currentRequest(job) != null && job.attempts < 5) {
                db.aiLinkDao().updateJob(job.copy(state = AiLinkJobState.WAITING, nextAttemptAt = now(), lastErrorCode = null))
                stateStatus(job, "WAITING", null)
            }
        }
    }
    suspend fun nextExecution(id: String) = if (db.aiLinkDao().getJob(id)?.state in active) WorkExecution.RETRY else WorkExecution.FINISHED
    private suspend fun owned(id: String, token: String) = db.aiLinkDao().getJob(id)?.takeIf {
        it.promptVersion == MemoryWire.LINK_VERSION && it.state == AiLinkJobState.RUNNING && it.attemptToken == token && (it.leaseUntil ?: 0) > now()
    }
    private suspend fun snapshot(job: AiLinkJob): AssociationSnapshot? = try {
        db.aiLinkDao().getSnapshot(job.jobId)?.let { MemoryWire.json.decodeFromString<AssociationSnapshot>(it.candidatesJson) }
    } catch (_: Exception) { null }
    private suspend fun currentRequest(job: AiLinkJob): AssociationRequest? {
        val note = db.noteDao().get(job.noteId) ?: return null
        val policy = db.noteLinkDao().getPolicy(job.noteId) ?: return null
        val state = db.memoryAssociationDao().state(job.noteId) ?: return null
        if (job.promptVersion != MemoryWire.LINK_VERSION || note.kind != NoteKind.VOICE || note.revision != job.noteRevision ||
            policy.userRevision != job.userRevision || policy.userLocked || state.activeJobId != job.jobId) return null
        val snapshot = snapshot(job) ?: return null
        if (MemoryWire.contextHash(snapshot) != job.contextHash) return null
        if (db.memoryAssociationDao().links(note.id).any { it.status == "CONFIRMED" }) return null
        for (source in snapshot.sources) {
            val valid = when (source.candidate.kind) {
                MemoryKind.PHOTO -> db.photoDao().get(source.localRef)?.let {
                    it.accessible && it.linkState != LinkState.REJECTED && MemorySourceFingerprint.photo(it) == source.fingerprint
                } == true
                MemoryKind.NOTE -> source.localRef.toLongOrNull()?.let { db.noteDao().get(it) }?.let {
                    it.kind == NoteKind.VOICE && MemorySourceFingerprint.note(it) == source.fingerprint
                } == true
                MemoryKind.VISIT -> db.visitDao().getAll().any { it.id == source.localRef && MemorySourceFingerprint.visit(it) == source.fingerprint }
                MemoryKind.NAVIGATION -> true // The dated SDK observation itself is the immutable evidence snapshot.
            }
            if (!valid) return null
        }
        return AssociationRequest(job.jobId, job.noteRevision, job.userRevision, job.contextHash, MemoryWire.timestamp(note.capturedAt),
            note.text, snapshot.timeScope, snapshot.sources.map { it.candidate })
    }
    private suspend fun finish(job: AiLinkJob, state: AiLinkJobState, error: String?, display: String = state.name) {
        db.aiLinkDao().updateJob(job.copy(state = state, attemptToken = null, leaseUntil = null, lastErrorCode = error))
        stateStatus(job, display, error)
    }
    private suspend fun stateStatus(job: AiLinkJob, status: String, error: String?) {
        val current = db.memoryAssociationDao().state(job.noteId) ?: return
        if (current.activeJobId == job.jobId) db.memoryAssociationDao().saveState(current.copy(status = status, errorCode = error))
    }
}
