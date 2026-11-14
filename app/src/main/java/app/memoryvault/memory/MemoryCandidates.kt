package app.memoryvault.memory

import app.memoryvault.data.*
import app.memoryvault.memorycontract.*
import java.time.LocalDate

object EmptyNavigationEvidenceProvider : NavigationEvidenceProvider {
    override fun snapshot() = emptyList<NavigationEvidence>()
    override fun status() = NavigationEvidenceStatus.UNAVAILABLE
}
data class MemoryCandidateBatch(
    val scope: ExperienceTimeScope, val sources: List<MemorySource>, val nextOffset: Int,
    val hasMore: Boolean, val issues: Set<String>,
)

object MemorySourceFingerprint {
    fun photo(value: Photo) = MemoryWire.digest("${value.uri}|${value.takenAt}")
    fun note(value: Note) = MemoryWire.digest("${value.id}|${value.revision}|${value.capturedAt}|${value.text}")
    fun visit(value: Visit) = MemoryWire.digest("${value.id}|${value.placeName}|${value.date}|${value.arrivedAt}|${value.leftAt}|${value.lat}|${value.lng}")
    suspend fun current(db: AppDatabase, source: MemorySource): Boolean = when (source.candidate.kind) {
        MemoryKind.PHOTO -> db.photoDao().get(source.localRef)?.let { it.accessible && photo(it) == source.fingerprint } == true
        MemoryKind.NOTE -> source.localRef.toLongOrNull()?.let { db.noteDao().get(it) }?.let {
            it.kind == NoteKind.VOICE && note(it) == source.fingerprint
        } == true
        MemoryKind.VISIT -> db.visitDao().getAll().any { it.id == source.localRef && visit(it) == source.fingerprint }
        MemoryKind.NAVIGATION -> true
    }
}

/** Classification is from the stored source/SDK enum; semantic models cannot invent source types. */
class MemoryCandidates(private val db: AppDatabase, private val navigation: NavigationEvidenceProvider) {
    suspend fun batch(note: Note, state: MemoryReviewState, includeRejected: Boolean = false,
        forManualSelection: Boolean = false): MemoryCandidateBatch {
        val originalScope = ExperienceTimeResolver.resolve(note.text, note.capturedAt)
        val scope = state.eventDateOverride?.let {
            ExperienceTimeScope(ExperienceTimeMode.EXPLICIT_DATE, LocalDate.parse(it).toString())
        } ?: originalScope
        if (!forManualSelection && state.eventDateOverride != null &&
            (originalScope.mode == ExperienceTimeMode.UNRESOLVED || originalScope.eventDate != state.eventDateOverride)) {
            // v1.1 has no user-date provenance: never pass a conflicting date as server authority.
            // Manual candidate browsing may use the preserved date, but paid photo/link analysis stops here.
            return MemoryCandidateBatch(ExperienceTimeScope(ExperienceTimeMode.UNRESOLVED, null), emptyList(),
                0, false, setOf("USER_DATE_CONFLICT"))
        }
        if (scope.mode == ExperienceTimeMode.UNRESOLVED) return MemoryCandidateBatch(scope, emptyList(), 0, false, setOf("TIME_UNRESOLVED"))
        val sources = mutableListOf<MemorySource>()
        val issues = mutableSetOf<String>()
        val rejected = if (includeRejected) emptySet() else (
            db.memoryAssociationDao().links(note.id).filter { it.status == "REJECTED" }.map { it.targetId } +
                db.noteLinkDao().getForNote(note.id).filter { it.status == app.memoryvault.domain.NoteLinkStatus.REJECTED }
                    .map { "visit:${MemoryWire.digest(it.visitId)}" }
            ).toSet()
        db.photoDao().getAll().filter { it.accessible && it.linkState != LinkState.REJECTED }.forEach { photo ->
            val time = photo.takenAt
            if (time == null) { issues += "UNDATED_PHOTO"; return@forEach }
            if (ExperienceTimeResolver.day(time) != scope.eventDate) return@forEach
            sources += MemorySource(MemoryCandidate("photo:${MemoryWire.digest(photo.uri)}", MemoryKind.PHOTO,
                scope.eventDate, MemoryTimeBasis.PHOTO_CAPTURE, "", MemoryWire.timestamp(time), null, null, null, null),
                photo.uri, MemorySourceFingerprint.photo(photo))
        }
        db.noteDao().getAll().filter { it.id != note.id && it.kind == NoteKind.VOICE && it.capturedAt <= note.capturedAt }.forEach { other ->
            val date = ExperienceTimeResolver.resolve(other.text, other.capturedAt).eventDate
            if (date == null) { issues += "UNDATED_NOTE"; return@forEach }
            if (date != scope.eventDate) return@forEach
            if (!MemoryWire.string(other.text, 4000)) { issues += "NOTE_TOO_LARGE"; return@forEach }
            sources += MemorySource(MemoryCandidate("note:${other.id}", MemoryKind.NOTE, date,
                MemoryTimeBasis.NOTE_EXPERIENCE, other.text, MemoryWire.timestamp(other.capturedAt), null, null, null, null),
                other.id.toString(), MemorySourceFingerprint.note(other))
        }
        val nav = try {
            when (navigation.status()) {
                NavigationEvidenceStatus.NOT_READY -> issues += "NAVIGATION_NOT_READY"
                NavigationEvidenceStatus.UNAVAILABLE -> issues += "NAVIGATION_UNAVAILABLE"
                NavigationEvidenceStatus.AVAILABLE -> Unit
            }
            navigation.snapshot()
        } catch (_: Exception) { issues += "NAVIGATION_UNAVAILABLE"; emptyList() }
        nav.forEach { value ->
            // Observation is evidence of an active destination *on the observed day*, not arrival.
            val occurred = if (value.kind == NavigationEvidenceKind.ARRIVAL_OBSERVED) value.eventAtMillis else null
            val date = when (value.kind) {
                NavigationEvidenceKind.DESTINATION_OBSERVED -> ExperienceTimeResolver.day(value.observedAtMillis)
                NavigationEvidenceKind.ARRIVAL_OBSERVED -> ExperienceTimeResolver.day(occurred ?: value.observedAtMillis)
                NavigationEvidenceKind.UNDATED_RECENT -> null
            }
            if (date == null) { issues += "UNDATED_NAVIGATION"; return@forEach }
            if (date != scope.eventDate || !MemoryWire.string(value.placeName, 200)) return@forEach
            val coordinates = value.latitude != null && value.longitude != null && value.latitude.isFinite() && value.longitude.isFinite() &&
                value.latitude in -90.0..90.0 && value.longitude in -180.0..180.0
            val basis = if (value.kind == NavigationEvidenceKind.ARRIVAL_OBSERVED) MemoryTimeBasis.NAV_ARRIVED else MemoryTimeBasis.NAV_OBSERVED
            val description = if (basis == MemoryTimeBasis.NAV_OBSERVED) "내비에서 관측한 활성 목적지: ${value.placeName}. 설정일·도착·방문 증거가 아님."
                else if (occurred == null) "내비 도착 이벤트 관측: ${value.placeName}. 날짜는 관측일이며 정확한 도착시각·확정 방문은 아님."
                else "내비 도착 이벤트로 관측한 목적지: ${value.placeName}. 확정 방문은 아님."
            val candidate = MemoryCandidate("nav:${MemoryWire.digest(value.id)}", MemoryKind.NAVIGATION, date, basis,
                description, occurred?.let(MemoryWire::timestamp), MemoryWire.timestamp(value.observedAtMillis), value.placeName,
                if (coordinates) value.latitude else null, if (coordinates) value.longitude else null)
            sources += MemorySource(candidate, value.id, MemoryWire.digest(MemoryWire.json.encodeToString(MemoryCandidate.serializer(), candidate)))
        }
        db.visitDao().getDay(checkNotNull(scope.eventDate)).filter { it.arrivedAt <= note.capturedAt }.forEach { visit ->
            if (!MemoryWire.string(visit.placeName, 200)) return@forEach
            sources += MemorySource(MemoryCandidate("visit:${MemoryWire.digest(visit.id)}", MemoryKind.VISIT, visit.date,
                MemoryTimeBasis.VISIT, "기존 방문 기록(시연 시드): ${visit.placeName}", MemoryWire.timestamp(visit.arrivedAt), null,
                visit.placeName, visit.lat.takeIf { it.isFinite() && it in -90.0..90.0 },
                visit.lng.takeIf { it.isFinite() && it in -180.0..180.0 }), visit.id, MemorySourceFingerprint.visit(visit))
        }
        val ordered = sources.distinctBy { it.candidate.id }.filter { it.candidate.id !in rejected }
            .sortedWith(compareBy({ it.candidate.kind.ordinal }, { it.candidate.occurredAt ?: it.candidate.observedAt.orEmpty() }, { it.candidate.id }))
        val start = state.pageOffset.coerceAtMost(ordered.size)
        val selected = mutableListOf<MemorySource>()
        var photos = 0
        for (source in ordered.drop(start)) {
            if (selected.size == 20 || source.candidate.kind == MemoryKind.PHOTO && photos == 10) break
            selected += source
            if (source.candidate.kind == MemoryKind.PHOTO) photos++
        }
        val next = start + selected.size
        return MemoryCandidateBatch(scope, selected, next, next < ordered.size, issues)
    }
}
