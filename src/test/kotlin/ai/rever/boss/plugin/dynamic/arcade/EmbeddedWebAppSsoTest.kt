package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.dynamic.arcade.paarcade.PA_ARCADE_SSO_RPC
import ai.rever.boss.plugin.dynamic.arcade.paarcade.PA_ARCADE_URL
import ai.rever.boss.plugin.dynamic.arcade.poker.POKER_SSO_RPC
import ai.rever.boss.plugin.dynamic.arcade.poker.POKER_URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The console-SSO URL contract shared by the embedded web apps (poker, PA
 * Arcade): a well-shaped code rides along as `?sso=`, and every failure
 * (missing provider, RPC not deployed, garbage, a throw) degrades to the plain
 * URL, where the web app shows its own sign-in. Never a crash into the host.
 */
class EmbeddedWebAppSsoTest {

    private val code = "0123456789abcdef".repeat(3) // 48 lowercase hex chars

    @Test
    fun codeShapeIs48LowercaseHex() {
        assertEquals(code, ssoCodeOrNull(code))
        assertEquals(code, ssoCodeOrNull("\"$code\""), "JSON-quoted RPC result")
        assertEquals(code, ssoCodeOrNull("  \"$code\"\n"), "whitespace around it")
        assertNull(ssoCodeOrNull(code.dropLast(1)), "47 chars")
        assertNull(ssoCodeOrNull(code + "0"), "49 chars")
        assertNull(ssoCodeOrNull(code.uppercase()), "uppercase hex")
        assertNull(ssoCodeOrNull("g" + code.drop(1)), "non-hex")
        assertNull(ssoCodeOrNull(""))
        assertNull(ssoCodeOrNull(null))
        assertNull(ssoCodeOrNull("""{"code":"$code"}"""), "wrapped in an object")
    }

    @Test
    fun urlCarriesTheCodeOnlyWhenWellShaped() {
        assertEquals("$PA_ARCADE_URL/?sso=$code", ssoUrlFor(PA_ARCADE_URL, "\"$code\""))
        assertEquals(PA_ARCADE_URL, ssoUrlFor(PA_ARCADE_URL, "null"))
        assertEquals(PA_ARCADE_URL, ssoUrlFor(PA_ARCADE_URL, null))
        assertEquals("https://risa-pa-arcade.web.app/?sso=$code", ssoUrlFor(PA_ARCADE_URL, code))
    }

    @Test
    fun mintsThroughTheAppsOwnRpc() = runTest {
        val supabase = FakeSupabase { _, _ -> Result.success("\"$code\"") }
        assertEquals("$PA_ARCADE_URL/?sso=$code", mintSsoUrl(supabase, PA_ARCADE_SSO_RPC, PA_ARCADE_URL))
        assertEquals("$POKER_URL/?sso=$code", mintSsoUrl(supabase, POKER_SSO_RPC, POKER_URL))
        assertEquals(
            listOf("pa_arcade_sso_code" to "{}", "poker_sso_code" to "{}"),
            supabase.calls,
        )
    }

    @Test
    fun noProviderFallsBackToPlainUrl() = runTest {
        assertEquals(PA_ARCADE_URL, mintSsoUrl(null, PA_ARCADE_SSO_RPC, PA_ARCADE_URL))
    }

    @Test
    fun rpcFailureFallsBackToPlainUrl() = runTest {
        // What an undeployed pa_arcade_sso_code (or a non-member) looks like.
        val supabase = FakeSupabase { _, _ -> Result.failure(RuntimeException("function does not exist")) }
        assertEquals(PA_ARCADE_URL, mintSsoUrl(supabase, PA_ARCADE_SSO_RPC, PA_ARCADE_URL))
    }

    @Test
    fun rpcThrowFallsBackToPlainUrl() = runTest {
        val supabase = FakeSupabase { _, _ -> throw NoSuchMethodError("old console") }
        assertEquals(PA_ARCADE_URL, mintSsoUrl(supabase, PA_ARCADE_SSO_RPC, PA_ARCADE_URL))
    }

    @Test
    fun unexpectedRpcValueFallsBackToPlainUrl() = runTest {
        val supabase = FakeSupabase { _, _ -> Result.success("\"not-a-code\"") }
        assertEquals(PA_ARCADE_URL, mintSsoUrl(supabase, PA_ARCADE_SSO_RPC, PA_ARCADE_URL))
    }
}
