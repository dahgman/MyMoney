package com.martinsmayhem.mymoney.mobile

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A transaction's category, as the phone sends it (docs/SYNC-DESIGN.md, "add"). */
sealed interface CategoryRef {
    data class Existing(val uid: String) : CategoryRef
    data class New(val path: String) : CategoryRef
    data class Transfer(val accountUid: String) : CategoryRef

    fun toJson(): JsonObject = when (this) {
        is Existing -> buildJsonObject { put("uid", JsonPrimitive(uid)) }
        is New -> buildJsonObject { put("path", JsonPrimitive(path)) }
        is Transfer -> buildJsonObject { put("transferAccountUid", JsonPrimitive(accountUid)) }
    }

    companion object {
        fun fromJson(e: JsonElement?): CategoryRef? {
            if (e == null || e is JsonNull) return null
            val o = e.jsonObject
            o["uid"]?.jsonPrimitive?.contentOrNull?.let { return Existing(it) }
            o["transferAccountUid"]?.jsonPrimitive?.contentOrNull?.let { return Transfer(it) }
            o["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return New(it) }
            return null
        }
    }
}

/** A transaction as the phone shows it: the snapshot's, with your changes on top. */
data class TxnView(
    val uid: String,
    val accountUid: String,
    val date: LocalDate,
    val num: String,
    val payee: String,
    val category: CategoryRef?,
    val split: Boolean,
    val tag: String?,
    val memo: String,
    val description: String,
    val bankDate: LocalDate?,
    val type: String?,
    val amountCents: Long,
    val netCents: Long,
    val status: String,
    val editable: Boolean,
    val pending: Boolean,            // changed on the phone; the computer hasn't confirmed it yet
    val receipts: Int,
    val mirror: Boolean = false,     // the other side of a transfer added on the phone (shown, not editable)
) {
    /**
     * Whether a field may change, as on the computer: a cleared transaction's
     * money stays put, so only its payee, category and status may change, and
     * its category not into or out of a transfer.
     */
    fun canChange(field: String): Boolean = editable && (status != "C" || when (field) {
        "payee", "status" -> true
        "category" -> category !is CategoryRef.Transfer
        else -> false
    })

    val cleared get() = status == "C"
}

data class LedgerRow(val txn: TxnView, val balanceCents: Long)

data class AccountView(
    val uid: String,
    val name: String,
    val type: String,
    val balanceCents: Long,
    val clearedCents: Long,
    val pending: Int,
    val envelopes: List<AccountView>,
)

/**
 * What the phone shows: the last snapshot with the pending changes applied on
 * top (docs/SYNC-DESIGN.md, "The mobile app"). Balances move with them; a
 * transfer added here shows in both accounts. Built fresh from the state, so
 * it's never out of step with it.
 */
class LedgerView(val state: PhoneState) {

    val snapshot: Snapshot = state.snapshot ?: Snapshot()
    private val accountsByUid = snapshot.accounts.associateBy { it.uid }
    private val categoriesByUid = snapshot.categories.associateBy { it.uid }

    /** Every transaction shown, in the snapshot's order (date, then entry), with pending ones after. */
    val transactions: List<TxnView>

    init {
        val map = LinkedHashMap<String, TxnView>()
        for (t in snapshot.transactions) map[t.uid] = fromSnapshot(t)
        for (p in state.pending) apply(map, p.op)
        // The other side of each transfer added here, so both accounts show it before the computer does.
        for (p in state.pending) {
            val op = p.op
            if (op.str("op") != "add") continue
            val t = map[op.str("uid")] ?: continue
            val target = (t.category as? CategoryRef.Transfer)?.accountUid ?: continue
            if (accountsByUid[target]?.synced != true) continue
            map["${t.uid}~mirror"] = t.copy(uid = "${t.uid}~mirror", accountUid = target,
                amountCents = -t.amountCents, netCents = -t.netCents,
                category = CategoryRef.Transfer(t.accountUid), editable = false, mirror = true)
        }
        transactions = map.values.toList()
    }

    private fun fromSnapshot(t: SnapTxn) = TxnView(
        uid = t.uid, accountUid = t.accountUid, date = LocalDate.parse(t.date), num = t.num, payee = t.payee,
        category = t.transferAccountUid?.let { CategoryRef.Transfer(it) } ?: t.categoryUid?.let { CategoryRef.Existing(it) },
        split = t.split, tag = t.tag, memo = t.memo, description = t.description,
        bankDate = t.bankDate?.let { LocalDate.parse(it) }, type = t.type, amountCents = t.amountCents, netCents = t.netCents,
        status = t.status, editable = t.editable, pending = false, receipts = t.receipts,
    )

    private fun apply(map: LinkedHashMap<String, TxnView>, op: JsonObject) {
        val uid = op.str("uid") ?: return
        when (op.str("op")) {
            "add" -> if (uid !in map) {
                val amount = op["amountCents"]?.jsonPrimitive?.longOrNull ?: 0
                map[uid] = TxnView(
                    uid = uid, accountUid = op.str("accountUid") ?: return, date = LocalDate.parse(op.str("date") ?: return),
                    num = op.str("num") ?: "", payee = op.str("payee") ?: "", category = CategoryRef.fromJson(op["category"]),
                    split = false, tag = op.str("tag"), memo = op.str("memo") ?: "",
                    description = op.str("description") ?: "", bankDate = null, type = null, amountCents = amount,
                    netCents = amount, status = op.str("status") ?: "U", editable = true, pending = true, receipts = 0,
                )
            }
            "edit" -> {
                val t = map[uid] ?: return
                var n = t.copy(pending = true)
                val changes = op["changes"]?.jsonObject ?: return
                for ((field, change) in changes) {
                    val v = change.jsonObject["new"]
                    n = when (field) {
                        "date" -> n.copy(date = LocalDate.parse(v.content() ?: continue))
                        "num" -> n.copy(num = v.content() ?: "")
                        "payee" -> n.copy(payee = v.content() ?: "")
                        "category" -> n.copy(category = CategoryRef.fromJson(v))
                        "tag" -> n.copy(tag = v.content()?.ifBlank { null })
                        "memo" -> n.copy(memo = v.content() ?: "")
                        "description" -> n.copy(description = v.content() ?: "")
                        "status" -> n.copy(status = v.content() ?: "U")
                        "amountCents" -> {
                            val a = v?.jsonPrimitive?.longOrNull ?: 0
                            n.copy(amountCents = a, netCents = n.netCents + (a - n.amountCents))
                        }
                        else -> n
                    }
                }
                map[uid] = n
            }
            "delete" -> map.remove(uid)
        }
    }

    /** The accounts the phone shows: synced ones, envelopes under their parents, balances with pending changes. */
    fun accounts(): List<AccountView> {
        val delta = HashMap<String, Long>()
        for (t in transactions) delta[t.accountUid] = (delta[t.accountUid] ?: 0) + t.netCents
        for (t in snapshot.transactions) delta[t.accountUid] = (delta[t.accountUid] ?: 0) - t.netCents
        val pendingBy = transactions.filter { it.pending }.groupingBy { it.accountUid }.eachCount()
        fun view(a: SnapAccount, envelopes: List<AccountView>) = AccountView(
            uid = a.uid, name = a.name, type = a.type,
            balanceCents = (a.balanceCents ?: 0) + (delta[a.uid] ?: 0),
            clearedCents = a.clearedCents ?: 0, pending = pendingBy[a.uid] ?: 0, envelopes = envelopes,
        )
        val synced = snapshot.accounts.filter { it.synced && !it.closed }
        return synced.filter { !it.isEnvelope || synced.none { p -> p.uid == it.parentUid } }.map { a ->
            view(a, synced.filter { it.isEnvelope && it.parentUid == a.uid }.map { view(it, emptyList()) })
        }
    }

    fun account(uid: String): SnapAccount? = accountsByUid[uid]

    /**
     * An account's transactions in the computer's ledger order (by status:
     * Reconciled, Cleared, transfers to an envelope, Pending, Paid, Scheduled,
     * then none; each by date; transactions dated after today last, by date),
     * with the balance after each running in that order, so the last cleared
     * one shows the cleared balance. Returned last first, as the phone shows
     * it (newest at the top). The phone's settings can leave out reconciled
     * transactions, and scheduled ones dated after today (the balances still
     * count them).
     */
    fun ledger(accountUid: String, today: LocalDate, prefs: Prefs = state.prefs): List<LedgerRow> {
        var balance = accountsByUid[accountUid]?.ledgerStartCents ?: 0
        val rows = transactions.filter { it.accountUid == accountUid }.withIndex()
            .sortedWith(compareBy<IndexedValue<TxnView>>({ it.value.date > today })
                .thenBy { if (it.value.date > today) 0 else statusRank(it.value) }
                .thenBy { it.value.date }.thenBy { it.index })
            .map { (_, t) ->
                balance += t.netCents
                LedgerRow(t, balance)
            }
        return rows.reversed().filter { r ->
            !(prefs.hideReconciled && r.txn.status == "R") &&
                !(prefs.hideFutureScheduled && r.txn.status == "S" && r.txn.date > today)
        }
    }

    /** As the computer ranks statuses (LedgerTableModel.statusRank); none goes last. */
    private fun statusRank(t: TxnView): Int {
        val target = (t.category as? CategoryRef.Transfer)?.accountUid
        if (target != null && accountsByUid[target]?.isEnvelope == true) return 2
        return when (t.status) {
            "R" -> 0
            "C", "I" -> 1
            "P" -> 3
            "D" -> 4
            "S" -> 5
            else -> 6
        }
    }

    /** Scheduled transactions (if the computer sends them), soonest first. */
    fun upcoming(): List<TxnView> = transactions.filter { it.status == "S" && !it.mirror }.sortedBy { it.date }

    fun txn(uid: String): TxnView? = transactions.firstOrNull { it.uid == uid }

    /** "Food:Groceries", "[Savings]", "Split", or "" for none. */
    fun categoryLabel(t: TxnView): String = if (t.split) "Split" else categoryLabel(t.category)

    fun categoryLabel(c: CategoryRef?): String = when (c) {
        null -> ""
        is CategoryRef.Existing -> categoriesByUid[c.uid]?.path ?: "(deleted category)"
        is CategoryRef.New -> c.path
        is CategoryRef.Transfer -> "[" + (accountsByUid[c.accountUid]?.name ?: "deleted account") + "]"
    }

    /** Accounts a transfer can go to: every open one (synced or not) but this. */
    fun transferTargets(fromAccountUid: String): List<SnapAccount> =
        snapshot.accounts.filter { !it.closed && it.uid != fromAccountUid }.sortedBy { it.name.lowercase() }

    /** Payee names for suggestions: the computer's, and any typed here since. */
    fun payees(): List<String> = (snapshot.payees + transactions.filter { it.pending }.map { it.payee })
        .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    fun dateFormat(): String = snapshot.settings.dateFormat
}

internal fun JsonObject.str(key: String): String? = this[key].content()

internal fun JsonElement?.content(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.contentOrNull
