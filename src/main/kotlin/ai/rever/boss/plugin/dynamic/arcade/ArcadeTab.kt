package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.NewTabContext
import ai.rever.boss.plugin.api.NewTabSpec
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipScreen
import ai.rever.boss.plugin.dynamic.arcade.battleship.BattleshipViewModel
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048Screen
import ai.rever.boss.plugin.dynamic.arcade.game2048.Game2048ViewModel
import ai.rever.boss.plugin.dynamic.arcade.mirrordash.MirrorDashScreen
import ai.rever.boss.plugin.dynamic.arcade.mirrordash.MirrorDashViewModel
import ai.rever.boss.plugin.dynamic.arcade.skystack.SkyStackScreen
import ai.rever.boss.plugin.dynamic.arcade.skystack.SkyStackViewModel
import ai.rever.boss.plugin.dynamic.arcade.typingsprint.TypingSprintScreen
import ai.rever.boss.plugin.dynamic.arcade.typingsprint.TypingSprintViewModel
import ai.rever.boss.plugin.dynamic.arcade.wordle.WordleScreen
import ai.rever.boss.plugin.dynamic.arcade.wordle.WordleViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

object ArcadeTabType : TabTypeInfo {
    override val typeId = TabTypeId(typeId = "arcade", pluginId = ARCADE_PLUGIN_ID)
    override val displayName = "Arcade"
    override val icon: ImageVector = Icons.Outlined.SportsEsports

    override val newTabSpec = NewTabSpec(
        order = 300,
        inputLabel = "",
        inputPlaceholder = "",
        inputOptional = true,
        confirmLabel = "Play",
    )

    override fun createTabInfo(input: String, context: NewTabContext): TabInfo = ArcadeTabInfo()
}

data class ArcadeTabInfo(
    override val id: String = UUID.randomUUID().toString(),
    override val title: String = "Arcade",
) : TabInfo {
    override val typeId = ArcadeTabType.typeId
    override val icon: ImageVector = Icons.Outlined.SportsEsports
}

class ArcadeTabComponent(
    override val config: TabInfo,
    ctx: ComponentContext,
    private val services: ArcadeServices,
) : TabComponentWithUI, ArcadeGameHost, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = ArcadeTabType

    private val componentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var screen by mutableStateOf<ArcadeScreen>(ArcadeScreen.Home)

    // Created on first play and kept for the tab's lifetime, so hopping
    // Home <-> game never resets a run in progress.
    private var game2048: Game2048ViewModel? = null
    private var mirrorDash: MirrorDashViewModel? = null
    private var skyStack: SkyStackViewModel? = null
    private var typingSprint: TypingSprintViewModel? = null
    private var wordle: WordleViewModel? = null
    private var battleship: BattleshipViewModel? = null

    init {
        services.activeArcadeTab = this
        // Deliver anything an earlier session recorded but never got to send.
        services.leaderboard.flushPending(services.pluginScope)
        // Reach, including the people who open the Arcade and never score.
        services.leaderboard.recordEvent(
            services.pluginScope, LeaderboardService.ARCADE_KEY, ArcadeEvent.OPEN,
        )
        lifecycle.doOnDestroy {
            game2048?.onDisposed()
            mirrorDash?.onDisposed()
            skyStack?.onDisposed()
            typingSprint?.onDisposed()
            wordle?.onDisposed()
            battleship?.onDisposed()
            if (services.activeGame2048 === game2048) services.activeGame2048 = null
            if (services.activeWordle === wordle) services.activeWordle = null
            if (services.activeArcadeTab === this) services.activeArcadeTab = null
            componentScope.cancel()
        }
    }

    /** MCP entry point: make the 2048 board the visible screen and return its game. */
    override fun showGame2048(): Game2048ViewModel {
        val vm = game2048()
        screen = ArcadeScreen.Game2048
        return vm
    }

    /** MCP entry point: make the Wordle board the visible screen and return its game. */
    override fun showWordle(): WordleViewModel {
        val vm = wordle()
        screen = ArcadeScreen.Wordle
        return vm
    }

    private fun game2048(): Game2048ViewModel =
        game2048 ?: Game2048ViewModel(componentScope, services).also {
            game2048 = it
            services.activeGame2048 = it
        }

    private fun mirrorDash(): MirrorDashViewModel =
        mirrorDash ?: MirrorDashViewModel(componentScope, services).also { mirrorDash = it }

    private fun skyStack(): SkyStackViewModel =
        skyStack ?: SkyStackViewModel(componentScope, services).also { skyStack = it }

    private fun typingSprint(): TypingSprintViewModel =
        typingSprint ?: TypingSprintViewModel(componentScope, services).also { typingSprint = it }

    /** Toast/MCP entry point: surface the Battleship lobby on screen. */
    override fun showBattleship(): BattleshipViewModel {
        val vm = battleship()
        screen = ArcadeScreen.Battleship
        vm.refreshLobby()
        return vm
    }

    private fun battleship(): BattleshipViewModel =
        battleship ?: BattleshipViewModel(componentScope, services).also { battleship = it }

    private fun wordle(): WordleViewModel =
        wordle ?: WordleViewModel(componentScope, services).also {
            wordle = it
            services.activeWordle = it
        }

    @Composable
    override fun Content() {
        ArcadeBackground {
            when (screen) {
                ArcadeScreen.Home -> ArcadeHomeScreen(
                    leaderboard = services.leaderboard,
                    onPlay2048 = { screen = ArcadeScreen.Game2048 },
                    onPlayMirrorDash = { screen = ArcadeScreen.MirrorDash },
                    onPlaySkyStack = { screen = ArcadeScreen.SkyStack },
                    onPlayTypingSprint = { screen = ArcadeScreen.TypingSprint },
                    onPlayWordle = { screen = ArcadeScreen.Wordle },
                    onPlayBattleship = { screen = ArcadeScreen.Battleship },
                    // Created here rather than on first play: the badge is the
                    // point, and a lazily-created VM would read 0 until you had
                    // already opened the game you were meant to be nudged into.
                    battleshipWaiting = battleship().actionableCount,
                )
                ArcadeScreen.Game2048 -> Game2048Screen(
                    viewModel = game2048(),
                    leaderboard = services.leaderboard,
                    onBack = { screen = ArcadeScreen.Home },
                )
                ArcadeScreen.MirrorDash -> MirrorDashScreen(
                    viewModel = mirrorDash(),
                    leaderboard = services.leaderboard,
                    onBack = {
                        mirrorDash?.pauseIfPlaying()
                        screen = ArcadeScreen.Home
                    },
                )
                ArcadeScreen.SkyStack -> SkyStackScreen(
                    viewModel = skyStack(),
                    leaderboard = services.leaderboard,
                    onBack = {
                        skyStack?.pauseIfPlaying()
                        screen = ArcadeScreen.Home
                    },
                )
                ArcadeScreen.TypingSprint -> TypingSprintScreen(
                    viewModel = typingSprint(),
                    leaderboard = services.leaderboard,
                    onBack = { screen = ArcadeScreen.Home },
                )
                ArcadeScreen.Wordle -> WordleScreen(
                    viewModel = wordle(),
                    leaderboard = services.leaderboard,
                    onBack = { screen = ArcadeScreen.Home },
                )
                ArcadeScreen.Battleship -> BattleshipScreen(
                    viewModel = battleship(),
                    onBack = { screen = ArcadeScreen.Home },
                )
            }
        }
    }
}

enum class ArcadeScreen { Home, Game2048, MirrorDash, SkyStack, TypingSprint, Wordle, Battleship }
