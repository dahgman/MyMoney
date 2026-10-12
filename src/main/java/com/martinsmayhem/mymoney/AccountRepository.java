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
            "id, name, type, opening_balance_cents, currency, closed, parent_id,"
            + " balance_adjustment_cents, default_category";

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

    /** Everything about an account that Manage Accounts shows and edits. */
    public record Details(long id, String name, AccountType type, Long parentId, String bankName,
                          java.time.LocalDate startDate, long openingBalanceCents, long balanceAdjustmentCents,
                          String accountNumber, String routingNumber, String defaultCategory,
                          String comments, String website) {
    }

    public Details findDetails(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, name, type, parent_id, bank_name,"
                + " start_date, opening_balance_cents, balance_adjustment_cents, account_number, routing_number,"
                + " default_category, comments, website FROM account WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long parent = rs.getLong(4);
                Long parentId = rs.wasNull() ? null : parent;
                String start = rs.getString(6);
                return new Details(rs.getLong(1), rs.getString(2), AccountType.valueOf(rs.getString(3)),
                        parentId, rs.getString(5), start == null ? null : java.time.LocalDate.parse(start),
                        rs.getLong(7), rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11),
                        rs.getString(12), rs.getString(13));
            }
        }
    }

    /** Saves everything Manage Accounts edits. Blank text is saved as nothing (NULL). */
    public void saveDetails(Details d) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE account SET name = ?, type = ?, parent_id = ?,"
                + " bank_name = ?, start_date = ?, opening_balance_cents = ?, balance_adjustment_cents = ?,"
                + " account_number = ?, routing_number = ?, default_category = ?, comments = ?, website = ?"
                + " WHERE id = ?")) {
            ps.setString(1, d.name());
            ps.setString(2, d.type().name());
            TransactionRepository.setId(ps, 3, d.parentId());
            TransactionRepository.setText(ps, 4, d.bankName());
            TransactionRepository.setText(ps, 5, d.startDate() == null ? null : d.startDate().toString());
            ps.setLong(6, d.openingBalanceCents());
            ps.setLong(7, d.balanceAdjustmentCents());
            TransactionRepository.setText(ps, 8, d.accountNumber());
            TransactionRepository.setText(ps, 9, d.routingNumber());
            TransactionRepository.setText(ps, 10, d.defaultCategory());
            TransactionRepository.setText(ps, 11, d.comments());
            TransactionRepository.setText(ps, 12, d.website());
            ps.setLong(13, d.id());
            ps.executeUpdate();
        }
    }

    /**
     * The account an imported file's account number (and routing number, if
     * known) most likely belongs to, or null if none clearly does. Only the
     * digits are compared. An exact account number wins; failing that, the
     * last four digits (banks often send or show them masked, like XXXX1234),
     * but only if exactly one account fits. A matching routing number breaks ties.
     */
    public Account suggestForImport(String accountNumber, String routingNumber) throws SQLException {
        String acct = digits(accountNumber);
        if (acct.isEmpty()) {
            return null;
        }
        String routing = digits(routingNumber);
        java.util.Map<Long, Account> byId = new java.util.HashMap<>();
        for (Account a : findAll()) {
            byId.put(a.id(), a);
        }
        Account best = null;
        int bestScore = 0;
        boolean tie = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, account_number, routing_number FROM account WHERE account_number IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String mine = digits(rs.getString(2));
                if (mine.isEmpty()) {
                    continue;
                }
                int score;
                if (mine.equals(acct)) {
                    score = 4;
                } else if (mine.length() >= 4 && acct.length() >= 4
                        && mine.substring(mine.length() - 4).equals(acct.substring(acct.length() - 4))) {
                    score = 2;
                } else {
                    continue;
                }
                if (!routing.isEmpty() && routing.equals(digits(rs.getString(3)))) {
                    score++;
                }
                if (score > bestScore) {
                    best = byId.get(rs.getLong(1));
                    bestScore = score;
                    tie = false;
                } else if (score == bestScore) {
                    tie = true;
                }
            }
        }
        return tie ? null : best;
    }

    /** Saves just the account and routing numbers (remembered from an import). */
    public void setNumbers(long id, String accountNumber, String routingNumber) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE account SET account_number = ?, routing_number = COALESCE(?, routing_number) WHERE id = ?")) {
            TransactionRepository.setText(ps, 1, accountNumber);
            TransactionRepository.setText(ps, 2, routingNumber);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
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
                long opening = account.startingBalanceCents();
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
        String sql = "SELECT a.id, a.opening_balance_cents + a.balance_adjustment_cents + COALESCE(SUM(CASE"
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
                        parentId,
                        rs.getLong(8),
                        rs.getString(9)));
            }
        }
        return list;
    }
}
