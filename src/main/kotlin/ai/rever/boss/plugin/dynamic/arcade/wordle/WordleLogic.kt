package ai.rever.boss.plugin.dynamic.arcade.wordle

enum class LetterState { CORRECT, PRESENT, ABSENT }

/** One committed guess: the word and the per-letter verdicts. */
data class GuessRow(val word: String, val states: List<LetterState>)

/**
 * Pure Wordle rules: guess evaluation with the original's duplicate-letter
 * handling, keyboard hint aggregation, and the guess-count scoring.
 */
object WordleLogic {
    const val WORD_LENGTH = 5
    const val MAX_GUESSES = 6

    /**
     * Two-pass evaluation, exactly like the original: greens claim their answer
     * letters first, then yellows consume what's left, so a duplicate guess
     * letter never flags more copies than the answer actually has.
     */
    fun evaluate(guess: String, answer: String): List<LetterState> {
        val result = arrayOfNulls<LetterState>(WORD_LENGTH)
        val remaining = IntArray(26)
        for (i in 0 until WORD_LENGTH) {
            if (guess[i] == answer[i]) {
                result[i] = LetterState.CORRECT
            } else {
                remaining[answer[i] - 'A']++
            }
        }
        for (i in 0 until WORD_LENGTH) {
            if (result[i] != null) continue
            val letter = guess[i] - 'A'
            result[i] = if (remaining[letter] > 0) {
                remaining[letter]--
                LetterState.PRESENT
            } else {
                LetterState.ABSENT
            }
        }
        return result.map { it!! }
    }

    /** Best-known verdict per letter across all guesses, for keyboard coloring. */
    fun keyStates(rows: List<GuessRow>): Map<Char, LetterState> {
        val states = mutableMapOf<Char, LetterState>()
        for (row in rows) {
            row.word.forEachIndexed { i, letter ->
                val next = row.states[i]
                val prev = states[letter]
                if (prev == null || next.ordinal < prev.ordinal) states[letter] = next
            }
        }
        return states
    }

    /** Leaderboard points: 6 for a first-guess solve down to 1 for the sixth. */
    fun points(guessesUsed: Int): Int =
        (MAX_GUESSES + 1 - guessesUsed).coerceIn(1, MAX_GUESSES)
}
