package ai.rever.boss.plugin.dynamic.arcade.game2048

import ai.rever.boss.plugin.dynamic.arcade.ArcadeEvent
import ai.rever.boss.plugin.dynamic.arcade.ArcadeServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Game state machine. Mirrors the two-phase timing of the original HTML game:
 * a 105 ms slide window (input blocked), then settle (merge pop + spawn), then
 * optional win/over veil after the same delays the CSS animations used.
 */
class Game2048ViewModel(
    private val scope: CoroutineScope,
    private val services: ArcadeServices,
) {
    companion object {
        const val GAME = "2048"
        private const val SLIDE_MS = 105L
        private const val WIN_VEIL_DELAY_MS = 380L
        private const val OVER_VEIL_DELAY_MS = 420L
    }

    enum class Veil { WIN, OVER }

    /**
     * One-shot animation cues for the last state change. `seq` increments on
     * every emission so the UI can key LaunchedEffects off it.
     */
    data class MoveFx(
        val seq: Int = 0,
        val gained: Int = 0,
        val mergedIds: Set<Long> = emptySet(),
        val spawnedIds: Set<Long> = emptySet(),
    )

    data class UiState(
        val tiles: List<TileData> = emptyList(),
        val score: Int = 0,
        val best: Int = 0,
        val over: Boolean = false,
        val won: Boolean = false,
        val veil: Veil? = null,
        val canUndo: Boolean = false,
        val fx: MoveFx = MoveFx(),
    )

    private data class Snapshot(val cells: List<List<Int>>, val score: Int, val won: Boolean)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var nextId = 1L
    private var busy = false
    private var keepPlaying = false
    private var undoSnapshot: Snapshot? = null
    private var submittedScore = 0
    private var settleJob: Job? = null
    private var pendingBestSubmit: Job? = null
    private var bestToSubmit = 0

    init {
        newGame()
        loadBest()
    }

    fun move(dr: Int, dc: Int): Boolean {
        val s = _state.value
        if (busy || s.over || s.veil != null) return false
        val outcome = Game2048Logic.computeMove(s.tiles, dr, dc)
        if (!outcome.moved) return false

        busy = true
        undoSnapshot = snapshotOf(s)
        val newScore = s.score + outcome.gained
        val newBest = maxOf(s.best, newScore)
        if (newBest > s.best) {
            persistBest(newBest)
            scheduleBestSubmit(newScore)
        }

        // Phase 1: slide. Absorbed tiles glide onto their survivor.
        _state.value = s.copy(
            tiles = outcome.tiles,
            score = newScore,
            best = newBest,
            canUndo = true,
            fx = MoveFx(seq = s.fx.seq + 1, gained = outcome.gained),
        )

        settleJob = scope.launch {
            delay(SLIDE_MS)
            var tiles = Game2048Logic.settle(outcome)
            val justWon = !_state.value.won && tiles.any { it.value == Game2048Logic.WIN_VALUE }
            val spawned = Game2048Logic.spawn(tiles, nextId++)
            if (spawned != null) tiles = tiles + spawned
            val over = !Game2048Logic.canMove(tiles)

            val cur = _state.value
            _state.value = cur.copy(
                tiles = tiles,
                won = cur.won || justWon,
                over = over,
                fx = MoveFx(
                    seq = cur.fx.seq + 1,
                    mergedIds = outcome.mergedIds,
                    spawnedIds = setOfNotNull(spawned?.id),
                ),
            )
            busy = false

            if (over) {
                submitRun()
                delay(OVER_VEIL_DELAY_MS)
                showVeil(Veil.OVER)
            } else if (justWon && !keepPlaying) {
                submitRun()
                delay(WIN_VEIL_DELAY_MS)
                showVeil(Veil.WIN)
            }
        }
        return true
    }

    /** Compact JSON snapshot for the MCP tools (0 = empty cell). */
    fun snapshotJson(): String {
        val s = _state.value
        val grid = List(Game2048Logic.SIZE) { r ->
            List(Game2048Logic.SIZE) { c ->
                s.tiles.firstOrNull { it.row == r && it.col == c }?.value ?: 0
            }
        }
        val veil = when (s.veil) {
            Veil.WIN -> "\"win\""
            Veil.OVER -> "\"over\""
            null -> "null"
        }
        return "{\"board\":$grid,\"score\":${s.score},\"best\":${s.best}," +
            "\"over\":${s.over},\"won\":${s.won},\"veil\":$veil}"
    }

    fun undo() {
        if (busy) return
        val snap = undoSnapshot ?: return
        undoSnapshot = null
        val tiles = buildList {
            snap.cells.forEachIndexed { r, row ->
                row.forEachIndexed { c, value ->
                    if (value > 0) add(TileData(id = nextId++, value = value, row = r, col = c))
                }
            }
        }
        val cur = _state.value
        _state.value = cur.copy(
            tiles = tiles,
            score = snap.score,
            won = snap.won,
            over = false,
            veil = null,
            canUndo = false,
            fx = MoveFx(seq = cur.fx.seq + 1),
        )
    }

    fun newGame() {
        // A run abandoned via "New game" still counts — record it before wiping.
        submitRun()
        settleJob?.cancel()
        busy = false
        keepPlaying = false
        undoSnapshot = null
        submittedScore = 0
        bestToSubmit = 0
        services.leaderboard.recordEvent(services.pluginScope, GAME, ArcadeEvent.START)
        var tiles = emptyList<TileData>()
        repeat(2) { Game2048Logic.spawn(tiles, nextId++)?.let { tiles = tiles + it } }
        val cur = _state.value
        _state.value = UiState(
            tiles = tiles,
            best = cur.best,
            fx = MoveFx(seq = cur.fx.seq + 1, spawnedIds = tiles.map { it.id }.toSet()),
        )
    }

    fun keepGoing() {
        keepPlaying = true
        _state.value = _state.value.copy(veil = null)
    }

    fun dismissVeilToNewGame() {
        newGame()
    }

    /** Called when the hosting tab closes so a mid-run score still counts. */
    fun onDisposed() {
        submitRun()
    }

    private fun showVeil(veil: Veil) {
        val s = _state.value
        // A new game or undo may have raced the delay; only show when still relevant.
        val stillRelevant = when (veil) {
            Veil.OVER -> s.over
            Veil.WIN -> s.won && !keepPlaying
        }
        if (stillRelevant) _state.value = s.copy(veil = veil)
    }

    private fun submitRun() {
        pendingBestSubmit?.cancel()
        val score = _state.value.score
        if (score <= submittedScore) return
        submittedScore = score
        // Submit on the plugin scope: it survives the tab being closed.
        services.leaderboard.submitAsync(services.pluginScope, GAME, score)
    }

    /**
     * Sync a new personal best while the run is still going, so the leaderboard
     * never depends on reaching game over (or on the app shutting down cleanly).
     *
     * Throttled, NOT debounced: the first new best arms a submit 2s out, and
     * further improvements inside the window just raise the value it will send.
     * (A debounce that resets per improvement never fires for an active player
     * who moves every second — the original "leaderboard never updates" bug.)
     */
    private fun scheduleBestSubmit(score: Int) {
        bestToSubmit = maxOf(bestToSubmit, score)
        if (pendingBestSubmit?.isActive == true) return
        pendingBestSubmit = services.pluginScope.launch {
            delay(2000)
            val value = bestToSubmit
            if (value > submittedScore) {
                submittedScore = value
                services.leaderboard.submitAsync(
                    services.pluginScope, GAME, value, ArcadeEvent.PROGRESS,
                )
            }
        }
    }

    private fun snapshotOf(s: UiState): Snapshot {
        val cells = List(Game2048Logic.SIZE) { r ->
            List(Game2048Logic.SIZE) { c ->
                s.tiles.firstOrNull { it.row == r && it.col == c }?.value ?: 0
            }
        }
        return Snapshot(cells, s.score, s.won)
    }

    private fun bestKey(): String = "best.$GAME." + (services.leaderboard.currentUserId ?: "local")

    private fun loadBest() {
        scope.launch {
            val local = services.storage?.getInt(bestKey(), 0) ?: 0
            // syncBest also pushes a local best the server never received.
            val best = runCatching { services.leaderboard.syncBest(GAME, local) }.getOrNull() ?: local
            if (best > _state.value.best) {
                _state.value = _state.value.copy(best = best)
            }
        }
    }

    private fun persistBest(best: Int) {
        services.pluginScope.launch {
            services.storage?.putInt(bestKey(), best)
        }
    }
}
