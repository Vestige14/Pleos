package app.memoryvault.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import app.memoryvault.data.DayDraft
import app.memoryvault.data.Note
import app.memoryvault.data.NoteKind
import app.memoryvault.data.SeedLoader
import app.memoryvault.data.VisitCard
import app.memoryvault.domain.DriveState
import app.memoryvault.platform.DriveSnapshot
import org.junit.Rule
import org.junit.Test

class P2HomeProvenanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun knownReplayVisitIsLabeledOnlyWhileForegroundAndParked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val seed = context.assets.open("seed_day.json").bufferedReader().use { SeedLoader.parse(it.readText()) }
        val ui = FixtureUi(DriveState.PARKED)
        val pendingNote = Note(91, null, "합성 음성 원문", NoteKind.VOICE, 1793509200000, sourceDate = seed.date)
        ui.day.value = DayDraftState.Ready(DayDraft(seed.date,
            listOf(VisitCard(seed.visits.first(), emptyList(), emptyList())), listOf(pendingNote)))
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MemoryVaultTheme { MemoryVaultScreen(ui, onRequestPermissions = {}) }
            }
        }

        compose.onNodeWithText("출처 · 시연 데이터").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("상태 · 실제 방문 미확인").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("출처 · 내가 남긴 말").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onAllNodesWithText("출처 · 시연 데이터").assertCountEquals(0)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("출처 · 시연 데이터").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            ui.day.value = DayDraftState.Ready(DayDraft(seed.date,
                listOf(VisitCard(seed.visits.first().copy(id = "independent-visit"), emptyList(), emptyList())),
                listOf(pendingNote)))
        }
        compose.onAllNodesWithText("출처 · 시연 데이터").assertCountEquals(0)
        compose.runOnIdle { ui.driveSnapshot.value = DriveSnapshot(state = DriveState.UNKNOWN, invalidationGeneration = 1) }
        compose.onAllNodesWithText("출처 · 내가 남긴 말").assertCountEquals(0)
    }
}
