package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.PluginStorageProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The ONE casino-ambience player, owned by [ArcadeServices] at plugin level so
 * several open Arcade tabs can never double-play: playback demand is a
 * refcount of "some tab is currently showing its HOME screen" tokens
 * ([homeShown]/[homeHidden], one token per tab, set semantics so a tab can
 * never be counted twice), and there is only ever one [SourceDataLine].
 *
 * Product rules encoded here:
 *  - DEFAULT OFF; the opt-in persists via the plugin storage provider under
 *    [KEY_ENABLED]. Ambience plays only while enabled AND a home screen shows.
 *  - ~3s fade-in when playback starts, ~0.9s fade-out on disable or when the
 *    last home screen navigates into a game (games are silent by design).
 *  - Every javax.sound call sits in runCatching: a missing/busy audio device
 *    flips [unavailable] (the toggle renders muted) and nothing ever throws
 *    into the host. javax.sound is JDK — no LinkageError risk on old consoles.
 *
 * The audio thread is a plain daemon thread, deliberately NOT a pluginScope
 * coroutine: the host watchdog swaps that scope without re-registering, and
 * audio pacing comes from the blocking line.write anyway. The scope is used
 * only for storage reads/writes, resolved per call (never cached).
 */
class CasinoAmbiencePlayer(
    private val storage: PluginStorageProvider?,
    private val scopeProvider: () -> CoroutineScope,
) {
    private val _enabled = MutableStateFlow(false)

    /** The user's opt-in. Drives the toggle's lit state. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _unavailable = MutableStateFlow(false)

    /** True after the audio device refused to open; cleared by a successful retry. */
    val unavailable: StateFlow<Boolean> = _unavailable.asStateFlow()

    private val lock = Any()
    private val homeTokens = mutableSetOf<Any>()
    private var thread: Thread? = null

    @Volatile
    private var targetGain = 0f

    @Volatile
    private var disposed = false

    /** Restore the persisted opt-in (default off). Failures leave the toggle off — degrade open. */
    fun loadPersisted() {
        val store = storage ?: return
        scopeProvider().launch {
            runCatching {
                if (store.getBoolean(KEY_ENABLED, false)) {
                    _enabled.value = true
                    refresh()
                }
            }
        }
    }

    fun toggle() {
        if (_unavailable.value) {
            // The muted state means "device failed" — a click is a retry, not an off.
            setEnabled(true)
        } else {
            setEnabled(!_enabled.value)
        }
    }

    fun setEnabled(value: Boolean) {
        if (value) _unavailable.value = false
        _enabled.value = value
        refresh()
        val store = storage ?: return
        scopeProvider().launch { runCatching { store.putBoolean(KEY_ENABLED, value) } }
    }

    /** A tab's HOME screen entered composition. [token] is the tab — idempotent per tab. */
    fun homeShown(token: Any) {
        synchronized(lock) { homeTokens += token }
        refresh()
    }

    /** That tab's HOME screen left composition (navigated into a game, or the tab closed). */
    fun homeHidden(token: Any) {
        synchronized(lock) { homeTokens -= token }
        refresh()
    }

    /** Plugin dispose: stop for good. The audio thread flushes and closes the line on its way out. */
    fun shutdown() {
        disposed = true
        targetGain = 0f
    }

    private fun refresh() {
        synchronized(lock) {
            val demand = !disposed && _enabled.value && !_unavailable.value && homeTokens.isNotEmpty()
            targetGain = if (demand) 1f else 0f
            if (demand && thread == null) {
                thread = Thread(::playbackLoop, "arcade-ambience").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    private fun playbackLoop() {
        val line = runCatching { openLine() }.getOrNull()
        if (line == null) {
            // Device absent or busy: show the muted/unavailable toggle, crash nothing.
            // The persisted opt-in is NOT overwritten, so the next launch retries.
            synchronized(lock) { thread = null }
            _unavailable.value = true
            _enabled.value = false
            return
        }
        _unavailable.value = false
        val synth = AmbienceSynth()
        val samples = FloatArray(BUFFER_SAMPLES)
        val bytes = ByteArray(BUFFER_SAMPLES * 2)
        var gain = 0f
        try {
            while (!disposed) {
                if (gain <= 0f && targetGain <= 0f) {
                    // Exit decision is taken under the same lock refresh() uses, so a
                    // demand that rises right now either flips targetGain before this
                    // check or finds thread == null and starts a fresh player.
                    val exit = synchronized(lock) {
                        if (targetGain <= 0f) {
                            thread = null
                            true
                        } else {
                            false
                        }
                    }
                    if (exit) break
                }
                synth.nextBuffer(samples)
                for (i in samples.indices) {
                    gain = when {
                        targetGain > gain -> (gain + ATTACK_STEP).coerceAtMost(targetGain)
                        targetGain < gain -> (gain - RELEASE_STEP).coerceAtLeast(targetGain)
                        else -> gain
                    }
                    // Squared gain: a linear ramp sounds like a jump at the quiet end.
                    samples[i] *= gain * gain
                }
                AmbienceSynth.toPcm16(samples, samples.size, bytes)
                val wrote = runCatching { line.write(bytes, 0, bytes.size) }.getOrNull() ?: break
                if (wrote < bytes.size) break // line was closed underneath us
            }
        } finally {
            synchronized(lock) { if (thread === Thread.currentThread()) thread = null }
            runCatching {
                line.stop()
                line.flush()
                line.close()
            }
        }
    }

    private fun openLine(): SourceDataLine {
        val format = AudioFormat(AmbienceSynth.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val line = AudioSystem.getSourceDataLine(format)
        line.open(format, LINE_BUFFER_BYTES)
        line.start()
        return line
    }

    companion object {
        /** Storage key, following the games' "best.*" flat-key convention. */
        const val KEY_ENABLED = "ambience.enabled"

        /** 100ms of synthesis per write; the blocking line.write paces the loop. */
        private const val BUFFER_SAMPLES = AmbienceSynth.SAMPLE_RATE / 10

        /** ~400ms device buffer: small enough to stop fast, big enough to never underrun. */
        private const val LINE_BUFFER_BYTES = BUFFER_SAMPLES * 2 * 4

        /** ~3s fade-in, ~0.9s fade-out (per-sample gain steps). */
        private const val ATTACK_STEP = 1f / (3.0f * AmbienceSynth.SAMPLE_RATE)
        private const val RELEASE_STEP = 1f / (0.9f * AmbienceSynth.SAMPLE_RATE)
    }
}

/**
 * The small round speaker toggle on the casino floor, next to the credits
 * chip: gold-dim ghost when off, lit gold while the ambience plays, muted
 * gray when the audio device is unavailable (a click retries).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AmbienceToggle(player: CasinoAmbiencePlayer) {
    val enabled by player.enabled.collectAsState()
    val unavailable by player.unavailable.collectAsState()
    val lit = enabled && !unavailable
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CasinoColors.PanelDeep)
                    .border(1.dp, CasinoColors.GoldDim.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    if (unavailable) "Audio unavailable — click to retry" else "Casino ambience",
                    fontSize = 11.sp,
                    color = CasinoColors.TextSoft,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (lit) CasinoColors.Gold.copy(alpha = 0.14f) else Color.Transparent)
                .border(
                    1.5.dp,
                    if (lit) CasinoColors.Gold.copy(alpha = 0.85f) else CasinoColors.GoldDim.copy(alpha = 0.7f),
                    CircleShape,
                )
                .plainClickable { player.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (lit) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                contentDescription = "Casino ambience",
                tint = when {
                    unavailable -> CasinoColors.TextMuted
                    lit -> CasinoColors.GoldBright
                    else -> CasinoColors.GoldDim
                },
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
