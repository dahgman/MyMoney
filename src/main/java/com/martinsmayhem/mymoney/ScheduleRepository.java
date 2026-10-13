package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled transactions: what to enter, how often (a Recurrence), and in
 * which account.
 *
 * Occurrences are put in the ledger ahead of time, as ordinary transactions
 * with the status Scheduled, up to a number of days ahead (a preference).
 * Each one remembers its schedule and the date it was scheduled for
 * (txn.schedule_id, txn.schedule_date). The schedule remembers how far it
 * has been created (generated_through), so an occurrence deleted from the
 * ledger isn't created again.
 *
 * Saving a changed schedule replaces its upcoming occurrences that are still
 * untouched (Scheduled, from today on); ones already edited or cleared stay.
 * Past occurrences are never created: a schedule starts from today at the
 * earliest.
 */
final class ScheduleRepository {

    /** One schedule. id 0 = not saved yet. */
    record Schedule(long id, long accountId, String payee, String category, String tag, String description,
                    String memo, long amountCents, Recurrence recurrence) {
    }

    private final Connection conn;
    private final TransactionRepository transactions;

    ScheduleRepository(Database db) {
        this.conn = db.getConnection();
        this.transactions = new TransactionRepository(db);
    }

    // ----- Reading -------------------------------------------------------------

    List<Schedule> findAll() throws SQLException {
        return query("SELECT " + COLUMNS + " FROM schedule ORDER BY id", null);
    }

    Schedule find(long id) throws SQLException {
        List<Schedule> list = query("SELECT " + COLUMNS + " FROM schedule WHERE id = ?", id);
        return list.isEmpty() ? null : list.get(0);
    }

    private static final String COLUMNS = "id, account_id, payee, category, tag, description, memo, amount_cents,"
            + " unit, interval, second_day, start_date, end_date, max_count";

    private List<Schedule> query(String sql, Long id) throws SQLException {
        List<Schedule> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (id != null) {
                ps.setLong(1, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int second = rs.getInt(11);
                    String end = rs.getString(13);
                    int max = rs.getInt(14);
                    Integer maxCount = rs.wasNull() ? null : max;
                    Recurrence r = new Recurrence(Recurrence.Unit.valueOf(rs.getString(9)), rs.getInt(10), second,
                            LocalDate.parse(rs.getString(12)), end == null ? null : LocalDate.parse(end), maxCount);
                    list.add(new Schedule(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), r));
                }
            }
        }
        return list;
    }

    // ----- Saving --------------------------------------------------------------

    /**
     * Saves a schedule (new if its id is 0) and returns its id. A changed
     * schedule's untouched upcoming occurrences are removed, to be created
     * again by the next generate().
     */
    long save(Schedule s) throws SQLException {
        conn.setAutoCommit(false);
        try {
            long id = s.id();
            LocalDate through = startOfCreating(s.recurrence().start());
            if (id == 0) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schedule (account_id, payee,"
                        + " category, tag, description, memo, amount_cents, unit, interval, second_day, start_date,"
                        + " end_date, max_count, generated_through) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    int i = bind(ps, s);
                    ps.setString(i, through.toString());
                    ps.executeUpdate();
                }
                id = transactions.lastInsertId();
            } else {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE schedule SET account_id = ?, payee = ?,"
                        + " category = ?, tag = ?, description = ?, memo = ?, amount_cents = ?, unit = ?, interval = ?,"
                        + " second_day = ?, start_date = ?, end_date = ?, max_count = ?, generated_through = ?"
                        + " WHERE id = ?")) {
                    int i = bind(ps, s);
                    ps.setString(i, through.toString());
                    ps.setLong(i + 1, id);
                    ps.executeUpdate();
                }
                removeUntouchedUpcoming(id);
            }
            conn.commit();
            return id;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** Deletes a schedule, and (if removeUpcoming) its untouched upcoming occurrences. Others stay. */
    void delete(long id, boolean removeUpcoming) throws SQLException {
        conn.setAutoCommit(false);
        try {
            if (removeUpcoming) {
                removeUntouchedUpcoming(id);
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM schedule WHERE id = ?")) {
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

    /** How many of a schedule's occurrences are still Scheduled and dated from today on. */
    int countUntouchedUpcoming(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM txn WHERE schedule_id = ?"
                + " AND status = ? AND schedule_date >= ?")) {
            ps.setLong(1, id);
            ps.setString(2, TxnStatus.SCHEDULED.code());
            ps.setString(3, LocalDate.now().toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Removes occurrences still Scheduled and dated from today on (and the other side of a transfer). */
    private void removeUntouchedUpcoming(long id) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM txn WHERE schedule_id = ?"
                + " AND status = ? AND schedule_date >= ?")) {
            ps.setLong(1, id);
            ps.setString(2, TxnStatus.SCHEDULED.code());
            ps.setString(3, LocalDate.now().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        for (long txnId : ids) {
            transactions.deleteInCurrentTransaction(txnId);
        }
    }

    /** Occurrences before this date are never created: yesterday, or the day before the start. */
    private static LocalDate startOfCreating(LocalDate start) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate beforeStart = start.minusDays(1);
        return beforeStart.isAfter(yesterday) ? beforeStart : yesterday;
    }

    private static int bind(PreparedStatement ps, Schedule s) throws SQLException {
        Recurrence r = s.recurrence();
        ps.setLong(1, s.accountId());
        TransactionRepository.setText(ps, 2, s.payee());
        TransactionRepository.setText(ps, 3, s.category());
        TransactionRepository.setText(ps, 4, s.tag());
        TransactionRepository.setText(ps, 5, s.description());
        TransactionRepository.setText(ps, 6, s.memo());
        ps.setLong(7, s.amountCents());
        ps.setString(8, r.unit().name());
        ps.setInt(9, r.interval());
        if (r.unit() == Recurrence.Unit.SEMIMONTH) {
            ps.setInt(10, r.secondDay());
        } else {
            ps.setNull(10, Types.INTEGER);
        }
        ps.setString(11, r.start().toString());
        TransactionRepository.setText(ps, 12, r.endDate() == null ? null : r.endDate().toString());
        if (r.maxCount() == null) {
            ps.setNull(13, Types.INTEGER);
        } else {
            ps.setInt(13, r.maxCount());
        }
        return 14;
    }

    // ----- Creating occurrences ----------------------------------------------

    /**
     * Puts every schedule's occurrences up to daysAhead days from today in the
     * ledger, as Scheduled transactions. Returns how many were created;
     * problems (like a transfer to an account that no longer exists) are
     * added to problems, and that schedule is left for next time.
     */
    int generate(int daysAhead, List<String> problems) throws SQLException {
        LocalDate until = LocalDate.now().plusDays(daysAhead);
        int created = 0;
        for (Schedule s : findAll()) {
            LocalDate through = generatedThrough(s.id());
            if (!through.isBefore(until)) {
                continue;
            }
            conn.setAutoCommit(false);
            try {
                int n = 0;
                for (LocalDate d : s.recurrence().datesBetween(through.plusDays(1), until)) {
                    if (!exists(s.id(), d)) {
                        create(s, d);
                        n++;
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE schedule SET generated_through = ? WHERE id = ?")) {
                    ps.setString(1, until.toString());
                    ps.setLong(2, s.id());
                    ps.executeUpdate();
                }
                conn.commit();
                created += n;
            } catch (SQLException e) {
                conn.rollback();
                problems.add("\"" + (s.payee() == null ? "(no payee)" : s.payee()) + "\" "
                        + Money.format(s.amountCents()) + ": " + e.getMessage());
            } finally {
                conn.setAutoCommit(true);
            }
        }
        return created;
    }

    private LocalDate generatedThrough(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT generated_through FROM schedule WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return LocalDate.parse(rs.getString(1));
            }
        }
    }

    /** True if this schedule already has a transaction for that date (kept when a schedule is changed). */
    private boolean exists(long scheduleId, LocalDate date) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM txn WHERE schedule_id = ? AND schedule_date = ?")) {
            ps.setLong(1, scheduleId);
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** One occurrence: an ordinary transaction, Scheduled, marked with its schedule and date. */
    private void create(Schedule s, LocalDate date) throws SQLException {
        LedgerEntry e = new LedgerEntry();
        e.date = date;
        e.payee = s.payee();
        e.category = s.category();
        e.tag = s.tag();
        e.description = s.description();
        e.memo = s.memo();
        e.amountCents = s.amountCents();
        e.status = TxnStatus.SCHEDULED;
        transactions.insertInCurrentTransaction(s.accountId(), e);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE txn SET schedule_id = ?, schedule_date = ? WHERE id = ?")) {
            ps.setLong(1, s.id());
            ps.setString(2, date.toString());
            ps.setLong(3, e.id);
            ps.executeUpdate();
        }
    }
}
