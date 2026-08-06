package ai.rever.boss.plugin.dynamic.arcade.mirrordash

import ai.rever.boss.plugin.dynamic.arcade.LeaderboardOverlay
import ai.rever.boss.plugin.dynamic.arcade.LeaderboardService
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.min

/**
 * Mirror Dash: full-bleed canvas simulation driven by withFrameNanos, with the
 * HUD and overlay cards composed on top. One input everywhere: tap/space
 * reverses, P/Esc pauses, Enter starts.
 */
@Composable
fun MirrorDashScreen(
    viewModel: MirrorDashViewModel,
    leaderboard: LeaderboardService,
    onBack: () -> Unit,
) {
    var showLeaderboard by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var frameTick by remember { mutableStateOf(0L) }
    val density = LocalDensity.current.density

    // Render/update loop. dt is clamped like the original (34 ms) so a hung
    // frame never teleports obstacles through the player.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) {
                    val dt = min(0.034f, (now - last) / 1_000_000_000f)
                    if (!showLeaderboard) viewModel.onFrame(dt)
                }
                last = now
                frameTick++
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(showLeaderboard) {
        if (showLeaderboard) viewModel.pauseIfPlaying() else focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || showLeaderboard) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Spacebar, Key.DirectionLeft, Key.DirectionRight -> {
                        viewModel.reverse(); true
                    }
                    Key.P, Key.Escape -> {
                        viewModel.togglePause(); true
                    }
                    Key.Enter -> {
                        if (viewModel.phase == MirrorDashViewModel.Phase.MENU ||
                            viewModel.phase == MirrorDashViewModel.Phase.OVER
                        ) {
                            viewModel.start(); true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures {
                    viewModel.reverse()
                    focusRequester.requestFocus()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            frameTick // reading the tick invalidates this draw every frame
            viewModel.engine.resize(size.width / density, size.height / density)
            drawMirrorDash(viewModel.engine, density)
        }

        MirrorDashHud(
            viewModel = viewModel,
            onBack = onBack,
            onLeaderboard = { showLeaderboard = true },
        )

        when (viewModel.phase) {
            MirrorDashViewModel.Phase.MENU -> MirrorDashStartCard(onStart = { viewModel.start() })
            MirrorDashViewModel.Phase.PAUSED ->
                if (!showLeaderboard) MirrorDashPauseCard(onResume = { viewModel.togglePause() })
            MirrorDashViewModel.Phase.OVER -> MirrorDashOverCard(
                score = viewModel.score,
                isNewBest = viewModel.isNewBest,
                onRetry = { viewModel.start() },
                onLeaderboard = { showLeaderboard = true },
            )
            MirrorDashViewModel.Phase.PLAYING -> Unit
        }

        if (showLeaderboard) {
            LeaderboardOverlay(
                leaderboard = leaderboard,
                game = MirrorDashViewModel.GAME,
                onClose = { showLeaderboard = false },
            )
        }
    }
}
