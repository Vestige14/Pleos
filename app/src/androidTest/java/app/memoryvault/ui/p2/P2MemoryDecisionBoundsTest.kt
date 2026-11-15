package app.memoryvault.ui.p2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.memory.MemoryAssociationProgress
import app.memoryvault.memory.MemoryCandidate
import app.memoryvault.memory.MemoryKind
import app.memoryvault.memory.MemoryReviewItem
import app.memoryvault.memory.MemoryReviewLink
import app.memoryvault.memory.MemoryReviewState
import app.memoryvault.memory.MemorySource
import app.memoryvault.memory.MemoryTimeBasis
import app.memoryvault.memory.ui.MemoryReviewScreen
import app.memoryvault.ui.MemoryVaultTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class P2MemoryDecisionBoundsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneSuggestionKeepsEveryDecisionReachableAtNarrowWidthAndLargeText() {
        show(1, "SUGGESTED")
        assertDecision("선택한 제안 확정")
        assertDecision("이번 제안 거절")
        assertDecision("직접 연결 수정")
        assertDecision("명시적으로 다시 분석")
        assertDecision("다음 후보 묶음 분석")
    }

    @Test fun twoSuggestionsKeepTheSameDecisionBounds() {
        show(2, "SUGGESTED")
        assertDecision("선택한 제안 확정")
        assertDecision("이번 제안 거절")
        assertDecision("직접 연결 수정")
        assertDecision("명시적으로 다시 분석")
        assertDecision("다음 후보 묶음 분석")
    }

    @Test fun confirmedConnectionKeepsUnlinkAndManualDecisionFullSize() {
        show(1, "CONFIRMED")
        assertDecision("확정 연결 해제")
        assertDecision("직접 연결 수정")
    }

    private fun show(linkCount: Int, status: String) {
        val original = "합성 음성 원문입니다. 긴 장소 이름과 사진 설명이 큰 글자에서도 모두 읽히는지 확인합니다."
        val note = Note(301, null, original, NoteKind.VOICE, 1794105000000, sourceDate = "2026-11-08")
        val links = (1..linkCount).map { index ->
            val candidate = MemoryCandidate("photo:$index", MemoryKind.PHOTO, "2026-11-08",
                MemoryTimeBasis.PHOTO_CAPTURE, "합성 사진 $index", null, null, "합성 장소", null, null)
            MemoryReviewLink("photo-$index", candidate.id, candidate.kind, 0,
                original.codePointCount(0, original.length), status,
                if (status == "CONFIRMED") "USER" else "AI",
                MemorySource(candidate, "synthetic:$index", "synthetic"))
        }
        val item = MemoryReviewItem(note, 0, status == "CONFIRMED",
            MemoryReviewState(301, status = status, hasMore = status == "SUGGESTED"), links)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                MemoryVaultTheme {
                    Box(Modifier.width(320.dp)) {
                        MemoryReviewScreen(MemoryAssociationProgress(listOf(item)), true, false, null, null,
                            {}, {}, {}, { _, _, _ -> }, {}, { _, _, _ -> }, {}, {})
                    }
                }
            }
        }
    }

    private fun assertDecision(label: String) {
        val bounds = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$label height ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top >= 64.dp)
        assertTrue("$label width ${bounds.right - bounds.left}", bounds.right - bounds.left >= 200.dp)
    }
}
