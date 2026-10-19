package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starting a reconciliation selects only the statement's period: from the
 * day after the account was last reconciled through this statement's date,
 * by the date the bank posted each transaction. Anything after keeps its status.
 */
class ReconcileStartTest {

    @TempDir
    Path dir;
    private Database db;
    private Account checking;
    private TransactionRepository transactions;
    private ReconcileRepository reconciles;
    private final Map<String, Long> ids = new LinkedHashMap<>();

    // Windows won't let @TempDir delete a database file that is still open.
    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(dir.resolve("T.MyMoney"), "password1");
        AccountRepository accounts = new AccountRepository(db);
        accounts.insert("Checking", AccountType.CHECKING, 100_000, null);
        checking = accounts.findAll().get(0);
        transactions = new TransactionRepository(db);
        reconciles = new ReconcileRepository(db);
    }

    private void txn(String name, String date, TxnStatus status) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.parse(date);
        e.payee = name;
        e.amountCents = -1_000;
        e.status = status;
        transactions.insert(checking.id(), e);
        ids.put(name, e.id);
    }

    private TxnStatus status(String name) throws Exception {
        long id = ids.get(name);
        return transactions.findByAccount(checking.id()).stream().filter(e -> e.id == id).findFirst()
                .orElseThrow().status;
    }

    /** A finished reconciliation for September (statement date 09/30). */
    private void reconciledThroughSeptember() throws Exception {
        txn("aug cleared", "2026-08-20", TxnStatus.CLEARED);
        ReconcileRepository.Reconciliation sep = reconciles.start(checking, LocalDate.of(2026, 9, 30), 99_000, false);
        reconciles.finish(sep, 100_000);
    }

    @Test
    void clearedInThePeriodAreSelectedLaterOnesAreLeftAlone() throws Exception {
        reconciledThroughSeptember();
        txn("old uncleared", "2026-09-15", TxnStatus.NONE);       // before the last statement date
        txn("on last date", "2026-09-30", TxnStatus.CLEARED);
        txn("oct cleared", "2026-10-12", TxnStatus.CLEARED);
        txn("oct uncleared", "2026-10-20", TxnStatus.NONE);
        txn("nov cleared", "2026-11-03", TxnStatus.CLEARED);      // after this statement
        txn("nov pending", "2026-11-04", TxnStatus.PENDING);

        reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, false);
        assertEquals(TxnStatus.NONE, status("old uncleared"), "before the period: untouched");
        assertEquals(TxnStatus.CLEARED, status("on last date"), "the period starts the day after the last one");
        assertEquals(TxnStatus.RECONCILING, status("oct cleared"));
        assertEquals(TxnStatus.NONE, status("oct uncleared"), "without As of: only Cleared ones");
        assertEquals(TxnStatus.CLEARED, status("nov cleared"), "after the statement date: as it was");
        assertEquals(TxnStatus.PENDING, status("nov pending"));
    }

    @Test
    void asOfSelectsEverythingInThePeriodOnly() throws Exception {
        reconciledThroughSeptember();
        txn("old uncleared", "2026-09-15", TxnStatus.NONE);
        txn("oct uncleared", "2026-10-20", TxnStatus.NONE);
        txn("oct pending", "2026-10-21", TxnStatus.PENDING);
        txn("statement day", "2026-10-31", TxnStatus.NONE);
        txn("nov uncleared", "2026-11-01", TxnStatus.NONE);
        txn("nov cleared", "2026-11-03", TxnStatus.CLEARED);

        reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, true);
        assertEquals(TxnStatus.NONE, status("old uncleared"), "before the last reconciliation: untouched");
        assertEquals(TxnStatus.RECONCILING, status("oct uncleared"));
        assertEquals(TxnStatus.RECONCILING, status("oct pending"));
        assertEquals(TxnStatus.RECONCILING, status("statement day"), "the statement date is included");
        assertEquals(TxnStatus.NONE, status("nov uncleared"), "after the as of date: as it was");
        assertEquals(TxnStatus.CLEARED, status("nov cleared"));
    }

    @Test
    void theFirstReconciliationStartsFromTheBeginning() throws Exception {
        txn("long ago", "2024-01-05", TxnStatus.CLEARED);
        txn("oct", "2026-10-05", TxnStatus.CLEARED);
        txn("nov", "2026-11-05", TxnStatus.CLEARED);
        reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, false);
        assertEquals(TxnStatus.RECONCILING, status("long ago"));
        assertEquals(TxnStatus.RECONCILING, status("oct"));
        assertEquals(TxnStatus.CLEARED, status("nov"));
    }

    /** Reconciled in Quicken (or by hand): no reconciliation in MyMoney, but the period starts after them. */
    @Test
    void transactionsReconciledElsewhereStartThePeriod() throws Exception {
        txn("old stray", "2025-06-01", TxnStatus.NONE);
        txn("reconciled", "2026-09-28", TxnStatus.RECONCILED);
        txn("same day", "2026-09-28", TxnStatus.NONE);
        txn("next day", "2026-09-29", TxnStatus.NONE);
        txn("oct", "2026-10-15", TxnStatus.PENDING);
        assertEquals(LocalDate.of(2026, 9, 29), reconciles.periodStart(checking.id()));
        reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, true);
        assertEquals(TxnStatus.NONE, status("old stray"), "before the period: untouched");
        assertEquals(TxnStatus.NONE, status("same day"));
        assertEquals(TxnStatus.RECONCILING, status("next day"), "the first day of the period");
        assertEquals(TxnStatus.RECONCILING, status("oct"));
    }

    @Test
    void theLaterOfTheLastStatementAndTheLastReconciledCounts() throws Exception {
        reconciledThroughSeptember();
        assertEquals(LocalDate.of(2026, 10, 1), reconciles.periodStart(checking.id()));
        txn("reconciled by hand", "2026-10-05", TxnStatus.RECONCILED);
        assertEquals(LocalDate.of(2026, 10, 6), reconciles.periodStart(checking.id()));
        Database empty = Database.create(dir.resolve("E.MyMoney"), "password1");
        try {
            assertEquals(null, new ReconcileRepository(empty).periodStart(checking.id()),
                    "never reconciled: from the beginning");
        } finally {
            empty.close();
        }
    }

    @Test
    void theBankDateDecidesTheStatement() throws Exception {
        reconciledThroughSeptember();
        txn("sent in sept", "2026-09-29", TxnStatus.CLEARED);    // posted in October: on October's statement
        txn("sent in oct", "2026-10-30", TxnStatus.CLEARED);     // posted in November: next statement
        for (LedgerEntry e : transactions.findByAccount(checking.id())) {
            if (e.payee.startsWith("sent")) {
                e.bankDate = e.date.plusDays(3);
                transactions.update(e);
            }
        }
        reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, true);
        assertEquals(TxnStatus.RECONCILING, status("sent in sept"));
        assertEquals(TxnStatus.CLEARED, status("sent in oct"));
    }

    /** Picking up one left unfinished (Finish Later, or the window closed) selects its period too. */
    @Test
    void resumingAnUnfinishedOneSelectsThePeriod() throws Exception {
        reconciledThroughSeptember();
        txn("oct uncleared", "2026-10-20", TxnStatus.NONE);
        txn("oct pending", "2026-10-22", TxnStatus.PENDING);
        txn("nov", "2026-11-02", TxnStatus.NONE);
        reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, false);    // started, nothing Cleared yet
        assertEquals(TxnStatus.NONE, status("oct uncleared"));

        reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, true);     // Reconcile Account again
        assertEquals(TxnStatus.RECONCILING, status("oct uncleared"));
        assertEquals(TxnStatus.RECONCILING, status("oct pending"));
        assertEquals(TxnStatus.NONE, status("nov"));
        assertEquals(LocalDate.of(2026, 10, 1), reconciles.statementStart(checking.id()),
                "still the day after September's");
    }

    @Test
    void neverReconciledStartsAtTheFirstTransaction() throws Exception {
        assertEquals(null, reconciles.statementStart(checking.id()), "no transactions at all");
        txn("first", "2025-03-04", TxnStatus.NONE);
        txn("later", "2026-01-01", TxnStatus.NONE);
        assertEquals(LocalDate.of(2025, 3, 4), reconciles.statementStart(checking.id()));
    }

    @Test
    void aStartDateChosenInTheWindowIsUsed() throws Exception {
        reconciledThroughSeptember();
        txn("missed in sept", "2026-09-25", TxnStatus.NONE);     // left off September's by mistake
        txn("oct 3", "2026-10-03", TxnStatus.NONE);
        txn("oct 12", "2026-10-12", TxnStatus.NONE);
        reconciles.start(checking, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 31), 0, true);
        assertEquals(TxnStatus.RECONCILING, status("missed in sept"), "an earlier start takes it in");
        assertEquals(TxnStatus.RECONCILING, status("oct 3"));

        reconciles.deselectAll(checking.id());
        reconciles.start(checking, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 31), 0, true);
        assertEquals(TxnStatus.NONE, status("missed in sept"));
        assertEquals(TxnStatus.NONE, status("oct 3"), "a later start leaves it out");
        assertEquals(TxnStatus.RECONCILING, status("oct 12"));

        reconciles.start(checking, null, LocalDate.of(2026, 10, 31), 0, true);
        assertEquals(TxnStatus.RECONCILING, status("missed in sept"), "blank: from the first transaction");
    }

    @Test
    void movingTheStatementDateEarlierPutsLaterOnesBack() throws Exception {
        reconciledThroughSeptember();
        txn("oct 10", "2026-10-10", TxnStatus.CLEARED);
        txn("oct 25", "2026-10-25", TxnStatus.CLEARED);
        txn("oct 28", "2026-10-28", TxnStatus.PENDING);
        reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, true);
        assertEquals(TxnStatus.RECONCILING, status("oct 28"));

        reconciles.start(checking, LocalDate.of(2026, 10, 20), 0, false);   // Change Statement...
        assertEquals(TxnStatus.RECONCILING, status("oct 10"));
        assertEquals(TxnStatus.CLEARED, status("oct 25"), "now after the statement: its own status back");
        assertEquals(TxnStatus.PENDING, status("oct 28"));
    }

    @Test
    void finishingStillReconcilesJustTheSelected() throws Exception {
        reconciledThroughSeptember();
        txn("oct", "2026-10-05", TxnStatus.CLEARED);
        txn("nov", "2026-11-05", TxnStatus.CLEARED);
        ReconcileRepository.Reconciliation oct = reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, false);
        reconciles.finish(oct, 99_000);
        assertEquals(TxnStatus.RECONCILED, status("oct"));
        assertEquals(TxnStatus.CLEARED, status("nov"), "next month's, for next time");
    }
}
