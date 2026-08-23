package ai.rever.boss.plugin.dynamic.arcade.poker

import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.dynamic.arcade.ArcadeServices
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where the poker web app lives. Placeholder host — the final URL lands before release. */
const val POKER_URL = "https://boss-poker.web.app"

/**
 * Owns the embedded browser showing the poker web app. Like the game VMs, the
 * component keeps this for the tab's lifetime, so hopping Home <-> Poker never
 * reloads the table — you stay seated in your hand.
 *
 * The handle is a plain field, not Compose state: [phase] is what the screen
 * recomposes on, and the handle itself must survive the screen leaving the
 * composition (see above).
 */
class PokerViewModel(
    private val scope: CoroutineScope,
    private val services: ArcadeServices,
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
        val service = services.browserService
        if (service == null || !service.isAvailable()) {
            phase = Phase.UNAVAILABLE
            unavailableReason = "BOSS's embedded browser isn't available on this machine."
            return
        }
        creating = true
        phase = Phase.CREATING
        scope.launch {
            val created = runCatching { service.createBrowser(BrowserConfig(url = POKER_URL)) }.getOrNull()
            if (created != null) {
                // Table links (target=_blank, window.open) stay inside the tab.
                created.setOpenInNewTabCallback { url -> scope.launch { created.loadUrl(url) } }
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
