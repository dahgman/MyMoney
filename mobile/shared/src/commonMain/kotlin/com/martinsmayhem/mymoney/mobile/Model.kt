package com.martinsmayhem.mymoney.mobile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** JSON as the desktop writes it: unknown fields ignored, so either side can add some later. */
val SyncJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

// ----- The pairing code (docs/SYNC-DESIGN.md, "Pairing a phone") -----------------

@Serializable
data class PairingCode(
    val v: Int = 1,
    val fileId: String,
    val fileName: String = "",
    val deviceId: String,
    val key: String,                    // base64 of the 32-byte AES key
    val mailbox: PairingMailbox,
)

@Serializable
data class PairingMailbox(val type: String = "webdav", val url: String, val path: String = "")

// ----- The snapshot (docs/SYNC-DESIGN.md, "What the phone gets") -------------------

@Serializable
data class Snapshot(
    val format: Int = 1,
    val snapshotSeq: Long = 0,
    val made: String = "",
    val fileName: String = "",
    val appliedThrough: Long = 0,
    val results: List<OpResult> = emptyList(),
    val settings: SnapshotSettings = SnapshotSettings(),
    val accounts: List<SnapAccount> = emptyList(),
    val categories: List<SnapCategory> = emptyList(),
    val payees: List<String> = emptyList(),
    val tags: List<SnapTag> = emptyList(),
    val types: List<String> = emptyList(),
    val transactions: List<SnapTxn> = emptyList(),
)

@Serializable
data class OpResult(
    val opId: String,
    val result: String,                 // applied, applied-with-conflicts, rejected
    val conflicts: List<String> = emptyList(),
    val message: String? = null,
)

@Serializable
data class SnapshotSettings(
    val showScheduled: Boolean = false,
    val dateFormat: String = "MM/dd/yyyy",
    val currency: String = "USD",
)

@Serializable
data class SnapAccount(
    val uid: String,
    val name: String,
    val type: String,
    val parentUid: String? = null,
    val closed: Boolean = false,
    val synced: Boolean = false,
    val balanceCents: Long? = null,
    val clearedCents: Long? = null,
    val todayBalanceCents: Long? = null,
    val ledgerStartCents: Long? = null,
) {
    val isEnvelope get() = type == "ENVELOPE"
}

@Serializable
data class SnapCategory(val uid: String, val path: String, val income: Boolean = false)

@Serializable
data class SnapTag(val name: String, val color: String = "#808080")

@Serializable
data class SnapTxn(
    val uid: String,
    val accountUid: String,
    val date: String,
    val bankDate: String? = null,
    val num: String = "",
    val payee: String = "",
    val categoryUid: String? = null,
    val transferAccountUid: String? = null,
    val split: Boolean = false,
    val tag: String? = null,
    val memo: String = "",
    val description: String = "",
    val type: String? = null,
    val amountCents: Long,
    val netCents: Long = amountCents,
    val status: String = "U",
    val editable: Boolean = true,
    val receipts: Int = 0,
)

// ----- What the phone keeps ----------------------------------------------------------

/** A change made on the phone, not yet confirmed by the computer. sentSeq: the change file it went in. */
@Serializable
data class PendingOp(val opId: String, val op: JsonObject, val sentSeq: Long? = null)

/**
 * The phone's own settings (not synced): theme is "system", "light" or
 * "dark"; the ledger can leave out reconciled transactions, and scheduled
 * ones dated after today.
 */
@Serializable
data class Prefs(
    val theme: String = "system",
    val hideReconciled: Boolean = false,
    val hideFutureScheduled: Boolean = false,
)

/** Something the phone should tell you: a change the computer refused, or kept its own value for. */
@Serializable
data class Notice(val opId: String, val message: String, val refused: Boolean)

/**
 * Everything the phone keeps (in one encrypted file): how it's paired, the
 * last snapshot, and the changes it hasn't heard back about.
 */
@Serializable
data class PhoneState(
    val pairing: PairingCode? = null,
    val user: String = "",
    val password: String = "",
    val snapshot: Snapshot? = null,
    val pending: List<PendingOp> = emptyList(),
    val nextSeq: Long = 1,
    val notices: List<Notice> = emptyList(),
    val lastSync: String? = null,        // ISO instant of the last sync that worked
    val lastError: String? = null,       // why the last sync failed, if it did
    val prefs: Prefs = Prefs(),
    /** Receipts this phone added, by transaction uid: their ids, for thumbnails (kept after they're sent). */
    val receipts: Map<String, List<String>> = emptyMap(),
)

/** A receipt photo ready to attach: the JPEG to send (about 1600 pixels), and a small one to show. */
class ReceiptImage(val jpeg: ByteArray, val thumbnail: ByteArray)

/**
 * Where PhoneState is kept: on Android an encrypted file, in tests memory.
 * Photos are kept beside it, one file each (blobs), so saving the state
 * doesn't rewrite them every time.
 */
interface PhoneStore {
    suspend fun load(): PhoneState?
    suspend fun save(state: PhoneState)
    suspend fun saveBlob(name: String, data: ByteArray)
    suspend fun loadBlob(name: String): ByteArray?
    suspend fun deleteBlob(name: String)
}

class MemoryStore(private var state: PhoneState? = null) : PhoneStore {
    val blobs = mutableMapOf<String, ByteArray>()
    override suspend fun load() = state
    override suspend fun save(state: PhoneState) {
        this.state = state
    }
    override suspend fun saveBlob(name: String, data: ByteArray) {
        blobs[name] = data
    }
    override suspend fun loadBlob(name: String) = blobs[name]
    override suspend fun deleteBlob(name: String) {
        blobs.remove(name)
    }
}
