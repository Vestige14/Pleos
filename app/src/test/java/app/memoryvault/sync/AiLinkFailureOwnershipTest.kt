package app.memoryvault.sync

import app.memoryvault.data.AiLinkJob
import app.memoryvault.data.acceptsLinkFailure
import app.memoryvault.domain.AiLinkJobState
import org.junit.Assert.*
import org.junit.Test

class AiLinkFailureOwnershipTest {
    private val running = AiLinkJob("job", 1, 1, 0, "h", "memory-links-v1", AiLinkJobState.RUNNING,
        1, 0, leaseUntil = 1_000, attemptToken = "current", lastErrorCode = LinkError.OUTCOME_UNKNOWN.name)

    @Test fun sameTokenUnknownCanSettleAtAndAfterLeaseExpiry() {
        assertTrue(running.acceptsLinkFailure("current", 999, LinkError.OUTCOME_UNKNOWN))
        for (time in listOf(1_000L, 1_001L, Long.MAX_VALUE)) {
            assertTrue("The current attempt must settle clear UNKNOWN even after lease expiry",
                running.acceptsLinkFailure("current", time, LinkError.OUTCOME_UNKNOWN))
        }
    }

    @Test fun otherFailuresStillRequireAnUnexpiredLease() {
        for (error in listOf(LinkError.UPSTREAM_UNAVAILABLE, LinkError.UPSTREAM_RATE_LIMIT,
            LinkError.MALFORMED_RESPONSE, LinkError.UNAUTHORIZED)) {
            assertTrue(running.acceptsLinkFailure("current", 999, error))
            assertFalse(running.acceptsLinkFailure("current", 1_000, error))
            assertFalse(running.acceptsLinkFailure("current", 1_001, error))
        }
    }

    @Test fun replacedTokenCannotSettleCurrentOwnerBeforeOrAfterExpiry() {
        val replacement = running.copy(attemptToken = "replacement")
        for (time in listOf(999L, 1_000L, 1_001L)) {
            for (error in listOf(LinkError.OUTCOME_UNKNOWN, LinkError.UPSTREAM_UNAVAILABLE)) {
                assertFalse(replacement.acceptsLinkFailure("current", time, error))
            }
        }
    }

    @Test fun nonRunningStatesAndMissingTokenRejectLateUnknown() {
        for (state in listOf(AiLinkJobState.WAITING, AiLinkJobState.RETRY_WAIT, AiLinkJobState.DONE,
            AiLinkJobState.REVIEW_REQUIRED, AiLinkJobState.SUPERSEDED, AiLinkJobState.BLOCKED)) {
            for (time in listOf(999L, 1_000L)) {
                assertFalse(running.copy(state = state).acceptsLinkFailure("current", time, LinkError.OUTCOME_UNKNOWN))
            }
        }
        assertFalse(running.copy(attemptToken = null).acceptsLinkFailure("current", 999, LinkError.OUTCOME_UNKNOWN))
    }
}
