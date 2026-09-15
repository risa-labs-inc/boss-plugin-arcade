package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipService
import ai.rever.boss.plugin.dynamic.arcade.mirrordash.*
import ai.rever.boss.plugin.dynamic.arcade.skystack.*
import ai.rever.boss.plugin.dynamic.arcade.poker.PokerScreen
import ai.rever.boss.plugin.dynamic.arcade.poker.PokerViewModel
import ai.rever.boss.plugin.dynamic.arcade.poker.POKER_URL
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import java.io.File
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.*

@OptIn(ExperimentalTestApi::class)
class ArcadeRemainingResponsiveTest {
    private val sizes = listOf(1000 to 700, 600 to 500, 420 to 300, 300 to 500, 900 to 240, 300 to 240)

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // SkyStack's sounds use the VM scope. These UI tests never open an audio device.
        val vmScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
        var pending = false
        val backend = FakeSupabase { name, _ -> Result.success(when (name) {
            "arcade_my_credits" -> if (pending) """{"balance":0,"pendingRequest":{"id":"pending","amount":1000}}""" else """{"balance":10050}"""
            "arcade_admin_requests" -> (0..5).joinToString(prefix = "[", postfix = "]") {
                """{"id":"row-$it","displayName":"Player with a long display name $it","amount":1000,"status":"pending","note":"A deliberately long note for a narrow pane"}"""
            }
            "arcade_request_credits" -> """{"ok":false,"error":"request_pending"}"""
            else -> """{"ok":true,"balance":9950,"cost":100}"""
        }) }
        val credits = CreditsService(backend, FakeAuth("responsive-fixture")) { scope }
        val leaderboard = LeaderboardService(null, null)
        val services = ArcadeServices({ scope }, null, null, leaderboard, credits, BattleshipService(null, null), null, null, null)
        init { runBlocking { credits.refresh() } }
        fun close() { scope.cancel(); vmScope.cancel() }
    }

    @Test
    fun allActionGameCardsAndTowerActionsAreReachableByPointer() = runDesktopComposeUiTest(1000, 700) {
        var pane by mutableStateOf(1000 to 700)
        var scene by mutableStateOf(0)
        var clicks = 0
        val callback = { clicks++; Unit }
        setContent {
            TestPane(pane) {
                when (scene) {
                    0 -> MirrorDashStartCard(callback)
                    1 -> MirrorDashPauseCard(callback)
                    2 -> MirrorDashOverCard(123456, true, callback, callback)
                    3 -> SkyStackStartCard(123456, remember { FocusRequester() }, callback)
                    4 -> SkyStackPauseCard(callback)
                    5 -> SkyStackOverCard(123456, 123456, true, callback, callback, callback)
                    else -> SkyStackTowerOverviewControls(123456, "Saved a tower to a deliberately long destination path",
                        onBack = callback, onExportSvg = callback, onExportPng = callback)
                }
            }
        }
        val actions = listOf(listOf("START GAME"), listOf("RESUME"), listOf("PLAY AGAIN", "Leaderboard"),
            listOf("START STACKING"), listOf("RESUME"), listOf("STACK AGAIN", "VIEW FULL TOWER", "Leaderboard"),
            listOf("BACK", "SAVE SVG", "SAVE PNG"))
        for (s in actions.indices) {
            runOnIdle { scene = s }
            for (size in sizes) {
                runOnIdle { pane = size }
                for (label in actions[s]) {
                    val before = clicks
                    onNodeWithText(label).reachable().performTouchInput { click() }
                    assertEquals(before + 1, clicks, "$label pointer action at $size")
                }
                if (size == 300 to 240) snapshot("card-$s-300-240")
            }
        }
    }

    @Test
    fun pausedActionGamesKeepStateAndHudNavigationAcrossResize() {
        for (mirror in listOf(true, false)) {
            val f = Fixture()
            try {
                runDesktopComposeUiTest(1000, 700) {
                    mainClock.autoAdvance = false
                    val dash = MirrorDashViewModel(f.vmScope, f.services)
                    val sky = SkyStackViewModel(f.vmScope, f.services)
                    var pane by mutableStateOf(1000 to 700)
                    var backs = 0
                    if (mirror) { dash.start(); dash.pauseIfPlaying() } else { sky.start(); sky.pauseIfPlaying() }
                    val charges = f.backend.calls.count { it.first == "arcade_charge_run" }
                    val block = sky.engine.current?.copy()
                    setContent {
                        TestPane(pane) {
                            if (mirror) MirrorDashScreen(dash, f.leaderboard) { backs++ }
                            else SkyStackScreen(sky, f.leaderboard) { backs++ }
                        }
                    }
                    mainClock.advanceTimeByFrame()
                    for (size in sizes) {
                        runOnIdle { pane = size }
                        mainClock.advanceTimeByFrame()
                        onNodeWithContentDescription("Back to games").reachable().performTouchInput { click() }
                        onNodeWithContentDescription("Leaderboard").reachable().performTouchInput { click() }
                        mainClock.advanceTimeByFrame()
                        onNodeWithContentDescription("Close").reachable().performTouchInput { click() }
                        mainClock.advanceTimeByFrame()
                        if (mirror) assertEquals(MirrorDashViewModel.Phase.PAUSED, dash.phase)
                        else {
                            assertEquals(SkyStackViewModel.Phase.PAUSED, sky.phase)
                            assertEquals(block, sky.engine.current)
                        }
                        assertEquals(charges, f.backend.calls.count { it.first == "arcade_charge_run" })
                    }
                    assertEquals(sizes.size, backs)
                    for (size in listOf(300 to 240, 900 to 240)) {
                        runOnIdle {
                            pane = size
                            if (mirror) dash.togglePause() else sky.togglePause()
                        }
                        mainClock.advanceTimeByFrame()
                        snapshot("${if (mirror) "mirror" else "sky"}-playing-${size.first}-${size.second}")
                        runOnIdle { if (mirror) dash.pauseIfPlaying() else sky.pauseIfPlaying() }
                        mainClock.advanceTimeByFrame()
                    }
                }
            } finally { f.close() }
        }
    }

    @Test
    fun creditRequestPreservesFormAndErrorAndCancelStayReachable() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                var pane by mutableStateOf(1000 to 700)
                var closes = 0
                setContent { TestPane(pane) {
                    RequestCreditsDialog(f.credits) { closes++ }
                } }
                onAllNodes(hasSetTextAction())[0].performTextReplacement("1234")
                onAllNodes(hasSetTextAction())[1].performTextReplacement("Please top up")
                for (size in sizes) {
                    runOnIdle { pane = size }
                    onAllNodes(hasSetTextAction())[0].assertTextContains("1234")
                    onAllNodes(hasSetTextAction())[1].assertTextContains("Please top up")
                    onNodeWithText("Send request").reachable().performTouchInput { click() }
                    onNodeWithText("You already have a pending request.").reachable()
                    if (size == 300 to 240) snapshot("credits-request-error-300-240")
                    onNodeWithText("Cancel").reachable().performTouchInput { click() }
                }
                assertEquals(sizes.size, closes)
                assertEquals(sizes.size, f.backend.calls.count { it.first == "arcade_request_credits" })
            }
        } finally { f.close() }
    }

    @Test
    fun insufficientCreditsAndAdminQueueKeepActionsReachable() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                var pane by mutableStateOf(1000 to 700)
                var admin by mutableStateOf(false)
                var closes = 0
                var requests = 0
                setContent { TestPane(pane) {
                    if (admin) AdminCreditsOverlay(f.credits) { closes++ }
                    else InsufficientCreditsCard(BlockedRun("2048", 0, 100), f.credits, { requests++ }, { closes++ })
                } }
                for (size in sizes) {
                    runOnIdle { pane = size }
                    onNodeWithText("Request credits").reachable().performTouchInput { click() }
                    onNodeWithText("Not now").reachable().performTouchInput { click() }
                }
                assertEquals(sizes.size, requests)
                runOnIdle { f.pending = true; runBlocking { f.credits.refresh() } }
                onNodeWithText("Close").reachable().performTouchInput { click() }
                runOnIdle { admin = true }
                for (size in sizes) {
                    runOnIdle { pane = size }
                    onAllNodesWithText("Approve").onLast().reachable().performTouchInput { click() }
                    onAllNodesWithText("Deny").onLast().reachable().performTouchInput { click() }
                    if (size == 300 to 240) snapshot("credits-admin-300-240")
                    onNodeWithContentDescription("Close").reachable().performTouchInput { click() }
                }
                assertEquals(2 * sizes.size, f.backend.calls.count { it.first == "arcade_admin_resolve" })
            }
        } finally { f.close() }
    }

    @Test
    fun pokerUnavailableFallbackAndBackFitWithoutAnEmbeddedBrowser() {
        val f = Fixture()
        try {
            runDesktopComposeUiTest(1000, 700) {
                var pane by mutableStateOf(1000 to 700)
                val vm = PokerViewModel(f.scope, f.services)
                var backs = 0
                setContent { TestPane(pane) {
                    PokerScreen(vm) { backs++ }
                } }
                for (size in sizes) {
                    runOnIdle { pane = size }
                    assertEquals(PokerViewModel.Phase.UNAVAILABLE, vm.phase)
                    onNodeWithText(POKER_URL).reachable()
                    if (size == 300 to 240) snapshot("poker-fallback-300-240")
                    onNodeWithContentDescription("Back to games").reachable().performTouchInput { click() }
                }
                assertEquals(sizes.size, backs)
            }
        } finally { f.close() }
    }

    private fun DesktopComposeUiTest.snapshot(name: String) {
        val file = File("build/responsive-screenshots/expanded-$name.png")
        file.parentFile.mkdirs()
        val bitmap = onNodeWithTag("pane").captureToImage().asSkiaBitmap()
        file.writeBytes(Image.makeFromBitmap(bitmap).encodeToData()!!.bytes)
    }

    @Composable
    private fun TestPane(pane: Pair<Int, Int>, content: @Composable BoxScope.() -> Unit) {
        val fontScale = if (pane == 300 to 500) 1.5f else 1f
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
            Box(Modifier.requiredSize(pane.first.dp, pane.second.dp).testTag("pane"), content = content)
        }
    }

    private fun SemanticsNodeInteraction.reachable(): SemanticsNodeInteraction {
        val clipped = runCatching { getBoundsInRoot() }.getOrNull()
        val full = runCatching { getUnclippedBoundsInRoot() }.getOrNull()
        if (runCatching { assertIsDisplayed() }.isFailure || clipped != full) performScrollTo()
        assertIsDisplayed()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue(bounds.right > bounds.left && bounds.bottom > bounds.top)
        val layouts = mutableListOf<TextLayoutResult>()
        if (fetchSemanticsNode().config.contains(SemanticsActions.GetTextLayoutResult)) {
            performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            for (layout in layouts) {
                assertTrue(layout.multiParagraph.height <= layout.size.height + 1f,
                    "Text height must fit: ${layout.layoutInput.text}")
                for (line in 0 until layout.lineCount) {
                    assertTrue(layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f,
                        "Text glyphs must fit: ${layout.layoutInput.text}")
                }
                if (layout.layoutInput.text.length <= 20) assertTrue(layout.lineCount <= 2,
                    "Short action labels must not become vertical text: ${layout.layoutInput.text}")
            }
        }
        return this
    }
}
