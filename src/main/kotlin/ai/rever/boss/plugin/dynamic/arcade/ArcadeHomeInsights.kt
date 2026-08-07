package ai.rever.boss.plugin.dynamic.arcade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
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
import java.time.Duration
import java.time.OffsetDateTime

private data class GameBoard(val title: String, val entries: List<LeaderboardEntry>)

/**
 * "On the board" strip for the Arcade home screen: top-3 podium and player
 * count per game, so the picker itself shows who has been playing and what
 * it takes to beat them. Renders nothing while unavailable — the home page
 * stays clean when signed out or before the first score lands.
 */
@Composable
fun ArcadeHomeInsights(leaderboard: LeaderboardService) {
    var boards by remember { mutableStateOf<List<GameBoard>?>(null) }

    LaunchedEffect(Unit) {
        if (!leaderboard.isAvailable) return@LaunchedEffect
        leaderboard.awaitPendingSubmits()
        boards = listOf("2048" to "2048", "mirror-dash" to "Mirror Dash").map { (key, title) ->
            GameBoard(title, leaderboard.topScores(key, 10).getOrNull().orEmpty())
        }
    }

    val loaded = boards ?: return
    if (loaded.all { it.entries.isEmpty() }) return

    Spacer(Modifier.height(30.dp))
    Text(
        "ON THE BOARD",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        color = ArcadeColors.Muted,
    )
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (board in loaded) {
            if (board.entries.isNotEmpty()) {
                GameBoardCard(board, leaderboard.currentUserId)
            }
        }
    }
}

@Composable
private fun GameBoardCard(board: GameBoard, myUserId: String?) {
    Column(
        modifier = Modifier
            .width(224.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ArcadeColors.Chip.copy(alpha = 0.75f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                board.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = ArcadeColors.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (board.entries.size >= 10) "10+ players" else "${board.entries.size} player" +
                    if (board.entries.size == 1) "" else "s",
                fontSize = 10.sp,
                color = ArcadeColors.Muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        val medals = listOf("🥇", "🥈", "🥉")
        board.entries.take(3).forEachIndexed { i, entry ->
            val isMe = entry.userId != null && entry.userId == myUserId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                Text(medals[i], fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    (entry.displayName ?: "Player") + if (isMe) " (you)" else "",
                    fontSize = 12.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium,
                    color = ArcadeColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%,d".format(entry.bestScore),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = ArcadeColors.Ink,
                )
            }
        }
        latestActivity(board.entries)?.let { note ->
            Spacer(Modifier.height(6.dp))
            Text(note, fontSize = 10.sp, color = ArcadeColors.Muted)
        }
    }
}

/** "Latest: shivang set 1,240 · 2h ago" from the freshest best on the board. */
private fun latestActivity(entries: List<LeaderboardEntry>): String? {
    val latest = entries
        .mapNotNull { e ->
            val at = e.achievedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            if (at == null) null else e to at
        }
        .maxByOrNull { it.second } ?: return null
    val (entry, at) = latest
    val ago = timeAgo(at) ?: return null
    return "Latest: ${entry.displayName ?: "Player"} set ${"%,d".format(entry.bestScore)} · $ago"
}

private fun timeAgo(at: OffsetDateTime): String? {
    val minutes = runCatching {
        Duration.between(at.toInstant(), java.time.Instant.now()).toMinutes()
    }.getOrNull() ?: return null
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}
