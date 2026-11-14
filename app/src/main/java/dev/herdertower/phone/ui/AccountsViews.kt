package dev.herdertower.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdertower.phone.api.AccountMove
import dev.herdertower.phone.api.AccountUsage
import dev.herdertower.phone.api.AccountsApi
import dev.herdertower.phone.api.AccountsBoard
import dev.herdertower.phone.api.UsageWindow

/** What the accounts strip says, in words (the strip and the tests read these). */
object AccountsModel {
    /** "Accounts · orca out until 15:00", "Accounts · new sessions start on orca", "Accounts". */
    fun head(b: AccountsBoard, now: Long, clock: (Long) -> String = ::clock): String {
        val out = b.list.filter { AccountsApi.out(it, now) }
        val def = b.list.firstOrNull { it.default }?.name
        return "Accounts" + when {
            b.waiting.isNotEmpty() -> " · every account is out: ${b.waiting.size} waiting"
            out.size == 1 -> " · ${out[0].name} " + AccountsApi.outLine(out[0], now, clock).orEmpty()
            out.size > 1 -> " · ${out.size} out"
            b.startsOn != null && def != null && b.startsOn != def -> " · new sessions start on ${b.startsOn}"
            else -> ""
        }
    }

    /** A window's row: "5h  42%  ↻15:00". */
    fun window(label: String, w: UsageWindow?, clock: (Long) -> String = ::clock): String =
        listOf(label, AccountsApi.percent(w), AccountsApi.resets(w, clock)).filter { it.isNotBlank() }.joinToString("  ")

    /** A move, in a line: "fix-login (sandbox): default → orca at 15:02, 5-hour limit"; a failed one says so. */
    fun move(m: AccountMove, clock: (Long) -> String = ::clock): String {
        val who = (m.name?.takeIf { it.isNotBlank() } ?: m.address) + (m.space?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        val at = if (m.atMs > 0) " at ${clock(m.atMs)}" else ""
        val why = m.why?.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
        return if (m.how == "failed") "$who: couldn't move from ${m.from}$at$why" else "$who: ${m.from} → ${m.to}$at$why"
    }

    /** Whether a window is near its limit: amber from 80 %. */
    fun high(w: UsageWindow?): Boolean = (w?.used ?: 0.0) >= AccountsApi.HIGH
}

/**
 * Each Claude account's usage, as the Mac's sidebar strip: one row an
 * account (its badge, its fullest window as a bar, the percent), amber from
 * 80 %, "out until 15:00" while out. Tap: both windows of each, when they
 * reset, and what the failover moved lately.
 */
@Composable
fun AccountsStrip(board: AccountsBoard, now: Long, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tc.card)
            .clickable(role = Role.Button, onClickLabel = if (open) "Fold the accounts" else "Show each account's usage") { open = !open }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(AccountsModel.head(board, now), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = tc.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = tc.faint)
        }
        board.list.forEach { a -> AccountRow(a, now, whole = open, startsHere = open && board.startsOn == a.name && !a.default) }
        if (open) {
            board.waiting.takeIf { it.isNotEmpty() }?.let { w ->
                Text(
                    "Waiting for an account: " + w.joinToString(", ") { it.address } + ". The tower carries on with them when one is back.",
                    style = MaterialTheme.typography.bodySmall, color = tc.decisionInk,
                )
            }
            board.moves.takeLast(4).reversed().takeIf { it.isNotEmpty() }?.let { moves ->
                Text("Moved lately", style = MaterialTheme.typography.labelMedium, color = tc.faint, modifier = Modifier.padding(top = 2.dp))
                moves.forEach { m -> Text(AccountsModel.move(m), style = MaterialTheme.typography.bodySmall, color = if (m.how == "failed") tc.failed else tc.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun AccountRow(a: AccountUsage, now: Long, whole: Boolean, startsHere: Boolean) {
    val out = AccountsApi.outLine(a, now, ::clock)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountBadge(a.name, a.default)
            if (!whole) {
                val (label, w) = AccountsApi.fullest(a) ?: ("7d" to null)
                UsageBar(w, Modifier.weight(1f))
                Text(if (w == null) "–" else "$label ${AccountsApi.percent(w)}", style = MaterialTheme.typography.labelMedium, color = if (AccountsModel.high(w)) tc.decisionInk else tc.muted, maxLines = 1)
            } else {
                Text(
                    listOfNotNull(a.email?.takeIf { it.isNotBlank() }, if (startsHere) "new sessions start here" else null).joinToString(" · "),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = tc.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (whole) AccountsApi.rows(a).forEach { (label, w) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium, color = tc.faint)
                UsageBar(w, Modifier.weight(1f))
                Text(
                    listOf(AccountsApi.percent(w), AccountsApi.resets(w, ::clock)).filter { it.isNotBlank() }.joinToString("  "),
                    style = MaterialTheme.typography.labelMedium, color = if (AccountsModel.high(w)) tc.decisionInk else tc.muted, maxLines = 1,
                )
            }
        }
        out?.let { Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.labelMedium, color = tc.decisionInk, fontWeight = FontWeight.Medium) }
    }
}

/** A window's bar: how much of its limit is used, amber from 80 %. */
@Composable
private fun UsageBar(w: UsageWindow?, modifier: Modifier = Modifier) {
    val used = ((w?.used ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val color: Color = if (AccountsModel.high(w)) tc.decision else tc.live
    Box(
        modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(tc.edge)
            .semantics { contentDescription = if (w == null) "not reported" else "${AccountsApi.percent(w)} used" },
    ) {
        if (used > 0f) Box(Modifier.fillMaxWidth(used).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** A seat's account under a session or a tower: its badge, and "moved from default at 15:02" when the failover moved it. */
@Composable
fun SeatAccount(board: AccountsBoard?, address: String?, account: String?, defaultAccount: String?, modifier: Modifier = Modifier) {
    val acc = account?.takeIf { it.isNotBlank() }
    val extra = AccountsApi.now(board, address) ?: acc?.let { AccountsApi.movedTo(board, address, it) }?.let { AccountsApi.movedLine(it, ::clock) }
    if (acc == null && extra == null) return
    Row(modifier.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        acc?.let { AccountBadge(it, isDefault = it == defaultAccount) }
        extra?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = tc.decisionInk, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
