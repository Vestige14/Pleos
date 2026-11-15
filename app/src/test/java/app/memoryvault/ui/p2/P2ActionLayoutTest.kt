package app.memoryvault.ui.p2

import app.memoryvault.ui.components.P2ActionLayout
import app.memoryvault.ui.components.actionLayoutFor
import org.junit.Assert.assertEquals
import org.junit.Test

class P2ActionLayoutTest {
    @Test fun splitWindowStacksBothActionsSoEachKeepsItsReadingWidth() {
        assertEquals(P2ActionLayout.STACKED, actionLayoutFor(contentWidthDp = 480f, fontScale = 1f))
    }

    @Test fun fullWidthShowsBothActionsTogether() {
        assertEquals(P2ActionLayout.SIDE_BY_SIDE, actionLayoutFor(contentWidthDp = 800f, fontScale = 1f))
    }

    @Test fun enlargedTextStacksActionsEvenAtNormallyWideWidth() {
        assertEquals(P2ActionLayout.STACKED, actionLayoutFor(contentWidthDp = 800f, fontScale = 1.6f))
    }
}
