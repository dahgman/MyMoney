package com.martinsmayhem.mymoney;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out an installment loan's payment and schedule, the way lenders do.
 * Plain arithmetic, no user interface, so it can be tested on its own.
 *
 * <h2>Methods</h2>
 * <ul>
 * <li>{@link Method#STANDARD_AMORTIZATION}: every period is one month, charged
 *     APR / 12, whatever the dates; the payment comes from the usual closed-form
 *     formula. What most online calculators do.</li>
 * <li>{@link Method#DAILY_SIMPLE_INTEREST}: simple interest accrued daily on
 *     the unpaid principal (the Texas Finance Code's "true daily earnings
 *     method", and how most auto and installment lenders work). Each period's
 *     interest is principal × APR / days-in-year × the actual days since the
 *     previous date (the contract date, for the first payment), so a first
 *     payment more or less than a month out changes the payment. There is no
 *     formula for the payment once periods differ, so it's solved for: the
 *     level payment that leaves nothing owed after the last one.</li>
 * </ul>
 *
 * <h2>Conventions</h2>
 * <ul>
 * <li>Money is BigDecimal throughout. Each period's interest is rounded to the
 *     cent, half up ({@link #INTEREST_ROUNDING}); the regular payment too
 *     ({@link #PAYMENT_ROUNDING}).</li>
 * <li>A payment pays the interest due first, then principal. A payment too
 *     small to cover the interest leaves the rest of it due with the next
 *     payment (it isn't added to the principal, so it earns no interest).</li>
 * <li>The final payment is whatever clears the loan: the principal left plus
 *     its interest, so the balance ends at exactly 0.00.</li>
 * <li>Payment dates: see {@link Frequency}.</li>
 * </ul>
 */
final class LoanCalculator {

    /** How each period's interest is rounded to the cent. */
    static final RoundingMode INTEREST_ROUNDING = RoundingMode.HALF_UP;
    /** How the solved regular payment is rounded to the cent. */
    static final RoundingMode PAYMENT_ROUNDING = RoundingMode.HALF_UP;

    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private LoanCalculator() {
    }

    /** How interest is worked out (see the class comment). */
    enum Method {
        STANDARD_AMORTIZATION("Standard amortization (rate ÷ 12)"),
        DAILY_SIMPLE_INTEREST("Simple interest, accrued daily");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The days in a year, for the daily rate (APR / days). */
    enum DayCount {
        /** Always 365, leap years too. */
        FIXED_365("365-day year"),
        /** 365, or 366 for days in a leap year (a period spanning New Year is split at it). */
        ACTUAL_365_366("Actual (366 in leap years)");

        private final String label;

        DayCount(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * How often payments fall due. Payment n's date is worked out from the
     * first payment's date (never from the payment before it), so short
     * months don't drift the schedule.
     */
    enum Frequency {
        /**
         * The same day of the month as the first payment. Where a month is
         * too short (a first payment on the 29th to 31st), the payment falls
         * on that month's last day, and goes back to the usual day the month
         * after: Jan 31, Feb 28, Mar 31, Apr 30, ...
         */
        MONTHLY {
            @Override
            LocalDate due(LocalDate first, int n) {
                return first.plusMonths(n - 1L);
            }

            @Override
            int periodsPerYear() {
                return 12;
            }
        };

        /** The date payment n (1 = the first) is due. */
        abstract LocalDate due(LocalDate first, int n);

        /** For the standard method's rate per period (APR / this). */
        abstract int periodsPerYear();
    }

    /**
     * What the loan contract says.
     *
     * @param amountFinanced the principal borrowed
     * @param apr            the annual rate as a percent (8.14 for 8.14%)
     * @param payments       how many payments
     * @param contractDate   when the loan was made (interest starts)
     * @param firstPayment   when the first payment is due
     */
    record Terms(BigDecimal amountFinanced, BigDecimal apr, int payments, LocalDate contractDate,
                 LocalDate firstPayment, Frequency frequency, Method method, DayCount dayCount) {

        Terms {
            if (amountFinanced == null || amountFinanced.signum() <= 0) {
                throw new IllegalArgumentException("The amount financed must be more than zero.");
            }
            if (apr == null || apr.signum() < 0) {
                throw new IllegalArgumentException("The APR can't be negative.");
            }
            if (payments <= 0) {
                throw new IllegalArgumentException("There must be at least one payment.");
            }
            if (contractDate == null || firstPayment == null || !firstPayment.isAfter(contractDate)) {
                throw new IllegalArgumentException("The first payment must be due after the contract date.");
            }
        }

        /** The same loan, worked out another way. */
        Terms with(Method m) {
            return new Terms(amountFinanced, apr, payments, contractDate, firstPayment, frequency, m, dayCount);
        }
    }

    /** One payment in the schedule. balance is what's still owed after it. */
    record Row(int number, LocalDate date, long days, BigDecimal payment, BigDecimal interest,
               BigDecimal principal, BigDecimal balance) {
    }

    /** The answer: the payments and their totals. */
    record Result(Terms terms, BigDecimal regularPayment, BigDecimal finalPayment, BigDecimal totalOfPayments,
                  BigDecimal financeCharge, List<Row> schedule) {
    }

    /** Works out the regular payment, then the schedule it makes. */
    static Result calculate(Terms t) {
        return withPayment(t, solvePayment(t));
    }

    /**
     * The schedule a given regular payment makes (such as the lender's own
     * figure, or one with extra principal): every payment but the last is
     * that amount; the last clears what's left. A payment large enough to
     * clear the loan early ends the schedule there.
     */
    static Result withPayment(Terms t, BigDecimal regularPayment) {
        BigDecimal payment = regularPayment.setScale(2, PAYMENT_ROUNDING);
        List<Row> rows = new ArrayList<>();
        BigDecimal balance = t.amountFinanced().setScale(2, INTEREST_ROUNDING);
        BigDecimal unpaidInterest = BigDecimal.ZERO;          // interest a short payment didn't cover
        BigDecimal total = BigDecimal.ZERO;
        LocalDate previous = t.contractDate();
        for (int n = 1; n <= t.payments(); n++) {
            LocalDate date = t.frequency().due(t.firstPayment(), n);
            BigDecimal interest = interest(t, balance, previous, date).setScale(2, INTEREST_ROUNDING);
            BigDecimal due = interest.add(unpaidInterest);
            boolean last = n == t.payments() || balance.add(due).compareTo(payment) <= 0;
            BigDecimal pay = last ? balance.add(due) : payment;
            BigDecimal toInterest = pay.min(due);
            BigDecimal principal = pay.subtract(toInterest);
            unpaidInterest = due.subtract(toInterest);
            balance = balance.subtract(principal);
            total = total.add(pay);
            rows.add(new Row(n, date, ChronoUnit.DAYS.between(previous, date), pay, interest, principal, balance));
            previous = date;
            if (last) {
                break;
            }
        }
        BigDecimal last = rows.get(rows.size() - 1).payment();
        return new Result(t, payment, last, total, total.subtract(t.amountFinanced()), List.copyOf(rows));
    }

    /**
     * The level payment (rounded to the cent) that pays the loan off by its
     * last payment. Standard amortization: the closed-form formula. Daily:
     * solved by bisection on the exact (unrounded) schedule.
     */
    static BigDecimal solvePayment(Terms t) {
        if (t.apr().signum() == 0) {
            return t.amountFinanced().divide(BigDecimal.valueOf(t.payments()), 2, RoundingMode.UP);
        }
        if (t.method() == Method.STANDARD_AMORTIZATION) {
            BigDecimal r = periodRate(t);
            BigDecimal growth = BigDecimal.ONE.add(r).pow(t.payments(), MC);
            return t.amountFinanced().multiply(r, MC).multiply(growth, MC)
                    .divide(growth.subtract(BigDecimal.ONE), MC).setScale(2, PAYMENT_ROUNDING);
        }
        // The balance left after the last payment falls as the payment rises: bisect for zero.
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = t.amountFinanced().add(interest(t, t.amountFinanced(), t.contractDate(),
                t.frequency().due(t.firstPayment(), t.payments())));    // more than enough
        BigDecimal tolerance = new BigDecimal("1E-12");
        for (int i = 0; i < 200 && high.subtract(low).compareTo(tolerance) > 0; i++) {
            BigDecimal mid = low.add(high).divide(BigDecimal.valueOf(2), MC);
            if (balanceAfterAll(t, mid).signum() > 0) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return high.setScale(2, PAYMENT_ROUNDING);
    }

    /** What's owed after paying payment every time, with nothing rounded. */
    private static BigDecimal balanceAfterAll(Terms t, BigDecimal payment) {
        BigDecimal balance = t.amountFinanced();
        LocalDate previous = t.contractDate();
        for (int n = 1; n <= t.payments(); n++) {
            LocalDate date = t.frequency().due(t.firstPayment(), n);
            balance = balance.add(interest(t, balance, previous, date)).subtract(payment);
            previous = date;
        }
        return balance;
    }

    /** The interest on balance from one date to the next, unrounded. */
    private static BigDecimal interest(Terms t, BigDecimal balance, LocalDate from, LocalDate to) {
        if (balance.signum() <= 0 || t.apr().signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (t.method() == Method.STANDARD_AMORTIZATION) {
            return balance.multiply(periodRate(t), MC);       // a period is a period, however long
        }
        BigDecimal rate = t.apr().divide(HUNDRED, MC);
        if (t.dayCount() == DayCount.FIXED_365) {
            return balance.multiply(rate, MC).multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to)), MC)
                    .divide(BigDecimal.valueOf(365), MC);
        }
        // Actual: the days in each calendar year, over that year's length.
        BigDecimal sum = BigDecimal.ZERO;
        LocalDate start = from;
        while (start.isBefore(to)) {
            LocalDate yearEnd = LocalDate.of(start.getYear() + 1, 1, 1);
            LocalDate end = yearEnd.isBefore(to) ? yearEnd : to;
            sum = sum.add(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end))
                    .divide(BigDecimal.valueOf(start.lengthOfYear()), MC));
            start = end;
        }
        return balance.multiply(rate, MC).multiply(sum, MC);
    }

    /** The standard method's rate per period: APR / 100 / periods a year. */
    private static BigDecimal periodRate(Terms t) {
        return t.apr().divide(HUNDRED, MC).divide(BigDecimal.valueOf(t.frequency().periodsPerYear()), MC);
    }
}
