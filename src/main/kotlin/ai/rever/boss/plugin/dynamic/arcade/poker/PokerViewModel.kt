package ai.rever.boss.plugin.dynamic.arcade.poker

import ai.rever.boss.plugin.dynamic.arcade.ArcadeServices
import ai.rever.boss.plugin.dynamic.arcade.EmbeddedWebAppViewModel
import kotlinx.coroutines.CoroutineScope

/** Where the poker web app lives. */
const val POKER_URL = "https://boss-poker.web.app"

/** The RPC that mints poker's one-time console-SSO code (see the poker schema). */
internal const val POKER_SSO_RPC = "poker_sso_code"

/**
 * Owns the embedded browser showing the poker web app. The component keeps
 * this for the tab's lifetime, so hopping Home <-> Poker never reloads the
 * table: you stay seated in your hand. All the browser/SSO mechanics live in
 * [EmbeddedWebAppViewModel].
 */
class PokerViewModel(
    scope: CoroutineScope,
    services: ArcadeServices,
) : EmbeddedWebAppViewModel(scope, services, POKER_URL, POKER_SSO_RPC)
