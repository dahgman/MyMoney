package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A transaction dated ahead with any status but Scheduled is committed:
 * treated as done, in the current balances and among today's in the ledger.
 */
class CommittedTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private long checking;
    private final LocalDate today = LocalDate.now();

    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(dir.resolve("T.MyMoney"), "password1");
        accounts = new AccountRepository(db);
        transactions = new TransactionRepository(db);
        checking = accounts.insert("Checking", AccountType.CHECKING, 100_000, null);
    }

    private LedgerEntry txn(long account, String payee, long cents, TxnStatus status, LocalDate date) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = date;
        e.payee = payee;
        e.amountCents = cents;
        e.status = status;
        transactions.insert(account, e);
        return e;
    }

    @Test
    void theCurrentBalanceCountsCommittedOnes() throws Exception {
        txn(checking, "Today", -1_000, TxnStatus.NONE, today);
        txn(checking, "Rent, paid ahead", -50_000, TxnStatus.PAID, today.plusDays(5));
        txn(checking, "Power, entered", -9_000, TxnStatus.NONE, today.plusDays(9));
        txn(checking, "Insurance", -7_000, TxnStatus.SCHEDULED, today.plusDays(3));
        assertEquals(100_000 - 1_000 - 50_000 - 9_000, accounts.balanceThrough(checking, today),
                "committed ones count now; the scheduled one doesn't");
        assertEquals(100_000 - 1_000 - 50_000 - 9_000 - 7_000, accounts.balanceThrough(checking, today.plusDays(3)));
        assertEquals(100_000, accounts.balanceThrough(checking, today.minusDays(1)), "the past is as it was");
    }

    @Test
    void anEnvelopeShowsItsCommittedOnesInTheTree() throws Exception {
        long savings = accounts.insert("Savings", AccountType.SAVINGS, 0, null);
        long trip = accounts.insert("Trip", AccountType.ENVELOPE, 20_000, savings);
        txn(trip, "Hotel deposit", -5_000, TxnStatus.PENDING, today.plusDays(10));
        txn(trip, "Flights", -8_000, TxnStatus.SCHEDULED, today.plusDays(12));
        assertEquals(15_000L, accounts.treeBalances().get(trip));
    }

    @Test
    void aLoanFollowsItsDates() throws Exception {
        long loan = accounts.insert("Car loan", AccountType.LOAN, -1_000_000, null);
        txn(loan, "Payment", 30_000, TxnStatus.PAID, today.plusDays(4));
        assertEquals(-1_000_000, accounts.balanceThrough(loan, today));
    }

    @Test
    void theLedgerPutsThemAmongTodays() throws Exception {
        LedgerEntry paid = txn(checking, "Rent, paid ahead", -50_000, TxnStatus.PAID, today.plusDays(5));
        LedgerEntry scheduled = txn(checking, "Insurance", -7_000, TxnStatus.SCHEDULED, today.plusDays(3));
        txn(checking, "Today", -1_000, TxnStatus.NONE, today);
        LedgerTableModel model = new LedgerTableModel(transactions, m -> {
            throw new AssertionError(m);
        }, q -> true, () -> { });
        model.setAccount(accounts.findAll().stream().filter(a -> a.id() == checking).findFirst().orElseThrow());
        int paidRow = model.rowOfTransaction(paid.id);
        int scheduledRow = model.rowOfTransaction(scheduled.id);
        assertFalse(model.isFuture(paidRow), "drawn as done, not grey");
        assertTrue(model.isFuture(scheduledRow));
        assertTrue(paidRow < model.draftRow() && model.draftRow() < scheduledRow,
                "above the blank row, with today's; the scheduled one below it");
    }
}
