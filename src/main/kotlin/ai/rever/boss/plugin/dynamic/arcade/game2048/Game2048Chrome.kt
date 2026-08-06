package ai.rever.boss.plugin.dynamic.arcade.game2048

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import ai.rever.boss.plugin.dynamic.arcade.ArcadeGhostButton
import ai.rever.boss.plugin.dynamic.arcade.ArcadePrimaryButton
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun formatScore(value: Int): String = "%,d".format(value)

@Composable
internal fun Game2048Header(state: Game2048ViewModel.UiState, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .plainClickable(onBack)
                        .padding(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = "Back to games",
                        tint = ArcadeColors.InkSoft,
                    )
                }
                Spacer(Modifier.widthIn(min = 6.dp))
                Text(
                    "2048",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ArcadeColors.Ink,
                )
            }
            Text(
                "Join matching tiles. Get to 2048.",
                fontSize = 13.sp,
                color = ArcadeColors.InkSoft,
            )
        }
        ScoreChip(label = "SCORE", value = state.score, fx = state.fx)
        Spacer(Modifier.widthIn(min = 8.dp))
        ScoreChip(label = "BEST", value = state.best)
    }
}

@Composable
private fun ScoreChip(label: String, value: Int, fx: Game2048ViewModel.MoveFx? = null) {
    Box {
        Column(
            Modifier
                .shadow(6.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(ArcadeColors.Chip)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .widthIn(min = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = ArcadeColors.Muted,
            )
            Text(
                formatScore(value),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ArcadeColors.Ink,
            )
        }
        if (fx != null) {
            ScoreFloat(fx, Modifier.align(Alignment.TopCenter))
        }
    }
}

/** The "+N" that drifts up from the score chip after a merge. */
@Composable
private fun ScoreFloat(fx: Game2048ViewModel.MoveFx, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(fx.seq) {
        if (fx.gained > 0) {
            text = "+" + formatScore(fx.gained)
            progress.snapTo(0f)
            progress.animateTo(1f, tween(600, easing = LinearOutSlowInEasing))
            text = null
        }
    }
    text?.let {
        Text(
            it,
            modifier = modifier
                .offset(y = (10 - 28 * progress.value).dp)
                .graphicsLayer { alpha = 0.9f * (1f - progress.value) },
            color = ArcadeColors.Pink,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 14.sp,
        )
    }
}

@Composable
internal fun BoxScope.Game2048Veil(
    state: Game2048ViewModel.UiState,
    viewModel: Game2048ViewModel,
) {
    // Remember the last shown veil so the fade-out keeps its content.
    var lastVeil by remember { mutableStateOf(Game2048ViewModel.Veil.OVER) }
    state.veil?.let { lastVeil = it }

    AnimatedVisibility(
        visible = state.veil != null,
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(250)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFFAEEE0).copy(alpha = 0.92f))
                .plainClickable {},
            contentAlignment = Alignment.Center,
        ) {
            val isWin = lastVeil == Game2048ViewModel.Veil.WIN
            Column(
                Modifier
                    .shadow(12.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(ArcadeColors.Chip)
                    .padding(horizontal = 30.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (isWin) "You hit 2048" else "Game over",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ArcadeColors.Ink,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isWin) "Keep going for a higher tile?"
                    else "You scored ${formatScore(state.score)}.",
                    fontSize = 14.sp,
                    color = ArcadeColors.InkSoft,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArcadePrimaryButton(
                        text = if (isWin) "New game" else "Try again",
                        onClick = { viewModel.newGame() },
                    )
                    if (isWin) {
                        ArcadeGhostButton(text = "Keep going", onClick = { viewModel.keepGoing() })
                    }
                }
            }
        }
    }
}
