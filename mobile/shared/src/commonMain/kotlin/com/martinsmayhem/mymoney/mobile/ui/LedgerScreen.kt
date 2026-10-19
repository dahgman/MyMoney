package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.Format
import com.martinsmayhem.mymoney.mobile.LedgerRow
import com.martinsmayhem.mymoney.mobile.LedgerView

/** One account's transactions, newest first, with the balance after each. */
@Composable
fun LedgerScreen(view: LedgerView, accountUid: String, nav: Navigator, today: kotlinx.datetime.LocalDate) {
    val rows = view.ledger(accountUid, today)
    val hidden = view.ledger(accountUid, today, com.martinsmayhem.mymoney.mobile.Prefs()).size - rows.size
    val account = view.account(accountUid)
    val balance = view.accounts().flatMap { listOf(it) + it.envelopes }.firstOrNull { it.uid == accountUid }?.balanceCents
    Scaffold(
        topBar = { Bar(account?.name ?: "Account", back = { nav.back() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.go(Screen.Edit(accountUid, null)) }) { Text("+ Transaction") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Balance", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Money(balance ?: 0, bold = true)
                }
                Hint("By status, as on the computer: newest first. Tap one for all its details." +
                    if (hidden > 0) " $hidden hidden (see Settings)." else "")
                HorizontalDivider()
            }
            items(rows, key = { it.txn.uid }) { row ->
                Row(row, view) { nav.go(Screen.Detail(row.txn.uid)) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Row(row: LedgerRow, view: LedgerView, open: () -> Unit) {
    val t = row.txn
    Row(Modifier.fillMaxWidth().clickable(onClick = open)
        .alpha(if (t.editable || t.mirror) 1f else 0.55f).padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // The status column: the computer's colored dots.
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { StatusDot(t.status) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(t.payee.ifBlank { "(no payee)" }, fontWeight = FontWeight.Medium, maxLines = 1)
            val details = listOfNotNull(Format.date(t.date, view.dateFormat()), view.categoryLabel(t).ifBlank { null },
                if (t.receipts > 0) "📎${t.receipts}" else null)
            Hint(details.joinToString(" · "), Modifier)
            if (t.pending) Text("not yet on the computer", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Money(t.amountCents, bold = true)
            Text(Format.money(row.balanceCents), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
