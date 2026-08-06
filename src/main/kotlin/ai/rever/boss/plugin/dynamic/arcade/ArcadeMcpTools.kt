package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

class ArcadeMcpTools(
    override val providerId: String,
    private val leaderboard: LeaderboardService,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "arcade_leaderboard",
            description = "Current Arcade leaderboard: top scores per player for a game (default: 2048).",
            inputSchema = """{"type":"object","properties":{"game":{"type":"string","description":"Game key, e.g. 2048"},"limit":{"type":"integer","description":"Max entries (default 10)"}},"required":[]}""",
            handler = McpToolHandler { args ->
                val game = args.string("game") ?: "2048"
                val limit = (args.int("limit") ?: 10).coerceIn(1, 50)
                leaderboard.topScores(game, limit).fold(
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
    )
}
