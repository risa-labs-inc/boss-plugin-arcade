package ai.rever.boss.plugin.dynamic.arcade.skystack

import ai.rever.boss.plugin.dynamic.arcade.ArcadeEvent
import ai.rever.boss.plugin.dynamic.arcade.ArcadeServices
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Game phase and score bookkeeping around [SkyStackEngine]. */
class SkyStackViewModel(
    private val scope: CoroutineScope,
    private val services: ArcadeServices,
) {
    companion object {
        const val GAME = "sky-stack"
        private const val OVERLAY_DELAY_MS = 650L
    }

    enum class Phase { MENU, PLAYING, PAUSED, REVEALING, OVER }

    val engine = SkyStackEngine()
    private val sounds = SkyStackSoundPlayer(scope)

    var phase by mutableStateOf(Phase.MENU)
        private set
    var score by mutableStateOf(0)
        private set
    var best by mutableStateOf(0)
        private set
    var combo by mutableStateOf(0)
        private set
    var isNewBest by mutableStateOf(false)
        private set

    private var submittedScore = 0
    private var revealJob: Job? = null

    init {
        loadBest()
    }

    fun start() {
        submitScore(score)
        revealJob?.cancel()
        engine.reset()
        score = 0
        combo = 0
        isNewBest = false
        submittedScore = 0
        phase = Phase.PLAYING
        services.leaderboard.recordEvent(services.pluginScope, GAME, ArcadeEvent.START)
    }

    fun drop() {
        if (phase != Phase.PLAYING) return
        when (engine.drop()) {
            SkyStackEngine.DropResult.PLACED -> {
                score = engine.score
                combo = engine.combo
                if (engine.lastDropWasPerfect) {
                    sounds.perfect(engine.combo)
                } else {
                    sounds.trim(engine.level - 1)
                }
            }

            SkyStackEngine.DropResult.MISSED -> {
                sounds.gameOver()
                onGameOver()
            }
        }
    }

    fun onFrame(dt: Float) {
        when (phase) {
            Phase.PAUSED -> Unit
            Phase.PLAYING -> engine.update(dt, isPlaying = true)
            Phase.MENU, Phase.REVEALING, Phase.OVER -> engine.update(dt, isPlaying = false)
        }
    }

    fun togglePause() {
        phase = when (phase) {
            Phase.PLAYING -> Phase.PAUSED
            Phase.PAUSED -> Phase.PLAYING
            else -> return
        }
    }

    fun pauseIfPlaying() {
        if (phase == Phase.PLAYING) phase = Phase.PAUSED
    }

    fun onDisposed() {
        if (score > best) {
            best = score
            persistBest(score)
        }
        submitScore(score)
    }

    private fun onGameOver() {
        score = engine.score
        combo = engine.combo
        isNewBest = score > best
        if (isNewBest) {
            best = score
            persistBest(score)
        }
        submitScore(score)
        phase = Phase.REVEALING
        revealJob = scope.launch {
            delay(OVERLAY_DELAY_MS)
            if (phase == Phase.REVEALING) phase = Phase.OVER
        }
    }

    private fun submitScore(value: Int) {
        if (value <= submittedScore) return
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
