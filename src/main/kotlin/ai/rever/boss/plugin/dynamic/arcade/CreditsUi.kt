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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private fun creditsLabel(value: Long): String = "%,d ${CreditsService.GLYPH}".format(value)

/**
 * Balance chip for the home screen, drawn as an actual casino chip: circular,
 * gold-rimmed with alternating edge dashes, balance in the middle. Renders
 * nothing while credits are unavailable/degraded — the home page stays clean
 * and every game stays free.
 */
@Composable
fun CreditsChip(credits: CreditsService, onRequest: () -> Unit) {
    val snap by credits.snapshot.collectAsState()
    val s = snap ?: return
    val pending = s.pendingRequest
    Box(
        modifier = Modifier
            .size(92.dp)
            .drawBehind {
                val radius = size.minDimension / 2f
                val rim = 5.dp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)
                // Faint gold halo so the chip reads as lit on the dark floor.
                drawCircle(CasinoColors.Gold.copy(alpha = 0.14f), radius = radius + 2.dp.toPx(), center = center)
                drawCircle(CasinoColors.Gold.copy(alpha = 0.05f), radius = radius + 5.dp.toPx(), center = center)
                // Chip body + rim base.
                drawCircle(CasinoColors.PanelDeep, radius = radius, center = center)
                drawCircle(
                    CasinoColors.GoldDim,
                    radius = radius - rim / 2f,
                    center = center,
                    style = Stroke(width = rim),
                )
                // Edge dashes: 8 bright segments alternating with the dim rim.
                val inset = rim / 2f
                for (i in 0 until 8) {
                    drawArc(
                        color = CasinoColors.GoldBright,
                        startAngle = i * 45f + 11.25f,
                        sweepAngle = 22.5f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2f, size.height - inset * 2f),
                        style = Stroke(width = rim),
                    )
                }
                // Thin inner ring separating rim from face.
                drawCircle(
                    CasinoColors.Gold.copy(alpha = 0.35f),
                    radius = radius - rim - 2.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .plainClickable(onRequest),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                creditsLabel(s.balance),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = CasinoColors.TextBright,
                maxLines = 1,
            )
            Text(
                if (pending == null) "tap to top up" else "pending ${"%,d".format(pending.amount)}",
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = if (pending == null) CasinoColors.TextMuted else CasinoColors.Alert,
                maxLines = 1,
            )
        }
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
            .background(CasinoColors.Scrim)
            .plainClickable(onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .neonSign(CasinoColors.Gold, cornerRadius = 18.dp, lit = 0.35f)
                .clip(RoundedCornerShape(18.dp))
                .background(CasinoColors.Panel)
                .plainClickable {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Out of credits",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = CasinoColors.TextBright,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "This run costs ${creditsLabel(blocked.cost)} and you have " +
                    "${creditsLabel(blocked.balance)}. Credits refill to the weekly floor " +
                    "every Monday, or you can ask an admin for a top-up.",
                fontSize = 13.sp,
                color = CasinoColors.TextSoft,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            if (pending != null) {
                Text(
                    "Request pending: ${creditsLabel(pending.amount)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = CasinoColors.Alert,
                )
                Spacer(Modifier.height(12.dp))
                CasinoGhostButton("Close", onClick = onDismiss)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CasinoPrimaryButton("Request credits", onClick = onRequest)
                    CasinoGhostButton("Not now", onClick = onDismiss)
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
            .background(CasinoColors.Scrim)
            .plainClickable(onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .neonSign(CasinoColors.Gold, cornerRadius = 18.dp, lit = 0.35f)
                .clip(RoundedCornerShape(18.dp))
                .background(CasinoColors.Panel)
                .plainClickable {}
                .padding(24.dp),
        ) {
            Text(
                "Request credits",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = CasinoColors.TextBright,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "An admin approves top-ups inside the Arcade.",
                fontSize = 12.sp,
                color = CasinoColors.TextMuted,
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
                Text(message, fontSize = 12.sp, color = CasinoColors.Alert)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CasinoPrimaryButton(
                    text = if (busy) "Sending…" else "Send request",
                    enabled = !busy && (amount.toLongOrNull() ?: 0L) > 0L,
                    onClick = {
                        val value = amount.toLongOrNull() ?: return@CasinoPrimaryButton
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
                CasinoGhostButton("Cancel", onClick = onClose, enabled = !busy)
            }
        }
    }
}

@Composable
private fun CreditsFieldLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CasinoColors.TextSoft)
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
            color = CasinoColors.TextBright,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CasinoColors.PanelDeep)
            .border(1.5.dp, CasinoColors.GoldDim.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
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
            .background(CasinoColors.Scrim)
            .plainClickable(onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(430.dp)
                .heightIn(max = 520.dp)
                .neonSign(CasinoColors.Gold, cornerRadius = 18.dp, lit = 0.35f)
                .clip(RoundedCornerShape(18.dp))
                .background(CasinoColors.Panel)
                .plainClickable {}
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Credit requests",
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
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = CasinoColors.Gold,
                        modifier = Modifier.width(28.dp).height(28.dp),
                        strokeWidth = 3.dp,
                    )
                }

                error != null -> Text(
                    error ?: "",
                    fontSize = 13.sp,
                    color = CasinoColors.TextSoft,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                rows.isEmpty() -> Text(
                    "No requests — everyone is flush.",
                    fontSize = 13.sp,
                    color = CasinoColors.TextSoft,
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
            .background(if (pending) CasinoColors.Gold.copy(alpha = 0.10f) else CasinoColors.PanelDeep)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.displayName ?: "Player",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = CasinoColors.TextBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (pending) "wants ${creditsLabel(row.amount)}" else row.status,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (pending) CasinoColors.Gold else CasinoColors.TextMuted,
            )
        }
        Text(
            "balance ${creditsLabel(row.balance)}" +
                (row.note?.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: ""),
            fontSize = 11.sp,
            color = CasinoColors.TextMuted,
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
                CasinoPrimaryButton(
                    text = "Approve",
                    enabled = (amount.toLongOrNull() ?: 0L) > 0L,
                    onClick = { onResolve(true, amount.toLongOrNull()) },
                )
                CasinoGhostButton("Deny", onClick = { onResolve(false, null) })
            }
        }
    }
}
