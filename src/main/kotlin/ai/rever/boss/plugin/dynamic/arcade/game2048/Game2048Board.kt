package ai.rever.boss.plugin.dynamic.arcade.game2048

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The 4x4 board: static cells underneath, animated tiles on top, veil overlay last.
 * Sizing follows the original CSS: gap = boardSize * 11/430, cell = (board - 5*gap)/4.
 */
@Composable
internal fun Game2048Board(
    state: Game2048ViewModel.UiState,
    viewModel: Game2048ViewModel,
    boardSize: Dp,
) {
    val gap = boardSize * (11f / 430f)
    val cell = (boardSize - gap * 5) / 4

    Box(
        Modifier
            .size(boardSize)
            .clip(RoundedCornerShape(18.dp))
            .background(ArcadeColors.Frame),
    ) {
        repeat(Game2048Logic.SIZE) { r ->
            repeat(Game2048Logic.SIZE) { c ->
                Box(
                    Modifier
                        .offset(x = gap + (cell + gap) * c, y = gap + (cell + gap) * r)
                        .size(cell)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ArcadeColors.Cell),
                )
            }
        }

        state.tiles.forEach { tile ->
            key(tile.id) {
                TileView(tile = tile, cell = cell, gap = gap, fx = state.fx)
            }
        }

        Game2048Veil(state = state, viewModel = viewModel)
    }
}

@Composable
private fun TileView(
    tile: TileData,
    cell: Dp,
    gap: Dp,
    fx: Game2048ViewModel.MoveFx,
) {
    // Slide: 100 ms ease, same as the CSS `transition: transform .1s`.
    val x by animateDpAsState(gap + (cell + gap) * tile.col, tween(100))
    val y by animateDpAsState(gap + (cell + gap) * tile.row, tween(100))

    // Spawn: rise from 40% scale after a 30 ms beat (CSS `rise` keyframes).
    val isNew = remember { tile.id in fx.spawnedIds }
    val appear = remember { Animatable(if (isNew) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (isNew) {
            delay(30)
            appear.animateTo(1f, tween(160))
        }
    }

    // Merge: pop to 122% and back (CSS `pop` keyframes), retriggered per move.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(fx.seq) {
        if (tile.id in fx.mergedIds) {
            pop.snapTo(1f)
            pop.animateTo(1.22f, tween(75))
            pop.animateTo(1f, tween(95))
        }
    }

    Box(
        Modifier
            .offset(x = x, y = y)
            .size(cell)
            .graphicsLayer {
                val scale = pop.value * (0.4f + 0.6f * appear.value)
                scaleX = scale
                scaleY = scale
                alpha = appear.value
            }
            .clip(RoundedCornerShape(12.dp))
            .background(ArcadeColors.tileBackground(tile.value)),
        contentAlignment = Alignment.Center,
    ) {
        val digits = tile.value.toString().length
        val sizeFactor = when {
            digits <= 2 -> 0.42f
            digits == 3 -> 0.36f
            digits == 4 -> 0.30f
            else -> 0.24f
        }
        Text(
            text = tile.value.toString(),
            color = ArcadeColors.tileForeground(tile.value),
            fontSize = with(LocalDensity.current) { (cell * sizeFactor).toSp() },
            fontWeight = FontWeight.ExtraBold,
        )
    }
}
