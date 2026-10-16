package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Edit > Move to Account. */
class MoveToAccountTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private long checking;
    private long savings;
    private long moneyMarket;

    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(Files.createDirectories(dir.resolve("data")).resolve("T.MyMoney"), "password1");
        accounts = new AccountRepository(db);
        transactions = new TransactionRepository(db);
        checking = accounts.insert("Checking", AccountType.CHECKING, 100_000, null);
        savings = accounts.insert("Savings", AccountType.SAVINGS, 0, null);
        moneyMarket = accounts.insert("Money Market", AccountType.SAVINGS, 0, null);
    }

    private LedgerEntry txn(long account, String payee, long cents, String category, TxnStatus status) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 10, 9);
        e.payee = payee;
        e.amountCents = cents;
        e.category = category;
        e.status = status;
        transactions.insert(account, e);
        return e;
    }

    private long balance(long account) throws Exception {
        return accounts.balanceThrough(account, LocalDate.of(2026, 12, 31));
    }

    private List<LedgerEntry> in(long account) throws Exception {
        return transactions.findByAccount(account);
    }

    @Test
    void aPlainTransactionMoves() throws Exception {
        LedgerEntry e = txn(checking, "Walmart", -4_599, "Food:Groceries", TxnStatus.CLEARED);
        TransactionRepository.Moved r = transactions.moveToAccount(List.of(e.id), savings);
        assertEquals(1, r.moved());
        assertTrue(r.skipped().isEmpty());
        assertTrue(in(checking).isEmpty());
        LedgerEntry moved = in(savings).get(0);
        assertEquals("Walmart", moved.payee);
        assertEquals("Food:Groceries", moved.category);
        assertEquals(TxnStatus.CLEARED, moved.status, "everything else is kept");
        assertEquals(100_000, balance(checking));
        assertEquals(-4_599, balance(savings));
    }

    @Test
    void aTransferStaysLinkedWithItsOtherSide() throws Exception {
        LedgerEntry e = txn(checking, "Move money", -20_000, "[Savings]", TxnStatus.NONE);
        transactions.moveToAccount(List.of(e.id), moneyMarket);   // it came from Money Market after all
        LedgerEntry moved = in(moneyMarket).get(0);
        assertEquals("[Savings]", moved.category, "still a transfer with Savings");
        LedgerEntry other = in(savings).get(0);
        assertEquals("[Money Market]", other.category, "Savings' side now transfers with Money Market");
        assertEquals(100_000, balance(checking), "Checking is out of it");
        assertEquals(-20_000, balance(moneyMarket));
        assertEquals(20_000, balance(savings));
        // Still linked: changing one side changes the other.
        moved.amountCents = -25_000;
        transactions.update(moved);
        assertEquals(25_000, in(savings).get(0).amountCents);
    }

    @Test
    void whatCantBeMovedIsSaidAndTheRestMove() throws Exception {
        LedgerEntry ok = txn(checking, "Walmart", -4_599, null, TxnStatus.CLEARED);
        LedgerEntry reconciled = txn(checking, "Rent", -150_000, null, TxnStatus.RECONCILED);
        LedgerEntry toItself = txn(checking, "Move money", -20_000, "[Savings]", TxnStatus.NONE);
        TransactionRepository.Moved r = transactions.moveToAccount(List.of(ok.id, reconciled.id, toItself.id), savings);
        assertEquals(1, r.moved());
        assertEquals(2, r.skipped().size());
        assertTrue(r.skipped().get(0).contains("Rent") && r.skipped().get(0).contains("reconciled"), r.skipped().get(0));
        assertTrue(r.skipped().get(1).contains("transfer with that account"), r.skipped().get(1));
        assertEquals(2, in(checking).size(), "the reconciled one and the transfer stay");
    }

    @Test
    void aLoanPaymentMovedOutLosesItsInterest() throws Exception {
        long loan = accounts.insert("Car", AccountType.LOAN, -1_000_000, null);
        accounts.saveDetails(new AccountRepository.Details(loan, "Car", AccountType.LOAN, null, "CU",
                LocalDate.of(2026, 1, 6), -1_000_000, 0, null, null, null, null, null,
                new AccountRepository.LoanTerms(8_000, 1_000_000, 36, null, 0, null, Loan.InterestMethod.MONTHLY),
                AccountRepository.CardTerms.NONE));
        transactions.recalculateAllLoans();
        long owedBefore = balance(loan);                          // principal and scheduled interest
        LedgerEntry pay = txn(loan, "CU", 31_336, null, TxnStatus.CLEARED);      // entered in the loan by mistake
        assertTrue(in(loan).stream().anyMatch(x -> x.id == pay.id && x.interestCents > 0));

        transactions.moveToAccount(List.of(pay.id), checking);
        LedgerEntry moved = in(checking).stream().filter(x -> x.id == pay.id).findFirst().orElseThrow();
        assertEquals(0, moved.interestCents, "an ordinary deposit now");
        assertEquals(31_336, moved.netCents());
        assertEquals(owedBefore, balance(loan), "the loan is back to just as it was before that payment");
        assertEquals(1_000_000, accounts.principalOwed(loan, LocalDate.of(2026, 12, 31)), "all its principal owed");
        LedgerEntry charge = in(loan).stream().filter(x -> x.scheduledInterest).findFirst().orElseThrow();
        assertEquals(1, transactions.moveToAccount(List.of(charge.id), checking).skipped().size(),
                "a loan's scheduled interest stays");
    }

    @Test
    void attachmentsFollowIt() throws Exception {
        AttachmentRepository attachments = new AttachmentRepository(db);
        LedgerEntry e = txn(checking, "Walmart", -4_599, null, TxnStatus.NONE);
        Account c = accounts.findAll().stream().filter(a -> a.id() == checking).findFirst().orElseThrow();
        attachments.addToTransaction(c, e.id, e.date, Files.writeString(dir.resolve("r.pdf"), "pdf"));
        transactions.moveToAccount(List.of(e.id), savings);
        attachments.fileTransactionAttachments();                // as the main window does after each change
        AttachmentRepository.Attachment a = attachments.forTransaction(e.id).get(0);
        assertEquals("attachments/transactions/Savings/2026/10/09/" + e.id + "-Walmart.pdf", a.path());
        assertTrue(Files.exists(dir.resolve("data").resolve(a.path())));
        assertFalse(Files.exists(dir.resolve("data/attachments/transactions/Checking")));
    }

    @Test
    void undoPutsItBack() throws Exception {
        LedgerEntry e = txn(checking, "Move money", -20_000, "[Savings]", TxnStatus.NONE);
        UndoLog undo = new UndoLog(db);
        transactions.moveToAccount(List.of(e.id), moneyMarket);
        undo.checkpoint("Move to Money Market");
        undo.undo();
        assertEquals(1, in(checking).size());
        assertTrue(in(moneyMarket).isEmpty());
        assertEquals("[Checking]", in(savings).get(0).category, "linked with Checking again");
        assertEquals(80_000, balance(checking));
    }
}
