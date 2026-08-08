package ai.rever.boss.plugin.dynamic.arcade.skystack

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Short synthesized tones matching the oscillators in the original HTML game. */
class SkyStackSoundPlayer(private val scope: CoroutineScope) {
    private enum class Wave { SINE, SQUARE, SAW }

    fun perfect(combo: Int) {
        play(660f + combo * 60f, 0.16f, Wave.SINE, 0.12f)
        play(990f + combo * 60f, 0.22f, Wave.SINE, 0.07f)
    }

    fun trim(level: Int) {
        play(180f + level * 3f, 0.08f, Wave.SQUARE, 0.05f)
    }

    fun gameOver() {
        play(160f, 0.35f, Wave.SAW, 0.08f)
        play(90f, 0.50f, Wave.SAW, 0.06f)
    }

    private fun play(frequency: Float, durationSeconds: Float, wave: Wave, volume: Float) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val sampleRate = 44_100f
                val sampleCount = (sampleRate * durationSeconds).toInt()
                val bytes = ByteArray(sampleCount * 2)
                for (sampleIndex in 0 until sampleCount) {
                    val time = sampleIndex / sampleRate
                    val phase = (frequency * time) % 1f
                    val oscillator = when (wave) {
                        Wave.SINE -> sin(2.0 * PI * phase).toFloat()
                        Wave.SQUARE -> if (phase < 0.5f) 1f else -1f
                        Wave.SAW -> phase * 2f - 1f
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
