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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import org.jetbrains.skia.Image

/** Actual production screens rendered offscreen; no host, account, audio or backend. */
@OptIn(ExperimentalTestApi::class)
class ResponsiveScreensTest {
    private val sizes = listOf(1000 to 700, 600 to 500, 600 to 400, 420 to 300, 300 to 500, 900 to 240, 300 to 240)

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val leaderboard = LeaderboardService(null, null)
        val creditBackend = FakeSupabase { name, _ ->
            Result.success(when (name) {
                "arcade_is_admin" -> "true"
                "arcade_charge_run" -> """{"ok":true,"balance":9900,"cost":100}"""
                else -> """{"balance":10050,"weeklyFloor":10000}"""
            })
        }
        val credits = CreditsService(creditBackend, FakeAuth("layout-fixture")) { scope }
        init { runBlocking { credits.refresh() } }
        val battleship = BattleshipService(null, null)
        val ambience = CasinoAmbiencePlayer(null) { scope }
        val services = ArcadeServices({ scope }, null, null, leaderboard, credits, battleship, null, null, null)
        fun close() { ambience.shutdown(); scope.cancel() }
    }

    @Test
    fun homeKeepsTitleAndEveryGameReachableAtSplitSizes() {
        for ((width, height) in sizes) {
            val f = Fixture()
            try {
                runDesktopComposeUiTest(width, height) {
                    var plays = 0
                    var creditRequests = 0
                    setContent {
                        ArcadeHomeScreen(f.leaderboard, f.battleship, f.credits, f.ambience,
                            { creditRequests++ }, {}, { plays++ }, {}, {}, {}, {}, {}, {})
                    }
                    onAllNodesWithText("BOSS ARCADE").onLast().assertIsDisplayed()
                    val title = onAllNodesWithText("BOSS ARCADE").onLast().fetchSemanticsNode().boundsInRoot
                    assertTrue(title.left >= 0 && title.right <= width, "Title must fit $width x $height: $title")
                    if (height <= 300) assertTrue(title.height < 70, "Compact title should leave space for games")
                    if (height == 240 || width == 420) screenshot("home-$width-$height")
                    if (width < 600 || height < 420) {
                        onNodeWithText("10,050", substring = true).reveal().performClick()
                        assertEquals(1, creditRequests)
                    }
                    onAllNodesWithText("2048").onLast().reveal()
                    onAllNodesWithText("Play")[0].reveal().performClick()
                    assertEquals(1, plays)
                    onNodeWithText("Poker").reveal()
                    if (width == 300 && height == 240) screenshot("home-narrow-short")
                }
            } finally { f.close() }
        }
    }

    @Test
    fun boardAndActionsRemainReachableAndResizeDoesNotRestartRun() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                val vm = Game2048ViewModel(f.scope, f.services)
                var pane by mutableStateOf(1000 to 700)
                setContent {
                    Box(Modifier.requiredSize(pane.first.dp, pane.second.dp).testTag("fixture-pane")) {
                        ArcadeBackground { Game2048Screen(vm, f.leaderboard) {} }
                    }
                }
                val initial = vm.state.value
                val initialCharges = f.creditBackend.calls.count { it.first == "arcade_charge_run" }
                for ((width, height) in sizes) {
                    runOnIdle { pane = width to height }
                    onNodeWithText("New game").reveal()
                    if (width >= 600 && height >= 224 && height < 420) {
                        onNodeWithTag("2048-board", useUnmergedTree = true).assertIsDisplayed()
                    } else {
                        onNodeWithTag("2048-board", useUnmergedTree = true).performScrollTo()
                    }
                    val board = onNodeWithTag("2048-board", useUnmergedTree = true).getUnclippedBoundsInRoot()
                    assertEquals((board.right - board.left), (board.bottom - board.top))
                    assertTrue((board.right - board.left) <= width.dp)
                    for (row in 0..3) for (col in 0..3) {
                        val cell = onNodeWithTag("2048-cell-$row-$col", useUnmergedTree = true).getUnclippedBoundsInRoot()
                        assertTrue((cell.right - cell.left) > 0.dp && (cell.bottom - cell.top) > 0.dp)
                        assertTrue(cell.left >= board.left && cell.top >= board.top &&
                            cell.right <= board.right && cell.bottom <= board.bottom,
                            "Cell $row,$col must stay in board at $width x $height")
                    }
                    if (width == 420 || width == 900) screenshot("2048-board-$width-$height", onNodeWithTag("fixture-pane"))
                    assertEquals(initial, vm.state.value, "Resizing must preserve the run")
                    assertEquals(initialCharges, f.creditBackend.calls.count { it.first == "arcade_charge_run" })
                    onNodeWithText("Leaderboard").reveal().performClick()
                    onNodeWithContentDescription("Close").reveal().performClick()
                    onNodeWithContentDescription("Back to games").reveal()
                    if (width == 420 || width == 900) screenshot("2048-controls-$width-$height", onNodeWithTag("fixture-pane"))
                }
                runOnIdle { pane = 180 to 160 }
                onNodeWithTag("2048-board", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithText("Enlarge this pane to play 2048.", useUnmergedTree = true).reveal()
                onRoot().performKeyInput {
                    pressKey(Key.DirectionLeft)
                    pressKey(Key.DirectionDown)
                    pressKey(Key.DirectionRight)
                    pressKey(Key.DirectionUp)
                }
                assertEquals(initial, vm.state.value, "Hidden board must not accept moves")
                onNodeWithContentDescription("Back to games").reveal()
            }
        } finally { f.close() }
    }

    @Test
    fun typingInputStatsAndActionsSurviveShrinkAndGrow() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                val vm = TypingSprintViewModel(f.scope, f.services)
                var pane by mutableStateOf(600 to 500)
                setContent {
                    Box(Modifier.requiredSize(pane.first.dp, pane.second.dp).testTag("fixture-pane")) {
                        ArcadeBackground { TypingSprintScreen(vm, f.leaderboard) {} }
                    }
                }
                onNode(hasSetTextAction()).performTextInput("abc")
                for ((width, height) in sizes) {
                    runOnIdle { pane = width to height }
                    for (stat in listOf("TIME", "WPM", "ACC", "BEST")) {
                        onNodeWithText(stat, useUnmergedTree = true).reveal()
                    }
                    onNodeWithText("Restart").reveal()
                    assertEquals("abc", vm.typed)
                    onNodeWithText("Leaderboard").reveal().performClick()
                    onNodeWithContentDescription("Close").reveal().performClick()
                }
                onNode(hasSetTextAction()).performTextInput("d")
                assertEquals("abcd", vm.typed)
            }
        } finally { f.close() }
    }

    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
        // A fitting layout need not scroll. Only demand a recovery path when
        // the actual control lies outside the visible pane.
        if (runCatching { assertIsDisplayed() }.isFailure) performScrollTo()
        return assertIsDisplayed()
    }

    private fun DesktopComposeUiTest.screenshot(name: String, target: SemanticsNodeInteraction = onRoot()) {
        val bitmap = target.captureToImage().asSkiaBitmap()
        val file = File("build/responsive-screenshots/$name.png")
        file.parentFile.mkdirs()
        file.writeBytes(Image.makeFromBitmap(bitmap).encodeToData()!!.bytes)
    }
}
