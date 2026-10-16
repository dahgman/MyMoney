package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transaction attachments: "attachments/transactions/<Account>/YYYY/MM/DD/<id>-<payee>.<ext>". */
class AttachmentFilingTest {

    @TempDir
    Path dir;
    private Database db;
    private Path data;
    private TransactionRepository transactions;
    private AttachmentRepository attachments;
    private Account checking;

    // Windows won't let @TempDir delete a database file that is still open.
    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        data = Files.createDirectories(dir.resolve("data"));
        db = Database.create(data.resolve("T.MyMoney"), "password1");
        AccountRepository accounts = new AccountRepository(db);
        long id = accounts.insert("Checking", AccountType.CHECKING, 0, null);
        checking = accounts.findAll().get(0);
        transactions = new TransactionRepository(db);
        attachments = new AttachmentRepository(db);
    }

    private LedgerEntry txn(String payee, LocalDate date) throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = date;
        e.payee = payee;
        e.amountCents = -4_599;
        transactions.insert(checking.id(), e);
        return e;
    }

    private Path file(String name) throws Exception {
        return Files.writeString(dir.resolve(name), name);
    }

    private List<AttachmentRepository.Attachment> of(LedgerEntry e) throws Exception {
        return attachments.forTransaction(e.id);
    }

    @Test
    void namedForTheTransaction() throws Exception {
        LedgerEntry e = txn("Walmart", LocalDate.of(2026, 10, 9));
        AttachmentRepository.Attachment a = attachments.addToTransaction(checking, e.id, e.date, file("IMG_2041.PDF"));
        assertEquals("attachments/transactions/Checking/2026/10/09/" + e.id + "-Walmart.pdf", a.path(),
                "the original's type, in the date's folder, no folder per transaction");
        assertEquals("IMG_2041.PDF", Files.readString(data.resolve(a.path())), "its contents");
        assertTrue(Files.exists(dir.resolve("IMG_2041.PDF")), "the original is left alone");

        AttachmentRepository.Attachment b = attachments.addToTransaction(checking, e.id, e.date, file("receipt.jpg"));
        assertEquals(e.id + "-Walmart.jpg", b.fileName(), "its own type");
        AttachmentRepository.Attachment c = attachments.addToTransaction(checking, e.id, e.date, file("other.pdf"));
        assertEquals(e.id + "-Walmart (2).pdf", c.fileName(), "a second of the same type");
    }

    @Test
    void payeeMadeSafeOrLeftOut() throws Exception {
        LedgerEntry odd = txn("AT&T / U-verse: \"Internet\"?", LocalDate.of(2026, 10, 1));
        assertEquals(odd.id + "-AT&T _ U-verse_ _Internet__.pdf",
                attachments.addToTransaction(checking, odd.id, odd.date, file("bill.pdf")).fileName());
        LedgerEntry none = txn(null, LocalDate.of(2026, 10, 2));
        assertEquals(none.id + ".pdf", attachments.addToTransaction(checking, none.id, none.date, file("x.pdf")).fileName());
        LedgerEntry noType = txn("Shell", LocalDate.of(2026, 10, 3));
        assertEquals(noType.id + "-Shell", attachments.addToTransaction(checking, noType.id, noType.date,
                file("scan")).fileName(), "no extension on the original: none on the copy");
    }

    @Test
    void followsTheTransactionsDateAndPayee() throws Exception {
        LedgerEntry e = txn("Walmart", LocalDate.of(2026, 10, 9));
        AttachmentRepository.Attachment a = attachments.addToTransaction(checking, e.id, e.date, file("r.pdf"));
        Path before = data.resolve(a.path());

        e.payee = "Walmart Supercenter";
        e.date = LocalDate.of(2026, 10, 10);
        transactions.update(e);
        assertEquals(1, attachments.fileTransactionAttachments());
        AttachmentRepository.Attachment after = of(e).get(0);
        assertEquals("attachments/transactions/Checking/2026/10/10/" + e.id + "-Walmart Supercenter.pdf", after.path());
        assertTrue(Files.exists(data.resolve(after.path())));
        assertFalse(Files.exists(before));
        assertFalse(Files.exists(before.getParent()), "the emptied day folder is tidied away");
        assertTrue(Files.exists(data.resolve("attachments/transactions/Checking/2026/10")), "the month's still in use");
        assertEquals(0, attachments.fileTransactionAttachments(), "already in place");
    }

    @Test
    void undoPutsTheFilesBackToo() throws Exception {
        LedgerEntry e = txn("Walmart", LocalDate.of(2026, 10, 9));
        attachments.addToTransaction(checking, e.id, e.date, file("r.pdf"));
        UndoLog undo = new UndoLog(db);
        attachments.setUndoLog(undo);                  // as the main window does

        // As the main window does: change, checkpoint, then file the attachments (not an undo step).
        e.date = LocalDate.of(2026, 11, 1);
        transactions.update(e);
        undo.checkpoint("Edit Date");
        attachments.fileTransactionAttachments();
        assertTrue(of(e).get(0).path().contains("/2026/11/01/"));

        undo.undo();
        attachments.fileTransactionAttachments();
        AttachmentRepository.Attachment back = of(e).get(0);
        assertEquals("attachments/transactions/Checking/2026/10/09/" + e.id + "-Walmart.pdf", back.path());
        assertTrue(Files.exists(data.resolve(back.path())), "the listing and the file agree");
        assertFalse(Files.exists(data.resolve("attachments/transactions/Checking/2026/11")));

        undo.redo();
        attachments.fileTransactionAttachments();
        assertTrue(Files.exists(data.resolve(of(e).get(0).path())));
        assertTrue(of(e).get(0).path().contains("/2026/11/01/"));
    }

    @Test
    void theOldLayoutIsConverted() throws Exception {
        LedgerEntry e = txn("Costco", LocalDate.of(2026, 9, 12));
        String old = "Checking/Attachments/Transactions/2026/09/12/" + e.id + "/Costco receipt.png";   // filed the old way
        Files.createDirectories(data.resolve(old).getParent());
        Files.writeString(data.resolve(old), "png");
        try (PreparedStatement ps = db.getConnection().prepareStatement("INSERT INTO attachment"
                + " (account_id, txn_id, file_name, path, added_at) VALUES (?, ?, 'Costco receipt.png', ?, 'x')")) {
            ps.setLong(1, checking.id());
            ps.setLong(2, e.id);
            ps.setString(3, old);
            ps.executeUpdate();
        }
        assertEquals(1, attachments.fileTransactionAttachments());
        AttachmentRepository.Attachment a = of(e).get(0);
        assertEquals("attachments/transactions/Checking/2026/09/12/" + e.id + "-Costco.png", a.path());
        assertEquals("png", Files.readString(data.resolve(a.path())));
        assertFalse(Files.exists(data.resolve("Checking")), "the old account folder is tidied away");
    }

    @Test
    void attachingIsNotAnUndoStepButTheRestOfTheActionIs() throws Exception {
        UndoLog undo = new UndoLog(db);
        attachments.setUndoLog(undo);
        // One action (like the transaction dialog): a new transaction, with a file attached to it.
        LedgerEntry e = txn("Walmart", LocalDate.of(2026, 10, 9));
        AttachmentRepository.Attachment a = attachments.addToTransaction(checking, e.id, e.date, file("r.pdf"));
        undo.checkpoint("New Transaction");
        // Another: the file removed.
        attachments.remove(a, checking);
        undo.checkpoint("Attachments");
        assertEquals("New Transaction", undo.undoLabel(), "removing the file isn't an undo step");
        assertTrue(of(e).isEmpty());
        undo.undo();                                       // the transaction goes
        assertTrue(transactions.findByAccount(checking.id()).isEmpty());
        assertTrue(of(e).isEmpty(), "and no attachment comes back pointing at the deleted file");
    }

    @Test
    void undoingADeletedTransactionBringsItsFilesBack() throws Exception {
        LedgerEntry e = txn("Walmart", LocalDate.of(2026, 10, 9));
        AttachmentRepository.Attachment a = attachments.addToTransaction(checking, e.id, e.date, file("r.pdf"));
        UndoLog undo = new UndoLog(db);
        attachments.setUndoLog(undo);
        transactions.delete(e.id);                         // its attachment's listing goes with it; the file stays
        undo.checkpoint("Delete");
        assertTrue(of(e).isEmpty());
        assertTrue(Files.exists(data.resolve(a.path())));
        undo.undo();
        assertEquals(1, of(e).size(), "Undo brings the transaction back with its attachment");
        assertTrue(Files.exists(data.resolve(of(e).get(0).path())));
    }

    @Test
    void removingTidiesEmptyFolders() throws Exception {
        LedgerEntry e = txn("Walmart", LocalDate.of(2026, 10, 9));
        AttachmentRepository.Attachment a = attachments.addToTransaction(checking, e.id, e.date, file("r.pdf"));
        attachments.remove(a, checking);
        assertFalse(Files.exists(data.resolve("attachments")), "emptied folders are tidied away");
        assertTrue(Files.exists(data.resolve("T.MyMoney")), "the data file's own folder stays, of course");
    }
}
