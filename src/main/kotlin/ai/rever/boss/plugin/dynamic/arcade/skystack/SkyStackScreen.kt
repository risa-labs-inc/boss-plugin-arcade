package ai.rever.boss.plugin.dynamic.arcade.skystack

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

@Composable
fun SkyStackScreen(
    viewModel: SkyStackViewModel,
    leaderboard: LeaderboardService,
    onBack: () -> Unit,
) {
    var showLeaderboard by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var frameTick by remember { mutableStateOf(0L) }
    val density = LocalDensity.current.density

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last != 0L) {
                    val dt = min(0.05f, (now - last) / 1_000_000_000f)
                    if (!showLeaderboard) viewModel.onFrame(dt)
                }
                last = now
                frameTick++
            }
        }
    }

    // Own keyboard focus as soon as the screen appears. Preview handling runs
    // before child buttons, so Space works immediately rather than only after
    // the first mouse click (the focus bug in the original HTML version).
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(showLeaderboard) {
        if (showLeaderboard) {
            viewModel.pauseIfPlaying()
        } else {
            focusRequester.requestFocus()
        }
    }

    fun activatePrimaryAction() {
        when (viewModel.phase) {
            SkyStackViewModel.Phase.MENU, SkyStackViewModel.Phase.OVER -> viewModel.start()
            SkyStackViewModel.Phase.PLAYING -> viewModel.drop()
            SkyStackViewModel.Phase.PAUSED -> viewModel.togglePause()
            SkyStackViewModel.Phase.REVEALING -> Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || showLeaderboard) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Spacebar, Key.Enter -> {
                        activatePrimaryAction()
                        true
                    }

                    Key.P, Key.Escape -> {
                        viewModel.togglePause()
                        true
                    }

                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(viewModel.phase) {
                detectTapGestures {
                    if (viewModel.phase == SkyStackViewModel.Phase.PLAYING) viewModel.drop()
                    focusRequester.requestFocus()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            frameTick
            drawSkyStack(
                engine = viewModel.engine,
                density = density,
                width = size.width / density,
                height = size.height / density,
                showMovingBlock = viewModel.phase == SkyStackViewModel.Phase.PLAYING ||
                    viewModel.phase == SkyStackViewModel.Phase.PAUSED,
            )
        }

        SkyStackHud(
            viewModel = viewModel,
            onBack = onBack,
            onLeaderboard = { showLeaderboard = true },
        )

        when (viewModel.phase) {
            SkyStackViewModel.Phase.MENU -> SkyStackStartCard(viewModel.best, viewModel::start)
            SkyStackViewModel.Phase.PAUSED ->
                if (!showLeaderboard) SkyStackPauseCard(viewModel::togglePause)
            SkyStackViewModel.Phase.OVER -> SkyStackOverCard(
                score = viewModel.score,
                best = viewModel.best,
                isNewBest = viewModel.isNewBest,
                onRetry = viewModel::start,
                onLeaderboard = { showLeaderboard = true },
            )

            SkyStackViewModel.Phase.PLAYING, SkyStackViewModel.Phase.REVEALING -> Unit
        }

        if (showLeaderboard) {
            LeaderboardOverlay(
                leaderboard = leaderboard,
                game = SkyStackViewModel.GAME,
                onClose = { showLeaderboard = false },
            )
        }
    }
}
