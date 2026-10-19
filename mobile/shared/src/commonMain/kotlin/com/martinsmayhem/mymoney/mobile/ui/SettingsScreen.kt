package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.LedgerView
import com.martinsmayhem.mymoney.mobile.Phone
import com.martinsmayhem.mymoney.mobile.Prefs
import kotlinx.coroutines.launch

/** The phone's own settings: its look, and what the ledgers leave out. Kept on the phone, not synced. */
@Composable
fun SettingsScreen(phone: Phone, view: LedgerView, nav: Navigator, appVersion: String) {
    val scope = rememberCoroutineScope()
    val prefs = view.state.prefs
    fun set(p: Prefs) {
        scope.launch { phone.setPrefs(p) }
    }
    Scaffold(topBar = { Bar("Settings", back = { nav.back() }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            listOf("system" to "Match the system", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                Row(Modifier.fillMaxWidth().clickable { set(prefs.copy(theme = key)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(prefs.theme == key, { set(prefs.copy(theme = key)) })
                    Text(label)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Ledgers", style = MaterialTheme.typography.titleMedium)
            Toggle("Hide reconciled transactions", prefs.hideReconciled) { set(prefs.copy(hideReconciled = it)) }
            Toggle("Hide scheduled transactions dated after today", prefs.hideFutureScheduled) {
                set(prefs.copy(hideFutureScheduled = it))
            }
            Hint("Hidden ones still count in the balances. Upcoming (in the ⋮ menu) always lists the scheduled ones.", Modifier)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = { nav.go(Screen.Sync) }, modifier = Modifier.fillMaxWidth()) { Text("Sync and pairing") }
            Hint("MyMoney $appVersion", Modifier.fillMaxWidth().padding(top = 16.dp))
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { changed(!on) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(on, changed)
    }
}
