package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Payee names chosen for bank names during imports (the payee_rename table),
 * so the next download from the same merchant is offered the same payee.
 * Account > Manage Payee Renames lists, edits and deletes them.
 */
final class PayeeRenameRepository {

    /** One remembered rename: the simplified bank name, and the payee chosen for it. */
    record Rename(long id, String bankKey, String payee) {
    }

    private final Connection conn;

    PayeeRenameRepository(Database db) {
        this.conn = db.getConnection();
    }

    /**
     * A bank's payee name, simplified so the same merchant matches from one
     * download to the next: lower case, without digits or punctuation (which
     * are often store numbers, phone numbers or dates), single-spaced.
     * "NETFLIX.COM 866-579-7172 CA" -> "netflix com ca".
     */
    static String bankKey(String bankName) {
        return bankName == null ? ""
                : bankName.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim();
    }

    /** The payee chosen for this bank name before, or null. */
    String find(String bankName) throws SQLException {
        String key = bankKey(bankName);
        if (key.isEmpty()) {
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT payee FROM payee_rename WHERE bank_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Remembers the payee chosen for a bank name. Keeping the bank's own name
     * (or no payee) forgets any earlier choice.
     */
    void remember(String bankName, String payee) throws SQLException {
        String key = bankKey(bankName);
        if (key.isEmpty()) {
            return;
        }
        if (payee == null || payee.isBlank() || payee.trim().equalsIgnoreCase(bankName.trim())) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payee_rename WHERE bank_key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO payee_rename (bank_key, payee) VALUES (?, ?)
                ON CONFLICT(bank_key) DO UPDATE SET payee = excluded.payee
                """)) {
            ps.setString(1, key);
            ps.setString(2, payee.trim());
            ps.executeUpdate();
        }
    }

    /** Every remembered rename, by bank name. */
    List<Rename> findAll() throws SQLException {
        List<Rename> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, bank_key, payee FROM payee_rename ORDER BY bank_key");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Rename(rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
        }
        return list;
    }

    /** Changes the payee a rename gives. */
    void setPayee(long id, String payee) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE payee_rename SET payee = ? WHERE id = ?")) {
            ps.setString(1, payee.trim());
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    void delete(List<Long> ids) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payee_rename WHERE id = ?")) {
            for (long id : ids) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }
}
