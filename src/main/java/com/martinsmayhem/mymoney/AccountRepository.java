package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes rows in the account table. */
public class AccountRepository {

    private static final String COLUMNS =
            "id, name, type, opening_balance_cents, currency, closed, parent_id";

    private final Connection conn;

    public AccountRepository(Database db) {
        this.conn = db.getConnection();
    }

    /** Every account, sorted by name (case-insensitive). */
    public List<Account> findAll() throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM account ORDER BY name COLLATE NOCASE";
        return query(sql);
    }

    /** Accounts an envelope can belong to: open, top-level, and not envelopes themselves. */
    public List<Account> findParentCandidates() throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM account"
                + " WHERE parent_id IS NULL AND closed = 0 AND type <> 'ENVELOPE'"
                + " ORDER BY name COLLATE NOCASE";
        return query(sql);
    }

    /** True if an account already has this name, ignoring upper/lower case. */
    public boolean nameExists(String name) throws SQLException {
        return nameExists(name, 0);
    }

    /** True if an account other than excludeId already has this name, ignoring case. */
    public boolean nameExists(String name, long excludeId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM account WHERE name = ? COLLATE NOCASE AND id <> ?")) {
            ps.setString(1, name);
            ps.setLong(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** The account with this name (ignoring case), or null if there isn't one. */
    public Account findByName(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLUMNS + " FROM account WHERE name = ? COLLATE NOCASE")) {
            ps.setString(1, name);
            List<Account> found = read(ps);
            return found.isEmpty() ? null : found.get(0);
        }
    }

    /** True if any account has this one as its parent. */
    public boolean hasChildren(long accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM account WHERE parent_id = ? LIMIT 1")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Adds an account and returns its new id.
     * @param parentId the parent account's id, or null for a top-level account
     */
    public long insert(String name, AccountType type, long openingBalanceCents, Long parentId)
            throws SQLException {
        String sql = "INSERT INTO account (name, type, opening_balance_cents, parent_id)"
                + " VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, type.name());
            ps.setLong(3, openingBalanceCents);
            if (parentId == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setLong(4, parentId);
            }
            ps.executeUpdate();
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Saves changes to an account's name, type, opening balance and parent. */
    public void update(long id, String name, AccountType type, long openingBalanceCents, Long parentId)
            throws SQLException {
        String sql = "UPDATE account SET name = ?, type = ?, opening_balance_cents = ?, parent_id = ?"
                + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, type.name());
            ps.setLong(3, openingBalanceCents);
            if (parentId == null) {
                ps.setNull(4, Types.INTEGER);
            } else {
                ps.setLong(4, parentId);
            }
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    /** What deleting an account would affect, for the confirmation message. */
    public record DeleteInfo(int transactions, int reconciled, int transfers, List<String> envelopes) {
    }

    public DeleteInfo deleteInfo(long id) throws SQLException {
        int transactions;
        int reconciled;
        int transfers;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT (SELECT COUNT(*) FROM txn WHERE account_id = ?),
                       (SELECT COUNT(*) FROM txn WHERE account_id = ? AND status = ?),
                       (SELECT COUNT(*) FROM split s JOIN txn t ON t.id = s.txn_id
                         WHERE s.transfer_account_id = ? AND t.account_id <> ?)
                """)) {
            ps.setLong(1, id);
            ps.setLong(2, id);
            ps.setString(3, TxnStatus.RECONCILED.code());
            ps.setLong(4, id);
            ps.setLong(5, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                transactions = rs.getInt(1);
                reconciled = rs.getInt(2);
                transfers = rs.getInt(3);
            }
        }
        List<String> envelopes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM account WHERE parent_id = ? ORDER BY name COLLATE NOCASE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    envelopes.add(rs.getString(1));
                }
            }
        }
        return new DeleteInfo(transactions, reconciled, transfers, envelopes);
    }

    /**
     * Deletes an account and all of its transactions, all at once. Transfers
     * with other accounts keep their side in the other account, as a plain
     * transaction with no category, so those balances don't change.
     * The account must not have envelopes.
     */
    public void delete(long id) throws SQLException {
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE split SET transfer_account_id = NULL, transfer_txn_id = NULL"
                    + " WHERE transfer_account_id = ?"
                    + " AND txn_id IN (SELECT id FROM txn WHERE account_id <> ?)")) {
                ps.setLong(1, id);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            for (String sql : new String[] {
                    "DELETE FROM attachment WHERE account_id = ?",      // the files stay on disk
                    "DELETE FROM reconciliation WHERE account_id = ?",
                    "DELETE FROM txn WHERE account_id = ?"}) {         // their splits go too (ON DELETE CASCADE)
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM account WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** Sets just the opening balance (used by the QIF import). */
    public void setOpeningBalance(long id, long openingBalanceCents) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE account SET opening_balance_cents = ? WHERE id = ?")) {
            ps.setLong(1, openingBalanceCents);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /**
     * An account's balances.
     * cleared: opening balance plus reconciled, cleared and reconciling transactions.
     * total:   opening balance plus every transaction, whatever its status or date.
     */
    public record Balances(long clearedCents, long totalCents) {
    }

    public Balances balances(Account account) throws SQLException {
        String sql = "SELECT COALESCE(SUM(CASE WHEN t.status IN (?, ?, ?) THEN s.amount_cents ELSE 0 END), 0),"
                + " COALESCE(SUM(s.amount_cents), 0)"
                + " FROM split s JOIN txn t ON s.txn_id = t.id"
                + " WHERE t.account_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TxnStatus.RECONCILED.code());
            ps.setString(2, TxnStatus.CLEARED.code());
            ps.setString(3, TxnStatus.RECONCILING.code());
            ps.setLong(4, account.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long opening = account.openingBalanceCents();
                return new Balances(opening + rs.getLong(1), opening + rs.getLong(2));
            }
        }
    }

    /**
     * The balance shown next to each account in the account tree, by account id:
     * the cleared balance (opening + reconciled, cleared and reconciling), except
     * for envelopes, which show their current balance (opening + every
     * transaction dated today or earlier).
     */
    public java.util.Map<Long, Long> treeBalances() throws SQLException {
        String sql = "SELECT a.id, a.opening_balance_cents + COALESCE(SUM(CASE"
                + " WHEN a.type = 'ENVELOPE' THEN CASE WHEN t.txn_date <= ? THEN s.amount_cents END"
                + " WHEN t.status IN (?, ?, ?) THEN s.amount_cents END), 0)"
                + " FROM account a LEFT JOIN txn t ON t.account_id = a.id LEFT JOIN split s ON s.txn_id = t.id"
                + " GROUP BY a.id";
        java.util.Map<Long, Long> map = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, java.time.LocalDate.now().toString());
            ps.setString(2, TxnStatus.RECONCILED.code());
            ps.setString(3, TxnStatus.CLEARED.code());
            ps.setString(4, TxnStatus.RECONCILING.code());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getLong(1), rs.getLong(2));
                }
            }
        }
        return map;
    }

    // ---------------------------------------------------------------------

    private List<Account> query(String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            return read(ps);
        }
    }

    private List<Account> read(PreparedStatement ps) throws SQLException {
        List<Account> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long parent = rs.getLong(7);
                Long parentId = rs.wasNull() ? null : parent;
                list.add(new Account(
                        rs.getLong(1),
                        rs.getString(2),
                        AccountType.valueOf(rs.getString(3)),
                        rs.getLong(4),
                        rs.getString(5),
                        rs.getInt(6) != 0,
                        parentId));
            }
        }
        return list;
    }
}
