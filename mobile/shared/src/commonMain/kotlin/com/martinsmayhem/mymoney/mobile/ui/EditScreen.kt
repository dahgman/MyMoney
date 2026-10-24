package com.martinsmayhem.mymoney.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.martinsmayhem.mymoney.mobile.CategoryRef
import com.martinsmayhem.mymoney.mobile.Format
import com.martinsmayhem.mymoney.mobile.LedgerView
import com.martinsmayhem.mymoney.mobile.Phone
import com.martinsmayhem.mymoney.mobile.TxnDraft
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.abs

/** Adding a transaction, or changing (or deleting) one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(phone: Phone, view: LedgerView, platform: Platform, nav: Navigator, screen: Screen.Edit) {
    val existing = screen.txnUid?.let { view.txn(it) }
    val synced = view.accounts().flatMap { listOf(it) + it.envelopes }
    val scope = rememberCoroutineScope()

    var accountUid by remember { mutableStateOf(existing?.accountUid ?: screen.accountUid ?: synced.firstOrNull()?.uid ?: "") }
    var deposit by remember { mutableStateOf((existing?.amountCents ?: -1) > 0) }
    var amount by remember { mutableStateOf(existing?.let { Format.money(abs(it.amountCents)).removePrefix("$") } ?: "") }
    var date by remember { mutableStateOf(existing?.date ?: platform.today()) }
    var payee by remember { mutableStateOf(existing?.payee ?: "") }
    var category by remember { mutableStateOf(existing?.category) }
    var tag by remember { mutableStateOf(existing?.tag ?: "") }
    var memo by remember { mutableStateOf(existing?.memo ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var photos by remember { mutableStateOf(listOf<com.martinsmayhem.mymoney.mobile.ReceiptImage>()) }   // a new one's
    // A cleared transaction's money stays put: only its payee, category and status (TxnView.canChange).
    fun can(field: String) = existing == null || existing.canChange(field)
    var num by remember { mutableStateOf(existing?.num ?: "") }
    var status by remember { mutableStateOf(if (screen.enter) "U" else existing?.status ?: "U") }
    var problem by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var pickCategory by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val cents = Format.parseMoney(amount)
        if (cents == null || cents == 0L) {
            problem = "Enter the amount."
            return
        }
        if (accountUid.isBlank()) {
            problem = "Choose the account."
            return
        }
        val d = TxnDraft(accountUid, date, num, payee, category, tag.ifBlank { null }, memo, description,
            if (deposit) cents else -cents, status)
        scope.launch {
            try {
                if (existing == null) {
                    val uid = phone.add(d)
                    photos.forEach { phone.attach(uid, it) }
                } else {
                    phone.edit(existing.uid, d)
                    photos.forEach { phone.attach(existing.uid, it) }   // receipts added here
                }
                platform.syncSoon()
                nav.back()
            } catch (e: Exception) {
                problem = e.message
            }
        }
    }

    Scaffold(topBar = {
        Bar(when {
            screen.enter -> "Enter scheduled"
            existing == null -> "New transaction"
            else -> "Change transaction"
        }, back = { nav.back() }) {
            TextButton(onClick = ::save) { Text("Save") }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Account, Payment/Deposit, Date, Amount, Payee, Category, Description, then receipts.
            // (Tag and memo aren't shown; a transaction's own are kept as they are.)
            if (existing == null) {
                Picker("Account", synced.firstOrNull { it.uid == accountUid }?.name ?: "", synced.map { it.uid to it.name }) {
                    accountUid = it
                }
            } else {
                OutlinedTextField(view.account(existing.accountUid)?.name ?: "", {}, label = { Text("Account") },
                    enabled = false, modifier = Modifier.fillMaxWidth())   // a transaction stays in its account
            }
            if (existing?.cleared == true) {
                Hint("Cleared: only its payee, category and status can be changed. To change the rest, set its" +
                    " status to None and save first.", Modifier)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!deposit, { deposit = false }, label = { Text("Payment") }, enabled = can("amountCents"))
                FilterChip(deposit, { deposit = true }, label = { Text("Deposit") }, enabled = can("amountCents"))
            }
            Box {
                OutlinedTextField(Format.date(date, view.dateFormat()), {}, label = { Text("Date") }, readOnly = true,
                    enabled = can("date"), modifier = Modifier.fillMaxWidth())
                if (can("date")) Box(Modifier.matchParentSize().clickable { pickDate = true })
            }
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true, prefix = { Text("$") },
                enabled = can("amountCents"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Suggest("Payee", payee, view.payees(), Modifier.fillMaxWidth()) { payee = it }
            Box {
                OutlinedTextField(view.categoryLabel(category), {}, label = { Text("Category or transfer") }, readOnly = true,
                    enabled = can("category"), modifier = Modifier.fillMaxWidth())
                if (can("category")) Box(Modifier.matchParentSize().clickable { pickCategory = true })
            }
            OutlinedTextField(description, { description = it }, label = { Text("Description") }, enabled = can("description"),
                modifier = Modifier.fillMaxWidth())
            if (existing?.mirror != true) {
                Text("Receipts", style = MaterialTheme.typography.titleSmall)
                val saved = existing?.let { view.phonePhotos(it.uid) } ?: emptyList()
                val thumbs = saved.map { storedThumbnail(phone, platform, it.receiptId) to (if (it.waiting) "Waiting to send" else null) } +
                    remember(photos) { photos.map { platform.imageBitmap(it.thumbnail) to "Added" } }
                Thumbnails(thumbs)
                ReceiptButtons(platform, { problem = it }) { photos = photos + it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(num, { num = it }, label = { Text("Num") }, singleLine = true, enabled = can("num"),
                    modifier = Modifier.weight(1f))
                Box(Modifier.weight(1f)) {
                    Picker("Status", Format.status(status), Format.phoneStatuses.map { it to Format.status(it) }) { status = it }
                }
            }
            problem?.let { Text(it, color = Negative) }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (existing != null && !screen.enter) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete", color = Negative)
                }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDays() * DAY)
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { date = LocalDate.fromEpochDays(it / DAY) }
                pickDate = false
            }) { Text("OK") }
        }, dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }) {
            DatePicker(state)
        }
    }
    if (pickCategory) {
        CategoryPicker(view, accountUid, transfers = existing?.cleared != true, { pickCategory = false }) {
            category = it
            pickCategory = false
        }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("It's deleted on the computer too, at the next sync.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            phone.delete(existing.uid)
                            platform.syncSoon()
                            nav.back()
                            if ((nav.current as? Screen.Detail)?.txnUid == existing.uid) nav.back()   // its details too
                        } catch (e: Exception) {
                            problem = e.message
                        }
                    }
                }) { Text("Delete", color = Negative) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
}

private const val DAY = 86_400_000L

/** A field that opens a list of choices (value, label). */
@Composable
fun Picker(label: String, shown: String, choices: List<Pair<String, String>>, chosen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(shown, {}, label = { Text(label) }, readOnly = true, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(open, { open = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    chosen(value)
                    open = false
                })
            }
        }
    }
}

/** A text field that suggests from a list as you type (like the computer's autocomplete). */
@Composable
fun Suggest(label: String, value: String, all: List<String>, modifier: Modifier, changed: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val matches = remember(value, all) {
        if (value.isBlank()) emptyList()
        else all.filter { it.contains(value.trim(), ignoreCase = true) && !it.equals(value.trim(), ignoreCase = true) }
            .sortedBy { !it.startsWith(value.trim(), ignoreCase = true) }.take(6)
    }
    Column(modifier) {
        OutlinedTextField(value, { changed(it); focused = true }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        if (focused && matches.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                matches.forEach { m ->
                    Text(m, Modifier.fillMaxWidth().clickable {
                        changed(m)
                        focused = false
                    }.padding(vertical = 8.dp, horizontal = 8.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Choose a category, a transfer to another account, a new category (typed), or none. */
@Composable
private fun CategoryPicker(view: LedgerView, accountUid: String, transfers: Boolean, dismiss: () -> Unit,
                           chosen: (CategoryRef?) -> Unit) {
    var search by remember { mutableStateOf("") }
    val q = search.trim()
    val transfers = if (!transfers) emptyList()                  // a cleared one can't become a transfer
    else view.transferTargets(accountUid).filter { q.isEmpty() || it.name.contains(q, true) }
    val categories = view.snapshot.categories.filter { q.isEmpty() || it.path.contains(q, true) }
    val exact = view.snapshot.categories.any { it.path.equals(q, true) }
    AlertDialog(onDismissRequest = dismiss, confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        title = { Text("Category") },
        text = {
            Column {
                OutlinedTextField(search, { search = it }, label = { Text("Search, or type a new one") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (q.isNotEmpty() && !exact) item {
                        Choice("New category: $q") { chosen(CategoryRef.New(q)) }
                    }
                    if (q.isEmpty()) item { Choice("(none)") { chosen(null) } }
                    if (transfers.isNotEmpty()) item { Hint("Transfer to", Modifier.padding(top = 8.dp)) }
                    items(transfers, key = { "t" + it.uid }) { a -> Choice("[${a.name}]") { chosen(CategoryRef.Transfer(a.uid)) } }
                    if (categories.isNotEmpty()) item { Hint("Categories", Modifier.padding(top = 8.dp)) }
                    items(categories, key = { "c" + it.uid }) { c -> Choice(c.path) { chosen(CategoryRef.Existing(c.uid)) } }
                }
            }
        })
}

@Composable
private fun Choice(text: String, pick: () -> Unit) {
    Column {
        Text(text, Modifier.fillMaxWidth().clickable(onClick = pick).padding(vertical = 10.dp))
        HorizontalDivider()
    }
}
