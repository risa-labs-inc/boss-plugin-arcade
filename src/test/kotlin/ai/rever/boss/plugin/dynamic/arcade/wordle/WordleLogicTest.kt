package ai.rever.boss.plugin.dynamic.arcade.wordle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordleLogicTest {

    private fun eval(guess: String, answer: String): String =
        WordleLogic.evaluate(guess, answer).joinToString("") {
            when (it) {
                LetterState.CORRECT -> "G"
                LetterState.PRESENT -> "Y"
                LetterState.ABSENT -> "B"
            }
        }

    @Test
    fun `all correct`() {
        assertEquals("GGGGG", eval("CRANE", "CRANE"))
    }

    @Test
    fun `greens claim answer letters before yellows`() {
        // The second B in ABBEY is already green; BABES's first B takes the
        // remaining copy, and S finds nothing.
        assertEquals("YYGGB", eval("BABES", "ABBEY"))
    }

    @Test
    fun `duplicate guess letters beyond the answer's count go gray`() {
        // CIGAR has one A: only the first A in MAMMA may light up.
        assertEquals("BYBBB", eval("MAMMA", "CIGAR"))
    }

    @Test
    fun `mixed presents and corrects`() {
        assertEquals("YYGYB", eval("PAPER", "APPLE"))
    }

    @Test
    fun `keyboard hints keep the best verdict per letter`() {
        val rows = listOf(
            GuessRow("PAPER", WordleLogic.evaluate("PAPER", "APPLE")),
            GuessRow("APPLE", WordleLogic.evaluate("APPLE", "APPLE")),
        )
        val keys = WordleLogic.keyStates(rows)
        assertEquals(LetterState.CORRECT, keys['P'])
        assertEquals(LetterState.CORRECT, keys['A'])
        assertEquals(LetterState.ABSENT, keys['R'])
    }

    @Test
    fun `points scale from six for an ace to one for a squeak`() {
        assertEquals(6, WordleLogic.points(1))
        assertEquals(1, WordleLogic.points(6))
    }

    @Test
    fun `daily word is deterministic, valid, and changes over days`() {
        assertEquals(2315, WordleWords.answers.size)
        assertTrue(WordleWords.answers.all { it.length == 5 && it.all { c -> c in 'A'..'Z' } })

        val day = 20_675L
        val word = WordleWords.answerForDay(day)
        assertEquals(word, WordleWords.answerForDay(day))
        assertTrue(WordleWords.isValid(word))
        // Not a proof of good distribution, just that the index moves.
        assertTrue((0L until 30L).map { WordleWords.answerForDay(day + it) }.toSet().size > 20)
    }

    @Test
    fun `guess dictionary accepts obscure words and rejects junk`() {
        assertTrue(WordleWords.isValid("crane"))
        assertTrue(WordleWords.isValid("AAHED"))
        assertTrue(!WordleWords.isValid("QQQQQ"))
        assertTrue(!WordleWords.isValid("CRAN"))
    }
}
