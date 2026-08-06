package ai.rever.boss.plugin.dynamic.arcade.game2048

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import ai.rever.boss.plugin.dynamic.arcade.ArcadeGhostButton
import ai.rever.boss.plugin.dynamic.arcade.ArcadePrimaryButton
import ai.rever.boss.plugin.dynamic.arcade.LeaderboardOverlay
import ai.rever.boss.plugin.dynamic.arcade.LeaderboardService
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The full 2048 screen: header, controls, board, hint — centered and sized like
 * the original page (board caps at 430dp). Keyboard is the primary input:
 * arrows or WASD, captured on a focused root box.
 */
@Composable
fun Game2048Screen(
    viewModel: Game2048ViewModel,
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
                if (showLeaderboard) return@onPreviewKeyEvent false
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

        val boardSize = minOf(maxWidth - 48.dp, maxHeight - 210.dp, 430.dp)
            .coerceAtLeast(240.dp)

        Column(
            modifier = Modifier.width(boardSize).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Game2048Header(state = state, onBack = onBack)
            Spacer(Modifier.height(14.dp))
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
                ArcadeGhostButton(
                    text = "Undo",
                    onClick = { viewModel.undo() },
                    enabled = state.canUndo,
                )
                ArcadePrimaryButton(text = "New game", onClick = { viewModel.newGame() })
            }
            Spacer(Modifier.height(12.dp))
            Game2048Board(state = state, viewModel = viewModel, boardSize = boardSize)
            Spacer(Modifier.height(14.dp))
            Text(
                "Move with arrow keys or WASD.",
                fontSize = 12.sp,
                color = ArcadeColors.Muted,
            )
        }

        if (showLeaderboard) {
            LeaderboardOverlay(
                leaderboard = leaderboard,
                game = Game2048ViewModel.GAME,
                onClose = { showLeaderboard = false },
            )
        }
    }
}
