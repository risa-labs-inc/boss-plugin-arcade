package ai.rever.boss.plugin.dynamic.arcade.typingsprint

import ai.rever.boss.plugin.dynamic.arcade.ArcadeEvent
import ai.rever.boss.plugin.dynamic.arcade.ArcadeServices
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 60-second typing sprint. The clock starts on the first keystroke; finished
 * passages bank their stats and a fresh one loads. Score = WPM x accuracy,
 * where WPM counts only correctly typed characters (5 chars = 1 word).
 */
class TypingSprintViewModel(
    private val scope: CoroutineScope,
    private val services: ArcadeServices,
) {
    companion object {
        const val GAME = "typing-sprint"
        const val DURATION_MS = 60_000L
    }

    enum class Phase { IDLE, RUNNING, DONE }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var passage by mutableStateOf(TypingPassages.random())
        private set
    var typed by mutableStateOf("")
        private set
    var timeLeftMs by mutableStateOf(DURATION_MS)
        private set
    var wpm by mutableStateOf(0)
        private set
    var accuracy by mutableStateOf(100)
        private set
    var score by mutableStateOf(0)
        private set
    var best by mutableStateOf(0)
        private set

    private var bankedCorrect = 0
    private var bankedTyped = 0
    private var startedAt = 0L
    private var timerJob: Job? = null
    private var submittedScore = 0

    init {
        loadBest()
    }

    fun onTyped(newText: String) {
        if (phase == Phase.DONE) return
        // A jump of many chars at once is a paste, not typing.
        if (newText.length > typed.length + 3) return
        if (phase == Phase.IDLE && newText.isNotEmpty()) start()
        typed = newText.take(passage.length)
        if (typed.length == passage.length) {
            bankedCorrect += correctIn(typed, passage)
            bankedTyped += typed.length
            passage = TypingPassages.random(exclude = passage)
            typed = ""
        }
        refreshLiveStats()
    }

    fun restart() {
        timerJob?.cancel()
        phase = Phase.IDLE
        passage = TypingPassages.random()
        typed = ""
        timeLeftMs = DURATION_MS
        wpm = 0
        accuracy = 100
        score = 0
        bankedCorrect = 0
        bankedTyped = 0
    }

    /** A run abandoned mid-sprint (tab closed) still counts for what it was. */
    fun onDisposed() {
        if (phase == Phase.RUNNING) finish()
    }

    private fun start() {
        phase = Phase.RUNNING
        // Per-run, not per-session: without this a sprint slower than an earlier
        // one in the same sitting is silently never recorded.
        submittedScore = 0
        startedAt = System.currentTimeMillis()
        services.leaderboard.recordEvent(services.pluginScope, GAME, ArcadeEvent.START)
        timerJob = scope.launch {
            while (true) {
                delay(100)
                val left = DURATION_MS - (System.currentTimeMillis() - startedAt)
                timeLeftMs = left.coerceAtLeast(0)
                refreshLiveStats()
                if (left <= 0) {
                    finish()
                    break
                }
            }
        }
    }

    private fun refreshLiveStats() {
        if (phase != Phase.RUNNING) return
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1000)
        val correct = bankedCorrect + correctIn(typed, passage)
        val total = bankedTyped + typed.length
        accuracy = if (total == 0) 100 else correct * 100 / total
        wpm = ((correct / 5.0) / (elapsedMs / 60000.0)).toInt()
    }

    private fun finish() {
        if (phase == Phase.DONE) return
        timerJob?.cancel()
        val elapsedMs = (System.currentTimeMillis() - startedAt)
            .coerceIn(1000L, DURATION_MS)
        phase = Phase.DONE
        val correct = bankedCorrect + correctIn(typed, passage)
        val total = bankedTyped + typed.length
        val acc = if (total == 0) 0.0 else correct.toDouble() / total
        wpm = ((correct / 5.0) / (elapsedMs / 60000.0)).roundToInt()
        accuracy = (acc * 100).roundToInt()
        score = (wpm * acc).roundToInt()
        if (score > best) {
            best = score
            persistBest(score)
        }
        submit(score)
    }

    private fun correctIn(text: String, target: String): Int =
        text.zip(target).count { (a, b) -> a == b }

    private fun submit(value: Int) {
        if (value <= submittedScore || value <= 0) return
        submittedScore = value
        services.leaderboard.submitAsync(services.pluginScope, GAME, value)
    }

    private fun bestKey(): String = "best.$GAME." + (services.leaderboard.currentUserId ?: "local")

    private fun loadBest() {
        scope.launch {
            val local = services.storage?.getInt(bestKey(), 0) ?: 0
            // syncBest also pushes a local best the server never received.
            val loaded = runCatching { services.leaderboard.syncBest(GAME, local) }.getOrNull() ?: local
            if (loaded > best) best = loaded
        }
    }

    private fun persistBest(value: Int) {
        services.pluginScope.launch {
            services.storage?.putInt(bestKey(), value)
        }
    }
}
