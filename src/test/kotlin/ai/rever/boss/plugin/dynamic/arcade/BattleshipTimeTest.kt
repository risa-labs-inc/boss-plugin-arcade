package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.battleship.relativeTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BattleshipTimeTest {

    private val now: Instant = Instant.parse("2026-08-13T18:00:00Z")

    private fun ago(seconds: Long) =
        relativeTime(now.minusSeconds(seconds).toString(), now)

    @Test
    fun formatsTheAsyncWaitAtEveryScale() {
        assertEquals("just now", ago(5))
        assertEquals("just now", ago(44))
        assertEquals("a minute ago", ago(60))
        assertEquals("5 min ago", ago(300))
        assertEquals("an hour ago", ago(3600))
        assertEquals("5 hours ago", ago(5 * 3600))
        assertEquals("yesterday", ago(30 * 3600))
        assertEquals("3 days ago", ago(3 * 86_400))
    }

    @Test
    fun readsThePostgresOffsetFormatTheRpcActuallyReturns() {
        // This is verbatim what arcade_bs_match_detail returned from prod; the
        // trailing "+00:00" offset is why this parses as OffsetDateTime.
        // 14m59.4s truncates to 14, matching how the hour and day buckets round.
        assertEquals(
            "14 min ago",
            relativeTime("2026-08-13T17:45:00.585032+00:00", now),
        )
    }

    @Test
    fun aTimestampSlightlyInTheFutureReadsAsJustNowNotNegative() {
        // Server and client clocks drift; "in -2 minutes" would be worse than
        // rounding to the present.
        assertEquals("just now", relativeTime(now.plusSeconds(90).toString(), now))
    }

    @Test
    fun missingOrJunkTimestampsProduceNothingRatherThanCrashing() {
        assertNull(relativeTime(null, now))
        assertNull(relativeTime("", now))
        assertNull(relativeTime("not a timestamp", now))
    }
}
