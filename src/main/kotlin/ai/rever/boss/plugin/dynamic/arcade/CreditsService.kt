package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.SupabaseDataProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** A top-up request the player has open, as returned by arcade_my_credits. */
@Serializable
data class PendingCreditRequest(
    val id: String = "",
    val amount: Long = 0,
    val note: String? = null,
    val createdAt: String? = null,
)

/** The player's server-side credits view (arcade_my_credits). */
@Serializable
data class CreditsSnapshot(
    val balance: Long = 0,
    val weeklyFloor: Long = 0,
    val pendingRequest: PendingCreditRequest? = null,
)

/** One row of the admin queue (arcade_admin_requests). */
@Serializable
data class CreditRequestRow(
    val id: String = "",
    val userId: String? = null,
    val displayName: String? = null,
    val amount: Long = 0,
    val note: String? = null,
    val status: String = "pending",
    val createdAt: String? = null,
    val balance: Long = 0,
)

@Serializable
private data class ChargeResponse(
    val ok: Boolean = false,
    val balance: Long = 0,
    val cost: Long = 0,
    val error: String? = null,
)

@Serializable
private data class RequestResponse(
    val ok: Boolean = false,
    val requestId: String? = null,
    val error: String? = null,
)

@Serializable
private data class ResolveResponse(
    val ok: Boolean = false,
    val newBalance: Long? = null,
    val error: String? = null,
)

/** Why a run was refused: shown as the in-game "out of credits" card. */
data class BlockedRun(val game: String, val balance: Long, val cost: Long)

/**
 * Client for the Arcade credits economy (arcade_my_credits / arcade_charge_run /
 * arcade_request_credits / arcade_is_admin / arcade_admin_requests /
 * arcade_admin_resolve). Balances live server-side; this class only caches.
 *
 * Charging policy — cached pre-check + optimistic background charge:
 * [tryStartRun] never touches the network on the start path. It gates on the
 * cached [snapshot] (so starting a run adds zero latency), optimistically
 * deducts the cost locally, and fires the real arcade_charge_run in the
 * background. A charge that comes back insufficient just leaves the true (low)
 * balance in the cache, so it is the NEXT run that blocks — the current one was
 * already allowed to begin.
 *
 * Degrade open, always: no supabase, signed out, schema not deployed, offline —
 * any of it leaves [snapshot] null, which means "credits UI hidden, every game
 * free". A credits outage must never lock anyone out of playing, and per the
 * plugin's backward-compat rule nothing here may throw into the host: every
 * entry point swallows all Throwables (LinkageError included) into null/Result.
 */
class CreditsService(
    private val supabase: SupabaseDataProvider?,
    private val auth: AuthDataProvider?,
    private val scopeProvider: () -> CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Server-confirmed cost per game, learned from charge responses. */
    private val serverCosts = ConcurrentHashMap<String, Long>()

    private val _snapshot = MutableStateFlow<CreditsSnapshot?>(null)

    /** Null = credits unknown/unavailable: hide all credits UI, play free. */
    val snapshot: StateFlow<CreditsSnapshot?> = _snapshot

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin

    private val _blockedRun = MutableStateFlow<BlockedRun?>(null)

    /** Set when a run start was refused; the tab renders it as the blocking card. */
    val blockedRun: StateFlow<BlockedRun?> = _blockedRun

    private val isSignedIn: Boolean
        get() = auth?.currentUser?.value != null

    /**
     * The cost a run of [game] is believed to have: server-confirmed once a
     * charge has answered, the embedded default until then. Display + pre-check
     * only — the server is the authority on what it actually deducts.
     */
    fun costOf(game: String): Long = serverCosts[game] ?: DEFAULT_COST

    /** "100 [glyph]" for the game cards, or null while credits UI is hidden. */
    fun costLabel(game: String): String? =
        if (_snapshot.value == null) null else "%,d $GLYPH".format(costOf(game))

    /** Re-read balance/pending state. Any failure degrades to free play. */
    suspend fun refresh() {
        val provider = supabase
        if (provider == null || !isSignedIn) {
            _snapshot.value = null
            return
        }
        _snapshot.value = runCatching {
            json.decodeFromString<CreditsSnapshot>(
                provider.rpc("arcade_my_credits", "{}").getOrThrow(),
            )
        }.getOrNull()
    }

    /** Re-read whether this player sees the admin queue. Errors mean no. */
    suspend fun refreshAdmin() {
        val provider = supabase ?: return
        if (!isSignedIn) return
        _isAdmin.value = runCatching {
            provider.rpc("arcade_is_admin", "{}").getOrThrow().trim() == "true"
        }.getOrDefault(false)
    }

    /**
     * Gate + charge for one run, latency-free (see the class doc for the
     * policy). Returns false when the run must not begin, with [blockedRun]
     * set for the UI. Battleship gates and charges separately because its
     * "run" only starts once the server accepts the fleet — use [canStartRun]
     * then [chargeRun].
     */
    fun tryStartRun(game: String): Boolean {
        if (!canStartRun(game)) return false
        chargeRun(game)
        return true
    }

    /** The cached-balance gate alone: true = a run may begin (or credits are off). */
    fun canStartRun(game: String): Boolean {
        val snap = _snapshot.value ?: return true // degraded/unknown → free play
        val cost = costOf(game)
        if (snap.balance < cost) {
            _blockedRun.value = BlockedRun(game, snap.balance, cost)
            return false
        }
        _blockedRun.value = null
        return true
    }

    /**
     * Fire the real charge in the background (plugin scope: survives tab
     * close) and deduct optimistically so rapid restarts can't outrun the
     * server. The response's balance/cost overwrite the guess.
     */
    fun chargeRun(game: String): Job? {
        val provider = supabase ?: return null
        if (_snapshot.value == null) return null // credits off → free play
        _snapshot.update { it?.copy(balance = it.balance - costOf(game)) }
        return scopeProvider().launch {
            runCatching {
                val params = """{"p_game":${JsonPrimitive(game)}}"""
                val response = json.decodeFromString<ChargeResponse>(
                    provider.rpc("arcade_charge_run", params).getOrThrow(),
                )
                serverCosts[game] = response.cost
                _snapshot.update { it?.copy(balance = response.balance) }
                // ok:false = insufficient_credits: the run already started
                // (optimistic); the low balance now cached blocks the next one.
            }.onFailure {
                // RPC missing (schema not deployed) or offline: free play.
                _snapshot.value = null
            }
        }
    }

    /** Clear the blocking card (player chose "not now"). */
    fun dismissBlockedRun() {
        _blockedRun.value = null
    }

    /**
     * Ask the admins for a top-up. On success the snapshot is re-read so the
     * chip shows the pending state. Failure returns a wire error string
     * ("request_pending") or a throwable message for the dialog to translate.
     */
    suspend fun requestCredits(amount: Long, note: String): Result<Unit> = runCatching {
        val provider = supabase ?: error("unavailable")
        val params = """{"p_amount":$amount,"p_note":${JsonPrimitive(note)}}"""
        val response = json.decodeFromString<RequestResponse>(
            provider.rpc("arcade_request_credits", params).getOrThrow(),
        )
        if (!response.ok) error(response.error ?: "request_failed")
        refresh()
    }

    /** The admin queue, pending requests first, newest within each group. */
    suspend fun adminRequests(): Result<List<CreditRequestRow>> = runCatching {
        val provider = supabase ?: error("unavailable")
        json.decodeFromString<List<CreditRequestRow>>(
            provider.rpc("arcade_admin_requests", "{}").getOrThrow(),
        ).sortedWith(
            compareBy<CreditRequestRow> { it.status != "pending" }
                .thenByDescending { it.createdAt ?: "" },
        )
    }

    /** Approve (optionally overriding the amount) or deny one request. */
    suspend fun adminResolve(
        requestId: String,
        approve: Boolean,
        amount: Long? = null,
    ): Result<Unit> = runCatching {
        val provider = supabase ?: error("unavailable")
        val params = """{"p_request_id":${JsonPrimitive(requestId)},""" +
            """"p_approve":$approve,"p_amount":${amount ?: "null"}}"""
        val response = json.decodeFromString<ResolveResponse>(
            provider.rpc("arcade_admin_resolve", params).getOrThrow(),
        )
        if (!response.ok) error(response.error ?: "resolve_failed")
    }

    companion object {
        /** Play-money glyph. Deliberately not a real currency symbol. */
        const val GLYPH = "✦"

        /**
         * Display/pre-check cost until the server has answered a charge for
         * that game. The SQL cost table is the truth; this only has to be
         * close enough that the first pre-check isn't nonsense.
         */
        const val DEFAULT_COST = 100L
    }
}
