package com.martinsmayhem.mymoney;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits each loan payment into interest and principal.
 *
 * A payment is entered as just the amount paid. Its interest is recorded in
 * the payment itself, on the loan's side, as a split to the loan's interest
 * category (split.loan_interest = 1); the rest is principal. So the payment
 * lowers what's owed by its principal only, and the interest counts as an
 * expense. The ledger shows the payment's full amount, with its interest and
 * principal in their own columns.
 *
 * Every payment is worked out again, oldest first, from the loan's initial
 * balance, whenever its payments or terms change, so editing, adding or
 * deleting one payment corrects all those after it. A payment is any
 * transaction that lowers what's owed: a transfer from another account, or
 * one entered on the loan itself. Interest:
 * <ul>
 * <li>monthly: rate / 12 on what's owed, on the first payment in each month
 *     (more payments that month are all principal);</li>
 * <li>daily: rate / 365 on what's owed for each day since the loan was made
 *     or last paid, added up and charged at the next payment.</li>
 * <li>daily on the due dates: rate / 365 on what's owed for each day of the
 *     lender's schedule, the nth payment paying the nth period (from one due
 *     date to the next), whenever it's actually paid.</li>
 * </ul>
 * A loan charged on its due dates is shown as such lenders show it: the
 * balance is everything left to pay. All the interest its schedule charges
 * (for the payments made, and the rest of the schedule at the regular
 * payment) is one calculated "Scheduled interest" transaction on the day the
 * loan was made; each payment then lowers the balance by its full amount, its
 * interest only shown beside it (txn.loan_interest_cents).
 *
 * A reconciled payment's interest is left as it is.
 */
final class LoanInterest {

    private final Connection conn;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private int changes;

    LoanInterest(Database db) {
        this.conn = db.getConnection();
        this.accounts = new AccountRepository(db);
        this.categories = new CategoryRepository(db);
    }

    /** How many changes recalculating has made so far (to tell whether a recalculation changed anything). */
    int changes() {
        return changes;
    }

    /** Recalculates every loan. For the caller's open database transaction, if any. */
    void recalculateAll() throws SQLException {
        List<Long> loans = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM account WHERE type = 'LOAN'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    loans.add(rs.getLong(1));
                }
            }
        }
        for (long id : loans) {
            recalculate(id);
        }
        // Interest left in an account that is no longer a loan goes.
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM txn WHERE id IN (SELECT s.txn_id FROM split s"
                + " WHERE s.loan_interest = 2) AND status <> 'R'"
                + " AND account_id NOT IN (SELECT id FROM account WHERE type = 'LOAN')")) {
            changes += ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM split WHERE loan_interest = 1"
                + " AND txn_id IN (SELECT t.id FROM txn t JOIN account a ON a.id = t.account_id"
                + " WHERE a.type <> 'LOAN' AND t.status <> 'R')")) {
            changes += ps.executeUpdate();
        }
    }

    /** Recalculates one loan's interest (does nothing if the account isn't a loan). */
    void recalculate(long accountId) throws SQLException {
        Loan loan = accounts.findLoan(accountId);
        if (loan == null) {
            return;
        }
        convertSplitPayments(accountId);

        long balance;
        LocalDate made;
        try (PreparedStatement ps = conn.prepareStatement("SELECT opening_balance_cents + balance_adjustment_cents,"
                + " start_date FROM account WHERE id = ?")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                balance = rs.getLong(1);
                made = rs.getString(2) == null ? null : LocalDate.parse(rs.getString(2));
            }
        }

        // The loan's transactions, oldest first; on one day, new borrowing before payments.
        List<Move> moves = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT t.id, t.txn_date, t.status,
                       COALESCE(SUM(CASE WHEN s.loan_interest = 0 THEN s.amount_cents END), 0),
                       COALESCE(-SUM(CASE WHEN s.loan_interest = 1 THEN s.amount_cents END), 0),
                       COUNT(CASE WHEN s.loan_interest = 1 THEN 1 END),
                       MAX(CASE WHEN s.loan_interest = 1 THEN s.category_id END),
                       t.loan_interest_cents
                FROM txn t LEFT JOIN split s ON s.txn_id = t.id
                WHERE t.account_id = ?
                  AND NOT EXISTS (SELECT 1 FROM split WHERE txn_id = t.id AND loan_interest = 2)
                GROUP BY t.id
                """)) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    moves.add(new Move(rs.getLong(1), LocalDate.parse(rs.getString(2)), rs.getString(3),
                            rs.getLong(4), rs.getLong(5), rs.getInt(6), rs.getLong(7), rs.getLong(8)));
                }
            }
        }
        moves.sort((a, b) -> a.date.equals(b.date)
                ? a.amount > 0 == b.amount > 0 ? Long.compare(a.id, b.id) : a.amount > 0 ? 1 : -1
                : a.date.compareTo(b.date));

        long categoryId = loan.rateMilli() > 0 ? categories.findOrCreate(loan.interestCategoryOrDefault()) : 0;
        LocalDate accruedTo = made != null ? made : moves.isEmpty() ? null : moves.get(0).date;
        BigDecimal accrued = BigDecimal.ZERO;
        LocalDate lastPayment = null;
        int paymentNumber = 0;
        // Charged on the due dates: the balance holds all the scheduled interest, charged up front.
        boolean upFront = loan.interestMethod() == Loan.InterestMethod.DUE_DATES && loan.dueDate(1) != null;
        long interestCharged = 0;
        for (Move m : moves) {
            // Daily: what's owed earns a day's interest for each day up to this transaction.
            if (accruedTo != null && m.date.isAfter(accruedTo)) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(accruedTo, m.date);
                accrued = accrued.add(loan.dailyInterest(-balance).multiply(BigDecimal.valueOf(days)));
                accruedTo = m.date;
            }
            balance += m.amount;
            long interest;
            if (m.amount <= 0 || loan.rateMilli() <= 0 || (made != null && m.date.isBefore(made))) {
                interest = 0;                           // borrowing more, a fee, or no interest charged
            } else if (upFront) {
                // The next period of the lender's schedule, whenever it's actually paid.
                paymentNumber++;
                LocalDate from = paymentNumber == 1 && made != null ? made : loan.dueDate(paymentNumber - 1);
                interest = loan.periodInterest(-(balance - m.amount), from, loan.dueDate(paymentNumber));
            } else if (loan.interestMethod() == Loan.InterestMethod.DAILY) {
                interest = accrued.setScale(0, RoundingMode.HALF_UP).longValue();
            } else {
                boolean paidThisMonth = lastPayment != null
                        && lastPayment.withDayOfMonth(1).equals(m.date.withDayOfMonth(1));
                interest = paidThisMonth ? 0 : loan.monthlyInterest(-(balance - m.amount));
            }
            if (m.amount > 0) {
                lastPayment = m.date;
                accrued = BigDecimal.ZERO;
            }

            if (TxnStatus.RECONCILED.code().equals(m.status)) {
                interest = m.splitInterest + m.shownInterest;   // reconciled: kept as it was
            } else if (upFront) {
                // Shown with the payment; the charge up front already holds it.
                if (m.interestSplits > 0) {
                    setInterest(m.id, 0, categoryId);
                }
                if (m.shownInterest != interest) {
                    setShownInterest(m.id, interest);
                }
            } else {
                if (interest == 0 ? m.interestSplits > 0
                        : m.interestSplits != 1 || m.splitInterest != interest || m.categoryId != categoryId) {
                    setInterest(m.id, interest, categoryId);
                }
                if (m.shownInterest != 0) {
                    setShownInterest(m.id, 0);
                }
            }
            balance -= interest;                        // (what's owed, in principal and interest due)
            interestCharged += interest;
        }

        // The interest still to come: the rest of the schedule, at the regular payment, from the next due date.
        long scheduled = 0;
        if (upFront && loan.rateMilli() > 0) {
            scheduled = interestCharged;
            if (-balance > 0 && loan.regularPayment() > 0) {
                for (Loan.Payment p : loan.schedule(-balance, loan.dueDate(paymentNumber + 1), loan.regularPayment())) {
                    scheduled += p.interestCents();
                }
            }
        }
        LocalDate chargeDate = made != null ? made : moves.isEmpty() ? LocalDate.now() : moves.get(0).date;
        setScheduledInterest(accountId, chargeDate, scheduled, categoryId);
    }

    /** Records interest shown with a payment (on a loan charged on its due dates). */
    private void setShownInterest(long txnId, long interest) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET loan_interest_cents = ? WHERE id = ?")) {
            ps.setLong(1, interest);
            ps.setLong(2, txnId);
            changes += ps.executeUpdate();
        }
    }

    /**
     * The loan's one "Scheduled interest" transaction: all the interest its
     * schedule charges (none: removed), on the day the loan was made, to the
     * interest category. A reconciled one is left as it is.
     */
    private void setScheduledInterest(long accountId, LocalDate date, long interest, long categoryId)
            throws SQLException {
        Long txnId = null;
        String status = null;
        long amount = 0;
        long category = 0;
        String onDate = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT t.id, t.status, s.amount_cents, s.category_id,"
                + " t.txn_date FROM txn t JOIN split s ON s.txn_id = t.id"
                + " WHERE t.account_id = ? AND s.loan_interest = 2 ORDER BY t.id")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (txnId != null) {
                        execute("DELETE FROM txn WHERE id = ?", rs.getLong(1));   // only ever one
                        continue;
                    }
                    txnId = rs.getLong(1);
                    status = rs.getString(2);
                    amount = -rs.getLong(3);
                    category = rs.getLong(4);
                    onDate = rs.getString(5);
                }
            }
        }
        if (TxnStatus.RECONCILED.code().equals(status)) {
            return;
        }
        if (interest <= 0) {
            if (txnId != null) {
                execute("DELETE FROM txn WHERE id = ?", txnId);
            }
            return;
        }
        if (txnId == null) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO txn (account_id, txn_date, memo, status)"
                    + " VALUES (?, ?, 'Scheduled interest', ?)")) {
                ps.setLong(1, accountId);
                ps.setString(2, date.toString());
                ps.setString(3, TxnStatus.CLEARED.code());
                changes += ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO split (txn_id, amount_cents,"
                    + " category_id, loan_interest) VALUES (last_insert_rowid(), ?, ?, 2)")) {
                ps.setLong(1, -interest);
                ps.setLong(2, categoryId);
                changes += ps.executeUpdate();
            }
        } else if (amount != interest || category != categoryId || !date.toString().equals(onDate)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE split SET amount_cents = ?, category_id = ? WHERE txn_id = ? AND loan_interest = 2")) {
                ps.setLong(1, -interest);
                ps.setLong(2, categoryId);
                ps.setLong(3, txnId);
                changes += ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE txn SET txn_date = ? WHERE id = ?")) {
                ps.setString(1, date.toString());
                ps.setLong(2, txnId);
                ps.executeUpdate();
            }
        }
    }

    /** Replaces a payment's interest split (none for 0). */
    private void setInterest(long txnId, long interest, long categoryId) throws SQLException {
        execute("DELETE FROM split WHERE txn_id = ? AND loan_interest = 1", txnId);
        if (interest > 0) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO split (txn_id, amount_cents,"
                    + " category_id, memo, loan_interest) VALUES (?, ?, ?, 'Interest', 1)")) {
                ps.setLong(1, txnId);
                ps.setLong(2, -interest);
                ps.setLong(3, categoryId);
                changes += ps.executeUpdate();
            }
        }
    }

    /**
     * Payments made before interest was calculated this way were split into
     * a "Principal" transfer to the loan and an "Interest" category, on the
     * paying account's side. Each becomes one transfer of the whole payment
     * (the loan's side then gets its interest worked out like any other).
     */
    private void convertSplitPayments(long loanId) throws SQLException {
        List<long[]> found = new ArrayList<>();       // txn, principal split, interest split
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.txn_id, p.id, i.id FROM split p
                JOIN split i ON i.txn_id = p.txn_id AND i.id <> p.id
                JOIN txn t ON t.id = p.txn_id
                WHERE p.transfer_account_id = ? AND p.memo = 'Principal' AND i.memo = 'Interest'
                  AND i.category_id IS NOT NULL AND i.loan_interest = 0 AND t.status <> 'R'
                  AND (SELECT COUNT(*) FROM split WHERE txn_id = p.txn_id) = 2
                """)) {
            ps.setLong(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.add(new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)});
                }
            }
        }
        for (long[] f : found) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE split SET memo = NULL,"
                    + " amount_cents = (SELECT SUM(amount_cents) FROM split WHERE txn_id = ?) WHERE id = ?")) {
                ps.setLong(1, f[0]);
                ps.setLong(2, f[1]);
                ps.executeUpdate();
            }
            execute("DELETE FROM split WHERE id = ?", f[2]);
            // The loan's side of it: the whole payment.
            try (PreparedStatement ps = conn.prepareStatement("UPDATE split SET amount_cents ="
                    + " -(SELECT amount_cents FROM split WHERE id = ?)"
                    + " WHERE transfer_txn_id = ? AND txn_id IN (SELECT id FROM txn WHERE account_id = ?)")) {
                ps.setLong(1, f[1]);
                ps.setLong(2, f[0]);
                ps.setLong(3, loanId);
                ps.executeUpdate();
            }
        }
    }

    private void execute(String sql, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            changes += ps.executeUpdate();
        }
    }

    /**
     * One of the loan's transactions. amount (the payment, without its
     * interest) > 0 lowers what's owed. Its interest as recorded now: in a
     * split of its own (splitInterest), or just shown (shownInterest).
     */
    private record Move(long id, LocalDate date, String status, long amount, long splitInterest, int interestSplits,
                        long categoryId, long shownInterest) {
    }
}
