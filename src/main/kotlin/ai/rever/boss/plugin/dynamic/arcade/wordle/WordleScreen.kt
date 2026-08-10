package ai.rever.boss.plugin.dynamic.arcade.wordle

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import ai.rever.boss.plugin.dynamic.arcade.ArcadeGhostButton
import ai.rever.boss.plugin.dynamic.arcade.LeaderboardOverlay
import ai.rever.boss.plugin.dynamic.arcade.LeaderboardService
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The full Wordle screen: header, controls, 6x5 grid, toast, keyboard —
 * physical keys are the primary input, captured on a focused root box.
 */
@Composable
fun WordleScreen(
    viewModel: WordleViewModel,
    leaderboard: LeaderboardService,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showLeaderboard by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (showLeaderboard || state.veil) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Enter -> {
                        viewModel.onEnter()
                        true
                    }
                    Key.Backspace -> {
                        viewModel.onBackspace()
                        true
                    }
                    else -> {
                        val letter = event.utf16CodePoint.toChar().uppercaseChar()
                        if (letter in 'A'..'Z') {
                            viewModel.onKey(letter)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .plainClickable { focusRequester.requestFocus() },
        contentAlignment = Alignment.Center,
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        // Re-grab focus whenever an overlay goes away so keys keep working.
        LaunchedEffect(showLeaderboard, state.veil) {
            if (!showLeaderboard && !state.veil) focusRequester.requestFocus()
        }
        // Catch the UTC-midnight rollover even while the result card is closed.
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                viewModel.rolloverIfNewDay()
            }
        }

        val contentWidth = minOf(maxWidth - 40.dp, 430.dp).coerceAtLeast(240.dp)
        val tileSize = minOf(
            (contentWidth - 24.dp) / 5,
            (maxHeight - 340.dp) / 6,
            56.dp,
        ).coerceAtLeast(36.dp)
        val keyWidth = ((contentWidth - 60.dp) / 10).coerceIn(24.dp, 34.dp)

        // Fresh board: lead with the how-to card; the "?" button toggles it back.
        var helpOverride by remember { mutableStateOf<Boolean?>(null) }
        val showHelp = helpOverride
            ?: (state.rows.isEmpty() && state.phase == WordleViewModel.Phase.PLAYING)

        // Scrollable so short tabs never clip the keyboard or overlap sections.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.width(contentWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WordleHeader(state = state, onBack = onBack)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ArcadeGhostButton(text = "Leaderboard", onClick = { showLeaderboard = true })
                    Icon(
                        Icons.Outlined.EmojiEvents,
                        contentDescription = null,
                        tint = ArcadeColors.Muted,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.phase != WordleViewModel.Phase.PLAYING && !state.veil) {
                        ArcadeGhostButton(text = "Result", onClick = { viewModel.showVeil() })
                    }
                    ArcadeGhostButton(
                        text = "?",
                        onClick = { helpOverride = !showHelp },
                    )
                }
                if (showHelp) {
                    Spacer(Modifier.height(12.dp))
                    WordleHelpCard()
                }
                Spacer(Modifier.height(12.dp))
                Box {
                    WordleGrid(state = state, tileSize = tileSize)
                    WordleToast(
                        message = state.message,
                        messageSeq = state.messageSeq,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                Spacer(Modifier.height(16.dp))
                WordleKeyboard(
                    keyStates = WordleLogic.keyStates(state.rows),
                    onKey = viewModel::onKey,
                    onEnter = viewModel::onEnter,
                    onBackspace = viewModel::onBackspace,
                    keyWidth = keyWidth,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Type your guess and press Enter. Fewer guesses, more points.",
                    fontSize = 12.sp,
                    color = ArcadeColors.Muted,
                )
            }
        }

        WordleVeil(
            state = state,
            viewModel = viewModel,
            onShowLeaderboard = {
                viewModel.dismissVeil()
                showLeaderboard = true
            },
        )

        if (showLeaderboard) {
            LeaderboardOverlay(
                leaderboard = leaderboard,
                game = WordleViewModel.GAME,
                onClose = { showLeaderboard = false },
            )
        }
    }
}
