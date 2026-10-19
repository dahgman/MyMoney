package com.martinsmayhem.mymoney.mobile

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneTest {

    private val key = ByteArray(32) { it.toByte() }
    private val today = LocalDate(2026, 10, 19)
    private val code = PairingCode(fileId = "file1", fileName = "Household", deviceId = "dev1",
        key = Base64.encode(key), mailbox = PairingMailbox(url = "https://nas:5006", path = "/MyMoney-Sync"))
    private val root = Files.createTempDirectory("mailbox")
    private val mailbox = FolderMailbox(root)
    private val store = MemoryStore()
    private val phone = Phone(store) { _, _, _ -> mailbox }

    private val checking = SnapAccount("acc-c", "Checking", "CHECKING", synced = true, balanceCents = 10_000,
        clearedCents = 10_000, ledgerStartCents = 11_500)   // 10,000 less Rent and Old
    private val savings = SnapAccount("acc-s", "Savings", "SAVINGS", synced = true, balanceCents = 50_000,
        clearedCents = 50_000, ledgerStartCents = 50_000)
    private val vacation = SnapAccount("acc-v", "Vacation", "ENVELOPE", parentUid = "acc-s", synced = true,
        balanceCents = 0, clearedCents = 0, ledgerStartCents = 0)
    private val card = SnapAccount("acc-k", "Visa", "CREDIT_CARD")
    private val rent = SnapTxn("t-rent", "acc-c", "2026-10-01", payee = "Rent", amountCents = -1_000)
    private val old = SnapTxn("t-old", "acc-c", "2026-09-01", payee = "Old", amountCents = -500, status = "R", editable = false)

    private fun snapshot(seq: Long, vararg txns: SnapTxn, results: List<OpResult> = emptyList(), appliedThrough: Long = 0) =
        Snapshot(snapshotSeq = seq, appliedThrough = appliedThrough, results = results,
            accounts = listOf(checking, savings, vacation, card),
            categories = listOf(SnapCategory("cat-g", "Food:Groceries")), payees = listOf("Rent", "Walmart"),
            transactions = txns.toList())

    /** What the computer does: leaves this phone a snapshot. */
    private fun computerWrites(s: Snapshot) {
        val path = "devices/dev1/snapshot.mms"
        val f = root.resolve("file1/$path")
        Files.createDirectories(f.parent)
        Files.write(f, SyncCrypto.encrypt(key, path, SyncJson.encodeToString(Snapshot.serializer(), s).encodeToByteArray()))
    }

    /** What the computer reads: the phone's change file. */
    private fun changeFile(seq: Long): JsonObject {
        val path = "devices/dev1/changes/$seq.mms"
        return Json.parseToJsonElement(SyncCrypto.decrypt(key, path, Files.readAllBytes(root.resolve("file1/$path")))
            .decodeToString()).jsonObject
    }

    /** What the computer writes every sync: its mark on the mailbox. */
    private fun computerHasSynced() {
        Files.createDirectories(root.resolve("file1"))
        Files.writeString(root.resolve("file1/mailbox.json"), "{ \"format\": 1, \"fileId\": \"file1\" }")
    }

    private suspend fun paired(vararg txns: SnapTxn) {
        computerHasSynced()
        phone.pair(code, "sync", "secret")
        computerWrites(snapshot(1, *txns))
        phone.sync()
    }

    private fun draft(amount: Long, payee: String = "Starbucks", category: CategoryRef? = null) =
        TxnDraft("acc-c", LocalDate(2026, 10, 19), payee = payee, amountCents = amount, category = category)

    // ----- The file format ---------------------------------------------------------------

    @Test
    fun readsWhatTheDesktopWrote() {
        // Made by the desktop's SyncCrypto with the key 0, 1, ..., 31.
        val file = Base64.decode("TU1TMQHJn9z31q0pe58ywBvgokhlWJNviUgbXDzTZXKP+tHijZuZZyVQhVtnNerLnGZRx3gEGGJqXoglWnirXLJiaxPRdIW4ym6Jh+I=")
        assertEquals("{\"made\":\"by the desktop\"}", SyncCrypto.decrypt(key, "devices/7f21/snapshot.mms", file).decodeToString())
        assertFailsWith<BadFileException> { SyncCrypto.decrypt(key, "devices/other/snapshot.mms", file) }
    }

    @Test
    fun readsThePairingCode() {
        val text = """{"v":1,"fileId":"f","fileName":"Household","deviceId":"d","key":"${Base64.encode(key)}",""" +
            """"mailbox":{"type":"webdav","url":"https://nas:5006","path":"/MyMoney-Sync"}}"""
        val c = phone.readCode(text)
        assertEquals("d", c.deviceId)
        assertFailsWith<IllegalArgumentException> { phone.readCode("hello") }
        assertFailsWith<IllegalArgumentException> { phone.readCode(text.replace(Base64.encode(key), "AAAA")) }
    }

    @Test
    fun webDavListingsWithAnyPrefix() {
        val synology = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
            <D:response><D:href>/MyMoney-Sync/f/devices/d/changes/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
            <D:response><D:href>/MyMoney-Sync/f/devices/d/changes/2.mms</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
            <D:response><D:href>/MyMoney-Sync/f/devices/d/changes/1.mms</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
            <D:response><D:href>/MyMoney-Sync/f/devices/d/changes/3.mms.tmp</D:href><D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat></D:response>
            </D:multistatus>"""
        assertEquals(listOf("1.mms", "2.mms"), WebDavMailbox.fileNames(synology, false))
        assertEquals(listOf("1.mms", "2.mms", "3.mms.tmp"), WebDavMailbox.fileNames(synology, true))
        val nextcloud = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav/files/u/My%20Box/a%20b.mms</d:href>""" +
            """<d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response></d:multistatus>"""
        assertEquals(listOf("a b.mms"), WebDavMailbox.fileNames(nextcloud, false))
    }

    @Test
    fun money() {
        assertEquals("$1,234.56", Format.money(123_456))
        assertEquals("-$0.05", Format.money(-5))
        assertEquals(1_234_50L, Format.parseMoney("$1,234.5"))
        assertEquals(1_200L, Format.parseMoney("12"))
        assertNull(Format.parseMoney("12.345"))
        assertNull(Format.parseMoney("abc"))
        assertEquals("10/19/2026", Format.date(LocalDate(2026, 10, 19), "MM/dd/yyyy"))
        assertEquals("19.10.2026", Format.date(LocalDate(2026, 10, 19), "dd.MM.yyyy"))
    }

    // ----- What the phone shows -----------------------------------------------------------

    @Test
    fun theSnapshotWithPendingChangesOnTop() = runTest {
        paired(rent, old)
        val view0 = LedgerView(phone.state.value)
        assertEquals(listOf("Checking", "Savings"), view0.accounts().map { it.name })
        assertEquals(listOf("Vacation"), view0.accounts()[1].envelopes.map { it.name }, "envelopes under their parent")
        val rows = view0.ledger("acc-c", today)
        assertEquals(listOf("Rent", "Old"), rows.map { it.txn.payee }, "newest first")
        assertEquals(10_000, rows.first().balanceCents, "the running balance ends at the balance")

        phone.add(draft(-575))
        phone.edit("t-rent", draft(-1_200, "Rent").copy(date = LocalDate(2026, 10, 1)))
        val view = LedgerView(phone.state.value)
        val c = view.accounts().first { it.name == "Checking" }
        assertEquals(10_000 - 575 - 200, c.balanceCents)
        assertEquals(2, c.pending)
        assertTrue(view.ledger("acc-c", today).first().txn.pending)
        assertEquals(c.balanceCents, view.ledger("acc-c", today).first().balanceCents)
    }

    @Test
    fun theLedgerIsByStatusLikeTheComputers() = runTest {
        val sched = SnapTxn("t-ins", "acc-c", "2026-10-24", payee = "Insurance", amountCents = -100, status = "S")
        val cleared = SnapTxn("t-wal", "acc-c", "2026-10-13", payee = "Walmart", amountCents = -100, status = "C")
        val none = SnapTxn("t-sh", "acc-c", "2026-10-16", payee = "Shell", amountCents = -100)
        val pendingOld = SnapTxn("t-p", "acc-c", "2026-10-02", payee = "Pending", amountCents = -100, status = "P")
        paired(rent, old, sched, cleared, none, pendingOld)
        val view = LedgerView(phone.state.value)
        // The computer's order is Old (R), Walmart (C), Pending (P), Rent and Shell (none, by date), then Insurance
        // (after today); the phone shows it last first.
        assertEquals(listOf("Insurance", "Shell", "Rent", "Pending", "Walmart", "Old"),
            view.ledger("acc-c", today).map { it.txn.payee })
        val walmart = view.ledger("acc-c", today).first { it.txn.payee == "Walmart" }
        assertEquals(11_500 - 500 - 100, walmart.balanceCents, "after the reconciled and cleared ones: the cleared balance")

        phone.setPrefs(Prefs(hideReconciled = true, hideFutureScheduled = true))
        val hidden = LedgerView(phone.state.value).ledger("acc-c", today).map { it.txn.payee }
        assertEquals(listOf("Shell", "Rent", "Pending", "Walmart"), hidden)
        assertEquals("Insurance", LedgerView(phone.state.value).upcoming().single().payee, "still in Upcoming")
    }

    @Test
    fun settingsOutliveUnpairing() = runTest {
        paired(rent)
        phone.setPrefs(Prefs(theme = "dark", hideReconciled = true))
        phone.unpair()
        assertNull(phone.state.value.pairing)
        assertEquals("dark", phone.state.value.prefs.theme)
        computerHasSynced()
        phone.pair(code, "sync", "secret")
        assertTrue(phone.state.value.prefs.hideReconciled)
    }

    @Test
    fun aTransferShowsInBothAccounts() = runTest {
        paired(rent)
        phone.add(draft(-2_000, "Save", CategoryRef.Transfer("acc-v")))
        val view = LedgerView(phone.state.value)
        assertEquals(2_000, view.accounts()[1].envelopes[0].balanceCents)
        val mirror = view.ledger("acc-v", today).single().txn
        assertTrue(mirror.mirror)
        assertFalse(mirror.editable)
        assertEquals("[Checking]", view.categoryLabel(mirror))
    }

    @Test
    fun aClearedOneChangesOnlyItsPayeeCategoryOrStatus() = runTest {
        paired(rent.copy(status = "C"))
        val t = LedgerView(phone.state.value).txn("t-rent")!!
        assertTrue(t.canChange("payee") && t.canChange("category") && t.canChange("status"))
        assertFalse(t.canChange("amountCents") || t.canChange("date") || t.canChange("memo") || t.canChange("description"))
        val base = TxnDraft("acc-c", LocalDate(2026, 10, 1), payee = "Rent", amountCents = -1_000, status = "C")
        assertFailsWith<IllegalArgumentException> { phone.edit("t-rent", base.copy(amountCents = -1_100)) }
        assertFailsWith<IllegalArgumentException> { phone.edit("t-rent", base.copy(memo = "typed")) }
        assertFailsWith<IllegalArgumentException> {
            phone.edit("t-rent", base.copy(category = CategoryRef.Transfer("acc-s")))
        }
        assertTrue(phone.state.value.pending.isEmpty(), "none of those was kept")
        phone.edit("t-rent", base.copy(payee = "Landlord", category = CategoryRef.Existing("cat-g")))
        assertEquals(setOf("payee", "category"), phone.state.value.pending.single().op["changes"]!!.jsonObject.keys)
    }

    @Test
    fun theDetailsShowEverything() = runTest {
        paired(rent.copy(description = "October rent", memo = "check 1001", bankDate = "2026-10-03", type = "CHECK"))
        val t = LedgerView(phone.state.value).txn("t-rent")!!
        assertEquals("October rent", t.description)
        assertEquals(LocalDate(2026, 10, 3), t.bankDate)
        assertEquals("CHECK", t.type)
    }

    @Test
    fun reconciledOnesCantBeChanged() = runTest {
        paired(rent, old)
        assertFailsWith<IllegalArgumentException> { phone.edit("t-old", draft(-1)) }
        assertFailsWith<IllegalArgumentException> { phone.delete("t-old") }
    }

    // ----- How changes are kept --------------------------------------------------------------

    @Test
    fun anEditSendsOnlyWhatChangedWithWhereItStarted() = runTest {
        paired(rent)
        phone.edit("t-rent", TxnDraft("acc-c", LocalDate(2026, 10, 1), payee = "Landlord", amountCents = -1_000))
        phone.edit("t-rent", TxnDraft("acc-c", LocalDate(2026, 10, 1), payee = "Landlord", amountCents = -1_100))
        val op = phone.state.value.pending.single().op
        val changes = op["changes"]!!.jsonObject
        assertEquals(setOf("payee", "amountCents"), changes.keys, "two edits before sending: one change")
        assertEquals("Rent", changes["payee"]!!.jsonObject["old"]!!.jsonPrimitive.content, "the value it started from")
        assertEquals(-1_100, changes["amountCents"]!!.jsonObject["new"]!!.jsonPrimitive.long)
    }

    @Test
    fun anUnsentAddIsChangedOrDroppedInPlace() = runTest {
        paired()
        val uid = phone.add(draft(-575))
        phone.edit(uid, draft(-600))
        assertEquals(1, phone.state.value.pending.size)
        assertEquals(-600, phone.state.value.pending.single().op["amountCents"]!!.jsonPrimitive.long)
        phone.delete(uid)
        assertTrue(phone.state.value.pending.isEmpty(), "never sent: nothing to tell the computer")
    }

    // ----- Syncing ----------------------------------------------------------------------------

    @Test
    fun syncSendsThenHearsBack() = runTest {
        paired(rent)
        val uid = phone.add(draft(-575))
        phone.delete("t-rent")
        val first = phone.sync()
        assertTrue(first.ok)
        assertEquals(2, first.sent, "two changes, in one file")
        val batch = changeFile(1)
        assertEquals("dev1", batch["deviceId"]!!.jsonPrimitive.content)
        assertEquals(listOf("add", "delete"), batch["ops"]!!.jsonArray.map { it.jsonObject["op"]!!.jsonPrimitive.content })
        assertTrue(phone.state.value.pending.all { it.sentSeq == 1L }, "sent, not yet confirmed")
        assertNotNull(LedgerView(phone.state.value).txn(uid), "still shown until the computer has it")

        phone.sync()
        assertEquals(2, phone.state.value.nextSeq, "nothing new: no second change file")

        val ops = phone.state.value.pending
        computerWrites(snapshot(2, SnapTxn(uid, "acc-c", "2026-10-19", payee = "Starbucks", amountCents = -575),
            results = listOf(OpResult(ops[0].opId, "applied"),
                OpResult(ops[1].opId, "rejected", message = "Rent (10/01/2026) was reconciled on the computer.")),
            appliedThrough = 1))
        val second = phone.sync()
        assertEquals(2, second.confirmed)
        assertTrue(phone.state.value.pending.isEmpty())
        assertEquals("Rent (10/01/2026) was reconciled on the computer.", phone.state.value.notices.single().message)
        assertEquals(listOf("Starbucks"), LedgerView(phone.state.value).ledger("acc-c", today).map { it.txn.payee })
    }

    @Test
    fun anOlderSnapshotIsIgnored() = runTest {
        paired(rent)
        computerWrites(snapshot(1))                     // the same seq again, without Rent
        phone.sync()
        assertEquals(listOf("Rent"), LedgerView(phone.state.value).ledger("acc-c", today).map { it.txn.payee })
    }

    @Test
    fun pairingWithTheWrongFolderSaysSo() = runTest {
        val e = assertFailsWith<MailboxException> { phone.pair(code, "sync", "secret") }   // no mailbox.json there
        assertTrue(e.message!!.contains("Same as above"), e.message)
        assertNull(phone.state.value.pairing, "not paired")
    }

    @Test
    fun beforeTheComputerHasWrittenAnythingItSaysSo() = runTest {
        computerHasSynced()
        phone.pair(code, "sync", "secret")
        val r = phone.sync()
        assertTrue(r.ok)
        assertTrue(r.message.contains("Sync Now"))
    }

    @Test
    fun aWrongKeyIsReported() = runTest {
        paired(rent)
        val path = "devices/dev1/snapshot.mms"
        Files.write(root.resolve("file1/$path"), SyncCrypto.encrypt(ByteArray(32), path, "{}".encodeToByteArray()))
        val r = phone.sync()
        assertFalse(r.ok)
        assertTrue(r.message.contains("Pair this phone again"))
        assertEquals(r.message, phone.state.value.lastError)
        assertEquals(listOf("Rent"), LedgerView(phone.state.value).ledger("acc-c", today).map { it.txn.payee }, "what it had stays")
    }

    @Test
    fun everythingIsSaved() = runTest {
        paired(rent)
        phone.add(draft(-575))
        val again = Phone(store) { _, _, _ -> mailbox }
        again.load()
        assertEquals(1, again.state.value.pending.size)
        assertEquals("Household", again.state.value.pairing?.fileName)
        assertContentEquals(listOf("Rent"), LedgerView(again.state.value).snapshot.transactions.map { it.payee })
    }
}
