package com.martinsmayhem.mymoney;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a phone's snapshot: everything it shows (docs/SYNC-DESIGN.md, "What
 * the phone gets"). Every account's name; for synced accounts (and the
 * envelopes under them) their balances, every unreconciled transaction and
 * the reconciled ones from the last 60 days; the category, payee, tag and
 * type lists; and the results of the phone's changes.
 */
final class SyncSnapshot {

    static final int FORMAT = 1;
    /** How far back reconciled transactions go to the phone (read-only, for reference). */
    static final int RECONCILED_DAYS = 60;

    private final Database db;
    private final Connection conn;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final TagRepository tags;
    private final SyncRepository sync;

    SyncSnapshot(Database db) {
        this.db = db;
        this.conn = db.getConnection();
        this.accounts = new AccountRepository(db);
        this.transactions = new TransactionRepository(db);
        this.tags = new TagRepository(db);
        this.sync = new SyncRepository(db);
    }

    /**
     * The accounts that go to the phone in full: those ticked to sync, and
     * the envelopes under them.
     */
    static Set<Long> syncedWithEnvelopes(List<Account> all, Set<Long> ticked) {
        Set<Long> synced = new java.util.HashSet<>(ticked);
        for (Account a : all) {
            if (a.type() == AccountType.ENVELOPE && a.parentId() != null && ticked.contains(a.parentId())) {
                synced.add(a.id());
            }
        }
        return synced;
    }

    /** True if the phone may change this transaction (see "editable" in the design). */
    static boolean editable(LedgerEntry e) {
        return e.status != TxnStatus.RECONCILED && e.status != TxnStatus.RECONCILING && !e.scheduledInterest
                && !TransactionRepository.SPLIT_LABEL.equals(e.category);
    }

    /** True if a transaction goes to the phone at all. */
    static boolean included(LedgerEntry e, LocalDate today, boolean includeScheduled) {
        if (e.status == TxnStatus.SCHEDULED) {
            return includeScheduled;
        }
        return e.status != TxnStatus.RECONCILED || !e.date.isBefore(today.minusDays(RECONCILED_DAYS));
    }

    /** Category paths ("Food:Groceries") and their uids, both ways round (paths in lower case as keys). */
    record Categories(Map<String, String> uidByPath, Map<String, String> pathByUid, Map<String, Boolean> income) {
    }

    Categories categories() throws SQLException {
        Map<String, String> uidByPath = new HashMap<>();
        Map<String, String> pathByUid = new HashMap<>();
        Map<String, Boolean> income = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT c.uid,"
                + " CASE WHEN p.id IS NULL THEN c.name ELSE p.name || ':' || c.name END, c.is_income"
                + " FROM category c LEFT JOIN category p ON p.id = c.parent_id ORDER BY 2");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                uidByPath.put(rs.getString(2).toLowerCase(), rs.getString(1));
                pathByUid.put(rs.getString(1), rs.getString(2));
                income.put(rs.getString(1), rs.getInt(3) == 1);
            }
        }
        return new Categories(uidByPath, pathByUid, income);
    }

    /** Account uids by account id. */
    Map<Long, String> accountUids() throws SQLException {
        Map<Long, String> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, uid FROM account");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getLong(1), rs.getString(2));
            }
        }
        return map;
    }

    /** The snapshot for this phone, as JSON. */
    JsonObject build(SyncRepository.Device device, LocalDate today) throws SQLException {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("snapshotSeq", sync.nextSnapshotSeq());
        root.addProperty("made", Instant.now().toString());
        root.addProperty("fileName", db.baseName());
        root.addProperty("appliedThrough", device.appliedThrough());

        JsonArray results = new JsonArray();
        for (SyncRepository.OpResult r : sync.recentResults(device.id())) {
            JsonObject o = new JsonObject();
            o.addProperty("opId", r.opId());
            o.addProperty("result", r.result());
            if (!r.conflicts().isEmpty()) {
                JsonArray c = new JsonArray();
                r.conflicts().forEach(c::add);
                o.add("conflicts", c);
            }
            if (r.message() != null) {
                o.addProperty("message", r.message());
            }
            results.add(o);
        }
        root.add("results", results);

        JsonObject settings = new JsonObject();
        settings.addProperty("showScheduled", device.includeScheduled());
        settings.addProperty("dateFormat", LedgerTableModel.datePattern());
        settings.addProperty("currency", "USD");
        root.add("settings", settings);

        List<Account> all = accounts.findAll();
        Set<Long> synced = syncedWithEnvelopes(all, sync.syncedAccounts());
        Map<Long, String> accountUid = accountUids();
        Map<String, String> accountUidByName = new HashMap<>();
        for (Account a : all) {
            accountUidByName.put(a.name().toLowerCase(), accountUid.get(a.id()));
        }
        Categories cats = categories();

        JsonArray accountList = new JsonArray();
        JsonArray txnList = new JsonArray();
        for (Account a : all) {
            JsonObject o = new JsonObject();
            o.addProperty("uid", accountUid.get(a.id()));
            o.addProperty("name", a.name());
            o.addProperty("type", a.type().name());
            o.addProperty("parentUid", a.parentId() == null ? null : accountUid.get(a.parentId()));
            o.addProperty("closed", a.closed());
            boolean isSynced = synced.contains(a.id());
            o.addProperty("synced", isSynced);
            if (isSynced) {
                AccountRepository.Balances b = accounts.balances(a);
                o.addProperty("balanceCents", b.totalCents());
                o.addProperty("clearedCents", b.clearedCents());
                o.addProperty("todayBalanceCents", accounts.balanceThrough(a.id(), today));
                long included = 0;
                for (LedgerEntry e : transactions.findByAccount(a.id())) {
                    if (!included(e, today, device.includeScheduled())) {
                        continue;
                    }
                    included += e.netCents();
                    txnList.add(transaction(e, accountUid.get(a.id()), cats, accountUidByName));
                }
                o.addProperty("ledgerStartCents", b.totalCents() - included);
            }
            accountList.add(o);
        }
        root.add("accounts", accountList);

        JsonArray categoryList = new JsonArray();
        cats.pathByUid().entrySet().stream().sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
                .forEach(en -> {
                    JsonObject c = new JsonObject();
                    c.addProperty("uid", en.getKey());
                    c.addProperty("path", en.getValue());
                    c.addProperty("income", cats.income().get(en.getKey()));
                    categoryList.add(c);
                });
        root.add("categories", categoryList);

        JsonArray payees = new JsonArray();
        transactions.findPayeeNames().forEach(payees::add);
        root.add("payees", payees);
        JsonArray tagList = new JsonArray();
        for (TagRepository.Tag t : tags.findAll()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("color", TagRepository.toHex(t.color()));
            tagList.add(o);
        }
        root.add("tags", tagList);
        JsonArray types = new JsonArray();
        transactions.findTypes().forEach(types::add);
        root.add("types", types);

        root.add("transactions", txnList);
        return root;
    }

    private static JsonObject transaction(LedgerEntry e, String accountUid, Categories cats,
                                          Map<String, String> accountUidByName) {
        JsonObject o = new JsonObject();
        o.addProperty("uid", e.uid);
        o.addProperty("accountUid", accountUid);
        o.addProperty("date", e.date.toString());
        o.addProperty("bankDate", e.bankDate == null ? null : e.bankDate.toString());
        o.addProperty("num", text(e.checkNumber));
        o.addProperty("payee", text(e.payee));
        boolean split = TransactionRepository.SPLIT_LABEL.equals(e.category);
        String categoryUid = null;
        String transferUid = null;
        if (!split && e.category != null) {
            if (TransactionRepository.isTransfer(e.category)) {
                transferUid = accountUidByName.get(e.category.substring(1, e.category.length() - 1).toLowerCase());
            } else {
                categoryUid = cats.uidByPath().get(e.category.toLowerCase());
            }
        }
        o.addProperty("categoryUid", categoryUid);
        o.addProperty("transferAccountUid", transferUid);
        o.addProperty("split", split);
        o.addProperty("tag", e.tag);
        o.addProperty("memo", text(e.memo));
        o.addProperty("description", text(e.description));
        o.addProperty("type", e.type);
        o.addProperty("amountCents", e.amountCents);
        o.addProperty("netCents", e.netCents());
        o.addProperty("status", e.status.code());
        o.addProperty("editable", editable(e));
        o.addProperty("receipts", e.attachments);
        return o;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    /** JSON null, for code that adds one explicitly. */
    static final JsonNull NULL = JsonNull.INSTANCE;
}
