package ai.rever.boss.plugin.dynamic.arcade.skystack

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object SkyStackColors {
    val Ink = Color(0xFFF4EFFF)
    val InkDim = Color(0xFFB9AEDC)
    val Glow = Color(0xFFFFB48A)
    val Card = Color(0xEB100A26)
    val CardEdge = Color(0x24F4EFFF)
    val DuskTop = Color(0xFF3A2C5F)
    val DuskBottom = Color(0xFFFF9E7A)
    val SpaceTop = Color(0xFF05030F)
    val SpaceBottom = Color(0xFF1B1440)
    val Star = Color(0xFFEAE4FF)
}

/** Draw one frame of the native port using the original isometric projection. */
fun DrawScope.drawSkyStack(
    engine: SkyStackEngine,
    density: Float,
    width: Float,
    height: Float,
    showMovingBlock: Boolean,
    showFullTower: Boolean = false,
) {
    if (width <= 0f || height <= 0f) return
    val altitude = min(1f, engine.score / 42f)
    drawRect(
        brush = Brush.verticalGradient(
            listOf(
                mix(SkyStackColors.DuskTop, SkyStackColors.SpaceTop, altitude),
                mix(SkyStackColors.DuskBottom, SkyStackColors.SpaceBottom, altitude),
            ),
        ),
    )

    scale(density, density, pivot = Offset.Zero) {
        drawStars(engine, width, height, altitude)
    }

    val towerHeight = engine.level * SkyStackEngine.BLOCK_HEIGHT + SkyStackEngine.SIZE
    val overviewScale = if (showFullTower) {
        min(
            1f,
            min(
                (height - 150f).coerceAtLeast(120f) / towerHeight,
                (width - 80f).coerceAtLeast(120f) / (SkyStackEngine.SIZE * 1.8f),
            ),
        ).coerceAtLeast(0.12f)
    } else {
        1f
    }
    val logicalWidth = width / overviewScale
    val logicalHeight = height / overviewScale

    scale(density * overviewScale, density * overviewScale, pivot = Offset.Zero) {
        var centerX = logicalWidth / 2f
        var centerY = if (showFullTower) {
            logicalHeight / 2f + engine.level * SkyStackEngine.BLOCK_HEIGHT / 2f
        } else {
            logicalHeight * 0.74f + engine.cameraY
        }
        if (engine.shakeTime > 0f) {
            centerX += (Random.nextFloat() - 0.5f) * 10f * engine.shakeTime
            centerY += (Random.nextFloat() - 0.5f) * 10f * engine.shakeTime
        }

        val firstVisible = max(
            0,
            if (showFullTower) {
                0
            } else {
                floor((engine.cameraY - logicalHeight) / SkyStackEngine.BLOCK_HEIGHT).toInt()
            },
        )
        for (i in firstVisible until engine.blocks.size) {
            drawBlock(
                block = engine.blocks[i],
                yTop = (i + 1) * SkyStackEngine.BLOCK_HEIGHT,
                centerX = centerX,
                centerY = centerY,
            )
        }

        if (showMovingBlock) {
            engine.current?.let { moving ->
                drawBlock(
                    block = SkyStackEngine.Block(
                        moving.x,
                        moving.z,
                        moving.w,
                        moving.d,
                        moving.hue,
                    ),
                    yTop = (engine.level + 1) * SkyStackEngine.BLOCK_HEIGHT,
                    centerX = centerX,
                    centerY = centerY,
                )
            }
        }

        engine.slices.forEach { slice ->
            drawBlock(
                block = SkyStackEngine.Block(
                    slice.x,
                    slice.z,
                    slice.w,
                    slice.d,
                    slice.hue,
                ),
                yTop = slice.y,
                centerX = centerX,
                centerY = centerY,
                alpha = slice.alpha.coerceIn(0f, 1f),
            )
        }

        engine.rings.forEach { ring ->
            drawPerfectRing(ring, centerX, centerY)
        }
    }
}

private fun DrawScope.drawStars(
    engine: SkyStackEngine,
    width: Float,
    height: Float,
    altitude: Float,
) {
    if (altitude <= 0.12f) return
    val starAlpha = (altitude - 0.12f) / 0.88f
    engine.stars.forEach { star ->
        val twinkle = 0.6f + 0.4f * sin(engine.elapsed / 0.9f + star.phase)
        val y = ((star.y * height * 1.4f + engine.cameraY * 0.12f) % (height * 1.4f)) -
            height * 0.2f
        drawCircle(
            color = SkyStackColors.Star.copy(
                alpha = (starAlpha * twinkle * 0.9f).coerceIn(0f, 1f),
            ),
            radius = star.radius,
            center = Offset(star.x * width, y),
        )
    }
}

private fun DrawScope.drawBlock(
    block: SkyStackEngine.Block,
    yTop: Float,
    centerX: Float,
    centerY: Float,
    alpha: Float = 1f,
) {
    fun project(x: Float, z: Float, y: Float): Offset = Offset(
        x = centerX + (x - z) * 0.866f,
        y = centerY + (x + z) * 0.5f - y,
    )

    val a = project(block.x, block.z, yTop)
    val b = project(block.x + block.w, block.z, yTop)
    val c = project(block.x + block.w, block.z + block.d, yTop)
    val d = project(block.x, block.z + block.d, yTop)
    val h = SkyStackEngine.BLOCK_HEIGHT

    drawPath(quad(a, b, c, d), face(block.hue, 0.68f).copy(alpha = alpha))
    drawPath(
        quad(b, c, Offset(c.x, c.y + h), Offset(b.x, b.y + h)),
        face(block.hue, 0.46f).copy(alpha = alpha),
    )
    drawPath(
        quad(d, c, Offset(c.x, c.y + h), Offset(d.x, d.y + h)),
        face(block.hue, 0.38f).copy(alpha = alpha),
    )
}

private fun DrawScope.drawPerfectRing(
    ring: SkyStackEngine.Ring,
    centerX: Float,
    centerY: Float,
) {
    val block = ring.block
    val expansion = 1f + ring.progress * 0.5f
    val middleX = block.x + block.w / 2f
    val middleZ = block.z + block.d / 2f
    val halfW = block.w / 2f * expansion
    val halfD = block.d / 2f * expansion
    fun project(x: Float, z: Float): Offset = Offset(
        centerX + (x - z) * 0.866f,
        centerY + (x + z) * 0.5f - ring.y,
    )
    val path = quad(
        project(middleX - halfW, middleZ - halfD),
        project(middleX + halfW, middleZ - halfD),
        project(middleX + halfW, middleZ + halfD),
        project(middleX - halfW, middleZ + halfD),
    )
    drawPath(
        path = path,
        color = Color(0xFFFFE7D2).copy(alpha = (1f - ring.progress) * 0.8f),
        style = Stroke(width = 2f),
    )
}

private fun quad(a: Offset, b: Offset, c: Offset, d: Offset): Path = Path().apply {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    lineTo(d.x, d.y)
    close()
}

private fun face(hue: Float, lightness: Float): Color = Color.hsl(
    hue = hue,
    saturation = 0.62f,
    lightness = lightness,
)

private fun mix(from: Color, to: Color, amount: Float): Color = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
    alpha = 1f,
)
