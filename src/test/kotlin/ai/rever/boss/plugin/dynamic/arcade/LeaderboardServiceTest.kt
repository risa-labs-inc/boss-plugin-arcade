package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.api.UserData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

/**
 * Covers the durability contract that keeps a finished run from vanishing when
 * the host watchdog restarts the plugin sandbox mid-submit (it cancels the
 * plugin scope, killing whatever submit was in flight).
 */
class LeaderboardServiceTest {

    private val user = "4ece2a4a-7d11-4735-a193-9ed915a88ff1"
    private val pendingKey = "pending.score.2048.$user"

    @Test
    fun scoreIsPersistedBeforeTheInsertIsAttempted() = runTest {
        val storage = FakeStorage()
        // Asserted from inside the RPC: by the time the insert runs, the score
        // must already be on disk, or a crash here would lose it.
        var pendingDuringRpc: Int? = null
        val supabase = FakeSupabase { _, _ ->
            pendingDuringRpc = storage.raw[pendingKey]?.toInt()
            Result.success("")
        }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        service.submitAsync(this, "2048", 54716).join()

        assertEquals(54716, pendingDuringRpc)
        assertNull(storage.raw[pendingKey], "delivered score should not stay pending")
    }

    @Test
    fun failedSubmitLeavesTheScorePendingForALaterSession() = runTest {
        val storage = FakeStorage()
        val supabase = FakeSupabase { _, _ -> Result.failure(RuntimeException("offline")) }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        service.submitAsync(this, "2048", 54716).join()

        assertEquals("54716", storage.raw[pendingKey])
        assertEquals(3, supabase.calls.size, "should exhaust its retries")
    }

    @Test
    fun retrySucceedsAfterTransientFailures() = runTest {
        val storage = FakeStorage()
        var attempt = 0
        val supabase = FakeSupabase { _, _ ->
            attempt++
            if (attempt < 3) Result.failure(RuntimeException("flaky")) else Result.success("")
        }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        service.submitAsync(this, "2048", 54716).join()

        assertEquals(3, attempt)
        assertNull(storage.raw[pendingKey])
    }

    @Test
    fun flushPendingReplaysAMarkerLeftByAKilledSession() = runTest {
        // Exactly the state a sandbox restart leaves behind: recorded, never sent.
        val storage = FakeStorage(mutableMapOf(pendingKey to "54716"))
        val supabase = FakeSupabase { _, _ -> Result.success("") }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        service.flushPending(this)?.join()

        assertEquals(1, supabase.calls.size)
        assertTrue(supabase.calls.single().second.contains("\"p_score\":54716"))
        assertTrue(supabase.calls.single().second.contains("\"p_game\":\"2048\""))
        assertNull(storage.raw[pendingKey])
    }

    @Test
    fun flushPendingIgnoresAnotherPlayersMarkers() = runTest {
        val storage = FakeStorage(mutableMapOf("pending.score.2048.someone-else" to "99999"))
        val supabase = FakeSupabase { _, _ -> Result.success("") }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        service.flushPending(this)?.join()

        assertTrue(supabase.calls.isEmpty())
        assertEquals("99999", storage.raw["pending.score.2048.someone-else"])
    }

    @Test
    fun aBetterScoreRecordedMidFlightStaysPending() = runTest {
        val storage = FakeStorage()
        val supabase = FakeSupabase { name, _ ->
            // A new personal best lands while the earlier one is still in flight.
            if (name == "arcade_submit_score") storage.raw[pendingKey] = "60000"
            Result.success("")
        }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        service.submitAsync(this, "2048", 54716).join()

        assertEquals("60000", storage.raw[pendingKey], "must not clear a score it never sent")
    }

    @Test
    fun syncBestSubmitsALocalBestTheServerNeverReceived() = runTest {
        val storage = FakeStorage()
        val supabase = FakeSupabase { name, _ ->
            if (name == "arcade_personal_best") Result.success("49488") else Result.success("")
        }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        val best = service.syncBest("2048", 54716)

        assertEquals(54716, best)
        val submits = supabase.calls.filter { it.first == "arcade_submit_score" }
        assertEquals(1, submits.size)
        assertTrue(submits.single().second.contains("\"p_score\":54716"))
    }

    @Test
    fun syncBestStaysQuietWhenTheServerIsAlreadyAhead() = runTest {
        val storage = FakeStorage()
        val supabase = FakeSupabase { name, _ ->
            if (name == "arcade_personal_best") Result.success("60000") else Result.success("")
        }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        val best = service.syncBest("2048", 54716)

        assertEquals(60000, best)
        assertTrue(supabase.calls.none { it.first == "arcade_submit_score" })
    }

    @Test
    fun signedOutPlayersRecordNothing() = runTest {
        val storage = FakeStorage()
        val supabase = FakeSupabase { _, _ -> Result.success("") }
        val service = LeaderboardService(supabase, FakeAuth(null), storage)

        service.submitAsync(this, "2048", 54716).join()

        assertTrue(supabase.calls.isEmpty())
        assertTrue(storage.raw.isEmpty())
    }

    @Test
    fun submittingWithoutStorageStillReachesTheServer() = runTest {
        val supabase = FakeSupabase { _, _ -> Result.success("") }
        val service = LeaderboardService(supabase, FakeAuth(user), storage = null)

        service.submitAsync(this, "2048", 54716).join()

        assertEquals(1, supabase.calls.size)
    }

    @Test
    fun submitsCarryTheirEventSoRunsCanBeToldFromScoreSyncs() = runTest {
        val storage = FakeStorage()
        val supabase = FakeSupabase { _, _ -> Result.success("") }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        service.submitAsync(this, "2048", 1200, ArcadeEvent.PROGRESS).join()
        service.submitAsync(this, "2048", 54716, ArcadeEvent.FINAL).join()

        val params = supabase.calls.map { it.second }
        assertTrue(params[0].contains("\"p_event\":\"progress\""), params[0])
        assertTrue(params[1].contains("\"p_event\":\"final\""), params[1])
    }

    @Test
    fun scorelessEventsAreRecordedButNeverPersistedForReplay() = runTest {
        val storage = FakeStorage()
        val supabase = FakeSupabase { _, _ -> Result.success("") }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        service.recordEvent(this, LeaderboardService.ARCADE_KEY, ArcadeEvent.OPEN).join()

        val (function, params) = supabase.calls.single()
        assertEquals("arcade_submit_score", function)
        assertTrue(params.contains("\"p_event\":\"open\""), params)
        assertTrue(params.contains("\"p_score\":0"), params)
        // A tab open is not a score: nothing to replay if it never lands.
        assertTrue(storage.raw.isEmpty())
    }

    @Test
    fun onlyFinalScoresTakeTheDurablePath() = runTest {
        val storage = FakeStorage()
        val supabase = FakeSupabase { _, _ -> Result.failure(RuntimeException("offline")) }
        val service = LeaderboardService(supabase, FakeAuth(user), storage)

        service.submitAsync(this, "2048", 1200, ArcadeEvent.PROGRESS).join()
        assertTrue(storage.raw.isEmpty())

        service.submitAsync(this, "2048", 54716, ArcadeEvent.FINAL).join()
        assertEquals(1, storage.raw.size)
    }
}

private class FakeSupabase(
    private val handler: (String, String) -> Result<String>,
) : SupabaseDataProvider {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun rpc(functionName: String, params: String): Result<String> {
        calls.add(functionName to params)
        return handler(functionName, params)
    }

    override suspend fun select(
        table: String,
        columns: String,
        filters: List<QueryFilter>,
        range: QueryRange?,
    ): Result<String> = Result.success("[]")
}

private class FakeAuth(userId: String?) : AuthDataProvider {
    override val currentUser: StateFlow<UserData?> = MutableStateFlow(
        userId?.let {
            UserData(
                id = it,
                email = "nilesh@risalabs.ai",
                displayName = "nilesh",
                avatarUrl = null,
                roles = emptyList(),
                createdAt = 0L,
            )
        }
    )
    override val isAdmin: StateFlow<Boolean> = MutableStateFlow(false)
    override val userPermissions: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override fun hasPermission(permission: String): Boolean = false
    override fun hasAnyPermission(vararg permissions: String): Boolean = false
}

private class FakeStorage(
    val raw: MutableMap<String, String> = mutableMapOf(),
) : PluginStorageProvider {
    override fun getPluginId(): String = ARCADE_PLUGIN_ID

    override suspend fun putString(key: String, value: String) { raw[key] = value }
    override suspend fun getString(key: String, defaultValue: String?): String? =
        raw[key] ?: defaultValue

    override suspend fun putInt(key: String, value: Int) { raw[key] = value.toString() }
    override suspend fun getInt(key: String, defaultValue: Int): Int =
        raw[key]?.toIntOrNull() ?: defaultValue

    override suspend fun putLong(key: String, value: Long) { raw[key] = value.toString() }
    override suspend fun getLong(key: String, defaultValue: Long): Long =
        raw[key]?.toLongOrNull() ?: defaultValue

    override suspend fun putBoolean(key: String, value: Boolean) { raw[key] = value.toString() }
    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        raw[key]?.toBooleanStrictOrNull() ?: defaultValue

    override suspend fun putFloat(key: String, value: Float) { raw[key] = value.toString() }
    override suspend fun getFloat(key: String, defaultValue: Float): Float =
        raw[key]?.toFloatOrNull() ?: defaultValue

    override suspend fun putJson(key: String, jsonValue: String) { raw[key] = jsonValue }
    override suspend fun getJson(key: String): String? = raw[key]

    override suspend fun contains(key: String): Boolean = raw.containsKey(key)
    override suspend fun remove(key: String) { raw.remove(key) }
    override suspend fun getAllKeys(): Set<String> = raw.keys.toSet()
    override suspend fun clear() { raw.clear() }

    override fun observeString(key: String): Flow<String?> = emptyFlow()
    override fun observeChanges(): Flow<String> = emptyFlow()
}
