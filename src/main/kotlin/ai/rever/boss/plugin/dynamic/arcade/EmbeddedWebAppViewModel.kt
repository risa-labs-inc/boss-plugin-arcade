package ai.rever.boss.plugin.dynamic.arcade

import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Console-SSO codes (poker_sso_code(), pa_arcade_sso_code()) are 24 random
 * bytes hex-encoded.
 */
internal val SSO_CODE_SHAPE = Regex("[0-9a-f]{48}")

/**
 * The code in the raw result of an SSO-code RPC (a bare or JSON-quoted
 * string), or null when it does not have [SSO_CODE_SHAPE]. The one parse every
 * SSO caller goes through, so the embedded browsers and the poker MCP client
 * cannot disagree about what a valid code looks like.
 */
internal fun ssoCodeOrNull(rpcResult: String?): String? =
    rpcResult
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeIf { it.matches(SSO_CODE_SHAPE) }

/**
 * The URL to open for [baseUrl] given the raw result of an SSO-code RPC: the
 * code rides along as `?sso=` when it has the expected shape, otherwise the
 * plain URL, where the web app shows its own sign-in. Pure so it is testable.
 */
internal fun ssoUrlFor(baseUrl: String, rpcResult: String?): String {
    val code = ssoCodeOrNull(rpcResult)
    return if (code != null) "$baseUrl/?sso=$code" else baseUrl
}

/**
 * Console SSO: mint a one-time code as the signed-in BOSS user through
 * [ssoRpc] and hand it to the web app in the URL, so opening the tab signs the
 * player in with zero clicks. Codes are single-use and short-lived, minted
 * fresh per browser creation. Any failure (no provider, signed out, RPC not
 * deployed, network, a throw of any kind) falls back to the plain URL.
 */
internal suspend fun mintSsoUrl(supabase: SupabaseDataProvider?, ssoRpc: String, baseUrl: String): String {
    val raw = supabase?.let { runCatching { it.rpc(ssoRpc, "{}").getOrNull() }.getOrNull() }
    return ssoUrlFor(baseUrl, raw)
}

/**
 * Owns the embedded browser showing an external web app (poker, PA Arcade).
 * Like the game VMs, the tab component keeps this for the tab's lifetime, so
 * hopping Home <-> game never reloads the page and you keep your place.
 *
 * The handle is a plain field, not Compose state: [phase] is what the screen
 * recomposes on, and the handle itself must survive the screen leaving the
 * composition (see above).
 */
open class EmbeddedWebAppViewModel(
    private val scope: CoroutineScope,
    private val services: ArcadeServices,
    /** Where the web app lives; also shown in the no-browser fallback. */
    val baseUrl: String,
    /** Supabase RPC that mints the one-time console-SSO code for this app. */
    private val ssoRpc: String,
) {
    enum class Phase { CREATING, READY, UNAVAILABLE }

    var phase by mutableStateOf(Phase.CREATING)
        private set
    var unavailableReason by mutableStateOf("")
        private set

    private var handle: BrowserHandle? = null
    private var creating = false

    /**
     * Lazily create the browser on first show (and again if the host has since
     * invalidated the old handle, e.g. a browser-engine restart).
     */
    fun ensureBrowser() {
        if (creating || handle?.isValid == true) return
        handle?.dispose()
        handle = null
        // Raw is null on consoles whose plugin-api predates the browser package
        // (as well as when the host has no JxBrowser); the `as?` never resolves
        // BrowserService on a null receiver, so old hosts never load the class.
        val raw = services.browserServiceRaw
        if (raw == null) {
            phase = Phase.UNAVAILABLE
            unavailableReason = "Playing inside BOSS needs a newer BOSS console version."
            return
        }
        val service = runCatching { raw as? BrowserService }.getOrNull()
        if (service == null || !service.isAvailable()) {
            phase = Phase.UNAVAILABLE
            unavailableReason = "BOSS's embedded browser isn't available on this machine."
            return
        }
        creating = true
        phase = Phase.CREATING
        scope.launch {
            val url = mintSsoUrl(services.supabase, ssoRpc, baseUrl)
            val created = runCatching { service.createBrowser(BrowserConfig(url = url)) }.getOrNull()
            if (created != null) {
                // In-app links (target=_blank, window.open) stay inside the tab.
                created.setOpenInNewTabCallback { link -> scope.launch { created.loadUrl(link) } }
                handle = created
                phase = Phase.READY
            } else {
                phase = Phase.UNAVAILABLE
                unavailableReason = "The embedded browser could not be created."
            }
            creating = false
        }
    }

    /** The live handle, or null when it isn't usable (screen shows the fallback). */
    fun browser(): BrowserHandle? = handle?.takeIf { it.isValid }

    fun onDisposed() {
        handle?.dispose()
        handle = null
    }
}
