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

    /** The date a transaction counts on for a statement: when the bank posted it, if known. */
    private static final String DAY = "COALESCE(bank_date, txn_date)";

    private final Connection conn;

    ReconcileRepository(Database db) {
        this.conn = db.getConnection();
    }

    /** The opening balance (with its adjustment) plus every reconciled transaction: where this statement starts. */
    long beginningBalance(Account account) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(s.amount_cents), 0)"
                + " FROM split s JOIN txn t ON t.id = s.txn_id WHERE t.account_id = ? AND t.status = ?")) {
            ps.setLong(1, account.id());
            ps.setString(2, R);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return account.startingBalanceCents() + rs.getLong(1);
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
     * Where the account was last reconciled to: the later of the last
     * finished reconciliation's statement date and the latest reconciled
     * transaction's date (reconciled some other way, like in Quicken before
     * it was imported, or by hand); null if it never has been.
     */
    LocalDate lastReconciledDate(long accountId) throws SQLException {
        LocalDate statement = lastStatementDate(accountId);
        LocalDate latest = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(" + DAY + ") FROM txn"
                + " WHERE account_id = ? AND status = ?")) {
            ps.setLong(1, accountId);
            ps.setString(2, R);
            try (ResultSet rs = ps.executeQuery()) {
                String d = rs.next() ? rs.getString(1) : null;
                latest = d == null ? null : LocalDate.parse(d);
            }
        }
        return statement == null ? latest : latest == null || statement.isAfter(latest) ? statement : latest;
    }

    /** The first day of the next statement's period: the day after lastReconciledDate (null: the beginning). */
    LocalDate periodStart(long accountId) throws SQLException {
        LocalDate last = lastReconciledDate(accountId);
        return last == null ? null : last.plusDays(1);
    }

    /**
     * The statement's first day, as the start window shows it: periodStart,
     * or for an account never reconciled, its first transaction's date
     * (null if it has none).
     */
    LocalDate statementStart(long accountId) throws SQLException {
        LocalDate start = periodStart(accountId);
        if (start != null) {
            return start;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT MIN(" + DAY + ") FROM txn WHERE account_id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                String d = rs.next() ? rs.getString(1) : null;
                return d == null ? null : LocalDate.parse(d);
            }
        }
    }

    /**
     * Starts a reconciliation, or changes the statement of the unfinished one.
     *
     * Only the statement's own period is selected: transactions from the day
     * after the account was last reconciled (see periodStart; the beginning,
     * for the first one) through this statement's date, each by the date the
     * bank posted it (its Bank Date, or else its date). A new reconciliation
     * selects the Cleared ones in it; with asOf, every unreconciled one in it.
     * Anything after the statement date keeps its status (and when a
     * statement's date is moved earlier, ones now after it go back to the
     * status they had).
     */
    Reconciliation start(Account account, LocalDate statementDate, long endingBalanceCents, boolean asOf)
            throws SQLException {
        return start(account, periodStart(account.id()), statementDate, endingBalanceCents, asOf);
    }

    /**
     * As start, with the statement's period starting on periodStart (as
     * chosen in the start window; null: from the first transaction) instead
     * of the day after the last reconciliation. It only decides what's
     * selected for you: anything selected already stays selected.
     */
    Reconciliation start(Account account, LocalDate periodStart, LocalDate statementDate, long endingBalanceCents,
                         boolean asOf) throws SQLException {
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
                selectPeriod(account.id(), periodStart, statementDate, true);
            } else {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE reconciliation"
                        + " SET statement_date = ?, ending_balance_cents = ? WHERE id = ?")) {
                    ps.setString(1, statementDate.toString());
                    ps.setLong(2, endingBalanceCents);
                    ps.setLong(3, r.id());
                    ps.executeUpdate();
                }
                deselectAfter(account.id(), statementDate);    // not this statement's any more
            }
            if (asOf) {
                selectPeriod(account.id(), periodStart, statementDate, false);
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
            select("id = ?", id);
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

    private void select(String where, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET pre_reconcile_status = status,"
                + " status = '" + I + "' WHERE " + where + " AND status NOT IN ('" + R + "', '" + I + "')")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * Selects the account's unreconciled transactions from (null: the
     * beginning) through to, by bank date; only the Cleared ones if clearedOnly.
     */
    private void selectPeriod(long accountId, LocalDate from, LocalDate to, boolean clearedOnly)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET pre_reconcile_status = status,"
                + " status = '" + I + "' WHERE account_id = ? AND " + DAY + " BETWEEN ? AND ?"
                + (clearedOnly ? " AND status = '" + C + "'" : " AND status NOT IN ('" + R + "', '" + I + "')"))) {
            ps.setLong(1, accountId);
            ps.setString(2, from == null ? "0000-01-01" : from.toString());
            ps.setString(3, to.toString());
            ps.executeUpdate();
        }
    }

    /** Puts back the status of the account's Reconciling transactions after date (by bank date). */
    private void deselectAfter(long accountId, LocalDate date) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET status = COALESCE("
                + "pre_reconcile_status, 'U'), pre_reconcile_status = NULL"
                + " WHERE account_id = ? AND " + DAY + " > ? AND status = '" + I + "'")) {
            ps.setLong(1, accountId);
            ps.setString(2, date.toString());
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
