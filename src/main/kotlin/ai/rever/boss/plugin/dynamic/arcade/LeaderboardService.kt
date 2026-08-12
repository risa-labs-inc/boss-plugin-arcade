package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.SupabaseDataProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class LeaderboardEntry(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("best_score") val bestScore: Int = 0,
    @SerialName("achieved_at") val achievedAt: String? = null,
)

/**
 * What a row in arcade_scores actually represents. Without this every row looks
 * like a finished run, which made 2048 — it syncs a new best every 2s mid-run —
 * report roughly 100x the play that happened.
 */
enum class ArcadeEvent(val wire: String) {
    /** An Arcade tab was opened. Reach, including players who never scored. */
    OPEN("open"),

    /** A run began. This is the one honest "how many games were played" signal. */
    START("start"),

    /** Mid-run personal-best sync. Counts for the leaderboard, never for runs. */
    PROGRESS("progress"),

    /** A run ended. Score may be 0 for a scoreless loss. */
    FINAL("final"),
}

/**
 * Thin client over the arcade_* Postgres functions (see supabase/arcade_schema.sql).
 * All calls are best-effort: a null provider or a failed RPC never breaks gameplay.
 *
 * Scoring submits are durable. A score is written to plugin storage before the
 * insert is attempted and cleared only once the insert succeeds, so a submit
 * killed in flight is replayed later instead of vanishing. That is not a
 * theoretical concern: the host watchdog restarts a plugin sandbox by cancelling
 * its coroutine scope, which kills every submit that happens to be running.
 *
 * Only [ArcadeEvent.FINAL] scores get that treatment. A lost OPEN/START/PROGRESS
 * row costs one imprecise telemetry point; persisting each of them would put a
 * storage write on 2048's every-2s path for no gain.
 */
class LeaderboardService(
    private val supabase: SupabaseDataProvider?,
    private val auth: AuthDataProvider?,
    private val storage: PluginStorageProvider? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingSubmits = CopyOnWriteArrayList<Job>()

    val isAvailable: Boolean
        get() = supabase != null

    val isSignedIn: Boolean
        get() = auth?.currentUser?.value != null

    val currentUserId: String?
        get() = auth?.currentUser?.value?.id

    /**
     * Fire-and-forget submit, tracked so readers can order themselves after it:
     * the leaderboard UI calls [awaitPendingSubmits] before fetching, otherwise
     * a fetch triggered right at game over can race the insert and miss the run.
     */
    fun submitAsync(
        scope: CoroutineScope,
        game: String,
        score: Int,
        event: ArcadeEvent = ArcadeEvent.FINAL,
    ): Job = track(
        scope.launch {
            if (event == ArcadeEvent.FINAL && score > 0) submitDurable(game, score)
            else submitWithRetry(game, score, event)
        }
    )

    /**
     * Record a scoreless telemetry event (a tab open, a run starting). Not
     * tracked as a pending submit: the leaderboard has no reason to wait on it.
     */
    fun recordEvent(scope: CoroutineScope, game: String, event: ArcadeEvent): Job =
        scope.launch { submitScore(game, 0, event) }

    suspend fun awaitPendingSubmits() {
        pendingSubmits.toList().joinAll()
    }

    /**
     * Replay every score an earlier session recorded but never delivered. Call
     * this when an Arcade tab opens: it is what recovers runs lost to a sandbox
     * restart, a crash, or the app quitting mid-submit.
     */
    fun flushPending(scope: CoroutineScope): Job? {
        val store = storage ?: return null
        val user = currentUserId ?: return null
        val prefix = "$PENDING_PREFIX."
        val suffix = ".$user"
        return track(
            scope.launch {
                store.getAllKeys()
                    .filter { it.startsWith(prefix) && it.endsWith(suffix) }
                    .forEach { key ->
                        val game = key.removePrefix(prefix).removeSuffix(suffix)
                        if (game.isNotEmpty()) deliverPending(game, store, key)
                    }
            }
        )
    }

    /**
     * Reconcile a locally stored best against the server and return the real
     * best. A local best ahead of the server means an earlier run never reached
     * the leaderboard, so it is (durably) submitted here rather than silently
     * living on as a personal best only the player can see.
     */
    suspend fun syncBest(game: String, localBest: Int): Int {
        val remote = runCatching { personalBest(game) }.getOrNull() ?: 0
        if (localBest > remote) submitDurable(game, localBest)
        return maxOf(localBest, remote)
    }

    /** Record one telemetry event. Returns false when unavailable or rejected. */
    suspend fun submitScore(
        game: String,
        score: Int,
        event: ArcadeEvent = ArcadeEvent.FINAL,
    ): Boolean {
        val provider = supabase ?: return false
        if (score < 0 || !isSignedIn) return false
        val params = """{"p_game":${JsonPrimitive(game)},"p_score":$score,""" +
            """"p_event":${JsonPrimitive(event.wire)}}"""
        return provider.rpc("arcade_submit_score", params).isSuccess
    }

    /**
     * Top N best-per-user scores for a game. [sinceIso] (ISO timestamp) limits
     * the window — pass [weekStartIso] for the weekly board, null for all-time.
     */
    suspend fun topScores(
        game: String,
        limit: Int = 10,
        sinceIso: String? = null,
    ): Result<List<LeaderboardEntry>> {
        val provider = supabase
            ?: return Result.failure(IllegalStateException("Leaderboard unavailable"))
        val since = if (sinceIso == null) "" else ""","p_since":${JsonPrimitive(sinceIso)}"""
        val params = """{"p_game":${JsonPrimitive(game)},"p_limit":$limit$since}"""
        return provider.rpc("arcade_leaderboard", params).mapCatching { body ->
            json.decodeFromString<List<LeaderboardEntry>>(body)
        }
    }

    companion object {
        /** Game key for plugin-wide rows (tab opens) that belong to no one game. */
        const val ARCADE_KEY = "arcade"

        private const val PENDING_PREFIX = "pending.score"
        private const val SUBMIT_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 1500L

        /** Monday 00:00 UTC of the current week — the weekly race window. */
        fun weekStartIso(): String {
            val monday = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            return monday.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()
        }
    }

    /** The signed-in user's all-time best for a game, or null when unknown. */
    suspend fun personalBest(game: String): Int? {
        val provider = supabase ?: return null
        if (!isSignedIn) return null
        val params = """{"p_game":${JsonPrimitive(game)}}"""
        return provider.rpc("arcade_personal_best", params).getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
            ?.toIntOrNull()
    }

    private fun track(job: Job): Job {
        pendingSubmits.add(job)
        job.invokeOnCompletion { pendingSubmits.remove(job) }
        return job
    }

    /** Persist first, then deliver, so nothing is lost if this coroutine dies. */
    private suspend fun submitDurable(game: String, score: Int) {
        if (score <= 0 || !isSignedIn) return
        val store = storage
        val key = pendingKey(game)
        if (store == null || key == null) {
            // No storage: best effort only, there is nothing to replay from.
            submitWithRetry(game, score)
            return
        }
        if (score > store.getInt(key, 0)) store.putInt(key, score)
        deliverPending(game, store, key)
    }

    private suspend fun deliverPending(game: String, store: PluginStorageProvider, key: String) {
        val pending = store.getInt(key, 0)
        if (pending <= 0) return
        if (!submitWithRetry(game, pending)) return
        // Clear the marker outside cancellation so a restart landing right here
        // cannot leave a delivered score pending forever. A better score
        // recorded while this was in flight stays pending for the next pass.
        withContext(NonCancellable) {
            if (store.getInt(key, 0) <= pending) store.remove(key)
        }
    }

    private suspend fun submitWithRetry(
        game: String,
        score: Int,
        event: ArcadeEvent = ArcadeEvent.FINAL,
    ): Boolean {
        repeat(SUBMIT_ATTEMPTS) { attempt ->
            if (submitScore(game, score, event)) return true
            if (attempt < SUBMIT_ATTEMPTS - 1) delay(RETRY_BACKOFF_MS * (attempt + 1))
        }
        return false
    }

    private fun pendingKey(game: String): String? =
        currentUserId?.let { "$PENDING_PREFIX.$game.$it" }
}
