package app.memoryvault.p1.provenance

import app.memoryvault.data.LinkState
import app.memoryvault.data.NoteKind

/** The source of a record and its current decision are separate display facts. */
data class ProvenancePresentation(val sourceLabel: String, val statusLabel: String)

/** Pure presentation mapping; it never changes source records, user decisions, or AI results. */
object ProvenancePolicy {
    private val unknown = ProvenancePresentation("출처 확인 필요", "상태 확인 필요")

    /** Call only for the known replay seed, never for an arbitrary observed visit. */
    fun seedVisit(): ProvenancePresentation = ProvenancePresentation("시연 데이터", "실제 방문 미확인")

    fun note(kind: NoteKind?): ProvenancePresentation = when (kind) {
        NoteKind.TYPED -> ProvenancePresentation("내가 남긴 말", "직접 입력")
        NoteKind.VOICE -> ProvenancePresentation("내가 남긴 말", "음성 인식 원문")
        null -> unknown
    }

    fun photo(state: LinkState?): ProvenancePresentation = when (state) {
        LinkState.CANDIDATE -> ProvenancePresentation("사진", "후보")
        LinkState.CONFIRMED -> ProvenancePresentation("사진", "확인됨")
        LinkState.REJECTED -> ProvenancePresentation("사진", "사용자 거절")
        LinkState.UNLINKED -> ProvenancePresentation("사진", "미연결")
        null -> unknown
    }

    /** Accepts both v1 enum names and v2 stored strings without guessing at unknown values. */
    fun link(origin: String?, status: String?): ProvenancePresentation = when {
        origin == "AI" && status == "SUGGESTED" -> ProvenancePresentation("AI 제안", "후보")
        origin == "AI" && status == "CONFIRMED" -> ProvenancePresentation("AI 제안", "확인됨 (사용자 연결)")
        origin == "USER" && status == "CONFIRMED" -> ProvenancePresentation("사용자 연결", "확인됨 (사용자 연결)")
        else -> unknown
    }
}
