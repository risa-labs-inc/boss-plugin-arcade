package ai.rever.boss.plugin.dynamic.arcade.paarcade

import ai.rever.boss.plugin.dynamic.arcade.CasinoBackground
import ai.rever.boss.plugin.dynamic.arcade.CasinoColors
import ai.rever.boss.plugin.dynamic.arcade.EmbeddedWebAppViewModel
import ai.rever.boss.plugin.dynamic.arcade.NeonSignTitle
import ai.rever.boss.plugin.dynamic.arcade.neonSign
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PA Arcade is a web app, not a Compose port: the screen is a header plus the
 * embedded browser, exactly like poker's. The games (and their own scoring)
 * live at [PA_ARCADE_URL]; when the host can't give us a browser, we say so
 * and hand the player the URL instead. The chrome continues the casino floor
 * from the home screen in PA Arcade's own coral hue.
 */
@Composable
fun PaArcadeScreen(
    viewModel: PaArcadeViewModel,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.ensureBrowser() }

    CasinoBackground {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).plainClickable(onBack).padding(4.dp)) {
                    Icon(Icons.Outlined.ArrowBack, "Back to games", tint = CasinoColors.TextSoft)
                }
                Spacer(Modifier.width(6.dp))
                // The card's identity from the home screen: coral "PA" badge.
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(CasinoColors.NeonPaArcade.copy(alpha = 0.10f))
                        .border(1.5.dp, CasinoColors.NeonPaArcade.copy(alpha = 0.8f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("PA", color = CasinoColors.NeonPaArcade, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    NeonSignTitle(
                        "PA Arcade",
                        fontSize = 22.sp,
                        neon = CasinoColors.NeonPaArcade,
                        core = CasinoColors.TitleCore,
                    )
                    Text(
                        "Prior-auth training games: Auth Desk, Units Rush, daily puzzles and more.",
                        fontSize = 12.sp,
                        color = CasinoColors.TextSoft,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Neon rim drawn before the clip so the halo bleeds onto the floor.
                    .neonSign(CasinoColors.NeonPaArcade, cornerRadius = 14.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CasinoColors.PaArcadeDeep),
                contentAlignment = Alignment.Center,
            ) {
                when (viewModel.phase) {
                    EmbeddedWebAppViewModel.Phase.CREATING -> PaArcadeLoading()
                    EmbeddedWebAppViewModel.Phase.UNAVAILABLE -> PaArcadeUnavailableCard(viewModel.unavailableReason)
                    EmbeddedWebAppViewModel.Phase.READY -> {
                        val handle = viewModel.browser()
                        if (handle != null) {
                            handle.Content()
                        } else {
                            PaArcadeUnavailableCard("The embedded browser went away.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaArcadeLoading() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            color = CasinoColors.NeonPaArcade,
            strokeWidth = 2.dp,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("Opening PA Arcade…", fontSize = 12.sp, color = CasinoColors.TextSoft)
    }
}

@Composable
private fun PaArcadeUnavailableCard(message: String) {
    Column(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(CasinoColors.Panel)
            .border(1.5.dp, CasinoColors.NeonPaArcade.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "PA Arcade unavailable",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = CasinoColors.TextBright,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            fontSize = 12.sp,
            color = CasinoColors.TextSoft,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text("Open in your browser:", fontSize = 12.sp, color = CasinoColors.TextMuted)
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                PA_ARCADE_URL,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = CasinoColors.NeonPaArcade,
            )
        }
    }
}
