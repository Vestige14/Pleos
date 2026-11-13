package app.memoryvault.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedLoaderTest {
    // An unsorted parser would assign the wrong chronological card/capture context.
    @Test fun parsesVisitsInTimeOrder() {
        val seed = SeedLoader.parse("""{"date":"2026-11-08","demoNow":"2026-11-08T14:58:00+09:00","visits":[
            {"id":"v2","placeName":"방아머리해변","lat":37.2846,"lng":126.5779,"arrivedAt":"2026-11-08T13:30:00+09:00","leftAt":"2026-11-08T14:50:00+09:00"},
            {"id":"v1","placeName":"대부도 칼국수집","lat":37.2830,"lng":126.5810,"arrivedAt":"2026-11-08T12:00:00+09:00","leftAt":"2026-11-08T13:10:00+09:00"}]}""")
        assertEquals(listOf("v1", "v2"), seed.visits.map { it.id })
        assertEquals(t("12:00"), seed.visits[0].arrivedAt)
        assertEquals("2026-11-08", seed.visits[0].date)
        assertEquals(t("14:58"), seed.demoNow)
    }

    // Bad intervals must not become capture candidates.
    @Test(expected = IllegalArgumentException::class)
    fun leftBeforeArrived_isRejected() {
        SeedLoader.parse("""{"date":"2026-11-08","demoNow":"2026-11-08T14:58:00+09:00","visits":[
            {"id":"v1","placeName":"x","lat":0.0,"lng":0.0,"arrivedAt":"2026-11-08T13:00:00+09:00","leftAt":"2026-11-08T12:00:00+09:00"}]}""")
    }

    // Reads the actual bundled input, so a missing or divergent asset is visible.
    @Test fun realSeedFile_matchesFixtures() {
        val seed = SeedLoader.parse(File("src/main/assets/seed_day.json").readText())
        assertEquals(day, seed.visits)
        assertEquals("2026-11-08", seed.date)
        assertEquals(t("14:58"), seed.demoNow)
    }
}
