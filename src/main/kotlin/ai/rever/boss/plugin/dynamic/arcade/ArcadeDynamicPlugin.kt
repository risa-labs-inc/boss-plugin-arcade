package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048ViewModel
import kotlinx.coroutines.CoroutineScope

const val ARCADE_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.arcade"

/**
 * Implemented by the Arcade tab component so the MCP tools can bring the game
 * they're driving onto the user's screen (navigate the tab to the 2048 board).
 */
interface ArcadeGameHost {
    fun showGame2048(): Game2048ViewModel
}

/**
 * Everything the game screens need from the host, null-safe. The plugin works
 * fully offline/logged-out — leaderboard features degrade gracefully.
 */
class ArcadeServices(
    val pluginScope: CoroutineScope,
    val auth: AuthDataProvider?,
    val storage: PluginStorageProvider?,
    val leaderboard: LeaderboardService,
    val splitView: SplitViewOperations?,
) {
    /**
     * The 2048 game in the most recently opened Arcade tab, exposed so the MCP
     * tools can let an agent play it live on the user's screen. Null when no
     * Arcade tab has a 2048 game.
     */
    @Volatile
    var activeGame2048: Game2048ViewModel? = null

    /** The most recently opened Arcade tab; lets MCP tools surface the board. */
    @Volatile
    var activeArcadeTab: ArcadeGameHost? = null
}

object ArcadeDynamicPlugin : DynamicPlugin {
    override val pluginId = ARCADE_PLUGIN_ID
    override val displayName = "Arcade"
    override val version = "0.1.0"
    override val description = "Quick competitive games with team leaderboards. First up: 2048."
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-arcade"

    private var services: ArcadeServices? = null

    override fun register(context: PluginContext) {
        val services = ArcadeServices(
            pluginScope = context.pluginScope,
            auth = context.authDataProvider,
            storage = context.pluginStorageFactory?.createStorage(pluginId),
            leaderboard = LeaderboardService(context.supabaseDataProvider, context.authDataProvider),
            splitView = context.splitViewOperations,
        )
        this.services = services

        context.tabRegistry.registerTabType(ArcadeTabType) { tabInfo, ctx ->
            ArcadeTabComponent(tabInfo, ctx, services)
        }

        context.registerMcpToolProvider(ArcadeMcpTools(pluginId, services))
    }

    override fun dispose() {
        services = null
    }
}
