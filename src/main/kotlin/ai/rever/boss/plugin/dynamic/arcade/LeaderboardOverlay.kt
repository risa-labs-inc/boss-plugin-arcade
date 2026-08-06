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
import androidx.compose.ui.draw.shadow
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

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        if (!leaderboard.isAvailable) {
            error = "Leaderboard isn't available on this host."
            loading = false
        } else {
            leaderboard.topScores(game).fold(
                onSuccess = { entries = it },
                onFailure = { error = "Couldn't load the leaderboard. Try refresh." },
            )
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ArcadeColors.Ink.copy(alpha = 0.25f))
            .plainClickable(onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(340.dp)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(ArcadeColors.Chip)
                .plainClickable {}
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Leaderboard — $game",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ArcadeColors.Ink,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.clip(RoundedCornerShape(8.dp)).plainClickable { refreshKey++ }.padding(4.dp)) {
                    Icon(Icons.Outlined.Refresh, "Refresh", tint = ArcadeColors.InkSoft)
                }
                Box(Modifier.clip(RoundedCornerShape(8.dp)).plainClickable(onClose).padding(4.dp)) {
                    Icon(Icons.Outlined.Close, "Close", tint = ArcadeColors.InkSoft)
                }
            }
            Spacer(Modifier.height(12.dp))

            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = ArcadeColors.Pink,
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }

                error != null -> Text(
                    error ?: "",
                    fontSize = 13.sp,
                    color = ArcadeColors.InkSoft,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                entries.isEmpty() -> Text(
                    "No scores yet — set the first one!",
                    fontSize = 13.sp,
                    color = ArcadeColors.InkSoft,
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
                    color = ArcadeColors.Muted,
                )
            }
        }
    }
}

@Composable
private fun LeaderboardRow(rank: Int, entry: LeaderboardEntry, isMe: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isMe) ArcadeColors.Pink.copy(alpha = 0.10f) else ArcadeColors.Chip)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = ArcadeColors.Muted,
            modifier = Modifier.width(24.dp),
        )
        Text(
            (entry.displayName ?: "Player") + if (isMe) " (you)" else "",
            fontSize = 13.sp,
            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
            color = ArcadeColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "%,d".format(entry.bestScore),
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ArcadeColors.Ink,
        )
    }
}
