package com.martinsmayhem.mymoney;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a batch of a phone's changes (docs/SYNC-DESIGN.md, "How the desktop
 * applies changes"): add, edit, delete and attach, each through the same code
 * the ledger uses. A batch is one database transaction; each change in it is
 * applied, or refused with a reason, on its own. A change applied before (by
 * its opId) is skipped.
 */
final class SyncApplier {

    /** Where a batch's receipt photos come from (the mailbox). */
    interface Receipts {
        /** The photo's JPEG bytes, or null if it isn't there. */
        byte[] read(String receiptId) throws IOException, SyncCrypto.BadFileException;
    }

    /** The statuses a phone may give a transaction (not Reconciled or Reconciling). */
    private static final Set<String> PHONE_STATUSES = Set.of("U", "C", "P", "D", "S");
    /** What may change on a cleared transaction (see LedgerTableModel.canChange). */
    private static final Set<String> CLEARED_FIELDS = Set.of("payee", "category", "status");
    /** The fields an edit may change, as the phone names them. */
    private static final Set<String> FIELDS = Set.of("date", "num", "payee", "category", "tag", "memo",
            "description", "amountCents", "status", "type");

    /** A change refused: the message says why, for the phone to show. */
    private static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    private final Connection conn;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final AttachmentRepository attachments;
    private final SyncRepository sync;
    private final SyncSnapshot snapshot;

    SyncApplier(Database db, AttachmentRepository attachments) {
        this.conn = db.getConnection();
        this.accounts = new AccountRepository(db);
        this.transactions = new TransactionRepository(db);
        this.attachments = attachments;
        this.sync = new SyncRepository(db);
        this.snapshot = new SyncSnapshot(db);
    }

    // Looked up once per batch.
    private Map<String, Account> accountByUid;
    private Map<Long, String> accountUid;
    private Set<Long> synced;
    private SyncSnapshot.Categories categories;

    /**
     * Applies one change file's ops, all in one database transaction, and
     * records the file as applied.
     * @return what each op came to (ops applied before are left out)
     */
    List<SyncRepository.OpResult> apply(SyncRepository.Device device, JsonObject batch, Receipts receipts)
            throws SQLException {
        List<SyncRepository.OpResult> results = new ArrayList<>();
        conn.setAutoCommit(false);
        try {
            load();
            for (JsonElement el : batch.getAsJsonArray("ops")) {
                JsonObject op = el.getAsJsonObject();
                String opId = str(op, "opId");
                if (opId == null || sync.findOp(opId) != null) {
                    continue;                                   // applied before (or no id): once only
                }
                Savepoint sp = conn.setSavepoint();
                SyncRepository.OpResult r;
                try {
                    r = applyOne(op, opId, receipts);
                    conn.releaseSavepoint(sp);
                } catch (Refused e) {
                    conn.rollback(sp);
                    r = new SyncRepository.OpResult(opId, SyncRepository.OpResult.REJECTED, List.of(), e.getMessage());
                } catch (SQLException | IOException | RuntimeException e) {
                    conn.rollback(sp);
                    r = new SyncRepository.OpResult(opId, SyncRepository.OpResult.REJECTED, List.of(),
                            "The computer couldn't apply it: " + e.getMessage());
                }
                sync.recordOp(device.id(), r);
                results.add(r);
            }
            sync.setAppliedThrough(device.id(), batch.get("seq").getAsLong());
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e instanceof SQLException s ? s : new SQLException(e.getMessage(), e);
        } finally {
            conn.setAutoCommit(true);
        }
        return results;
    }

    private void load() throws SQLException {
        List<Account> all = accounts.findAll();
        accountUid = snapshot.accountUids();
        accountByUid = new HashMap<>();
        for (Account a : all) {
            accountByUid.put(accountUid.get(a.id()), a);
        }
        synced = SyncSnapshot.syncedWithEnvelopes(all, sync.syncedAccounts());
        categories = snapshot.categories();
    }

    private SyncRepository.OpResult applyOne(JsonObject op, String opId, Receipts receipts)
            throws Refused, SQLException, IOException {
        String kind = str(op, "op");
        List<String> conflicts = new ArrayList<>();
        String message = null;
        switch (kind == null ? "" : kind) {
            case "add" -> add(op);
            case "edit" -> message = edit(op, conflicts);
            case "delete" -> delete(op);
            case "attach" -> attach(op, receipts);
            default -> throw new Refused("This version of MyMoney doesn't know the change \"" + kind + "\".");
        }
        return new SyncRepository.OpResult(opId, conflicts.isEmpty() ? SyncRepository.OpResult.APPLIED
                : SyncRepository.OpResult.CONFLICTS, conflicts, message);
    }

    // ----- add ---------------------------------------------------------------

    private void add(JsonObject op) throws Refused, SQLException {
        Account account = syncedAccount(str(op, "accountUid"));
        String uid = str(op, "uid");
        if (uid == null || find(uid) != null) {
            throw new Refused("It was already added.");
        }
        LedgerEntry e = new LedgerEntry();
        e.date = date(op, "date");
        if (!op.has("amountCents") || op.get("amountCents").isJsonNull()) {
            throw new Refused("It has no amount.");
        }
        e.amountCents = op.get("amountCents").getAsLong();
        e.checkNumber = str(op, "num");
        e.payee = str(op, "payee");
        e.memo = str(op, "memo");
        e.description = str(op, "description");
        e.type = TransactionRepository.normalizeType(str(op, "type"));
        e.tag = tagText(op.get("tag"));
        e.status = status(str(op, "status"));
        e.category = categoryText(op.get("category"));
        if (e.category == null) {
            // As the ledger does: a payment to a payee that pays a debt goes to that debt; else the default.
            String debt = e.amountCents < 0 && e.payee != null ? transactions.debtCategoryForPayee(e.payee) : null;
            e.category = debt != null && !debt.equalsIgnoreCase("[" + account.name() + "]") ? debt
                    : account.defaultCategory();
        }
        transactions.insertInCurrentTransaction(account.id(), e);
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET uid = ? WHERE id = ?")) {
            ps.setString(1, uid);                     // the phone's own id for it
            ps.setLong(2, e.id);
            ps.executeUpdate();
        }
    }

    // ----- edit --------------------------------------------------------------

    /** Applies an edit's fields; those changed on both sides keep the computer's value (named in conflicts). */
    private String edit(JsonObject op, List<String> conflicts) throws Refused, SQLException {
        LedgerEntry e = editableEntry(str(op, "uid"));
        JsonObject changes = op.getAsJsonObject("changes");
        if (changes == null) {
            return null;
        }
        for (Map.Entry<String, JsonElement> c : changes.entrySet()) {
            String field = c.getKey();
            if (!FIELDS.contains(field)) {
                throw new Refused("This version of MyMoney can't change \"" + field + "\".");
            }
            JsonObject change = c.getValue().getAsJsonObject();
            String now = current(e, field);
            String old = given(field, change.get("old"));
            String wanted = given(field, change.get("new"));
            if (now.equals(wanted)) {
                continue;                                   // the computer has it already
            }
            if (e.status == TxnStatus.CLEARED && !CLEARED_FIELDS.contains(field)
                    || "category".equals(field) && !LedgerTableModel.canChange(e, LedgerTableModel.CATEGORY,
                    categoryText(change.get("new")))) {
                throw new Refused(describe(e) + " is cleared, so only its payee, category and status can be changed"
                        + " (and not to or from a transfer).");
            }
            if (!now.equals(old)) {
                conflicts.add(field);                       // changed here too: the computer's stays
                continue;
            }
            set(e, field, change.get("new"));
        }
        transactions.updateInCurrentTransaction(e);
        return conflicts.isEmpty() ? null : describe(e) + ": " + String.join(", ", conflicts)
                + (conflicts.size() == 1 ? " was" : " were") + " also changed on the computer, so the computer's "
                + (conflicts.size() == 1 ? "value was" : "values were") + " kept.";
    }

    /** A field's value on the computer, as compared with the phone's. */
    private static String current(LedgerEntry e, String field) {
        return switch (field) {
            case "date" -> e.date.toString();
            case "num" -> trim(e.checkNumber);
            case "payee" -> trim(e.payee);
            case "category" -> trim(e.category).toLowerCase();
            case "tag" -> trim(e.tag).toLowerCase();
            case "memo" -> trim(e.memo);
            case "description" -> trim(e.description);
            case "amountCents" -> String.valueOf(e.amountCents);
            case "status" -> e.status.code();
            case "type" -> trim(TransactionRepository.normalizeType(e.type));
            default -> "";
        };
    }

    /** A value the phone sent, in the same form as current(). */
    private String given(String field, JsonElement v) throws Refused, SQLException {
        boolean none = v == null || v.isJsonNull();
        return switch (field) {
            case "category" -> trim(categoryText(v)).toLowerCase();
            case "tag" -> trim(tagText(v)).toLowerCase();
            case "amountCents" -> none ? "0" : String.valueOf(v.getAsLong());
            case "status" -> none ? "U" : v.getAsString();
            case "type" -> trim(TransactionRepository.normalizeType(none ? null : v.getAsString()));
            default -> none ? "" : v.getAsString().trim();
        };
    }

    private void set(LedgerEntry e, String field, JsonElement v) throws Refused, SQLException {
        boolean none = v == null || v.isJsonNull();
        String s = none ? null : v.getAsString().trim();
        switch (field) {
            case "date" -> e.date = parseDate(s);
            case "num" -> e.checkNumber = s;
            case "payee" -> e.payee = s;
            case "category" -> e.category = categoryText(v);
            case "tag" -> e.tag = tagText(v);
            case "memo" -> e.memo = s;
            case "description" -> e.description = s;
            case "amountCents" -> e.amountCents = none ? 0 : v.getAsLong();
            case "status" -> e.status = status(s);
            case "type" -> e.type = TransactionRepository.normalizeType(s);
            default -> {
                // not a field (checked before)
            }
        }
    }

    // ----- delete and attach -------------------------------------------------

    private void delete(JsonObject op) throws Refused, SQLException {
        LedgerEntry e = editableEntry(str(op, "uid"));
        transactions.deleteInCurrentTransaction(e.id);
    }

    private void attach(JsonObject op, Receipts receipts) throws Refused, SQLException, IOException {
        Found f = find(str(op, "uid"));
        if (f == null) {
            throw new Refused("A receipt's transaction was deleted on the computer, so the photo wasn't kept.");
        }
        byte[] photo;
        try {
            photo = receipts.read(str(op, "receiptId"));
        } catch (SyncCrypto.BadFileException e) {
            throw new Refused("A receipt photo couldn't be read (" + e.getMessage() + ").");
        }
        if (photo == null) {
            throw new Refused("A receipt photo didn't arrive. Try attaching it again.");
        }
        String name = str(op, "fileName");
        name = name == null || name.isBlank() ? "receipt.jpg" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path dir = Files.createTempDirectory("mymoney-receipt");
        Path tmp = dir.resolve(name);
        try {
            Files.write(tmp, photo);
            attachments.addToTransaction(f.account(), f.entry().id, f.entry().date, tmp);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(dir);
        }
    }

    // ----- Looking things up ---------------------------------------------------

    private record Found(Account account, LedgerEntry entry) {
    }

    /** A transaction by its uid, or null if there's none. */
    private Found find(String uid) throws SQLException {
        if (uid == null) {
            return null;
        }
        long id;
        long accountId;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, account_id FROM txn WHERE uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                id = rs.getLong(1);
                accountId = rs.getLong(2);
            }
        }
        Account account = accountByUid.get(accountUid.get(accountId));
        for (LedgerEntry e : transactions.findByAccount(accountId)) {
            if (e.id == id) {
                return new Found(account, e);
            }
        }
        return null;
    }

    /** A transaction the phone may change, or the reason it may not. */
    private LedgerEntry editableEntry(String uid) throws Refused, SQLException {
        Found f = find(uid);
        if (f == null) {
            throw new Refused("It was deleted on the computer.");
        }
        LedgerEntry e = f.entry();
        if (!synced.contains(f.account().id())) {
            throw new Refused(describe(e) + ": " + f.account().name() + " isn't synced with the phone any more.");
        }
        if (e.status == TxnStatus.RECONCILED || e.status == TxnStatus.RECONCILING) {
            throw new Refused(describe(e) + " was reconciled on the computer, so it can't be changed.");
        }
        if (e.scheduledInterest) {
            throw new Refused(describe(e) + " is a loan's calculated interest; it can't be changed.");
        }
        if (TransactionRepository.SPLIT_LABEL.equals(e.category)) {
            throw new Refused(describe(e) + " is a split transaction; change it on the computer.");
        }
        return e;
    }

    private Account syncedAccount(String uid) throws Refused {
        Account a = uid == null ? null : accountByUid.get(uid);
        if (a == null) {
            throw new Refused("Its account was deleted on the computer.");
        }
        if (!synced.contains(a.id())) {
            throw new Refused(a.name() + " isn't synced with the phone any more.");
        }
        return a;
    }

    /**
     * A category reference as the ledger writes it: { "uid" }, { "path" }
     * (a new one), { "transferAccountUid" } ("[Savings]"), or null for none.
     */
    private String categoryText(JsonElement v) throws Refused {
        if (v == null || v.isJsonNull()) {
            return null;
        }
        JsonObject o = v.getAsJsonObject();
        String uid = str(o, "uid");
        if (uid != null) {
            String path = categories.pathByUid().get(uid);
            if (path == null) {
                throw new Refused("Its category was deleted on the computer.");
            }
            return path;
        }
        String transfer = str(o, "transferAccountUid");
        if (transfer != null) {
            Account a = accountByUid.get(transfer);
            if (a == null) {
                throw new Refused("The account it transfers to was deleted on the computer.");
            }
            return "[" + a.name() + "]";
        }
        String path = str(o, "path");
        return path == null || path.isBlank() ? null : CategoryRepository.normalize(path);
    }

    private static String tagText(JsonElement v) {
        if (v == null || v.isJsonNull() || v.getAsString().isBlank()) {
            return null;
        }
        return TagRepository.normalize(v.getAsString());
    }

    private static TxnStatus status(String code) throws Refused {
        if (code == null || code.isBlank()) {
            return TxnStatus.NONE;
        }
        if (!PHONE_STATUSES.contains(code)) {
            throw new Refused("A transaction can only be reconciled on the computer.");
        }
        return TxnStatus.fromCode(code);
    }

    private static LocalDate date(JsonObject o, String key) throws Refused {
        return parseDate(str(o, key));
    }

    private static LocalDate parseDate(String s) throws Refused {
        try {
            if (s == null) {
                throw new Refused("It has no date.");
            }
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new Refused("Its date \"" + s + "\" isn't a date.");
        }
    }

    private static String describe(LedgerEntry e) {
        String who = e.payee == null || e.payee.isBlank() ? Money.format(Math.abs(e.amountCents)) : e.payee;
        return who + " (" + e.date.format(LedgerTableModel.DISPLAY_DATE) + ")";
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o == null ? null : o.get(key);
        return v == null || v.isJsonNull() ? null : v.getAsString();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
