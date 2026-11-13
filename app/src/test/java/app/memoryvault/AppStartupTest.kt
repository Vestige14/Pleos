package app.memoryvault

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AppStartupTest {
    @Test fun replayCaptureClock_placesBothInputPathsOnTheLabeledDemoDay() {
        assertEquals("2026-11-08T14:58+09:00[Asia/Seoul]",
            Instant.ofEpochMilli(ReplayCaptureClock.now()).atZone(ZoneId.of("Asia/Seoul")).toString())
    }

    @Test fun databaseStartupFailure_isClassifiedAndCannotRunDestructiveRetry() = runTest {
        var attempts = 0
        val startup = RepositoryStartup { attempts++; error("private database failure payload") }
        startup.initialize()
        assertEquals(RepositoryState.Failed, startup.state.value)
        assertFalse(startup.state.value.toString().contains("private database"))
        startup.initialize()
        assertEquals(1, attempts)
    }
}
