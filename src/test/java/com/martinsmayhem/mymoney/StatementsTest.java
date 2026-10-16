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
        assertEquals("attachments/statements/1 - Wells Fargo/2026/1 - Wells Fargo_statement_2026-09-30.pdf",
                s.path(), "by account and year; named for the account and date, with the original's type");
        Path copy = dir.resolve("data").resolve(s.path());
        assertTrue(Files.exists(copy), "copied next to the data file");
        assertTrue(Files.exists(pdf), "the original is left where it was");
        // Transaction attachments go beside them, under attachments/transactions.
        long txn = transaction(a, LocalDate.of(2026, 9, 12));
        AttachmentRepository.Attachment t = attachments.addToTransaction(a, txn, LocalDate.of(2026, 9, 12), pdf);
        assertEquals("attachments/transactions/1 - Wells Fargo/2026/09/12/" + txn + ".pdf", t.path());
        // Another statement for the same date doesn't overwrite the first.
        AttachmentRepository.Attachment again = attachments.addStatement(a, LocalDate.of(2026, 9, 30), pdf);
        assertEquals("1 - Wells Fargo_statement_2026-09-30 (2).pdf", again.fileName());
        assertEquals("1 - Wells Fargo_statement_2026-10-31.jpg", attachments.addStatement(a,
                LocalDate.of(2026, 10, 31), Files.writeString(dir.resolve("scan.JPG"), "jpg")).fileName());
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
    void changingTheDateRenamesIt() throws Exception {
        Account a = account("Savings", AccountType.SAVINGS);
        attachments.addStatement(a, LocalDate.of(2026, 9, 1), pdf);
        AttachmentRepository.Statement s = attachments.statements(a.id()).get(0);
        attachments.redateStatement(s, a, LocalDate.of(2026, 9, 30));
        AttachmentRepository.Statement after = attachments.statements(a.id()).get(0);
        assertEquals(LocalDate.of(2026, 9, 30), after.date());
        assertEquals("attachments/statements/Savings/2026/Savings_statement_2026-09-30.pdf", after.attachment().path(),
                "named for its new date");
        assertTrue(Files.exists(attachments.file(after.attachment(), a)));
    }

    @Test
    void changingTheYearMovesIt() throws Exception {
        Account a = account("Savings", AccountType.SAVINGS);
        attachments.addStatement(a, LocalDate.of(2026, 1, 31), pdf);
        AttachmentRepository.Statement s = attachments.statements(a.id()).get(0);
        Path before = attachments.file(s.attachment(), a);
        attachments.redateStatement(s, a, LocalDate.of(2025, 12, 31));
        AttachmentRepository.Statement moved = attachments.statements(a.id()).get(0);
        assertEquals("attachments/statements/Savings/2025/Savings_statement_2025-12-31.pdf", moved.attachment().path());
        assertTrue(Files.exists(attachments.file(moved.attachment(), a)));
        assertFalse(Files.exists(before));
        assertFalse(Files.exists(before.getParent()), "the empty 2026 folder is tidied away");
    }

    @Test
    void aStatementRenamedByHandKeepsItsNameWhenRedated() throws Exception {
        Account a = account("Savings", AccountType.SAVINGS);
        attachments.addStatement(a, LocalDate.of(2026, 9, 1), pdf);
        attachments.rename(attachments.statements(a.id()).get(0).attachment(), a, "September");
        attachments.redateStatement(attachments.statements(a.id()).get(0), a, LocalDate.of(2026, 9, 30));
        assertEquals("September.pdf", attachments.statements(a.id()).get(0).attachment().fileName());
    }

    @Test
    void statementsFiledTheOldWaysMoveIntoPlace() throws Exception {
        Account a = account("Visa", AccountType.CREDIT_CARD);
        Path data = dir.resolve("data");
        // Filed by older versions: by day, and by year, under <Account>/Attachments; and a transaction attachment.
        String[] old = {"Visa/Attachments/Statements/2026/09/30/statement.pdf",
                        "Visa/Attachments/Statements/2026/October.PDF"};
        String[] dates = {"2026-09-30", "2026-10-31"};
        for (int i = 0; i < old.length; i++) {
            Files.createDirectories(data.resolve(old[i]).getParent());
            Files.writeString(data.resolve(old[i]), "month " + i);
            try (PreparedStatement ps = db.getConnection().prepareStatement("INSERT INTO attachment (account_id,"
                    + " file_name, path, added_at, statement_date) VALUES (?, ?, ?, 'x', ?)")) {
                ps.setLong(1, a.id());
                ps.setString(2, old[i].substring(old[i].lastIndexOf('/') + 1));
                ps.setString(3, old[i]);
                ps.setString(4, dates[i]);
                ps.executeUpdate();
            }
        }
        assertEquals(2, attachments.fileStatements());
        List<AttachmentRepository.Statement> list = attachments.statements(a.id());
        assertEquals("attachments/statements/Visa/2026/Visa_statement_2026-10-31.pdf", list.get(0).attachment().path());
        assertEquals("attachments/statements/Visa/2026/Visa_statement_2026-09-30.pdf", list.get(1).attachment().path());
        assertEquals("month 1", Files.readString(attachments.file(list.get(0).attachment(), a)));
        assertEquals(LocalDate.of(2026, 10, 31), list.get(0).date(), "dates are unchanged");
        assertFalse(Files.exists(data.resolve("Visa")), "the old folders are tidied away");
        assertEquals(0, attachments.fileStatements(), "nothing more to move the next time");
        // A statement renamed by hand, in its place, is left alone.
        attachments.rename(list.get(0).attachment(), a, "October");
        assertEquals(0, attachments.fileStatements());
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
        assertEquals("attachments/statements/Chase Sapphire/2026/Chase Sapphire_statement_2026-09-30.pdf",
                s.attachment().path(), "its folder and name follow the account's");
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
        assertEquals("attachments/statements/Visa/2026/September 2026.pdf", renamed.path(), "same folder");
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
        assertThrows(IllegalArgumentException.class, () -> attachments.rename(second, a, "Visa_statement_2026-09-30"),
                "the other statement already has that name");
        assertTrue(Files.exists(attachments.file(second, a)), "nothing moved");
        // Only the case changing is fine (the same file on Windows and macOS).
        assertEquals("VISA_STATEMENT_2026-09-30 (2).pdf",
                attachments.rename(second, a, "VISA_STATEMENT_2026-09-30 (2)").fileName());
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
        assertEquals("attachments/statements/Chase Sapphire/2026/Sep.pdf", s.path(), "named by hand: kept");
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
