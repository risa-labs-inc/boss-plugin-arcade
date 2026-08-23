package ai.rever.boss.plugin.dynamic.arcade.poker

import ai.rever.boss.plugin.api.SupabaseDataProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The poker edge function and GoTrue verify endpoint (same Supabase project the console signs into). */
private const val POKER_FN_URL = "https://api.risaboss.com/functions/v1/poker"
private const val AUTH_VERIFY_URL = "https://api.risaboss.com/auth/v1/verify"

/**
 * The Supabase project's PUBLIC anon key (role "anon") — the exact value the poker web app ships
 * in its public JS bundle, so a source constant here leaks nothing. It only lets requests reach
 * the endpoints; every poker op is additionally authorized by the user's short-lived access token.
 */
private const val SUPABASE_ANON_KEY =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
        "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBjbndxYW1xZG5zYWRyYW51Zmp2Iiwicm9sZSI6ImFub24iLCJpYXQ" +
        "iOjE3NTkxMDUwMzMsImV4cCI6MjA3NDY4MTAzM30.WZ6jSKuqM2EMyZLgoGJnI8Bn_Sdwk6plW0PkVNLIYVY"

private val MOVE_KINDS = setOf("fold", "check", "call", "bet", "raise")

/**
 * Lets an in-terminal agent play poker AS the signed-in BOSS user, over plain HTTP against the
 * poker edge function (protocol: boss-poker packages/protocol).
 *
 * Auth is the same console-SSO flow the embedded web app uses (see [PokerViewModel.ssoUrl]):
 * mint a one-time code via the poker_sso_code() RPC (runs as the signed-in user), exchange it at
 * the edge function for an email-OTP token hash, verify that with GoTrue for a regular access
 * token. The session is cached in memory; on expiry or any 401 the whole three-request flow just
 * re-runs — codes are free, so there is no refresh-token bookkeeping to get wrong.
 *
 * Backward compat: deliberately uses only [SupabaseDataProvider] (already relied on throughout
 * this plugin) plus JDK HTTP and kotlinx-serialization, so nothing here can throw a LinkageError
 * on consoles bundling an older plugin-api. Every public method returns Result — a failure is a
 * clear message for the agent, never a crash into the host.
 */
class PokerAgentService(private val supabase: SupabaseDataProvider?) {

    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    private class Session(val accessToken: String, val userId: String, private val expiresAtMs: Long) {
        val fresh: Boolean get() = System.currentTimeMillis() < expiresAtMs
    }

    private val sessionMutex = Mutex()

    @Volatile
    private var session: Session? = null

    /** List the lobby tables as compact JSON (id, name, blinds, buy-in range, seats, players). */
    suspend fun lobby(): Result<String> = guard {
        val sb = supabase ?: fail(NO_SUPABASE)
        val raw = sb.rpc("poker_lobby", "{}").getOrElse { fail("Poker lobby unavailable: ${it.message}") }
        val rows = runCatching { json.parseToJsonElement(raw).jsonArray }
            .getOrElse { fail("poker_lobby returned unexpected data — the poker schema may not be deployed.") }
        if (rows.isEmpty()) {
            "No poker tables exist yet. The user can create one from the Poker screen in the Arcade tab."
        } else {
            buildJsonArray {
                rows.mapNotNull { it as? JsonObject }.forEach { t ->
                    add(
                        buildJsonObject {
                            put("tableId", t.str("id"))
                            put("name", t.str("name"))
                            put("blinds", "${t.num("smallBlind")}/${t.num("bigBlind")}")
                            put("buyIn", "${t.num("minBuyIn")}-${t.num("maxBuyIn")}")
                            put("seats", "${t.num("seatedCount")}/${t.num("maxSeats")}")
                            put("players", t["seatedNames"] ?: JsonNull)
                            put("status", t.str("status"))
                        },
                    )
                }
            }.toString()
        }
    }

    /** The table state summarized for an agent, including the caller's hole cards and turn. */
    suspend fun state(tableId: String): Result<String> = guard {
        val reply = fetchState(tableId)
        summarize(reply.body, reply.userId)
    }

    /** Sit the signed-in user at [seat] with [buyIn] chips (CAS handled internally). */
    suspend fun sit(tableId: String, seat: Int, buyIn: Long): Result<String> = guard {
        mutate(tableId) { version ->
            buildJsonObject {
                put("op", "sit")
                put("tableId", tableId)
                put("seat", seat)
                put("buyIn", buyIn)
                put("expectedVersion", version)
            }
        }
    }

    /** Leave the table; the remaining stack returns to the user's bankroll. */
    suspend fun leave(tableId: String): Result<String> = guard {
        mutate(tableId) { version ->
            buildJsonObject {
                put("op", "leave")
                put("tableId", tableId)
                put("expectedVersion", version)
            }
        }
    }

    /**
     * Make a move. Fetches the current version itself, sends the action under a fresh actionId,
     * and on version_conflict retries once with the reported version (same actionId — the server
     * dedupes on it, so a retry can never double-apply).
     */
    suspend fun act(tableId: String, kind: String, amount: Long?): Result<String> = guard {
        val move = kind.lowercase()
        if (move !in MOVE_KINDS) fail("kind must be one of fold, check, call, bet, raise.")
        if ((move == "bet" || move == "raise") && amount == null) {
            fail("$move needs an amount — the TOTAL committed this street (raise TO), not a delta.")
        }
        val actionId = UUID.randomUUID().toString()
        mutate(tableId) { version ->
            buildJsonObject {
                put("op", "action")
                put("tableId", tableId)
                put("actionId", actionId)
                put("expectedVersion", version)
                put(
                    "move",
                    buildJsonObject {
                        put("kind", move)
                        if (move == "bet" || move == "raise") put("amount", amount!!)
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private class OpReply(val body: JsonObject, val userId: String)

    /** Fetch my_state, failing with a described error unless ok. */
    private suspend fun fetchState(tableId: String): OpReply {
        val reply = pokerOp(
            buildJsonObject {
                put("op", "my_state")
                put("tableId", tableId)
            },
        )
        if (reply.body.bool("ok") != true) fail(describeError(reply.body))
        return reply
    }

    /** Run a CAS-guarded op: fetch the version, send, retry once on version_conflict. */
    private suspend fun mutate(tableId: String, build: (Long) -> JsonObject): String {
        val version = fetchState(tableId).body.long("version") ?: 0L
        var reply = pokerOp(build(version))
        if (reply.body.str("error") == "version_conflict") {
            val current = reply.body.long("currentVersion")
                ?: fetchState(tableId).body.long("version")
                ?: 0L
            reply = pokerOp(build(current))
        }
        if (reply.body.bool("ok") != true) fail(describeError(reply.body))
        val events = (reply.body["events"] as? JsonArray ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonObject)?.let(::eventLine) }
        val summary = summarize(reply.body, reply.userId)
        return if (events.isEmpty()) summary else "events: ${events.joinToString("; ")}\n$summary"
    }

    /** POST one op to the edge function as the user, re-running the SSO flow once on a 401. */
    private suspend fun pokerOp(body: JsonObject): OpReply {
        var s = currentSession()
        var reply = postJson(POKER_FN_URL, body.toString(), bearer = s.accessToken)
        if (reply.status == 401) {
            s = refreshedSession(stale = s)
            reply = postJson(POKER_FN_URL, body.toString(), bearer = s.accessToken)
        }
        val parsed = parseObject(reply.body)
            ?: fail("The poker service returned a non-JSON reply (HTTP ${reply.status}).")
        return OpReply(parsed, s.userId)
    }

    private suspend fun currentSession(): Session = sessionMutex.withLock {
        session?.takeIf { it.fresh } ?: signIn().also { session = it }
    }

    /** Re-auth after a 401, unless another caller already replaced the stale session. */
    private suspend fun refreshedSession(stale: Session): Session = sessionMutex.withLock {
        val cached = session
        if (cached != null && cached !== stale && cached.fresh) cached
        else signIn().also { session = it }
    }

    /** The console-SSO flow: mint code (as the user) -> exchange -> verify -> access token. */
    private suspend fun signIn(): Session {
        val sb = supabase ?: fail(NO_SUPABASE)
        val minted = sb.rpc("poker_sso_code", "{}").getOrElse {
            fail("Could not mint a poker sign-in code (is the user signed in to BOSS?): ${it.message}")
        }
        val code = minted.trim().removeSurrounding("\"")
        if (!code.matches(SSO_CODE_SHAPE)) {
            fail("poker_sso_code returned an unexpected value — the poker schema may not be deployed.")
        }
        val exchange = postJson(
            POKER_FN_URL,
            buildJsonObject {
                put("op", "sso_exchange")
                put("code", code)
            }.toString(),
            bearer = SUPABASE_ANON_KEY,
        )
        val exBody = parseObject(exchange.body)
            ?: fail("sso_exchange returned a non-JSON reply (HTTP ${exchange.status}).")
        val tokenHash = exBody.str("tokenHash")
            ?: fail("Poker sign-in failed at sso_exchange: ${exBody.str("error") ?: "HTTP ${exchange.status}"}.")
        val verify = postJson(
            AUTH_VERIFY_URL,
            buildJsonObject {
                put("type", "email")
                put("token_hash", tokenHash)
            }.toString(),
            bearer = null,
        )
        val vBody = parseObject(verify.body)
            ?: fail("Token verify returned a non-JSON reply (HTTP ${verify.status}).")
        val access = vBody.str("access_token") ?: fail(
            "Poker sign-in failed at verify: " +
                "${vBody.str("msg") ?: vBody.str("error_description") ?: "HTTP ${verify.status}"}.",
        )
        val expiresIn = vBody.long("expires_in") ?: 3600L
        val userId = jwtSub(access) ?: fail("Could not read the user id from the poker access token.")
        val expiresAt = System.currentTimeMillis() + (expiresIn - 60).coerceAtLeast(30) * 1000
        return Session(access, userId, expiresAt)
    }

    // ------------------------------------------------------------------ summaries

    /**
     * Boil a PokerOkResponse down to what an agent needs to decide a move: streets, stacks,
     * the caller's own cards, and whether it is actually the caller's turn.
     */
    private fun summarize(resp: JsonObject, me: String): String {
        val state = resp["state"] as? JsonObject ?: fail("The poker reply carried no table state.")
        var mySeat: JsonObject? = null
        val seats = buildJsonArray {
            (state["seats"] as? JsonArray ?: JsonArray(emptyList())).forEach { el ->
                val s = el as? JsonObject ?: return@forEach // empty seats are nulls
                if (s.str("userId") == me) mySeat = s
                add(
                    buildJsonObject {
                        put("seat", s.int("seat"))
                        put("name", s.str("displayName"))
                        put("stack", s.long("stack"))
                        put("committed", s.long("committed"))
                        if (s.bool("folded") == true) put("folded", true)
                        if (s.bool("allIn") == true) put("allIn", true)
                        if (s.bool("sittingOut") == true) put("sittingOut", true)
                        if (s.str("userId") == me) put("you", true)
                    },
                )
            }
        }
        val my = mySeat
        val actorSeat = state.long("actorSeat")
        val yourTurn = my != null && actorSeat != null && actorSeat == my.long("seat")
        val currentBet = state.long("currentBet") ?: 0L
        val pot = (state["pots"] as? JsonArray ?: JsonArray(emptyList()))
            .sumOf { (it as? JsonObject)?.long("amount") ?: 0L }
        val config = state["config"] as? JsonObject
        return buildJsonObject {
            put("version", resp.long("version"))
            put("phase", state.str("phase"))
            state.str("street")?.let { put("street", it) }
            put("board", state["board"] ?: JsonArray(emptyList()))
            put("pot", pot)
            put("currentBet", currentBet)
            put("minRaiseTo", state.long("minRaiseTo"))
            config?.let { put("blinds", "${it.num("smallBlind")}/${it.num("bigBlind")}") }
            put("seats", seats)
            actorSeat?.let { put("actorSeat", it) }
            state.long("actionDeadline")?.let { put("actionDeadlineInMs", it - System.currentTimeMillis()) }
            put(
                "you",
                buildJsonObject {
                    if (my == null) {
                        put("seated", false)
                    } else {
                        put("seat", my.long("seat"))
                        resp["myCards"]?.let { put("cards", it) }
                        put("yourTurn", yourTurn)
                        if (yourTurn) {
                            val owed = (currentBet - (my.long("committed") ?: 0L)).coerceAtLeast(0L)
                            put("toCall", minOf(owed, my.long("stack") ?: owed))
                        }
                    }
                },
            )
            lastHandLine(state)?.let { put("lastHand", it) }
        }.toString()
    }

    private fun lastHandLine(state: JsonObject): String? {
        val results = state["lastHandResults"] as? JsonArray ?: return null
        val winners = results.mapNotNull { it as? JsonObject }
            .filter { (it.long("won") ?: 0L) > 0L }
            .map { r -> "seat ${r.num("seat")} won ${r.num("won")}${r.str("rankLabel")?.let { " ($it)" } ?: ""}" }
        return winners.joinToString(", ").ifEmpty { null }
    }

    /** One public event -> one short human line for the action result. */
    private fun eventLine(e: JsonObject): String? {
        val seat = e.num("seat")
        return when (e.str("kind")) {
            "deal" -> "new hand #${e.num("handNo")} dealt (button seat ${e.num("buttonSeat")})"
            "holeCards" -> null // private; the summary carries your cards
            "blind" -> "seat $seat posts ${e.str("blind")} blind ${e.num("amount")}"
            "fold" -> "seat $seat folds"
            "check" -> "seat $seat checks"
            "call" -> "seat $seat calls ${e.num("amount")}"
            "bet" -> "seat $seat bets ${e.num("amount")}"
            "raise" -> "seat $seat raises to ${e.num("amount")}"
            "street" -> "${e.str("street")}: ${cardsOf(e)}"
            "actor" -> "action on seat $seat"
            "timeout" -> "seat $seat timed out (auto-${e.str("autoAction")})"
            "showdown" -> "showdown"
            "handEnd" -> "hand over" + (lastHandFromResults(e)?.let { ": $it" } ?: "")
            "sat" -> "seat $seat sat down"
            "left" -> "seat $seat left"
            null -> null
            else -> e.str("kind")
        }
    }

    private fun lastHandFromResults(e: JsonObject): String? {
        val results = e["results"] as? JsonArray ?: return null
        return results.mapNotNull { it as? JsonObject }
            .filter { (it.long("won") ?: 0L) > 0L }
            .joinToString(", ") { r ->
                "seat ${r.num("seat")} won ${r.num("won")}${r.str("rankLabel")?.let { " ($it)" } ?: ""}"
            }
            .ifEmpty { null }
    }

    private fun cardsOf(e: JsonObject): String =
        (e["cards"] as? JsonArray ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .joinToString(" ")

    private fun describeError(body: JsonObject): String {
        val err = body.str("error") ?: "unknown_error"
        val hint = when (err) {
            "not_your_turn" -> " Poll poker_state and act only when it reports yourTurn: true."
            "unauthorized" -> " The poker sign-in did not take; try the tool again."
            "seat_taken", "table_full" -> " Check poker_state for an open seat."
            "already_seated" -> " You are already at this table; just play with poker_act."
            "not_seated" -> " Sit first (poker_sit) — and only if the user asked to play."
            "bad_amount", "illegal_move" ->
                " Amounts are the TOTAL committed this street (raise TO); respect currentBet/minRaiseTo."
            "bad_buy_in" -> " The buy-in must be inside the table's range (see poker_lobby)."
            else -> ""
        }
        return "Poker error: $err${body.str("message")?.let { " ($it)" } ?: ""}.$hint"
    }

    // ------------------------------------------------------------------ plumbing

    private class HttpReply(val status: Int, val body: String)

    private suspend fun postJson(url: String, body: String, bearer: String?): HttpReply =
        withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("apikey", SUPABASE_ANON_KEY)
            if (bearer != null) request.header("Authorization", "Bearer $bearer")
            val response = http.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            HttpReply(response.statusCode(), response.body())
        }

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    /** The "sub" claim (the auth user id) from an access token, decoded locally. */
    private fun jwtSub(jwt: String): String? = runCatching {
        val payload = jwt.split(".").getOrNull(1) ?: return null
        parseObject(Base64.getUrlDecoder().decode(payload).decodeToString())?.str("sub")
    }.getOrNull()

    /**
     * Every tool entry point funnels through this: any failure — including a LinkageError on an
     * exotic console — becomes an error string for the agent instead of a crash into the host.
     */
    private suspend fun guard(block: suspend () -> String): Result<String> =
        try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(t.message ?: t.toString()))
        }

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    private companion object {
        const val NO_SUPABASE =
            "Poker is unavailable: this console exposes no Supabase connection (user signed out or console too old)."
    }
}

// Tolerant JsonObject readers: absent keys and JsonNull both read as null.
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.num(key: String): String = str(key) ?: "?"
