package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipService
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipViewModel
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048ViewModel
import ai.rever.boss.plugin.dynamic.arcade.mirrordash.MirrorDashViewModel
import ai.rever.boss.plugin.dynamic.arcade.skystack.SkyStackViewModel
import ai.rever.boss.plugin.dynamic.arcade.typingsprint.TypingSprintViewModel
import ai.rever.boss.plugin.dynamic.arcade.wordle.WordleViewModel
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArcadeHomeScreen(
    leaderboard: LeaderboardService,
    battleshipService: BattleshipService,
    credits: CreditsService,
    onRequestCredits: () -> Unit,
    onOpenAdmin: () -> Unit,
    onPlay2048: () -> Unit,
    onPlayMirrorDash: () -> Unit,
    onPlaySkyStack: () -> Unit,
    onPlayTypingSprint: () -> Unit,
    onPlayWordle: () -> Unit,
    onPlayBattleship: () -> Unit,
    onPlayPoker: () -> Unit,
    battleshipWaiting: Int = 0,
) {
    // "Tab focus" refresh: the home screen recomposes from scratch on every
    // return to it (and on tab open), so this re-reads the balance each time.
    LaunchedEffect(Unit) {
        credits.refresh()
        credits.refreshAdmin()
    }
    val isAdmin by credits.isAdmin.collectAsState()
    // Collected so the cost tags (and their appearance/disappearance when
    // credits degrade or recover) recompose with the snapshot.
    val creditsSnapshot by credits.snapshot.collectAsState()
    fun cost(game: String): String? =
        if (creditsSnapshot == null) null else credits.costLabel(game)

    // The casino floor is painted here, opaquely over the shared pastel
    // ArcadeBackground — game screens still rely on that one, so it never changes.
    CasinoBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarqueeLights(Modifier.width(96.dp).height(12.dp))
                Spacer(Modifier.width(16.dp))
                NeonSignTitle("BOSS ARCADE")
                Spacer(Modifier.width(16.dp))
                MarqueeLights(Modifier.width(96.dp).height(12.dp))
            }
            Text(
                "Quick games. Team bragging rights.",
                fontSize = 14.sp,
                color = CasinoColors.TextSoft,
            )
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CreditsChip(credits, onRequest = onRequestCredits)
                if (isAdmin) CasinoGhostButton("Admin", onClick = onOpenAdmin)
            }
            Spacer(Modifier.height(20.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GameCard(
                    title = "2048",
                    subtitle = "Join tiles, chase the crown",
                    hue = CasinoColors.Neon2048,
                    badge = { TileBadge("2048", CasinoColors.Neon2048) },
                    costLabel = cost(Game2048ViewModel.GAME),
                    onClick = onPlay2048,
                )
                GameCard(
                    title = "Mirror Dash",
                    subtitle = "One tap, two sparks, don't crash",
                    hue = CasinoColors.NeonMirrorDash,
                    badge = { TileBadge("⟷", CasinoColors.NeonMirrorDash) },
                    costLabel = cost(MirrorDashViewModel.GAME),
                    onClick = onPlayMirrorDash,
                )
                GameCard(
                    title = "Sky Stack",
                    subtitle = "Stack from dusk to the stars",
                    hue = CasinoColors.NeonSkyStack,
                    badge = { TileBadge("▲", CasinoColors.NeonSkyStack) },
                    costLabel = cost(SkyStackViewModel.GAME),
                    onClick = onPlaySkyStack,
                )
                GameCard(
                    title = "Typing Sprint",
                    subtitle = "60 seconds, fast and clean",
                    hue = CasinoColors.NeonTypingSprint,
                    badge = { TileBadge("⌨", CasinoColors.NeonTypingSprint) },
                    costLabel = cost(TypingSprintViewModel.GAME),
                    onClick = onPlayTypingSprint,
                )
                GameCard(
                    title = "Wordle",
                    subtitle = "One shared word a day",
                    hue = CasinoColors.NeonWordle,
                    badge = { TileBadge("W", CasinoColors.NeonWordle) },
                    costLabel = cost(WordleViewModel.GAME),
                    onClick = onPlayWordle,
                )
                GameCard(
                    // The badge counts games waiting on you: the whole point of an
                    // async game is knowing there is a move to make without opening it.
                    title = "Battleship",
                    subtitle = if (battleshipWaiting > 0) {
                        "$battleshipWaiting waiting on you"
                    } else {
                        "Head to head, one shot at a time"
                    },
                    hue = CasinoColors.NeonBattleship,
                    badge = {
                        TileBadge(
                            if (battleshipWaiting > 0) "$battleshipWaiting" else "⚓",
                            CasinoColors.NeonBattleship,
                        )
                    },
                    costLabel = cost(BattleshipViewModel.GAME),
                    onClick = onPlayBattleship,
                )
                GameCard(
                    // The flagship keeps its ♠ identity: gold neon over a felt-green badge.
                    title = "Poker",
                    subtitle = "No-Limit Hold'em · live multiplayer",
                    hue = CasinoColors.NeonPoker,
                    badge = { TileBadge("♠", CasinoColors.NeonPoker, fill = CasinoColors.PokerFelt) },
                    onClick = onPlayPoker,
                )
            }
            ArcadeHomeInsights(leaderboard, battleshipService)
        }
    }
}

/**
 * The game's marquee tile: dark panel with the glyph glowing in the game's
 * own neon hue. [fill] overrides the panel color (poker's green felt).
 */
@Composable
private fun TileBadge(glyph: String, hue: Color, fill: Color? = null) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(fill ?: hue.copy(alpha = 0.10f))
            .border(1.5.dp, hue.copy(alpha = 0.8f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = hue, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

/**
 * A neon sign per game: dark panel, thin neon border with a faint halo in the
 * game's own hue. Hover switches the sign on — glow spread/alpha up, border
 * brightens, slight scale — all driven by one animated 0..1 float.
 */
@Composable
private fun GameCard(
    title: String,
    subtitle: String,
    hue: Color,
    badge: @Composable () -> Unit,
    onClick: () -> Unit,
    // Per-run price tag ("100 ✦"). Null = credits hidden (degraded) or a game
    // that charges nothing here (poker's buy-ins live in the web app).
    costLabel: String? = null,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val lit by animateFloatAsState(if (hovered) 1f else 0f, tween(180), label = "cardLit")
    Column(
        modifier = Modifier
            .width(180.dp)
            .graphicsLayer {
                val scale = 1f + 0.02f * lit
                scaleX = scale
                scaleY = scale
            }
            // Glow before clip: the halo must bleed outside the panel.
            .neonSign(hue, cornerRadius = 18.dp, lit = lit)
            .clip(RoundedCornerShape(18.dp))
            .background(CasinoColors.Panel)
            .background(hue.copy(alpha = 0.03f + 0.05f * lit))
            .hoverable(hoverSource)
            .plainClickable(onClick)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        badge()
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CasinoColors.TextBright)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            fontSize = 12.sp,
            color = CasinoColors.TextMuted,
            textAlign = TextAlign.Center,
            // Every card reserves exactly two subtitle lines so a short subtitle
            // ("One shared word a day") cannot make its card shorter than the rest.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        // The cost-tag slot is reserved even when there is no tag (poker charges
        // nothing here) so every card measures the same height as its neighbors.
        Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
            if (costLabel != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(CasinoColors.Gold.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        costLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CasinoColors.Gold,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        CasinoNeonButton(text = "Play", hue = hue, onClick = onClick)
    }
}
