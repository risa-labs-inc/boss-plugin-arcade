package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipService
import ai.rever.boss.plugin.dynamic.arcade.mirrordash.MirrorDashEngine
import ai.rever.boss.plugin.dynamic.arcade.mirrordash.MirrorDashScreen
import ai.rever.boss.plugin.dynamic.arcade.mirrordash.MirrorDashViewModel
import ai.rever.boss.plugin.dynamic.arcade.skystack.SkyStackEngine
import ai.rever.boss.plugin.dynamic.arcade.skystack.SkyStackScreen
import ai.rever.boss.plugin.dynamic.arcade.skystack.SkyStackViewModel
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipScreen
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipViewModel
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048Screen
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048ViewModel
import ai.rever.boss.plugin.dynamic.arcade.typingsprint.TypingSprintScreen
import ai.rever.boss.plugin.dynamic.arcade.typingsprint.TypingSprintViewModel
import ai.rever.boss.plugin.dynamic.arcade.wordle.WordleScreen
import ai.rever.boss.plugin.dynamic.arcade.wordle.WordleViewModel
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertTrue

/** The surrounding host area must stay untouched, not merely the cropped game screenshot. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ArcadePaneClippingTest {
    @Test fun mirrorDashOffscreenObjectsCannotPaintOverHostChrome() = checkContainment(true)
    @Test fun skyStackOffscreenSlicesCannotPaintOverAdjacentPanes() = checkContainment(false)

    @Test fun sharedBoundaryContains2048AnimationAndOverlays() = checkSharedBoundary("2048")
    @Test fun sharedBoundaryContainsWordleAnimationAndOverlays() = checkSharedBoundary("Wordle")
    @Test fun sharedBoundaryContainsTypingResultsAndOverlays() = checkSharedBoundary("Typing")
    @Test fun sharedBoundaryContainsBattleshipPickerAndOverlays() = checkSharedBoundary("Battleship")
    @Test fun sharedBoundaryContainsMirrorDashAndOverlays() = checkSharedBoundary("Mirror")
    @Test fun sharedBoundaryContainsSkyStackAndOverlays() = checkSharedBoundary("Sky")

    private fun checkSharedBoundary(game: String) {
        for (density in listOf(1f, 2f)) {
            val scope = TestScope()
            val silentScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
            try {
                val storage = FakeStorage()
                storage.raw["save.2048.local"] =
                    """{"cells":[[1024,1024,0,0],[0,0,0,0],[0,0,0,0],[0,0,0,0]],"score":100,"won":false}"""
                val leaderboard = LeaderboardService(null, null)
                val credits = CreditsService(null, null) { scope }
                val services = ArcadeServices({ scope }, null, storage, leaderboard, credits,
                    BattleshipService(null, null), null, null, null)
                val tiles = Game2048ViewModel(scope, services)
                val wordle = WordleViewModel(scope, services)
                val typing = TypingSprintViewModel(scope, services)
                val battle = BattleshipViewModel(scope, services)
                val dash = MirrorDashViewModel(silentScope, services)
                val sky = SkyStackViewModel(silentScope, services)
                dash.engine.resize(300f, 240f)
                dash.engine.obstacles.add(MirrorDashEngine.Obstacle(0.35f, 0.16f, -20f, 60f))
                sky.engine.slices.add(SkyStackEngine.Slice(-1000f, 0f, 200f, 200f, 0f, 0f, 1f, 280f))
                scope.runCurrent()
                val surrounding = Color(0xFF123456)
                runDesktopComposeUiTest(800, 640) {
                    mainClock.autoAdvance = false
                    var creditsOpen by mutableStateOf(false)
                    var boundaryProbe by mutableStateOf(false)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density)) {
                            Box(Modifier.fillMaxSize().background(surrounding)) {
                                Box(Modifier.offset(32.dp, 32.dp).requiredSize(300.dp, 240.dp).testTag("shared-pane")) {
                                    ArcadeBackground {
                                        when (game) {
                                            "2048" -> Game2048Screen(tiles, leaderboard) {}
                                            "Wordle" -> WordleScreen(wordle, leaderboard) {}
                                            "Typing" -> TypingSprintScreen(typing, leaderboard) {}
                                            "Battleship" -> BattleshipScreen(battle) {}
                                            "Mirror" -> MirrorDashScreen(dash, leaderboard) {}
                                            "Sky" -> SkyStackScreen(sky, leaderboard) {}
                                        }
                                        if (creditsOpen) RequestCreditsDialog(credits) { creditsOpen = false }
                                        // Stress the shared contract independently of a particular animation frame:
                                        // a future child may intentionally paint beyond its layout rectangle.
                                        if (boundaryProbe) Box(Modifier.fillMaxSize().drawBehind {
                                            drawRect(Color.Magenta, Offset(-20f, -20f),
                                                Size(size.width + 40f, size.height + 40f))
                                        })
                                    }
                                }
                            }
                        }
                    }
                    mainClock.advanceTimeByFrame()
                    assertOutsideUntouched("shared-pane", surrounding, "$game initial density=$density")
                    runOnIdle {
                        when (game) {
                            "2048" -> assertTrue(tiles.move(0, -1))
                            "Wordle" -> { wordle.onKey('A'); wordle.onEnter() }
                            "Typing" -> { typing.onTyped("abc"); typing.onDisposed() }
                            "Battleship" -> battle.openOpponentPicker()
                        }
                        scope.runCurrent()
                    }
                    // Sample the slide/pop/shake frames, not only settled layouts.
                    for (step in listOf(32L, 80L, 80L, 400L)) {
                        runOnIdle { scope.advanceTimeBy(step); scope.runCurrent() }
                        mainClock.advanceTimeBy(step)
                        assertOutsideUntouched("shared-pane", surrounding, "$game transition density=$density step=$step")
                    }
                    if (game == "2048") assertTrue(tiles.state.value.veil == Game2048ViewModel.Veil.WIN)
                    if (game == "Typing") assertTrue(typing.phase == TypingSprintViewModel.Phase.DONE)
                    if (game == "Battleship") assertTrue(battle.showOpponentPicker)
                    runOnIdle { creditsOpen = true }
                    mainClock.advanceTimeByFrame()
                    assertOutsideUntouched("shared-pane", surrounding, "$game credits overlay density=$density")
                    runOnIdle { boundaryProbe = true }
                    mainClock.advanceTimeByFrame()
                    assertOutsideUntouched("shared-pane", surrounding, "$game oversized child density=$density")
                }
            } finally { scope.cancel(); silentScope.cancel() }
        }
    }

    private fun DesktopComposeUiTest.assertOutsideUntouched(tag: String, surrounding: Color, state: String) {
        val bounds = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val pixels = onRoot().captureToImage().toPixelMap()
        var outsideChanges = 0
        var insideChanges = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y].toArgb() != surrounding.toArgb()) {
                val inside = x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
                if (inside) insideChanges++ else outsideChanges++
            }
        }
        assertTrue(insideChanges > 100, "Actual content must render: $state")
        assertTrue(outsideChanges == 0, "$outsideChanges pixels escaped the pane: $state")
    }

    private fun checkContainment(mirror: Boolean) {
        for (density in listOf(1f, 2f)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val vmScope = CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined)
            try {
                val leaderboard = LeaderboardService(null, null)
                val credits = CreditsService(null, null) { scope }
                val services = ArcadeServices({ scope }, null, null, leaderboard, credits,
                    BattleshipService(null, null), null, null, null)
                val dash = MirrorDashViewModel(vmScope, services)
                val sky = SkyStackViewModel(vmScope, services)
                dash.engine.resize(320f / density, 240f / density)
                // Incoming gates legitimately start above the game viewport.
                dash.engine.obstacles.add(MirrorDashEngine.Obstacle(0.35f, 0.16f, -20f / density, 60f / density))
                // A falling fragment can legitimately leave the visible playfield.
                sky.engine.slices.add(SkyStackEngine.Slice(-1000f, 0f, 200f, 200f, 0f, 0f, 1f, 280f))
                val surrounding = Color(0xFF123456)
                runDesktopComposeUiTest(640, 480) {
                    mainClock.autoAdvance = false
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density)) {
                            Box(Modifier.fillMaxSize().background(surrounding)) {
                                Box(Modifier.offset((48f / density).dp, (48f / density).dp)
                                    .requiredSize((320f / density).dp, (240f / density).dp)
                                    .testTag("game-pane")) {
                                    if (mirror) MirrorDashScreen(dash, leaderboard) {}
                                    else SkyStackScreen(sky, leaderboard) {}
                                }
                            }
                        }
                    }
                    mainClock.advanceTimeByFrame()
                    val bounds = onNodeWithTag("game-pane").fetchSemanticsNode().boundsInRoot
                    val pixels = onRoot().captureToImage().toPixelMap()
                    var outsideChanges = 0
                    var insideChanges = 0
                    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                        if (pixels[x, y].toArgb() != surrounding.toArgb()) {
                            val inside = x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
                            if (inside) insideChanges++ else outsideChanges++
                        }
                    }
                    assertTrue(insideChanges > 100, "The actual game must have rendered")
                    assertTrue(outsideChanges == 0,
                        "Game painted $outsideChanges pixels outside its pane (mirror=$mirror, density=$density)")
                }
            } finally { scope.cancel(); vmScope.cancel() }
        }
    }
}
