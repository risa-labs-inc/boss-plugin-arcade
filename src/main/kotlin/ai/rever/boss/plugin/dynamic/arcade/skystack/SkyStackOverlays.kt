package ai.rever.boss.plugin.dynamic.arcade.skystack

import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ButtonBackground = Brush.verticalGradient(
    listOf(Color(0xFFFFD9BC), Color(0xFFFFA97A)),
)

@Composable
internal fun BoxScope.SkyStackHud(
    viewModel: SkyStackViewModel,
    onBack: () -> Unit,
    onLeaderboard: () -> Unit,
) {
    if (viewModel.phase != SkyStackViewModel.Phase.MENU &&
        viewModel.phase != SkyStackViewModel.Phase.OVER
    ) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${viewModel.score}",
                color = SkyStackColors.Ink,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Text(
                if (viewModel.combo > 0) "PERFECT ×${viewModel.combo}" else "",
                color = SkyStackColors.Glow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
        }
    }

    Row(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (viewModel.phase) {
                SkyStackViewModel.Phase.PLAYING -> "Tap anywhere or press Space to drop"
                SkyStackViewModel.Phase.PAUSED -> "Paused"
                else -> "Stack from dusk to the stars"
            },
            color = SkyStackColors.Ink.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        SkyStackIconButton("‹", onBack)
        Spacer(Modifier.width(8.dp))
        SkyStackIconButton("🏆", onLeaderboard)
        if (viewModel.phase == SkyStackViewModel.Phase.PLAYING ||
            viewModel.phase == SkyStackViewModel.Phase.PAUSED
        ) {
            Spacer(Modifier.width(8.dp))
            SkyStackIconButton(
                if (viewModel.phase == SkyStackViewModel.Phase.PAUSED) "▶" else "Ⅱ",
                viewModel::togglePause,
            )
        }
    }
}

@Composable
internal fun BoxScope.SkyStackStartCard(
    best: Int,
    startButtonFocusRequester: FocusRequester,
    onStart: () -> Unit,
) {
    SkyStackCard {
        Text(
            "SKY STACK",
            color = Color(0xFFFFC49F),
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 7.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "STACK FROM DUSK TO THE STARS",
            color = SkyStackColors.InkDim,
            fontSize = 11.sp,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Text("Best altitude  $best", color = SkyStackColors.InkDim, fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        SkyStackPrimaryButton(
            text = "START STACKING",
            onClick = onStart,
            modifier = Modifier.focusRequester(startButtonFocusRequester),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "TAP OR PRESS  SPACE  TO DROP",
            color = SkyStackColors.InkDim,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
internal fun BoxScope.SkyStackPauseCard(onResume: () -> Unit) {
    SkyStackCard {
        Text("TOWER PAUSED", color = SkyStackColors.Glow, fontSize = 11.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(14.dp))
        Text("Take your time.", color = SkyStackColors.Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        SkyStackPrimaryButton("RESUME", onResume)
        Spacer(Modifier.height(12.dp))
        Text("Press Space or Enter to resume", color = SkyStackColors.InkDim, fontSize = 10.sp)
    }
}

@Composable
internal fun BoxScope.SkyStackOverCard(
    score: Int,
    best: Int,
    isNewBest: Boolean,
    onRetry: () -> Unit,
    onViewTower: () -> Unit,
    onLeaderboard: () -> Unit,
) {
    SkyStackCard {
        Text("THE TOWER RESTS AT", color = SkyStackColors.InkDim, fontSize = 11.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(10.dp))
        Text("$score", color = SkyStackColors.Ink, fontSize = 58.sp, fontWeight = FontWeight.Bold)
        Text(
            if (isNewBest && score > 0) "NEW BEST ALTITUDE" else " ",
            color = SkyStackColors.Glow,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text("Best altitude  $best", color = SkyStackColors.InkDim, fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        SkyStackPrimaryButton("STACK AGAIN", onRetry)
        Spacer(Modifier.height(10.dp))
        SkyStackOutlineButton("VIEW FULL TOWER", onViewTower)
        Spacer(Modifier.height(12.dp))
        Text(
            "Leaderboard",
            color = SkyStackColors.Glow,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).plainClickable(onLeaderboard).padding(6.dp),
        )
        Text("Press Space or Enter to replay", color = SkyStackColors.InkDim, fontSize = 10.sp)
    }
}

@Composable
internal fun BoxScope.SkyStackTowerOverviewControls(
    score: Int,
    exportMessage: String?,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "YOUR FULL TOWER",
            color = SkyStackColors.Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
        Text(
            "ALTITUDE $score",
            color = SkyStackColors.Glow,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }

    Column(
        modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        exportMessage?.let {
            Text(
                it,
                color = SkyStackColors.Ink.copy(alpha = 0.8f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkyStackOutlineButton("BACK TO SCORE", onBack)
            Spacer(Modifier.width(10.dp))
            SkyStackPrimaryButton("SAVE SHAREABLE SVG", onExport)
        }
    }
}

@Composable
private fun BoxScope.SkyStackCard(content: @Composable () -> Unit) {
    Box(modifier = Modifier.align(Alignment.Center).padding(20.dp)) {
        Column(
            modifier = Modifier
                .widthIn(max = 430.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SkyStackColors.Card)
                .border(1.dp, SkyStackColors.CardEdge, RoundedCornerShape(6.dp))
                .padding(horizontal = 48.dp, vertical = 38.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

@Composable
private fun SkyStackPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(ButtonBackground)
            .plainClickable(onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color(0xFF14092A),
            fontWeight = FontWeight.Black,
            fontSize = 13.sp,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun SkyStackOutlineButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(50.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(SkyStackColors.Card)
            .border(1.dp, SkyStackColors.CardEdge, RoundedCornerShape(4.dp))
            .plainClickable(onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = SkyStackColors.Ink,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun SkyStackIconButton(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SkyStackColors.Card)
            .border(1.dp, SkyStackColors.CardEdge, RoundedCornerShape(6.dp))
            .plainClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = SkyStackColors.Ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}
