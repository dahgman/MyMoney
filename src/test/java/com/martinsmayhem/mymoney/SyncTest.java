package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Syncing with a phone (docs/SYNC-DESIGN.md), with a pretend phone and a folder for the mailbox. */
class SyncTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private AttachmentRepository attachments;
    private SyncRepository sync;
    private SyncEngine engine;
    private Path mailboxDir;
    private FakePhone phone;
    private Account checking;
    private Account savings;
    private Account card;
    private Account vacation;
    private final LocalDate today = LocalDate.now();

    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(Files.createDirectories(dir.resolve("data")).resolve("Household.MyMoney"), "password1");
        accounts = new AccountRepository(db);
        transactions = new TransactionRepository(db);
        attachments = new AttachmentRepository(db);
        sync = new SyncRepository(db);
        engine = new SyncEngine(db, attachments);
        long c = accounts.insert("Checking", AccountType.CHECKING, 100_000, null);
        long s = accounts.insert("Savings", AccountType.SAVINGS, 500_000, null);
        accounts.insert("Credit Card", AccountType.CREDIT_CARD, 0, null);
        accounts.insert("Vacation", AccountType.ENVELOPE, 0, s);
        checking = account("Checking");
        savings = account("Savings");
        card = account("Credit Card");
        vacation = account("Vacation");
        sync.setSynced(c, true);
        sync.setSynced(s, true);
        mailboxDir = Files.createDirectories(dir.resolve("mailbox"));
        sync.saveSettings(new SyncRepository.Settings("folder", mailboxDir.toString(), "", "", "", "",
                "https://nas:5006", "/MyMoney-Sync", 5));
        phone = new FakePhone(sync.addDevice("Pixel"), sync.fileId());
    }

    private Account account(String name) throws Exception {
        return accounts.findAll().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private LedgerEntry txn(Account a, LocalDate date, String payee, long cents, TxnStatus status, String category)
            throws Exception {
        LedgerEntry e = new LedgerEntry();
        e.date = date;
        e.payee = payee;
        e.amountCents = cents;
        e.status = status;
        e.category = category;
        transactions.insert(a.id(), e);
        return byId(a, e.id);
    }

    private LedgerEntry byId(Account a, long id) throws Exception {
        return transactions.findByAccount(a.id()).stream().filter(x -> x.id == id).findFirst().orElse(null);
    }

    private LedgerEntry byUid(Account a, String uid) throws Exception {
        return transactions.findByAccount(a.id()).stream().filter(x -> uid.equals(x.uid)).findFirst().orElse(null);
    }

    /** What the phone does: reads its snapshot, writes change files and receipts. */
    private final class FakePhone {
        final SyncRepository.Device device;
        final String fileId;
        long seq;

        FakePhone(SyncRepository.Device device, String fileId) {
            this.device = device;
            this.fileId = fileId;
        }

        Path file(String path) {
            return mailboxDir.resolve(fileId).resolve(path);
        }

        JsonObject snapshot() throws Exception {
            String path = SyncEngine.snapshotPath(device);
            byte[] plain = SyncCrypto.decrypt(device.key(), path, Files.readAllBytes(file(path)));
            return JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        /** Writes one change file with these ops; returns its path. */
        Path send(JsonObject... ops) throws Exception {
            seq++;
            JsonObject batch = new JsonObject();
            batch.addProperty("format", 1);
            batch.addProperty("deviceId", device.uid());
            batch.addProperty("seq", seq);
            JsonArray list = new JsonArray();
            for (JsonObject op : ops) {
                list.add(op);
            }
            batch.add("ops", list);
            String path = SyncEngine.changePath(device, seq);
            Path f = file(path);
            Files.createDirectories(f.getParent());
            Files.write(f, SyncCrypto.encrypt(device.key(), path, batch.toString().getBytes(StandardCharsets.UTF_8)));
            return f;
        }

        String sendReceipt(byte[] jpeg) throws Exception {
            String id = UUID.randomUUID().toString();
            String path = SyncEngine.receiptPath(device, id);
            Path f = file(path);
            Files.createDirectories(f.getParent());
            Files.write(f, SyncCrypto.encrypt(device.key(), path, jpeg));
            return id;
        }
    }

    private static JsonObject op(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("op", kind);
        o.addProperty("opId", UUID.randomUUID().toString());
        return o;
    }

    private String uidOf(Account a) throws Exception {
        return new SyncSnapshot(db).accountUids().get(a.id());
    }

    private JsonObject add(Account a, String payee, long cents) throws Exception {
        JsonObject o = op("add");
        o.addProperty("uid", UUID.randomUUID().toString());
        o.addProperty("accountUid", uidOf(a));
        o.addProperty("date", today.toString());
        o.addProperty("payee", payee);
        o.addProperty("amountCents", cents);
        o.addProperty("status", "U");
        return o;
    }

    private static JsonObject edit(String uid, String field, Object old, Object now) {
        JsonObject o = op("edit");
        o.addProperty("uid", uid);
        JsonObject changes = new JsonObject();
        JsonObject c = new JsonObject();
        if (old instanceof Number n) {
            c.addProperty("old", n);
            c.addProperty("new", (Number) now);
        } else {
            c.addProperty("old", (String) old);
            c.addProperty("new", (String) now);
        }
        changes.add(field, c);
        o.add("changes", changes);
        return o;
    }

    private static JsonObject result(JsonObject snapshot, JsonObject op) {
        for (JsonElement r : snapshot.getAsJsonArray("results")) {
            if (r.getAsJsonObject().get("opId").getAsString().equals(op.get("opId").getAsString())) {
                return r.getAsJsonObject();
            }
        }
        return null;
    }

    // ----- The file format -------------------------------------------------------------

    @Test
    void filesOnlyOpenWithTheirKeyAndInTheirPlace() throws Exception {
        byte[] key = SyncCrypto.newKey();
        byte[] sealed = SyncCrypto.encrypt(key, "devices/a/snapshot.mms", "hello".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), SyncCrypto.decrypt(key, "devices/a/snapshot.mms", sealed));
        assertThrows(SyncCrypto.BadFileException.class,
                () -> SyncCrypto.decrypt(SyncCrypto.newKey(), "devices/a/snapshot.mms", sealed), "another key");
        assertThrows(SyncCrypto.BadFileException.class,
                () -> SyncCrypto.decrypt(key, "devices/b/snapshot.mms", sealed), "moved to another place");
        sealed[sealed.length - 1] ^= 1;
        assertThrows(SyncCrypto.BadFileException.class,
                () -> SyncCrypto.decrypt(key, "devices/a/snapshot.mms", sealed), "changed");
    }

    @Test
    void everyRowHasAUid() throws Exception {
        LedgerEntry e = txn(checking, today, "Walmart", -100, TxnStatus.NONE, null);
        assertNotNull(e.uid);
        assertEquals(36, e.uid.length());
        assertTrue(e.uid.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), e.uid);
        assertNotNull(uidOf(checking));
    }

    // ----- The snapshot ----------------------------------------------------------------

    @Test
    void theSnapshotHasWhatThePhoneShows() throws Exception {
        txn(checking, today.minusDays(100), "Old reconciled", -1_000, TxnStatus.RECONCILED, null);
        txn(checking, today.minusDays(20), "Recent reconciled", -2_000, TxnStatus.RECONCILED, null);
        txn(checking, today.minusDays(200), "Old uncleared", -3_000, TxnStatus.NONE, null);
        txn(checking, today.plusDays(5), "Rent", -150_000, TxnStatus.SCHEDULED, null);
        txn(checking, today, "Groceries", -4_000, TxnStatus.CLEARED, "Food:Groceries");
        txn(checking, today, "To vacation", -10_000, TxnStatus.NONE, "[Vacation]");
        txn(card, today, "Card charge", -500, TxnStatus.NONE, null);
        engine.runNow();

        JsonObject s = phone.snapshot();
        List<String> payees = s.getAsJsonArray("transactions").asList().stream()
                .map(t -> t.getAsJsonObject().get("payee").getAsString()).toList();
        assertTrue(payees.containsAll(List.of("Recent reconciled", "Old uncleared", "Rent", "Groceries", "To vacation")));
        assertFalse(payees.contains("Old reconciled"), "reconciled over 60 days ago: left out");
        assertFalse(payees.contains("Card charge"), "the card isn't synced");
        assertTrue(payees.contains("To vacation") && payees.stream().filter("To vacation"::equals).count() == 2,
                "both sides: Checking's, and the Vacation envelope's (synced with Savings)");

        JsonObject cardJson = s.getAsJsonArray("accounts").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(a -> a.get("name").getAsString().equals("Credit Card")).findFirst().orElseThrow();
        assertFalse(cardJson.get("synced").getAsBoolean());
        assertFalse(cardJson.has("balanceCents"), "an unsynced account: just its name");
        JsonObject checkingJson = s.getAsJsonArray("accounts").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(a -> a.get("name").getAsString().equals("Checking")).findFirst().orElseThrow();
        long balance = checkingJson.get("balanceCents").getAsLong();
        assertEquals(100_000 - 1_000 - 2_000 - 3_000 - 150_000 - 4_000 - 10_000, balance);
        long shown = s.getAsJsonArray("transactions").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(t -> t.get("accountUid").getAsString().equals(checkingJson.get("uid").getAsString()))
                .mapToLong(t -> t.get("netCents").getAsLong()).sum();
        assertEquals(balance, checkingJson.get("ledgerStartCents").getAsLong() + shown,
                "the phone's running balance ends at the real balance");
        JsonObject vacationJson = s.getAsJsonArray("accounts").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(a -> a.get("name").getAsString().equals("Vacation")).findFirst().orElseThrow();
        assertEquals(10_000, vacationJson.get("balanceCents").getAsLong());
        assertEquals(uidOf(savings), vacationJson.get("parentUid").getAsString());

        JsonObject recent = s.getAsJsonArray("transactions").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(t -> t.get("payee").getAsString().equals("Recent reconciled")).findFirst().orElseThrow();
        assertFalse(recent.get("editable").getAsBoolean());
        assertTrue(s.getAsJsonArray("categories").asList().stream()
                .anyMatch(c -> c.getAsJsonObject().get("path").getAsString().equals("Food:Groceries")));
    }

    @Test
    void scheduledOnesAreLeftOutIfThePhoneDoesntWantThem() throws Exception {
        txn(checking, today.plusDays(5), "Rent", -150_000, TxnStatus.SCHEDULED, null);
        sync.setDeviceOptions(phone.device.id(), "Pixel", false);
        engine.runNow();
        assertTrue(phone.snapshot().getAsJsonArray("transactions").isEmpty());
        assertFalse(phone.snapshot().getAsJsonObject("settings").get("showScheduled").getAsBoolean());
    }

    // ----- The phone's changes -----------------------------------------------------------

    @Test
    void addEditDeleteFromThePhone() throws Exception {
        JsonObject add = add(checking, "Starbucks", -575);
        JsonObject category = new JsonObject();
        category.addProperty("path", "Food:Coffee");
        add.add("category", category);
        Path changeFile = phone.send(add);
        SyncEngine.Summary sum = engine.runNow();
        assertEquals(1, sum.applied());
        String uid = add.get("uid").getAsString();
        LedgerEntry e = byUid(checking, uid);
        assertEquals("Starbucks", e.payee);
        assertEquals("Food:Coffee", e.category, "a new category, made as the ledger would");
        assertEquals(-575, e.amountCents);
        assertFalse(Files.exists(changeFile), "applied: deleted from the mailbox");
        assertEquals("applied", result(phone.snapshot(), add).get("result").getAsString());
        assertEquals(1, phone.snapshot().get("appliedThrough").getAsLong());

        JsonObject edit = edit(uid, "amountCents", -575, -625);
        phone.send(edit);
        engine.runNow();
        assertEquals(-625, byUid(checking, uid).amountCents);

        JsonObject del = op("delete");
        del.addProperty("uid", uid);
        phone.send(del);
        engine.runNow();
        assertNull(byUid(checking, uid));
        assertEquals("applied", result(phone.snapshot(), del).get("result").getAsString());
    }

    @Test
    void aTransferGetsItsOtherSide() throws Exception {
        JsonObject add = add(checking, "Save some", -20_000);
        JsonObject category = new JsonObject();
        category.addProperty("transferAccountUid", uidOf(vacation));
        add.add("category", category);
        phone.send(add);
        engine.runNow();
        assertEquals("[Vacation]", byUid(checking, add.get("uid").getAsString()).category);
        assertEquals(20_000, accounts.balanceThrough(vacation.id(), today));
    }

    @Test
    void bothSidesChangedTheSameFieldTheComputerWins() throws Exception {
        LedgerEntry e = txn(checking, today, "Shell", -4_000, TxnStatus.NONE, null);
        e.memo = "typed on the computer";
        transactions.update(e);
        JsonObject o = op("edit");
        o.addProperty("uid", e.uid);
        JsonObject changes = new JsonObject();
        JsonObject memo = new JsonObject();
        memo.addProperty("old", "");
        memo.addProperty("new", "typed on the phone");
        changes.add("memo", memo);
        JsonObject amount = new JsonObject();
        amount.addProperty("old", -4_000);
        amount.addProperty("new", -4_250);
        changes.add("amountCents", amount);
        o.add("changes", changes);
        phone.send(o);
        SyncEngine.Summary sum = engine.runNow();
        assertEquals(1, sum.conflicts());
        LedgerEntry after = byId(checking, e.id);
        assertEquals("typed on the computer", after.memo, "the computer's memo stays");
        assertEquals(-4_250, after.amountCents, "the other field still changes");
        JsonObject r = result(phone.snapshot(), o);
        assertEquals("applied-with-conflicts", r.get("result").getAsString());
        assertEquals("memo", r.getAsJsonArray("conflicts").get(0).getAsString());
    }

    @Test
    void whatThePhoneCantChangeIsRefusedWithAReason() throws Exception {
        LedgerEntry reconciled = txn(checking, today, "Rent", -150_000, TxnStatus.RECONCILED, null);
        JsonObject onReconciled = edit(reconciled.uid, "payee", "Rent", "Landlord");
        JsonObject onCard = add(card, "Coffee", -500);
        JsonObject reconcile = add(checking, "Sneaky", -100);
        reconcile.addProperty("status", "R");
        JsonObject fine = add(checking, "Fine", -100);
        phone.send(onReconciled, onCard, reconcile, fine);
        SyncEngine.Summary sum = engine.runNow();
        assertEquals(3, sum.rejected());
        assertEquals(1, sum.applied(), "the rest of the file still applies");
        JsonObject s = phone.snapshot();
        assertTrue(result(s, onReconciled).get("message").getAsString().contains("reconciled"));
        assertTrue(result(s, onCard).get("message").getAsString().contains("isn't synced"));
        assertTrue(result(s, reconcile).get("message").getAsString().contains("reconciled on the computer"));
        assertEquals("Rent", byId(checking, reconciled.id).payee);
        assertNotNull(byUid(checking, fine.get("uid").getAsString()));
    }

    @Test
    void aClearedOneTakesOnlyAPayeeCategoryOrStatusFromThePhone() throws Exception {
        LedgerEntry e = txn(checking, today, "Shell", -4_000, TxnStatus.CLEARED, null);
        JsonObject amount = edit(e.uid, "amountCents", -4_000, -4_500);
        JsonObject payee = edit(e.uid, "payee", "Shell", "Shell Oil");
        phone.send(amount, payee);
        SyncEngine.Summary sum = engine.runNow();
        assertEquals(1, sum.rejected());
        assertEquals(1, sum.applied());
        LedgerEntry after = byId(checking, e.id);
        assertEquals(-4_000, after.amountCents, "its amount stays");
        assertEquals("Shell Oil", after.payee);
        assertTrue(result(phone.snapshot(), amount).get("message").getAsString().contains("is cleared"));
    }

    @Test
    void aChangeSentTwiceIsAppliedOnce() throws Exception {
        JsonObject add = add(checking, "Coffee", -500);
        phone.send(add);
        engine.runNow();
        phone.send(add);                                 // the phone didn't hear back, so sent it again
        engine.runNow();
        assertEquals(1, transactions.findByAccount(checking.id()).stream().filter(x -> "Coffee".equals(x.payee)).count());
    }

    @Test
    void aReceiptPhotoIsFiledWithItsTransaction() throws Exception {
        JsonObject add = add(checking, "Home Depot", -8_999);
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        String receiptId = phone.sendReceipt(jpeg);
        JsonObject attach = op("attach");
        attach.addProperty("uid", add.get("uid").getAsString());
        attach.addProperty("receiptId", receiptId);
        attach.addProperty("fileName", "receipt.jpg");
        phone.send(add, attach);
        engine.runNow();
        LedgerEntry e = byUid(checking, add.get("uid").getAsString());
        List<AttachmentRepository.Attachment> files = attachments.forTransaction(e.id);
        assertEquals(1, files.size());
        assertArrayEquals(jpeg, Files.readAllBytes(dir.resolve("data").resolve(files.get(0).path())));
        assertTrue(files.get(0).path().startsWith("attachments/transactions/Checking/"));
        assertFalse(Files.exists(phone.file(SyncEngine.receiptPath(phone.device, receiptId))), "taken from the mailbox");
    }

    @Test
    void aFileThatWontOpenWaitsAndSaysSo() throws Exception {
        JsonObject first = add(checking, "First", -100);
        Path bad = phone.send(first);
        Files.write(bad, new byte[] {1, 2, 3});              // damaged
        JsonObject second = add(checking, "Second", -100);
        phone.send(second);
        SyncEngine.Summary sum = engine.runNow();
        assertFalse(sum.ok());
        assertTrue(sum.problems().get(0).contains("couldn't be read"), sum.problems().get(0));
        assertTrue(Files.exists(bad), "left for next time");
        assertNull(byUid(checking, second.get("uid").getAsString()), "and the one after it waits, in order");
    }

    @Test
    void undoTakesBackASync() throws Exception {
        UndoLog undo = new UndoLog(db);
        undo.checkpoint("Before");
        JsonObject add = add(checking, "Coffee", -500);
        phone.send(add);
        engine.runNow();
        undo.checkpoint("Sync from Pixel");
        assertNotNull(byUid(checking, add.get("uid").getAsString()));
        undo.undo();
        assertNull(byUid(checking, add.get("uid").getAsString()));
    }

    @Test
    void anOlderFileGetsUidsWhenUpgraded() throws Exception {
        LedgerEntry e = txn(checking, today, "Old", -100, TxnStatus.NONE, null);
        try (java.sql.Statement st = db.getConnection().createStatement()) {
            st.execute("UPDATE txn SET uid = NULL");
            st.execute("UPDATE meta SET value = '20' WHERE key = 'schema_version'");
        }
        db.close();
        db = Database.open(dir.resolve("data/Household.MyMoney"), "password1");
        transactions = new TransactionRepository(db);
        assertNotNull(byId(checking, e.id).uid);
    }

    @Test
    void thePairingCodeHoldsWhatThePhoneNeeds() throws Exception {
        SyncRepository.Settings s = sync.settings();
        String text = SyncDialog.pairingText(sync.fileId(), "Household", phone.device, s);
        java.awt.image.BufferedImage img = SyncDialog.qrImage(text);    // just as the pairing window shows it
        int[] pixels = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        String read = new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(
                new com.google.zxing.common.HybridBinarizer(new com.google.zxing.RGBLuminanceSource(
                        img.getWidth(), img.getHeight(), pixels)))).getText();
        assertEquals(text, read);
        JsonObject o = JsonParser.parseString(read).getAsJsonObject();
        assertEquals(phone.device.uid(), o.get("deviceId").getAsString());
        assertArrayEquals(phone.device.key(), SyncCrypto.fromBase64(o.get("key").getAsString()));
        assertEquals(sync.fileId(), o.get("fileId").getAsString());
        assertEquals("https://nas:5006", o.getAsJsonObject("mailbox").get("url").getAsString());
        assertFalse(read.contains("password"), "the NAS password isn't in it");
    }

    // ----- WebDAV --------------------------------------------------------------------------

    @Test
    void theTestButtonLeavesNothingBehind() throws Exception {
        Path root = Files.createDirectories(dir.resolve("box"));
        String old = "test-a51c038d-0e8a-4083-bbfe-557308967de0.txt";
        Files.write(root.resolve(old + ".tmp"), new byte[] {1});      // left by a test whose MOVE failed
        Files.write(root.resolve("keep.mms"), new byte[] {1});
        Files.createDirectories(root.resolve(sync.fileId()));
        SyncDialog.cleanUpTests(new FolderMailbox(root));
        assertFalse(Files.exists(root.resolve(old + ".tmp")));
        assertTrue(Files.exists(root.resolve("keep.mms")), "nothing else is touched");
        assertTrue(Files.isDirectory(root.resolve(sync.fileId())));

        Path davRoot = Files.createDirectories(dir.resolve("dav3/MyMoney-Sync"));
        Files.write(davRoot.resolve(old + ".tmp"), new byte[] {1});
        Files.write(davRoot.resolve("keep.mms"), new byte[] {1});
        try (TinyWebDav server = new TinyWebDav(dir.resolve("dav3"), "sync", "secret")) {
            SyncDialog.cleanUpTests(new WebDavMailbox(server.url(), "/MyMoney-Sync", "sync", "secret"));
        }
        assertFalse(Files.exists(davRoot.resolve(old + ".tmp")));
        assertTrue(Files.exists(davRoot.resolve("keep.mms")));
    }

    /** Behind a reverse proxy, MOVE can fail with 502: the file is written in place instead. */
    @Test
    void aServerThatWontMoveStillGetsTheFile() throws Exception {
        Path root = Files.createDirectories(dir.resolve("dav2"));
        try (TinyWebDav server = new TinyWebDav(root, "sync", "secret")) {
            server.refuseMove = true;
            WebDavMailbox m = new WebDavMailbox(server.url(), "/WebDav-mnm/MyMoney-Sync", "sync", "secret");
            Files.createDirectories(root.resolve("WebDav-mnm"));
            m.write("a/one.mms", new byte[] {1, 2});
            assertArrayEquals(new byte[] {1, 2}, m.read("a/one.mms"));
            assertTrue(m.writesInPlace());
            assertEquals(List.of("one.mms"), m.list("a"), "no .tmp left behind");
            assertFalse(Files.exists(root.resolve("WebDav-mnm/MyMoney-Sync/a/one.mms.tmp")));
            m.write("a/one.mms", new byte[] {3});
            assertArrayEquals(new byte[] {3}, m.read("a/one.mms"), "and from then on, straight in");
        }
    }

    @Test
    void theWebDavMailboxWorksAgainstAServer() throws Exception {
        try (TinyWebDav server = new TinyWebDav(Files.createDirectories(dir.resolve("dav")), "sync", "secret")) {
            Mailbox m = new WebDavMailbox(server.url(), "/MyMoney Sync", "sync", "secret");
            assertTrue(m.list("a/b").isEmpty(), "a folder that isn't there: nothing in it");
            m.write("a/b/one.mms", new byte[] {1, 2});
            m.write("a/b/two.mms", new byte[] {3});
            assertEquals(List.of("one.mms", "two.mms"), m.list("a/b"));
            assertArrayEquals(new byte[] {1, 2}, m.read("a/b/one.mms"));
            m.write("a/b/one.mms", new byte[] {9});
            assertArrayEquals(new byte[] {9}, m.read("a/b/one.mms"), "replaced");
            m.delete("a/b/one.mms");
            assertNull(m.read("a/b/one.mms"));
            m.deleteFolder("a");
            assertTrue(m.list("a/b").isEmpty());
            assertFalse(((WebDavMailbox) m).writesInPlace());
            Mailbox wrong = new WebDavMailbox(server.url(), "/MyMoney Sync", "sync", "nope");
            java.io.IOException e = assertThrows(java.io.IOException.class, () -> wrong.list("a"));
            assertTrue(e.getMessage().contains("user name or password"));
        }
    }
}
