package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048ViewModel
import kotlinx.coroutines.delay

/**
 * MCP tools for in-terminal agents (surfaced as mcp__boss__arcade_*):
 * read the leaderboards, and play the live 2048 game in the open Arcade tab —
 * every move renders on the user's screen. Mirror Dash is reflex/real-time, so
 * it has no play tools, only the leaderboard.
 */
class ArcadeMcpTools(
    override val providerId: String,
    private val services: ArcadeServices,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "arcade_leaderboard",
            description = "Arcade leaderboard: top scores per player. Games: 2048, mirror-dash.",
            inputSchema = """{"type":"object","properties":{"game":{"type":"string","description":"Game key: 2048 (default) or mirror-dash"},"limit":{"type":"integer","description":"Max entries (default 10)"}},"required":[]}""",
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
            description = "Slide the live 2048 board up, down, left, or right. The move animates on the user's screen. Returns the settled board JSON; a move that changes nothing reports so.",
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

    private suspend inline fun withGame(
        crossinline block: suspend (Game2048ViewModel) -> McpToolResult,
    ): McpToolResult {
        val game = services.activeGame2048 ?: return McpToolResult(
            "No live 2048 game. Ask the user to open an Arcade tab and select 2048 — " +
                "your moves will play out on their screen.",
            isError = true,
        )
        return block(game)
    }
}
