package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.AccountView
import com.martinsmayhem.mymoney.mobile.LedgerView
import com.martinsmayhem.mymoney.mobile.Phone
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The synced accounts with their balances, envelopes under their parents. */
@Composable
fun AccountsScreen(phone: Phone, view: LedgerView, platform: Platform, nav: Navigator) {
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val state = view.state
    val accounts = view.accounts()
    // Just paired (or never synced): sync now. Started here, not on the pairing screen, which goes
    // away as soon as pairing is done (and would take its sync with it).
    LaunchedEffect(state.pairing?.deviceId) {
        if (state.lastSync == null) {
            syncing = true
            phone.sync()
            syncing = false
        }
    }
    Scaffold(
        topBar = {
            Bar(state.pairing?.fileName?.ifBlank { null } ?: "MyMoney") {
                TextButton(enabled = !syncing, onClick = {
                    syncing = true
                    scope.launch {
                        phone.sync()
                        syncing = false
                    }
                }) { Text(if (syncing) "Syncing…" else "Sync") }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(menu, { menu = false }) {
                        if (view.snapshot.settings.showScheduled) {
                            DropdownMenuItem(text = { Text("Upcoming") }, onClick = { menu = false; nav.go(Screen.Upcoming) })
                        }
                        DropdownMenuItem(text = { Text("Sync status") }, onClick = { menu = false; nav.go(Screen.Sync) })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; nav.go(Screen.Settings) })
                    }
                }
            }
        },
        floatingActionButton = {
            if (accounts.isNotEmpty()) ExtendedFloatingActionButton(onClick = { nav.go(Screen.Edit(null, null)) }) {
                Text("+ Transaction")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SyncLine(view, nav) }
            if (state.notices.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth().padding(12.dp).clickable { nav.go(Screen.Sync) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("${state.notices.size} of your changes need a look. Tap to see.",
                        Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (accounts.isEmpty()) item {
                Hint(if (state.snapshot == null) "Waiting for the computer's first sync. On the computer, choose Sync > Sync Now."
                else "No accounts are synced. On the computer, tick them in Sync > Settings.")
            }
            items(accounts, key = { it.uid }) { a ->
                AccountRow(a, indent = false) { nav.go(Screen.Ledger(a.uid)) }
                a.envelopes.forEach { e -> AccountRow(e, indent = true) { nav.go(Screen.Ledger(e.uid)) } }
                HorizontalDivider()
            }
            if (accounts.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Total", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Money(accounts.sumOf { it.balanceCents }, bold = true)
                }
            }
        }
    }
}

@Composable
private fun AccountRow(a: AccountView, indent: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open)
        .padding(start = if (indent) 36.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(a.name, style = if (indent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium)
            if (a.pending > 0) Hint("${a.pending} not yet on the computer", Modifier)
        }
        Money(a.balanceCents, bold = !indent)
    }
}

/** "Synced 2:15 PM", or why it didn't, and how much is waiting. Tap for details. */
@Composable
fun SyncLine(view: LedgerView, nav: Navigator) {
    val s = view.state
    val text = when {
        s.lastError != null -> "⚠ ${s.lastError}"
        s.lastSync != null -> "Synced ${whenText(s.lastSync)}"
        else -> "Not synced yet"
    } + if (s.pending.isNotEmpty()) " · ${s.pending.size} waiting to go" else ""
    Text(text, Modifier.fillMaxWidth().clickable { nav.go(Screen.Sync) }.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (s.lastError != null) Negative else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An ISO instant as local "Oct 19, 2:15 PM". */
fun whenText(iso: String): String = try {
    val l = kotlin.time.Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())
    val h = if (l.hour % 12 == 0) 12 else l.hour % 12
    val month = l.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    "$month ${l.day}, $h:${l.minute.toString().padStart(2, '0')} ${if (l.hour < 12) "AM" else "PM"}"
} catch (e: Exception) {
    iso
}
