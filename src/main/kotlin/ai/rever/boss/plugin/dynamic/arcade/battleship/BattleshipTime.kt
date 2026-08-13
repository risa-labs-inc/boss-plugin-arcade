package ai.rever.boss.plugin.dynamic.arcade.battleship

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Relative wording for the async board. An async game is unreadable without it:
 * "waiting on them" and "the refresh is broken" look identical until the screen
 * can say how long it has actually been.
 *
 * [now] is injectable so this is testable without freezing the clock.
 */
internal fun relativeTime(iso: String?, now: Instant = Instant.now()): String? {
    if (iso.isNullOrBlank()) return null
    val then = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return null
    val seconds = Duration.between(then, now).seconds
    return when {
        // Clock skew between the server and this machine can put a timestamp
        // slightly in the future; "in -3 minutes" would be worse than rounding.
        seconds < 45 -> "just now"
        seconds < 90 -> "a minute ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 7200 -> "an hour ago"
        seconds < 86_400 -> "${seconds / 3600} hours ago"
        seconds < 172_800 -> "yesterday"
        else -> "${seconds / 86_400} days ago"
    }
}
