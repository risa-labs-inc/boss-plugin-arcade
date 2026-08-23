package ai.rever.boss.plugin.dynamic.arcade.poker

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Poker is a web app, not a Compose port: the screen is a header plus the
 * embedded browser. The table itself (and its own leaderboards) lives at
 * [POKER_URL]; when the host can't give us a browser, we say so and hand the
 * player the URL instead.
 */
@Composable
fun PokerScreen(
    viewModel: PokerViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.ensureBrowser() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).plainClickable(onBack).padding(4.dp)) {
                Icon(Icons.Outlined.ArrowBack, "Back to games", tint = ArcadeColors.InkSoft)
            }
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    "Poker",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ArcadeColors.Ink,
                )
                Text(
                    "No-Limit Hold'em with the team.",
                    fontSize = 12.sp,
                    color = ArcadeColors.InkSoft,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(ArcadeColors.Chip),
            contentAlignment = Alignment.Center,
        ) {
            when (viewModel.phase) {
                PokerViewModel.Phase.CREATING -> PokerLoading()
                PokerViewModel.Phase.UNAVAILABLE -> PokerUnavailableCard(viewModel.unavailableReason)
                PokerViewModel.Phase.READY -> {
                    val handle = viewModel.browser()
                    if (handle != null) {
                        handle.Content()
                    } else {
                        PokerUnavailableCard("The embedded browser went away.")
                    }
                }
            }
        }
    }
}

@Composable
private fun PokerLoading() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            color = ArcadeColors.Pink,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("Shuffling up and dealing…", fontSize = 12.sp, color = ArcadeColors.InkSoft)
    }
}

@Composable
private fun PokerUnavailableCard(message: String) {
    Column(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .shadow(8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(ArcadeColors.Chip)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Poker table unavailable",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = ArcadeColors.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            fontSize = 12.sp,
            color = ArcadeColors.InkSoft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text("Open in your browser:", fontSize = 12.sp, color = ArcadeColors.Muted)
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                POKER_URL,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = ArcadeColors.Ink,
            )
        }
    }
}
