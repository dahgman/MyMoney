package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** File > Rename File: the data file, and its folder when it's named after it. */
class RenameFileTest {

    @TempDir
    Path dir;
    private Database db;

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    /** A file made the way New File makes them: Household/Household.MyMoney, with an attachment. */
    private Path household() throws Exception {
        Path file = Files.createDirectories(dir.resolve("Household")).resolve("Household.MyMoney");
        db = Database.create(file, "password1");
        AccountRepository accounts = new AccountRepository(db);
        accounts.insert("Checking", AccountType.CHECKING, 100_000, null);
        Account checking = accounts.findAll().get(0);
        new AttachmentRepository(db).addStatement(checking, LocalDate.of(2026, 9, 30),
                Files.writeString(dir.resolve("sep.pdf"), "pdf"));
        return file;
    }

    @Test
    void renamesTheFileAndItsFolder() throws Exception {
        household();
        Database.Renamed r = db.renameTo("Martin Family");
        assertNull(r.problem());
        db = r.database();
        Path renamed = dir.resolve("Martin Family").resolve("Martin Family.MyMoney");
        assertEquals(renamed, db.getFile());
        assertTrue(Files.exists(renamed));
        assertFalse(Files.exists(dir.resolve("Household")), "the old folder is gone (renamed)");

        // Open again under the new name, with everything in it, and its attachments still found.
        AccountRepository accounts = new AccountRepository(db);
        Account checking = accounts.findAll().get(0);
        assertEquals("Checking", checking.name());
        AttachmentRepository attachments = new AttachmentRepository(db);
        AttachmentRepository.Statement s = attachments.statements(checking.id()).get(0);
        assertEquals("pdf", Files.readString(attachments.file(s.attachment(), checking)));
        assertTrue(s.attachment().path().startsWith("attachments/statements/Checking/"),
                "saved paths are relative to the folder, so they still work");
    }

    @Test
    void typingTheExtensionIsFine() throws Exception {
        household();
        db = db.renameTo("Budget.MyMoney").database();
        assertEquals(dir.resolve("Budget").resolve("Budget.MyMoney"), db.getFile());
    }

    @Test
    void aFolderNotNamedAfterTheFileStays() throws Exception {
        Path file = Files.createDirectories(dir.resolve("Money stuff")).resolve("Old.MyMoney");
        db = Database.create(file, "password1");
        assertFalse(db.hasOwnFolder());
        db = db.renameTo("New").database();
        assertEquals(dir.resolve("Money stuff").resolve("New.MyMoney"), db.getFile(), "just the file");
    }

    @Test
    void badOrTakenNamesChangeNothing() throws Exception {
        Path file = household();
        Files.createDirectories(dir.resolve("Taken"));
        for (String bad : new String[] {"", "a/b", "what?", "x:y", "dots.", "Household"}) {
            assertThrows(IllegalArgumentException.class, () -> db.renameTo(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> db.renameTo("Taken"), "a folder by that name exists");
        assertTrue(Files.exists(file));
        assertEquals(1, new AccountRepository(db).findAll().size(), "still open and working");
    }

    @Test
    void theRecentListAndDefaultFollowIt() throws Exception {
        Path xdg = Files.createDirectories(dir.resolve("config"));
        Path file = household();
        AppConfig config = new AppConfig(xdg.resolve("config.db"));
        try {
            config.addRecent(file);
            config.setDefaultDatabase(file);
            db = db.renameTo("Martin Family").database();
            config.renamed(file, db.getFile());
            assertEquals(db.getFile().toString(), config.getDefaultDatabase());
            assertEquals(db.getFile().toString(), config.getRecentDatabases().get(0));
            assertFalse(config.getRecentDatabases().contains(file.toString()));
        } finally {
            config.close();
        }
    }
}
