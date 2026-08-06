package ai.rever.boss.plugin.dynamic.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun ArcadeHomeScreen(onPlay2048: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GameCard(
                title = "2048",
                subtitle = "Join tiles, chase the crown",
                onClick = onPlay2048,
            )
            ComingSoonCard()
        }
    }
}

@Composable
private fun GameCard(title: String, subtitle: String, onClick: () -> Unit) {
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
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ArcadeColors.Pink),
            contentAlignment = Alignment.Center,
        ) {
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
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

@Composable
private fun ComingSoonCard() {
    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ArcadeColors.Cell.copy(alpha = 0.6f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ArcadeColors.Frame),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = ArcadeColors.Muted, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text("More soon", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ArcadeColors.InkSoft)
        Spacer(Modifier.height(4.dp))
        Text(
            "Got a game idea? Ping the team.",
            fontSize = 12.sp,
            color = ArcadeColors.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        ArcadeGhostButton(text = "Soon", onClick = {}, enabled = false)
    }
}
