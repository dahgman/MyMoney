package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.Format
import com.martinsmayhem.mymoney.mobile.LedgerView

/** Scheduled transactions the computer has put in ahead of time; Enter one when it happens. */
@Composable
fun UpcomingScreen(view: LedgerView, nav: Navigator) {
    val list = view.upcoming()
    Scaffold(topBar = { Bar("Upcoming", back = { nav.back() }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (list.isEmpty()) item { Hint("Nothing scheduled.") }
            items(list, key = { it.uid }) { t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.payee.ifBlank { "(no payee)" }, fontWeight = FontWeight.Medium)
                        Hint("${Format.date(t.date, view.dateFormat())} · ${view.account(t.accountUid)?.name ?: ""}", Modifier)
                    }
                    Money(t.amountCents)
                    if (t.editable) TextButton(onClick = { nav.go(Screen.Edit(t.accountUid, t.uid, enter = true)) }) { Text("Enter") }
                }
                HorizontalDivider()
            }
        }
    }
}
