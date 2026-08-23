package ai.rever.boss.plugin.dynamic.arcade

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Streaming synthesizer for the casino-floor ambience: everything is computed
 * on the fly in small buffers (no audio files, no big precomputed loop), so
 * memory stays tiny and the texture never audibly repeats. Pure math — no
 * javax.sound in here — which is what makes it unit-testable.
 *
 * Three layers, all deliberately QUIET (see the level constants):
 *  - a warm chord pad: three low-register voices per chord, each a pair of
 *    slightly detuned sines (slow chorus shimmer), breathing on a ~20s
 *    amplitude LFO, walking Fmaj7 -> Am7 -> Dm7 -> Bbmaj7 at 8s per chord
 *    with an equal-power crossfade. Sines only = nothing above the
 *    fundamentals, which is the "lowpassed felt-table" warmth for free.
 *  - a room murmur: white noise through a one-pole lowpass, its gain drifting
 *    slowly and randomly like a crowd swelling and settling.
 *  - sparse distant accents every 6-14s: a chip-clink (fast-decay noise burst
 *    plus a faint detuned metallic partial pair) or a card-riffle (a run of
 *    tiny noise ticks under a swell-and-fade envelope), each with randomized
 *    level and decay so no two sound alike.
 *
 * Output samples are floats in [-CEILING, CEILING] — the low master ceiling is
 * part of the product ("background texture, not foreground events"); the
 * player applies its own fade gain on top and converts to 16-bit PCM.
 */
class AmbienceSynth(private val random: Random = Random(System.nanoTime())) {

    /** Absolute sample clock; a Double time from it stays precise for days at 22.05 kHz. */
    private var sampleIndex = 0L

    // Room-murmur state: one-pole lowpass + slow random gain drift.
    private var murmurLp = 0f
    private var murmurGain = 0.7f
    private var murmurTarget = 0.7f
    private var nextDriftSample = DRIFT_INTERVAL_SAMPLES

    // Accent state: at most one is ever live (gaps >= 6s, events <= ~0.3s).
    private val activeAccents = mutableListOf<Accent>()
    private var nextAccentSample = (nextAccentGapSeconds() * SAMPLE_RATE).toLong()

    /** Test hook: called with the spawn time (seconds) whenever an accent starts. */
    internal var onAccentSpawned: ((timeSeconds: Double) -> Unit)? = null

    /** Fill [out] with the next samples of the ambience, advancing the stream. */
    fun nextBuffer(out: FloatArray) {
        for (i in out.indices) out[i] = nextSample()
    }

    private fun nextSample(): Float {
        val t = sampleIndex / SAMPLE_RATE.toDouble()
        val sample = pad(t) + murmur() + accents()
        sampleIndex++
        return sample.coerceIn(-CEILING, CEILING)
    }

    private fun pad(t: Double): Float {
        val chordPos = t % CHORD_SECONDS
        val chordIndex = ((t / CHORD_SECONDS).toLong() % CHORDS.size).toInt()
        var sum = chordVoices(chordIndex, t)
        if (chordPos < CROSSFADE_SECONDS && t >= CHORD_SECONDS) {
            // Equal-power crossfade out of the previous chord: continuous at both ends.
            val fade = chordPos / CROSSFADE_SECONDS
            val previous = (chordIndex + CHORDS.size - 1) % CHORDS.size
            sum = (sum * sin(fade * PI / 2) + chordVoices(previous, t) * cos(fade * PI / 2)).toFloat()
        }
        val lfo = 0.8f + 0.2f * sin(TWO_PI * PAD_LFO_HZ * t + 1.3).toFloat()
        return sum * lfo * PAD_LEVEL
    }

    /** One chord's voices: detuned sine pairs, low notes weighted louder, normalized to <= 1. */
    private fun chordVoices(chord: Int, t: Double): Float {
        val frequencies = CHORDS[chord]
        var acc = 0.0
        for (voice in frequencies.indices) {
            val frequency = frequencies[voice]
            acc += VOICE_WEIGHTS[voice] *
                (sin(TWO_PI * frequency * (1.0 - DETUNE) * t) + sin(TWO_PI * frequency * (1.0 + DETUNE) * t))
        }
        return (acc / VOICE_NORM).toFloat()
    }

    private fun murmur(): Float {
        if (sampleIndex >= nextDriftSample) {
            murmurTarget = 0.4f + 0.6f * random.nextFloat()
            nextDriftSample = sampleIndex + DRIFT_INTERVAL_SAMPLES
        }
        murmurGain += (murmurTarget - murmurGain) * DRIFT_ALPHA
        val white = random.nextFloat() * 2f - 1f
        murmurLp += (white - murmurLp) * MURMUR_ALPHA
        return murmurLp * murmurGain * MURMUR_LEVEL
    }

    private fun accents(): Float {
        if (sampleIndex >= nextAccentSample) spawnAccent()
        if (activeAccents.isEmpty()) return 0f
        var acc = 0f
        val iterator = activeAccents.iterator()
        while (iterator.hasNext()) {
            val accent = iterator.next()
            acc += accent.render()
            if (accent.done) iterator.remove()
        }
        return acc * ACCENT_LEVEL
    }

    private fun spawnAccent() {
        onAccentSpawned?.invoke(sampleIndex / SAMPLE_RATE.toDouble())
        activeAccents += if (random.nextFloat() < 0.6f) chipClink() else cardRiffle()
        nextAccentSample = sampleIndex + (nextAccentGapSeconds() * SAMPLE_RATE).toLong()
    }

    private fun nextAccentGapSeconds(): Double =
        ACCENT_GAP_MIN_S + (ACCENT_GAP_MAX_S - ACCENT_GAP_MIN_S) * random.nextDouble()

    private interface Accent {
        val done: Boolean

        fun render(): Float
    }

    /** A distant chip landing: fast-decay noise burst + a faint pair of metallic partials. */
    private fun chipClink(): Accent = object : Accent {
        private val level = 0.35f + 0.65f * random.nextFloat()
        private val partial1 = 2500.0 * (0.9 + 0.2 * random.nextDouble())
        private val partial2 = partial1 * 1.58
        private val noiseTau = 0.008 + 0.006 * random.nextDouble()
        private val length = (0.22 * SAMPLE_RATE).toInt()
        private var n = 0

        override val done get() = n >= length

        override fun render(): Float {
            val t = n / SAMPLE_RATE.toDouble()
            n++
            val burst = (random.nextFloat() * 2f - 1f) * exp(-t / noiseTau).toFloat()
            val metal = ((sin(TWO_PI * partial1 * t) + 0.6 * sin(TWO_PI * partial2 * t)) * exp(-t / 0.06)).toFloat()
            return (burst * 0.8f + metal * 0.35f) * level
        }
    }

    /** A distant card riffle: a rapid run of tiny noise ticks under a swell-and-fade envelope. */
    private fun cardRiffle(): Accent = object : Accent {
        private val level = 0.3f + 0.5f * random.nextFloat()
        private val tickGap = 0.010 + 0.008 * random.nextDouble()
        private val ticks = 9 + random.nextInt(6)
        private val length = ((ticks * tickGap + 0.03) * SAMPLE_RATE).toInt()
        private var n = 0
        private var lp = 0f

        override val done get() = n >= length

        override fun render(): Float {
            val t = n / SAMPLE_RATE.toDouble()
            n++
            val envelope = sin(PI * n / length).toFloat()
            val click = if (t < ticks * tickGap) {
                (random.nextFloat() * 2f - 1f) * exp(-(t % tickGap) / 0.0022).toFloat()
            } else {
                0f
            }
            lp += (click - lp) * 0.45f
            return lp * envelope * level
        }
    }

    companion object {
        const val SAMPLE_RATE = 22_050

        /**
         * Master output ceiling (linear). The layer budgets below sum under it
         * by construction; the per-sample coerce is a belt-and-braces guard.
         */
        const val CEILING = 0.24f

        internal const val ACCENT_GAP_MIN_S = 6.0
        internal const val ACCENT_GAP_MAX_S = 14.0

        private const val TWO_PI = 2.0 * PI
        private const val PAD_LEVEL = 0.14f
        private const val MURMUR_LEVEL = 0.03f
        private const val ACCENT_LEVEL = 0.05f
        private const val PAD_LFO_HZ = 0.05
        private const val DETUNE = 0.0013
        private const val CHORD_SECONDS = 8.0
        private const val CROSSFADE_SECONDS = 2.5

        /** One-pole lowpass coefficient, ~300 Hz at 22.05 kHz: crowd murmur, not hiss. */
        private const val MURMUR_ALPHA = 0.085f

        /** Murmur gain eases toward its drifting target with a ~2s time constant. */
        private const val DRIFT_ALPHA = 1f / (2f * SAMPLE_RATE)
        private val DRIFT_INTERVAL_SAMPLES = (4.0 * SAMPLE_RATE).toLong()

        /** Fmaj7 -> Am7 -> Dm7 -> Bbmaj7, three low-register voices each. */
        private val CHORDS = arrayOf(
            doubleArrayOf(87.31, 164.81, 220.00), // Fmaj7: F2 E3 A3
            doubleArrayOf(110.00, 196.00, 261.63), // Am7: A2 G3 C4
            doubleArrayOf(146.83, 174.61, 261.63), // Dm7: D3 F3 C4
            doubleArrayOf(116.54, 174.61, 220.00), // Bbmaj7: Bb2 F3 A3
        )
        private val VOICE_WEIGHTS = floatArrayOf(1.0f, 0.7f, 0.55f)
        private val VOICE_NORM = 2.0 * VOICE_WEIGHTS.sum()

        /** Convert [count] float samples to 16-bit little-endian mono PCM in [bytes]. */
        fun toPcm16(samples: FloatArray, count: Int, bytes: ByteArray) {
            for (i in 0 until count) {
                val value = (samples[i] * Short.MAX_VALUE)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[i * 2] = (value and 0xFF).toByte()
                bytes[i * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
            }
        }
    }
}
