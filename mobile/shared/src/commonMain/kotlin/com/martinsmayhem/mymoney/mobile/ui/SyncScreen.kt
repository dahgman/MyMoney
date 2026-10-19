package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.LedgerView
import com.martinsmayhem.mymoney.mobile.Phone
import kotlinx.coroutines.launch

/** How syncing is going: when it last worked, what's waiting, what the computer didn't take. And unpairing. */
@Composable
fun SyncScreen(phone: Phone, view: LedgerView, nav: Navigator, appVersion: String) {
    val scope = rememberCoroutineScope()
    val s = view.state
    var syncing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }
    Scaffold(topBar = { Bar("Sync", back = { nav.back() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Paired with ${s.pairing?.fileName ?: ""}", style = MaterialTheme.typography.titleMedium)
            Text("Mailbox: ${s.pairing?.mailbox?.url ?: ""}${s.pairing?.mailbox?.path ?: ""} (as ${s.user})")
            Text(s.lastSync?.let { "Last synced ${whenText(it)}" } ?: "Not synced yet")
            s.lastError?.let { Text("Last try: $it", color = Negative) }
            Text(if (s.pending.isEmpty()) "Everything you've changed is on the computer."
            else "${s.pending.size} changes not yet on the computer (${s.pending.count { it.sentSeq != null }} sent, " +
                "waiting for the computer to take them).")
            Button(enabled = !syncing, modifier = Modifier.fillMaxWidth(), onClick = {
                syncing = true
                scope.launch {
                    result = phone.sync().message
                    syncing = false
                }
            }) { Text(if (syncing) "Syncing…" else "Sync now") }
            result?.let { Hint(it, Modifier) }

            if (s.notices.isNotEmpty()) {
                Text("From the computer", style = MaterialTheme.typography.titleMedium)
                s.notices.forEach { n ->
                    Card(Modifier.fillMaxWidth()) {
                        Text((if (n.refused) "Not applied: " else "") + n.message, Modifier.padding(12.dp))
                    }
                }
                OutlinedButton(onClick = { scope.launch { phone.dismissNotices() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear these")
                }
            }
            OutlinedButton(onClick = { confirmUnpair = true }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text("Unpair this phone", color = Negative)
            }
            Hint("MyMoney $appVersion", Modifier.fillMaxWidth().padding(top = 16.dp))
        }
    }
    if (confirmUnpair) {
        AlertDialog(onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair this phone?") },
            text = {
                Text("Everything is removed from this phone" +
                    (if (s.pending.isNotEmpty()) ", including ${s.pending.size} changes the computer doesn't have yet" else "") +
                    ". Remove it on the computer too (File > Sync Settings).")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch {
                        phone.unpair()
                        nav.home()
                    }
                }) { Text("Unpair", color = Negative) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } })
    }
}
