package ai.rever.boss.plugin.dynamic.arcade.skystack

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Exports the exact final block geometry as a lightweight, shareable SVG. */
object SkyStackTowerExport {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun save(engine: SkyStackEngine): Path {
        val score = engine.score
        val blocks = engine.blocks.toList()
        val downloads = Path.of(System.getProperty("user.home"), "Downloads")
        Files.createDirectories(downloads)
        val target = downloads.resolve(
            "sky-stack-$score-${LocalDateTime.now().format(timestampFormat)}.svg",
        )
        Files.writeString(
            target,
            svg(blocks, score),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        return target
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
}
