package ai.rever.boss.plugin.dynamic.arcade.paarcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.SupabaseDataProvider
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

/**
 * The one parse of a [PA_ARCADE_ACCESS_RPC] result: true only for the literal
 * JSON `true`. Anything else (false, null, an error body, garbage, no body)
 * means no access.
 */
internal fun paArcadeAccessGranted(rpcResult: String?): Boolean = rpcResult?.trim() == "true"

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

    private fun currentUserId(): String? = runCatching { auth?.currentUser?.value?.id }.getOrNull()

    /** True only when the cached answer is a yes for the user signed in right now. */
    val isGranted: Boolean
        get() {
            val user = currentUserId() ?: return false
            return _granted.value && answeredFor == user
        }

    /**
     * Ask the server for the signed-in user, unless a yes for that same user is
     * already cached (then this is free).
     */
    suspend fun refresh() {
        val user = currentUserId()
        val provider = supabase
        if (user == null || provider == null) {
            answeredFor = null
            _granted.value = false
            return
        }
        if (answeredFor == user && _granted.value) return
        if (answeredFor != user) {
            // A different account: hide until its own answer arrives.
            answeredFor = null
            _granted.value = false
        }
        val raw = runCatching { provider.rpc(PA_ARCADE_ACCESS_RPC, "{}").getOrNull() }.getOrNull()
        // The user may have changed while the call was in flight; an answer
        // only ever applies to the account it was asked for. Drop it and stay
        // hidden; the sign-in change triggers its own refresh.
        if (currentUserId() != user) {
            answeredFor = null
            _granted.value = false
            return
        }
        answeredFor = user
        _granted.value = paArcadeAccessGranted(raw)
    }
}
