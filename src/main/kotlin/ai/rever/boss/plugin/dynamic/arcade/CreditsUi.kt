package ai.rever.boss.plugin.dynamic.arcade

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private fun creditsLabel(value: Long): String = "%,d ${CreditsService.GLYPH}".format(value)

/**
 * Balance pill for the home screen. Renders nothing while credits are
 * unavailable/degraded — the home page stays clean and every game stays free.
 */
@Composable
fun CreditsChip(credits: CreditsService, onRequest: () -> Unit) {
    val snap by credits.snapshot.collectAsState()
    val s = snap ?: return
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ArcadeColors.Chip)
            .border(2.dp, ArcadeColors.Frame, RoundedCornerShape(999.dp))
            .plainClickable(onRequest)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            creditsLabel(s.balance),
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ArcadeColors.Ink,
        )
        Text(
            when (val pending = s.pendingRequest) {
                null -> "${"%,d".format(s.weeklyFloor)} weekly · tap to request more"
                else -> "request pending · ${creditsLabel(pending.amount)}"
            },
            fontSize = 10.sp,
            color = if (s.pendingRequest == null) ArcadeColors.Muted else ArcadeColors.PinkDeep,
        )
    }
}

/**
 * The blocking "out of credits" card, shown over whichever game refused to
 * start a run. The run has NOT begun; dismissing just returns to the game's
 * idle screen.
 */
@Composable
fun InsufficientCreditsCard(
    blocked: BlockedRun,
    credits: CreditsService,
    onRequest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val snap by credits.snapshot.collectAsState()
    val pending = snap?.pendingRequest
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ArcadeColors.Ink.copy(alpha = 0.25f))
            .plainClickable(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(ArcadeColors.Chip)
                .plainClickable {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Out of credits",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ArcadeColors.Ink,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "This run costs ${creditsLabel(blocked.cost)} and you have " +
                    "${creditsLabel(blocked.balance)}. Credits refill to the weekly floor " +
                    "every Monday, or you can ask an admin for a top-up.",
                fontSize = 13.sp,
                color = ArcadeColors.InkSoft,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            if (pending != null) {
                Text(
                    "Request pending: ${creditsLabel(pending.amount)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ArcadeColors.PinkDeep,
                )
                Spacer(Modifier.height(12.dp))
                ArcadeGhostButton("Close", onClick = onDismiss)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ArcadePrimaryButton("Request credits", onClick = onRequest)
                    ArcadeGhostButton("Not now", onClick = onDismiss)
                }
            }
        }
    }
}

/** Amount + note dialog behind "Request credits", from the chip or the card. */
@Composable
fun RequestCreditsDialog(credits: CreditsService, onClose: () -> Unit) {
    var amount by remember { mutableStateOf("1000") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ArcadeColors.Ink.copy(alpha = 0.25f))
            .plainClickable(onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(ArcadeColors.Chip)
                .plainClickable {}
                .padding(24.dp),
        ) {
            Text(
                "Request credits",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ArcadeColors.Ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "An admin approves top-ups inside the Arcade.",
                fontSize = 12.sp,
                color = ArcadeColors.Muted,
            )
            Spacer(Modifier.height(14.dp))
            CreditsFieldLabel("Amount")
            CreditsTextField(
                value = amount,
                onValueChange = { text -> amount = text.filter { it.isDigit() }.take(7) },
            )
            Spacer(Modifier.height(10.dp))
            CreditsFieldLabel("Note (optional)")
            CreditsTextField(value = note, onValueChange = { note = it.take(200) })
            val message = error
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, fontSize = 12.sp, color = ArcadeColors.PinkDeep)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ArcadePrimaryButton(
                    text = if (busy) "Sending…" else "Send request",
                    enabled = !busy && (amount.toLongOrNull() ?: 0L) > 0L,
                    onClick = {
                        val value = amount.toLongOrNull() ?: return@ArcadePrimaryButton
                        busy = true
                        error = null
                        scope.launch {
                            credits.requestCredits(value, note.trim()).fold(
                                onSuccess = { onClose() },
                                onFailure = {
                                    error = if (it.message == "request_pending") {
                                        "You already have a pending request."
                                    } else {
                                        "Couldn't send the request. Try again."
                                    }
                                },
                            )
                            busy = false
                        }
                    },
                )
                ArcadeGhostButton("Cancel", onClick = onClose, enabled = !busy)
            }
        }
    }
}

@Composable
private fun CreditsFieldLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ArcadeColors.InkSoft)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun CreditsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = ArcadeColors.Ink,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ArcadeColors.Cell)
            .border(2.dp, ArcadeColors.Frame, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * Admin queue overlay: every top-up request, pending first, with approve
 * (amount editable) / deny. Visible only behind arcade_is_admin; the server
 * re-checks on resolve, so this is a convenience, not the security boundary.
 */
@Composable
fun AdminCreditsOverlay(credits: CreditsService, onClose: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<CreditRequestRow>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        credits.adminRequests().fold(
            onSuccess = { rows = it },
            onFailure = { error = "Couldn't load requests. Try refresh." },
        )
        loading = false
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
                .width(430.dp)
                .heightIn(max = 520.dp)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(ArcadeColors.Chip)
                .plainClickable {}
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Credit requests",
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
            Spacer(Modifier.height(10.dp))
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = ArcadeColors.Pink,
                        modifier = Modifier.width(28.dp).height(28.dp),
                        strokeWidth = 3.dp,
                    )
                }

                error != null -> Text(
                    error ?: "",
                    fontSize = 13.sp,
                    color = ArcadeColors.InkSoft,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                rows.isEmpty() -> Text(
                    "No requests — everyone is flush.",
                    fontSize = 13.sp,
                    color = ArcadeColors.InkSoft,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                else -> Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rows.forEach { row ->
                        AdminRequestRow(
                            row = row,
                            onResolve = { approve, amount ->
                                scope.launch {
                                    credits.adminResolve(row.id, approve, amount).fold(
                                        onSuccess = { refreshKey++ },
                                        onFailure = { error = "Couldn't resolve that request." },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminRequestRow(
    row: CreditRequestRow,
    onResolve: (approve: Boolean, amount: Long?) -> Unit,
) {
    val pending = row.status == "pending"
    var amount by remember(row.id) { mutableStateOf(row.amount.toString()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pending) ArcadeColors.Pink.copy(alpha = 0.08f) else ArcadeColors.Cell)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.displayName ?: "Player",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = ArcadeColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (pending) "wants ${creditsLabel(row.amount)}" else row.status,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (pending) ArcadeColors.PinkDeep else ArcadeColors.Muted,
            )
        }
        Text(
            "balance ${creditsLabel(row.balance)}" +
                (row.note?.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: ""),
            fontSize = 11.sp,
            color = ArcadeColors.Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (pending) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CreditsTextField(
                    value = amount,
                    onValueChange = { text -> amount = text.filter { it.isDigit() }.take(7) },
                    modifier = Modifier.width(90.dp),
                )
                ArcadePrimaryButton(
                    text = "Approve",
                    enabled = (amount.toLongOrNull() ?: 0L) > 0L,
                    onClick = { onResolve(true, amount.toLongOrNull()) },
                )
                ArcadeGhostButton("Deny", onClick = { onResolve(false, null) })
            }
        }
    }
}
