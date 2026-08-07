package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.SupabaseDataProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
 * Thin client over the arcade_* Postgres functions (see supabase/arcade_schema.sql).
 * All calls are best-effort: a null provider or a failed RPC never breaks gameplay.
 */
class LeaderboardService(
    private val supabase: SupabaseDataProvider?,
    private val auth: AuthDataProvider?,
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
    fun submitAsync(scope: CoroutineScope, game: String, score: Int): Job {
        val job = scope.launch { submitScore(game, score) }
        pendingSubmits.add(job)
        job.invokeOnCompletion { pendingSubmits.remove(job) }
        return job
    }

    suspend fun awaitPendingSubmits() {
        pendingSubmits.toList().joinAll()
    }

    /** Record a finished run. Returns false when unavailable or rejected. */
    suspend fun submitScore(game: String, score: Int): Boolean {
        val provider = supabase ?: return false
        if (score <= 0 || !isSignedIn) return false
        val params = """{"p_game":${JsonPrimitive(game)},"p_score":$score}"""
        return provider.rpc("arcade_submit_score", params).isSuccess
    }

    /** Top N best-per-user scores for a game. */
    suspend fun topScores(game: String, limit: Int = 10): Result<List<LeaderboardEntry>> {
        val provider = supabase
            ?: return Result.failure(IllegalStateException("Leaderboard unavailable"))
        val params = """{"p_game":${JsonPrimitive(game)},"p_limit":$limit}"""
        return provider.rpc("arcade_leaderboard", params).mapCatching { body ->
            json.decodeFromString<List<LeaderboardEntry>>(body)
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
}
