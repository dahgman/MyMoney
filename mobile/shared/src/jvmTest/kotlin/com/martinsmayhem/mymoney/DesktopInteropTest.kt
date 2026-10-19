package com.martinsmayhem.mymoney

import com.martinsmayhem.mymoney.mobile.CategoryRef
import com.martinsmayhem.mymoney.mobile.LedgerView
import com.martinsmayhem.mymoney.mobile.MemoryStore
import com.martinsmayhem.mymoney.mobile.Phone
import com.martinsmayhem.mymoney.mobile.TxnDraft
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real desktop (its SyncEngine, on a real data file) and the real phone
 * code, syncing through one folder: what each writes, the other reads.
 * In the desktop's package, so it can use the desktop's package-private classes.
 */
class DesktopInteropTest {

    private val dir = Files.createTempDirectory("interop")
    private val box = Files.createDirectories(dir.resolve("mailbox"))
    private val db = Database.create(Files.createDirectories(dir.resolve("data")).resolve("Household.MyMoney"), "password1")
    private val accounts = AccountRepository(db)
    private val transactions = TransactionRepository(db)
    private val sync = SyncRepository(db)
    private val engine = SyncEngine(db, AttachmentRepository(db))
    private val today = java.time.LocalDate.now()

    private fun account(name: String) = accounts.findAll().first { it.name() == name }

    private fun ledger(name: String) = transactions.findByAccount(account(name).id())

    private fun txn(account: String, payee: String, cents: Long, status: TxnStatus = TxnStatus.NONE): LedgerEntry {
        val e = LedgerEntry()
        e.date = today
        e.payee = payee
        e.amountCents = cents
        e.status = status
        transactions.insert(account(account).id(), e)
        return ledger(account).first { it.id == e.id }
    }

    private suspend fun pairedPhone(): Phone {
        val c = accounts.insert("Checking", AccountType.CHECKING, 100_000, null)
        val s = accounts.insert("Savings", AccountType.SAVINGS, 0, null)
        accounts.insert("Vacation", AccountType.ENVELOPE, 0, s)
        sync.setSynced(c, true)
        sync.setSynced(s, true)
        sync.saveSettings(SyncRepository.Settings("folder", box.toString(), "", "", "", "",
            "https://nas:5006", "/MyMoney-Sync", 5))
        val device = sync.addDevice("Pixel")
        val code = SyncDialog.pairingText(sync.fileId(), "Household", device, sync.settings())
        val phone = Phone(MemoryStore()) { _, _, _ -> com.martinsmayhem.mymoney.mobile.FolderMailbox(box) }
        phone.pair(phone.readCode(code), "sync", "secret")
        return phone
    }

    @Test
    fun theyUnderstandEachOther() = runTest {
        val phone = pairedPhone()
        val rent = txn("Checking", "Rent", -150_000, TxnStatus.CLEARED)
        val shell = txn("Checking", "Shell", -4_000)
        txn("Checking", "Paycheck", 200_000, TxnStatus.RECONCILED)

        // The computer writes the snapshot; the phone reads it.
        assertTrue(engine.runNow().ok())
        assertTrue(phone.sync().ok)
        var view = LedgerView(phone.state.value)
        assertEquals(listOf("Checking", "Savings"), view.accounts().map { it.name })
        assertEquals(100_000 - 150_000 - 4_000 + 200_000L, view.accounts()[0].balanceCents)
        assertEquals(listOf("Vacation"), view.accounts()[1].envelopes.map { it.name })
        val checkingUid = view.accounts()[0].uid
        val vacationUid = view.accounts()[1].envelopes[0].uid
        assertEquals(view.accounts()[0].balanceCents, view.ledger(checkingUid).first().balanceCents)

        // The phone adds (a new category), edits, deletes, and transfers to an envelope.
        val date = LocalDate(today.year, today.monthValue, today.dayOfMonth)
        val coffee = phone.add(TxnDraft(checkingUid, date, payee = "Starbucks", amountCents = -575,
            category = CategoryRef.New("Food:Coffee")))
        phone.edit(rent.uid, TxnDraft(checkingUid, date, payee = "Landlord", amountCents = -150_000, status = "C"))
        phone.delete(shell.uid)
        val save = phone.add(TxnDraft(checkingUid, date, payee = "Saving up", amountCents = -20_000,
            category = CategoryRef.Transfer(vacationUid)))
        assertTrue(phone.sync().ok)

        // The computer applies them.
        val summary = engine.runNow()
        assertEquals(4, summary.applied())
        assertEquals("Landlord", ledger("Checking").first { it.uid == rent.uid }.payee)
        assertNull(ledger("Checking").firstOrNull { it.uid == shell.uid })
        val added = ledger("Checking").first { it.uid == coffee }
        assertEquals("Food:Coffee", added.category)
        assertEquals("[Vacation]", ledger("Checking").first { it.uid == save }.category)
        assertEquals(20_000, accounts.balanceThrough(account("Vacation").id(), today))

        // The phone hears back: nothing pending, and it shows what the computer has.
        phone.sync()
        assertTrue(phone.state.value.pending.isEmpty())
        assertTrue(phone.state.value.notices.isEmpty())
        view = LedgerView(phone.state.value)
        assertEquals(100_000 - 150_000 - 575 - 20_000 + 200_000L, view.accounts()[0].balanceCents)
        assertEquals(20_000, view.accounts()[1].envelopes[0].balanceCents)
        assertEquals("Food:Coffee", view.categoryLabel(view.txn(coffee)!!))
    }

    /** The app reinstalled and paired again with the same code: its changes must still arrive. */
    @Test
    fun pairingAgainWithTheSameCodeLosesNothing() = runTest {
        val first = pairedPhone()
        engine.runNow()
        first.sync()
        val checkingUid = LedgerView(first.state.value).accounts()[0].uid
        val date = LocalDate(today.year, today.monthValue, today.dayOfMonth)
        first.add(TxnDraft(checkingUid, date, payee = "Before", amountCents = -100))
        first.sync()
        engine.runNow()                                   // the computer has applied change file 1

        val again = Phone(MemoryStore()) { _, _, _ -> com.martinsmayhem.mymoney.mobile.FolderMailbox(box) }
        again.pair(first.state.value.pairing!!, "sync", "secret")
        again.sync()                                      // learns where the computer is up to
        again.add(TxnDraft(checkingUid, date, payee = "After", amountCents = -200))
        again.sync()
        assertEquals(1, engine.runNow().applied())
        assertNotNull(ledger("Checking").firstOrNull { it.payee == "After" }, "not taken for an old file and deleted")
    }

    @Test
    fun aConflictAndARefusalReachThePhone() = runTest {
        val phone = pairedPhone()
        val shell = txn("Checking", "Shell", -4_000)
        val rent = txn("Checking", "Rent", -150_000, TxnStatus.CLEARED)
        engine.runNow()
        phone.sync()
        val checkingUid = LedgerView(phone.state.value).accounts()[0].uid
        val date = LocalDate(today.year, today.monthValue, today.dayOfMonth)

        phone.edit(shell.uid, TxnDraft(checkingUid, date, payee = "Shell Oil", amountCents = -4_250, memo = "from the phone"))
        phone.edit(rent.uid, TxnDraft(checkingUid, date, payee = "Landlord", amountCents = -150_000, status = "C"))
        phone.sync()

        // Meanwhile on the computer: Shell's memo typed, Rent reconciled.
        shell.memo = "from the computer"
        transactions.update(shell)
        val r = ledger("Checking").first { it.uid == rent.uid }
        r.status = TxnStatus.RECONCILED
        transactions.update(r)

        val summary = engine.runNow()
        assertEquals(1, summary.conflicts())
        assertEquals(1, summary.rejected())
        val after = ledger("Checking").first { it.uid == shell.uid }
        assertEquals("from the computer", after.memo)
        assertEquals("Shell Oil", after.payee)
        assertEquals(-4_250, after.amountCents)

        phone.sync()
        val notices = phone.state.value.notices.map { it.message }
        assertEquals(2, notices.size)
        assertTrue(notices.any { it.contains("memo") }, notices.toString())
        assertTrue(notices.any { it.contains("reconciled") }, notices.toString())
        assertNotNull(LedgerView(phone.state.value).txn(rent.uid))
        assertEquals("Rent", LedgerView(phone.state.value).txn(rent.uid)!!.payee, "the computer's stays")
    }
}
