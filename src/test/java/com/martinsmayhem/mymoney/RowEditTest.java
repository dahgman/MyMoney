package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Editing a transaction in the ledger: the fields typed into (Tab from one
 * to the next) are kept until Enter saves them all at once, as one undo
 * step; Escape puts it back; moving to another transaction saves it.
 */
class RowEditTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private LedgerTableModel model;
    private final List<String> saves = new ArrayList<>();
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
        checking = accounts.insert("Checking", AccountType.CHECKING, 100_000, null);
        model = new LedgerTableModel(transactions, m -> {
            throw new AssertionError(m);
        }, q -> true, () -> saves.add(model.lastAction()));
    }

    private LedgerEntry txn(String payee, long cents, int day) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 10, day);
        e.payee = payee;
        e.amountCents = cents;
        e.status = TxnStatus.NONE;
        transactions.insert(checking, e);
        return e;
    }

    private void show() throws Exception {
        model.setAccount(accounts.findAll().stream().filter(a -> a.id() == checking).findFirst().orElseThrow());
    }

    private LedgerEntry saved(long id) throws Exception {
        return transactions.findByAccount(checking).stream().filter(x -> x.id == id).findFirst().orElseThrow();
    }

    @Test
    void enterSavesEveryFieldAtOnce() throws Exception {
        LedgerEntry e = txn("Shell", -4_000, 2);
        show();
        int row = model.rowOfTransaction(e.id);
        model.setValueAt("Shell Oil", row, LedgerTableModel.PAYEE);
        model.setValueAt("Auto:Fuel", row, LedgerTableModel.CATEGORY);
        model.setValueAt("45.10", row, LedgerTableModel.PAYMENT);
        assertTrue(model.hasPendingEdit());
        assertEquals("Shell Oil", model.getValueAt(row, LedgerTableModel.PAYEE), "shown as typed");
        assertEquals("Shell", saved(e.id).payee, "not saved yet");
        assertEquals(-4_000, saved(e.id).amountCents);
        assertTrue(saves.isEmpty());

        assertTrue(model.commitEdit());                 // Enter
        assertEquals("Shell Oil", saved(e.id).payee);
        assertEquals("Auto:Fuel", saved(e.id).category);
        assertEquals(-4_510, saved(e.id).amountCents);
        assertEquals(List.of("Edit Transaction"), saves, "one save, one undo step");
        assertFalse(model.hasPendingEdit());
    }

    @Test
    void oneFieldIsNamedForUndo() throws Exception {
        LedgerEntry e = txn("Shell", -4_000, 2);
        show();
        model.setValueAt("Shell Oil", model.rowOfTransaction(e.id), LedgerTableModel.PAYEE);
        model.commitEdit();
        assertEquals(List.of("Edit Payee"), saves);
    }

    @Test
    void escapePutsItBack() throws Exception {
        LedgerEntry e = txn("Shell", -4_000, 2);
        show();
        int row = model.rowOfTransaction(e.id);
        model.setValueAt("Typo", row, LedgerTableModel.PAYEE);
        model.setValueAt("99.00", row, LedgerTableModel.PAYMENT);
        assertTrue(model.cancelEdit());
        row = model.rowOfTransaction(e.id);
        assertEquals("Shell", model.getValueAt(row, LedgerTableModel.PAYEE));
        assertEquals("Shell", saved(e.id).payee);
        assertFalse(model.commitEdit(), "nothing left to save");
        assertTrue(saves.isEmpty());
    }

    @Test
    void typingTheSameAgainSavesNothing() throws Exception {
        LedgerEntry e = txn("Shell", -4_000, 2);
        show();
        model.setValueAt("Shell", model.rowOfTransaction(e.id), LedgerTableModel.PAYEE);
        assertFalse(model.commitEdit());
        assertTrue(saves.isEmpty());
    }

    @Test
    void editingAnotherTransactionSavesTheFirst() throws Exception {
        LedgerEntry a = txn("Shell", -4_000, 2);
        LedgerEntry b = txn("Walmart", -2_000, 3);
        show();
        model.setValueAt("Shell Oil", model.rowOfTransaction(a.id), LedgerTableModel.PAYEE);
        model.setValueAt("Walmart #12", model.rowOfTransaction(b.id), LedgerTableModel.PAYEE);
        assertEquals("Shell Oil", saved(a.id).payee);
        assertEquals("Walmart", saved(b.id).payee, "the second is still being edited");
        model.commitEdit();
        assertEquals("Walmart #12", saved(b.id).payee);
    }

    @Test
    void aNewDateMovesItOnlyOnceSaved() throws Exception {
        LedgerEntry a = txn("Shell", -4_000, 2);
        txn("Walmart", -2_000, 5);
        show();
        int row = model.rowOfTransaction(a.id);
        model.setValueAt("10/09/2026", row, LedgerTableModel.DATE);
        assertEquals(row, model.rowOfTransaction(a.id), "stays put while being edited");
        model.commitEdit();
        assertEquals(LocalDate.of(2026, 10, 9), saved(a.id).date);
    }

    @Test
    void aNewTransactionWaitsForEnter() throws Exception {
        show();
        int draft = model.draftRow();
        model.setValueAt("10/04/2026", draft, LedgerTableModel.DATE);
        model.setValueAt("Kroger", draft, LedgerTableModel.PAYEE);
        model.setValueAt("62.15", draft, LedgerTableModel.PAYMENT);
        model.setValueAt("Food", model.draftRow(), LedgerTableModel.CATEGORY);   // after the amount: same row
        assertTrue(transactions.findByAccount(checking).isEmpty(), "not saved yet");
        assertTrue(model.draftHasAmount());
        model.recordDraft();                            // Enter
        LedgerEntry e = transactions.findByAccount(checking).get(0);
        assertEquals("Kroger", e.payee);
        assertEquals("Food", e.category);
        assertEquals(-6_215, e.amountCents);
        assertEquals(List.of("New Transaction"), saves);
    }

    @Test
    void reReadingKeepsTheChanges() throws Exception {
        LedgerEntry e = txn("Shell", -4_000, 2);
        show();
        model.setValueAt("Shell Oil", model.rowOfTransaction(e.id), LedgerTableModel.PAYEE);
        model.reload();                                 // a sync, say
        assertEquals("Shell Oil", saved(e.id).payee);
    }

    @Test
    void aDeletedOneIsntBroughtBack() throws Exception {
        LedgerEntry e = txn("Shell", -4_000, 2);
        show();
        int row = model.rowOfTransaction(e.id);
        model.setValueAt("Shell Oil", row, LedgerTableModel.PAYEE);
        model.deleteRows(new int[] {row});
        assertTrue(transactions.findByAccount(checking).isEmpty());
    }
}
