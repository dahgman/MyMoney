package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports a bank download (a QIF or QFX / OFX file of one account's
 * transactions) into an account, matching it against what is already there.
 *
 * For each downloaded transaction:
 *  - If it was imported before (same download id), it is skipped.
 *  - Otherwise, if matching is on, it is matched with an existing transaction
 *    for the same amount, dated within the given number of days, that isn't
 *    reconciled, cleared or being reconciled, and wasn't matched before (if
 *    both have check numbers, they must agree; the closest date wins). The
 *    match keeps its payee, gets the downloaded description in its memo, and
 *    is marked Cleared.
 *  - If nothing matches, it is added as a new, Cleared transaction.
 * Either way it is marked as downloaded (matched or new), which the ledger
 * shows in its download column.
 *
 * All of it is saved together, or none of it.
 */
final class DownloadImporter {

    /** One downloaded transaction. id identifies it across imports (OFX FITID, or made up for QIF). */
    record Download(LocalDate date, long amountCents, String payee, String memo, String checkNumber, String id) {

        /** The bank's description: payee and memo, as one line. */
        String description() {
            String p = payee == null ? "" : payee.trim();
            String m = memo == null ? "" : memo.trim();
            return p.isEmpty() ? m : m.isEmpty() || p.equalsIgnoreCase(m) ? p : p + " " + m;
        }
    }

    /** What an import did. */
    static final class Result {
        int matched;
        int added;
        int duplicates;
        final List<String> warnings = new ArrayList<>();
    }

    static final String MATCHED = "M";
    static final String NEW = "N";

    private final Connection conn;
    private final TransactionRepository transactions;

    DownloadImporter(Database db) {
        this.conn = db.getConnection();
        this.transactions = new TransactionRepository(db);
    }

    /**
     * The transactions in a bank QIF file. QIF has no transaction ids, so each
     * gets one made from its date, amount, payee and number (and how many
     * identical ones came before it in the file, so two $5 coffees on one day
     * are both kept, and both recognized next time).
     */
    static List<Download> fromQif(QifParser.QifFile qif) {
        List<Download> list = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (QifParser.Block b : qif.blocks) {
            for (QifParser.QifTransaction t : b.transactions) {
                String base = "QIF:" + t.date + "|" + t.amountCents + "|" + norm(t.payee) + "|" + norm(t.number);
                int n = seen.merge(base, 1, Integer::sum);
                list.add(new Download(t.date, t.amountCents, t.payee, t.memo, t.number, base + "#" + n));
            }
        }
        return list;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** An existing transaction that a download could match. */
    private static final class Candidate {
        long id;
        LocalDate date;
        String checkNumber;
        String memo;
        long amountCents;
    }

    Result run(Account account, List<Download> downloads, boolean match, int days) throws SQLException {
        Result result = new Result();
        List<Download> sorted = new ArrayList<>(downloads);
        sorted.sort(Comparator.comparing(Download::date));
        conn.setAutoCommit(false);
        try {
            Set<String> imported = importedIds(account.id());
            List<Candidate> candidates = match ? candidates(account.id()) : new ArrayList<>();
            TransactionRepository.Target category = transactions.target(account.defaultCategory());
            for (Download d : sorted) {
                if (d.id() != null && !imported.add(d.id())) {
                    result.duplicates++;
                    continue;
                }
                Candidate c = match ? bestMatch(candidates, d, days) : null;
                if (c != null) {
                    candidates.remove(c);
                    markMatched(c, d);
                    result.matched++;
                } else {
                    add(account.id(), d, category);
                    result.added++;
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        return result;
    }

    private Set<String> importedIds(long accountId) throws SQLException {
        Set<String> ids = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT download_id FROM txn WHERE account_id = ? AND download_id IS NOT NULL")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
        }
        return ids;
    }

    /** Transactions a download may match: not reconciled, cleared or reconciling, and not downloaded. */
    private List<Candidate> candidates(long accountId) throws SQLException {
        List<Candidate> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT t.id, t.txn_date, t.check_number, t.memo,
                       (SELECT COALESCE(SUM(amount_cents), 0) FROM split WHERE txn_id = t.id)
                FROM txn t
                WHERE t.account_id = ? AND t.status NOT IN (?, ?, ?) AND t.download_status IS NULL
                ORDER BY t.id
                """)) {
            ps.setLong(1, accountId);
            ps.setString(2, TxnStatus.RECONCILED.code());
            ps.setString(3, TxnStatus.CLEARED.code());
            ps.setString(4, TxnStatus.RECONCILING.code());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Candidate c = new Candidate();
                    c.id = rs.getLong(1);
                    c.date = LocalDate.parse(rs.getString(2));
                    c.checkNumber = rs.getString(3);
                    c.memo = rs.getString(4);
                    c.amountCents = rs.getLong(5);
                    list.add(c);
                }
            }
        }
        return list;
    }

    /** The same-amount candidate closest in date (within days), or null. */
    private static Candidate bestMatch(List<Candidate> candidates, Download d, int days) {
        Candidate best = null;
        long bestGap = Long.MAX_VALUE;
        for (Candidate c : candidates) {
            if (c.amountCents != d.amountCents()) {
                continue;
            }
            long gap = Math.abs(ChronoUnit.DAYS.between(c.date, d.date()));
            if (gap > days) {
                continue;
            }
            String a = norm(c.checkNumber);
            String b = norm(d.checkNumber());
            if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
                continue;                                     // different checks
            }
            if (gap < bestGap) {                              // ties: the earlier-entered one (list is in id order)
                best = c;
                bestGap = gap;
            }
        }
        return best;
    }

    /** Keeps the payee; puts the bank's description in the memo; marks it Cleared and matched. */
    private void markMatched(Candidate c, Download d) throws SQLException {
        String description = d.description();
        String memo = c.memo == null ? "" : c.memo.trim();
        if (memo.isEmpty()) {
            memo = description;
        } else if (!description.isEmpty() && !memo.toLowerCase().contains(description.toLowerCase())) {
            memo = memo + " - " + description;                // don't lose a memo typed by hand
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET status = ?, memo = ?,"
                + " download_status = ?, download_id = ? WHERE id = ?")) {
            ps.setString(1, TxnStatus.CLEARED.code());
            TransactionRepository.setText(ps, 2, memo);
            ps.setString(3, MATCHED);
            TransactionRepository.setText(ps, 4, d.id());
            ps.setLong(5, c.id);
            ps.executeUpdate();
        }
    }

    /** A new, Cleared transaction, in the account's default category (if it has one). */
    private void add(long accountId, Download d, TransactionRepository.Target category) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO txn (account_id, txn_date, payee_id,"
                + " check_number, memo, status, download_status, download_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, accountId);
            ps.setString(2, d.date().toString());
            TransactionRepository.setId(ps, 3, transactions.payeeId(d.payee()));
            TransactionRepository.setText(ps, 4, d.checkNumber());
            TransactionRepository.setText(ps, 5, d.memo());
            ps.setString(6, TxnStatus.CLEARED.code());
            ps.setString(7, NEW);
            TransactionRepository.setText(ps, 8, d.id());
            ps.executeUpdate();
        }
        transactions.insertSplit(transactions.lastInsertId(), d.amountCents(), category, null, null);
    }
}
