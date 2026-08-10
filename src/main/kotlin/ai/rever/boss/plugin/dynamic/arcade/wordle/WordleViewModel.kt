package ai.rever.boss.plugin.dynamic.arcade.wordle

import ai.rever.boss.plugin.dynamic.arcade.ArcadeServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Daily Wordle state machine. Everyone shares the same word (a hash of the UTC
 * epoch day), you get one board per day — guesses persist locally so closing
 * the tab never grants a retry — and solving scores 7 minus the guesses used.
 */
class WordleViewModel(
    private val scope: CoroutineScope,
    private val services: ArcadeServices,
) {
    companion object {
        const val GAME = "wordle"
        private const val TILE_FLIP_STAGGER_MS = 250L
        private const val TILE_FLIP_MS = 400L

        /** How long a committed row takes to finish flipping, for input gating. */
        const val REVEAL_TOTAL_MS =
            TILE_FLIP_STAGGER_MS * (WordleLogic.WORD_LENGTH - 1) + TILE_FLIP_MS
        private const val VEIL_DELAY_MS = 500L
    }

    enum class Phase { PLAYING, WON, LOST }

    data class UiState(
        val puzzleNumber: Long = 1,
        val rows: List<GuessRow> = emptyList(),
        val current: String = "",
        val phase: Phase = Phase.PLAYING,
        /** The answer, exposed only once the day's game is over. */
        val solution: String? = null,
        val veil: Boolean = false,
        val points: Int = 0,
        val best: Int = 0,
        val revealSeq: Int = 0,
        val shakeSeq: Int = 0,
        val message: String? = null,
        val messageSeq: Int = 0,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var epochDay = 0L
    private var answer = ""
    private var busy = false
    private var revealJob: Job? = null

    init {
        startDay(WordleWords.todayEpochDay())
        loadBest()
        restoreDaily()
    }

    /** Called from the screen's ticker so the board rolls over at UTC midnight. */
    fun rolloverIfNewDay() {
        val today = WordleWords.todayEpochDay()
        if (today != epochDay && !busy) startDay(today)
    }

    fun onKey(letter: Char) {
        val s = _state.value
        if (busy || s.phase != Phase.PLAYING) return
        if (s.current.length >= WordleLogic.WORD_LENGTH) return
        _state.value = s.copy(current = s.current + letter.uppercaseChar())
    }

    fun onBackspace() {
        val s = _state.value
        if (busy || s.phase != Phase.PLAYING) return
        if (s.current.isEmpty()) return
        _state.value = s.copy(current = s.current.dropLast(1))
    }

    fun onEnter() {
        submitGuess(_state.value.current)
    }

    /**
     * MCP entry point: type the agent's word onto the board and submit it.
     * Returns an error string when the guess can't be played, null on commit.
     */
    fun tryGuess(word: String): String? {
        rolloverIfNewDay()
        val s = _state.value
        if (s.phase != Phase.PLAYING) {
            return "Today's Wordle is already finished — a new word arrives at UTC midnight."
        }
        if (busy) return "The board is still revealing the previous guess — try again."
        val normalized = word.trim().uppercase()
        if (normalized.length != WordleLogic.WORD_LENGTH || normalized.any { it !in 'A'..'Z' }) {
            return "Guess must be exactly 5 letters."
        }
        if (!WordleWords.isValid(normalized)) return "'$normalized' is not in the word list."
        _state.value = s.copy(current = normalized)
        return if (submitGuess(normalized)) null else "Guess could not be played."
    }

    private fun submitGuess(word: String): Boolean {
        val s = _state.value
        if (busy || s.phase != Phase.PLAYING) return false
        if (word.length < WordleLogic.WORD_LENGTH) {
            reject(s, "Not enough letters")
            return false
        }
        if (!WordleWords.isValid(word)) {
            reject(s, "Not in word list")
            return false
        }

        val row = GuessRow(word, WordleLogic.evaluate(word, answer))
        val rows = s.rows + row
        _state.value = s.copy(
            rows = rows,
            current = "",
            revealSeq = s.revealSeq + 1,
        )
        persistDaily(rows)

        busy = true
        revealJob = scope.launch {
            delay(REVEAL_TOTAL_MS)
            busy = false
            when {
                word == answer -> finish(Phase.WON, rows.size)
                rows.size >= WordleLogic.MAX_GUESSES -> finish(Phase.LOST, rows.size)
            }
        }
        return true
    }

    private fun reject(s: UiState, message: String) {
        _state.value = s.copy(
            shakeSeq = s.shakeSeq + 1,
            message = message,
            messageSeq = s.messageSeq + 1,
        )
    }

    private fun finish(phase: Phase, guessesUsed: Int) {
        val points = if (phase == Phase.WON) WordleLogic.points(guessesUsed) else 0
        val s = _state.value
        val best = maxOf(s.best, points)
        _state.value = s.copy(phase = phase, solution = answer, points = points, best = best)
        if (points > 0) {
            if (best > s.best) persistBest(best)
            services.leaderboard.submitAsync(services.pluginScope, GAME, points)
        }
        scope.launch {
            delay(VEIL_DELAY_MS)
            val cur = _state.value
            if (cur.phase == phase) _state.value = cur.copy(veil = true)
        }
    }

    fun dismissVeil() {
        _state.value = _state.value.copy(veil = false)
    }

    fun showVeil() {
        if (_state.value.phase != Phase.PLAYING) {
            _state.value = _state.value.copy(veil = true)
        }
    }

    fun onDisposed() {
        // Nothing to flush: guesses persist as they land and points submit on solve.
    }

    /** The classic emoji share grid, available once the day's game is over. */
    fun shareText(): String? {
        val s = _state.value
        if (s.phase == Phase.PLAYING) return null
        val outcome = if (s.phase == Phase.WON) "${s.rows.size}" else "X"
        val grid = s.rows.joinToString("\n") { row ->
            row.states.joinToString("") { state ->
                when (state) {
                    LetterState.CORRECT -> "🟩"
                    LetterState.PRESENT -> "🟨"
                    LetterState.ABSENT -> "⬜"
                }
            }
        }
        return "Arcade Wordle #${s.puzzleNumber} $outcome/${WordleLogic.MAX_GUESSES}\n$grid"
    }

    /**
     * Compact JSON snapshot for the MCP tools. Feedback letters: G = correct
     * spot, Y = in the word elsewhere, B = absent. The solution stays hidden
     * until the game is over.
     */
    fun snapshotJson(): String {
        val s = _state.value
        val rows = s.rows.joinToString(",") { row ->
            val result = row.states.joinToString("") {
                when (it) {
                    LetterState.CORRECT -> "G"
                    LetterState.PRESENT -> "Y"
                    LetterState.ABSENT -> "B"
                }
            }
            """{"word":${JsonPrimitive(row.word)},"result":${JsonPrimitive(result)}}"""
        }
        val phase = when (s.phase) {
            Phase.PLAYING -> "playing"
            Phase.WON -> "won"
            Phase.LOST -> "lost"
        }
        val solution = s.solution?.let { JsonPrimitive(it).toString() } ?: "null"
        return """{"puzzle":${s.puzzleNumber},"phase":"$phase"""" +
            ""","guessesUsed":${s.rows.size},"maxGuesses":${WordleLogic.MAX_GUESSES}""" +
            ""","rows":[$rows],"points":${s.points},"solution":$solution}"""
    }

    private fun startDay(day: Long) {
        revealJob?.cancel()
        busy = false
        epochDay = day
        answer = WordleWords.answerForDay(day)
        _state.value = UiState(
            puzzleNumber = WordleWords.puzzleNumber(day),
            best = _state.value.best,
        )
    }

    // ---- persistence -------------------------------------------------------

    private fun userSuffix(): String = services.leaderboard.currentUserId ?: "local"

    private fun dayKey(): String = "wordle.day.${userSuffix()}"

    private fun bestKey(): String = "best.$GAME.${userSuffix()}"

    /** "epochDay|GUESS1,GUESS2" — replayed against the day's answer on restore. */
    private fun persistDaily(rows: List<GuessRow>) {
        val value = "$epochDay|" + rows.joinToString(",") { it.word }
        services.pluginScope.launch {
            services.storage?.putString(dayKey(), value)
        }
    }

    private fun restoreDaily() {
        scope.launch {
            val saved = services.storage?.getString(dayKey()) ?: return@launch
            val day = saved.substringBefore('|').toLongOrNull() ?: return@launch
            if (day != epochDay) return@launch
            val words = saved.substringAfter('|', "")
                .split(',')
                .filter { it.length == WordleLogic.WORD_LENGTH }
                .take(WordleLogic.MAX_GUESSES)
            if (words.isEmpty()) return@launch

            val s = _state.value
            // The user beat the restore to the board — never clobber live play.
            if (s.rows.isNotEmpty() || s.current.isNotEmpty() || busy) return@launch

            val rows = words.map { GuessRow(it, WordleLogic.evaluate(it, answer)) }
            val won = words.last() == answer
            val phase = when {
                won -> Phase.WON
                rows.size >= WordleLogic.MAX_GUESSES -> Phase.LOST
                else -> Phase.PLAYING
            }
            val points = if (won) WordleLogic.points(rows.size) else 0
            _state.value = s.copy(
                rows = rows,
                phase = phase,
                solution = if (phase == Phase.PLAYING) null else answer,
                points = points,
                best = maxOf(s.best, points),
                veil = phase != Phase.PLAYING,
            )
        }
    }

    private fun loadBest() {
        scope.launch {
            val local = services.storage?.getInt(bestKey(), 0) ?: 0
            val remote = runCatching { services.leaderboard.personalBest(GAME) }.getOrNull() ?: 0
            val best = maxOf(local, remote)
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
