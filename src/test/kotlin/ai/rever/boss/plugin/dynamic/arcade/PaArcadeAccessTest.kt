package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.api.UserData
import ai.rever.boss.plugin.dynamic.arcade.paarcade.PA_ARCADE_ACCESS_RPC
import ai.rever.boss.plugin.dynamic.arcade.paarcade.PaArcadeAccess
import ai.rever.boss.plugin.dynamic.arcade.paarcade.paArcadeAccessAnswer
import ai.rever.boss.plugin.dynamic.arcade.paarcade.paArcadeAccessGranted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * The PA Arcade card is an access rule, so it fails CLOSED (credits fail open):
 * only a literal `true` from pa_arcade_access, for the user signed in right
 * now, shows it. Every failure hides it and nothing throws into the host.
 */
class PaArcadeAccessTest {

    @Test
    fun parseAcceptsOnlyLiteralTrue() {
        assertTrue(paArcadeAccessGranted("true"))
        assertTrue(paArcadeAccessGranted("  true\n"), "whitespace around it")
        assertFalse(paArcadeAccessGranted("false"))
        assertFalse(paArcadeAccessGranted("null"))
        assertFalse(paArcadeAccessGranted(""))
        assertFalse(paArcadeAccessGranted(null))
        assertFalse(paArcadeAccessGranted("\"true\""), "a string is not a boolean")
        assertFalse(paArcadeAccessGranted("TRUE"))
        assertFalse(paArcadeAccessGranted("""{"message":"function does not exist"}"""))
        assertFalse(paArcadeAccessGranted("[true]"))
        assertEquals(false, paArcadeAccessAnswer(" false "), "a definitive no")
        assertEquals(null, paArcadeAccessAnswer("null"), "no answer")
        assertEquals(null, paArcadeAccessAnswer(null))
    }

    @Test
    fun hiddenUntilAsked() {
        val access = PaArcadeAccess(FakeSupabase { _, _ -> Result.success("true") }, FakeAuth("u1"))
        assertFalse(access.isGranted)
        assertFalse(access.granted.value)
    }

    @Test
    fun grantedWhenTheRpcSaysTrue() = runTest {
        val supabase = FakeSupabase { _, _ -> Result.success("true") }
        val access = PaArcadeAccess(supabase, FakeAuth("u1"))
        access.refresh()
        assertTrue(access.isGranted)
        assertTrue(access.granted.value)
        assertEquals(listOf(PA_ARCADE_ACCESS_RPC to "{}"), supabase.calls)
    }

    @Test
    fun hiddenWhenTheRpcSaysFalse() = runTest {
        val access = PaArcadeAccess(FakeSupabase { _, _ -> Result.success("false") }, FakeAuth("u1"))
        access.refresh()
        assertFalse(access.isGranted)
    }

    @Test
    fun hiddenWithNoProvider() = runTest {
        val access = PaArcadeAccess(null, FakeAuth("u1"))
        access.refresh()
        assertFalse(access.isGranted)
    }

    @Test
    fun hiddenWhenSignedOutAndNoRpcIsMade() = runTest {
        val supabase = FakeSupabase { _, _ -> Result.success("true") }
        val access = PaArcadeAccess(supabase, FakeAuth(null))
        access.refresh()
        assertFalse(access.isGranted)
        assertTrue(supabase.calls.isEmpty())
        assertFalse(PaArcadeAccess(supabase, null).also { it.refresh() }.isGranted, "no auth provider")
    }

    @Test
    fun hiddenWhenTheRpcIsMissing() = runTest {
        val access = PaArcadeAccess(
            FakeSupabase { _, _ -> Result.failure(RuntimeException("function does not exist")) },
            FakeAuth("u1"),
        )
        access.refresh()
        assertFalse(access.isGranted)
    }

    @Test
    fun hiddenOnAThrowOfAnyKind() = runTest {
        val access = PaArcadeAccess(FakeSupabase { _, _ -> throw NoSuchMethodError("old console") }, FakeAuth("u1"))
        access.refresh()
        assertFalse(access.isGranted)
    }

    @Test
    fun aYesIsCachedForTheSessionSoLaterChecksAreFree() = runTest {
        var answer: Result<String> = Result.success("true")
        val supabase = FakeSupabase { _, _ -> answer }
        val access = PaArcadeAccess(supabase, FakeAuth("u1"))
        access.refresh()
        answer = Result.failure(RuntimeException("offline"))
        access.refresh()
        assertTrue(access.isGranted)
        assertEquals(1, supabase.calls.size)
    }

    @Test
    fun aNoOrAFailureIsAskedAgainOnTheNextCheck() = runTest {
        var answer: Result<String> = Result.failure(RuntimeException("offline"))
        val supabase = FakeSupabase { _, _ -> answer }
        val access = PaArcadeAccess(supabase, FakeAuth("u1"))
        access.refresh()
        assertFalse(access.isGranted)
        answer = Result.success("false")
        access.refresh()
        assertFalse(access.isGranted)
        answer = Result.success("true") // e.g. added to the organisation mid-session
        access.refresh()
        assertTrue(access.isGranted)
        assertEquals(3, supabase.calls.size)
    }

    @Test
    fun anAccountSwitchNeverInheritsAccess() = runTest {
        val auth = SwitchableAuth("u1")
        val access = PaArcadeAccess(
            FakeSupabase { _, _ -> Result.success(if (auth.currentUser.value?.id == "u1") "true" else "false") },
            auth,
        )
        access.refresh()
        assertTrue(access.isGranted)

        auth.signIn("u2")
        assertFalse(access.isGranted, "hidden for the new account before any re-check")
        access.refresh()
        assertFalse(access.isGranted)

        auth.signIn(null)
        assertFalse(access.isGranted)
        access.refresh()
        assertFalse(access.granted.value)
    }

    @Test
    fun anAnswerForAnAccountThatSignedOutMidCallIsDropped() = runTest {
        val auth = SwitchableAuth("u1")
        val access = PaArcadeAccess(
            FakeSupabase { _, _ ->
                auth.signIn("u2") // the account changes while the call is in flight
                Result.success("true")
            },
            auth,
        )
        access.refresh()
        assertFalse(access.isGranted)
        assertFalse(access.granted.value)
    }
    // Two tabs refreshing at once: replies are answered by hand, in either order.

    @Test
    fun aLaterFailureNeverDowngradesAYesThatArrivedFirst() = runTest {
        val supabase = DeferredSupabase()
        val access = PaArcadeAccess(supabase, FakeAuth("u1"))
        launch { access.refresh() } // tab A
        launch { access.refresh() } // tab B
        runCurrent()
        supabase.answer(0, Result.success("true"))
        runCurrent()
        supabase.answer(1, Result.failure(RuntimeException("offline")))
        runCurrent()
        assertTrue(access.isGranted)
        assertTrue(access.granted.value)
    }

    @Test
    fun aStaleYesArrivingAfterTheLatestReplyIsDropped() = runTest {
        val supabase = DeferredSupabase()
        val access = PaArcadeAccess(supabase, FakeAuth("u1"))
        launch { access.refresh() } // tab A (older request)
        launch { access.refresh() } // tab B (latest request)
        runCurrent()
        supabase.answer(1, Result.success("false"))
        runCurrent()
        supabase.answer(0, Result.success("true"))
        runCurrent()
        assertFalse(access.isGranted, "only the latest request's reply counts")
    }

    @Test
    fun aFailureLandingFirstDoesNotOutdateAnOlderYes() = runTest {
        val supabase = DeferredSupabase()
        val access = PaArcadeAccess(supabase, FakeAuth("u1"))
        launch { access.refresh() } // tab A (older request)
        launch { access.refresh() } // tab B (latest request)
        runCurrent()
        supabase.answer(1, Result.failure(RuntimeException("offline")))
        runCurrent()
        assertFalse(access.isGranted, "nothing known yet: hidden")
        supabase.answer(0, Result.success("true"))
        runCurrent()
        assertTrue(access.isGranted, "a failure carries no answer, so A's yes still applies")
    }

    @Test
    fun aDefinitiveNoForTheSameUserRevokesAYes() = runTest {
        val supabase = DeferredSupabase()
        val access = PaArcadeAccess(supabase, FakeAuth("u1"))
        launch { access.refresh() }
        launch { access.refresh() }
        runCurrent()
        supabase.answer(0, Result.success("true"))
        runCurrent()
        supabase.answer(1, Result.success("false"))
        runCurrent()
        assertFalse(access.isGranted)
    }

    @Test
    fun aStaleReplyForThePreviousAccountNeverTouchesTheNewOnesAnswer() = runTest {
        for (stale in listOf(Result.success("false"), Result.success("true"), Result.failure(RuntimeException("x")))) {
            // The new account answers first, then the old account's reply lands.
            val auth = SwitchableAuth("u1")
            val supabase = DeferredSupabase()
            val access = PaArcadeAccess(supabase, auth)
            launch { access.refresh() } // for u1
            runCurrent()
            auth.signIn("u2")
            launch { access.refresh() } // for u2
            runCurrent()
            supabase.answer(1, Result.success("true"))
            runCurrent()
            supabase.answer(0, stale)
            runCurrent()
            assertTrue(access.isGranted, "u2 keeps its yes after a stale $stale")
        }
    }

    @Test
    fun aStaleReplyForThePreviousAccountLandingFirstIsDropped() = runTest {
        // The old account's reply lands before the new account's.
        val auth = SwitchableAuth("u1")
        val supabase = DeferredSupabase()
        val access = PaArcadeAccess(supabase, auth)
        launch { access.refresh() } // for u1
        runCurrent()
        auth.signIn("u2")
        launch { access.refresh() } // for u2
        runCurrent()
        supabase.answer(0, Result.success("true"))
        runCurrent()
        assertFalse(access.isGranted, "u1's yes never applies to u2")
        supabase.answer(1, Result.success("true"))
        runCurrent()
        assertTrue(access.isGranted)
    }

    // The MCP leaderboard tool: pa- boards need access.

    private val board = """[{"user_id":"u9","display_name":"ana","best_score":1200}]"""

    @Test
    fun mcpLeaderboardHidesPaBoardsWithoutAccess() = runTest {
        val supabase = FakeSupabase { name, _ ->
            if (name == PA_ARCADE_ACCESS_RPC) Result.success("false") else Result.success(board)
        }
        val auth = FakeAuth("u1")
        val access = PaArcadeAccess(supabase, auth).also { it.refresh() }
        val leaderboard = LeaderboardService(supabase, auth)
        for (key in listOf("pa-arcade", "pa-claims-sprint")) {
            val result = leaderboardToolResult(leaderboard, access, key, 10)
            assertEquals("No scores recorded for '$key' yet.", result.text)
            assertFalse(result.isError)
        }
        assertTrue(supabase.calls.none { it.first == "arcade_leaderboard" }, "no board request made")
        // Same shape as a real game with no scores.
        val emptySupabase = FakeSupabase { _, _ -> Result.success("[]") }
        assertEquals(
            "No scores recorded for 'snake' yet.",
            leaderboardToolResult(LeaderboardService(emptySupabase, auth), access, "snake", 10).text,
        )
    }

    @Test
    fun mcpLeaderboardShowsPaBoardsWithAccessAndOtherBoardsAlways() = runTest {
        val supabase = FakeSupabase { name, _ ->
            if (name == PA_ARCADE_ACCESS_RPC) Result.success("true") else Result.success(board)
        }
        val auth = FakeAuth("u1")
        val granted = PaArcadeAccess(supabase, auth).also { it.refresh() }
        val leaderboard = LeaderboardService(supabase, auth)
        assertEquals(
            "Leaderboard for pa-arcade:\n1. ana - 1200",
            leaderboardToolResult(leaderboard, granted, "pa-arcade", 10).text,
        )
        val unknown = PaArcadeAccess(null, auth)
        assertEquals(
            "Leaderboard for 2048:\n1. ana - 1200",
            leaderboardToolResult(leaderboard, unknown, "2048", 10).text,
        )
    }
}

/** Holds every rpc call open until the test answers it, by call index. */
private class DeferredSupabase : SupabaseDataProvider {
    private val replies = mutableListOf<CompletableDeferred<Result<String>>>()

    fun answer(index: Int, reply: Result<String>) {
        replies[index].complete(reply)
    }

    override suspend fun rpc(functionName: String, params: String): Result<String> {
        val reply = CompletableDeferred<Result<String>>()
        replies.add(reply)
        return reply.await()
    }

    override suspend fun select(
        table: String,
        columns: String,
        filters: List<QueryFilter>,
        range: QueryRange?,
    ): Result<String> = Result.success("[]")
}

private class SwitchableAuth(userId: String?) : AuthDataProvider {
    private val state = MutableStateFlow(userId?.let(::user))
    override val currentUser: StateFlow<UserData?> = state
    override val isAdmin: StateFlow<Boolean> = MutableStateFlow(false)
    override val userPermissions: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override fun hasPermission(permission: String): Boolean = false
    override fun hasAnyPermission(vararg permissions: String): Boolean = false

    fun signIn(userId: String?) {
        state.value = userId?.let(::user)
    }

    private fun user(id: String) = UserData(
        id = id,
        email = "$id@example.com",
        displayName = id,
        avatarUrl = null,
        roles = emptyList(),
        createdAt = 0L,
    )
}
