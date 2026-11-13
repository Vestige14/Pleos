package app.memoryvault.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkStateTest {
    @Test fun userConfirmedAndRejectedStates_areProtectedDecisions() {
        assertTrue(LinkState.CONFIRMED.isUserDecided)
        assertTrue(LinkState.REJECTED.isUserDecided)
    }

    @Test fun machineCandidateAndUnlinkedStates_canBeReevaluated() {
        assertFalse(LinkState.CANDIDATE.isUserDecided)
        assertFalse(LinkState.UNLINKED.isUserDecided)
    }
}
