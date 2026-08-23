package ai.rever.boss.plugin.dynamic.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Team leaderboard, shown as an overlay above the game. Best score per player,
 * fetched on open and on demand; every failure mode degrades to a message.
 */
@Composable
fun LeaderboardOverlay(
    leaderboard: LeaderboardService,
    game: String,
    onClose: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<LeaderboardEntry>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var weekly by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey, weekly) {
        loading = true
        error = null
        // Order after any in-flight score submit, so a run that just ended is
        // already in the list the player sees.
        leaderboard.awaitPendingSubmits()
        if (!leaderboard.isAvailable) {
            error = "Leaderboard isn't available on this host."
            loading = false
        } else {
            val since = if (weekly) LeaderboardService.weekStartIso() else null
            leaderboard.topScores(game, sinceIso = since).fold(
                onSuccess = { entries = it },
                onFailure = {
                    error = if (weekly) {
                        "Weekly board isn't available yet — the backend needs the updated arcade_leaderboard function."
                    } else {
                        "Couldn't load the leaderboard. Try refresh."
                    }
                },
            )
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CasinoColors.Scrim)
            .plainClickable(onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .neonSign(CasinoColors.Gold, cornerRadius = 18.dp, lit = 0.35f)
                .clip(RoundedCornerShape(18.dp))
                .background(CasinoColors.Panel)
                .plainClickable {}
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Leaderboard — $game",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CasinoColors.TextBright,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.clip(RoundedCornerShape(8.dp)).plainClickable { refreshKey++ }.padding(4.dp)) {
                    Icon(Icons.Outlined.Refresh, "Refresh", tint = CasinoColors.TextSoft)
                }
                Box(Modifier.clip(RoundedCornerShape(8.dp)).plainClickable(onClose).padding(4.dp)) {
                    Icon(Icons.Outlined.Close, "Close", tint = CasinoColors.TextSoft)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PeriodTab("All-time", selected = !weekly) { weekly = false }
                PeriodTab("This week", selected = weekly) { weekly = true }
            }
            Spacer(Modifier.height(10.dp))

            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = CasinoColors.Gold,
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }

                error != null -> Text(
                    error ?: "",
                    fontSize = 13.sp,
                    color = CasinoColors.TextSoft,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                entries.isEmpty() -> Text(
                    if (weekly) "No scores this week yet — the race is wide open!"
                    else "No scores yet — set the first one!",
                    fontSize = 13.sp,
                    color = CasinoColors.TextSoft,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    entries.forEachIndexed { index, entry ->
                        LeaderboardRow(
                            rank = index + 1,
                            entry = entry,
                            isMe = entry.userId != null && entry.userId == leaderboard.currentUserId,
                        )
                    }
                }
            }

            if (!leaderboard.isSignedIn) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Sign in to submit your scores.",
                    fontSize = 11.sp,
                    color = CasinoColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun PeriodTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) CasinoColors.Gold else CasinoColors.PanelDeep)
            .plainClickable(onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (selected) CasinoColors.PanelDeep else CasinoColors.TextSoft,
        )
    }
}

@Composable
private fun LeaderboardRow(rank: Int, entry: LeaderboardEntry, isMe: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isMe) CasinoColors.Gold.copy(alpha = 0.12f) else CasinoColors.Panel)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = CasinoColors.TextMuted,
            modifier = Modifier.width(24.dp),
        )
        Text(
            (entry.displayName ?: "Player") + if (isMe) " (you)" else "",
            fontSize = 13.sp,
            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
            color = CasinoColors.TextBright,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "%,d".format(entry.bestScore),
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            color = CasinoColors.TextBright,
        )
    }
}
