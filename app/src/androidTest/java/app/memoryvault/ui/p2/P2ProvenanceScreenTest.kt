package app.memoryvault.ui.p2

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.memory.MemoryAssociationProgress
import app.memoryvault.memory.MemoryCandidate
import app.memoryvault.memory.MemoryKind
import app.memoryvault.memory.MemoryReviewAction
import app.memoryvault.memory.MemoryReviewItem
import app.memoryvault.memory.MemoryReviewLink
import app.memoryvault.memory.MemoryReviewState
import app.memoryvault.memory.MemorySource
import app.memoryvault.memory.MemoryTimeBasis
import app.memoryvault.memory.ui.MemoryReviewScreen
import app.memoryvault.ui.MemoryVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class P2ProvenanceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun parkedVoiceAndTwoPhotosKeepSeparateOriginsAndLinkKeys() {
        val original = "합성 음성 원문과 사진 두 장"
        val note = Note(91, null, original, NoteKind.VOICE, 1793509200000, sourceDate = "2026-11-01")
        fun photo(id: String, description: String) = MemoryCandidate(id, MemoryKind.PHOTO, "2026-11-01",
            MemoryTimeBasis.PHOTO_CAPTURE, description, null, null, description, null, null)
        fun link(key: String, candidate: MemoryCandidate) = MemoryReviewLink(key, candidate.id, candidate.kind,
            0, original.codePointCount(0, original.length), "SUGGESTED", "AI", MemorySource(candidate, "synthetic", "hash"))
        val item = MemoryReviewItem(note, 0, false, MemoryReviewState(91, "SUGGESTED"), listOf(
            link("photo-a", photo("photo:a", "첫 사진")), link("photo-b", photo("photo:b", "둘째 사진"))))
        val allowed = mutableStateOf(true)
        var selected = emptySet<String>()
        compose.setContent { MemoryVaultTheme {
            MemoryReviewScreen(MemoryAssociationProgress(listOf(item)), allowed.value, false, null, null,
                {}, {}, {}, { _, action, keys -> if (action == MemoryReviewAction.CONFIRM) selected = keys },
                {}, { _, _, _ -> }, {}, {})
        } }

        compose.onNodeWithText("출처 · 내가 남긴 말").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("상태 · 음성 인식 원문").assertIsDisplayed()
        compose.onAllNodesWithText("출처 · AI 제안").assertCountEquals(2)
        compose.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("선택한 제안 확정").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("photo-a"), selected) }
        compose.runOnIdle { allowed.value = false }
        compose.onAllNodesWithText(original).assertCountEquals(0)
        compose.onAllNodesWithText("출처 · AI 제안").assertCountEquals(0)
    }
}
