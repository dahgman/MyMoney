package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Account statements, added on their own, filed with the transaction attachments. */
class StatementsTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private AttachmentRepository attachments;
    private Path pdf;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(Files.createDirectories(dir.resolve("data")).resolve("T.MyMoney"), "password1");
        accounts = new AccountRepository(db);
        attachments = new AttachmentRepository(db);
        pdf = Files.writeString(dir.resolve("Statement_2026-09-30.pdf"), "%PDF- pretend");
    }

    private Account account(String name, AccountType type) throws Exception {
        long id = accounts.insert(name, type, 0, null);
        return accounts.findAll().stream().filter(a -> a.id() == id).findFirst().orElseThrow();
    }

    private long transaction(Account a, LocalDate date) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = date;
        e.amountCents = -1_000;
        new TransactionRepository(db).insert(a.id(), e);
        return e.id;
    }

    @ParameterizedTest
    @EnumSource(AccountType.class)
    void anyAccountTakesStatements(AccountType type) throws Exception {
        Account a = account("Acct " + type, type);
        attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        List<AttachmentRepository.Statement> list = attachments.statements(a.id());
        assertEquals(1, list.size());
        assertEquals(LocalDate.of(2026, 9, 30), list.get(0).date());
        assertFalse(list.get(0).fromReconcile());
    }

    @Test
    void filedLikeTransactionAttachments() throws Exception {
        Account a = account("1 - Wells Fargo", AccountType.CHECKING);
        AttachmentRepository.Attachment s = attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        assertEquals("1 - Wells Fargo/Attachments/Statements/2026/09/30/Statement_2026-09-30.pdf", s.path());
        Path copy = dir.resolve("data").resolve(s.path());
        assertTrue(Files.exists(copy), "copied next to the data file");
        assertTrue(Files.exists(pdf), "the original is left where it was");
        // Transaction attachments live beside it, in the same account folder.
        long txn = transaction(a, LocalDate.of(2026, 9, 12));
        AttachmentRepository.Attachment t = attachments.addToTransaction(a, txn, LocalDate.of(2026, 9, 12), pdf);
        assertTrue(t.path().startsWith("1 - Wells Fargo/Attachments/Transactions/2026/09/12/" + txn + "/"));
        // A second file with the same name on the same date doesn't overwrite the first.
        AttachmentRepository.Attachment again = attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        assertTrue(again.fileName().endsWith("(2).pdf"), again.fileName());
    }

    @Test
    void reconcileStatementsAreListedToo() throws Exception {
        Account a = account("Visa", AccountType.CREDIT_CARD);
        long rec;
        try (PreparedStatement ps = db.getConnection().prepareStatement("INSERT INTO reconciliation"
                + " (account_id, statement_date, ending_balance_cents) VALUES (?, '2026-08-31', -12345)")) {
            ps.setLong(1, a.id());
            ps.executeUpdate();
        }
        rec = attachments.statements(a.id()).size();      // nothing yet
        assertEquals(0, rec);
        long recId;
        try (var st = db.getConnection().createStatement();
             var rs = st.executeQuery("SELECT max(id) FROM reconciliation")) {
            rs.next();
            recId = rs.getLong(1);
        }
        attachments.addToStatement(a, recId, LocalDate.of(2026, 8, 31), pdf);
        attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        attachments.addToTransaction(a, transaction(a, LocalDate.of(2026, 9, 2)), LocalDate.of(2026, 9, 2),
                pdf);                                                             // not a statement
        List<AttachmentRepository.Statement> list = attachments.statements(a.id());
        assertEquals(2, list.size(), "the two statements, not the transaction's attachment");
        assertEquals(LocalDate.of(2026, 9, 30), list.get(0).date(), "newest first");
        assertTrue(list.get(1).fromReconcile());
        assertEquals(LocalDate.of(2026, 8, 31), list.get(1).date(), "dated by its reconciliation");
        assertThrows(java.sql.SQLException.class,
                () -> attachments.redateStatement(list.get(1), a, LocalDate.of(2026, 8, 30)));
    }

    @Test
    void changingTheDateMovesTheFile() throws Exception {
        Account a = account("Savings", AccountType.SAVINGS);
        attachments.addStatement(a, LocalDate.of(2026, 9, 1), pdf);
        AttachmentRepository.Statement s = attachments.statements(a.id()).get(0);
        Path before = attachments.file(s.attachment(), a);
        attachments.redateStatement(s, a, LocalDate.of(2026, 9, 30));
        AttachmentRepository.Statement moved = attachments.statements(a.id()).get(0);
        assertEquals(LocalDate.of(2026, 9, 30), moved.date());
        assertEquals("Savings/Attachments/Statements/2026/09/30/Statement_2026-09-30.pdf", moved.attachment().path());
        assertTrue(Files.exists(attachments.file(moved.attachment(), a)));
        assertFalse(Files.exists(before));
        assertFalse(Files.exists(before.getParent()), "the empty day folder is tidied away");
    }

    @Test
    void removeAndUndo() throws Exception {
        Account a = account("Car", AccountType.LOAN);
        UndoLog undo = new UndoLog(db);
        attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        undo.checkpoint("Statements");
        AttachmentRepository.Statement s = attachments.statements(a.id()).get(0);
        Path file = attachments.file(s.attachment(), a);
        attachments.remove(s.attachment(), a);
        assertTrue(attachments.statements(a.id()).isEmpty());
        assertFalse(Files.exists(file), "its copy is deleted");
        undo.checkpoint("Statements");
        undo.undo();
        assertEquals(1, attachments.statements(a.id()).size(), "Undo brings the listing back");
    }

    @Test
    void renamingTheAccountTakesItsStatementsAlong() throws Exception {
        Account a = account("Chase", AccountType.CREDIT_CARD);
        attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        attachments.renameAccountFolder("Chase", "Chase Sapphire");
        Account renamed = new Account(a.id(), "Chase Sapphire", a.type(), a.openingBalanceCents(), null, false,
                a.parentId());
        AttachmentRepository.Statement s = attachments.statements(a.id()).get(0);
        assertTrue(s.attachment().path().startsWith("Chase Sapphire/Attachments/Statements/"));
        assertTrue(Files.exists(attachments.file(s.attachment(), renamed)));
    }

    @Test
    void datesGuessedFromFileNames() {
        LedgerTableModel.setDateFormat("MM/dd/yyyy");
        assertEquals(LocalDate.of(2026, 9, 30), StatementsDialog.guessDate("Statement_2026-09-30.pdf"));
        assertEquals(LocalDate.of(2026, 9, 30), StatementsDialog.guessDate("eStmt_20260930.pdf"));
        assertEquals(LocalDate.of(2026, 9, 30), StatementsDialog.guessDate("093026 x 09302026.pdf"));
        assertEquals(LocalDate.of(2026, 9, 30), StatementsDialog.guessDate("Chase 09-30-2026.pdf"));
        assertEquals(YearMonth.now().minusMonths(1).atEndOfMonth(), StatementsDialog.guessDate("statement.pdf"),
                "no date in it: the end of last month");
        LedgerTableModel.setDateFormat("dd/MM/yyyy");
        assertEquals(LocalDate.of(2026, 9, 3), StatementsDialog.guessDate("03-09-2026.pdf"), "day first");
        LedgerTableModel.setDateFormat("MM/dd/yyyy");
    }
}
