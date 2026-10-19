package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Edit Transactions (several fields at once) and payees on reconciled transactions. */
class BulkEditTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private LedgerTableModel model;
    private long checking;
    private long savings;

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
        savings = accounts.insert("Savings", AccountType.SAVINGS, 0, null);
        model = new LedgerTableModel(transactions, m -> {
            throw new AssertionError(m);
        }, q -> true, () -> { });
    }

    private LedgerEntry txn(long account, String payee, long cents, String category, TxnStatus status, int day)
            throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 10, day);
        e.payee = payee;
        e.amountCents = cents;
        e.category = category;
        e.status = status;
        transactions.insert(account, e);
        return e;
    }

    private void show(long account) throws Exception {
        model.setAccount(accounts.findAll().stream().filter(a -> a.id() == account).findFirst().orElseThrow());
    }

    private int rowOf(long id) {
        for (int r = 0; r < model.getRowCount() - 1; r++) {
            if (model.transactionId(r) == id) {
                return r;
            }
        }
        throw new AssertionError("no row for " + id);
    }

    private LedgerEntry get(long account, long id) throws Exception {
        return transactions.findByAccount(account).stream().filter(x -> x.id == id).findFirst().orElseThrow();
    }

    @Test
    void severalFieldsAtOnce() throws Exception {
        LedgerEntry a = txn(checking, "WALMART #1234", -4_599, "Food", TxnStatus.NONE, 1);
        LedgerEntry b = txn(checking, "WAL-MART 5678", -1_250, null, TxnStatus.CLEARED, 2);
        show(checking);
        Map<Integer, Object> values = new LinkedHashMap<>();
        values.put(LedgerTableModel.PAYEE, "Walmart");
        values.put(LedgerTableModel.CATEGORY, "Food:Groceries");
        values.put(LedgerTableModel.TAG, "Household");
        values.put(LedgerTableModel.TYPE, "pos");
        values.put(LedgerTableModel.MEMO, "");                         // ticked and blank: cleared
        values.put(LedgerTableModel.DATE, LocalDate.of(2026, 10, 5));
        values.put(LedgerTableModel.STATUS, TxnStatus.CLEARED);
        assertNull(model.bulkSet(new int[] {rowOf(a.id), rowOf(b.id)}, values), "everything changed");
        for (long id : new long[] {a.id, b.id}) {
            LedgerEntry e = get(checking, id);
            assertEquals("Walmart", e.payee);
            assertEquals("Food:Groceries", e.category);
            assertEquals("Household", e.tag);
            assertEquals("POS", e.type);
            assertEquals(LocalDate.of(2026, 10, 5), e.date);
            assertEquals(TxnStatus.CLEARED, e.status);
        }
        assertEquals(-4_599, get(checking, a.id).amountCents, "amounts untouched");
        assertEquals("Edit Transactions", model.lastAction(), "one change");
    }

    @Test
    void reconciledOnesTakeOnlyWhatCantChangeABalance() throws Exception {
        LedgerEntry open = txn(checking, "Shell", -3_000, null, TxnStatus.CLEARED, 1);
        LedgerEntry done = txn(checking, "SHELL OIL 123", -4_000, "Auto", TxnStatus.RECONCILED, 2);
        show(checking);
        Map<Integer, Object> values = new LinkedHashMap<>();
        values.put(LedgerTableModel.PAYEE, "Shell");
        values.put(LedgerTableModel.CATEGORY, "Auto:Fuel");
        values.put(LedgerTableModel.DATE, LocalDate.of(2026, 10, 20));
        String note = model.bulkSet(new int[] {rowOf(open.id), rowOf(done.id)}, values);
        assertTrue(note != null && note.contains("1 reconciled transaction kept"), note);
        LedgerEntry r = get(checking, done.id);
        assertEquals("Shell", r.payee, "a reconciled payee can change");
        assertEquals("Auto:Fuel", r.category, "and its (non-transfer) category");
        assertEquals(LocalDate.of(2026, 10, 2), r.date, "but not its date");
        assertEquals(TxnStatus.RECONCILED, r.status);
        assertEquals(LocalDate.of(2026, 10, 20), get(checking, open.id).date, "the unreconciled one moves");
    }

    @Test
    void aReconciledPayeeIsEditableInTheLedger() throws Exception {
        LedgerEntry done = txn(checking, "AMZN Mktp US*2K4", -2_999, null, TxnStatus.RECONCILED, 3);
        show(checking);
        int row = rowOf(done.id);
        assertTrue(model.isCellEditable(row, LedgerTableModel.PAYEE));
        assertTrue(!model.isCellEditable(row, LedgerTableModel.PAYMENT), "its amount stays locked");
        assertTrue(!model.isCellEditable(row, LedgerTableModel.DATE));
        model.setValueAt("Amazon", row, LedgerTableModel.PAYEE);
        assertEquals("Amazon", get(checking, done.id).payee);
        assertEquals(TxnStatus.RECONCILED, get(checking, done.id).status);
    }

    @Test
    void aTransferWhoseOtherSideIsReconciledTakesANewPayee() throws Exception {
        LedgerEntry t = txn(checking, "Xfer 4421", -20_000, "[Savings]", TxnStatus.NONE, 4);
        LedgerEntry other = transactions.findByAccount(savings).get(0);
        other.status = TxnStatus.RECONCILED;
        transactions.update(other);
        show(checking);
        model.setValueAt("Monthly savings", rowOf(t.id), LedgerTableModel.PAYEE);
        assertEquals("Monthly savings", get(checking, t.id).payee);
        LedgerEntry o = transactions.findByAccount(savings).get(0);
        assertEquals("Monthly savings", o.payee, "the reconciled side follows");
        assertEquals(TxnStatus.RECONCILED, o.status);
        assertEquals(20_000, o.amountCents);
    }

    @Test
    void undoTakesItAllBackInOneStep() throws Exception {
        LedgerEntry a = txn(checking, "A", -100, null, TxnStatus.NONE, 1);
        LedgerEntry b = txn(checking, "B", -200, null, TxnStatus.NONE, 2);
        UndoLog undo = new UndoLog(db);
        show(checking);
        model.bulkSet(new int[] {rowOf(a.id), rowOf(b.id)},
                Map.of(LedgerTableModel.PAYEE, "Same", LedgerTableModel.MEMO, "note"));
        undo.checkpoint(model.lastAction());
        undo.undo();
        assertEquals(List.of("A", "B"), transactions.findByAccount(checking).stream().map(e -> e.payee).toList());
    }
}
