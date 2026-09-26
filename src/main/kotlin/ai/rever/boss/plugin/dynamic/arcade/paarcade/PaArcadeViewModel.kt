package ai.rever.boss.plugin.dynamic.arcade.paarcade

import ai.rever.boss.plugin.dynamic.arcade.ArcadeServices
import ai.rever.boss.plugin.dynamic.arcade.EmbeddedWebAppViewModel
import kotlinx.coroutines.CoroutineScope

/** Where the PA Arcade web app lives. */
const val PA_ARCADE_URL = "https://risa-pa-arcade.web.app"

/**
 * The RPC that mints PA Arcade's one-time console-SSO code. It lives with the
 * web app's backend, not in this repo; if it is unavailable the call fails and
 * the tab opens the plain URL, where the app shows its own sign-in.
 */
internal const val PA_ARCADE_SSO_RPC = "pa_arcade_sso_code"

/**
 * Owns the embedded browser showing the PA Arcade web app. Kept for the tab's lifetime like poker's, so Home <-> PA Arcade never
 * reloads a game in progress. Charges no Arcade credits: the web app is its own
 * game with its own scoring.
 */
class PaArcadeViewModel(
    scope: CoroutineScope,
    services: ArcadeServices,
) : EmbeddedWebAppViewModel(scope, services, PA_ARCADE_URL, PA_ARCADE_SSO_RPC)
