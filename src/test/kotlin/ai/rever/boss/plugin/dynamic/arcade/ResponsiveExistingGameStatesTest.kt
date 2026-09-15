package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipService
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048Screen
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048ViewModel
import ai.rever.boss.plugin.dynamic.arcade.typingsprint.TypingSprintScreen
import ai.rever.boss.plugin.dynamic.arcade.typingsprint.TypingSprintViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.File
import org.jetbrains.skia.Image
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResponsiveExistingGameStatesTest {
    private class Fixture {
        val scope = TestScope()
        val storage = FakeStorage()
        val backend = FakeSupabase { name, _ -> Result.success(
            when (name) {
                "arcade_charge_run" -> """{"ok":true,"balance":9900,"cost":100}"""
                else -> """{"balance":10050,"weeklyFloor":10000}"""
            }
        ) }
        val credits = CreditsService(backend, FakeAuth("layout-states")) { scope }
        val leaderboard = LeaderboardService(null, null)
        val services = ArcadeServices({ scope }, null, storage, leaderboard, credits,
            BattleshipService(null, null), null, null, null)
        init { scope.runCurrent() }
        fun close() = scope.cancel()
    }

    @Test
    fun won2048RunKeepsBothOverlayActionsAcrossResizeAndCanContinueWithoutCharge() {
        val f = Fixture()
        try {
            f.storage.raw["save.2048.local"] =
                """{"cells":[[1024,1024,0,0],[0,0,0,0],[0,0,0,0],[0,0,0,0]],"score":100,"won":false}"""
            val vm = Game2048ViewModel(f.scope, f.services)
            f.scope.runCurrent()
            assertTrue(vm.move(0, -1))
            f.scope.advanceTimeBy(600)
            f.scope.runCurrent()
            assertEquals(Game2048ViewModel.Veil.WIN, vm.state.value.veil)
            val won = vm.state.value
            val charges = f.backend.calls.count { it.first == "arcade_charge_run" }
            runDesktopComposeUiTest(1000, 700) {
                var pane by mutableStateOf(1000 to 700)
                setContent { Box(Modifier.requiredSize(pane.first.dp, pane.second.dp).testTag("state-pane")) {
                    ArcadeBackground { Game2048Screen(vm, f.leaderboard) {} }
                } }
                for (size in listOf(900 to 240, 420 to 300, 600 to 400, 1000 to 700, 300 to 240)) {
                    runOnIdle { pane = size }
                    onNodeWithText("Keep going").performScrollTo().assertIsDisplayed()
                    assertReadableLabel("Keep going")
                    val action = onNodeWithText("Keep going").getUnclippedBoundsInRoot()
                    val board = onNodeWithTag("state-pane", useUnmergedTree = true).getUnclippedBoundsInRoot()
                    assertTrue(action.left >= board.left && action.right <= board.right &&
                        action.top >= board.top && action.bottom <= board.bottom,
                        "Winning action must be within pane at $size: $action / $board")
                    screenshot("2048-win-${size.first}-${size.second}")
                    assertEquals(won, vm.state.value)
                    assertEquals(charges, f.backend.calls.count { it.first == "arcade_charge_run" })
                }
                onNodeWithText("Keep going").performClick()
                runOnIdle {
                    assertEquals(null, vm.state.value.veil)
                    assertEquals(won.tiles, vm.state.value.tiles)
                    assertEquals(won.score, vm.state.value.score)
                    assertEquals(charges, f.backend.calls.count { it.first == "arcade_charge_run" })
                }
            }
        } finally { f.close() }
    }

    @Test
    fun completedTypingResultsSurviveResizeAndGoAgainActuallyRestarts() {
        val f = Fixture()
        try {
            val vm = TypingSprintViewModel(f.scope, f.services)
            runDesktopComposeUiTest(1000, 700) {
                var pane by mutableStateOf(600 to 500)
                setContent { Box(Modifier.requiredSize(pane.first.dp, pane.second.dp)) {
                    ArcadeBackground { TypingSprintScreen(vm, f.leaderboard) {} }
                } }
                onNode(hasSetTextAction()).performTextInput("abc")
                runOnIdle { vm.onDisposed() }
                assertEquals(TypingSprintViewModel.Phase.DONE, vm.phase)
                val score = vm.score
                val charges = f.backend.calls.count { it.first == "arcade_charge_run" }
                for (size in listOf(300 to 240, 900 to 240, 420 to 300, 1000 to 700)) {
                    runOnIdle { pane = size }
                    onNodeWithText("Go again").performScrollTo().assertIsDisplayed()
                    screenshot("typing-done-${size.first}-${size.second}")
                    onNodeWithText("Leaderboard").performScrollTo().performClick()
                    onNodeWithContentDescription("Close").performScrollTo().performClick()
                    assertEquals(TypingSprintViewModel.Phase.DONE, vm.phase)
                    assertEquals(score, vm.score)
                    assertEquals(charges, f.backend.calls.count { it.first == "arcade_charge_run" })
                }
                onNodeWithText("Go again").performScrollTo().performClick()
                assertEquals(TypingSprintViewModel.Phase.IDLE, vm.phase)
                onNode(hasSetTextAction()).performTextInput("a")
                assertEquals(TypingSprintViewModel.Phase.RUNNING, vm.phase)
            }
        } finally { f.close() }
    }
    @Test
    fun gameOverCanRestartInSmallPaneWithIncreasedFontScale() {
        val f = Fixture()
        try {
            f.storage.raw["save.2048.local"] =
                """{"cells":[[2,2,8,16],[8,16,32,64],[16,32,64,128],[32,64,128,256]],"score":100,"won":false}"""
            val vm = Game2048ViewModel(f.scope, f.services)
            f.scope.runCurrent()
            assertTrue(vm.move(0, -1))
            f.scope.advanceTimeBy(600)
            f.scope.runCurrent()
            assertEquals(Game2048ViewModel.Veil.OVER, vm.state.value.veil)
            runDesktopComposeUiTest(300, 240) {
                setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                        ArcadeBackground { Game2048Screen(vm, f.leaderboard) {} }
                    }
                }
                onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
                assertReadableLabel("Try again")
                screenshot("2048-gameover-largefont-300-240")
                onNodeWithText("Try again").performClick()
                assertEquals(null, vm.state.value.veil)
                assertEquals(0, vm.state.value.score)
                assertEquals(2, vm.state.value.tiles.size)
            }
        } finally { f.close() }
    }

    private fun DesktopComposeUiTest.assertReadableLabel(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val matching = layouts.filter { it.layoutInput.text.text == label }
        assertTrue(matching.isNotEmpty(), "Missing text layout for $label")
        for (layout in matching) {
            // Paragraph width can be the available constraint, not the glyph width.
            // Inspect the actual line bounds with one pixel of rounding tolerance.
            assertEquals(1, layout.lineCount, "$label should not wrap")
            assertTrue(layout.getLineLeft(0) >= -1f)
            assertTrue(layout.getLineRight(0) <= layout.size.width + 1f)
            assertTrue(layout.multiParagraph.height <= layout.size.height + 1f)
        }
    }

    private fun DesktopComposeUiTest.screenshot(name: String) {
        val output = File("build/responsive-screenshots/$name.png")
        output.parentFile.mkdirs()
        output.writeBytes(Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
    }

}
