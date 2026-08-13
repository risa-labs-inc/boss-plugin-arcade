package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipNotifier
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The hard part of a notifier is silence. A poll loop that re-announces the
 * same challenge every 60 seconds is worse than no notifications at all, so
 * most of this file is about NOT toasting.
 */
class BattleshipNotifierTest {

    private val user = "4ece2a4a-7d11-4735-a193-9ed915a88ff1"

    private fun match(
        id: String,
        opponent: String,
        status: String = "pending",
        iAmChallenger: Boolean = false,
        myTurn: Boolean = false,
        updatedAt: String = "2026-08-13T10:00:00+00:00",
    ) = """{"match_id":"$id","opponent_id":"opp-$id","opponent_name":"$opponent",
        "status":"$status","i_am_challenger":$iAmChallenger,"my_turn":$myTurn,
        "i_won":false,"updated_at":"$updatedAt"}"""

    /** Milliseconds; tests move this by hand instead of sleeping. */
    private var clock = 1_000_000_000L

    private fun notifier(
        storage: FakeStorage,
        notes: FakeNotifications,
        signedIn: Boolean = true,
        matchesJson: () -> String,
    ): BattleshipNotifier {
        val supabase = FakeSupabase { name, _ ->
            if (name == "arcade_bs_my_matches") Result.success("[${matchesJson()}]")
            else Result.success("[]")
        }
        val service = BattleshipService(supabase, FakeAuth(if (signedIn) user else null))
        return BattleshipNotifier(service, notes, storage, openBattleship = {}, now = { clock })
    }

    /** Storage with the opt-in already granted, which most tests assume. */
    private fun optedIn(): FakeStorage =
        FakeStorage(mutableMapOf(BattleshipNotifier.KEY_ENABLED to "true"))

    /** Push the clock past the quiet gap so a second toast is allowed. */
    private fun advancePastQuietGap() {
        clock += 31 * 60 * 1000L
    }

    @Test
    fun announcesAChallengeThatIsWaitingOnYou() = runTest {
        val notes = FakeNotifications()
        val n = notifier(optedIn(), notes) { match("m1", "saiprasad") }

        n.checkOnce()

        val toast = notes.toasts.single()
        assertEquals("Battleship challenge", toast.title)
        assertTrue(toast.message.contains("saiprasad"), toast.message)
        assertEquals("Accept", toast.actionLabel)
    }

    @Test
    fun doesNotAnnounceTheSameChallengeTwice() = runTest {
        val notes = FakeNotifications()
        val storage = optedIn()
        val n = notifier(storage, notes) { match("m1", "saiprasad") }

        n.checkOnce()
        n.checkOnce()
        n.checkOnce()

        assertEquals(1, notes.toasts.size, "a pending challenge is news exactly once")
    }

    @Test
    fun aRestartDoesNotReplayYesterdaysChallenge() = runTest {
        // Same storage, brand new notifier — this is what a relaunch looks like.
        val storage = optedIn()
        val first = FakeNotifications()
        notifier(storage, first) { match("m1", "saiprasad") }.checkOnce()
        assertEquals(1, first.toasts.size)

        val second = FakeNotifications()
        advancePastQuietGap()
        notifier(storage, second) { match("m1", "saiprasad") }.checkOnce()

        assertTrue(second.toasts.isEmpty(), "storage should survive the restart")
    }

    @Test
    fun theOpponentMovingAgainIsGenuinelyNewNews() = runTest {
        val storage = optedIn()
        val notes = FakeNotifications()
        var stamp = "2026-08-13T10:00:00+00:00"
        val n = notifier(storage, notes) {
            match("m1", "jinisha", status = "active", myTurn = true, updatedAt = stamp)
        }

        n.checkOnce()
        n.checkOnce()
        assertEquals(1, notes.toasts.size)

        // They fire back: updated_at moves, so this is a new state to announce.
        stamp = "2026-08-13T11:30:00+00:00"
        advancePastQuietGap()
        n.checkOnce()

        assertEquals(2, notes.toasts.size)
        assertEquals("Your move", notes.toasts.last().title)
    }

    @Test
    fun staysSilentWhenItIsTheOpponentsTurn() = runTest {
        val notes = FakeNotifications()
        val n = notifier(optedIn(), notes) {
            match("m1", "jinisha", status = "active", myTurn = false)
        }

        n.checkOnce()

        assertTrue(notes.toasts.isEmpty(), "nothing is waiting on this player")
    }

    @Test
    fun aChallengeYouSentIsNotSomethingToNotifyYouAbout() = runTest {
        val notes = FakeNotifications()
        val n = notifier(optedIn(), notes) {
            match("m1", "kulraj", status = "pending", iAmChallenger = true)
        }

        n.checkOnce()

        assertTrue(notes.toasts.isEmpty())
    }

    @Test
    fun aBacklogIsSummarisedRatherThanFiredOneByOne() = runTest {
        // Exactly the real situation: someone challenged nine people at once, so
        // the first check on their side must not produce nine separate toasts.
        val notes = FakeNotifications()
        val n = notifier(optedIn(), notes) {
            (1..9).joinToString(",") { match("m$it", "player$it") }
        }

        n.checkOnce()

        val toast = notes.toasts.single()
        assertEquals("Battleship", toast.title)
        assertTrue(toast.message.contains("9 new challenges"), toast.message)
    }

    @Test
    fun mixedBacklogNamesBothKinds() = runTest {
        val notes = FakeNotifications()
        val n = notifier(optedIn(), notes) {
            listOf(
                match("m1", "a"),
                match("m2", "b"),
                match("m3", "c", status = "active", myTurn = true),
            ).joinToString(",")
        }

        n.checkOnce()

        val message = notes.toasts.single().message
        assertTrue(message.contains("2 new challenges"), message)
        assertTrue(message.contains("1 game waiting on your move"), message)
    }

    @Test
    fun saysNothingUntilThePlayerHasOptedIn() = runTest {
        // The default. BOSS is a work tool and a Battleship turn is not urgent,
        // so an unrequested toast spends attention to save nothing.
        val notes = FakeNotifications()
        val n = notifier(FakeStorage(), notes) { match("m1", "saiprasad") }

        n.checkOnce()

        assertTrue(notes.toasts.isEmpty(), "off by default")
    }

    @Test
    fun optingInLaterWorksWithoutARestart() = runTest {
        val storage = FakeStorage()
        val notes = FakeNotifications()
        val n = notifier(storage, notes) { match("m1", "saiprasad") }

        n.checkOnce()
        assertTrue(notes.toasts.isEmpty())

        BattleshipNotifier.setEnabled(storage, true)
        n.checkOnce()

        assertEquals(1, notes.toasts.size)
    }

    @Test
    fun turningItOffAgainSilencesIt() = runTest {
        val storage = optedIn()
        val notes = FakeNotifications()
        val n = notifier(storage, notes) { match("m1", "a") }
        n.checkOnce()
        assertEquals(1, notes.toasts.size)

        BattleshipNotifier.setEnabled(storage, false)
        advancePastQuietGap()
        val n2 = notifier(storage, notes) { match("m2", "b") }
        n2.checkOnce()

        assertEquals(1, notes.toasts.size, "no further toasts once switched off")
    }

    @Test
    fun aQuietGapStopsALivelyGameBecomingAStreamOfInterruptions() = runTest {
        val storage = optedIn()
        val notes = FakeNotifications()
        var stamp = "2026-08-13T10:00:00+00:00"
        val n = notifier(storage, notes) {
            match("m1", "jinisha", status = "active", myTurn = true, updatedAt = stamp)
        }

        n.checkOnce()
        assertEquals(1, notes.toasts.size)

        // Three more moves land inside the quiet window: all suppressed.
        repeat(3) {
            stamp = "2026-08-13T10:0${it + 1}:00+00:00"
            clock += 60_000L
            n.checkOnce()
        }
        assertEquals(1, notes.toasts.size, "still just the one toast")

        // Past the window, the latest state is announced — not the backlog.
        advancePastQuietGap()
        n.checkOnce()
        assertEquals(2, notes.toasts.size)
    }

    @Test
    fun newsSuppressedByTheQuietGapIsNotLostForever() = runTest {
        // The bug this guards: marking a match "already told you" while its toast
        // was being suppressed would silently swallow it for good.
        val storage = optedIn()
        val notes = FakeNotifications()
        val n = notifier(storage, notes) { match("m1", "a") }
        n.checkOnce()
        assertEquals(1, notes.toasts.size)

        val second = FakeNotifications()
        val n2 = notifier(storage, second) { match("m2", "b") }
        n2.checkOnce()
        assertTrue(second.toasts.isEmpty(), "inside the quiet gap")

        advancePastQuietGap()
        n2.checkOnce()

        assertEquals(1, second.toasts.size, "m2 must still get announced later")
        assertTrue(second.toasts.single().message.contains("b"))
    }

    @Test
    fun signedOutPlayersAreNeverToasted() = runTest {
        val notes = FakeNotifications()
        val n = notifier(FakeStorage(), notes, signedIn = false) { match("m1", "saiprasad") }

        n.checkOnce()

        assertTrue(notes.toasts.isEmpty())
    }

    @Test
    fun recordsForVanishedMatchesAreCleanedUp() = runTest {
        val storage = optedIn()
        val notes = FakeNotifications()
        var live = "m1"
        val n = notifier(storage, notes) { match(live, "saiprasad") }

        n.checkOnce()
        assertEquals(1, storage.raw.keys.count { it.startsWith("bs.notified.") })

        // m1 is gone from the server's list (declined, or long finished).
        live = "m2"
        advancePastQuietGap()
        n.checkOnce()

        val keys = storage.raw.keys.filter { it.startsWith("bs.notified.") }
        assertEquals(listOf("bs.notified.$user.m2"), keys, "stale key should be pruned")
    }

    @Test
    fun oneAccountsRecordDoesNotSilenceAnother() = runTest {
        val storage = optedIn()
        val first = FakeNotifications()
        notifier(storage, first) { match("m1", "saiprasad") }.checkOnce()

        // A different signed-in user on the same machine must still be told.
        val other = "9c1f0000-0000-4000-8000-000000000002"
        val supabase = FakeSupabase { name, _ ->
            if (name == "arcade_bs_my_matches") Result.success("[${match("m1", "saiprasad")}]")
            else Result.success("[]")
        }
        val notes = FakeNotifications()
        advancePastQuietGap()
        BattleshipNotifier(
            BattleshipService(supabase, FakeAuth(other)), notes, storage, openBattleship = {},
            now = { clock },
        ).checkOnce()

        assertEquals(1, notes.toasts.size)
    }
}

internal class FakeNotifications : NotificationProvider {
    data class Toast(
        val message: String,
        val type: NotificationType,
        val duration: NotificationDuration,
        val title: String?,
        val actionLabel: String?,
    )

    val toasts = mutableListOf<Toast>()

    override fun showToast(
        message: String,
        type: NotificationType,
        duration: NotificationDuration,
        title: String?,
        actionLabel: String?,
        onAction: (() -> Unit)?,
    ): String {
        toasts.add(Toast(message, type, duration, title, actionLabel))
        return "toast-${toasts.size}"
    }

    override fun dismiss(notificationId: String) = Unit

    override fun dismissAll() = Unit
}
