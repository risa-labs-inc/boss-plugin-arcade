package ai.rever.boss.plugin.dynamic.arcade.game2048

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import ai.rever.boss.plugin.dynamic.arcade.ArcadeGhostButton
import ai.rever.boss.plugin.dynamic.arcade.ArcadePrimaryButton
import ai.rever.boss.plugin.dynamic.arcade.LeaderboardOverlay
import ai.rever.boss.plugin.dynamic.arcade.LeaderboardService
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The full 2048 screen: header, controls, board, hint — centered and sized like
 * the original page (board caps at 430dp). Keyboard is the primary input:
 * arrows or WASD, captured on a focused root box.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Game2048Screen(
    viewModel: Game2048ViewModel,
    leaderboard: LeaderboardService,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showLeaderboard by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Short panes scroll instead of constraining a board whose tiles still
        // use the larger requested geometry. Width is always bounded by the pane.
        val sideBySide = maxWidth >= 600.dp && maxHeight >= 224.dp && maxHeight < 420.dp
        val boardSize = if (sideBySide) {
            minOf(maxHeight - 24.dp, maxWidth - 288.dp, 430.dp)
        } else {
            minOf(
                (maxWidth - 24.dp).coerceAtLeast(0.dp),
                (maxHeight - 210.dp).coerceAtLeast(240.dp),
                430.dp,
            )
        }
        val compact = maxWidth < 420.dp || maxHeight < 420.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (showLeaderboard || boardSize < 200.dp) return@onPreviewKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) {
                        return@onPreviewKeyEvent false
                    }
                    val dir = when (event.key) {
                        Key.DirectionUp, Key.W -> -1 to 0
                        Key.DirectionDown, Key.S -> 1 to 0
                        Key.DirectionLeft, Key.A -> 0 to -1
                        Key.DirectionRight, Key.D -> 0 to 1
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    viewModel.move(dir.first, dir.second)
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                .plainClickable { focusRequester.requestFocus() },
            contentAlignment = Alignment.Center,
        ) {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            // Re-grab focus whenever an overlay goes away so keys keep working.
            LaunchedEffect(showLeaderboard, state.veil) {
                if (!showLeaderboard && state.veil == null) focusRequester.requestFocus()
            }

            val controls: @Composable () -> Unit = {
                Game2048Header(state = state, onBack = onBack, compact = compact)
                if (boardSize < 200.dp) {
                    Text("Enlarge this pane to play 2048.", color = ArcadeColors.InkSoft)
                } else {
                    Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ArcadeGhostButton(text = "Leaderboard", onClick = { showLeaderboard = true })
                        ArcadeGhostButton(
                            text = "Undo",
                            onClick = { viewModel.undo() },
                            enabled = state.canUndo,
                        )
                        ArcadePrimaryButton(text = "New game", onClick = { viewModel.newGame() })
                    }
                }
            }
            val hint: @Composable () -> Unit = {
                Text("Move with arrow keys or WASD.", fontSize = 12.sp, color = ArcadeColors.Muted)
            }

            if (sideBySide) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.width(240.dp).fillMaxHeight().verticalScroll(scrollState),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        controls()
                        Spacer(Modifier.height(8.dp))
                        hint()
                    }
                    Game2048Board(state = state, boardSize = boardSize)
                }
            } else {
                Column(
                    modifier = Modifier.width(boardSize).fillMaxHeight()
                        .verticalScroll(scrollState).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    controls()
                    if (boardSize >= 200.dp) {
                        Spacer(Modifier.height(12.dp))
                        Game2048Board(state = state, boardSize = boardSize)
                        Spacer(Modifier.height(14.dp))
                        hint()
                    }
                }
            }

            VerticalScrollbar(
                rememberScrollbarAdapter(scrollState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = LocalScrollbarStyle.current.copy(
                    unhoverColor = ArcadeColors.Muted.copy(alpha = 0.5f),
                    hoverColor = ArcadeColors.InkSoft,
                ),
            )

            // End-of-run actions belong to the visible pane, not the scrolled board.
            Game2048Veil(state = state, viewModel = viewModel)

            if (showLeaderboard) {
                LeaderboardOverlay(
                    leaderboard = leaderboard,
                    game = Game2048ViewModel.GAME,
                    onClose = { showLeaderboard = false },
                )
            }
        }
    }
}
