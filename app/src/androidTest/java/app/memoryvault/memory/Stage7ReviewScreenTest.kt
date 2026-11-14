package app.memoryvault.memory

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.memory.ui.MemoryReviewScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class Stage7ReviewScreenTest {
    @get:Rule val compose = createComposeRule()
    private val original = "STAGE7 합성 원문 바다 🌊"
    private fun item(): MemoryReviewItem {
        val note = Note(91, null, original, NoteKind.VOICE, 1793509200000, sourceDate = "2026-11-01")
        val c = MemoryCandidate("nav:synthetic", MemoryKind.NAVIGATION, "2026-11-01", MemoryTimeBasis.NAV_ARRIVED,
            "합성 도착 이벤트 관측. 정확한 도착시각·확정 방문 아님.", null, "2026-11-01T12:00:00+09:00", "합성 바다", null, null)
        val link = MemoryReviewLink("key", c.id, c.kind, 0, original.codePointCount(0, original.length), "SUGGESTED", "AI", MemorySource(c, "synthetic", "hash"))
        return MemoryReviewItem(note, 0, false, MemoryReviewState(91, "SUGGESTED"), listOf(link))
    }
    @Test fun parkedSuggestionIsDistinctAndSelectionConfirmsOnlyExplicitly() {
        var selected = emptySet<String>(); var action: MemoryReviewAction? = null
        compose.setContent { MaterialTheme {
            MemoryReviewScreen(MemoryAssociationProgress(listOf(item())), true, false, null, null,
                {}, {}, {}, { _, a, keys -> action = a; selected = keys }, {}, { _, _, _ -> }, {}, {})
        } }
        compose.onAllNodesWithText(original).onFirst().performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("선택한 제안 확정").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MemoryReviewAction.CONFIRM, action); assertEquals(setOf("key"), selected) }
    }
    @Test fun losingReviewCapabilityRemovesPrivateTextAndControls() {
        val allowed = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            MemoryReviewScreen(MemoryAssociationProgress(listOf(item())), allowed.value, false, null, null,
                {}, {}, {}, { _, _, _ -> }, {}, { _, _, _ -> }, {}, {})
        } }
        compose.onAllNodesWithText(original).onFirst().performScrollTo().assertIsDisplayed()
        compose.runOnIdle { allowed.value = false }
        compose.onAllNodesWithText(original).assertCountEquals(0)
        compose.onNodeWithText("선택한 제안 확정").assertDoesNotExist()
    }
}
