package ai.rever.boss.plugin.dynamic.arcade.battleship

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Synthesized shot feedback, same approach as SkyStackSoundPlayer: short
 * generated tones, no audio assets. A hit lands as a low thump with a crackle,
 * a miss as a watery plip, a sunk ship as a longer rumble. The opponent's
 * shots play lower and softer than yours, so you can tell whose shell you
 * just heard without looking at the board.
 */
class BattleshipSoundPlayer(private val scope: CoroutineScope) {
    private enum class Wave { SINE, SQUARE, SAW, NOISE }

    /** Play the sound for a server-reported shot result: miss, hit, or sunk. */
    fun outcome(result: String, incoming: Boolean = false) {
        when (result) {
            "sunk" -> sunk(incoming)
            "hit" -> hit(incoming)
            else -> miss(incoming)
        }
    }

    fun miss(incoming: Boolean = false) {
        val pitch = if (incoming) 0.75f else 1f
        val gain = if (incoming) 0.8f else 1f
        // Water plip: a quick high blip falling onto a soft low bloop.
        play(520f * pitch, 0.08f, Wave.SINE, 0.06f * gain)
        play(290f * pitch, 0.16f, Wave.SINE, 0.05f * gain)
    }

    fun hit(incoming: Boolean = false) {
        val pitch = if (incoming) 0.75f else 1f
        val gain = if (incoming) 0.8f else 1f
        // Shell strike: low thump with a burst of crackle on top.
        play(130f * pitch, 0.18f, Wave.SQUARE, 0.07f * gain)
        play(70f * pitch, 0.30f, Wave.SAW, 0.08f * gain)
        play(0f, 0.12f, Wave.NOISE, 0.04f * gain)
    }

    fun sunk(incoming: Boolean = false) {
        val pitch = if (incoming) 0.75f else 1f
        val gain = if (incoming) 0.8f else 1f
        // A whole ship going down: deeper and noticeably longer than a hit.
        play(110f * pitch, 0.22f, Wave.SQUARE, 0.06f * gain)
        play(55f * pitch, 0.50f, Wave.SAW, 0.09f * gain)
        play(0f, 0.30f, Wave.NOISE, 0.06f * gain)
    }

    private fun play(frequency: Float, durationSeconds: Float, wave: Wave, volume: Float) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val sampleRate = 44_100f
                val sampleCount = (sampleRate * durationSeconds).toInt()
                val bytes = ByteArray(sampleCount * 2)
                val noise = Random(0)
                for (sampleIndex in 0 until sampleCount) {
                    val time = sampleIndex / sampleRate
                    val phase = (frequency * time) % 1f
                    val oscillator = when (wave) {
                        Wave.SINE -> sin(2.0 * PI * phase).toFloat()
                        Wave.SQUARE -> if (phase < 0.5f) 1f else -1f
                        Wave.SAW -> phase * 2f - 1f
                        Wave.NOISE -> noise.nextFloat() * 2f - 1f
                    }
                    val progress = sampleIndex.toFloat() / sampleCount
                    val envelope = volume * (0.0001f / volume).pow(progress)
                    val value = (oscillator * envelope * Short.MAX_VALUE)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    bytes[sampleIndex * 2] = (value and 0xFF).toByte()
                    bytes[sampleIndex * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
                }

                val format = AudioFormat(sampleRate, 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                try {
                    line.open(format, bytes.size.coerceAtLeast(1024))
                    line.start()
                    line.write(bytes, 0, bytes.size)
                    line.drain()
                } finally {
                    line.stop()
                    line.close()
                }
            }
        }
    }
}
