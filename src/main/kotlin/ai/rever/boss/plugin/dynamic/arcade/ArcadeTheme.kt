package ai.rever.boss.plugin.dynamic.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The warm pastel palette of the original HTML game, shared by every arcade screen.
 */
object ArcadeColors {
    val Bg1 = Color(0xFFFDF7EE)
    val Bg2 = Color(0xFFF6E7D4)
    val Ink = Color(0xFF4C3B2F)
    val InkSoft = Color(0xFF8A7360)
    val Muted = Color(0xFFAC917A)
    val Frame = Color(0xFFE7D5C1)
    val Cell = Color(0xFFF2E6D5)
    val Chip = Color(0xFFFFFDF8)
    val Pink = Color(0xFFE85C87)
    val PinkDeep = Color(0xFFD94A77)

    fun tileBackground(value: Int): Color = when (value) {
        2 -> Color(0xFFFBEFDC)
        4 -> Color(0xFFF9E3BC)
        8 -> Color(0xFFF6AE7E)
        16 -> Color(0xFFF59274)
        32 -> Color(0xFFF47B6B)
        64 -> Color(0xFFF05F63)
        128 -> Color(0xFFED5B8B)
        256 -> Color(0xFFE24680)
        512 -> Color(0xFFD13A79)
        1024 -> Color(0xFFB83271)
        2048 -> Color(0xFFA62C6C)
        else -> Color(0xFF6E2450)
    }

    fun tileForeground(value: Int): Color = when (value) {
        2 -> Color(0xFF9A7B5F)
        4 -> Color(0xFF8F6A34)
        else -> Color.White
    }
}

@Composable
fun ArcadeBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(listOf(ArcadeColors.Bg1, ArcadeColors.Bg2)),
            ),
    ) {
        content()
    }
}
