package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes transactions for the ledger.
 *
 * A transaction entered in the ledger has one split, which holds the amount
 * and either a category or a transfer account. Imported transactions can have
 * several splits; the ledger shows those as "--Split--" and lets you edit
 * everything except their amount and category.
 *
 * A transfer always has a matching transaction in the other account, with
 * the opposite amount. The two are linked (split.transfer_txn_id), created
 * together, kept in step, and deleted together.
 */
public class TransactionRepository {

    /** What the Category column shows for a transaction with several splits. */
    public static final String SPLIT_LABEL = "--Split--";

    private final Connection conn;
    private final CategoryRepository categories;
    private final AccountRepository accounts;
    private final TagRepository tags;

    public TransactionRepository(Database db) {
        this.conn = db.getConnection();
        this.categories = new CategoryRepository(db);
        this.accounts = new AccountRepository(db);
        this.tags = new TagRepository(db);
    }

    /** True for Category text naming a transfer account, like "[Savings]". */
    public static boolean isTransfer(String category) {
        return category != null && category.length() > 2
                && category.startsWith("[") && category.endsWith("]");
    }

    /** An account's transactions, oldest first (entry order breaks ties). */
    public List<LedgerEntry> findByAccount(long accountId) throws SQLException {
        String sql = """
            SELECT t.id, t.txn_date, t.check_number, p.name, t.description, t.memo, t.status,
                   COALESCE(sp.total, 0),
                   CASE
                       WHEN sp.n > 1 THEN ?
                       WHEN xa.id IS NOT NULL THEN '[' || xa.name || ']'
                       WHEN c.id IS NULL THEN NULL
                       WHEN cp.id IS NULL THEN c.name
                       ELSE cp.name || ':' || c.name
                   END,
                   tg.name, tg.color,
                   CASE WHEN sp.n = 1 AND xa.type = 'ENVELOPE' THEN 1 ELSE 0 END,
                   (SELECT COUNT(*) FROM attachment at WHERE at.txn_id = t.id),
                   t.download_status
            FROM txn t
            LEFT JOIN payee p ON p.id = t.payee_id
            LEFT JOIN (SELECT txn_id, COUNT(*) AS n, SUM(amount_cents) AS total,
                              MAX(category_id) AS cat, MAX(transfer_account_id) AS xfer
                         FROM split GROUP BY txn_id) sp ON sp.txn_id = t.id
            LEFT JOIN category c ON c.id = sp.cat
            LEFT JOIN category cp ON cp.id = c.parent_id
            LEFT JOIN account xa ON xa.id = sp.xfer
            LEFT JOIN tag tg ON tg.id = t.tag_id
            WHERE t.account_id = ?
            ORDER BY t.txn_date, t.id
            """;
        List<LedgerEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, SPLIT_LABEL);
            ps.setLong(2, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LedgerEntry e = new LedgerEntry();
                    e.id = rs.getLong(1);
                    e.date = LocalDate.parse(rs.getString(2));
                    e.checkNumber = rs.getString(3);
                    e.payee = rs.getString(4);
                    e.description = rs.getString(5);
                    e.memo = rs.getString(6);
                    e.status = TxnStatus.fromCode(rs.getString(7));
                    e.amountCents = rs.getLong(8);
                    e.category = rs.getString(9);
                    e.tag = rs.getString(10);
                    e.tagColor = e.tag == null ? null : TagRepository.fromHex(rs.getString(11));
                    e.toEnvelope = rs.getInt(12) == 1;
                    e.attachments = rs.getInt(13);
                    e.download = rs.getString(14);
                    list.add(e);
                }
            }
        }
        return list;
    }

    /**
     * Saves a new transaction (and its one split) and sets entry.id.
     * A transfer ("[Savings]") also creates the matching transaction in the
     * other account.
     */
    public void insert(long accountId, LedgerEntry entry) throws SQLException {
        conn.setAutoCommit(false);
        try {
            Target target = target(entry.category);       // check the category first
            String sql = "INSERT INTO txn (account_id, txn_date, payee_id, check_number,"
                    + " memo, status, description, tag_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, accountId);
                ps.setString(2, entry.date.toString());     // ISO: YYYY-MM-DD
                setId(ps, 3, payeeId(entry.payee));
                setText(ps, 4, entry.checkNumber);
                setText(ps, 5, entry.memo);
                ps.setString(6, entry.status.code());
                setText(ps, 7, entry.description);
                setId(ps, 8, tagId(entry.tag));
                ps.executeUpdate();
            }
            long id = lastInsertId();
            long splitId = insertSplit(id, entry.amountCents, target, null, null);
            if (target.transferAccountId() != null) {
                createMirror(splitId);
            }
            conn.commit();
            entry.id = id;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /**
     * Saves changes to an existing transaction, keeping the matching side of
     * any transfer in step (date, amount, payee, description, tag).
     */
    public void update(LedgerEntry entry) throws SQLException {
        inTransaction(() -> doUpdate(entry));
    }

    /**
     * Clears the downloaded flag (matched or new) on these transactions, and
     * forgets which download they came from, so importing that file again
     * offers the download again. Nothing else changes.
     */
    public void clearDownloaded(List<Long> txnIds) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE txn SET download_status = NULL, download_id = NULL WHERE id = ?")) {
                for (long id : txnIds) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
        });
    }

    /** Saves changes to several transactions at once: all of them, or none. */
    public void updateAll(List<LedgerEntry> entries) throws SQLException {
        inTransaction(() -> {
            for (LedgerEntry e : entries) {
                doUpdate(e);
            }
        });
    }

    private void doUpdate(LedgerEntry entry) throws SQLException {
        int splits = splitCount(entry.id);
        if (splits > 1 && (entry.amountCents != splitTotal(entry.id)
                || !SPLIT_LABEL.equals(entry.category))) {
            throw new SQLException("This is a split transaction. Its amount and category"
                    + " can't be changed in the ledger yet.");
        }
        String sql = "UPDATE txn SET txn_date = ?, payee_id = ?, check_number = ?,"
                + " memo = ?, status = ?, description = ?, tag_id = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.date.toString());
            setId(ps, 2, payeeId(entry.payee));
            setText(ps, 3, entry.checkNumber);
            setText(ps, 4, entry.memo);
            ps.setString(5, entry.status.code());
            setText(ps, 6, entry.description);
            setId(ps, 7, tagId(entry.tag));
            ps.setLong(8, entry.id);
            ps.executeUpdate();
        }

        if (splits == 1) {
            Target target = target(entry.category);
            long splitId;
            Long oldTransferAccount;
            Long mirror;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, transfer_account_id, transfer_txn_id FROM split WHERE txn_id = ?")) {
                ps.setLong(1, entry.id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    splitId = rs.getLong(1);
                    long x = rs.getLong(2);
                    oldTransferAccount = rs.wasNull() ? null : x;
                    long m = rs.getLong(3);
                    mirror = rs.wasNull() ? null : m;
                }
            }
            // Transfer removed or pointed at a different account: the old match goes.
            if (mirror != null && !java.util.Objects.equals(oldTransferAccount, target.transferAccountId())) {
                deleteMirror(mirror);
                mirror = null;
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE split SET amount_cents = ?,"
                    + " category_id = ?, transfer_account_id = ?, transfer_txn_id = ? WHERE id = ?")) {
                ps.setLong(1, entry.amountCents);
                setId(ps, 2, target.categoryId());
                setId(ps, 3, target.transferAccountId());
                setId(ps, 4, mirror);
                ps.setLong(5, splitId);
                ps.executeUpdate();
            }
            if (target.transferAccountId() != null && mirror == null) {
                createMirror(splitId);
            }
        } else if (splits == 0) {
            Target target = target(entry.category);
            long splitId = insertSplit(entry.id, entry.amountCents, target, null, null);
            if (target.transferAccountId() != null) {
                createMirror(splitId);
            }
        }
        syncMirrors(entry.id);
    }

    /** Deletes a transaction, and the matching side of any transfer in it. */
    public void delete(long txnId) throws SQLException {
        inTransaction(() -> doDelete(txnId));
    }

    /** Deletes several transactions at once: all of them, or none. */
    public void deleteAll(List<Long> txnIds) throws SQLException {
        inTransaction(() -> {
            for (long id : txnIds) {
                doDelete(id);
            }
        });
    }

    /** Payee names, sorted, for the bulk Change Payee drop-down. */
    public List<String> findPayeeNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM payee ORDER BY name COLLATE NOCASE")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private void doDelete(long txnId) throws SQLException {
        for (long mirror : mirrorsOf(txnId)) {
            deleteMirror(mirror);
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM txn WHERE id = ?")) {
            ps.setLong(1, txnId);
            ps.executeUpdate();             // its splits go too (ON DELETE CASCADE)
        }
    }

    /** Database work that must succeed or fail as a whole. */
    private interface Work {
        void run() throws SQLException;
    }

    private void inTransaction(Work work) throws SQLException {
        conn.setAutoCommit(false);
        try {
            work.run();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ----- Transfers -------------------------------------------------------

    /**
     * Creates the other side of a transfer split: a transaction in the
     * transfer account with the opposite amount, the same date, payee,
     * number, memo, description, status and tag, transferring back. The two
     * are linked through split.transfer_txn_id. Returns the new transaction's id.
     */
    long createMirror(long sourceSplitId) throws SQLException {
        long sourceTxn;
        long amount;
        long targetAccount;
        long sourceAccount;
        try (PreparedStatement ps = conn.prepareStatement("SELECT s.txn_id, s.amount_cents,"
                + " s.transfer_account_id, t.account_id FROM split s JOIN txn t ON t.id = s.txn_id"
                + " WHERE s.id = ?")) {
            ps.setLong(1, sourceSplitId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                sourceTxn = rs.getLong(1);
                amount = rs.getLong(2);
                targetAccount = rs.getLong(3);
                sourceAccount = rs.getLong(4);
            }
        }
        if (targetAccount == sourceAccount) {
            throw new SQLException("A transaction can't transfer to its own account.");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO txn (account_id, txn_date,"
                + " payee_id, check_number, memo, status, description, tag_id)"
                + " SELECT ?, txn_date, payee_id, check_number, memo, status, description, tag_id"
                + " FROM txn WHERE id = ?")) {
            ps.setLong(1, targetAccount);
            ps.setLong(2, sourceTxn);
            ps.executeUpdate();
        }
        long mirror = lastInsertId();
        insertSplit(mirror, -amount, new Target(null, sourceAccount), null, sourceTxn);
        link(sourceSplitId, mirror);
        return mirror;
    }

    /** Points a split at the transaction on the other side of its transfer. */
    void link(long splitId, long otherTxnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE split SET transfer_txn_id = ? WHERE id = ?")) {
            ps.setLong(1, otherTxnId);
            ps.setLong(2, splitId);
            ps.executeUpdate();
        }
    }

    /** Copies date, payee, description, tag and (opposite) amount to each matching transaction. */
    private void syncMirrors(long txnId) throws SQLException {
        String sql = """
            SELECT s.transfer_txn_id, s.amount_cents, a.name, m.status,
                   m.txn_date <> t.txn_date OR COALESCE(m.payee_id, -1) <> COALESCE(t.payee_id, -1)
                       OR (SELECT SUM(amount_cents) FROM split WHERE txn_id = m.id) <> -s.amount_cents,
                   COALESCE(m.description, '') <> COALESCE(t.description, '')
                       OR COALESCE(m.tag_id, -1) <> COALESCE(t.tag_id, -1)
            FROM split s
            JOIN txn t ON t.id = s.txn_id
            JOIN txn m ON m.id = s.transfer_txn_id
            JOIN account a ON a.id = m.account_id
            WHERE s.txn_id = ?
            """;
        List<long[]> updates = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, txnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean lockedFieldsDiffer = rs.getBoolean(5);
                    if (!lockedFieldsDiffer && !rs.getBoolean(6)) {
                        continue;                               // already matches
                    }
                    // A reconciled match may still take a new description or tag, nothing else.
                    if (lockedFieldsDiffer && TxnStatus.RECONCILED.code().equals(rs.getString(4))) {
                        throw new SQLException("The matching transaction in \"" + rs.getString(3)
                                + "\" is reconciled, so this transfer can't be changed.");
                    }
                    updates.add(new long[] {rs.getLong(1), rs.getLong(2)});
                }
            }
        }
        for (long[] u : updates) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET"
                    + " (txn_date, payee_id, description, tag_id) ="
                    + " (SELECT txn_date, payee_id, description, tag_id FROM txn WHERE id = ?)"
                    + " WHERE id = ?")) {
                ps.setLong(1, txnId);
                ps.setLong(2, u[0]);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE split SET amount_cents = ? WHERE txn_id = ? AND transfer_txn_id = ?")) {
                ps.setLong(1, -u[1]);
                ps.setLong(2, u[0]);
                ps.setLong(3, txnId);
                ps.executeUpdate();
            }
        }
    }

    /** Ids of the transactions on the other side of this one's transfers. */
    private List<Long> mirrorsOf(long txnId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT transfer_txn_id FROM split WHERE txn_id = ? AND transfer_txn_id IS NOT NULL")) {
            ps.setLong(1, txnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** Deletes the other side of a transfer, unless it is reconciled. */
    private void deleteMirror(long mirrorTxnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT t.status, a.name FROM txn t"
                + " JOIN account a ON a.id = t.account_id WHERE t.id = ?")) {
            ps.setLong(1, mirrorTxnId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;                                     // already gone
                }
                if (TxnStatus.RECONCILED.code().equals(rs.getString(1))) {
                    throw new SQLException("The matching transaction in \"" + rs.getString(2)
                            + "\" is reconciled, so this transfer can't be removed or changed.");
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM txn WHERE id = ?")) {
            ps.setLong(1, mirrorTxnId);
            ps.executeUpdate();
        }
    }

    // ----- Shared with QifImporter ----------------------------------------

    /** Where a split's money goes: a category, a transfer account, or neither. */
    record Target(Long categoryId, Long transferAccountId) {
        static final Target NONE = new Target(null, null);
    }

    /**
     * Turns Category column text into a Target. "[Savings]" is a transfer to
     * an existing account; anything else is a category, created if needed.
     */
    Target target(String category) throws SQLException {
        if (category == null || category.isBlank()) {
            return Target.NONE;
        }
        if (isTransfer(category)) {
            String name = category.substring(1, category.length() - 1).trim();
            Account a = accounts.findByName(name);
            if (a == null) {
                throw new SQLException("There is no account named \"" + name + "\" to transfer to.");
            }
            return new Target(null, a.id());
        }
        return new Target(categories.findOrCreate(category), null);
    }

    /** The tag's id, creating the tag (with an unused color) if needed. Null for none. */
    Long tagId(String tag) throws SQLException {
        try {
            return tags.findOrCreate(tag);
        } catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage());
        }
    }

    /** Adds a split and returns its id. linkedTxnId is the other side of a transfer, if known. */
    long insertSplit(long txnId, long amountCents, Target target, String memo, Long linkedTxnId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO split (txn_id, amount_cents,"
                + " category_id, transfer_account_id, memo, transfer_txn_id) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, txnId);
            ps.setLong(2, amountCents);
            setId(ps, 3, target.categoryId());
            setId(ps, 4, target.transferAccountId());
            setText(ps, 5, memo);
            setId(ps, 6, linkedTxnId);
            ps.executeUpdate();
        }
        return lastInsertId();
    }

    /** Finds a payee by name (ignoring case), creating it if needed. Null for blank. */
    Long payeeId(String name) throws SQLException {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM payee WHERE name = ? COLLATE NOCASE")) {
            ps.setString(1, trimmed);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO payee (name) VALUES (?)")) {
            ps.setString(1, trimmed);
            ps.executeUpdate();
        }
        return lastInsertId();
    }

    long lastInsertId() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void setText(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value.trim());
        }
    }

    static void setId(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    // ---------------------------------------------------------------------

    private int splitCount(long txnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM split WHERE txn_id = ?")) {
            ps.setLong(1, txnId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private long splitTotal(long txnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM split WHERE txn_id = ?")) {
            ps.setLong(1, txnId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
