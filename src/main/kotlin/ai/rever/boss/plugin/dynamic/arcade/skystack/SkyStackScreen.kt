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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
fun SkyStackScreen(
    viewModel: SkyStackViewModel,
    leaderboard: LeaderboardService,
    onBack: () -> Unit,
) {
    var showLeaderboard by remember { mutableStateOf(false) }
    var showTowerOverview by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val startButtonFocusRequester = remember { FocusRequester() }
    val screenScope = rememberCoroutineScope()
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

    // BOSS can attach the plugin content a few frames after this composable is
    // created. Retry focus after attachment, and focus the actual Start button
    // while on the menu so Space activates it even before any pointer input.
    LaunchedEffect(viewModel.phase, showLeaderboard, showTowerOverview) {
        if (showLeaderboard) {
            viewModel.pauseIfPlaying()
            return@LaunchedEffect
        }
        if (showTowerOverview) return@LaunchedEffect
        repeat(20) {
            delay(100)
            if (viewModel.phase == SkyStackViewModel.Phase.MENU) {
                startButtonFocusRequester.requestFocus()
            } else {
                focusRequester.requestFocus()
            }
        }
    }

    LaunchedEffect(viewModel.phase) {
        if (viewModel.phase != SkyStackViewModel.Phase.OVER) {
            showTowerOverview = false
            exportMessage = null
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
                if (event.type != KeyEventType.KeyDown || showLeaderboard || showTowerOverview) {
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
            .pointerInput(viewModel.phase, showTowerOverview) {
                detectTapGestures {
                    if (!showTowerOverview && viewModel.phase == SkyStackViewModel.Phase.PLAYING) {
                        viewModel.drop()
                    }
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
                showFullTower = showTowerOverview,
            )
        }

        if (!showTowerOverview) {
            SkyStackHud(
                viewModel = viewModel,
                onBack = onBack,
                onLeaderboard = { showLeaderboard = true },
            )
        }

        when {
            showTowerOverview -> SkyStackTowerOverviewControls(
                score = viewModel.score,
                exportMessage = exportMessage,
                onBack = { showTowerOverview = false },
                onExport = {
                    exportMessage = "Saving tower…"
                    screenScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { SkyStackTowerExport.save(viewModel.engine) }
                        }
                        exportMessage = result.fold(
                            onSuccess = { "Saved to ${it.toAbsolutePath()}" },
                            onFailure = { "Could not save tower: ${it.message ?: "unknown error"}" },
                        )
                    }
                },
            )

            viewModel.phase == SkyStackViewModel.Phase.MENU -> SkyStackStartCard(
                best = viewModel.best,
                startButtonFocusRequester = startButtonFocusRequester,
                onStart = viewModel::start,
            )
            viewModel.phase == SkyStackViewModel.Phase.PAUSED ->
                if (!showLeaderboard) SkyStackPauseCard(viewModel::togglePause)
            viewModel.phase == SkyStackViewModel.Phase.OVER -> SkyStackOverCard(
                score = viewModel.score,
                best = viewModel.best,
                isNewBest = viewModel.isNewBest,
                onRetry = viewModel::start,
                onViewTower = {
                    exportMessage = null
                    showTowerOverview = true
                },
                onLeaderboard = { showLeaderboard = true },
            )

            else -> Unit
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
