package ai.rever.boss.plugin.dynamic.arcade.mirrordash

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Neon palette from the original page. */
object MirrorDashColors {
    val BgTop = Color(0xFF080712)
    val BgMid = Color(0xFF100D22)
    val BgBottom = Color(0xFF090815)
    val Purple = Color(0xFF9B6CFF)
    val SparkPurple = Color(0xFFAD79FF)
    val SparkCyan = Color(0xFF54E7FF)
    val Pink = Color(0xFFFF5CA8)
    val Ink = Color(0xFFF7F3FF)
    val Muted = Color(0xFFAAA3BF)
    val ObstacleTop = Color(0xFF51277E)
    val ObstacleBottom = Color(0xFFF04A98)
    val StarBright = Color(0xFFBA9BFF)
    val StarDim = Color(0xFF6E6688)
}

/**
 * Draws one frame of the simulation. The engine works in density-independent
 * units, so everything here is scaled by [density] around the origin.
 */
fun DrawScope.drawMirrorDash(engine: MirrorDashEngine, density: Float) {
    val w = engine.width
    val h = engine.height
    if (w <= 0f || h <= 0f) return

    drawRect(
        Brush.verticalGradient(
            0f to MirrorDashColors.BgTop,
            0.5f to MirrorDashColors.BgMid,
            1f to MirrorDashColors.BgBottom,
        ),
    )

    scale(density, density, pivot = Offset.Zero) {
        val shakeX = if (engine.shake > 0.5f) (Random.nextFloat() - 0.5f) * engine.shake else 0f
        val shakeY = if (engine.shake > 0.5f) (Random.nextFloat() - 0.5f) * engine.shake else 0f
        translate(shakeX, shakeY) {
            drawStars(engine, w, h)
            drawCorridor(engine, w, h)
            drawObstacles(engine, w)
            drawShards(engine, w)
            drawSparks(engine, w)
            drawParticles(engine)
        }
    }

    if (engine.flash > 0.01f) {
        drawRect(MirrorDashColors.Pink.copy(alpha = (engine.flash * 0.23f).coerceIn(0f, 1f)))
    }
}

private fun DrawScope.drawStars(engine: MirrorDashEngine, w: Float, h: Float) {
    for (st in engine.stars) {
        val yy = (st.y + engine.time * engine.speed * 0.04f * st.z).mod(h)
        val alpha = (0.2f + st.z * 0.45f + sin(engine.time * 1.4f + st.p) * 0.08f).coerceIn(0f, 1f)
        drawRect(
            color = (if (st.z > 0.7f) MirrorDashColors.StarBright else MirrorDashColors.StarDim)
                .copy(alpha = alpha),
            topLeft = Offset(st.x, yy),
            size = Size(st.s, st.s * (1 + engine.speed / 500f)),
        )
    }
}

private fun DrawScope.drawCorridor(engine: MirrorDashEngine, w: Float, h: Float) {
    val mid = w / 2
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to MirrorDashColors.Purple.copy(alpha = 0.065f),
            1f to Color.Transparent,
            startX = mid - 180,
            endX = mid + 180,
        ),
        topLeft = Offset(mid - 180, 0f),
        size = Size(360f, h),
    )
    drawLine(
        color = MirrorDashColors.StarBright.copy(alpha = 0.16f),
        start = Offset(mid, 0f),
        end = Offset(mid, h),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 10f)),
    )
    val horizon = h * 0.2f
    val lineColor = Color(0xFF714EBE).copy(alpha = 0.09f)
    for (i in 0 until 7) {
        val y = horizon + (i * 115 + engine.time * engine.speed * 0.3f).mod(h - horizon)
        drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }
}

private fun DrawScope.drawObstacles(engine: MirrorDashEngine, w: Float) {
    for (o in engine.obstacles) {
        val x = o.x * w
        val ow = o.w * w
        for (rx in floatArrayOf(x, w - x - ow)) {
            val radius = min(12f, o.h * 0.25f)
            drawRoundRect(
                brush = Brush.linearGradient(
                    0f to MirrorDashColors.ObstacleTop,
                    1f to MirrorDashColors.ObstacleBottom,
                    start = Offset(rx, o.y),
                    end = Offset(rx + ow, o.y + o.h),
                ),
                topLeft = Offset(rx, o.y),
                size = Size(ow, o.h),
                cornerRadius = CornerRadius(radius, radius),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.3f),
                topLeft = Offset(rx, o.y),
                size = Size(ow, o.h),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 1f),
            )
        }
    }
}

private fun DrawScope.drawShards(engine: MirrorDashEngine, w: Float) {
    for (s in engine.shards) {
        drawDiamond(s.x * w, s.y, s.r, s.rot, Color(0xFFB78CFF))
        drawDiamond(w - s.x * w, s.y, s.r, -s.rot, MirrorDashColors.SparkCyan)
    }
}

private fun DrawScope.drawDiamond(x: Float, y: Float, r: Float, rot: Float, color: Color) {
    // Soft halo standing in for the canvas glow.
    drawCircle(color.copy(alpha = 0.18f), radius = r * 2.2f, center = Offset(x, y))
    val path = Path()
    val cos = kotlin.math.cos(rot)
    val sin = sin(rot)
    fun pt(px: Float, py: Float) =
        Offset(x + px * cos - py * sin, y + px * sin + py * cos)
    path.moveTo(pt(0f, -r).x, pt(0f, -r).y)
    path.lineTo(pt(r * 0.72f, 0f).x, pt(r * 0.72f, 0f).y)
    path.lineTo(pt(0f, r).x, pt(0f, r).y)
    path.lineTo(pt(-r * 0.72f, 0f).x, pt(-r * 0.72f, 0f).y)
    path.close()
    drawPath(path, color)
    drawPath(path, Color.White.copy(alpha = 0.75f), style = Stroke(width = 1f))
}

private fun DrawScope.drawSparks(engine: MirrorDashEngine, w: Float) {
    drawTrail(engine.trailA, MirrorDashColors.SparkPurple)
    drawTrail(engine.trailB, MirrorDashColors.SparkCyan)

    val ax = engine.playerX * w
    val bx = (1 - engine.playerX) * w
    val y = engine.playerY
    drawLine(
        Color(0xFFAB89EE).copy(alpha = 0.13f),
        Offset(ax, y), Offset(bx, y), strokeWidth = 1f,
    )
    drawSpark(ax, y, engine, MirrorDashColors.SparkPurple, mirror = 1f)
    drawSpark(bx, y, engine, MirrorDashColors.SparkCyan, mirror = -1f)
}

private fun DrawScope.drawTrail(trail: ArrayDeque<Pair<Float, Float>>, color: Color) {
    if (trail.size < 2) return
    for (i in trail.size - 1 downTo 1) {
        val a = 1 - i.toFloat() / trail.size
        drawLine(
            color.copy(alpha = a * 0.42f),
            Offset(trail[i].first, trail[i].second + i * 2),
            Offset(trail[i - 1].first, trail[i - 1].second + (i - 1) * 2),
            strokeWidth = 1 + a * 5,
        )
    }
}

private fun DrawScope.drawSpark(x: Float, y: Float, engine: MirrorDashEngine, color: Color, mirror: Float) {
    val r = engine.playerR
    drawCircle(color.copy(alpha = 0.22f), radius = r * 2f, center = Offset(x, y))
    drawCircle(color, radius = r, center = Offset(x, y))
    drawCircle(Color.White, radius = 3.2f, center = Offset(x - 3 * mirror, y - 3))
    drawCircle(
        Color.White.copy(alpha = 0.9f),
        radius = r + 5 + sin(engine.time * 6) * 1.5f,
        center = Offset(x, y),
        style = Stroke(width = 1.3f),
    )
}

private fun DrawScope.drawParticles(engine: MirrorDashEngine) {
    for (p in engine.particles) {
        val alpha = (p.life / p.max).coerceIn(0f, 1f)
        val color = if (p.cyan) MirrorDashColors.SparkCyan else Color(0xFFB68BFF)
        drawRect(
            color.copy(alpha = alpha),
            topLeft = Offset(p.x, p.y),
            size = Size(p.size, p.size),
        )
    }
}
