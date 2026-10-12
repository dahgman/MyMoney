package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Reconciling an account against a bank statement.
 *
 * A reconciliation is started with a statement date and ending balance and
 * stays "unfinished" until Done. While it is open, the transactions chosen
 * for it have the status Reconciling, and remember their old status in
 * txn.pre_reconcile_status so that deselecting puts it back. Done turns
 * every Reconciling transaction into Reconciled. Finish Later just leaves
 * everything as it is, to pick up next time.
 */
final class ReconcileRepository {

    /** A reconciliation: the statement it is checked against. */
    record Reconciliation(long id, long accountId, LocalDate statementDate, long endingBalanceCents) {
    }

    private static final String R = TxnStatus.RECONCILED.code();
    private static final String C = TxnStatus.CLEARED.code();
    private static final String I = TxnStatus.RECONCILING.code();

    private final Connection conn;

    ReconcileRepository(Database db) {
        this.conn = db.getConnection();
    }

    /** The opening balance plus every reconciled transaction: where this statement starts. */
    long beginningBalance(Account account) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(s.amount_cents), 0)"
                + " FROM split s JOIN txn t ON t.id = s.txn_id WHERE t.account_id = ? AND t.status = ?")) {
            ps.setLong(1, account.id());
            ps.setString(2, R);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return account.openingBalanceCents() + rs.getLong(1);
            }
        }
    }

    /** The account's unfinished reconciliation, or null. */
    Reconciliation unfinished(long accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, statement_date, ending_balance_cents"
                + " FROM reconciliation WHERE account_id = ? AND completed_at IS NULL ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Reconciliation(rs.getLong(1), accountId,
                        LocalDate.parse(rs.getString(2)), rs.getLong(3)) : null;
            }
        }
    }

    /** The statement date of the last finished reconciliation, or null if there is none. */
    LocalDate lastStatementDate(long accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(statement_date) FROM reconciliation"
                + " WHERE account_id = ? AND completed_at IS NOT NULL")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                String d = rs.next() ? rs.getString(1) : null;
                return d == null ? null : LocalDate.parse(d);
            }
        }
    }

    /**
     * Starts a reconciliation, or changes the statement of the unfinished one.
     * A new reconciliation starts with every Cleared transaction selected.
     * With asOf, every unreconciled transaction dated on or before the
     * statement date is selected as well.
     */
    Reconciliation start(Account account, LocalDate statementDate, long endingBalanceCents, boolean asOf)
            throws SQLException {
        conn.setAutoCommit(false);
        try {
            Reconciliation r = unfinished(account.id());
            if (r == null) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO reconciliation"
                        + " (account_id, statement_date, ending_balance_cents) VALUES (?, ?, ?)")) {
                    ps.setLong(1, account.id());
                    ps.setString(2, statementDate.toString());
                    ps.setLong(3, endingBalanceCents);
                    ps.executeUpdate();
                }
                select("account_id = ? AND status = '" + C + "'", account.id(), null);
            } else {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE reconciliation"
                        + " SET statement_date = ?, ending_balance_cents = ? WHERE id = ?")) {
                    ps.setString(1, statementDate.toString());
                    ps.setLong(2, endingBalanceCents);
                    ps.setLong(3, r.id());
                    ps.executeUpdate();
                }
            }
            if (asOf) {
                select("account_id = ? AND txn_date <= ?", account.id(), statementDate.toString());
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        return unfinished(account.id());
    }

    /** Makes the given transactions Reconciling (remembering their status). Reconciled ones are left alone. */
    void select(List<Long> txnIds) throws SQLException {
        for (long id : txnIds) {
            select("id = ?", id, null);
        }
    }

    /** Puts the given Reconciling transactions back to the status they had. */
    void deselect(List<Long> txnIds) throws SQLException {
        for (long id : txnIds) {
            deselect("id = ?", id);
        }
    }

    /** Deselects every transaction in the account's reconciliation. */
    void deselectAll(long accountId) throws SQLException {
        deselect("account_id = ?", accountId);
    }

    private void select(String where, long id, String date) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET pre_reconcile_status = status,"
                + " status = '" + I + "' WHERE " + where + " AND status NOT IN ('" + R + "', '" + I + "')")) {
            ps.setLong(1, id);
            if (date != null) {
                ps.setString(2, date);
            }
            ps.executeUpdate();
        }
    }

    private void deselect(String where, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET status = COALESCE("
                + "pre_reconcile_status, 'U'), pre_reconcile_status = NULL WHERE " + where
                + " AND status = '" + I + "'")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Done: every Reconciling transaction becomes Reconciled, and the reconciliation is finished. */
    void finish(Reconciliation r, long beginningBalanceCents) throws SQLException {
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET status = '" + R + "',"
                    + " pre_reconcile_status = NULL WHERE account_id = ? AND status = '" + I + "'")) {
                ps.setLong(1, r.accountId());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE reconciliation"
                    + " SET beginning_balance_cents = ?, completed_at = ? WHERE id = ?")) {
                ps.setLong(1, beginningBalanceCents);
                ps.setString(2, Instant.now().toString());
                ps.setLong(3, r.id());
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
}
