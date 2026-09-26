package ai.rever.boss.plugin.dynamic.arcade.paarcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.SupabaseDataProvider
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The RPC that says whether the signed-in user may use the PA Arcade web app.
 * It lives with the web app's backend, not in this repo, and returns a bare
 * boolean for the calling user.
 */
internal const val PA_ARCADE_ACCESS_RPC = "pa_arcade_access"

/**
 * Leaderboard key the web app submits its overall score under in
 * `arcade_scores`: the player's career XP inside the app (best = current XP).
 * Per-game keys (`pa-<gameId>`) exist too, but are shown inside the web app
 * only.
 */
const val PA_ARCADE_LEADERBOARD_KEY = "pa-arcade"

/** Whether [game] is one of the web app's boards (`pa-arcade`, `pa-<gameId>`). */
internal fun isPaArcadeLeaderboardKey(game: String): Boolean = game.startsWith("pa-")

/**
 * The one parse of a [PA_ARCADE_ACCESS_RPC] result: the server's definitive
 * answer (the literal JSON `true` or `false`), or null when there is none (no
 * body, null, an error body, garbage).
 */
internal fun paArcadeAccessAnswer(rpcResult: String?): Boolean? =
    when (rpcResult?.trim()) {
        "true" -> true
        "false" -> false
        else -> null
    }

/** Access is granted only on a definitive yes; no answer means no access. */
internal fun paArcadeAccessGranted(rpcResult: String?): Boolean = paArcadeAccessAnswer(rpcResult) == true

/**
 * Whether the PA Arcade card (and its screen and leaderboard) is shown. The web
 * app admits only members of one organisation and enforces that itself; this
 * only stops everyone else from seeing a card they cannot use.
 *
 * **Fails closed**, unlike credits (which fail open): this is an access rule,
 * so [granted] is false while the answer is unknown and after any failure -
 * null provider, signed out, RPC missing, an unexpected body, or a throw of any
 * kind (LinkageError included). Nothing here throws into the host.
 *
 * A yes is cached for the plugin session, keyed on the user it was asked for,
 * so a later hiccup cannot pull the card (or a game in progress) out from under
 * a member. A no or a failure is not cached: the next [refresh] asks again, so
 * a newly added member or a transient failure recovers on the next tab open.
 * A different (or no) signed-in user reads as not granted until that user's
 * own answer arrives, so an account switch never inherits the previous
 * account's access. At most one RPC per [refresh]; nothing blocks rendering.
 *
 * Several tabs can refresh at once, so replies are ordered, not trusted:
 * - requests are numbered, and a definitive answer (`true`/`false`) is applied
 *   only if no NEWER request's answer has been applied already, so a stale
 *   reply can never overwrite a newer answer;
 * - a reply for an account that is no longer signed in is dropped without
 *   touching the cached answer (a newer account's answer stays intact);
 * - a failure (no answer) carries no information: it never downgrades a yes
 *   already cached for the same user and never outdates an older request's
 *   answer still in flight. It only leaves the card hidden when nothing is
 *   known. A definitive `false` for the same user does revoke a yes.
 */
class PaArcadeAccess(
    private val supabase: SupabaseDataProvider?,
    private val auth: AuthDataProvider?,
) {
    private val _granted = MutableStateFlow(false)

    /**
     * The cached answer, for recomposition. Gate navigation on [isGranted],
     * which also checks the answer belongs to the user signed in right now.
     */
    val granted: StateFlow<Boolean> = _granted

    /** The user id the cached answer belongs to; null = no answer cached. */
    @Volatile
    private var answeredFor: String? = null

    /** Numbers each request. */
    private val generation = AtomicLong(0)

    /** The number of the request whose definitive answer is cached. */
    private var appliedGeneration = 0L

    private fun currentUserId(): String? = runCatching { auth?.currentUser?.value?.id }.getOrNull()

    /** True only when the cached answer is a yes for the user signed in right now. */
    val isGranted: Boolean
        get() {
            val user = currentUserId() ?: return false
            return _granted.value && answeredFor == user
        }

    /** Whether the leaderboard for [game] may be read: `pa-` boards need access. */
    fun mayReadLeaderboard(game: String): Boolean = !isPaArcadeLeaderboardKey(game) || isGranted

    /**
     * Ask the server for the signed-in user, unless a yes for that same user is
     * already cached (then this is free). See the class doc for how replies
     * from concurrent calls are ordered.
     */
    suspend fun refresh() {
        val user = currentUserId()
        val provider = supabase
        if (user == null || provider == null) {
            synchronized(this) {
                appliedGeneration = generation.incrementAndGet() // outdates every reply in flight
                answeredFor = null
                _granted.value = false
            }
            return
        }
        if (answeredFor == user && _granted.value) return
        val request = generation.incrementAndGet()
        if (answeredFor != user) {
            // A different account: hide until its own answer arrives.
            answeredFor = null
            _granted.value = false
        }
        val raw = runCatching { provider.rpc(PA_ARCADE_ACCESS_RPC, "{}").getOrNull() }.getOrNull()
        synchronized(this) {
            // The account changed while the call was in flight (its own
            // refresh follows), or a newer request has already answered.
            if (currentUserId() != user || request < appliedGeneration) return
            val answer = paArcadeAccessAnswer(raw)
            if (answer == null) {
                // No answer: never downgrade a yes for this same user.
                if (answeredFor == user && _granted.value) return
                answeredFor = user
                _granted.value = false
                return
            }
            appliedGeneration = request
            answeredFor = user
            _granted.value = answer
        }
    }
}
