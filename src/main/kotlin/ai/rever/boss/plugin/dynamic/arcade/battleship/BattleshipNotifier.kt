package ai.rever.boss.plugin.dynamic.arcade.battleship

import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tells a player when a Battleship game is waiting on them — if, and only if,
 * they have asked to be told.
 *
 * OFF BY DEFAULT, deliberately. BOSS is a work tool and a Battleship turn has no
 * urgency whatsoever: turns are hours apart by design, and the badge on the
 * Arcade card carries the same information for free the next time the player
 * looks. So an unrequested toast spends someone's attention during real work to
 * save them nothing. The plugin API offers no ambient surface outside the Arcade
 * tab (no tray, no badge, no dashboard slot — SettingsProvider only opens the
 * settings dialog), so the only channel that reaches an unattended player is an
 * interrupt. The right answer to "the only tool I have is an interrupt" is to
 * not use it unless asked.
 *
 * This lives on the PLUGIN, not on the tab: register() runs when the plugin
 * loads, so once enabled it polls whether or not an Arcade tab is open. A
 * watcher inside the tab could only report challenges you had already gone
 * looking for.
 *
 * Known limitation: these are in-app toasts, so a nudge only lands while BOSS is
 * running. A challenge sent while someone has BOSS closed is seen at their next
 * launch, not at send time.
 *
 * The interesting problem here is not sending a toast, it is NOT sending one:
 * a poll loop that re-announces the same challenge every minute is worse than
 * silence. Every announcement is therefore keyed on the match's updated_at, so
 * a given state of a given match is announced at most once, and that record
 * persists to plugin storage so a restart does not replay yesterday's news.
 * On top of that, [QUIET_GAP_MS] enforces a floor between toasts so even an
 * opted-in player never gets a stream.
 */
class BattleshipNotifier(
    private val service: BattleshipService,
    private val notifications: NotificationProvider?,
    private val storage: PluginStorageProvider?,
    private val openBattleship: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val KEY_PREFIX = "bs.notified"
        const val KEY_ENABLED = "bs.notify.enabled"
        private const val KEY_LAST_TOAST = "bs.notify.lastToastAt"

        /**
         * Floor between toasts for an opted-in player. Long enough that a lively
         * back-and-forth cannot turn into a stream of interruptions; short
         * enough to still be useful to someone who wants to play.
         */
        private const val QUIET_GAP_MS = 30 * 60 * 1000L

        /** Read the opt-in. Absent means off — never notify by default. */
        suspend fun isEnabled(storage: PluginStorageProvider?): Boolean =
            storage?.getBoolean(KEY_ENABLED, false) ?: false

        suspend fun setEnabled(storage: PluginStorageProvider?, enabled: Boolean) {
            storage?.putBoolean(KEY_ENABLED, enabled)
        }

        /** Let the app finish starting before talking to the user. */
        private const val FIRST_CHECK_DELAY_MS = 20_000L
        private const val POLL_MS = 60_000L

        /**
         * Above this many at once, announce a count instead of a toast each. A
         * user's first-ever check can legitimately surface a big backlog — nine
         * separate toasts would be an ambush, not a nudge.
         */
        private const val INDIVIDUAL_TOAST_LIMIT = 2
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        delay(FIRST_CHECK_DELAY_MS)
        while (isActive) {
            runCatching { checkOnce() }
            delay(POLL_MS)
        }
    }

    /** One poll pass. Internal so tests can drive it without the 60s loop. */
    internal suspend fun checkOnce() {
        if (!service.isAvailable || !service.isSignedIn) return
        // Checked every pass, not once at startup: toggling the setting has to
        // take effect without restarting BOSS.
        if (!isEnabled(storage)) return
        val matches = service.myMatches().getOrNull() ?: return

        val actionable = matches.filter { it.myTurn || it.awaitingMyAnswer }
        val fresh = actionable.filter { isNewsFor(it) }

        // Prune first, so storage does not accumulate a key per match forever.
        prune(matches.map { it.matchId }.toSet())

        if (fresh.isEmpty()) return
        if (!withinQuietGap()) return
        announce(fresh)
        // Only mark as told once something was actually shown, or a toast
        // suppressed by the quiet gap would be silently lost for good.
        fresh.forEach { remember(it) }
        noteToastShown()
    }

    private suspend fun withinQuietGap(): Boolean {
        val store = storage ?: return true
        val last = store.getLong(KEY_LAST_TOAST, 0L)
        return now() - last >= QUIET_GAP_MS
    }

    private suspend fun noteToastShown() {
        storage?.putLong(KEY_LAST_TOAST, now())
    }

    private fun announce(fresh: List<MatchSummary>) {
        val provider = notifications ?: return
        if (fresh.size > INDIVIDUAL_TOAST_LIMIT) {
            val challenges = fresh.count { it.awaitingMyAnswer }
            val moves = fresh.size - challenges
            val parts = buildList {
                if (challenges > 0) add("$challenges new challenge${plural(challenges)}")
                if (moves > 0) add("$moves game${plural(moves)} waiting on your move")
            }
            provider.showToast(
                message = parts.joinToString(" and "),
                type = NotificationType.INFO,
                duration = NotificationDuration.LONG,
                title = "Battleship",
                actionLabel = "Open",
                onAction = openBattleship,
            )
            return
        }

        for (match in fresh) {
            val message = if (match.awaitingMyAnswer) {
                "${match.opponentName} challenged you to Battleship"
            } else {
                "${match.opponentName} fired back — your move"
            }
            provider.showToast(
                message = message,
                type = NotificationType.INFO,
                duration = NotificationDuration.LONG,
                title = if (match.awaitingMyAnswer) "Battleship challenge" else "Your move",
                actionLabel = if (match.awaitingMyAnswer) "Accept" else "Open",
                onAction = openBattleship,
            )
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    /**
     * True when this match is in a state we have not already announced.
     *
     * The stamp is updated_at rather than the match id: the opponent moving
     * again is genuinely new news, but the same pending challenge sitting there
     * for a week is not.
     */
    private suspend fun isNewsFor(match: MatchSummary): Boolean {
        val store = storage ?: return true
        val key = keyFor(match.matchId) ?: return true
        val stamp = match.updatedAt ?: match.status
        return store.getString(key, null) != stamp
    }

    private suspend fun remember(match: MatchSummary) {
        val store = storage ?: return
        val key = keyFor(match.matchId) ?: return
        store.putString(key, match.updatedAt ?: match.status)
    }

    private suspend fun prune(liveMatchIds: Set<String>) {
        val store = storage ?: return
        val user = service.currentUserId ?: return
        val prefix = "$KEY_PREFIX.$user."
        runCatching {
            store.getAllKeys()
                .filter { it.startsWith(prefix) }
                .filter { it.removePrefix(prefix) !in liveMatchIds }
                .forEach { store.remove(it) }
        }
    }

    // User-scoped: two accounts on one machine must not inherit each other's
    // "already told you" record.
    private fun keyFor(matchId: String): String? =
        service.currentUserId?.let { "$KEY_PREFIX.$it.$matchId" }
}
