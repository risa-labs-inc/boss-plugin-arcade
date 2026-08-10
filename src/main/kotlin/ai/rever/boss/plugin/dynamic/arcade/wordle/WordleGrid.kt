package ai.rever.boss.plugin.dynamic.arcade.wordle

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Wordle verdict colors, kept close to the original inside the pastel theme. */
internal object WordleColors {
    val Correct = Color(0xFF6AAA64)
    val Present = Color(0xFFC9B458)
    val Absent = Color(0xFF9C8F80)

    fun of(state: LetterState): Color = when (state) {
        LetterState.CORRECT -> Correct
        LetterState.PRESENT -> Present
        LetterState.ABSENT -> Absent
    }
}

/**
 * The 6x5 board. Committed rows flip tile by tile (250ms stagger, 400ms flip,
 * the original's cadence); the active row shakes when a guess is rejected.
 */
@Composable
fun WordleGrid(state: WordleViewModel.UiState, tileSize: Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (rowIndex in 0 until WordleLogic.MAX_GUESSES) {
            val committed = state.rows.getOrNull(rowIndex)
            when {
                committed != null -> key(rowIndex) {
                    RevealedRow(
                        row = committed,
                        // Only a row landing while we watch animates; a board
                        // restored from storage (revealSeq == 0) shows settled.
                        animate = state.revealSeq > 0 && rowIndex == state.rows.lastIndex,
                        tileSize = tileSize,
                    )
                }
                rowIndex == state.rows.size -> ActiveRow(
                    letters = state.current,
                    shakeSeq = state.shakeSeq,
                    tileSize = tileSize,
                )
                else -> EmptyRow(tileSize)
            }
        }
    }
}

@Composable
private fun RevealedRow(row: GuessRow, animate: Boolean, tileSize: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until WordleLogic.WORD_LENGTH) {
            val progress = remember { Animatable(if (animate) 0f else 1f) }
            LaunchedEffect(Unit) {
                if (animate && progress.value < 1f) {
                    delay(250L * i)
                    progress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
                }
            }
            // Flip as a vertical squash: colors swap at the halfway point.
            val revealed = progress.value >= 0.5f
            Tile(
                letter = row.word[i],
                background = if (revealed) WordleColors.of(row.states[i]) else ArcadeColors.Cell,
                foreground = if (revealed) Color.White else ArcadeColors.Ink,
                border = if (revealed) Color.Transparent else ArcadeColors.Muted,
                tileSize = tileSize,
                modifier = Modifier.graphicsLayer {
                    scaleY = abs(1f - 2f * progress.value).coerceAtLeast(0.01f)
                },
            )
        }
    }
}

@Composable
private fun ActiveRow(letters: String, shakeSeq: Int, tileSize: Dp) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeSeq) {
        if (shakeSeq > 0) {
            shake.snapTo(0f)
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = 400
                    for ((step, x) in listOf(-8f, 8f, -6f, 6f, -3f, 0f).withIndex()) {
                        x at (step + 1) * 65
                    }
                },
            )
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.graphicsLayer { translationX = shake.value },
    ) {
        for (i in 0 until WordleLogic.WORD_LENGTH) {
            val letter = letters.getOrNull(i)
            var popped by remember { mutableStateOf(false) }
            LaunchedEffect(letter) {
                if (letter != null) {
                    popped = true
                    delay(90)
                    popped = false
                }
            }
            Tile(
                letter = letter,
                background = ArcadeColors.Cell,
                foreground = ArcadeColors.Ink,
                border = if (letter != null) ArcadeColors.Muted else ArcadeColors.Frame,
                tileSize = tileSize,
                modifier = Modifier.graphicsLayer {
                    val scale = if (popped) 1.08f else 1f
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }
}

@Composable
private fun EmptyRow(tileSize: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(WordleLogic.WORD_LENGTH) {
            Tile(
                letter = null,
                background = ArcadeColors.Cell.copy(alpha = 0.55f),
                foreground = ArcadeColors.Ink,
                border = ArcadeColors.Frame,
                tileSize = tileSize,
            )
        }
    }
}

@Composable
private fun Tile(
    letter: Char?,
    background: Color,
    foreground: Color,
    border: Color,
    tileSize: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(tileSize)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(2.dp, border, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (letter != null) {
            Text(
                letter.toString(),
                fontSize = (tileSize.value * 0.48f).sp,
                fontWeight = FontWeight.ExtraBold,
                color = foreground,
            )
        }
    }
}
