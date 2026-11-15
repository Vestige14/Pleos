package app.memoryvault.p1.provenance

import app.memoryvault.data.LinkState
import app.memoryvault.data.NoteKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ProvenancePolicyTest {
    @Test fun seedVisitIsLabeledAsDemoRatherThanConfirmedVisit() {
        assertEquals(
            ProvenancePresentation("시연 데이터", "실제 방문 미확인"),
            ProvenancePolicy.seedVisit(),
        )
    }

    @Test fun typedNoteKeepsUserExpressionSeparateFromInputState() {
        assertEquals(
            ProvenancePresentation("내가 남긴 말", "직접 입력"),
            ProvenancePolicy.note(NoteKind.TYPED),
        )
    }

    @Test fun voiceNoteIsLabeledAsRecognizedOriginalNotAiText() {
        assertEquals(
            ProvenancePresentation("내가 남긴 말", "음성 인식 원문"),
            ProvenancePolicy.note(NoteKind.VOICE),
        )
    }

    @Test fun photoCandidateAndUserDecisionsRemainDistinct() {
        assertEquals(ProvenancePresentation("사진", "후보"), ProvenancePolicy.photo(LinkState.CANDIDATE))
        assertEquals(ProvenancePresentation("사진", "확인됨"), ProvenancePolicy.photo(LinkState.CONFIRMED))
        assertEquals(ProvenancePresentation("사진", "사용자 거절"), ProvenancePolicy.photo(LinkState.REJECTED))
        assertEquals(ProvenancePresentation("사진", "미연결"), ProvenancePolicy.photo(LinkState.UNLINKED))
    }

    @Test fun aiSuggestionIsNotAConfirmedFact() {
        assertEquals(
            ProvenancePresentation("AI 제안", "후보"),
            ProvenancePolicy.link("AI", "SUGGESTED"),
        )
    }

    @Test fun acceptedAiSuggestionStillCarriesAiSource() {
        assertEquals(
            ProvenancePresentation("AI 제안", "확인됨 (사용자 연결)"),
            ProvenancePolicy.link("AI", "CONFIRMED"),
        )
    }

    @Test fun explicitUserLinkIsSeparateFromAcceptedAiSuggestion() {
        assertEquals(
            ProvenancePresentation("사용자 연결", "확인됨 (사용자 연결)"),
            ProvenancePolicy.link("USER", "CONFIRMED"),
        )
    }

    @Test fun unknownOrContradictoryValuesNeverBecomeConfirmed() {
        val unknown = ProvenancePresentation("출처 확인 필요", "상태 확인 필요")
        assertEquals(unknown, ProvenancePolicy.note(null))
        assertEquals(unknown, ProvenancePolicy.photo(null))
        assertEquals(unknown, ProvenancePolicy.link(null, "CONFIRMED"))
        assertEquals(unknown, ProvenancePolicy.link("AI", "CONFIRM_PENDING"))
        assertEquals(unknown, ProvenancePolicy.link("USER", "SUGGESTED"))
    }
}
