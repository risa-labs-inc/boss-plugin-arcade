package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope

const val ARCADE_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.arcade"

/**
 * Everything the game screens need from the host, null-safe. The plugin works
 * fully offline/logged-out — leaderboard features degrade gracefully.
 */
class ArcadeServices(
    val pluginScope: CoroutineScope,
    val auth: AuthDataProvider?,
    val storage: PluginStorageProvider?,
    val leaderboard: LeaderboardService,
)

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
        )
        this.services = services

        context.tabRegistry.registerTabType(ArcadeTabType) { tabInfo, ctx ->
            ArcadeTabComponent(tabInfo, ctx, services)
        }

        context.registerMcpToolProvider(ArcadeMcpTools(pluginId, services.leaderboard))
    }

    override fun dispose() {
        services = null
    }
}
