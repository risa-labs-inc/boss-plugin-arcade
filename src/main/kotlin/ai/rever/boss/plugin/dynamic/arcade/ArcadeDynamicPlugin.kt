package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipNotifier
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipService
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipViewModel
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048ViewModel
import ai.rever.boss.plugin.dynamic.arcade.poker.PokerAgentService
import ai.rever.boss.plugin.dynamic.arcade.poker.PokerViewModel
import ai.rever.boss.plugin.dynamic.arcade.wordle.WordleViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

const val ARCADE_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.arcade"

/**
 * Implemented by the Arcade tab component so the MCP tools can bring the game
 * they're driving onto the user's screen (navigate the tab to that game).
 */
interface ArcadeGameHost {
    fun showGame2048(): Game2048ViewModel

    fun showWordle(): WordleViewModel

    fun showBattleship(): BattleshipViewModel

    fun showPoker(): PokerViewModel
}

/**
 * Everything the game screens need from the host, null-safe. The plugin works
 * fully offline/logged-out — leaderboard features degrade gracefully.
 */
class ArcadeServices(
    private val scopeProvider: () -> CoroutineScope,
    val auth: AuthDataProvider?,
    val storage: PluginStorageProvider?,
    val leaderboard: LeaderboardService,
    val credits: CreditsService,
    val battleship: BattleshipService,
    val splitView: SplitViewOperations?,
    /** Poker mints one-time console-SSO codes through this (see poker_sso_code()). */
    val supabase: SupabaseDataProvider?,
    /**
     * The host's BrowserService, deliberately typed as Any?. Older BOSS
     * consoles bundle a plugin-api without the browser package; naming the
     * type here (or reading context.browserService unguarded) is a
     * NoSuchMethodError at register() that kills the WHOLE arcade on them
     * (bit us in 0.1.22). Poker casts it back with `as?` behind a null check,
     * so browser classes only ever resolve on hosts that have them.
     */
    val browserServiceRaw: Any?,
) {
    /**
     * Resolved on every use, never cached. The host watchdog restarts a plugin
     * sandbox by cancelling its coroutine scope and installing a fresh one
     * WITHOUT re-running register(), so a scope captured at registration is
     * dead for the rest of the session and every launch on it silently does
     * nothing. Caching it is how finished runs stopped reaching the leaderboard.
     */
    val pluginScope: CoroutineScope get() = scopeProvider()

    /**
     * The 2048 game in the most recently opened Arcade tab, exposed so the MCP
     * tools can let an agent play it live on the user's screen. Null when no
     * Arcade tab has a 2048 game.
     */
    @Volatile
    var activeGame2048: Game2048ViewModel? = null

    /** Same idea for the daily Wordle board. */
    @Volatile
    var activeWordle: WordleViewModel? = null

    /** The most recently opened Arcade tab; lets MCP tools surface the board. */
    @Volatile
    var activeArcadeTab: ArcadeGameHost? = null

    /**
     * HTTP client the poker MCP tools play through, as the signed-in user (console SSO).
     * Independent of any tab: the embedded web app is only the spectator view.
     */
    val pokerAgent: PokerAgentService = PokerAgentService(supabase)

    /**
     * The one shared casino-ambience player. Plugin-level on purpose: several
     * open Arcade tabs share it (home-visibility is refcounted inside), so
     * ambience can never double-play.
     */
    val ambience: CasinoAmbiencePlayer = CasinoAmbiencePlayer(storage, scopeProvider)
}

object ArcadeDynamicPlugin : DynamicPlugin {
    override val pluginId = ARCADE_PLUGIN_ID
    override val displayName = "Arcade"
    override val version = "0.1.0"
    override val description = "Quick competitive games with team leaderboards."
    override val author = "Risa Labs"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-arcade"

    private var services: ArcadeServices? = null
    private var notifierJob: Job? = null

    override fun register(context: PluginContext) {
        val storage = context.pluginStorageFactory?.createStorage(pluginId)
        val services = ArcadeServices(
            scopeProvider = { context.pluginScope },
            auth = context.authDataProvider,
            storage = storage,
            leaderboard = LeaderboardService(
                context.supabaseDataProvider,
                context.authDataProvider,
                storage,
            ),
            credits = CreditsService(
                context.supabaseDataProvider,
                context.authDataProvider,
            ) { context.pluginScope },
            battleship = BattleshipService(
                context.supabaseDataProvider,
                context.authDataProvider,
            ),
            splitView = context.splitViewOperations,
            supabase = context.supabaseDataProvider,
            // Throwable-catching on purpose: NoSuchMethodError on pre-browser-API consoles.
            browserServiceRaw = runCatching { context.browserService }.getOrNull(),
        )
        this.services = services

        context.tabRegistry.registerTabType(ArcadeTabType) { tabInfo, ctx ->
            ArcadeTabComponent(tabInfo, ctx, services)
        }

        context.registerMcpToolProvider(ArcadeMcpTools(pluginId, services))

        // Restore the ambience opt-in (default off; a storage failure leaves it off).
        services.ambience.loadPersisted()

        // Watch for Battleship games waiting on this player. Deliberately here
        // and not in the tab: this has to work when no Arcade tab is open, which
        // is precisely when a challenge would otherwise go unnoticed.
        //
        // Cancel any previous watcher first — if register() ever runs twice
        // (a reload, a second window) two loops would double every toast.
        notifierJob?.cancel()
        notifierJob = BattleshipNotifier(
            service = services.battleship,
            notifications = context.notificationProvider,
            storage = storage,
            openBattleship = {
                runCatching {
                    val host = services.activeArcadeTab
                    if (host != null) {
                        host.showBattleship()
                    } else {
                        // No Arcade tab open: make one. The tab registers itself
                        // as activeArcadeTab, and its own home screen loads the
                        // lobby, so the player lands somewhere useful either way.
                        services.splitView?.openTab(ArcadeTabInfo())
                    }
                }
            },
        ).start(services.pluginScope)
    }

    override fun dispose() {
        notifierJob?.cancel()
        notifierJob = null
        // Stops the ambience thread and closes the audio line.
        services?.ambience?.shutdown()
        services = null
    }
}
