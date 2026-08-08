package ai.rever.boss.plugin.dynamic.arcade.skystack

import java.awt.Color as AwtColor
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.min

/** Exports the exact final block geometry as shareable SVG or PNG artwork. */
object SkyStackTowerExport {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    fun saveSvg(engine: SkyStackEngine): Path {
        val score = engine.score
        val blocks = engine.blocks.toList()
        val target = target(score, "svg")
        Files.writeString(
            target,
            svg(blocks, score),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        return target
    }

    fun savePng(engine: SkyStackEngine): Path {
        val score = engine.score
        val image = renderPng(engine.blocks.toList(), score)
        val target = target(score, "png")
        Files.newOutputStream(
            target,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { output ->
            check(ImageIO.write(image, "png", output)) { "PNG writer is unavailable" }
        }
        return target
    }

    private fun target(score: Int, extension: String): Path {
        val downloads = Path.of(System.getProperty("user.home"), "Downloads")
        Files.createDirectories(downloads)
        return downloads.resolve(
            "sky-stack-$score-${LocalDateTime.now().format(timestampFormat)}.$extension",
        )
    }

    internal fun renderPng(
        blocks: List<SkyStackEngine.Block>,
        score: Int,
    ): BufferedImage {
        val imageWidth = 1200
        val imageHeight = 1600
        val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.paint = GradientPaint(
                0f,
                0f,
                AwtColor(0x05, 0x03, 0x0F),
                0f,
                imageHeight.toFloat(),
                AwtColor(0x3A, 0x2C, 0x5F),
            )
            graphics.fillRect(0, 0, imageWidth, imageHeight)

            graphics.color = AwtColor(0xFF, 0xB4, 0x8A)
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 54)
            drawCentered(graphics, "S K Y   S T A C K", imageWidth, 92)
            graphics.color = AwtColor(0xB9, 0xAE, 0xDC)
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 28)
            drawCentered(graphics, "ALTITUDE $score", imageWidth, 146)

            val faces = blocks.flatMapIndexed { index, block ->
                blockFaces(block, (index + 1) * SkyStackEngine.BLOCK_HEIGHT)
            }
            if (faces.isNotEmpty()) {
                val allPoints = faces.flatMap { it.points }
                val minX = allPoints.minOf { it.first }
                val maxX = allPoints.maxOf { it.first }
                val minY = allPoints.minOf { it.second }
                val maxY = allPoints.maxOf { it.second }
                val worldWidth = (maxX - minX).coerceAtLeast(1f)
                val worldHeight = (maxY - minY).coerceAtLeast(1f)
                val left = 100f
                val top = 205f
                val availableWidth = imageWidth - left * 2f
                val availableHeight = 1160f
                val scale = min(availableWidth / worldWidth, availableHeight / worldHeight)
                val offsetX = left + (availableWidth - worldWidth * scale) / 2f - minX * scale
                val offsetY = top + (availableHeight - worldHeight * scale) / 2f - minY * scale

                faces.forEach { face ->
                    val polygon = Polygon(
                        face.points.map { (it.first * scale + offsetX).toInt() }.toIntArray(),
                        face.points.map { (it.second * scale + offsetY).toInt() }.toIntArray(),
                        face.points.size,
                    )
                    graphics.color = hsl(face.hue, 0.62f, face.lightness)
                    graphics.fillPolygon(polygon)
                }
            }

            graphics.color = AwtColor(0xB9, 0xAE, 0xDC)
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
            drawCentered(graphics, "BUILT IN BOSS ARCADE", imageWidth, 1510)
        } finally {
            graphics.dispose()
        }
        return image
    }

    internal fun svg(blocks: List<SkyStackEngine.Block>, score: Int): String {
        val towerHeight = blocks.size * SkyStackEngine.BLOCK_HEIGHT + SkyStackEngine.SIZE
        val viewWidth = 440f
        val viewHeight = towerHeight + 260f
        val minX = -viewWidth / 2f
        val minY = -blocks.size * SkyStackEngine.BLOCK_HEIGHT - 150f
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"880\" height=\"${number(viewHeight * 2)}\" " +
                    "viewBox=\"${number(minX)} ${number(minY)} ${number(viewWidth)} ${number(viewHeight)}\">",
            )
            appendLine("  <defs>")
            appendLine("    <linearGradient id=\"sky\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">")
            appendLine("      <stop offset=\"0\" stop-color=\"#05030F\"/>")
            appendLine("      <stop offset=\"1\" stop-color=\"#3A2C5F\"/>")
            appendLine("    </linearGradient>")
            appendLine("  </defs>")
            appendLine(
                "  <rect x=\"${number(minX)}\" y=\"${number(minY)}\" width=\"${number(viewWidth)}\" " +
                    "height=\"${number(viewHeight)}\" fill=\"url(#sky)\"/>",
            )
            appendLine(
                "  <text x=\"0\" y=\"${number(minY + 34f)}\" text-anchor=\"middle\" " +
                    "fill=\"#FFB48A\" font-family=\"sans-serif\" font-size=\"18\" " +
                    "font-weight=\"700\" letter-spacing=\"4\">SKY STACK</text>",
            )
            appendLine(
                "  <text x=\"0\" y=\"${number(minY + 58f)}\" text-anchor=\"middle\" " +
                    "fill=\"#B9AEDC\" font-family=\"sans-serif\" font-size=\"12\">ALTITUDE $score</text>",
            )
            blocks.forEachIndexed { index, block ->
                appendBlock(block, (index + 1) * SkyStackEngine.BLOCK_HEIGHT)
            }
            appendLine(
                "  <text x=\"0\" y=\"110\" text-anchor=\"middle\" fill=\"#B9AEDC\" " +
                    "font-family=\"sans-serif\" font-size=\"10\" letter-spacing=\"2\">BUILT IN BOSS ARCADE</text>",
            )
            appendLine("</svg>")
        }
    }

    private fun StringBuilder.appendBlock(block: SkyStackEngine.Block, yTop: Float) {
        fun point(x: Float, z: Float, y: Float): Pair<Float, Float> =
            (x - z) * 0.866f to (x + z) * 0.5f - y

        val a = point(block.x, block.z, yTop)
        val b = point(block.x + block.w, block.z, yTop)
        val c = point(block.x + block.w, block.z + block.d, yTop)
        val d = point(block.x, block.z + block.d, yTop)
        val h = SkyStackEngine.BLOCK_HEIGHT
        appendLine("  <polygon points=\"${points(a, b, c, d)}\" fill=\"hsl(${number(block.hue)},62%,68%)\"/>")
        appendLine(
            "  <polygon points=\"${points(b, c, c.first to c.second + h, b.first to b.second + h)}\" " +
                "fill=\"hsl(${number(block.hue)},62%,46%)\"/>",
        )
        appendLine(
            "  <polygon points=\"${points(d, c, c.first to c.second + h, d.first to d.second + h)}\" " +
                "fill=\"hsl(${number(block.hue)},62%,38%)\"/>",
        )
    }

    private fun points(vararg points: Pair<Float, Float>): String =
        points.joinToString(" ") { (x, y) -> "${number(x)},${number(y)}" }

    private fun number(value: Float): String = String.format(Locale.US, "%.2f", value)

    private data class Face(
        val points: List<Pair<Float, Float>>,
        val hue: Float,
        val lightness: Float,
    )

    private fun blockFaces(block: SkyStackEngine.Block, yTop: Float): List<Face> {
        fun point(x: Float, z: Float, y: Float): Pair<Float, Float> =
            (x - z) * 0.866f to (x + z) * 0.5f - y

        val a = point(block.x, block.z, yTop)
        val b = point(block.x + block.w, block.z, yTop)
        val c = point(block.x + block.w, block.z + block.d, yTop)
        val d = point(block.x, block.z + block.d, yTop)
        val h = SkyStackEngine.BLOCK_HEIGHT
        return listOf(
            Face(listOf(a, b, c, d), block.hue, 0.68f),
            Face(listOf(b, c, c.first to c.second + h, b.first to b.second + h), block.hue, 0.46f),
            Face(listOf(d, c, c.first to c.second + h, d.first to d.second + h), block.hue, 0.38f),
        )
    }

    private fun hsl(hue: Float, saturation: Float, lightness: Float): AwtColor {
        val normalizedHue = ((hue % 360f) + 360f) % 360f
        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val secondary = chroma * (1f - abs((normalizedHue / 60f) % 2f - 1f))
        val (red, green, blue) = when {
            normalizedHue < 60f -> Triple(chroma, secondary, 0f)
            normalizedHue < 120f -> Triple(secondary, chroma, 0f)
            normalizedHue < 180f -> Triple(0f, chroma, secondary)
            normalizedHue < 240f -> Triple(0f, secondary, chroma)
            normalizedHue < 300f -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
        val match = lightness - chroma / 2f
        return AwtColor(red + match, green + match, blue + match)
    }

    private fun drawCentered(
        graphics: java.awt.Graphics2D,
        text: String,
        width: Int,
        baseline: Int,
    ) {
        val textWidth = graphics.fontMetrics.stringWidth(text)
        graphics.drawString(text, (width - textWidth) / 2, baseline)
    }
}
