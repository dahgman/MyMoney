package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes a parsed QIF file into the database, all or nothing.
 *
 * - Accounts are matched by name (ignoring case); missing ones are created
 *   as top-level accounts (Edit Account can give them a parent later).
 * - An "Opening Balance" transaction that transfers to its own account
 *   becomes the account's opening balance, if the account is new or has no
 *   opening balance yet. If the same balance is already set, it is skipped.
 * - Categories (including the category list) are created as needed; paths
 *   deeper than two levels are folded into two ("Auto:Fuel - Premium").
 * - Transfers ("[Savings]") are recorded as transfers, creating the other
 *   account if needed. Every transfer ends up with a matching transaction in
 *   the other account: if the file has it (in that account's section), the
 *   two are linked; if not, it is created.
 * - Tags (QIF classes) are created as needed, each with a random color that
 *   no other tag is using. A transaction keeps only its first tag.
 * - A transaction already in the database before this import (same account,
 *   date, amount, payee and number) is skipped as a duplicate.
 */
final class QifImporter {

    /** What happened, for the summary shown afterwards. */
    static final class Summary {
        final List<String> accountsCreated = new ArrayList<>();
        final List<String> tagsCreated = new ArrayList<>();
        int transactions;
        int duplicates;
        int openingBalances;
        int transfersMatched;
        int transfersCreated;
        final Set<String> flattenedCategories = new HashSet<>();
        int investmentSkipped;
        final List<String> warnings = new ArrayList<>();

        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("Imported ").append(transactions).append(" transaction")
              .append(transactions == 1 ? "" : "s").append(".\n");
            if (!accountsCreated.isEmpty()) {
                sb.append("\nNew accounts (").append(accountsCreated.size()).append("):\n");
                for (String name : accountsCreated) {
                    sb.append("   ").append(name).append('\n');
                }
            }
            if (!tagsCreated.isEmpty()) {
                sb.append("\nNew tags (").append(tagsCreated.size()).append("): ")
                  .append(String.join(", ", tagsCreated)).append('\n');
            }
            if (transfersMatched + transfersCreated > 0) {
                sb.append("\nTransfers: ").append(transfersMatched).append(" matched with their other side in the file");
                if (transfersCreated > 0) {
                    sb.append(", ").append(transfersCreated).append(" given a new matching transaction");
                }
                sb.append(".\n");
            }
            if (openingBalances > 0) {
                sb.append("\nOpening balances set: ").append(openingBalances).append('\n');
            }
            if (duplicates > 0) {
                sb.append("\nSkipped ").append(duplicates).append(" transaction")
                  .append(duplicates == 1 ? "" : "s").append(" already in MyMoney (duplicates).\n");
            }
            if (!flattenedCategories.isEmpty()) {
                int n = flattenedCategories.size();
                sb.append("\n").append(n).append(n == 1 ? " category was" : " categories were")
                  .append(" more than two levels deep and shortened,\nlike Auto:Fuel:Premium -> Auto:Fuel - Premium.\n");
            }
            if (investmentSkipped > 0) {
                sb.append("\nSkipped ").append(investmentSkipped).append(" investment transaction")
                  .append(investmentSkipped == 1 ? "" : "s").append(" (not supported yet).\n");
            }
            int shown = 0;
            for (String w : warnings) {
                if (shown++ == 0) {
                    sb.append("\nWarnings:\n");
                }
                if (shown > 10) {
                    sb.append("   ...and ").append(warnings.size() - 10).append(" more\n");
                    break;
                }
                sb.append("   ").append(w).append('\n');
            }
            return sb.toString();
        }
    }

    private final Connection conn;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final TagRepository tags;

    private final Map<String, Long> accountIds = new HashMap<>();      // lower-case name -> id
    private final Map<String, String> accountTypes = new HashMap<>();  // lower-case name -> QIF type
    private final Set<Long> createdAccounts = new HashSet<>();
    private Summary summary;
    private long lastExistingTxnId;

    QifImporter(Database db) {
        this.conn = db.getConnection();
        this.accounts = new AccountRepository(db);
        this.transactions = new TransactionRepository(db);
        this.categories = new CategoryRepository(db);
        this.tags = new TagRepository(db);
    }

    /**
     * @param unnamedAccount account for transactions the file doesn't assign
     *                       to any account (ignored if there are none)
     */
    Summary run(QifParser.QifFile qif, String unnamedAccount) throws SQLException {
        summary = new Summary();
        summary.investmentSkipped = qif.investmentSkipped;
        summary.warnings.addAll(qif.warnings);

        conn.setAutoCommit(false);
        try {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM txn")) {
                rs.next();
                lastExistingTxnId = rs.getLong(1);
            }
            for (Account a : accounts.findAll()) {
                accountIds.put(key(a.name()), a.id());
            }
            for (QifParser.QifAccount a : qif.accounts) {
                if (a.type != null) {
                    accountTypes.putIfAbsent(key(a.name), a.type);
                }
            }

            for (QifParser.QifCategory c : qif.categories) {
                Long id = categories.findOrCreate(flatten(c.name));
                if (id != null && c.income) {
                    categories.setIncome(id, true);
                }
            }
            for (String tag : qif.tags) {
                tagId(tag);
            }
            for (QifParser.QifAccount a : qif.accounts) {
                if (!isInvestment(a.type)) {
                    accountId(a.name, a.type);
                }
            }
            for (QifParser.Block block : qif.blocks) {
                String name = block.account != null ? block.account.name : unnamedAccount;
                if (name == null || name.isBlank()) {
                    if (!block.transactions.isEmpty()) {
                        summary.warnings.add("Skipped " + block.transactions.size()
                                + " transactions that weren't in any account.");
                    }
                    continue;
                }
                long accountId = accountId(name, block.type);
                for (QifParser.QifTransaction t : block.transactions) {
                    importTransaction(accountId, name, t);
                }
            }
            matchTransfers();
            conn.commit();
            return summary;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ---------------------------------------------------------------------

    private void importTransaction(long accountId, String accountName, QifParser.QifTransaction t)
            throws SQLException {
        // "Opening Balance" transferring to its own account.
        boolean selfTransfer = t.splits.isEmpty() && TransactionRepository.isTransfer(t.category)
                && key(transferName(t.category)).equals(key(accountName));
        if (selfTransfer && "opening balance".equalsIgnoreCase(t.payee)) {
            long current = openingBalance(accountId);
            if (createdAccounts.contains(accountId) || current == 0) {
                accounts.setOpeningBalance(accountId, t.amountCents);   // new, or no balance set yet
                summary.openingBalances++;
                return;
            }
            if (current == t.amountCents) {
                summary.duplicates++;                                   // already set by an earlier import
                return;
            }
            // A different opening balance is already set: keep it, and import this as a transaction.
        }

        if (isDuplicate(accountId, t)) {
            summary.duplicates++;
            return;
        }

        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO txn (account_id, txn_date,"
                + " payee_id, check_number, memo, status, tag_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, accountId);
            ps.setString(2, t.date.toString());
            TransactionRepository.setId(ps, 3, transactions.payeeId(t.payee));
            TransactionRepository.setText(ps, 4, t.number);
            TransactionRepository.setText(ps, 5, t.memo);
            ps.setString(6, t.status.code());
            TransactionRepository.setId(ps, 7, tagId(t.tag));
            ps.executeUpdate();
        }
        long txnId = transactions.lastInsertId();

        if (t.splits.isEmpty()) {
            transactions.insertSplit(txnId, t.amountCents,
                    selfTransfer ? TransactionRepository.Target.NONE : target(t.category), null, null);
        } else {
            long total = 0;
            for (QifParser.QifSplit s : t.splits) {
                transactions.insertSplit(txnId, s.amountCents, target(s.category), s.memo, null);
                total += s.amountCents;
            }
            if (total != t.amountCents) {           // keep the transaction's total right
                transactions.insertSplit(txnId, t.amountCents - total, TransactionRepository.Target.NONE,
                        "Difference from QIF split total", null);
                summary.warnings.add("A split transaction on " + t.date + " didn't add up; "
                        + "the difference was added as an uncategorized split.");
            }
        }
        summary.transactions++;
    }

    /**
     * Gives every transfer imported here its other side. A transfer from A to
     * B is matched with an unlinked transfer in B back to A for the opposite
     * amount, within a week of the same date (closest first). If there is
     * none, the matching transaction is created in B.
     */
    private void matchTransfers() throws SQLException {
        List<long[]> transfers = new ArrayList<>();      // split id, txn id, account, other account, amount
        List<String> dates = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT s.id, s.txn_id, t.account_id, s.transfer_account_id, s.amount_cents, t.txn_date
                FROM split s JOIN txn t ON t.id = s.txn_id
                WHERE s.transfer_account_id IS NOT NULL AND s.transfer_txn_id IS NULL AND t.id > ?
                ORDER BY s.id
                """)) {
            ps.setLong(1, lastExistingTxnId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    transfers.add(new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)});
                    dates.add(rs.getString(6));
                }
            }
        }
        for (int i = 0; i < transfers.size(); i++) {
            long[] t = transfers.get(i);
            if (isLinked(t[0])) {
                continue;                                 // matched as the other side of an earlier one
            }
            if (t[2] == t[3]) {
                continue;                                 // transfers to itself have no other side
            }
            long[] match = findMatch(t[1], t[3], t[2], -t[4], dates.get(i));
            if (match != null) {
                transactions.link(t[0], match[1]);       // this split -> the other transaction
                transactions.link(match[0], t[1]);       // the other split -> this transaction
                summary.transfersMatched++;
            } else {
                transactions.createMirror(t[0]);
                summary.transfersCreated++;
            }
        }
    }

    private boolean isLinked(long splitId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT transfer_txn_id IS NOT NULL FROM split WHERE id = ?")) {
            ps.setLong(1, splitId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** {split id, txn id} of an unlinked transfer in account back to otherAccount, or null. */
    private long[] findMatch(long txnId, long account, long otherAccount, long amount, String date)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT s.id, t.id FROM split s JOIN txn t ON t.id = s.txn_id
                WHERE t.account_id = ? AND s.transfer_account_id = ? AND s.amount_cents = ?
                  AND s.transfer_txn_id IS NULL AND t.id <> ?
                  AND ABS(julianday(t.txn_date) - julianday(?)) <= 7
                ORDER BY ABS(julianday(t.txn_date) - julianday(?)), t.id
                LIMIT 1
                """)) {
            ps.setLong(1, account);
            ps.setLong(2, otherAccount);
            ps.setLong(3, amount);
            ps.setLong(4, txnId);
            ps.setString(5, date);
            ps.setString(6, date);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new long[] {rs.getLong(1), rs.getLong(2)} : null;
            }
        }
    }

    private long openingBalance(long accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT opening_balance_cents FROM account WHERE id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** True if an equivalent transaction was in the database before this import. */
    private boolean isDuplicate(long accountId, QifParser.QifTransaction t) throws SQLException {
        String sql = """
            SELECT 1 FROM txn t
            WHERE t.account_id = ? AND t.txn_date = ? AND t.id <= ?
              AND COALESCE(t.check_number, '') = ?
              AND COALESCE((SELECT name FROM payee p WHERE p.id = t.payee_id), '') = ? COLLATE NOCASE
              AND COALESCE((SELECT SUM(amount_cents) FROM split s WHERE s.txn_id = t.id), 0) = ?
            LIMIT 1
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, accountId);
            ps.setString(2, t.date.toString());
            ps.setLong(3, lastExistingTxnId);
            ps.setString(4, t.number == null ? "" : t.number.trim());
            ps.setString(5, t.payee == null ? "" : t.payee.trim());
            ps.setLong(6, t.amountCents);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** A split's category or transfer; transfer accounts are created if missing. */
    private TransactionRepository.Target target(String category) throws SQLException {
        if (category == null) {
            return TransactionRepository.Target.NONE;
        }
        if (TransactionRepository.isTransfer(category)) {
            return new TransactionRepository.Target(null, accountId(transferName(category), null));
        }
        String path = flatten(category);
        return path == null ? TransactionRepository.Target.NONE
                : new TransactionRepository.Target(categories.findOrCreate(path), null);
    }

    /** The id of the named account, creating it (top-level) if it doesn't exist. */
    private long accountId(String name, String qifType) throws SQLException {
        Long id = accountIds.get(key(name));
        if (id != null) {
            return id;
        }
        String type = accountTypes.getOrDefault(key(name), qifType);
        long newId = accounts.insert(name.trim(), accountType(type), 0, null);
        accountIds.put(key(name), newId);
        createdAccounts.add(newId);
        summary.accountsCreated.add(name.trim());
        return newId;
    }

    /** The tag's id, creating it with an unused color if new. Bad names are skipped. */
    private Long tagId(String tag) throws SQLException {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        try {
            String name = TagRepository.normalize(tag);
            Long id = tags.findId(name);
            if (id == null) {
                id = tags.create(name, tags.unusedColor());
                summary.tagsCreated.add(name);
            }
            return id;
        } catch (IllegalArgumentException e) {
            summary.warnings.add("Skipped the tag \"" + tag + "\": " + e.getMessage());
            return null;
        }
    }

    private String flatten(String path) {
        String flat = CategoryRepository.flatten(path);
        if (flat != null && path.split(":").length > 2) {
            summary.flattenedCategories.add(flat.toLowerCase(Locale.ROOT));
        }
        return flat;
    }

    private static String transferName(String transfer) {
        return transfer.substring(1, transfer.length() - 1).trim();
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    static AccountType accountType(String qifType) {
        if (qifType == null) {
            return AccountType.CHECKING;
        }
        return switch (qifType.trim().toLowerCase(Locale.ROOT)) {
            case "cash" -> AccountType.CASH;
            case "ccard" -> AccountType.CREDIT_CARD;
            case "oth a" -> AccountType.OTHER_ASSET;
            case "oth l" -> AccountType.LOAN;
            default -> AccountType.CHECKING;            // "Bank" and anything unknown
        };
    }

    private static boolean isInvestment(String qifType) {
        if (qifType == null) {
            return false;
        }
        String t = qifType.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("invst") || t.equals("port") || t.startsWith("401") || t.equals("mutual");
    }
}
