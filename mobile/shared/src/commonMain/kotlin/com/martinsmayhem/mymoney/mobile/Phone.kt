package com.martinsmayhem.mymoney.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A transaction as typed into the phone's form. */
data class TxnDraft(
    val accountUid: String,
    val date: LocalDate,
    val num: String = "",
    val payee: String = "",
    val category: CategoryRef? = null,
    val tag: String? = null,
    val memo: String = "",
    val amountCents: Long,           // negative: a payment; positive: a deposit
    val status: String = "U",
)

/** How a sync went. */
data class SyncOutcome(val ok: Boolean, val sent: Int, val confirmed: Int, val message: String)

/**
 * The phone's side of sync (docs/SYNC-DESIGN.md): pairing, the changes you
 * make, and syncing them with the computer through the mailbox. Everything is
 * saved after each change. One thing at a time (the screen and the background
 * sync share it).
 */
class Phone(
    private val store: PhoneStore,
    private val mailboxFor: (PairingCode, String, String) -> Mailbox,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    suspend fun load() = lock.withLock {
        _state.value = store.load() ?: PhoneState()
    }

    private suspend fun save(s: PhoneState) {
        store.save(s)
        _state.value = s
    }

    // ----- Pairing ------------------------------------------------------------------

    /** Reads a pairing code (the QR code's text). @throws IllegalArgumentException if it isn't one */
    fun readCode(text: String): PairingCode {
        val code = try {
            SyncJson.decodeFromString(PairingCode.serializer(), text.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("That isn't a MyMoney pairing code.")
        }
        require(code.v == 1) { "This pairing code is from a newer MyMoney; update the app." }
        require(runCatching { Base64.decode(code.key) }.getOrNull()?.size == 32) { "That pairing code is damaged." }
        require(code.mailbox.url.isNotBlank()) { "The pairing code has no mailbox address." }
        return code
    }

    /**
     * Pairs with a computer: checks the mailbox works with this user name and
     * password (by reading this phone's folder), then keeps it all. Starts
     * empty: what's there comes with the first snapshot.
     */
    suspend fun pair(code: PairingCode, user: String, password: String) = lock.withLock {
        mailboxFor(code, user, password).list("${code.fileId}/devices/${code.deviceId}")
        save(PhoneState(pairing = code, user = user, password = password))
    }

    suspend fun unpair() = lock.withLock { save(PhoneState()) }

    // ----- Changes ---------------------------------------------------------------------

    @OptIn(ExperimentalUuidApi::class)
    private fun newId() = Uuid.random().toString()

    /** Adds a transaction. @return its uid */
    suspend fun add(d: TxnDraft): String = lock.withLock {
        val uid = newId()
        val s = _state.value
        save(s.copy(pending = s.pending + PendingOp(newId(), addOp(uid, d))))
        uid
    }

    private fun addOp(uid: String, d: TxnDraft, opId: String? = null) = buildJsonObject {
        put("op", JsonPrimitive("add"))
        put("opId", JsonPrimitive(opId ?: newId()))
        put("uid", JsonPrimitive(uid))
        put("accountUid", JsonPrimitive(d.accountUid))
        put("date", JsonPrimitive(d.date.toString()))
        put("num", JsonPrimitive(d.num.trim()))
        put("payee", JsonPrimitive(d.payee.trim()))
        put("category", d.category?.toJson() ?: JsonNull)
        put("tag", d.tag?.trim()?.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("memo", JsonPrimitive(d.memo.trim()))
        put("amountCents", JsonPrimitive(d.amountCents))
        put("status", JsonPrimitive(d.status))
    }

    /**
     * Changes a transaction to match the form. Only fields that differ are
     * sent, each with the value the phone started from, so the computer can
     * tell if it was changed there too. One not yet sent is changed in place.
     */
    suspend fun edit(uid: String, d: TxnDraft) = lock.withLock {
        val s = _state.value
        val view = LedgerView(s)
        val cur = view.txn(uid) ?: throw IllegalStateException("That transaction isn't there any more.")
        require(cur.editable) { "That transaction can't be changed on the phone." }

        val unsentAdd = s.pending.firstOrNull { it.sentSeq == null && it.op.str("op") == "add" && it.op.str("uid") == uid }
        if (unsentAdd != null) {                      // never sent: just change what will be sent
            val replaced = unsentAdd.copy(op = addOp(uid, d.copy(accountUid = cur.accountUid), unsentAdd.opId))
            save(s.copy(pending = s.pending.map { if (it === unsentAdd) replaced else it }))
            return@withLock
        }
        val changes = LinkedHashMap<String, Pair<JsonElement, JsonElement>>()
        fun field(name: String, old: JsonElement, new: JsonElement) {
            if (old != new) changes[name] = old to new
        }
        field("date", JsonPrimitive(cur.date.toString()), JsonPrimitive(d.date.toString()))
        field("num", JsonPrimitive(cur.num), JsonPrimitive(d.num.trim()))
        field("payee", JsonPrimitive(cur.payee), JsonPrimitive(d.payee.trim()))
        field("category", cur.category?.toJson() ?: JsonNull, d.category?.toJson() ?: JsonNull)
        field("tag", cur.tag?.let { JsonPrimitive(it) } ?: JsonNull,
            d.tag?.trim()?.ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
        field("memo", JsonPrimitive(cur.memo), JsonPrimitive(d.memo.trim()))
        field("amountCents", JsonPrimitive(cur.amountCents), JsonPrimitive(d.amountCents))
        field("status", JsonPrimitive(cur.status), JsonPrimitive(d.status))
        if (changes.isEmpty()) return@withLock

        // An unsent edit of the same transaction: fold these into it (keeping the value it started from).
        val unsentEdit = s.pending.lastOrNull { it.sentSeq == null && it.op.str("op") == "edit" && it.op.str("uid") == uid }
        val merged = LinkedHashMap<String, Pair<JsonElement, JsonElement>>()
        unsentEdit?.op?.get("changes")?.jsonObject?.forEach { (k, v) ->
            merged[k] = (v.jsonObject["old"] ?: JsonNull) to (v.jsonObject["new"] ?: JsonNull)
        }
        for ((k, v) in changes) merged[k] = (merged[k]?.first ?: v.first) to v.second
        val op = buildJsonObject {
            put("op", JsonPrimitive("edit"))
            put("opId", JsonPrimitive(unsentEdit?.opId ?: newId()))
            put("uid", JsonPrimitive(uid))
            put("changes", buildJsonObject {
                for ((k, v) in merged) if (v.first != v.second) put(k, buildJsonObject {
                    put("old", v.first)
                    put("new", v.second)
                })
            })
        }
        val pending = if (unsentEdit != null) s.pending.map { if (it === unsentEdit) it.copy(op = op) else it }
        else s.pending + PendingOp(op.str("opId")!!, op)
        save(s.copy(pending = pending))
    }

    /** Deletes a transaction (one never sent just disappears, with any changes to it). */
    suspend fun delete(uid: String) = lock.withLock {
        val s = _state.value
        val cur = LedgerView(s).txn(uid) ?: return@withLock
        require(cur.editable) { "That transaction can't be deleted on the phone." }
        val unsentForIt = s.pending.filter { it.sentSeq == null && it.op.str("uid") == uid }
        val wasUnsentAdd = unsentForIt.any { it.op.str("op") == "add" }
        val kept = s.pending - unsentForIt.toSet()
        val pending = if (wasUnsentAdd) kept else {
            val id = newId()
            kept + PendingOp(id, buildJsonObject {
                put("op", JsonPrimitive("delete"))
                put("opId", JsonPrimitive(id))
                put("uid", JsonPrimitive(uid))
            })
        }
        save(s.copy(pending = pending))
    }

    suspend fun dismissNotices() = lock.withLock {
        save(_state.value.copy(notices = emptyList()))
    }

    // ----- Syncing ----------------------------------------------------------------------

    /**
     * Syncs: reads this phone's snapshot, then sends the changes not yet sent
     * as one change file. A newer snapshot replaces the last one; changes it
     * answers (or that the computer has applied) are dropped from pending, and
     * any it refused, or kept its own values for, become notices. The change
     * file is numbered after the last one the computer has applied, so a phone
     * paired again with the same code (say, after reinstalling) carries on
     * from there rather than starting at 1 again.
     */
    suspend fun sync(): SyncOutcome = lock.withLock {
        var s = _state.value
        val code = s.pairing ?: return@withLock SyncOutcome(false, 0, 0, "This phone isn't paired.")
        val key = Base64.decode(code.key)
        val dev = "devices/${code.deviceId}"
        try {
            val mailbox = mailboxFor(code, s.user, s.password)

            // 1. What the computer has.
            var confirmed = 0
            val snapPath = "$dev/snapshot.mms"
            val file = mailbox.read("${code.fileId}/$snapPath")
            if (file != null) {
                val snap = SyncJson.decodeFromString(Snapshot.serializer(),
                    SyncCrypto.decrypt(key, snapPath, file).decodeToString())
                if (snap.snapshotSeq > (s.snapshot?.snapshotSeq ?: -1)) {
                    val results = snap.results.associateBy { it.opId }
                    val notices = s.notices.toMutableList()
                    val kept = s.pending.filter { p ->
                        val r = results[p.opId]
                        val done = r != null || (p.sentSeq != null && p.sentSeq <= snap.appliedThrough)
                        if (done) confirmed++
                        if (r != null && r.result != "applied") {
                            notices += Notice(p.opId, r.message ?: "The computer didn't apply a change.", r.result == "rejected")
                        }
                        !done
                    }
                    s = s.copy(snapshot = snap, pending = kept, notices = notices)
                }
            }

            // 2. What's new here.
            val unsent = s.pending.filter { it.sentSeq == null }
            if (unsent.isNotEmpty()) {
                val seq = maxOf(s.nextSeq, (s.snapshot?.appliedThrough ?: 0) + 1)
                val batch = buildJsonObject {
                    put("format", JsonPrimitive(1))
                    put("deviceId", JsonPrimitive(code.deviceId))
                    put("seq", JsonPrimitive(seq))
                    put("baseSnapshot", JsonPrimitive(s.snapshot?.snapshotSeq ?: 0))
                    put("ops", buildJsonArray { unsent.forEach { add(it.op) } })
                }
                val path = "$dev/changes/$seq.mms"
                mailbox.write("${code.fileId}/$path", SyncCrypto.encrypt(key, path, batch.toString().encodeToByteArray()))
                val sentIds = unsent.map { it.opId }.toSet()
                s = s.copy(nextSeq = seq + 1, pending = s.pending.map { if (it.opId in sentIds) it.copy(sentSeq = seq) else it })
            }

            s = s.copy(lastSync = Clock.System.now().toString(), lastError = null)
            save(s)
            SyncOutcome(true, unsent.size, confirmed,
                if (s.snapshot == null) "Waiting for the computer: on it, choose File > Sync Now." else "Synced.")
        } catch (e: MailboxAuthException) {
            fail(e.message ?: "The NAS refused the user name or password.")
        } catch (e: BadFileException) {
            fail("The computer's update couldn't be read (${e.message}). Pair this phone again.")
        } catch (e: Exception) {
            fail("Couldn't reach the mailbox: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun fail(message: String): SyncOutcome {
        save(_state.value.copy(lastError = message))
        return SyncOutcome(false, 0, 0, message)
    }
}
