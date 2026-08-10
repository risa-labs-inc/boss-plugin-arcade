package ai.rever.boss.plugin.dynamic.arcade.wordle

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import ai.rever.boss.plugin.dynamic.arcade.ArcadeGhostButton
import ai.rever.boss.plugin.dynamic.arcade.ArcadePrimaryButton
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.delay

@Composable
internal fun WordleHeader(state: WordleViewModel.UiState, onBack: () -> Unit) {
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
                    "Wordle",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ArcadeColors.Ink,
                )
            }
            Text(
                "Daily word #${state.puzzleNumber} — the whole team gets the same one.",
                fontSize = 13.sp,
                color = ArcadeColors.InkSoft,
            )
        }
        WordleChip(label = "TODAY", value = if (state.points > 0) "+${state.points}" else "—")
        Spacer(Modifier.widthIn(min = 8.dp))
        WordleChip(label = "BEST", value = if (state.best > 0) "${state.best}" else "—")
    }
}

@Composable
private fun WordleChip(label: String, value: String) {
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
            value,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ArcadeColors.Ink,
        )
    }
}

/** Transient "Not in word list" style toast, shown above the board. */
@Composable
internal fun WordleToast(message: String?, messageSeq: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(messageSeq) {
        if (messageSeq > 0) {
            visible = true
            delay(1600)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible && message != null,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(250)),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ArcadeColors.Ink)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                message ?: "",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Result card: outcome, points, share-grid copy, and the next-word countdown. */
@Composable
internal fun BoxScope.WordleVeil(
    state: WordleViewModel.UiState,
    viewModel: WordleViewModel,
    onShowLeaderboard: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.veil,
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
            val won = state.phase == WordleViewModel.Phase.WON
            val clipboard = LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .shadow(12.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(ArcadeColors.Chip)
                    .padding(horizontal = 30.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (won) winTitle(state.rows.size) else "Out of guesses",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ArcadeColors.Ink,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (won) {
                        "Solved in ${state.rows.size}/6 — +${state.points} " +
                            if (state.points == 1) "point." else "points."
                    } else {
                        "The word was ${state.solution ?: "?"}."
                    },
                    fontSize = 14.sp,
                    color = ArcadeColors.InkSoft,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                NextWordCountdown(viewModel)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArcadePrimaryButton(
                        text = if (copied) "Copied!" else "Copy result",
                        onClick = {
                            viewModel.shareText()?.let {
                                clipboard.setText(AnnotatedString(it))
                                copied = true
                            }
                        },
                    )
                    ArcadeGhostButton(text = "Leaderboard", onClick = onShowLeaderboard)
                    ArcadeGhostButton(text = "View board", onClick = { viewModel.dismissVeil() })
                }
            }
        }
    }
}

private fun winTitle(guesses: Int): String = when (guesses) {
    1 -> "Genius!"
    2 -> "Magnificent!"
    3 -> "Impressive!"
    4 -> "Splendid!"
    5 -> "Great!"
    else -> "Phew!"
}

/** Live HH:MM:SS to UTC midnight; the tick also rolls the board to the new day. */
@Composable
private fun NextWordCountdown(viewModel: WordleViewModel) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Instant.now()
            val midnight = now.atOffset(ZoneOffset.UTC).toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
            val left = java.time.Duration.between(now, midnight)
            text = "%02d:%02d:%02d".format(
                left.toHours(),
                left.toMinutesPart(),
                left.toSecondsPart(),
            )
            viewModel.rolloverIfNewDay()
            delay(1000)
        }
    }
    Text(
        "Next word in $text",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = ArcadeColors.Muted,
    )
}
