package com.martinsmayhem.mymoney;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A credit card account's terms, and the arithmetic of carrying and paying
 * off a balance.
 *
 * A card's balance is negative when money is owed. Interest is the balance
 * owed times the APR / 12 each month (the issuer posts it on the statement,
 * so it isn't added here automatically). The minimum payment is a percent of
 * what's owed or a dollar floor, whichever is more (never more than owed).
 *
 * @param aprMilli         annual percentage rate in thousandths of a percent (19.99% = 19990)
 * @param limitCents       the credit limit; 0 = not entered
 * @param statementDay     the day of the month the statement closes (1-31; 0 = not entered)
 * @param dueDay           the day of the month the payment is due (1-31; 0 = not entered)
 * @param minPercentMilli  the minimum payment's percent of the balance, in thousandths (2% = 2000)
 * @param minFloorCents    the smallest minimum payment (e.g. $35.00)
 */
record CreditCard(long accountId, int aprMilli, long limitCents, int statementDay, int dueDay,
                  int minPercentMilli, long minFloorCents) {

    private static final MathContext MC = MathContext.DECIMAL64;

    /** Available credit when owing owedCents (negative if over the limit); 0 if there is no limit. */
    long available(long owedCents) {
        return limitCents <= 0 ? 0 : limitCents - Math.max(0, owedCents);
    }

    /** How much of the limit is used, in whole percent; -1 if there is no limit. */
    int utilizationPercent(long owedCents) {
        if (limitCents <= 0) {
            return -1;
        }
        return (int) Math.round(Math.max(0, owedCents) * 100.0 / limitCents);
    }

    /** One month's interest on what's owed. */
    long monthlyInterest(long owedCents) {
        if (owedCents <= 0 || aprMilli <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(owedCents)
                .multiply(BigDecimal.valueOf(aprMilli).divide(BigDecimal.valueOf(100_000L * 12), MC), MC)
                .setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** The minimum payment on what's owed: the percent or the floor, whichever is more, but not more than owed. */
    long minimumPayment(long owedCents) {
        if (owedCents <= 0) {
            return 0;
        }
        long percent = BigDecimal.valueOf(owedCents).multiply(BigDecimal.valueOf(minPercentMilli))
                .divide(BigDecimal.valueOf(100_000), 0, RoundingMode.UP).longValue();
        return Math.min(owedCents, Math.max(percent, minFloorCents));
    }

    /** True if a minimum payment rule was entered. */
    boolean hasMinimumRule() {
        return minPercentMilli > 0 || minFloorCents > 0;
    }

    /** The next date (today or later) falling on day of the month (31 = the month's last day), or null if day is 0. */
    static LocalDate nextDay(int day, LocalDate from) {
        if (day <= 0) {
            return null;
        }
        LocalDate d = from.withDayOfMonth(Math.min(day, from.lengthOfMonth()));
        if (d.isBefore(from)) {
            LocalDate next = from.withDayOfMonth(1).plusMonths(1);
            d = next.withDayOfMonth(Math.min(day, next.lengthOfMonth()));
        }
        return d;
    }

    /**
     * Paying off owedCents from firstDate on, monthly: each month the interest
     * is added and then the payment made, either fixedCents a month or (if
     * fixedCents is 0) the minimum payment on that month's balance. Stops
     * after 1200 months, or if the payment doesn't cover the interest.
     */
    List<Loan.Payment> payoff(long owedCents, LocalDate firstDate, long fixedCents) {
        List<Loan.Payment> rows = new ArrayList<>();
        long owed = owedCents;
        for (int n = 1; owed > 0 && n <= 1200; n++) {
            long interest = monthlyInterest(owed);
            long due = owed + interest;
            long pay = Math.min(due, fixedCents > 0 ? fixedCents : minimumPayment(due));
            if (pay <= interest) {
                break;                                  // never pays it off
            }
            owed = due - pay;
            rows.add(new Loan.Payment(n, firstDate.plusMonths(n - 1), pay, interest, pay - interest, owed));
        }
        return rows;
    }
}
