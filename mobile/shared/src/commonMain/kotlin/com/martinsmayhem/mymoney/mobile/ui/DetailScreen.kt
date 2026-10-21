package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.martinsmayhem.mymoney.mobile.Phone
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.Format
import com.martinsmayhem.mymoney.mobile.LedgerView
import kotlin.math.abs

/** Everything about one transaction, and (if the phone may) a way to change it. */
@Composable
fun DetailScreen(phone: Phone, view: LedgerView, platform: Platform, txnUid: String, nav: Navigator) {
    val t = view.txn(txnUid)
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = {
        Bar("Transaction", back = { nav.back() }) {
            if (t != null && t.editable) TextButton(onClick = { nav.go(Screen.Edit(t.accountUid, t.uid)) }) { Text("Edit") }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (t == null) {
                Text("This transaction isn't here any more (deleted, or the computer moved it).")
                return@Column
            }
            Text(t.payee.ifBlank { "(no payee)" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Money(t.amountCents, bold = true)
                Text(if (t.amountCents < 0) "  payment" else "  deposit", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Field("Account", view.account(t.accountUid)?.name ?: "")
            Field("Date", Format.date(t.date, view.dateFormat()))
            Field("Bank date", t.bankDate?.let { Format.date(it, view.dateFormat()) } ?: "")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Status", Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusDot(t.status)
                Text((if (t.status == "U") "" else "  ") + Format.status(t.status))
            }
            HorizontalDivider()
            Field("Payment", if (t.amountCents < 0) Format.money(abs(t.amountCents)) else "")
            Field("Deposit", if (t.amountCents > 0) Format.money(t.amountCents) else "")
            Field("Category", view.categoryLabel(t))
            Field("Tag", t.tag ?: "")
            Field("Num", t.num)
            Field("Type", t.type ?: "")
            Field("Memo", t.memo)
            Field("Description", t.description)
            val photos = view.phonePhotos(t.uid)
            val waiting = photos.count { it.waiting }
            Field("Receipts", when {
                t.receipts == 0 -> ""
                waiting == 0 -> "${t.receipts} on the computer"
                waiting == t.receipts -> "$waiting waiting to send"
                else -> "${t.receipts - waiting} on the computer, $waiting waiting to send"
            })
            Thumbnails(photos.map { storedThumbnail(phone, platform, it.receiptId) to (if (it.waiting) "Waiting to send" else null) })
            if (!t.mirror) {
                ReceiptButtons(platform, { problem = it }) { image ->
                    scope.launch {
                        try {
                            phone.attach(t.uid, image)
                            platform.syncSoon()
                        } catch (e: Exception) {
                            problem = e.message
                        }
                    }
                }
            }
            problem?.let { Text(it, color = Negative) }
            Text(when {
                t.mirror -> "The other side of a transfer you added. Change it from the other account."
                t.pending -> "Changed on this phone; not yet on the computer."
                !t.editable -> "Reconciled (or calculated by the computer): change it on the computer."
                t.cleared -> "Cleared: only its payee, category and status can be changed."
                else -> ""
            }, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.editable) Button(onClick = { nav.go(Screen.Edit(t.accountUid, t.uid)) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Edit") }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, color = if (value.isBlank()) MaterialTheme.colorScheme.outline
        else MaterialTheme.colorScheme.onSurface)
    }
    HorizontalDivider()
}
