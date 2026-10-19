package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cancel in Reconcile Account: everything since it was started goes back as it was. */
class ReconcileCancelTest {

    @TempDir
    Path dir;
    private Database db;
    private Account checking;
    private TransactionRepository transactions;
    private ReconcileRepository reconciles;
    private AttachmentRepository attachments;
    private UndoLog undo;

    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(Files.createDirectories(dir.resolve("data")).resolve("T.MyMoney"), "password1");
        AccountRepository accounts = new AccountRepository(db);
        accounts.insert("Checking", AccountType.CHECKING, 100_000, null);
        checking = accounts.findAll().get(0);
        transactions = new TransactionRepository(db);
        reconciles = new ReconcileRepository(db);
        attachments = new AttachmentRepository(db);
        undo = new UndoLog(db);
        attachments.setUndoLog(undo);
    }

    private LedgerEntry txn(String payee, int day, long cents, TxnStatus status) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 10, day);
        e.payee = payee;
        e.amountCents = cents;
        e.status = status;
        transactions.insert(checking.id(), e);
        return e;
    }

    private List<String> ledger() throws Exception {
        return transactions.findByAccount(checking.id()).stream()
                .map(e -> e.payee + " " + e.amountCents + " " + e.status).toList();
    }

    @Test
    void everythingGoesBack() throws Exception {
        txn("Rent", 1, -150_000, TxnStatus.CLEARED);
        LedgerEntry shell = txn("Shell", 5, -4_000, TxnStatus.NONE);
        txn("Paycheck", 15, 200_000, TxnStatus.PENDING);
        undo.checkpoint("Before");
        List<String> before = ledger();
        Set<Long> filesBefore = attachments.idsFor(checking.id());

        // Reconcile Account: start, change things in the window, attach the statement.
        ReconcileRepository.Reconciliation r = reconciles.start(checking, LocalDate.of(2026, 10, 31), 146_000, true);
        reconciles.deselect(List.of(shell.id));
        shell.amountCents = -4_500;
        transactions.update(shell);
        txn("Bank fee", 20, -500, TxnStatus.RECONCILING);
        attachments.addToStatement(checking, r.id(), r.statementDate(),
                Files.writeString(dir.resolve("oct.pdf"), "pdf"));     // under Bank Statement
        assertFalse(attachments.addedSince(checking.id(), filesBefore).isEmpty());

        // Cancel.
        for (AttachmentRepository.Attachment a : attachments.addedSince(checking.id(), filesBefore)) {
            attachments.remove(a, checking);
        }
        undo.revertToCheckpoint();

        assertEquals(before, ledger(), "statuses, the edit and the new transaction: all as they were");
        assertNull(reconciles.unfinished(checking.id()), "the reconciliation it started is gone");
        assertTrue(attachments.addedSince(checking.id(), filesBefore).isEmpty(), "the statement file is removed");
        assertEquals("Before", undo.undoLabel(), "the earlier step is still there to undo");
        assertNull(undo.redoLabel(), "and nothing to redo");
    }

    @Test
    void cancellingAResumedOneKeepsWhatWasThereBefore() throws Exception {
        LedgerEntry rent = txn("Rent", 1, -150_000, TxnStatus.CLEARED);
        txn("Shell", 5, -4_000, TxnStatus.NONE);
        reconciles.start(checking, LocalDate.of(2026, 10, 31), 0, false);       // Finish Later last time
        undo.checkpoint("Reconcile (unfinished)");
        List<String> before = ledger();

        reconciles.start(checking, LocalDate.of(2026, 10, 25), 99, true);       // picked up again
        reconciles.deselect(List.of(rent.id));
        undo.revertToCheckpoint();

        assertEquals(before, ledger());
        ReconcileRepository.Reconciliation r = reconciles.unfinished(checking.id());
        assertEquals(LocalDate.of(2026, 10, 31), r.statementDate(), "its statement as it was");
        assertEquals(0, r.endingBalanceCents());
    }
}
