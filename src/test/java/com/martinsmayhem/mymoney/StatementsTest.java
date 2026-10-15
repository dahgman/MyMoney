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
import org.junit.jupiter.api.AfterEach;
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

    // Windows won't let @TempDir delete a database file that is still open.
    @AfterEach
    void tearDown() {
        db.close();
    }

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
        assertEquals("1 - Wells Fargo/Attachments/Statements/2026/Statement_2026-09-30.pdf", s.path(),
                "filed by year only");
        Path copy = dir.resolve("data").resolve(s.path());
        assertTrue(Files.exists(copy), "copied next to the data file");
        assertTrue(Files.exists(pdf), "the original is left where it was");
        // Transaction attachments live beside it, in the same account folder.
        long txn = transaction(a, LocalDate.of(2026, 9, 12));
        AttachmentRepository.Attachment t = attachments.addToTransaction(a, txn, LocalDate.of(2026, 9, 12), pdf);
        assertEquals("1 - Wells Fargo/Attachments/Transactions/2026/09/12/" + txn + ".pdf", t.path());
        // Another with the same name that year doesn't overwrite the first.
        AttachmentRepository.Attachment again = attachments.addStatement(a, LocalDate.of(2026, 10, 31), pdf);
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
    void changingTheDateWithinTheYearLeavesTheFile() throws Exception {
        Account a = account("Savings", AccountType.SAVINGS);
        attachments.addStatement(a, LocalDate.of(2026, 9, 1), pdf);
        AttachmentRepository.Statement s = attachments.statements(a.id()).get(0);
        String path = s.attachment().path();
        attachments.redateStatement(s, a, LocalDate.of(2026, 9, 30));
        AttachmentRepository.Statement after = attachments.statements(a.id()).get(0);
        assertEquals(LocalDate.of(2026, 9, 30), after.date());
        assertEquals(path, after.attachment().path(), "same year: same folder");
        assertTrue(Files.exists(attachments.file(after.attachment(), a)));
    }

    @Test
    void changingTheYearMovesTheFile() throws Exception {
        Account a = account("Savings", AccountType.SAVINGS);
        attachments.addStatement(a, LocalDate.of(2026, 1, 31), pdf);
        AttachmentRepository.Statement s = attachments.statements(a.id()).get(0);
        Path before = attachments.file(s.attachment(), a);
        attachments.redateStatement(s, a, LocalDate.of(2025, 12, 31));
        AttachmentRepository.Statement moved = attachments.statements(a.id()).get(0);
        assertEquals("Savings/Attachments/Statements/2025/Statement_2026-09-30.pdf", moved.attachment().path());
        assertTrue(Files.exists(attachments.file(moved.attachment(), a)));
        assertFalse(Files.exists(before));
        assertFalse(Files.exists(before.getParent()), "the empty 2026 folder is tidied away");
    }

    @Test
    void statementsFiledByDayMoveUpToTheirYear() throws Exception {
        Account a = account("Visa", AccountType.CREDIT_CARD);
        Path data = dir.resolve("data");
        // Two statements filed the old way (one each in September and October), and a transaction attachment.
        String[] old = {"Visa/Attachments/Statements/2026/09/30/statement.pdf",
                        "Visa/Attachments/Statements/2026/10/31/statement.pdf"};
        String[] dates = {"2026-09-30", "2026-10-31"};
        for (int i = 0; i < old.length; i++) {
            Files.createDirectories(data.resolve(old[i]).getParent());
            Files.writeString(data.resolve(old[i]), "month " + i);
            try (PreparedStatement ps = db.getConnection().prepareStatement("INSERT INTO attachment (account_id,"
                    + " file_name, path, added_at, statement_date) VALUES (?, 'statement.pdf', ?, 'x', ?)")) {
                ps.setLong(1, a.id());
                ps.setString(2, old[i]);
                ps.setString(3, dates[i]);
                ps.executeUpdate();
            }
        }
        long txn = transaction(a, LocalDate.of(2026, 9, 12));
        AttachmentRepository.Attachment t = attachments.addToTransaction(a, txn, LocalDate.of(2026, 9, 12), pdf);

        assertEquals(2, attachments.fileStatementsByYear());
        List<AttachmentRepository.Statement> list = attachments.statements(a.id());
        assertEquals("Visa/Attachments/Statements/2026/statement (2).pdf", list.get(0).attachment().path(),
                "October's, renamed so it doesn't overwrite September's");
        assertEquals("Visa/Attachments/Statements/2026/statement.pdf", list.get(1).attachment().path());
        assertEquals("month 1", Files.readString(attachments.file(list.get(0).attachment(), a)));
        assertEquals(LocalDate.of(2026, 10, 31), list.get(0).date(), "dates are unchanged");
        assertFalse(Files.exists(data.resolve("Visa/Attachments/Statements/2026/09")), "old folders tidied away");
        assertTrue(Files.exists(data.resolve(t.path())), "transaction attachments are left alone");
        assertEquals(0, attachments.fileStatementsByYear(), "nothing more to move the next time");
    }

    @Test
    void removeIsNotUndone() throws Exception {
        Account a = account("Car", AccountType.LOAN);
        UndoLog undo = new UndoLog(db);
        attachments.setUndoLog(undo);                  // as the main window does
        attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        AttachmentRepository.Statement s = attachments.statements(a.id()).get(0);
        Path file = attachments.file(s.attachment(), a);
        attachments.remove(s.attachment(), a);
        assertTrue(attachments.statements(a.id()).isEmpty());
        assertFalse(Files.exists(file), "its copy is deleted");
        assertEquals(null, undo.undoLabel(), "nothing to undo: a removed statement doesn't come back without its file");
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
    void renamingAStatement() throws Exception {
        Account a = account("Visa", AccountType.CREDIT_CARD);
        attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        AttachmentRepository.Attachment s = attachments.statements(a.id()).get(0).attachment();
        Path before = attachments.file(s, a);

        AttachmentRepository.Attachment renamed = attachments.rename(s, a, "  September 2026  ");
        assertEquals("September 2026.pdf", renamed.fileName(), "typed without an extension: keeps .pdf");
        assertEquals("Visa/Attachments/Statements/2026/September 2026.pdf", renamed.path(), "same folder");
        assertFalse(Files.exists(before));
        assertTrue(Files.exists(attachments.file(renamed, a)));
        AttachmentRepository.Statement listed = attachments.statements(a.id()).get(0);
        assertEquals("September 2026.pdf", listed.attachment().fileName());
        assertEquals(LocalDate.of(2026, 9, 30), listed.date(), "its date is unchanged");

        assertEquals("Sept.txt", attachments.rename(listed.attachment(), a, "Sept.txt").fileName(),
                "a typed extension is used as typed");
    }

    @Test
    void badOrTakenNamesAreRefused() throws Exception {
        Account a = account("Visa", AccountType.CREDIT_CARD);
        attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);           // "... (2).pdf"
        List<AttachmentRepository.Statement> list = attachments.statements(a.id());
        AttachmentRepository.Attachment second = list.get(0).attachment();
        for (String bad : new String[] {"", "  ", "a/b", "a\\b", "what?", "x:y", "dots.", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> attachments.rename(second, a, bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> attachments.rename(second, a, "Statement_2026-09-30"),
                "the other statement already has that name");
        assertTrue(Files.exists(attachments.file(second, a)), "nothing moved");
        // Only the case changing is fine (the same file on Windows and macOS).
        assertEquals("STATEMENT_2026-09-30 (2).pdf",
                attachments.rename(second, a, "STATEMENT_2026-09-30 (2)").fileName());
    }

    @Test
    void reconcileStatementsCanBeRenamedAndUndoLeavesThemAlone() throws Exception {
        Account a = account("Checking", AccountType.CHECKING);
        try (var st = db.getConnection().createStatement()) {
            st.execute("INSERT INTO reconciliation (account_id, statement_date, ending_balance_cents)"
                    + " VALUES (" + a.id() + ", '2026-08-31', 100)");
        }
        attachments.addToStatement(a, 1, LocalDate.of(2026, 8, 31), pdf);
        UndoLog undo = new UndoLog(db);
        attachments.setUndoLog(undo);                  // as the main window does
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 9, 1);
        e.amountCents = 500;
        new TransactionRepository(db).insert(a.id(), e);
        undo.checkpoint("New Transaction");                // an earlier step, still undoable

        AttachmentRepository.Attachment s = attachments.statements(a.id()).get(0).attachment();
        AttachmentRepository.Attachment renamed = attachments.rename(s, a, "August");
        assertEquals("New Transaction", undo.undoLabel(), "the rename isn't an undo step");
        undo.undo();
        assertTrue(new TransactionRepository(db).findByAccount(a.id()).isEmpty(), "the earlier step undid");
        AttachmentRepository.Attachment listed = attachments.statements(a.id()).get(0).attachment();
        assertEquals("August.pdf", listed.fileName(), "the statement keeps its new name");
        assertTrue(Files.exists(attachments.file(listed, a)), "and its file is still found");
    }

    @Test
    void renamedStatementFollowsAnAccountRename() throws Exception {
        Account a = account("Chase", AccountType.CREDIT_CARD);
        attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        attachments.rename(attachments.statements(a.id()).get(0).attachment(), a, "Sep");
        attachments.renameAccountFolder("Chase", "Chase Sapphire");
        AttachmentRepository.Attachment s = attachments.statements(a.id()).get(0).attachment();
        assertEquals("Chase Sapphire/Attachments/Statements/2026/Sep.pdf", s.path());
        assertTrue(Files.exists(dir.resolve("data").resolve(s.path())));
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
