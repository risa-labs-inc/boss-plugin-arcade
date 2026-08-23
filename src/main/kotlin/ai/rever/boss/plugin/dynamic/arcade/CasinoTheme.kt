package ai.rever.boss.plugin.dynamic.arcade

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * "Vegas neon night" palette for the arcade CHROME (home screen, credits UI,
 * overlays). The in-game screens keep [ArcadeColors] — those values are 1:1
 * ports of the original games and must never change; this object exists so the
 * casino look never has to touch them.
 */
object CasinoColors {
    /** Near-black warm charcoal floor — deliberately not pure black. */
    val Floor = Color(0xFF171210)
    val FloorEdge = Color(0xFF0B0807)
    val Spotlight = Color(0xFF4A3A24)

    /** Dark sign/dialog panels. */
    val Panel = Color(0xFF201914)
    val PanelDeep = Color(0xFF191310)

    /** Body text stays near-white/warm gray — the neon is decoration only. */
    val TextBright = Color(0xFFF4EDDF)
    val TextSoft = Color(0xFFC9BAA1)
    val TextMuted = Color(0xFF93846E)

    /** Casino gold, used for cost tags, the credits chip and dialog accents. */
    val Gold = Color(0xFFD9B45B)
    val GoldBright = Color(0xFFF2D689)
    val GoldDim = Color(0xFF8C7443)

    /** Warning/pending accent (request pending, out of credits). */
    val Alert = Color(0xFFFF7B92)

    /** Overlay scrim: darker than the pastel one so game screens dim properly. */
    val Scrim = Color(0xCC0B0807)

    /** Marquee bulbs around the title sign. */
    val Bulb = Color(0xFFFFD98A)

    /** The title sign's neon tube. */
    val TitleNeon = Color(0xFFFF5FB0)
    val TitleCore = Color(0xFFFFEAF5)

    // One distinct neon hue per game card.
    val Neon2048 = Color(0xFF35E0F2) // cyan
    val NeonMirrorDash = Color(0xFFFF3BD4) // magenta
    val NeonSkyStack = Color(0xFFFFB13D) // amber
    val NeonTypingSprint = Color(0xFFB18CFF) // violet
    val NeonWordle = Color(0xFFA8E64C) // lime
    val NeonBattleship = Color(0xFF7CC8FF) // ice blue
    val NeonPoker = Color(0xFFF2C94C) // gold, over a felt-green badge
    val PokerFelt = Color(0xFF1C5B3F)

    /** Deep table felt behind the poker screen's browser (loading/fallback backdrop). */
    val PokerFeltDeep = Color(0xFF102319)
}

/**
 * The casino floor: near-black warm ground, a soft spotlight falling from the
 * top-center, and a vignette darkening the edges. Painted opaquely INSIDE the
 * home screen so the shared [ArcadeBackground] (which game screens rely on)
 * stays untouched.
 */
@Composable
fun CasinoBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CasinoColors.Floor)
            .drawBehind {
                // Spotlight from the top-center.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CasinoColors.Spotlight.copy(alpha = 0.50f),
                            CasinoColors.Spotlight.copy(alpha = 0.16f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, 0f),
                        radius = (size.height * 0.95f).coerceAtLeast(1f),
                    ),
                )
                // Vignette: edges fall away into the dark.
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1.0f to CasinoColors.FloorEdge.copy(alpha = 0.80f),
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = (maxOf(size.width, size.height) * 0.72f).coerceAtLeast(1f),
                    ),
                )
            },
    ) {
        content()
    }
}

/**
 * Neon-sign border: 2-3 expanding rounded-rect strokes of decreasing alpha
 * behind a crisp 1.5dp "tube" stroke. [lit] 0..1 drives the switch-on (hover):
 * glow spread/alpha and tube brightness all scale with it. Draw BEFORE any
 * clip in the modifier chain so the halo can bleed outside the card.
 */
fun Modifier.neonSign(hue: Color, cornerRadius: Dp, lit: Float = 0f): Modifier = drawBehind {
    val corner = cornerRadius.toPx()
    for (layer in 1..3) {
        val spread = layer * (2.5f + 2.5f * lit).dp.toPx()
        val alpha = (0.14f + 0.24f * lit) / (layer * layer)
        drawRoundRect(
            color = hue.copy(alpha = alpha),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2f, size.height + spread * 2f),
            cornerRadius = CornerRadius(corner + spread),
            style = Stroke(width = spread * 1.6f),
        )
    }
    drawRoundRect(
        color = hue.copy(alpha = 0.55f + 0.45f * lit),
        cornerRadius = CornerRadius(corner),
        style = Stroke(width = 1.5f.dp.toPx()),
    )
}

/**
 * A short run of marquee bulbs with a gentle chase: a ~3-bulb bright window
 * sweeps along the run every 2s. One Canvas, one animated float — cheap.
 */
@Composable
fun MarqueeLights(modifier: Modifier, count: Int = 9, color: Color = CasinoColors.Bulb) {
    val transition = rememberInfiniteTransition(label = "marquee")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "marqueePhase",
    )
    Canvas(modifier) {
        val spacing = size.width / count
        val radius = min(size.height / 2f, spacing / 2f) * 0.5f
        for (i in 0 until count) {
            val distance = ((i.toFloat() / count) - phase).mod(1f)
            val chase = (1f - distance * count / 3f).coerceIn(0f, 1f)
            val center = Offset(spacing * (i + 0.5f), size.height / 2f)
            drawCircle(color.copy(alpha = 0.20f * chase), radius = radius * 2.2f, center = center)
            drawCircle(color.copy(alpha = 0.30f + 0.70f * chase), radius = radius, center = center)
        }
    }
}

/**
 * The neon sign headline: a blurred halo copy behind a crisp bright core.
 * The halo (only) carries a very occasional flicker; the core stays steady
 * so the title is always legible. [neon]/[core] default to the home marquee's
 * pink; a screen can pass its own hue (poker's gold-over-felt header).
 */
@Composable
fun NeonSignTitle(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 44.sp,
    neon: Color = CasinoColors.TitleNeon,
    core: Color = CasinoColors.TitleCore,
) {
    val transition = rememberInfiniteTransition(label = "neonFlicker")
    val flicker by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 9000
                1f at 0
                1f at 6600
                0.55f at 6680
                1f at 6760
                0.78f at 6840
                1f at 6920
                1f at 9000
            },
            RepeatMode.Restart,
        ),
        label = "neonFlickerAlpha",
    )
    Box(contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
            color = neon.copy(alpha = 0.75f * flicker),
            modifier = Modifier.blur(14.dp),
        )
        Text(
            text,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
            color = core,
            style = TextStyle(
                shadow = Shadow(
                    color = neon.copy(alpha = 0.9f * flicker),
                    blurRadius = 18f,
                ),
            ),
        )
    }
}

/**
 * Casino chrome buttons. The pastel [ArcadePrimaryButton]/[ArcadeGhostButton]
 * are used INSIDE game screens and keep their original look — these variants
 * exist so the chrome never repaints them.
 */
@Composable
fun CasinoPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) CasinoColors.Gold else CasinoColors.Gold.copy(alpha = 0.30f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            color = if (enabled) CasinoColors.PanelDeep else CasinoColors.PanelDeep.copy(alpha = 0.6f),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

@Composable
fun CasinoGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, CasinoColors.GoldDim.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            color = if (enabled) CasinoColors.TextSoft else CasinoColors.TextSoft.copy(alpha = 0.35f),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

/** A neon-outlined action button in a game's own hue (the card's "Play"). */
@Composable
fun CasinoNeonButton(
    text: String,
    hue: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(hue.copy(alpha = 0.10f))
            .border(1.5.dp, hue.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(text, color = hue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
