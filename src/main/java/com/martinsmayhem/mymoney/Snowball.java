package com.martinsmayhem.mymoney;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The debt snowball: one monthly amount for all debts. Every debt gets its
 * minimum (planned) payment; whatever is left goes to the first debt in the
 * payoff order; when a debt is paid off, its payment rolls on to the next.
 *
 * Debts are the loan and credit card accounts with a balance owed today. A
 * debt's minimum is, in order: one set in the plan; else its linked
 * schedule's payment (as a monthly amount); else a loan's regular payment or
 * a card's minimum payment rule. A card is never paid less than its minimum
 * rule. Interest is the balance times the rate / 12, each month. No new
 * charges are assumed.
 *
 * The plan's settings (budget, order, custom order, left-out debts, minimums
 * set by hand) are kept in the data file's meta table.
 */
final class Snowball {

    /** The payoff order. */
    enum Method {
        SNOWBALL("Snowball: smallest balance first"),
        AVALANCHE("Avalanche: highest rate first"),
        CUSTOM("Custom order");

        final String label;

        Method(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One debt in the plan. scheduleId: its one monthly linked schedule, if it has exactly one (for Apply). */
    record Debt(Account account, long owedCents, int rateMilli, long minimumCents, String minimumSource,
                CreditCard card, Long scheduleId) {
    }

    /** What happens to one debt over the plan. */
    record Outcome(long firstPayment, int payoffMonth, long interestCents) {
    }

    /** One month of the plan, for all debts together. */
    record Month(LocalDate date, long paymentCents, long interestCents, long owedAfterCents, List<String> paidOff) {
    }

    /** The whole plan: per debt (same order as the debts given), month by month, and the totals. */
    record Plan(Map<Long, Outcome> outcomes, List<Month> months, long totalInterest, boolean paidOff) {
        int monthsToDebtFree() {
            return months.size();
        }
    }

    // ----- Settings ----------------------------------------------------------------

    /** The plan's saved settings. minimums: account id -> minimum set by hand (cents). */
    static final class Settings {
        long budgetCents;
        Method method = Method.SNOWBALL;
        List<Long> customOrder = new ArrayList<>();
        Set<Long> excluded = new HashSet<>();
        Map<Long, Long> minimums = new HashMap<>();
    }

    static Settings load(Connection conn) throws SQLException {
        Settings s = new Settings();
        try (PreparedStatement ps = conn.prepareStatement("SELECT key, value FROM meta WHERE key LIKE 'snowball.%'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString(1);
                String v = rs.getString(2);
                try {
                    switch (key) {
                        case "snowball.budget" -> s.budgetCents = Long.parseLong(v);
                        case "snowball.method" -> s.method = Method.valueOf(v);
                        case "snowball.order" -> s.customOrder = ids(v);
                        case "snowball.excluded" -> s.excluded = new HashSet<>(ids(v));
                        default -> {
                            if (key.startsWith("snowball.min.")) {
                                s.minimums.put(Long.parseLong(key.substring(13)), Long.parseLong(v));
                            }
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // a damaged setting: use the default
                }
            }
        }
        return s;
    }

    static void save(Connection conn, Settings s) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM meta WHERE key LIKE 'snowball.%'");
             PreparedStatement ins = conn.prepareStatement("INSERT INTO meta (key, value) VALUES (?, ?)")) {
            del.executeUpdate();
            put(ins, "snowball.budget", String.valueOf(s.budgetCents));
            put(ins, "snowball.method", s.method.name());
            put(ins, "snowball.order", join(s.customOrder));
            put(ins, "snowball.excluded", join(new ArrayList<>(s.excluded)));
            for (Map.Entry<Long, Long> m : s.minimums.entrySet()) {
                put(ins, "snowball.min." + m.getKey(), String.valueOf(m.getValue()));
            }
        }
    }

    private static void put(PreparedStatement ps, String key, String value) throws SQLException {
        ps.setString(1, key);
        ps.setString(2, value);
        ps.executeUpdate();
    }

    private static List<Long> ids(String v) {
        List<Long> list = new ArrayList<>();
        for (String p : v.split(",")) {
            if (!p.isBlank()) {
                list.add(Long.parseLong(p.trim()));
            }
        }
        return list;
    }

    private static String join(List<Long> ids) {
        StringBuilder b = new StringBuilder();
        for (Long id : ids) {
            b.append(b.length() == 0 ? "" : ",").append(id);
        }
        return b.toString();
    }

    // ----- The debts ---------------------------------------------------------------

    /** Every loan and credit card owing money today, with its rate and minimum payment. */
    static List<Debt> debts(AccountRepository accounts, ScheduleRepository schedules, Settings settings)
            throws SQLException {
        LocalDate today = LocalDate.now();
        List<ScheduleRepository.Schedule> all = schedules.findAll();
        List<Debt> list = new ArrayList<>();
        for (Account a : accounts.findAll()) {
            if (a.type() != AccountType.LOAN && a.type() != AccountType.CREDIT_CARD) {
                continue;
            }
            long owed = -accounts.balanceThrough(a.id(), today);
            if (owed <= 0) {
                continue;
            }
            Loan loan = a.type() == AccountType.LOAN ? accounts.findLoan(a.id()) : null;
            CreditCard card = a.type() == AccountType.CREDIT_CARD ? accounts.findCard(a.id()) : null;
            int rate = loan != null ? loan.rateMilli() : card != null ? card.aprMilli() : 0;

            // Linked schedules still running: payments into this account.
            long fromSchedules = 0;
            List<ScheduleRepository.Schedule> monthly = new ArrayList<>();
            for (ScheduleRepository.Schedule s : all) {
                if (("[" + a.name() + "]").equalsIgnoreCase(String.valueOf(s.category())) && s.amountCents() < 0
                        && s.recurrence().nextOnOrAfter(today) != null) {
                    fromSchedules += monthlyAmount(-s.amountCents(), s.recurrence());
                    if (s.recurrence().unit() == Recurrence.Unit.MONTH && s.recurrence().interval() == 1) {
                        monthly.add(s);
                    }
                }
            }
            Long scheduleId = monthly.size() == 1 ? monthly.get(0).id() : null;

            long minimum;
            String source;
            if (settings.minimums.containsKey(a.id())) {
                minimum = settings.minimums.get(a.id());
                source = "set in the plan";
            } else if (fromSchedules > 0) {
                minimum = fromSchedules;
                source = "its scheduled payment";
            } else if (loan != null && loan.regularPayment() > 0) {
                minimum = loan.regularPayment();
                source = "the loan's payment";
            } else if (card != null && card.hasMinimumRule()) {
                minimum = card.minimumPayment(owed);
                source = "the card's minimum";
            } else {
                minimum = 0;
                source = "none: set a minimum, or link a schedule";
            }
            list.add(new Debt(a, owed, rate, minimum, source, card, scheduleId));
        }
        return list;
    }

    /** A schedule's payment as a monthly amount (every 2 weeks: 26 a year / 12, and so on). */
    static long monthlyAmount(long cents, Recurrence r) {
        BigDecimal perYear = switch (r.unit()) {
            case DAY -> BigDecimal.valueOf(365).divide(BigDecimal.valueOf(r.interval()), 10, RoundingMode.HALF_UP);
            case WEEK -> BigDecimal.valueOf(52).divide(BigDecimal.valueOf(r.interval()), 10, RoundingMode.HALF_UP);
            case MONTH -> BigDecimal.valueOf(12).divide(BigDecimal.valueOf(r.interval()), 10, RoundingMode.HALF_UP);
            case YEAR -> BigDecimal.ONE.divide(BigDecimal.valueOf(r.interval()), 10, RoundingMode.HALF_UP);
            case SEMIMONTH -> BigDecimal.valueOf(24);
        };
        return BigDecimal.valueOf(cents).multiply(perYear).divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /** The debts in the plan (left-out ones and ones with no minimum aren't), in payoff order. */
    static List<Debt> ordered(List<Debt> debts, Settings s) {
        List<Debt> in = new ArrayList<>();
        for (Debt d : debts) {
            if (!s.excluded.contains(d.account().id()) && d.minimumCents() > 0) {
                in.add(d);
            }
        }
        Comparator<Debt> bySize = Comparator.comparingLong(Debt::owedCents);
        switch (s.method) {
            case SNOWBALL -> in.sort(bySize);
            case AVALANCHE -> in.sort(Comparator.comparingInt(Debt::rateMilli).reversed().thenComparing(bySize));
            case CUSTOM -> in.sort(Comparator.comparingInt((Debt d) -> {
                int i = s.customOrder.indexOf(d.account().id());
                return i < 0 ? Integer.MAX_VALUE : i;
            }).thenComparing(bySize));
        }
        return in;
    }

    // ----- The plan ----------------------------------------------------------------

    /**
     * Month by month from firstDate: interest is added, each debt gets its
     * minimum (a card at least its minimum rule), and (if roll) the rest of
     * the budget goes to the debts in order. With roll false, it's just the
     * minimums, and a paid-off debt's payment isn't passed on.
     */
    static Plan plan(List<Debt> order, long budgetCents, boolean roll, LocalDate firstDate) {
        int n = order.size();
        long[] owed = new long[n];
        long[] interestSum = new long[n];
        long[] first = new long[n];
        int[] payoff = new int[n];
        for (int i = 0; i < n; i++) {
            owed[i] = order.get(i).owedCents();
        }
        List<Month> months = new ArrayList<>();
        long total = 0;
        boolean done = n == 0;
        for (int m = 1; m <= 600 && !done; m++) {
            long monthInterest = 0;
            long monthPaid = 0;
            long[] pay = new long[n];
            long used = 0;
            for (int i = 0; i < n; i++) {
                if (owed[i] <= 0) {
                    continue;
                }
                Debt d = order.get(i);
                long interest = monthlyInterest(owed[i], d.rateMilli());
                owed[i] += interest;
                interestSum[i] += interest;
                monthInterest += interest;
                long minimum = d.minimumCents();
                if (d.card() != null && d.card().hasMinimumRule()) {
                    minimum = Math.max(minimum, d.card().minimumPayment(owed[i]));
                }
                pay[i] = Math.min(owed[i], minimum);
                used += pay[i];
            }
            long extra = roll ? Math.max(0, budgetCents - used) : 0;
            for (int i = 0; i < n && extra > 0; i++) {
                long more = Math.min(extra, owed[i] - pay[i]);
                if (more > 0) {
                    pay[i] += more;
                    extra -= more;
                }
            }
            List<String> paidOff = new ArrayList<>();
            long owedAfter = 0;
            for (int i = 0; i < n; i++) {
                if (pay[i] == 0 && owed[i] <= 0) {
                    continue;
                }
                owed[i] -= pay[i];
                monthPaid += pay[i];
                if (m == 1) {
                    first[i] = pay[i];
                }
                if (owed[i] <= 0 && payoff[i] == 0) {
                    payoff[i] = m;
                    paidOff.add(order.get(i).account().name());
                }
                owedAfter += Math.max(0, owed[i]);
            }
            total += monthInterest;
            months.add(new Month(firstDate.plusMonths(m - 1), monthPaid, monthInterest, owedAfter, paidOff));
            done = owedAfter <= 0;
            if (!done && monthPaid <= monthInterest) {
                break;                                  // the payments don't cover the interest: never paid off
            }
        }
        Map<Long, Outcome> outcomes = new HashMap<>();
        for (int i = 0; i < n; i++) {
            outcomes.put(order.get(i).account().id(), new Outcome(first[i], payoff[i], interestSum[i]));
        }
        return new Plan(outcomes, months, total, done);
    }

    private static long monthlyInterest(long owed, int rateMilli) {
        if (owed <= 0 || rateMilli <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(owed).multiply(BigDecimal.valueOf(rateMilli))
                .divide(BigDecimal.valueOf(100_000L * 12), 0, RoundingMode.HALF_UP).longValue();
    }
}
