package ai.rever.boss.plugin.dynamic.arcade.battleship

import ai.rever.boss.plugin.dynamic.arcade.ArcadeColors
import ai.rever.boss.plugin.dynamic.arcade.ArcadeGhostButton
import ai.rever.boss.plugin.dynamic.arcade.ArcadePrimaryButton
import ai.rever.boss.plugin.dynamic.arcade.plainClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BattleshipScreen(
    viewModel: BattleshipViewModel,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArcadeGhostButton("← Arcade", onClick = {
                if (viewModel.phase == BattleshipViewModel.Phase.LOBBY) onBack()
                else if (viewModel.phase == BattleshipViewModel.Phase.BOARD) viewModel.leaveBoard()
                else viewModel.cancelPlacement()
            })
            Spacer(Modifier.width(12.dp))
            Text(
                "Battleship",
                color = ArcadeColors.Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
            Spacer(Modifier.weight(1f))
            if (viewModel.busy) {
                Text("…", color = ArcadeColors.Muted, fontSize = 18.sp)
            }
        }

        viewModel.message?.let { text ->
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ArcadeColors.Chip)
                    .border(1.dp, ArcadeColors.Frame, RoundedCornerShape(10.dp))
                    .plainClickable { viewModel.dismissMessage() }
                    .padding(10.dp),
            ) {
                Text(text, color = ArcadeColors.InkSoft, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))

        when (viewModel.phase) {
            BattleshipViewModel.Phase.LOBBY -> BattleshipLobby(viewModel)
            BattleshipViewModel.Phase.PLACING -> BattleshipPlacement(viewModel)
            BattleshipViewModel.Phase.BOARD -> BattleshipBoard(viewModel)
        }
    }

    if (viewModel.showOpponentPicker) {
        OpponentPicker(viewModel)
    }
}

// ------------------------------------------------------------------- lobby

@Composable
private fun BattleshipLobby(viewModel: BattleshipViewModel) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArcadePrimaryButton("Challenge someone", onClick = { viewModel.openOpponentPicker() })
            Spacer(Modifier.width(8.dp))
            ArcadeGhostButton(
                if (viewModel.refreshing) "Refreshing…" else "Refresh",
                onClick = { viewModel.refreshLobby() },
                enabled = !viewModel.refreshing,
            )
            // The stamp carries seconds on purpose: an unchanged list is the
            // common case, and without a value that visibly moves the button
            // gives no evidence it did anything.
            viewModel.lobbyCheckedAt?.let { stamp ->
                Spacer(Modifier.width(10.dp))
                Text("Checked $stamp", color = ArcadeColors.Muted, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (viewModel.matches.isEmpty()) {
            Text(
                "No games yet. Challenge a teammate — they can place their fleet and " +
                    "fire whenever they get a minute.",
                color = ArcadeColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.widthIn(max = 460.dp),
            )
        } else {
            Text("Your games", color = ArcadeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            for (match in viewModel.matches) {
                MatchRow(match, viewModel)
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(18.dp))
        NotifyToggle(viewModel)

        Spacer(Modifier.height(20.dp))
        Text("Standings", color = ArcadeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        if (viewModel.standings.isEmpty()) {
            // Always render the heading with a reason. Hiding the whole section
            // when nothing has finished yet is indistinguishable from it being
            // broken, which is exactly how it was first reported.
            Text(
                "Nothing here until a game finishes — standings count won matches, " +
                    "not shots fired.",
                color = ArcadeColors.Muted,
                fontSize = 12.sp,
                modifier = Modifier.widthIn(max = 460.dp),
            )
        } else {
            for ((index, row) in viewModel.standings.withIndex()) {
                Row(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp).padding(vertical = 3.dp),
                ) {
                    Text(
                        "${index + 1}.",
                        color = ArcadeColors.Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(row.displayName, color = ArcadeColors.Ink, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${row.wins}W · ${row.losses}L",
                        color = ArcadeColors.InkSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * Opt-in for toasts. Lives here rather than in host settings because this is
 * where someone is when they decide they care about their games, and because
 * the plugin API has no preferences surface to put it in.
 */
@Composable
private fun NotifyToggle(viewModel: BattleshipViewModel) {
    val on = viewModel.notifyEnabled
    Row(
        modifier = Modifier
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ArcadeColors.Chip)
            .border(1.dp, ArcadeColors.Frame, RoundedCornerShape(10.dp))
            .plainClickable { viewModel.setNotifyPreference(!on) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (on) ArcadeColors.Pink else ArcadeColors.Cell)
                .border(
                    1.dp,
                    if (on) ArcadeColors.PinkDeep else ArcadeColors.Frame,
                    RoundedCornerShape(6.dp),
                )
                .width(18.dp)
                .height(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "Notify me when a game needs my move",
                color = ArcadeColors.Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (on) {
                    "A toast at most once every 30 minutes. Off by default so the " +
                        "Arcade never interrupts your work uninvited."
                } else {
                    "Off. You'll still see a count on the Arcade card whenever you look."
                },
                color = ArcadeColors.Muted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MatchRow(match: MatchSummary, viewModel: BattleshipViewModel) {
    val statusText = when {
        match.isFinished && match.iWon -> "You won"
        match.isFinished -> "You lost"
        match.awaitingMyAnswer -> "Challenged you"
        match.isPending -> "Waiting for them to accept"
        match.myTurn -> "Your move"
        else -> "Their move"
    }
    val highlight = match.myTurn || match.awaitingMyAnswer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 460.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlight) ArcadeColors.Pink.copy(alpha = 0.10f) else ArcadeColors.Chip)
            .border(
                1.dp,
                if (highlight) ArcadeColors.Pink else ArcadeColors.Frame,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    match.opponentName,
                    color = ArcadeColors.Ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Text(
                    // Append staleness: "waiting for them to accept" reads very
                    // differently at two minutes and at two days.
                    relativeTime(match.updatedAt)
                        ?.let { "$statusText · $it" }
                        ?: statusText,
                    color = if (highlight) ArcadeColors.PinkDeep else ArcadeColors.Muted,
                    fontSize = 12.sp,
                )
            }
            when {
                match.awaitingMyAnswer -> Row {
                    ArcadePrimaryButton("Accept", onClick = { viewModel.acceptChallenge(match) })
                    Spacer(Modifier.width(6.dp))
                    ArcadeGhostButton("Decline", onClick = { viewModel.declineChallenge(match) })
                }
                match.isPending -> Text("Sent", color = ArcadeColors.Muted, fontSize = 12.sp)
                else -> ArcadeGhostButton(
                    if (match.isFinished) "Review" else "Open",
                    onClick = { viewModel.openMatch(match.matchId) },
                )
            }
        }
    }
}

@Composable
private fun OpponentPicker(viewModel: BattleshipViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .plainClickable { viewModel.dismissOpponentPicker() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ArcadeColors.Bg1)
                .padding(16.dp)
                // Swallow clicks so tapping the card does not dismiss it.
                .plainClickable { },
        ) {
            Text("Pick an opponent", color = ArcadeColors.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            if (viewModel.opponents.isEmpty()) {
                Text("Nobody else has played yet.", color = ArcadeColors.Muted, fontSize = 12.sp)
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for (player in viewModel.opponents) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .plainClickable { viewModel.challengeOpponent(player) }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                        ) {
                            Text(player.displayName, color = ArcadeColors.Ink, fontSize = 13.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            ArcadeGhostButton("Cancel", onClick = { viewModel.dismissOpponentPicker() })
        }
    }
}

// --------------------------------------------------------------- placement

@Composable
private fun BattleshipPlacement(viewModel: BattleshipViewModel) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            viewModel.placementTitle,
            color = ArcadeColors.Ink,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        val next = viewModel.activeShip
        Text(
            // The repositioning hint stays up even once the fleet is complete —
            // that is exactly the moment you want to nudge a ship, and hiding it
            // behind "Fleet ready." made moving one look impossible.
            if (next != null) {
                "Click the board to place your ${next.label} (${next.length}). " +
                    "Tap any ship below — or on the board — to move it."
            } else {
                "Fleet ready. Tap any ship below, or on the board, to move it."
            },
            color = ArcadeColors.Muted,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArcadeGhostButton(
                if (viewModel.orientation == BattleshipLogic.Orientation.HORIZONTAL) {
                    "Rotate (horizontal)"
                } else {
                    "Rotate (vertical)"
                },
                onClick = { viewModel.rotate() },
            )
            Spacer(Modifier.width(8.dp))
            ArcadeGhostButton("Random", onClick = { viewModel.randomizeFleet() })
            Spacer(Modifier.width(8.dp))
            ArcadeGhostButton("Clear", onClick = { viewModel.clearFleet() })
        }

        Spacer(Modifier.height(10.dp))
        PlacementRoster(
            placedIds = viewModel.placedTypes,
            activeShip = viewModel.activeShip,
            onSelect = { viewModel.selectShip(it) },
        )

        Spacer(Modifier.height(12.dp))
        BattleshipGrid(
            markAt = { cell ->
                when {
                    viewModel.placed.any { cell in it.cells } -> CellMark.SHIP
                    else -> CellMark.EMPTY
                }
            },
            onCellClick = { cell ->
                val onBoard = viewModel.placed.firstOrNull { cell in it.cells }
                // Tapping a placed ship selects it for re-placement rather than
                // just deleting it, so the next board click puts it back down.
                if (onBoard != null) viewModel.selectShip(onBoard.type)
                else viewModel.placeAt(cell)
            },
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArcadePrimaryButton(
                "Submit fleet",
                onClick = { viewModel.submitFleet() },
                enabled = viewModel.placementComplete && !viewModel.busy,
            )
            Spacer(Modifier.width(8.dp))
            ArcadeGhostButton("Cancel", onClick = { viewModel.cancelPlacement() })
        }
    }
}

// ------------------------------------------------------------------- board

@Composable
private fun BattleshipBoard(viewModel: BattleshipViewModel) {
    val detail = viewModel.detail
    if (detail == null) {
        Text("Loading…", color = ArcadeColors.Muted, fontSize = 13.sp)
        return
    }

    val myShots = detail.myShots.associateBy { it.cell }
    val theirShots = detail.theirShots.associateBy { it.cell }
    val myFleetCells = detail.myFleet.flatMap { it.cells }.toSet()
    val sunkByMe = detail.myShots.filter { it.result == "sunk" }
    // The server only names a ship on the shot that sinks it, so the roster is
    // driven by how many sinkings have happened, not by inspecting their fleet.
    val theirSunkCount = sunkByMe.size

    val headline = when {
        detail.finished && detail.iWon -> "You sank ${detail.opponentName}'s fleet"
        detail.finished -> "${detail.opponentName} sank your fleet"
        detail.myTurn -> "Your move — pick a target"
        else -> "Waiting on ${detail.opponentName}"
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                headline,
                color = if (detail.myTurn && !detail.finished) ArcadeColors.PinkDeep else ArcadeColors.Ink,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.width(10.dp))
            ArcadeGhostButton(
                if (viewModel.refreshing) "Refreshing…" else "Refresh",
                onClick = { viewModel.refreshBoard() },
                enabled = !viewModel.refreshing,
            )
        }

        // Waiting is the normal state of an async game, so the screen has to
        // distinguish "nothing has happened yet" from "the refresh is broken".
        if (!detail.finished) {
            Spacer(Modifier.height(4.dp))
            val lastMove = relativeTime(detail.theirLastShotAt)
            val lastSeen = relativeTime(detail.opponentLastSeen)
            val waitingSince = relativeTime(detail.updatedAt)
            val activity = buildList {
                if (lastMove != null) {
                    add("Their last shot $lastMove")
                } else if (!detail.myTurn && waitingSince != null) {
                    add("No shots yet — their move since $waitingSince")
                }
                if (lastSeen != null) add("last in the Arcade $lastSeen")
            }
            if (activity.isNotEmpty()) {
                Text(
                    activity.joinToString(" · "),
                    color = ArcadeColors.Muted,
                    fontSize = 11.sp,
                )
            }
            viewModel.boardCheckedAt?.let { stamp ->
                Text(
                    "Checked $stamp · re-checks itself every 10s while it's their move",
                    color = ArcadeColors.Muted,
                    fontSize = 11.sp,
                )
            }
        }

        viewModel.lastOutcome?.let { outcome ->
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    outcome.sunk != null -> "Sunk their ${outcome.sunk}!"
                    outcome.isHit -> "Hit!"
                    else -> "Miss."
                },
                color = if (outcome.isHit) ArcadeColors.PinkDeep else ArcadeColors.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(14.dp))

        BattleshipBoardLabel(
            "${detail.opponentName}'s waters",
            if (detail.finished) "Match over" else "Click a cell to fire",
        )
        BattleshipGrid(
            markAt = { cell ->
                when (myShots[cell]?.result) {
                    "sunk" -> CellMark.SUNK
                    "hit" -> CellMark.HIT
                    "miss" -> CellMark.MISS
                    else -> CellMark.EMPTY
                }
            },
            onCellClick = if (detail.myTurn && !detail.finished) {
                { cell -> viewModel.fireAt(cell) }
            } else {
                null
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "$theirSunkCount of ${BattleshipLogic.FLEET.size} enemy ships sunk",
            color = ArcadeColors.Muted,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(20.dp))

        BattleshipBoardLabel("Your fleet", "Where they have fired")
        BattleshipGrid(
            markAt = { cell ->
                val shot = theirShots[cell]
                when {
                    shot?.result == "sunk" -> CellMark.SUNK
                    shot?.result == "hit" -> CellMark.HIT
                    shot != null -> CellMark.MISS
                    cell in myFleetCells -> CellMark.SHIP
                    else -> CellMark.EMPTY
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        FleetRoster(
            sunkIds = detail.myFleet
                .filter { ship -> ship.cells.all { theirShots.containsKey(it) } }
                .map { it.id }
                .toSet(),
        )
        Spacer(Modifier.height(20.dp))
    }
}
