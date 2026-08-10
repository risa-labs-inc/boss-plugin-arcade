package ai.rever.boss.plugin.dynamic.arcade.typingsprint

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import ai.rever.boss.plugin.dynamic.arcade.ArcadeGhostButton
import ai.rever.boss.plugin.dynamic.arcade.ArcadePrimaryButton
import ai.rever.boss.plugin.dynamic.arcade.LeaderboardOverlay
import ai.rever.boss.plugin.dynamic.arcade.LeaderboardService
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 60 seconds, one passage after another. The invisible text field owns real
 * keyboard input (IME, repeat, backspace); the passage renders per-character
 * feedback on top.
 */
@Composable
fun TypingSprintScreen(
    viewModel: TypingSprintViewModel,
    leaderboard: LeaderboardService,
    onBack: () -> Unit,
) {
    var showLeaderboard by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(showLeaderboard, viewModel.phase) {
        if (!showLeaderboard) focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().plainClickable { focusRequester.requestFocus() },
        contentAlignment = Alignment.Center,
    ) {
        // Real input lives here, invisibly; selection is pinned to the end.
        BasicTextField(
            value = TextFieldValue(viewModel.typed, TextRange(viewModel.typed.length)),
            onValueChange = { viewModel.onTyped(it.text) },
            modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focusRequester),
        )

        Column(
            modifier = Modifier.widthIn(max = 640.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).plainClickable(onBack).padding(4.dp)) {
                    Icon(Icons.Outlined.ArrowBack, "Back to games", tint = ArcadeColors.InkSoft)
                }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(
                        "Typing Sprint",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ArcadeColors.Ink,
                    )
                    Text(
                        "Sixty seconds. Type fast, type clean.",
                        fontSize = 13.sp,
                        color = ArcadeColors.InkSoft,
                    )
                }
                StatChip("TIME", "${(viewModel.timeLeftMs + 999) / 1000}s")
                StatChip("WPM", "${viewModel.wpm}")
                StatChip("ACC", "${viewModel.accuracy}%")
                StatChip("BEST", "${viewModel.best}")
            }

            Spacer(Modifier.height(20.dp))

            when (viewModel.phase) {
                TypingSprintViewModel.Phase.DONE -> ResultsPanel(
                    viewModel = viewModel,
                    onAgain = {
                        viewModel.restart()
                        focusRequester.requestFocus()
                    },
                    onLeaderboard = { showLeaderboard = true },
                )
                else -> {
                    PassageCard(viewModel)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArcadeGhostButton("Leaderboard", onClick = { showLeaderboard = true })
                        ArcadeGhostButton("Restart", onClick = {
                            viewModel.restart()
                            focusRequester.requestFocus()
                        })
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (viewModel.phase == TypingSprintViewModel.Phase.IDLE)
                            "The clock starts on your first keystroke."
                        else "Finish a passage and the next one loads.",
                        fontSize = 12.sp,
                        color = ArcadeColors.Muted,
                    )
                }
            }
        }

        if (showLeaderboard) {
            LeaderboardOverlay(
                leaderboard = leaderboard,
                game = TypingSprintViewModel.GAME,
                onClose = { showLeaderboard = false },
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .padding(start = 8.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(ArcadeColors.Chip)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ArcadeColors.Muted, letterSpacing = 1.sp)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = ArcadeColors.Ink)
    }
}

@Composable
private fun PassageCard(viewModel: TypingSprintViewModel) {
    val passage = viewModel.passage
    val typed = viewModel.typed
    val text = buildAnnotatedString {
        passage.forEachIndexed { i, c ->
            when {
                i < typed.length && typed[i] == c -> pushStyle(SpanStyle(color = ArcadeColors.Ink))
                i < typed.length -> pushStyle(
                    SpanStyle(
                        color = ArcadeColors.PinkDeep,
                        background = ArcadeColors.PinkDeep.copy(alpha = 0.14f),
                    ),
                )
                i == typed.length -> pushStyle(
                    SpanStyle(color = ArcadeColors.InkSoft, textDecoration = TextDecoration.Underline),
                )
                else -> pushStyle(SpanStyle(color = ArcadeColors.Muted))
            }
            append(c)
            pop()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(ArcadeColors.Chip)
            .padding(24.dp),
    ) {
        Text(
            text,
            fontSize = 18.sp,
            lineHeight = 30.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ResultsPanel(
    viewModel: TypingSprintViewModel,
    onAgain: () -> Unit,
    onLeaderboard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .shadow(10.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(ArcadeColors.Chip)
            .padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Time!", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = ArcadeColors.Ink)
        Spacer(Modifier.height(10.dp))
        Text("${viewModel.score}", fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, color = ArcadeColors.Pink)
        Text("SCORE = WPM x ACCURACY", fontSize = 9.sp, letterSpacing = 1.2.sp, color = ArcadeColors.Muted)
        Spacer(Modifier.height(12.dp))
        Text(
            "${viewModel.wpm} WPM at ${viewModel.accuracy}% accuracy" +
                if (viewModel.score >= viewModel.best) " — new best!" else "",
            fontSize = 14.sp,
            color = ArcadeColors.InkSoft,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArcadePrimaryButton("Go again", onClick = onAgain)
            ArcadeGhostButton("Leaderboard", onClick = onLeaderboard)
        }
    }
}
