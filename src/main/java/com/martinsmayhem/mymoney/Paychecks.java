package com.martinsmayhem.mymoney;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Paychecks: scheduled deposits marked as the main, secondary, tertiary, ...
 * paycheck (schedule.paycheck), and what's due between them.
 *
 * One paycheck is chosen to go by. Its periods are:
 *  - Until next paycheck: from today to the day before it next arrives (what
 *    the money you have now has to cover);
 *  - Next pay period: from that payday to the day before the one after (what
 *    that paycheck has to cover).
 * A bill due on a payday falls in the period that paycheck starts. The
 * transactions shown are those not yet cleared (Scheduled, Pending, Paid,
 * none) in the period, in every open account; a transfer once (its
 * outgoing side); a loan's calculated interest left out.
 *
 * The settings (on or off, which paycheck, the Summary's period) belong to
 * the data file, as its schedules do: they're kept in its meta table.
 */
final class Paychecks {

    /** Which period: until the next paycheck, or the pay period it starts. */
    enum Mode {
        UNTIL_NEXT("next", "Until next paycheck"),
        NEXT_PERIOD("period", "Next pay period");

        final String key;
        final String label;

        Mode(String key, String label) {
            this.key = key;
            this.label = label;
        }

        static Mode of(String key) {
            return NEXT_PERIOD.key.equals(key) ? NEXT_PERIOD : UNTIL_NEXT;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** On or off; which paycheck (its rank) to go by; the period the Summary shows. */
    record Settings(boolean enabled, int rank, Mode mode) {
        static final Settings OFF = new Settings(false, 1, Mode.UNTIL_NEXT);
    }

    /** A period: from (inclusive) to before (exclusive), and the paycheck and payday it's measured by. */
    record Period(LocalDate from, LocalDate before, ScheduleRepository.Schedule paycheck, LocalDate payday) {
        /** The last day in it. */
        LocalDate last() {
            return before.minusDays(1);
        }

        boolean contains(LocalDate d) {
            return !d.isBefore(from) && d.isBefore(before);
        }
    }

    /** One transaction in a period. */
    record Upcoming(LedgerEntry entry, Account account) {
    }

    /** "Main", "Secondary", "Tertiary", "4th", ... */
    static String rankName(int rank) {
        return switch (rank) {
            case 1 -> "Main";
            case 2 -> "Secondary";
            case 3 -> "Tertiary";
            default -> Recurrence.ordinal(rank);
        };
    }

    /** The ranks the schedule window offers (Main to 5th). */
    static final int MAX_RANK = 5;

    private final Connection conn;
    private final ScheduleRepository schedules;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    Paychecks(Database db) {
        this.conn = db.getConnection();
        this.schedules = new ScheduleRepository(db);
        this.accounts = new AccountRepository(db);
        this.transactions = new TransactionRepository(db);
    }

    // ----- Settings -------------------------------------------------------------------

    private String meta(String key, String def) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : def;
            }
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    Settings settings() throws SQLException {
        int rank;
        try {
            rank = Integer.parseInt(meta("paycheck_rank", "1"));
        } catch (NumberFormatException e) {
            rank = 1;
        }
        return new Settings("1".equals(meta("paycheck_enabled", "0")), Math.max(1, rank),
                Mode.of(meta("paycheck_mode", Mode.UNTIL_NEXT.key)));
    }

    void save(Settings s) throws SQLException {
        setMeta("paycheck_enabled", s.enabled() ? "1" : "0");
        setMeta("paycheck_rank", String.valueOf(s.rank()));
        setMeta("paycheck_mode", s.mode().key);
    }

    // ----- Paychecks and periods ----------------------------------------------------

    /** The schedules marked as paychecks, main first. */
    List<ScheduleRepository.Schedule> paychecks() throws SQLException {
        List<ScheduleRepository.Schedule> list = new ArrayList<>();
        for (ScheduleRepository.Schedule s : schedules.findAll()) {
            if (s.paycheck() != null) {
                list.add(s);
            }
        }
        list.sort(Comparator.comparingInt(ScheduleRepository.Schedule::paycheck));
        return list;
    }

    /** The paycheck with this rank, or null if none is marked so. */
    ScheduleRepository.Schedule paycheck(int rank) throws SQLException {
        for (ScheduleRepository.Schedule s : paychecks()) {
            if (s.paycheck() == rank) {
                return s;
            }
        }
        return null;
    }

    /**
     * The period measured by a paycheck, as of today; null if it has no
     * payday after today (the schedule has ended).
     */
    static Period period(ScheduleRepository.Schedule paycheck, Mode mode, LocalDate today) {
        LocalDate next = paycheck.recurrence().nextOnOrAfter(today.plusDays(1));
        if (next == null) {
            return null;
        }
        if (mode == Mode.UNTIL_NEXT) {
            return new Period(today, next, paycheck, next);
        }
        LocalDate after = paycheck.recurrence().nextOnOrAfter(next.plusDays(1));
        return after == null ? null : new Period(next, after, paycheck, next);
    }

    /** The period the settings call for, as of today; null if off, no such paycheck, or it has ended. */
    Period current(Mode mode, LocalDate today) throws SQLException {
        Settings s = settings();
        if (!s.enabled()) {
            return null;
        }
        ScheduleRepository.Schedule p = paycheck(s.rank());
        return p == null ? null : period(p, mode, today);
    }

    /**
     * How many days ahead scheduled transactions must be in the ledger: at
     * least setting (the Preferences one), and with paychecks on, through the
     * end of the next pay period, so every bill in it is there to show.
     */
    int daysAhead(int setting, LocalDate today) throws SQLException {
        Period next = current(Mode.NEXT_PERIOD, today);
        return next == null ? setting
                : Math.max(setting, (int) java.time.temporal.ChronoUnit.DAYS.between(today, next.last()));
    }

    /** A status that counts as upcoming: not yet cleared by the bank. */
    static boolean upcoming(TxnStatus s) {
        return s != TxnStatus.CLEARED && s != TxnStatus.RECONCILED && s != TxnStatus.RECONCILING;
    }

    /**
     * What's due in a period, in every open account, by date: the
     * transactions not yet cleared; a transfer once (its outgoing side); a
     * loan's calculated interest left out.
     */
    List<Upcoming> upcoming(Period p) throws SQLException {
        List<Upcoming> list = new ArrayList<>();
        for (Account a : accounts.findAll()) {
            if (a.closed()) {
                continue;
            }
            for (LedgerEntry e : transactions.findByAccount(a.id())) {
                if (!p.contains(e.date) || !upcoming(e.status) || e.scheduledInterest) {
                    continue;
                }
                if (TransactionRepository.isTransfer(e.category) && e.amountCents > 0) {
                    continue;                            // the other side's outgoing half lists it
                }
                list.add(new Upcoming(e, a));
            }
        }
        list.sort(Comparator.comparing((Upcoming u) -> u.entry().date).thenComparingLong(u -> u.entry().id));
        return list;
    }
}
