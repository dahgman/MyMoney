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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The steps of importing a bank download (a QIF or QFX / OFX file of one
 * account's transactions). Nothing happens automatically: the review window
 * (DownloadReviewWindow) shows each downloaded transaction with its possible
 * matches, and the user accepts a match or adds it as new.
 *
 *  - Transactions imported before (same download id) are left out.
 *  - A possible match is an existing transaction for the same amount, dated
 *    within the allowed number of days, that wasn't matched to a download
 *    before (if both have check numbers, they must agree). Open ones come
 *    first, likeliest first: the payee most like the bank's description,
 *    then the closest date. Ones already cleared (or reconciled, or being
 *    reconciled) come after: the download may be one entered and cleared by
 *    hand, and listing them keeps it from being added twice.
 *  - Accepting a match keeps its payee, puts the bank's description in its
 *    memo, and marks it Cleared. Linking to one already cleared does the
 *    same but leaves its status alone (a reconciled one stays reconciled).
 *  - Adding as new creates a Cleared transaction in the account's default
 *    category, under the payee the user chose. If that isn't the bank's name,
 *    the choice is remembered (payee_rename) and offered the next time that
 *    bank name is downloaded, in any account.
 * Either way it is marked as downloaded (matched or new), which the ledger
 * shows in its download column.
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

    /** An existing transaction a download could match. */
    record Candidate(long id, LocalDate date, String payee, String memo, String checkNumber, long amountCents,
                     TxnStatus status, long daysApart, int payeeWords) {

        /** Cleared, reconciled or being reconciled: it can only be linked, not matched. */
        boolean alreadyCleared() {
            return status.isCleared();
        }
    }

    static final String MATCHED = "M";
    static final String NEW = "N";

    private final Connection conn;
    private final TransactionRepository transactions;
    private final PayeeRenameRepository renames;

    DownloadImporter(Database db) {
        this.conn = db.getConnection();
        this.transactions = new TransactionRepository(db);
        this.renames = new PayeeRenameRepository(db);
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
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** The downloads not imported into this account before, in date order. */
    List<Download> notYetImported(long accountId, List<Download> downloads) throws SQLException {
        Set<String> imported = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT download_id FROM txn WHERE account_id = ? AND download_id IS NOT NULL")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    imported.add(rs.getString(1));
                }
            }
        }
        List<Download> fresh = new ArrayList<>();
        for (Download d : downloads) {
            if (d.id() == null || imported.add(d.id())) {   // add(): also drops repeats within the file
                fresh.add(d);
            }
        }
        fresh.sort(Comparator.comparing(Download::date));
        return fresh;
    }

    /** The existing transactions this download could match, likeliest first. */
    List<Candidate> candidates(long accountId, Download d, int days) throws SQLException {
        List<Candidate> list = new ArrayList<>();
        String remembered = rememberedPayee(d);          // a payee chosen for this bank name before also counts
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT t.id, t.txn_date, p.name, t.memo, t.check_number, t.status,
                       (SELECT COALESCE(SUM(amount_cents), 0) FROM split WHERE txn_id = t.id) AS amount
                FROM txn t LEFT JOIN payee p ON p.id = t.payee_id
                WHERE t.account_id = ? AND t.download_status IS NULL
                  AND t.txn_date BETWEEN ? AND ? AND amount = ?
                ORDER BY t.id
                """)) {
            ps.setLong(1, accountId);
            ps.setString(2, d.date().minusDays(days).toString());
            ps.setString(3, d.date().plusDays(days).toString());
            ps.setLong(4, d.amountCents());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String check = rs.getString(5);
                    String a = norm(check);
                    String b = norm(d.checkNumber());
                    if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
                        continue;                             // different checks
                    }
                    LocalDate date = LocalDate.parse(rs.getString(2));
                    String payee = rs.getString(3);
                    list.add(new Candidate(rs.getLong(1), date, payee, rs.getString(4), check,
                            rs.getLong(7), TxnStatus.fromCode(rs.getString(6)),
                            Math.abs(ChronoUnit.DAYS.between(date, d.date())),
                            sharedWords(payee, d.description() + " " + text(remembered))));
                }
            }
        }
        // Open ones first; then likeliest first: payee most like the bank's description,
        // then closest date, then entered first.
        list.sort(Comparator.comparing(Candidate::alreadyCleared)
                .thenComparing(Comparator.comparingInt(Candidate::payeeWords).reversed())
                .thenComparingLong(Candidate::daysApart)
                .thenComparingLong(Candidate::id));
        return list;
    }

    /**
     * How alike a payee and the bank's description are: how many words they
     * share (ignoring case, punctuation and words under 3 letters), so
     * "Electric Co" and "ELECTRIC CO ONLINE PMT" share 1 ("electric").
     */
    static int sharedWords(String payee, String description) {
        Set<String> a = words(payee);
        a.retainAll(words(description));
        return a.size();
    }

    private static Set<String> words(String s) {
        Set<String> set = new HashSet<>();
        if (s != null) {
            for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (w.length() >= 3) {
                    set.add(w);
                }
            }
        }
        return set;
    }

    /**
     * Accepts a match: keeps its payee; puts the bank's description in the memo;
     * marks it matched, and Cleared (one already cleared or reconciled keeps
     * its status: it is just linked to the download).
     */
    void accept(Download d, Candidate c) throws SQLException {
        String description = d.description();
        String memo = c.memo() == null ? "" : c.memo().trim();
        if (memo.isEmpty()) {
            memo = description;
        } else if (!description.isEmpty()
                && !memo.toLowerCase(Locale.ROOT).contains(description.toLowerCase(Locale.ROOT))) {
            memo = memo + " - " + description;                // don't lose a memo typed by hand
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET status = ?, memo = ?,"
                + " download_status = ?, download_id = ? WHERE id = ?")) {
            ps.setString(1, c.alreadyCleared() ? c.status().code() : TxnStatus.CLEARED.code());
            TransactionRepository.setText(ps, 2, memo);
            ps.setString(3, MATCHED);
            TransactionRepository.setText(ps, 4, d.id());
            ps.setLong(5, c.id());
            ps.executeUpdate();
        }
    }

    // ----- Remembered payee names --------------------------------------------

    /** The payee chosen for this download's bank name before, or null. */
    String rememberedPayee(Download d) throws SQLException {
        return renames.find(d.payee());
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    /** Payee names already used, for choosing a new transaction's payee. */
    List<String> payeeNames() throws SQLException {
        return transactions.findPayeeNames();
    }

    /**
     * Adds a download as a new, Cleared transaction in the account's default
     * category, under the payee the user chose (blank: no payee). If that
     * isn't the bank's name, the bank's description goes in the memo (as for
     * a match), so it isn't lost. Returns its id.
     */
    long addNew(Account account, Download d, String payee) throws SQLException {
        conn.setAutoCommit(false);
        try {
            TransactionRepository.Target category = transactions.target(account.defaultCategory());
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO txn (account_id, txn_date, payee_id,"
                    + " check_number, memo, status, download_status, download_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, account.id());
                ps.setString(2, d.date().toString());
                TransactionRepository.setId(ps, 3, transactions.payeeId(payee));
                TransactionRepository.setText(ps, 4, d.checkNumber());
                boolean renamed = !norm(payee).equals(norm(d.payee()));
                TransactionRepository.setText(ps, 5, renamed ? d.description() : d.memo());
                ps.setString(6, TxnStatus.CLEARED.code());
                ps.setString(7, NEW);
                TransactionRepository.setText(ps, 8, d.id());
                ps.executeUpdate();
            }
            long id = transactions.lastInsertId();
            transactions.insertSplit(id, d.amountCents(), category, null, null);
            renames.remember(d.payee(), payee);
            conn.commit();
            return id;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }
}
