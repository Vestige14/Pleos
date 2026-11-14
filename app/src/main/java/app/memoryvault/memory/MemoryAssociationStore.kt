package app.memoryvault.memory

import androidx.room.withTransaction
import app.memoryvault.data.*
import app.memoryvault.domain.*
import java.time.LocalDate
import kotlinx.serialization.encodeToString

data class MemoryReviewLink(val key: String, val targetId: String, val kind: MemoryKind, val startCp: Int, val endCp: Int,
    val status: String, val origin: String, val source: MemorySource)
data class MemoryReviewItem(val note: Note, val userRevision: Int, val userLocked: Boolean,
    val state: MemoryReviewState, val links: List<MemoryReviewLink>)
data class MemoryAssociationProgress(val items: List<MemoryReviewItem> = emptyList(), val preparing: Boolean = false,
    val errorCode: String? = null)
enum class MemoryReviewAction { CONFIRM, REJECT, UNLINK }
enum class MemoryReviewResult { SAVED, BLOCKED, STALE, LOCKED, INVALID, FAILED }

/** The shared user policy fences both v1 and v2 in the same Room transaction as the decision. */
class MemoryAssociationStore(private val db: AppDatabase) {
    suspend fun register(noteId: Long) = db.withTransaction {
        if (db.memoryAssociationDao().state(noteId) != null) return@withTransaction
        val note = db.noteDao().get(noteId) ?: return@withTransaction
        if (note.kind != NoteKind.VOICE) return@withTransaction
        val policy = db.noteLinkDao().getPolicy(noteId) ?: NoteLinkPolicy(noteId).also { db.noteLinkDao().insertPolicy(it) }
        val legacy = db.aiLinkDao().getAllJobs().any { it.noteId == noteId && it.promptVersion == "memory-links-v1" }
        db.memoryAssociationDao().saveState(MemoryReviewState(noteId, status = when {
            policy.userLocked -> if (db.noteLinkDao().getForNote(noteId).any { it.status == NoteLinkStatus.CONFIRMED }) "CONFIRMED" else "REJECTED"
            legacy -> "LEGACY"
            else -> "WAITING"
        }))
    }
    suspend fun items(): List<MemoryReviewItem> = db.withTransaction {
        val states = db.memoryAssociationDao().states().associateBy { it.noteId }
        db.noteDao().getAll().filter { it.kind == NoteKind.VOICE }.mapNotNull { note ->
            val state = states[note.id] ?: return@mapNotNull null
            val policy = db.noteLinkDao().getPolicy(note.id) ?: return@mapNotNull null
            val own = db.memoryAssociationDao().links(note.id).mapNotNull { link ->
                runCatching {
                    val source = MemoryWire.json.decodeFromString<MemorySource>(link.sourceJson)
                    MemoryReviewLink("${link.targetId}:${link.startCp}:${link.endCp}:${link.origin}", link.targetId, MemoryKind.valueOf(link.kind),
                        link.startCp, link.endCp, link.status, link.origin, source)
                }.getOrNull()
            }
            val legacy = db.noteLinkDao().getForNote(note.id).mapNotNull { link ->
                val visit = db.visitDao().getAll().find { it.id == link.visitId } ?: return@mapNotNull null
                val candidate = MemoryCandidate("visit:${MemoryWire.digest(visit.id)}", MemoryKind.VISIT, visit.date, MemoryTimeBasis.VISIT,
                    "기존 v1 방문 연결: ${visit.placeName}", MemoryWire.timestamp(visit.arrivedAt), null, visit.placeName, null, null)
                MemoryReviewLink("v1:${link.visitId}:${link.startCp}:${link.endCp}:${link.origin}", candidate.id, MemoryKind.VISIT,
                    link.startCp, link.endCp, link.status.name, link.origin.name, MemorySource(candidate, visit.id, MemorySourceFingerprint.visit(visit)))
            }
            MemoryReviewItem(note, policy.userRevision, policy.userLocked, state, own + legacy)
        }.sortedByDescending { it.note.capturedAt }
    }

    suspend fun decide(noteId: Long, noteRevision: Int, userRevision: Int, action: MemoryReviewAction,
        selectedKeys: Set<String>, allowed: () -> Boolean): MemoryReviewResult = db.withTransaction {
        val item = checked(noteId, noteRevision, userRevision, allowed) ?: return@withTransaction staleOrBlocked(allowed)
        val all = db.memoryAssociationDao().links(noteId)
        val current = item.links.filter { it.status == "SUGGESTED" }
        if (action == MemoryReviewAction.CONFIRM && (selectedKeys.isEmpty() || selectedKeys.any { key -> current.none { it.key == key } }))
            return@withTransaction MemoryReviewResult.INVALID
        if (action == MemoryReviewAction.CONFIRM) {
            // Explicitly accepting selected suggestions rejects unselected suggestions in this batch.
            all.filter { it.status == "SUGGESTED" }.forEach { link ->
                val key = "${link.targetId}:${link.startCp}:${link.endCp}:${link.origin}"
                db.memoryAssociationDao().saveLink(link.copy(status = if (key in selectedKeys) "CONFIRMED" else "REJECTED"))
            }
            db.noteLinkDao().getForNote(noteId).filter { it.status == NoteLinkStatus.SUGGESTED }.forEach { link ->
                val key = "v1:${link.visitId}:${link.startCp}:${link.endCp}:${link.origin}"
                db.noteLinkDao().saveLinkForReview(link.copy(status = if (key in selectedKeys) NoteLinkStatus.CONFIRMED else NoteLinkStatus.REJECTED))
            }
        } else {
            all.filter { it.status == "SUGGESTED" || action == MemoryReviewAction.UNLINK && it.status == "CONFIRMED" }.forEach {
                db.memoryAssociationDao().saveLink(it.copy(status = "REJECTED"))
            }
            if (action == MemoryReviewAction.UNLINK) db.noteLinkDao().rejectActiveForReview(noteId)
            else db.noteLinkDao().decideSuggestions(noteId, "REJECTED")
        }
        val confirmed = db.memoryAssociationDao().links(noteId).any { it.status == "CONFIRMED" } ||
            db.noteLinkDao().getForNote(noteId).any { it.status == NoteLinkStatus.CONFIRMED }
        fence(item, true)
        db.memoryAssociationDao().saveState(item.state.copy(status = if (confirmed) "CONFIRMED" else
            if (action == MemoryReviewAction.UNLINK) "UNLINKED" else "REJECTED", activeJobId = null, errorCode = null))
        if (!allowed()) throw MemoryReviewBlocked()
        MemoryReviewResult.SAVED
    }

    suspend fun correct(noteId: Long, noteRevision: Int, userRevision: Int, sources: List<MemorySource>,
        selectedIds: Set<String>, startCp: Int, endCp: Int, allowed: () -> Boolean): MemoryReviewResult = db.withTransaction {
        val item = checked(noteId, noteRevision, userRevision, allowed) ?: return@withTransaction staleOrBlocked(allowed)
        val chosen = sources.filter { it.candidate.id in selectedIds }
        if (selectedIds.isEmpty() || chosen.size != selectedIds.size || chosen.size > 10) return@withTransaction MemoryReviewResult.INVALID
        for (source in chosen) if (!MemorySourceFingerprint.current(db, source)) return@withTransaction MemoryReviewResult.STALE
        try { if (MemoryWire.evidence(item.note.text, startCp, endCp).isBlank()) return@withTransaction MemoryReviewResult.INVALID }
        catch (_: Exception) { return@withTransaction MemoryReviewResult.INVALID }
        db.memoryAssociationDao().links(noteId).filter { it.status != "REJECTED" }.forEach {
            db.memoryAssociationDao().saveLink(it.copy(status = "REJECTED"))
        }
        db.noteLinkDao().rejectActiveForReview(noteId)
        chosen.forEach { source ->
            db.memoryAssociationDao().saveLink(MemoryAssociationLink(noteId, source.candidate.id, source.candidate.kind.name,
                startCp, endCp, "USER", "CONFIRMED", MemoryWire.json.encodeToString(source), null))
        }
        fence(item, true)
        db.memoryAssociationDao().saveState(item.state.copy(status = "CONFIRMED", activeJobId = null, errorCode = null))
        if (!allowed()) throw MemoryReviewBlocked()
        MemoryReviewResult.SAVED
    }

    /** Reanalysis never unlocks a confirmed record. User must explicitly unlink/correct it first. */
    suspend fun reanalyze(noteId: Long, noteRevision: Int, userRevision: Int, nextPage: Boolean,
        dateOverride: String?, allowed: () -> Boolean): MemoryReviewResult = db.withTransaction {
        val item = checked(noteId, noteRevision, userRevision, allowed) ?: return@withTransaction staleOrBlocked(allowed)
        if (item.links.any { it.status == "CONFIRMED" }) return@withTransaction MemoryReviewResult.LOCKED
        if (nextPage && !item.state.hasMore) return@withTransaction MemoryReviewResult.INVALID
        val date = try { dateOverride?.let { LocalDate.parse(it).also { date ->
            require(date.year in 1..9999 && date.toString() == it && date <= LocalDate.parse(ExperienceTimeResolver.day(item.note.capturedAt)))
        }.toString() } } catch (_: Exception) { return@withTransaction MemoryReviewResult.INVALID }
        if ("USER_DATE_CONFLICT" in item.state.issues.split(',') && (date ?: item.state.eventDateOverride) == item.state.eventDateOverride)
            return@withTransaction MemoryReviewResult.INVALID
        // Preserve every rejected USER row; a new AI batch excludes those targets.
        db.memoryAssociationDao().deleteSuggestions(noteId)
        db.noteLinkDao().decideSuggestions(noteId, "REJECTED")
        fence(item, false)
        db.memoryAssociationDao().saveState(item.state.copy(status = "WAITING", activeJobId = null,
            eventDateOverride = date ?: item.state.eventDateOverride, pageOffset = if (nextPage) item.state.nextOffset else 0,
            nextOffset = 0, hasMore = false, issues = "", errorCode = null))
        if (!allowed()) throw MemoryReviewBlocked()
        MemoryReviewResult.SAVED
    }
    suspend fun preparationError(noteId: Long, userRevision: Int, code: String) = db.withTransaction {
        val state = db.memoryAssociationDao().state(noteId) ?: return@withTransaction
        val policy = db.noteLinkDao().getPolicy(noteId) ?: return@withTransaction
        if (policy.userRevision == userRevision && !policy.userLocked && state.status == "WAITING" && state.activeJobId == null)
            db.memoryAssociationDao().saveState(state.copy(status = "REVIEW_REQUIRED", errorCode = code))
    }
    private suspend fun checked(noteId: Long, noteRevision: Int, userRevision: Int, allowed: () -> Boolean): MemoryReviewItem? {
        if (!allowed()) return null
        return items().find { it.note.id == noteId && it.note.revision == noteRevision && it.userRevision == userRevision }
    }
    private suspend fun fence(item: MemoryReviewItem, locked: Boolean) {
        check(item.userRevision < Int.MAX_VALUE)
        db.noteLinkDao().savePolicy(NoteLinkPolicy(item.note.id, item.userRevision + 1, locked))
        db.aiLinkDao().getAllJobs().filter { it.noteId == item.note.id && it.state in setOf(AiLinkJobState.WAITING,
            AiLinkJobState.RUNNING, AiLinkJobState.RETRY_WAIT, AiLinkJobState.BLOCKED) }.forEach {
            db.aiLinkDao().updateJob(it.copy(state = AiLinkJobState.SUPERSEDED, leaseUntil = null, attemptToken = null))
        }
    }
    private fun staleOrBlocked(allowed: () -> Boolean) = if (allowed()) MemoryReviewResult.STALE else MemoryReviewResult.BLOCKED
}
