package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Bank Date: when the bank posted a transaction, kept apart from the date it was entered on. */
class BankDateTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private DownloadImporter importer;
    private Account checking;

    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(dir.resolve("T.MyMoney"), "password1");
        accounts = new AccountRepository(db);
        transactions = new TransactionRepository(db);
        importer = new DownloadImporter(db);
        long id = accounts.insert("Checking", AccountType.CHECKING, 0, null);
        checking = accounts.findAll().stream().filter(a -> a.id() == id).findFirst().orElseThrow();
    }

    private static LocalDate oct(int day) {
        return LocalDate.of(2026, 10, day);
    }

    private LedgerEntry entered(int day, String payee, long cents, TxnStatus status) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = oct(day);
        e.payee = payee;
        e.amountCents = cents;
        e.status = status;
        transactions.insert(checking.id(), e);
        return e;
    }

    private LedgerEntry only() throws Exception {
        List<LedgerEntry> all = transactions.findByAccount(checking.id());
        assertEquals(1, all.size());
        return all.get(0);
    }

    private static DownloadImporter.Download download(int day, long cents, String payee, String id) {
        return new DownloadImporter.Download(oct(day), cents, payee, null, null, id, "POS");
    }

    @Test
    void aMatchKeepsItsDateAndGetsTheBanks() throws Exception {
        entered(1, "Electric Co", -31_200, TxnStatus.NONE);
        DownloadImporter.Download d = download(4, -31_200, "ELECTRIC CO", "F1");
        importer.accept(d, importer.candidates(checking.id(), d, 5).get(0));
        LedgerEntry e = only();
        assertEquals(oct(1), e.date, "the date it was entered on stays");
        assertEquals(oct(4), e.bankDate);
    }

    @Test
    void oneAddedNewIsOnTheBanksDate() throws Exception {
        importer.addNew(checking, download(6, 2_000, "COFFEE", "F2"), "Coffee");
        LedgerEntry e = only();
        assertEquals(oct(6), e.date);
        assertEquals(oct(6), e.bankDate);
    }

    @Test
    void importingAFileAgainFillsInMissingBankDatesOnly() throws Exception {
        entered(1, "Electric Co", -31_200, TxnStatus.CLEARED);
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "UPDATE txn SET download_status = 'M', download_id = 'F1', bank_date = NULL")) {
            ps.executeUpdate();                       // matched before there was a Bank Date
        }
        List<DownloadImporter.Download> fresh = importer.notYetImported(checking.id(),
                List.of(download(4, -31_200, "ELECTRIC CO", "F1")));
        assertTrue(fresh.isEmpty(), "not offered again");
        assertEquals(oct(4), only().bankDate);
        importer.notYetImported(checking.id(), List.of(download(9, -31_200, "ELECTRIC CO", "F1")));
        assertEquals(oct(4), only().bankDate, "one already known is kept");
    }

    @Test
    void theGraphUsesTheBankDate() throws Exception {
        LedgerEntry bill = entered(1, "Rent", -50_000, TxnStatus.CLEARED);    // entered the day it was sent
        bill.bankDate = oct(3);                                               // taken two days later
        transactions.update(bill);
        entered(2, "Paycheck", 100_000, TxnStatus.CLEARED);
        assertArrayEquals(new long[] {0, 100_000, 50_000},
                accounts.dailyBalances(checking.id(), oct(1), oct(3), true), "never below zero at the bank");
        assertArrayEquals(new long[] {-50_000, 50_000, 50_000},
                accounts.dailyBalances(checking.id(), oct(1), oct(3)), "by the dates entered");
    }

    @Test
    void anOlderFileFillsInWhatItCan() throws Exception {
        entered(1, "Hand entered", -100, TxnStatus.CLEARED);
        importer.addNew(checking, download(5, -200, "NEW ONE", "F9"), "New one");
        entered(2, "Matched QIF", -300, TxnStatus.CLEARED);
        entered(3, "Matched QFX", -400, TxnStatus.CLEARED);
        try (java.sql.Statement st = db.getConnection().createStatement()) {
            st.execute("UPDATE txn SET download_status = 'M', download_id = 'QIF:2026-10-04|-300|matched qif|#1'"
                    + " WHERE id = (SELECT txn_id FROM split WHERE amount_cents = -300)");
            st.execute("UPDATE txn SET download_status = 'M', download_id = '20261006X'"
                    + " WHERE id = (SELECT txn_id FROM split WHERE amount_cents = -400)");
            st.execute("ALTER TABLE txn DROP COLUMN bank_date");      // as it was in version 19
            st.execute("UPDATE meta SET value = '19' WHERE key = 'schema_version'");
        }
        db.close();
        db = Database.open(dir.resolve("T.MyMoney"), "password1");
        transactions = new TransactionRepository(db);
        java.util.Map<String, LocalDate> bank = new java.util.HashMap<>();
        for (LedgerEntry e : transactions.findByAccount(checking.id())) {
            bank.put(e.payee, e.bankDate);
        }
        assertNull(bank.get("Hand entered"));
        assertEquals(oct(5), bank.get("New one"), "added new: on the bank's date");
        assertEquals(oct(4), bank.get("Matched QIF"), "from its QIF id");
        assertNull(bank.get("Matched QFX"), "filled in when its file is imported again");
    }

    @Test
    void theLedgerShowsEditsAndClearsIt() throws Exception {
        entered(1, "Rent", -50_000, TxnStatus.RECONCILED);
        LedgerTableModel model = new LedgerTableModel(transactions, m -> {
            throw new AssertionError(m);
        }, q -> true, () -> { });
        model.setAccount(checking);
        assertTrue(model.isCellEditable(0, LedgerTableModel.BANK_DATE), "even when reconciled");
        model.setValueAt("10/03/2026", 0, LedgerTableModel.BANK_DATE);
        model.commitEdit();
        assertEquals(oct(3), only().bankDate);
        assertEquals("10/03/2026", model.getValueAt(0, LedgerTableModel.BANK_DATE));
        assertEquals("Posted 2 days after the date entered", model.bankDelay(0));
        model.setValueAt("", 0, LedgerTableModel.BANK_DATE);
        model.commitEdit();
        assertNull(only().bankDate);
        assertNull(model.bankDelay(0));
        assertEquals(TxnStatus.RECONCILED, only().status);
    }
}
