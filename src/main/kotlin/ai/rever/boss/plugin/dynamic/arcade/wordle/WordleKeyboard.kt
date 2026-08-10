package ai.rever.boss.plugin.dynamic.arcade.wordle

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KEY_ROWS = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")

/**
 * On-screen QWERTY keyboard, colored by the best-known verdict per letter.
 * Clicks feed the same entry points as physical keys.
 */
@Composable
fun WordleKeyboard(
    keyStates: Map<Char, LetterState>,
    onKey: (Char) -> Unit,
    onEnter: () -> Unit,
    onBackspace: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KEY_ROWS.forEachIndexed { rowIndex, letters ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (rowIndex == KEY_ROWS.lastIndex) {
                    WideKey("ENTER", onEnter)
                }
                for (letter in letters) {
                    LetterKey(letter, keyStates[letter], onKey)
                }
                if (rowIndex == KEY_ROWS.lastIndex) {
                    WideKey("⌫", onBackspace)
                }
            }
        }
    }
}

@Composable
private fun LetterKey(letter: Char, state: LetterState?, onKey: (Char) -> Unit) {
    // Keys recolor after the row finishes flipping, like the original.
    val background by animateColorAsState(
        targetValue = state?.let { WordleColors.of(it) } ?: ArcadeColors.Chip,
        animationSpec = tween(250, delayMillis = if (state != null) 1400 else 0),
    )
    Key(
        label = letter.toString(),
        background = background,
        foreground = if (state != null) Color.White else ArcadeColors.Ink,
        width = 32.dp,
    ) { onKey(letter) }
}

@Composable
private fun WideKey(label: String, onClick: () -> Unit) {
    Key(
        label = label,
        background = ArcadeColors.Frame,
        foreground = ArcadeColors.Ink,
        width = 52.dp,
        onClick = onClick,
    )
}

@Composable
private fun Key(
    label: String,
    background: Color,
    foreground: Color,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(42.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .plainClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = foreground,
        )
    }
}
