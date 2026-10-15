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
 * last reconciliation's statement date through this one's. Anything after
 * keeps its status.
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
        assertEquals(TxnStatus.RECONCILING, status("on last date"), "the last statement date is included");
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
