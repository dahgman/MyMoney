package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The trend graph plots the cleared balance: only what the bank has posted. */
class BalanceChartDataTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private long checking;

    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(dir.resolve("T.MyMoney"), "password1");
        accounts = new AccountRepository(db);
        transactions = new TransactionRepository(db);
        checking = accounts.insert("Checking", AccountType.CHECKING, 10_000, null);
    }

    private void txn(int day, long cents, TxnStatus status) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 10, day);
        e.payee = "P";
        e.amountCents = cents;
        e.status = status;
        transactions.insert(checking, e);
    }

    @Test
    void onlyClearedTransactionsCount() throws Exception {
        txn(1, -2_000, TxnStatus.RECONCILED);
        txn(2, -50_000, TxnStatus.SCHEDULED);     // not taken by the bank yet
        txn(2, -3_000, TxnStatus.NONE);           // a check not cashed yet
        txn(3, 40_000, TxnStatus.CLEARED);
        txn(3, -1_000, TxnStatus.RECONCILING);
        txn(4, -500, TxnStatus.PENDING);
        LocalDate from = LocalDate.of(2026, 10, 2);
        LocalDate to = LocalDate.of(2026, 10, 4);
        assertArrayEquals(new long[] {8_000, 47_000, 47_000}, accounts.dailyBalances(checking, from, to, true));
        assertArrayEquals(new long[] {-45_000, -6_000, -6_500}, accounts.dailyBalances(checking, from, to),
                "every status, as before");
    }
}
