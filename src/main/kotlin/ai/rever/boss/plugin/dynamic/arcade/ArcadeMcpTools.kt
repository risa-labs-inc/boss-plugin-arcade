package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048ViewModel
import ai.rever.boss.plugin.dynamic.arcade.wordle.WordleViewModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MCP tools for in-terminal agents (surfaced as mcp__boss__arcade_*):
 * read the leaderboards, play the live turn-based games (2048, Wordle) in the
 * open Arcade tab — every move renders on the user's screen — and play
 * multiplayer poker AS the signed-in user (console SSO over HTTP; the embedded
 * poker web app mirrors every action live via realtime). Mirror Dash and Sky
 * Stack are reflex/real-time, so they have no play tools, only the leaderboard.
 */
class ArcadeMcpTools(
    override val providerId: String,
    private val services: ArcadeServices,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "arcade_leaderboard",
            description = "Arcade leaderboard: top scores per player. Games: 2048, mirror-dash, sky-stack, typing-sprint, wordle (points = 7 minus guesses used).",
            inputSchema = """{"type":"object","properties":{"game":{"type":"string","description":"Game key: 2048 (default), mirror-dash, sky-stack, typing-sprint, or wordle"},"limit":{"type":"integer","description":"Max entries (default 10)"}},"required":[]}""",
            handler = McpToolHandler { args ->
                val game = args.string("game") ?: "2048"
                val limit = (args.int("limit") ?: 10).coerceIn(1, 50)
                services.leaderboard.awaitPendingSubmits()
                services.leaderboard.topScores(game, limit).fold(
                    onSuccess = { entries ->
                        if (entries.isEmpty()) {
                            McpToolResult("No scores recorded for '$game' yet.")
                        } else {
                            val lines = entries.mapIndexed { i, e ->
                                "${i + 1}. ${e.displayName ?: "Player"} — ${e.bestScore}"
                            }
                            McpToolResult("Leaderboard for $game:\n" + lines.joinToString("\n"))
                        }
                    },
                    onFailure = { e ->
                        McpToolResult("Leaderboard unavailable: ${e.message}", isError = true)
                    },
                )
            },
        ),
        McpToolDefinition(
            name = "arcade_2048_state",
            description = "Read the live 2048 board in the open Arcade tab as JSON: 4x4 grid (0 = empty), score, best, over/won, and veil (win/over dialog showing).",
            handler = McpToolHandler {
                withGame { game -> McpToolResult(game.snapshotJson()) }
            },
        ),
        McpToolDefinition(
            name = "arcade_2048_move",
            description = "Slide the live 2048 board up, down, left, or right. The Arcade tab switches to the board so the user watches the move animate. Returns the settled board JSON; a move that changes nothing reports so.",
            inputSchema = """{"type":"object","properties":{"direction":{"type":"string","description":"up | down | left | right"}},"required":["direction"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val dir = when (args.string("direction")?.lowercase()) {
                    "up" -> -1 to 0
                    "down" -> 1 to 0
                    "left" -> 0 to -1
                    "right" -> 0 to 1
                    else -> null
                } ?: return@McpToolHandler McpToolResult(
                    "direction must be up, down, left or right",
                    isError = true,
                )
                withGame { game ->
                    var moved = game.move(dir.first, dir.second)
                    if (!moved) {
                        // The board blocks input for ~105 ms while tiles slide.
                        delay(160)
                        moved = game.move(dir.first, dir.second)
                    }
                    delay(220)
                    val prefix = if (moved) "" else "Move did not change the board.\n"
                    McpToolResult(prefix + game.snapshotJson())
                }
            },
        ),
        McpToolDefinition(
            name = "arcade_2048_new_game",
            description = "Start a new 2048 game in the open Arcade tab (also dismisses a game-over dialog).",
            readOnly = false,
            handler = McpToolHandler {
                withGame { game ->
                    game.newGame()
                    delay(100)
                    McpToolResult(game.snapshotJson())
                }
            },
        ),
        McpToolDefinition(
            name = "arcade_wordle_state",
            description = "Read today's live Wordle board in the open Arcade tab as JSON: committed guesses with per-letter feedback (G = correct spot, Y = in the word elsewhere, B = absent), phase (playing/won/lost), and the solution once the game is over. The word is shared: everyone gets the same one each UTC day.",
            handler = McpToolHandler {
                withWordle { game -> McpToolResult(game.snapshotJson()) }
            },
        ),
        McpToolDefinition(
            name = "arcade_wordle_guess",
            description = "Play a 5-letter guess on today's live Wordle board. The Arcade tab switches to the board so the user watches the tiles flip. One shared word per UTC day, 6 guesses total; solving in fewer guesses scores more points. Returns the board JSON after the reveal.",
            inputSchema = """{"type":"object","properties":{"word":{"type":"string","description":"A 5-letter word from the game's dictionary"}},"required":["word"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val word = args.string("word")
                    ?: return@McpToolHandler McpToolResult("word is required", isError = true)
                withWordle { game ->
                    val error = game.tryGuess(word)
                    if (error != null) {
                        McpToolResult(error, isError = true)
                    } else {
                        delay(WordleViewModel.REVEAL_TOTAL_MS + 600)
                        McpToolResult(game.snapshotJson())
                    }
                }
            },
        ),
        McpToolDefinition(
            name = "poker_lobby",
            description = "List the live poker tables (multiplayer No-Limit Hold'em with teammates, play " +
                "money): table id, blinds, buy-in range, seats filled and who is seated. Poker tools play AS " +
                "the signed-in BOSS user via console SSO. Etiquette: you are handling the user's own seat and " +
                "chips, so NEVER sit at a table (poker_sit) unless the user explicitly asked you to play.",
            handler = McpToolHandler {
                surfacePoker(everyCall = false)
                services.pokerAgent.lobby().toToolResult()
            },
        ),
        McpToolDefinition(
            name = "poker_state",
            description = "Read a poker table's live state as the signed-in user: phase/street, board, pot, " +
                "currentBet and minRaiseTo, every seat's stack, whose turn it is (actorSeat, " +
                "actionDeadlineInMs) — plus YOUR hole cards, yourTurn and toCall. Poll this between turns and " +
                "only call poker_act when yourTurn is true; acting early just returns not_your_turn. " +
                "bet/raise amounts are the TOTAL committed this street (raise TO), not an increment.",
            inputSchema = """{"type":"object","properties":{"tableId":{"type":"string",""" +
                """"description":"Table id from poker_lobby"}},"required":["tableId"]}""",
            handler = McpToolHandler { args ->
                val tableId = args.string("tableId")
                    ?: return@McpToolHandler McpToolResult("tableId is required", isError = true)
                surfacePoker(everyCall = false)
                services.pokerAgent.state(tableId).toToolResult()
            },
        ),
        McpToolDefinition(
            name = "poker_sit",
            description = "Sit the signed-in user at a poker table. ONLY when the user explicitly asked you " +
                "to play poker — sitting stakes their play-money bankroll in a live game with teammates. " +
                "Choose an empty seat (poker_state shows which are taken) and a buyIn inside the table's " +
                "range from poker_lobby. The Arcade tab switches to the poker table so the user watches live.",
            inputSchema = """{"type":"object","properties":{"tableId":{"type":"string"},""" +
                """"seat":{"type":"integer","description":"Empty seat number"},""" +
                """"buyIn":{"type":"integer","description":"Chips to bring, within the table's buy-in range"}},""" +
                """"required":["tableId","seat","buyIn"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val tableId = args.string("tableId")
                    ?: return@McpToolHandler McpToolResult("tableId is required", isError = true)
                val seat = args.int("seat")
                    ?: return@McpToolHandler McpToolResult("seat is required", isError = true)
                val buyIn = args.int("buyIn")
                    ?: return@McpToolHandler McpToolResult("buyIn is required", isError = true)
                surfacePoker(everyCall = true)
                services.pokerAgent.sit(tableId, seat, buyIn.toLong()).toToolResult()
            },
        ),
        McpToolDefinition(
            name = "poker_leave",
            description = "Leave a poker table as the signed-in user; the remaining stack returns to their " +
                "bankroll (leaving mid-hand folds it). Leave when the user asks to stop — prefer finishing " +
                "the current hand first.",
            inputSchema = """{"type":"object","properties":{"tableId":{"type":"string"}},""" +
                """"required":["tableId"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val tableId = args.string("tableId")
                    ?: return@McpToolHandler McpToolResult("tableId is required", isError = true)
                surfacePoker(everyCall = true)
                services.pokerAgent.leave(tableId).toToolResult()
            },
        ),
        McpToolDefinition(
            name = "poker_act",
            description = "Make the user's poker move: fold, check, call, bet or raise. Handles versioning " +
                "itself (fetches state, retries once on a conflict) and returns the resulting events plus the " +
                "new state summary. amount is required for bet/raise and is the TOTAL committed this street — " +
                "'raise TO', at least minRaiseTo from poker_state — never a delta. Only act when poker_state " +
                "says yourTurn (respect actionDeadlineInMs or the server auto-folds). You are playing the " +
                "user's chips in front of their teammates: play sensibly, no needless all-ins. The user " +
                "watches every move live in the Arcade tab.",
            inputSchema = """{"type":"object","properties":{"tableId":{"type":"string"},""" +
                """"kind":{"type":"string","description":"fold | check | call | bet | raise"},""" +
                """"amount":{"type":"integer","description":"bet/raise only: TOTAL chips committed this street"}},""" +
                """"required":["tableId","kind"]}""",
            readOnly = false,
            handler = McpToolHandler { args ->
                val tableId = args.string("tableId")
                    ?: return@McpToolHandler McpToolResult("tableId is required", isError = true)
                val kind = args.string("kind")
                    ?: return@McpToolHandler McpToolResult("kind is required", isError = true)
                surfacePoker(everyCall = true)
                services.pokerAgent.act(tableId, kind, args.int("amount")?.toLong()).toToolResult()
            },
        ),
        McpToolDefinition(
            name = "arcade_2048_keep_going",
            description = "Dismiss the 'You hit 2048' dialog and continue the current run toward higher tiles.",
            readOnly = false,
            handler = McpToolHandler {
                withGame { game ->
                    game.keepGoing()
                    McpToolResult(game.snapshotJson())
                }
            },
        ),
    )

    /**
     * Resolve the game to drive, making it visible: navigate the open Arcade
     * tab to the 2048 board, opening a fresh Arcade tab first when none exists.
     */
    private suspend inline fun withGame(
        crossinline block: suspend (Game2048ViewModel) -> McpToolResult,
    ): McpToolResult {
        if (services.activeArcadeTab == null) {
            runCatching { services.splitView?.openTab(ArcadeTabInfo()) }
            withTimeoutOrNull(2500) {
                while (services.activeArcadeTab == null) delay(100)
            }
        }
        val game = services.activeArcadeTab?.let { runCatching { it.showGame2048() }.getOrNull() }
            ?: services.activeGame2048
            ?: return McpToolResult(
                "No Arcade tab is available and one could not be opened. Ask the user " +
                    "to open an Arcade tab (new tab → Arcade) — your moves will play " +
                    "out on their screen.",
                isError = true,
            )
        return block(game)
    }

    /** Poker tools first show the table, then answer; games error instead — see [surfacePoker]. */
    private val pokerShown = AtomicBoolean(false)

    /**
     * Agent play must be visible: bring the poker table onto the user's screen, opening a fresh
     * Arcade tab when none exists. The embedded web app mirrors the agent's actions live over
     * realtime, so showing the screen is all that is needed. Unlike 2048/Wordle this is
     * best-effort, not a precondition — poker is played server-side over HTTP, so a console that
     * cannot show the screen (no browser engine) still plays, and other players still see it.
     * Read-only tools surface it once per plugin session; mutating tools re-surface every call.
     */
    private suspend fun surfacePoker(everyCall: Boolean) {
        if (!everyCall && !pokerShown.compareAndSet(false, true)) return
        pokerShown.set(true)
        if (services.activeArcadeTab == null) {
            runCatching { services.splitView?.openTab(ArcadeTabInfo()) }
            withTimeoutOrNull(2500) {
                while (services.activeArcadeTab == null) delay(100)
            }
        }
        services.activeArcadeTab?.let { runCatching { it.showPoker() } }
    }

    private fun Result<String>.toToolResult(): McpToolResult = fold(
        onSuccess = { McpToolResult(it) },
        onFailure = { McpToolResult(it.message ?: "Poker failed for an unknown reason.", isError = true) },
    )

    /** Same as [withGame], but surfaces the Wordle board. */
    private suspend inline fun withWordle(
        crossinline block: suspend (WordleViewModel) -> McpToolResult,
    ): McpToolResult {
        if (services.activeArcadeTab == null) {
            runCatching { services.splitView?.openTab(ArcadeTabInfo()) }
            withTimeoutOrNull(2500) {
                while (services.activeArcadeTab == null) delay(100)
            }
        }
        val game = services.activeArcadeTab?.let { runCatching { it.showWordle() }.getOrNull() }
            ?: services.activeWordle
            ?: return McpToolResult(
                "No Arcade tab is available and one could not be opened. Ask the user " +
                    "to open an Arcade tab (new tab → Arcade) — your guesses will play " +
                    "out on their screen.",
                isError = true,
            )
        return block(game)
    }
}
