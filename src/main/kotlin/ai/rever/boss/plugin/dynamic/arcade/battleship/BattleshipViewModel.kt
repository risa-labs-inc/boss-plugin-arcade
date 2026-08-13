package ai.rever.boss.plugin.dynamic.arcade.battleship

import ai.rever.boss.plugin.dynamic.arcade.ArcadeEvent
import ai.rever.boss.plugin.dynamic.arcade.ArcadeServices
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Async head-to-head Battleship.
 *
 * Unlike every other Arcade game this one has no run loop — a "turn" may be
 * hours apart, so the model is: pick an opponent, place a fleet, then fire one
 * shot whenever it is your turn. Everything authoritative lives on the server
 * (see supabase/arcade_battleship.sql); this class only ever holds what the
 * server was willing to tell this player.
 */
class BattleshipViewModel(
    private val scope: CoroutineScope,
    private val services: ArcadeServices,
) {
    companion object {
        const val GAME = "battleship"

        /** How often an open board re-checks while waiting on the opponent. */
        private const val POLL_MS = 10_000L
    }

    enum class Phase { LOBBY, PLACING, BOARD }

    /** What submitting the fleet currently being placed will do. */
    private sealed interface PlacementIntent {
        data class Challenge(val opponent: BattleshipPlayer) : PlacementIntent
        data class Accept(val match: MatchSummary) : PlacementIntent
    }

    var phase by mutableStateOf(Phase.LOBBY)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    // --- lobby ---
    val matches = mutableStateListOf<MatchSummary>()
    val opponents = mutableStateListOf<BattleshipPlayer>()
    val standings = mutableStateListOf<Standing>()
    var showOpponentPicker by mutableStateOf(false)
        private set

    // --- placement ---
    val placed = mutableStateListOf<BattleshipLogic.Ship>()
    var orientation by mutableStateOf(BattleshipLogic.Orientation.HORIZONTAL)
        private set
    private var intent: PlacementIntent? = null

    /** The next ship still needing a home, or null once the fleet is complete. */
    val nextShip: BattleshipLogic.ShipType?
        get() = BattleshipLogic.FLEET.firstOrNull { type -> placed.none { it.type == type } }

    val placementComplete: Boolean
        get() = BattleshipLogic.isCompleteFleet(placed.toList())

    val placementTitle: String
        get() = when (val i = intent) {
            is PlacementIntent.Challenge -> "Challenge ${i.opponent.displayName}"
            is PlacementIntent.Accept -> "Accept ${i.match.opponentName}"
            null -> "Place your fleet"
        }

    private val occupied: Set<Int>
        get() = placed.flatMap { it.cells }.toSet()

    // --- board ---
    var detail by mutableStateOf<MatchDetail?>(null)
        private set
    var lastOutcome by mutableStateOf<FireOutcome?>(null)
        private set
    private var openMatchId: String? = null
    private var pollJob: Job? = null

    val service: BattleshipService get() = services.battleship

    /** Matches waiting on this player — what the home card badges. */
    val actionableCount: Int
        get() = matches.count { it.myTurn || it.awaitingMyAnswer }

    init {
        refreshLobby()
    }

    fun dismissMessage() {
        message = null
    }

    // ---------------------------------------------------------------- lobby

    fun refreshLobby() {
        if (!service.isAvailable || !service.isSignedIn) {
            message = "Sign in to play against your team"
            return
        }
        scope.launch {
            busy = true
            service.myMatches()
                .onSuccess { matches.replaceWith(it) }
                .onFailure { message = it.friendly() }
            service.standings().onSuccess { standings.replaceWith(it) }
            busy = false
        }
    }

    fun openOpponentPicker() {
        showOpponentPicker = true
        scope.launch {
            service.players()
                .onSuccess { opponents.replaceWith(it) }
                .onFailure { message = it.friendly() }
        }
    }

    fun dismissOpponentPicker() {
        showOpponentPicker = false
    }

    fun challengeOpponent(player: BattleshipPlayer) {
        showOpponentPicker = false
        beginPlacement(PlacementIntent.Challenge(player))
    }

    fun acceptChallenge(match: MatchSummary) = beginPlacement(PlacementIntent.Accept(match))

    fun declineChallenge(match: MatchSummary) {
        scope.launch {
            busy = true
            service.decline(match.matchId)
                .onSuccess { refreshLobby() }
                .onFailure { message = it.friendly() }
            busy = false
        }
    }

    // ------------------------------------------------------------ placement

    private fun beginPlacement(next: PlacementIntent) {
        intent = next
        placed.clear()
        orientation = BattleshipLogic.Orientation.HORIZONTAL
        message = null
        phase = Phase.PLACING
    }

    fun rotate() {
        orientation = if (orientation == BattleshipLogic.Orientation.HORIZONTAL) {
            BattleshipLogic.Orientation.VERTICAL
        } else {
            BattleshipLogic.Orientation.HORIZONTAL
        }
    }

    /** True when the ship currently being placed would fit starting at [cell]. */
    fun canPlaceAt(cell: Int): Boolean {
        val type = nextShip ?: return false
        return BattleshipLogic.canPlace(type, cell, orientation, occupied)
    }

    fun placeAt(cell: Int) {
        val type = nextShip ?: return
        val cells = BattleshipLogic.span(type, cell, orientation)
        if (cells == null || cells.any { it in occupied }) {
            message = "${type.label} doesn't fit there"
            return
        }
        placed += BattleshipLogic.Ship(type, cells)
    }

    /** Remove the ship occupying [cell], so a misplacement can be redone. */
    fun clearAt(cell: Int) {
        placed.removeAll { cell in it.cells }
    }

    fun randomizeFleet() {
        placed.replaceWith(BattleshipLogic.randomFleet())
    }

    fun clearFleet() {
        placed.clear()
    }

    fun cancelPlacement() {
        intent = null
        placed.clear()
        phase = Phase.LOBBY
    }

    fun submitFleet() {
        val ships = placed.toList()
        val current = intent ?: return
        if (!BattleshipLogic.isCompleteFleet(ships)) {
            message = "Place all five ships first"
            return
        }
        scope.launch {
            busy = true
            val result = when (current) {
                is PlacementIntent.Challenge -> service.challenge(current.opponent.userId, ships)
                    .map { it }
                is PlacementIntent.Accept -> service.accept(current.match.matchId, ships)
                    .map { current.match.matchId }
            }
            result
                .onSuccess { matchId ->
                    services.leaderboard.recordEvent(
                        services.pluginScope, GAME, ArcadeEvent.START,
                    )
                    intent = null
                    placed.clear()
                    // A fresh challenge has nothing to look at until the other
                    // side accepts, so only jump straight to a playable board.
                    if (current is PlacementIntent.Accept) openMatch(matchId) else {
                        phase = Phase.LOBBY
                        message = "Challenge sent"
                        refreshLobby()
                    }
                }
                .onFailure { message = it.friendly() }
            busy = false
        }
    }

    // ---------------------------------------------------------------- board

    fun openMatch(matchId: String) {
        openMatchId = matchId
        detail = null
        lastOutcome = null
        phase = Phase.BOARD
        loadDetail()
        startPolling()
    }

    fun leaveBoard() {
        pollJob?.cancel()
        pollJob = null
        openMatchId = null
        detail = null
        phase = Phase.LOBBY
        refreshLobby()
    }

    fun refreshBoard() = loadDetail()

    private fun loadDetail() {
        val matchId = openMatchId ?: return
        scope.launch {
            busy = true
            service.matchDetail(matchId)
                .onSuccess { detail = it }
                .onFailure { message = it.friendly() }
            busy = false
        }
    }

    /**
     * Re-check while the opponent holds the turn. Polling stops the moment the
     * turn comes back, so an open board is not a permanent request loop.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val current = detail
                if (current == null || current.finished || current.myTurn) continue
                val matchId = openMatchId ?: break
                service.matchDetail(matchId).onSuccess { detail = it }
            }
        }
    }

    fun fireAt(cell: Int) {
        val current = detail ?: return
        val matchId = openMatchId ?: return
        if (!current.myTurn || current.finished || busy) return
        if (current.myShots.any { it.cell == cell }) {
            message = "You've already fired there"
            return
        }
        scope.launch {
            busy = true
            service.fire(matchId, cell)
                .onSuccess {
                    lastOutcome = it
                    loadDetail()
                }
                .onFailure { message = it.friendly() }
            busy = false
        }
    }

    fun onDisposed() {
        pollJob?.cancel()
        pollJob = null
    }
}

/** Postgres surfaces RPC errors with noisy prefixes; show the human part. */
private fun Throwable.friendly(): String {
    val raw = message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
    return raw.substringAfterLast("ERROR:").substringBefore("\n").trim().ifBlank { raw }
}

private fun <T> androidx.compose.runtime.snapshots.SnapshotStateList<T>.replaceWith(
    items: List<T>,
) {
    clear()
    addAll(items)
}
