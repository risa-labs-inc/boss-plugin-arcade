package ai.rever.boss.plugin.dynamic.arcade.skystack

import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    compact: Boolean = false,
    onBack: () -> Unit,
    onLeaderboard: () -> Unit,
) {
    if (viewModel.phase != SkyStackViewModel.Phase.MENU &&
        viewModel.phase != SkyStackViewModel.Phase.OVER
    ) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = if (compact) 12.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${viewModel.score}",
                color = SkyStackColors.Ink,
                fontSize = if (compact) 24.sp else 48.sp,
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
        SkyStackIconButton("‹", "Back to games", onBack)
        Spacer(Modifier.width(8.dp))
        SkyStackIconButton("🏆", "Leaderboard", onLeaderboard)
        if (viewModel.phase == SkyStackViewModel.Phase.PLAYING ||
            viewModel.phase == SkyStackViewModel.Phase.PAUSED
        ) {
            Spacer(Modifier.width(8.dp))
            SkyStackIconButton(
                if (viewModel.phase == SkyStackViewModel.Phase.PAUSED) "▶" else "Ⅱ",
                "Pause",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.SkyStackTowerOverviewControls(
    score: Int,
    exportMessage: String?,
    paneTooSmall: Boolean = false,
    onBack: () -> Unit,
    onExportSvg: () -> Unit,
    onExportPng: () -> Unit,
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
        modifier = Modifier.align(Alignment.BottomCenter).heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (paneTooSmall) {
            Text("Enlarge this pane to see the full tower.", color = SkyStackColors.Ink, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        exportMessage?.let {
            Text(
                it,
                color = SkyStackColors.Ink.copy(alpha = 0.8f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SkyStackOutlineButton("BACK", onBack)
            SkyStackOutlineButton("SAVE SVG", onExportSvg)
            SkyStackPrimaryButton("SAVE PNG", onExportPng)
        }
    }
}

@Composable
private fun BoxScope.SkyStackCard(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.align(Alignment.Center)
        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 76.dp)) {
        val compact = maxWidth < 600.dp || maxHeight < 420.dp
        Column(
            modifier = Modifier
                .widthIn(max = 430.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SkyStackColors.Card)
                .border(1.dp, SkyStackColors.CardEdge, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 20.dp else 48.dp, vertical = if (compact) 20.dp else 38.dp),
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
            .heightIn(min = 50.dp)
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
            .heightIn(min = 50.dp)
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
private fun SkyStackIconButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SkyStackColors.Card)
            .border(1.dp, SkyStackColors.CardEdge, RoundedCornerShape(6.dp))
            .semantics { contentDescription = description }
            .plainClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = SkyStackColors.Ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
    }
}
