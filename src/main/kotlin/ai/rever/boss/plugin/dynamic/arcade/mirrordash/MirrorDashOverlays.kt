package ai.rever.boss.plugin.dynamic.arcade.mirrordash

import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardBg = Brush.linearGradient(
    0f to Color(0xF219142F),
    1f to Color(0xF50C0A19),
)
private val PrimaryBg = Brush.horizontalGradient(
    0f to Color(0xFF7547EF),
    1f to Color(0xFFB54EFF),
)

@Composable
internal fun BoxScope.MirrorDashHud(
    viewModel: MirrorDashViewModel,
    onBack: () -> Unit,
    onLeaderboard: () -> Unit,
) {
    Row(
        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "MIRROR DASH",
            color = MirrorDashColors.Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.weight(1f))
        HudStat("SCORE", viewModel.score)
        Spacer(Modifier.width(20.dp))
        HudStat("BEST", viewModel.best)
    }

    if (viewModel.mult > 1 && viewModel.phase == MirrorDashViewModel.Phase.PLAYING) {
        Text(
            "COMBO ×${viewModel.mult}",
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 86.dp),
            color = Color(0xFFFBD7FF),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.4.sp,
        )
    }

    Row(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            "Tap anywhere or press Space to reverse",
            color = Color(0xA3DCD6EE),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        NeonIconButton("‹", "Back to games", onBack)
        Spacer(Modifier.width(8.dp))
        NeonIconButton("🏆", "Leaderboard", onLeaderboard)
        Spacer(Modifier.width(8.dp))
        NeonIconButton(
            if (viewModel.phase == MirrorDashViewModel.Phase.PAUSED) "▶" else "Ⅱ",
            "Pause",
            onClick = { viewModel.togglePause() },
        )
    }
}

@Composable
private fun HudStat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            "$value",
            color = MirrorDashColors.Ink,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            label,
            color = MirrorDashColors.Muted,
            fontSize = 9.sp,
            letterSpacing = 1.3.sp,
        )
    }
}

@Composable
private fun NeonIconButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xB30B0916))
            .border(1.dp, Color(0x21FFFFFF), CircleShape)
            .plainClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}

@Composable
internal fun BoxScope.MirrorDashStartCard(onStart: () -> Unit) {
    NeonCard {
        Text("A ONE-TAP SURVIVAL GAME", color = MirrorDashColors.SparkCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        CardTitle("Mirror ", "Dash")
        Spacer(Modifier.height(14.dp))
        Text(
            "You control two linked sparks. Reverse their direction to slip through mirrored gates and collect energy shards.",
            color = Color(0xFFBCB5CE),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HowChip("Tap / Space", "Reverse direction")
            HowChip("Collect diamonds", "Build combo")
            HowChip("Avoid blocks", "Keep both alive")
        }
        Spacer(Modifier.height(24.dp))
        NeonPrimaryButton("START GAME", onStart)
        Spacer(Modifier.height(12.dp))
        Text("Keyboard and mouse supported — Enter also starts", color = Color(0xFF7F788F), fontSize = 10.sp)
    }
}

@Composable
internal fun BoxScope.MirrorDashPauseCard(onResume: () -> Unit) {
    NeonCard {
        Text("GAME PAUSED", color = MirrorDashColors.SparkCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        CardTitle("Take a ", "breath.")
        Spacer(Modifier.height(14.dp))
        Text("Your run is safe. Resume whenever you are ready.", color = Color(0xFFBCB5CE), fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        NeonPrimaryButton("RESUME", onResume)
    }
}

@Composable
internal fun BoxScope.MirrorDashOverCard(
    score: Int,
    isNewBest: Boolean,
    onRetry: () -> Unit,
    onLeaderboard: () -> Unit,
) {
    NeonCard {
        Text("RUN COMPLETE", color = MirrorDashColors.SparkCyan, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        CardTitle("Nice ", "dash.")
        Spacer(Modifier.height(16.dp))
        Text("$score", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Black)
        Text("FINAL SCORE", color = MirrorDashColors.Muted, fontSize = 10.sp, letterSpacing = 1.6.sp)
        if (isNewBest) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFF7D9FF))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Text("NEW BEST", color = Color(0xFF1A1023), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        NeonPrimaryButton("PLAY AGAIN", onRetry)
        Spacer(Modifier.height(10.dp))
        Text(
            "Leaderboard",
            color = MirrorDashColors.SparkCyan,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).plainClickable(onLeaderboard).padding(6.dp),
        )
        Text("Press Enter to replay", color = Color(0xFF7F788F), fontSize = 10.sp)
    }
}

@Composable
private fun BoxScope.NeonCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.align(Alignment.Center).padding(20.dp),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(CardBg)
                .border(1.dp, Color(0x21FFFFFF), RoundedCornerShape(28.dp))
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

@Composable
private fun CardTitle(plain: String, accent: String) {
    Row {
        Text(plain, color = MirrorDashColors.Ink, fontSize = 52.sp, fontFamily = FontFamily.Serif)
        Text(accent, color = MirrorDashColors.Purple, fontSize = 52.sp, fontFamily = FontFamily.Serif)
    }
}

@Composable
private fun HowChip(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Color(0x09FFFFFF))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp, vertical = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = Color(0xFF948DA8), fontSize = 10.sp)
    }
}

@Composable
private fun NeonPrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(PrimaryBg)
            .plainClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, fontSize = 14.sp)
    }
}
