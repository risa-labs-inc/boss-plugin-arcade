package ai.rever.boss.plugin.dynamic.arcade

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmbienceSynthTest {

    /** 30s of stream: every sample finite, under the master ceiling, and audibly non-silent. */
    @Test
    fun buffersStayFiniteAndUnderTheCeiling() {
        val synth = AmbienceSynth(Random(42))
        val buffer = FloatArray(2205)
        var peak = 0f
        repeat(30 * AmbienceSynth.SAMPLE_RATE / buffer.size) {
            synth.nextBuffer(buffer)
            for (sample in buffer) {
                assertTrue(sample.isFinite(), "non-finite sample in the stream")
                if (abs(sample) > peak) peak = abs(sample)
            }
        }
        assertTrue(peak <= AmbienceSynth.CEILING + 1e-4f, "peak $peak exceeds ceiling ${AmbienceSynth.CEILING}")
        assertTrue(peak > 0.01f, "stream is near-silent (peak $peak) — the pad should be audible")
    }

    /** Accents are sparse background texture: every gap between spawns stays in [6s, 14s]. */
    @Test
    fun accentGapsStayWithinBounds() {
        val synth = AmbienceSynth(Random(7))
        val starts = mutableListOf<Double>()
        synth.onAccentSpawned = { starts += it }
        val buffer = FloatArray(4410)
        repeat(150 * AmbienceSynth.SAMPLE_RATE / buffer.size) { synth.nextBuffer(buffer) }
        assertTrue(starts.size >= 10, "expected at least 10 accents in 150s, got ${starts.size}")
        assertTrue(starts.size <= 26, "expected at most 26 accents in 150s, got ${starts.size}")
        // The first accent is scheduled from t=0, so its start time is itself a gap.
        val gaps = listOf(starts.first()) + starts.zipWithNext { a, b -> b - a }
        for (gap in gaps) {
            assertTrue(gap >= AmbienceSynth.ACCENT_GAP_MIN_S - 0.01, "accent gap ${gap}s below the 6s floor")
            assertTrue(gap <= AmbienceSynth.ACCENT_GAP_MAX_S + 0.01, "accent gap ${gap}s above the 14s cap")
        }
    }

    /** Same seed, same stream: the generator is deterministic (what the range test relies on). */
    @Test
    fun sameSeedProducesSameStream() {
        val a = AmbienceSynth(Random(99))
        val b = AmbienceSynth(Random(99))
        val bufferA = FloatArray(2205)
        val bufferB = FloatArray(2205)
        repeat(20) {
            a.nextBuffer(bufferA)
            b.nextBuffer(bufferB)
            assertTrue(bufferA.contentEquals(bufferB), "streams diverged at buffer $it")
        }
    }

    /** PCM conversion is 16-bit little-endian and clamps out-of-range floats. */
    @Test
    fun pcmConversionIsLittleEndianAndClamped() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 1.5f, -1.5f)
        val bytes = ByteArray(samples.size * 2)
        AmbienceSynth.toPcm16(samples, samples.size, bytes)
        fun decode(i: Int): Int =
            (bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)
        assertEquals(0, decode(0))
        assertEquals((0.5f * Short.MAX_VALUE).toInt(), decode(1))
        assertEquals((-0.5f * Short.MAX_VALUE).toInt(), decode(2))
        assertEquals(Short.MAX_VALUE.toInt(), decode(3), "over-range must clamp to +32767")
        assertEquals(Short.MIN_VALUE.toInt(), decode(4), "under-range must clamp to -32768")
    }
}
