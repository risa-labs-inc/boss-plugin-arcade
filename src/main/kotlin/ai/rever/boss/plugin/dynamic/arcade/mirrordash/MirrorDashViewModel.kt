package ai.rever.boss.plugin.dynamic.arcade.mirrordash

import ai.rever.boss.plugin.dynamic.arcade.ArcadeEvent
import ai.rever.boss.plugin.dynamic.arcade.ArcadeServices
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase machine + score bookkeeping around [MirrorDashEngine]. Runs are always
 * finite (every run ends in a crash), so the leaderboard submit happens at
 * game over — plus on dispose, in case the tab closes mid-run.
 */
class MirrorDashViewModel(
    private val scope: CoroutineScope,
    private val services: ArcadeServices,
) {
    companion object {
        const val GAME = "mirror-dash"

        /** How long the game-over card is safe from a still-mashing spacebar. */
        private const val RESTART_LOCKOUT_MS = 500L
    }

    enum class Phase { MENU, PLAYING, PAUSED, OVER }

    val engine = MirrorDashEngine()

    var phase by mutableStateOf(Phase.MENU)
        private set
    var score by mutableStateOf(0)
        private set
    var best by mutableStateOf(0)
        private set
    var mult by mutableStateOf(1)
        private set
    var isNewBest by mutableStateOf(false)
        private set

    private var submittedScore = 0
    private var overSince = 0L

    init {
        loadBest()
    }

    fun start() {
        engine.reset()
        score = 0
        mult = 1
        isNewBest = false
        // Per-run, not per-session: without this a run scoring below an earlier
        // one in the same sitting is silently never recorded.
        submittedScore = 0
        phase = Phase.PLAYING
        services.leaderboard.recordEvent(services.pluginScope, GAME, ArcadeEvent.START)
    }

    fun reverse() {
        if (phase == Phase.PLAYING) engine.reverse()
    }

    /**
     * The one-button input, for players who never touch the mouse. Spacebar
     * routes here rather than straight to [reverse] so it means something in
     * every phase — reverse() alone silently no-ops outside PLAYING, which made
     * the key look dead on the start and game-over cards.
     */
    fun primaryAction() {
        when (phase) {
            Phase.MENU -> start()
            // Mirror Dash is a mash-the-key game, so the player is usually still
            // hammering space at the instant they die. Without this window the
            // run restarts before the score card is on screen for even a frame.
            Phase.OVER -> if (System.currentTimeMillis() - overSince >= RESTART_LOCKOUT_MS) start()
            Phase.PAUSED -> phase = Phase.PLAYING
            Phase.PLAYING -> engine.reverse()
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

    /** Advance the simulation one frame; called from the render loop. */
    fun onFrame(dt: Float) {
        if (phase != Phase.PLAYING) return
        val survived = engine.update(dt)
        score = engine.displayScore()
        mult = engine.mult
        if (!survived) onGameOver()
    }

    fun onDisposed() {
        submitScore(score)
    }

    private fun onGameOver() {
        phase = Phase.OVER
        overSince = System.currentTimeMillis()
        isNewBest = score > best
        if (isNewBest) {
            best = score
            persistBest(score)
        }
        submitScore(score)
    }

    private fun submitScore(score: Int) {
        if (score <= submittedScore) return
        submittedScore = score
        services.leaderboard.submitAsync(services.pluginScope, GAME, score)
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
