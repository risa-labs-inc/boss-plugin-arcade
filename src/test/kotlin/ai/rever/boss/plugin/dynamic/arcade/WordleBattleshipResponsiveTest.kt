package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.battleship.*
import ai.rever.boss.plugin.dynamic.arcade.wordle.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.File
import org.jetbrains.skia.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WordleBattleshipResponsiveTest {
    private val sizes = listOf(1000 to 700, 600 to 500, 420 to 300, 300 to 500, 900 to 240, 300 to 240)

    private class Fixture {
        val clock = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(clock))
        val leaderboard = LeaderboardService(null, null)
        var detail = MatchDetail(matchId = "fixture", status = "active", myTurn = true,
            opponentName = "A teammate with a very long display name", myShots = listOf(ShotRecord(0, "hit")),
            theirShots = listOf(ShotRecord(12, "miss")))
        val backend = FakeSupabase { name, _ ->
            when (name) {
                "arcade_bs_match_detail" -> Result.success(Json.encodeToString(detail))
                "arcade_players" -> Result.success(Json.encodeToString((1..20).map { BattleshipPlayer("p$it", "Opponent $it") }))
                "arcade_bs_fire" -> Result.failure(IllegalStateException("Fixture shot refused"))
                else -> Result.success("[]")
            }
        }
        val battleship = BattleshipService(backend, FakeAuth("layout-fixture"))
        val credits = CreditsService(null, null) { scope }
        val services = ArcadeServices({ scope }, null, null, leaderboard, credits, battleship, null, null, null)
        fun close() { scope.cancel() }
    }

    @Test
    fun wordleKeyboardBoardHelpAndInputSurviveEverySplitSize() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                val vm = WordleViewModel(f.scope, f.services)
                var pane by mutableStateOf(1000 to 700)
                setContent { TestPane(pane) {
                    WordleScreen(vm, f.leaderboard) {}
                } }
                for (size in sizes) {
                    runOnIdle { pane = size }
                    onNodeWithText("HOW TO PLAY").reveal()
                    onNodeWithText("?").reveal().performClick()
                    onNodeWithTag("wordle-grid", true).reveal()
                    fitsHorizontally(onNodeWithTag("wordle-grid", true))
                    for (key in "QWERTYUIOPASDFGHJKLZXCVBNM".map { it.toString() } + listOf("ENTER", "⌫")) {
                        val node = onNodeWithTag("wordle-key-$key", true).reveal()
                        fitsHorizontally(node)
                        if (key == "⌫") onNodeWithContentDescription("Backspace").reveal()
                        else if (key == "ENTER") onNodeWithContentDescription("Enter").reveal()
                        else onNodeWithText(key, useUnmergedTree = true).reveal()
                        val bounds = node.getUnclippedBoundsInRoot()
                        assertTrue((bounds.right - bounds.left) >= 20.dp && (bounds.bottom - bounds.top) >= 40.dp)
                    }
                    if (size.first == 300 || size.first == 900) screenshot("wordle-keys-${size.first}-${size.second}")
                    onNodeWithTag("wordle-key-Q", true).reveal().performClick()
                    assertEquals("Q", vm.state.value.current)
                    onNodeWithTag("wordle-key-⌫", true).reveal().performClick()
                    assertEquals("", vm.state.value.current)
                    onNodeWithTag("wordle-key-ENTER", true).reveal().performClick()
                    assertEquals("Not enough letters", vm.state.value.message)
                    onNodeWithText("Leaderboard").reveal().performClick()
                    onNodeWithContentDescription("Close").reveal().performClick()
                    onNodeWithText("?").reveal().performClick()
                }
                runOnIdle { vm.onKey('A'); pane = 180 to 160 }
                onNodeWithTag("wordle-grid", true).assertDoesNotExist()
                onNodeWithText("Enlarge this pane to play Wordle.").reveal()
                onRoot().performKeyInput { pressKey(Key.Backspace) }
                assertEquals("A", vm.state.value.current)
                onNodeWithContentDescription("Back to games").reveal()
                runOnIdle { pane = 260 to 500 }
                onNodeWithTag("wordle-grid", true).assertDoesNotExist()
                onNodeWithText("Enlarge this pane to play Wordle.").reveal()
                runOnIdle { pane = 280 to 500 }
                val minimumKey = onNodeWithTag("wordle-key-Q", true).reveal().getUnclippedBoundsInRoot()
                assertTrue(minimumKey.right - minimumKey.left >= 20.dp)
                onNodeWithTag("wordle-grid", true).assertExists()
                runOnIdle { pane = 600 to 500 }
                onNodeWithTag("wordle-key-⌫", true).reveal().performClick()
                assertEquals("", vm.state.value.current)
            }
        } finally { f.close() }
    }

    @Test
    fun wordleResultActionsScrollInShortPanesAndResizePreservesFinishedBoard() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                val vm = WordleViewModel(f.scope, f.services)
                var pane by mutableStateOf(1000 to 700)
                runOnIdle {
                    vm.tryGuess(WordleWords.answerForDay(WordleWords.todayEpochDay()))
                    f.clock.advanceTimeBy(2500)
                }
                setContent { TestPane(pane) {
                    WordleScreen(vm, f.leaderboard) {}
                } }
                val finished = vm.state.value
                assertEquals(WordleViewModel.Phase.WON, finished.phase)
                for (size in sizes) {
                    runOnIdle { pane = size }
                    onNodeWithText("Copy result").reveal().also { fitsHorizontally(it) }
                    if (size.first == 300 || size.first == 900) screenshot("wordle-result-${size.first}-${size.second}")
                    onNodeWithText("View board").reveal().also { fitsHorizontally(it) }.performClick()
                    onNodeWithText("Result").reveal().performClick()
                    assertEquals(finished.rows, vm.state.value.rows)
                    assertEquals(finished.points, vm.state.value.points)
                }
            }
        } finally { f.close() }
    }

    @Test
    fun battleshipLobbyPickerPlacementAndFleetRemainReachableAfterResize() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                val vm = BattleshipViewModel(f.scope, f.services)
                var pane by mutableStateOf(1000 to 700)
                setContent { TestPane(pane) {
                    BattleshipScreen(vm) {}
                } }
                for (size in sizes) {
                    runOnIdle { pane = size }
                    onNodeWithText("Refresh").reveal().also { fitsHorizontally(it) }
                    onNodeWithText("Challenge someone").reveal().performClick()
                    onNodeWithText("Opponent 20").reveal().also { fitsHorizontally(it) }
                    onNodeWithText("Cancel").reveal().performClick()
                }
                runOnIdle { vm.challengeOpponent(BattleshipPlayer("other", "Teammate")); vm.randomizeFleet() }
                val fleet = vm.placed.toList()
                for (size in sizes) {
                    runOnIdle { pane = size }
                    onNodeWithText("Rotate (horizontal)").reveal().also { fitsHorizontally(it) }
                    onNodeWithText("Random").reveal().also { fitsHorizontally(it) }
                    onNodeWithText("Clear").reveal().also { fitsHorizontally(it) }
                    for (ship in BattleshipLogic.FLEET) onNodeWithText("✓ ${ship.label} ${ship.length}").reveal().also { fitsHorizontally(it) }
                    verifyGrid(0)
                    if (size.first == 300 || size.first == 900) screenshot("battleship-placement-${size.first}-${size.second}")
                    onNodeWithText("Submit fleet").reveal().assertIsEnabled()
                    assertEquals(fleet, vm.placed.toList())
                }
                runOnIdle { pane = 180 to 160 }
                onNodeWithTag("battleship-grid", true).assertDoesNotExist()
                onNodeWithText("Enlarge this pane to use the Battleship board.").reveal()
                onNodeWithText("← Arcade").reveal().performClick()
                assertEquals(BattleshipViewModel.Phase.LOBBY, vm.phase)
            }
        } finally { f.close() }
    }

    @Test
    fun battleshipBothBoardsAndRefreshFitDuringMyTurnWaitingAndFinishedStates() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                val vm = BattleshipViewModel(f.scope, f.services)
                var pane by mutableStateOf(1000 to 700)
                setContent { TestPane(pane) {
                    BattleshipScreen(vm) {}
                } }
                for (phase in listOf("my-turn", "waiting", "won", "lost")) {
                    runOnIdle {
                        f.detail = f.detail.copy(myTurn = phase == "my-turn", finished = phase in listOf("won", "lost"), iWon = phase == "won")
                        vm.openMatch("fixture")
                    }
                    for (size in sizes) {
                        runOnIdle { pane = size }
                        onNodeWithText("Refresh").reveal().also { fitsHorizontally(it) }
                        verifyGrid(0)
                        verifyGrid(1)
                        assertEquals(f.detail, vm.detail)
                    }
                    val before = f.backend.calls.count { it.first == "arcade_bs_fire" }
                    val target = onAllNodesWithTag("battleship-cell-99", true)[0].reveal()
                    if (phase == "my-turn") {
                        target.performClick()
                        assertEquals(before + 1, f.backend.calls.count { it.first == "arcade_bs_fire" })
                    } else target.assertHasNoClickAction()
                }
            }
        } finally { f.close() }
    }

    @Test
    fun wordleLossResultAlsoKeepsEveryActionReachable() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                val vm = WordleViewModel(f.scope, f.services)
                var pane by mutableStateOf(1000 to 700)
                runOnIdle {
                    val answer = WordleWords.answerForDay(WordleWords.todayEpochDay())
                    val wrong = if (answer == "CRANE") "SLATE" else "CRANE"
                    repeat(6) {
                        assertEquals(null, vm.tryGuess(wrong))
                        f.clock.advanceTimeBy(1500)
                    }
                    f.clock.advanceTimeBy(1000)
                }
                setContent { TestPane(pane) {
                    WordleScreen(vm, f.leaderboard) {}
                } }
                assertEquals(WordleViewModel.Phase.LOST, vm.state.value.phase)
                for (size in sizes) {
                    runOnIdle { pane = size }
                    onNodeWithText("Out of guesses").reveal().also { fitsHorizontally(it) }
                    onNodeWithText("Copy result").reveal().also { fitsHorizontally(it) }
                    onNodeWithText("View board").reveal().performClick()
                    onNodeWithText("Result").reveal().performClick()
                    assertEquals(6, vm.state.value.rows.size)
                }
            }
        } finally { f.close() }
    }

    @Test
    fun battleshipNavigationStartsAtTopButResizeKeepsScroll() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                val vm = BattleshipViewModel(f.scope, f.services)
                var pane by mutableStateOf(600 to 500)
                runOnIdle {
                    vm.matches.addAll((1..30).map { MatchSummary(matchId = "match-$it", opponentName = "Opponent $it", status = "active") })
                }
                setContent { TestPane(pane) { BattleshipScreen(vm) {} } }
                onAllNodesWithText("Open").onLast().reveal().performClick()
                onNodeWithText("Your move — pick a target").assertIsDisplayed()
                val enemyTop = onAllNodesWithTag("battleship-grid", true)[0].getUnclippedBoundsInRoot().top
                val paneBounds = onNodeWithTag("pane").getUnclippedBoundsInRoot()
                assertTrue(enemyTop >= paneBounds.top && enemyTop < paneBounds.bottom)
                onAllNodesWithTag("battleship-cell-99", true)[1].reveal()
                fun scroll() = onNodeWithTag("battleship-screen-scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue(scroll() > 0)
                runOnIdle { pane = 600 to 450 }
                assertTrue(scroll() > 0, "Resizing must not reset board position")
                runOnIdle { f.detail = f.detail.copy(matchId = "other"); vm.openMatch("other") }
                onNodeWithText("Your move — pick a target").assertIsDisplayed()
                assertEquals(0f, scroll(), "Another match starts at its headline")
                runOnIdle { vm.leaveBoard(); vm.matches.addAll((1..30).map { MatchSummary(matchId = "pending-$it", opponentName = "Opponent $it", status = "pending") }) }
                onAllNodesWithText("Accept").onLast().reveal().performClick()
                onNodeWithText("Accept Opponent 30").assertIsDisplayed()
                assertEquals(0f, scroll(), "Placement starts at its instructions")
            }
        } finally { f.close() }
    }

    @Composable
    private fun TestPane(pane: Pair<Int, Int>, content: @Composable BoxScope.() -> Unit) {
        val scale = if (pane == 300 to 500) 1.5f else 1f
        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
            Box(Modifier.requiredSize(pane.first.dp, pane.second.dp).testTag("pane"), content = content)
        }
    }

    private fun DesktopComposeUiTest.screenshot(name: String) {
        val bitmap = onNodeWithTag("pane").captureToImage().asSkiaBitmap()
        val file = File("build/responsive-screenshots/$name.png")
        file.parentFile.mkdirs()
        file.writeBytes(Image.makeFromBitmap(bitmap).encodeToData()!!.bytes)
    }

    private fun DesktopComposeUiTest.verifyGrid(index: Int) {
        val grid = onAllNodesWithTag("battleship-grid", true)[index]
        grid.performScrollTo()
        fitsHorizontally(grid)
        val board = grid.getUnclippedBoundsInRoot()
        assertEquals((board.right - board.left), (board.bottom - board.top))
        for (cell in listOf(0, 9, 90, 99)) {
            val bounds = onAllNodesWithTag("battleship-cell-$cell", true)[index].getUnclippedBoundsInRoot()
            assertTrue((bounds.right - bounds.left) >= 20.dp && (bounds.bottom - bounds.top) >= 20.dp)
            assertTrue(bounds.left >= board.left && bounds.right <= board.right && bounds.top >= board.top && bounds.bottom <= board.bottom)
        }
    }

    private fun DesktopComposeUiTest.fitsHorizontally(node: SemanticsNodeInteraction) {
        val pane = onNodeWithTag("pane").getUnclippedBoundsInRoot()
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= pane.left && bounds.right <= pane.right, "$bounds must fit $pane")
    }

    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
        if (runCatching { assertIsDisplayed() }.isFailure || getBoundsInRoot() != getUnclippedBoundsInRoot()) performScrollTo()
        assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        if (fetchSemanticsNode().config.contains(SemanticsActions.GetTextLayoutResult)) {
            performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            for (layout in layouts) {
                assertTrue(layout.multiParagraph.height <= layout.size.height + 1f, "Text height must fit: ${layout.layoutInput.text}")
                for (line in 0 until layout.lineCount) {
                    assertTrue(layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f,
                        "Text glyphs must fit: ${layout.layoutInput.text}; line ${layout.getLineLeft(line)}..${layout.getLineRight(line)} in ${layout.size.width}; ${layout.layoutInput.density}")
                }
                if (layout.layoutInput.text.length == 1) assertEquals(1, layout.lineCount, "Keyboard letters stay on one line")
                if (layout.layoutInput.text.length <= 20) assertTrue(layout.lineCount <= 2,
                    "Short labels must not become vertical text: ${layout.layoutInput.text}")
            }
        }
        return this
    }
}
