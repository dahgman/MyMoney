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
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM account WHERE name = ? COLLATE NOCASE")) {
            ps.setString(1, name);
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

    /** Opening balance plus every split on this account's transactions. */
    public long balanceCents(Account account) throws SQLException {
        String sql = "SELECT COALESCE(SUM(s.amount_cents), 0)"
                + " FROM split s JOIN txn t ON s.txn_id = t.id"
                + " WHERE t.account_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, account.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return account.openingBalanceCents() + rs.getLong(1);
            }
        }
    }

    // ---------------------------------------------------------------------

    private List<Account> query(String sql) throws SQLException {
        List<Account> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
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
