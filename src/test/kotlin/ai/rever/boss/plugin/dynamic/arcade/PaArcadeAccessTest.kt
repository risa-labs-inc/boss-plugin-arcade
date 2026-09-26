package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.UserData
import ai.rever.boss.plugin.dynamic.arcade.paarcade.PA_ARCADE_ACCESS_RPC
import ai.rever.boss.plugin.dynamic.arcade.paarcade.PaArcadeAccess
import ai.rever.boss.plugin.dynamic.arcade.paarcade.paArcadeAccessGranted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
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
