package ai.rever.boss.plugin.dynamic.arcade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Covers the credits contract that matters most: a run start never waits on
 * the network, insufficient credits block the NEXT run (never mid-flight),
 * and every failure mode — missing provider, missing schema, offline —
 * degrades to free play instead of locking anyone out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreditsServiceTest {

    private val user = "4ece2a4a-7d11-4735-a193-9ed915a88ff1"

    private fun snapshotJson(balance: Long, pending: String = "null") =
        """{"balance":$balance,"weeklyFloor":10000,"pendingRequest":$pending}"""

    @Test
    fun unknownCreditsMeanFreePlay() = runTest {
        // Never refreshed: nothing may charge and nothing may block.
        val supabase = FakeSupabase { _, _ -> Result.success("") }
        val service = CreditsService(supabase, FakeAuth(user)) { this }

        assertTrue(service.tryStartRun("2048"))
        advanceUntilIdle()

        assertTrue(supabase.calls.isEmpty())
        assertNull(service.blockedRun.value)
    }

    @Test
    fun missingProviderMeansFreePlay() = runTest {
        val service = CreditsService(null, FakeAuth(user)) { this }

        service.refresh()

        assertNull(service.snapshot.value)
        assertTrue(service.tryStartRun("2048"))
    }

    @Test
    fun refreshParsesTheSnapshot() = runTest {
        val pending = """{"id":"r1","amount":500,"note":"pls","createdAt":"2026-08-23"}"""
        val supabase = FakeSupabase { _, _ -> Result.success(snapshotJson(1234, pending)) }
        val service = CreditsService(supabase, FakeAuth(user)) { this }

        service.refresh()

        val snap = service.snapshot.value
        assertEquals(1234L, snap?.balance)
        assertEquals(10000L, snap?.weeklyFloor)
        assertEquals(500L, snap?.pendingRequest?.amount)
    }

    @Test
    fun failedRefreshDegradesToFreePlay() = runTest {
        val supabase = FakeSupabase { name, _ ->
            if (name == "arcade_my_credits") Result.failure(RuntimeException("no such function"))
            else Result.success("")
        }
        val service = CreditsService(supabase, FakeAuth(user)) { this }

        service.refresh()

        assertNull(service.snapshot.value)
        assertTrue(service.tryStartRun("2048"))
        advanceUntilIdle()
        assertTrue(supabase.calls.none { it.first == "arcade_charge_run" })
    }

    @Test
    fun chargeIsOptimisticAndRunsInTheBackground() = runTest {
        val supabase = FakeSupabase { name, _ ->
            when (name) {
                "arcade_my_credits" -> Result.success(snapshotJson(500))
                "arcade_charge_run" ->
                    Result.success("""{"ok":true,"balance":400,"cost":100}""")
                else -> Result.success("")
            }
        }
        val service = CreditsService(supabase, FakeAuth(user)) { this }
        service.refresh()

        // The gate answers from cache — the RPC has not run yet at this point.
        assertTrue(service.tryStartRun("2048"))
        assertEquals(400L, service.snapshot.value?.balance, "optimistic deduction")

        advanceUntilIdle()
        val charge = supabase.calls.single { it.first == "arcade_charge_run" }
        assertTrue(charge.second.contains("\"p_game\":\"2048\""), charge.second)
        assertEquals(400L, service.snapshot.value?.balance, "server balance wins")
    }

    @Test
    fun insufficientCachedBalanceBlocksBeforeAnyRpc() = runTest {
        val supabase = FakeSupabase { name, _ ->
            if (name == "arcade_my_credits") Result.success(snapshotJson(40))
            else Result.success("")
        }
        val service = CreditsService(supabase, FakeAuth(user)) { this }
        service.refresh()

        assertFalse(service.tryStartRun("2048"))
        advanceUntilIdle()

        assertTrue(supabase.calls.none { it.first == "arcade_charge_run" })
        val blocked = service.blockedRun.value
        assertEquals("2048", blocked?.game)
        assertEquals(40L, blocked?.balance)
        assertEquals(100L, blocked?.cost)
    }

    @Test
    fun insufficientChargeBlocksTheNextRunNotTheCurrentOne() = runTest {
        val supabase = FakeSupabase { name, _ ->
            when (name) {
                "arcade_my_credits" -> Result.success(snapshotJson(100))
                "arcade_charge_run" -> Result.success(
                    """{"ok":false,"error":"insufficient_credits","balance":60,"cost":100}""",
                )
                else -> Result.success("")
            }
        }
        val service = CreditsService(supabase, FakeAuth(user)) { this }
        service.refresh()

        // Cache says 100 >= 100: this run starts (optimistically).
        assertTrue(service.tryStartRun("2048"))
        advanceUntilIdle()

        // Server said no and reported the true balance; the next run blocks.
        assertFalse(service.tryStartRun("2048"))
        assertEquals(60L, service.blockedRun.value?.balance)
    }

    @Test
    fun chargeRpcErrorDegradesToFreePlay() = runTest {
        val supabase = FakeSupabase { name, _ ->
            when (name) {
                "arcade_my_credits" -> Result.success(snapshotJson(500))
                else -> Result.failure(RuntimeException("schema not deployed"))
            }
        }
        val service = CreditsService(supabase, FakeAuth(user)) { this }
        service.refresh()

        assertTrue(service.tryStartRun("2048"))
        advanceUntilIdle()

        assertNull(service.snapshot.value, "a charge error hides credits")
        assertTrue(service.tryStartRun("2048"), "and every game stays free")
    }

    @Test
    fun serverReportedCostOverridesTheDefault() = runTest {
        val supabase = FakeSupabase { name, _ ->
            when (name) {
                "arcade_my_credits" -> Result.success(snapshotJson(500))
                "arcade_charge_run" ->
                    Result.success("""{"ok":true,"balance":250,"cost":250}""")
                else -> Result.success("")
            }
        }
        val service = CreditsService(supabase, FakeAuth(user)) { this }
        service.refresh()

        assertEquals(CreditsService.DEFAULT_COST, service.costOf("wordle"))
        service.tryStartRun("wordle")
        advanceUntilIdle()
        assertEquals(250L, service.costOf("wordle"))
    }

    @Test
    fun adminFlagParsesABareBoolean() = runTest {
        var body = "true"
        val supabase = FakeSupabase { _, _ -> Result.success(body) }
        val service = CreditsService(supabase, FakeAuth(user)) { this }

        service.refreshAdmin()
        assertTrue(service.isAdmin.value)

        body = " false\n"
        service.refreshAdmin()
        assertFalse(service.isAdmin.value)
    }

    @Test
    fun adminCheckErrorMeansNotAdmin() = runTest {
        val supabase = FakeSupabase { _, _ -> Result.failure(RuntimeException("nope")) }
        val service = CreditsService(supabase, FakeAuth(user)) { this }

        service.refreshAdmin()

        assertFalse(service.isAdmin.value)
    }

    @Test
    fun requestCreditsRefreshesThePendingState() = runTest {
        val supabase = FakeSupabase { name, _ ->
            when (name) {
                "arcade_request_credits" -> Result.success("""{"ok":true,"requestId":"r9"}""")
                "arcade_my_credits" -> Result.success(
                    snapshotJson(40, """{"id":"r9","amount":800}"""),
                )
                else -> Result.success("")
            }
        }
        val service = CreditsService(supabase, FakeAuth(user)) { this }

        val result = service.requestCredits(800, "please")

        assertTrue(result.isSuccess)
        val params = supabase.calls.first { it.first == "arcade_request_credits" }.second
        assertTrue(params.contains("\"p_amount\":800"), params)
        assertTrue(params.contains("\"p_note\":\"please\""), params)
        assertEquals("r9", service.snapshot.value?.pendingRequest?.id)
    }

    @Test
    fun requestCreditsSurfacesRequestPending() = runTest {
        val supabase = FakeSupabase { _, _ ->
            Result.success("""{"ok":false,"error":"request_pending"}""")
        }
        val service = CreditsService(supabase, FakeAuth(user)) { this }

        val result = service.requestCredits(800, "")

        assertEquals("request_pending", result.exceptionOrNull()?.message)
    }

    @Test
    fun adminRequestsSortPendingFirst() = runTest {
        val supabase = FakeSupabase { _, _ ->
            Result.success(
                """[
                    {"id":"a","userId":"u1","displayName":"A","amount":100,
                     "status":"approved","createdAt":"2026-08-23","balance":900},
                    {"id":"b","userId":"u2","displayName":"B","amount":200,
                     "status":"pending","createdAt":"2026-08-21","balance":10}
                ]""",
            )
        }
        val service = CreditsService(supabase, FakeAuth(user)) { this }

        val rows = service.adminRequests().getOrThrow()

        assertEquals(listOf("b", "a"), rows.map { it.id })
    }

    @Test
    fun adminResolveSendsNullWhenTheAmountIsNotOverridden() = runTest {
        val supabase = FakeSupabase { _, _ -> Result.success("""{"ok":true,"newBalance":900}""") }
        val service = CreditsService(supabase, FakeAuth(user)) { this }

        assertTrue(service.adminResolve("r1", approve = true, amount = null).isSuccess)
        assertTrue(service.adminResolve("r2", approve = false, amount = 300).isSuccess)

        val params = supabase.calls.map { it.second }
        assertTrue(params[0].contains("\"p_request_id\":\"r1\""), params[0])
        assertTrue(params[0].contains("\"p_approve\":true"), params[0])
        assertTrue(params[0].contains("\"p_amount\":null"), params[0])
        assertTrue(params[1].contains("\"p_approve\":false"), params[1])
        assertTrue(params[1].contains("\"p_amount\":300"), params[1])
    }

    @Test
    fun signedOutPlayersSeeNoCreditsAndPlayFree() = runTest {
        val supabase = FakeSupabase { _, _ -> Result.success(snapshotJson(500)) }
        val service = CreditsService(supabase, FakeAuth(null)) { this }

        service.refresh()
        service.refreshAdmin()

        assertNull(service.snapshot.value)
        assertFalse(service.isAdmin.value)
        assertTrue(service.tryStartRun("2048"))
        advanceUntilIdle()
        assertTrue(supabase.calls.isEmpty())
    }
}
