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
 * For now every transaction has exactly one split, which holds the
 * amount and the category.
 */
public class TransactionRepository {

    private final Connection conn;
    private final CategoryRepository categories;

    public TransactionRepository(Database db) {
        this.conn = db.getConnection();
        this.categories = new CategoryRepository(db);
    }

    /** An account's transactions, oldest first (entry order breaks ties). */
    public List<LedgerEntry> findByAccount(long accountId) throws SQLException {
        String sql = """
            SELECT t.id, t.txn_date, t.check_number, p.name, t.description, t.memo, t.status,
                   COALESCE((SELECT SUM(s.amount_cents) FROM split s WHERE s.txn_id = t.id), 0),
                   (SELECT CASE WHEN cp.id IS NULL THEN c.name ELSE cp.name || ':' || c.name END
                      FROM split s
                      JOIN category c ON c.id = s.category_id
                      LEFT JOIN category cp ON cp.id = c.parent_id
                     WHERE s.txn_id = t.id
                     LIMIT 1)
            FROM txn t
            LEFT JOIN payee p ON p.id = t.payee_id
            WHERE t.account_id = ?
            ORDER BY t.txn_date, t.id
            """;
        List<LedgerEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accountId);
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
                    list.add(e);
                }
            }
        }
        return list;
    }

    /** Saves a new transaction (and its one split) and sets entry.id. */
    public void insert(long accountId, LedgerEntry entry) throws SQLException {
        conn.setAutoCommit(false);
        try {
            String sql = "INSERT INTO txn (account_id, txn_date, payee_id, check_number,"
                    + " memo, status, description) VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, accountId);
                ps.setString(2, entry.date.toString());     // ISO: YYYY-MM-DD
                setId(ps, 3, payeeId(entry.payee));
                setText(ps, 4, entry.checkNumber);
                setText(ps, 5, entry.memo);
                ps.setString(6, entry.status.code());
                setText(ps, 7, entry.description);
                ps.executeUpdate();
            }
            long id = lastInsertId();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO split (txn_id, amount_cents, category_id) VALUES (?, ?, ?)")) {
                ps.setLong(1, id);
                ps.setLong(2, entry.amountCents);
                setId(ps, 3, categories.findOrCreate(entry.category));
                ps.executeUpdate();
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

    /** Saves changes to an existing transaction. */
    public void update(LedgerEntry entry) throws SQLException {
        conn.setAutoCommit(false);
        try {
            if (splitCount(entry.id) != 1) {
                throw new SQLException("This transaction has more than one split"
                        + " and can't be edited in the ledger yet.");
            }
            String sql = "UPDATE txn SET txn_date = ?, payee_id = ?, check_number = ?,"
                    + " memo = ?, status = ?, description = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, entry.date.toString());
                setId(ps, 2, payeeId(entry.payee));
                setText(ps, 3, entry.checkNumber);
                setText(ps, 4, entry.memo);
                ps.setString(5, entry.status.code());
                setText(ps, 6, entry.description);
                ps.setLong(7, entry.id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE split SET amount_cents = ?, category_id = ? WHERE txn_id = ?")) {
                ps.setLong(1, entry.amountCents);
                setId(ps, 2, categories.findOrCreate(entry.category));
                ps.setLong(3, entry.id);
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

    /** Deletes a transaction; its splits go with it (ON DELETE CASCADE). */
    public void delete(long txnId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM txn WHERE id = ?")) {
            ps.setLong(1, txnId);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------------

    /** Finds a payee by name (ignoring case), creating it if needed. Null for blank. */
    private Long payeeId(String name) throws SQLException {
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

    private long lastInsertId() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void setText(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value.trim());
        }
    }

    private static void setId(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }
}
