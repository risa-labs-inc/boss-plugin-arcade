package ai.rever.boss.plugin.dynamic.arcade.battleship

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.SupabaseDataProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class BattleshipPlayer(
    @SerialName("user_id") val userId: String = "",
    @SerialName("display_name") val displayName: String = "",
)

@Serializable
data class MatchSummary(
    @SerialName("match_id") val matchId: String = "",
    @SerialName("opponent_id") val opponentId: String = "",
    @SerialName("opponent_name") val opponentName: String = "",
    val status: String = "",
    @SerialName("i_am_challenger") val iAmChallenger: Boolean = false,
    @SerialName("my_turn") val myTurn: Boolean = false,
    @SerialName("i_won") val iWon: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val isPending: Boolean get() = status == "pending"
    val isActive: Boolean get() = status == "active"
    val isFinished: Boolean get() = status == "finished"

    /** A challenge sent to you that you have not answered yet. */
    val awaitingMyAnswer: Boolean get() = isPending && !iAmChallenger
}

@Serializable
data class ShotRecord(val cell: Int = -1, val result: String = "miss")

@Serializable
data class FleetEntry(val id: String = "", val cells: List<Int> = emptyList())

@Serializable
data class MatchDetail(
    @SerialName("match_id") val matchId: String = "",
    val status: String = "",
    @SerialName("my_turn") val myTurn: Boolean = false,
    @SerialName("i_won") val iWon: Boolean = false,
    val finished: Boolean = false,
    @SerialName("i_am_challenger") val iAmChallenger: Boolean = false,
    @SerialName("opponent_id") val opponentId: String = "",
    @SerialName("opponent_name") val opponentName: String = "",
    @SerialName("my_fleet") val myFleet: List<FleetEntry> = emptyList(),
    @SerialName("my_shots") val myShots: List<ShotRecord> = emptyList(),
    @SerialName("their_shots") val theirShots: List<ShotRecord> = emptyList(),
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("their_last_shot_at") val theirLastShotAt: String? = null,
    /** Last time the opponent did anything in the Arcade — "are they around?". */
    @SerialName("opponent_last_seen") val opponentLastSeen: String? = null,
)

@Serializable
data class FireOutcome(
    val result: String = "miss",
    val sunk: String? = null,
    val won: Boolean = false,
    val cell: Int = -1,
) {
    val isHit: Boolean get() = result == "hit" || result == "sunk"
}

@Serializable
data class Standing(
    @SerialName("user_id") val userId: String = "",
    @SerialName("display_name") val displayName: String = "",
    val wins: Int = 0,
    val losses: Int = 0,
    val played: Int = 0,
)

/**
 * Client for the arcade_bs_* RPCs (see supabase/arcade_battleship.sql).
 *
 * Every call returns a Result and never throws into the UI — same contract as
 * LeaderboardService, because the providers from PluginContext are nullable and
 * the plugin has to stay usable signed out.
 *
 * Note what is NOT here: there is no "read the opponent's fleet" call, by
 * design. The server will not answer one. Hit/miss comes back only as the
 * return value of [fire].
 */
class BattleshipService(
    private val supabase: SupabaseDataProvider?,
    private val auth: AuthDataProvider?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val isAvailable: Boolean get() = supabase != null

    val isSignedIn: Boolean get() = auth?.currentUser?.value != null

    val currentUserId: String? get() = auth?.currentUser?.value?.id

    suspend fun players(limit: Int = 50): Result<List<BattleshipPlayer>> =
        call("arcade_players", """{"p_limit":$limit}""")

    suspend fun myMatches(): Result<List<MatchSummary>> =
        call("arcade_bs_my_matches", "{}")

    suspend fun standings(limit: Int = 20): Result<List<Standing>> =
        call("arcade_bs_standings", """{"p_limit":$limit}""")

    suspend fun matchDetail(matchId: String): Result<MatchDetail> =
        callOne("arcade_bs_match_detail", """{"p_match":${JsonPrimitive(matchId)}}""")

    /** Challenge [opponentId], placing [ships] in the same call. Returns the match id. */
    suspend fun challenge(
        opponentId: String,
        ships: List<BattleshipLogic.Ship>,
    ): Result<String> {
        val params = """{"p_opponent":${JsonPrimitive(opponentId)},""" +
            """"p_ships":${BattleshipLogic.fleetToJson(ships)}}"""
        return rpc("arcade_bs_challenge", params).map { it.trim().trim('"') }
    }

    suspend fun accept(matchId: String, ships: List<BattleshipLogic.Ship>): Result<Unit> {
        val params = """{"p_match":${JsonPrimitive(matchId)},""" +
            """"p_ships":${BattleshipLogic.fleetToJson(ships)}}"""
        return rpc("arcade_bs_accept", params).map { }
    }

    suspend fun decline(matchId: String): Result<Unit> =
        rpc("arcade_bs_decline", """{"p_match":${JsonPrimitive(matchId)}}""").map { }

    suspend fun fire(matchId: String, cell: Int): Result<FireOutcome> =
        callOne("arcade_bs_fire", """{"p_match":${JsonPrimitive(matchId)},"p_cell":$cell}""")

    private suspend fun rpc(function: String, params: String): Result<String> {
        val provider = supabase
            ?: return Result.failure(IllegalStateException("Multiplayer is unavailable"))
        if (!isSignedIn) {
            return Result.failure(IllegalStateException("Sign in to play against your team"))
        }
        return provider.rpc(function, params)
    }

    private suspend inline fun <reified T> call(function: String, params: String): Result<List<T>> =
        rpc(function, params).mapCatching { body ->
            if (body.isBlank() || body == "null") emptyList() else json.decodeFromString(body)
        }

    private suspend inline fun <reified T> callOne(function: String, params: String): Result<T> =
        rpc(function, params).mapCatching { body -> json.decodeFromString(body) }
}
