package ai.rever.boss.plugin.dynamic.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArcadeHomeScreen(
    leaderboard: LeaderboardService,
    onPlay2048: () -> Unit,
    onPlayMirrorDash: () -> Unit,
    onPlaySkyStack: () -> Unit,
    onPlayTypingSprint: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Arcade",
            fontSize = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ArcadeColors.Ink,
        )
        Text(
            "Quick games. Team bragging rights.",
            fontSize = 14.sp,
            color = ArcadeColors.InkSoft,
        )
        Spacer(Modifier.height(28.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GameCard(
                title = "2048",
                subtitle = "Join tiles, chase the crown",
                badge = { TileBadge("2048", ArcadeColors.Pink) },
                onClick = onPlay2048,
            )
            GameCard(
                title = "Mirror Dash",
                subtitle = "One tap, two sparks, don't crash",
                badge = { TileBadge("⟷", Color(0xFF7547EF)) },
                onClick = onPlayMirrorDash,
            )
            GameCard(
                title = "Sky Stack",
                subtitle = "Stack from dusk to the stars",
                badge = { TileBadge("▲", Color(0xFFFF9E7A)) },
                onClick = onPlaySkyStack,
            )
            GameCard(
                title = "Typing Sprint",
                subtitle = "60 seconds, fast and clean",
                badge = { TileBadge("⌨", Color(0xFF4CA6A8)) },
                onClick = onPlayTypingSprint,
            )
        }
        ArcadeHomeInsights(leaderboard)
    }
}

@Composable
private fun TileBadge(glyph: String, background: Color) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

@Composable
private fun GameCard(
    title: String,
    subtitle: String,
    badge: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .shadow(8.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(ArcadeColors.Chip)
            .plainClickable(onClick)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        badge()
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ArcadeColors.Ink)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            fontSize = 12.sp,
            color = ArcadeColors.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        ArcadePrimaryButton(text = "Play", onClick = onClick)
    }
}
