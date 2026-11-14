package app.memoryvault.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareImageTextTest {
    // Rendering input order or private source records instead of the snapshot would change these lines.
    @Test fun dateThenNumberedPlacesAndOnlyExplicitSharedCaptions() {
        val draft = ShareDraft("2026-11-08", listOf(
            ShareItem(1, "대부도 칼국수집", listOf("p1"), null),
            ShareItem(2, "방아머리해변", listOf("p2"), "바다에서 한참 놀았어요"),
        ))

        assertEquals(listOf("2026년 11월 8일", "1. 대부도 칼국수집", "2. 방아머리해변", "바다에서 한참 놀았어요"),
            ShareImageText.lines(draft))
    }
}
