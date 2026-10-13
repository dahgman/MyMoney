package com.martinsmayhem.mymoney;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A loan account's terms, and the arithmetic of paying it off.
 *
 * Payments are monthly. Each month's interest is the balance owed times the
 * annual rate / 12; the rest of a payment is principal. The interest rate is
 * kept in thousandths of a percent (6.375% = 6375) so it is exact.
 *
 * @param rateMilli      annual interest rate in thousandths of a percent; 0 = no interest
 * @param originalCents  the amount borrowed (positive)
 * @param termMonths     how many monthly payments, from the origination date
 * @param origination    when the loan was made; may be null
 * @param paymentCents   the lender's monthly payment, if entered (0 = use the calculated one)
 * @param firstPayment   when the first payment is due, if entered (null = a month after origination)
 *
 * Lenders' payments often differ a little from the standard calculation: many
 * charge interest daily, and the first payment is often due ~45 days after
 * signing, so that first stretch's extra interest is spread over every
 * payment. Entering the lender's payment and first date makes the schedule
 * follow the actual loan.
 */
record Loan(long accountId, int rateMilli, long originalCents, int termMonths, LocalDate origination,
            String interestCategory, long paymentCents, LocalDate firstPayment) {

    /** Without the lender's payment or first payment date (both calculated). */
    Loan(long accountId, int rateMilli, long originalCents, int termMonths, LocalDate origination,
         String interestCategory) {
        this(accountId, rateMilli, originalCents, termMonths, origination, interestCategory, 0, null);
    }

    /** The category interest goes to when a loan doesn't name one. */
    static final String DEFAULT_INTEREST_CATEGORY = "Interest:Loan";

    private static final MathContext MC = MathContext.DECIMAL64;

    /** The category interest is recorded under. */
    String interestCategoryOrDefault() {
        return interestCategory == null || interestCategory.isBlank() ? DEFAULT_INTEREST_CATEGORY : interestCategory;
    }

    /** "6.375%" */
    static String formatRate(int rateMilli) {
        return BigDecimal.valueOf(rateMilli, 3).stripTrailingZeros().toPlainString() + "%";
    }

    /** "6.375" or "6.375%" -> 6375. @throws IllegalArgumentException with a message fit to show */
    static int parseRate(String text) {
        String t = text.replace("%", "").trim();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            BigDecimal r = new BigDecimal(t);
            if (r.signum() < 0 || r.compareTo(BigDecimal.valueOf(100)) >= 0) {
                throw new NumberFormatException();
            }
            return r.movePointRight(3).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("\"" + text + "\" isn't an interest rate (like 6.375).");
        }
    }

    /** One month's interest on a balance owed (positive cents), rounded to the cent. */
    long monthlyInterest(long owedCents) {
        if (owedCents <= 0 || rateMilli <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(owedCents).multiply(monthlyRate(), MC)
                .setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** The regular monthly payment: the lender's, if entered, else the calculated one. */
    long regularPayment() {
        return paymentCents > 0 ? paymentCents : calculatedPayment();
    }

    /** The standard monthly payment that pays off the original amount over the term. */
    long calculatedPayment() {
        return paymentFor(originalCents, termMonths);
    }

    /** When the first payment is due: as entered, else a month after origination (null if neither is known). */
    LocalDate firstPaymentDate() {
        return firstPayment != null ? firstPayment : origination == null ? null : origination.plusMonths(1);
    }

    /** The monthly payment that pays off owed over months payments (standard amortization). */
    long paymentFor(long owedCents, int months) {
        if (owedCents <= 0 || months <= 0) {
            return 0;
        }
        if (rateMilli <= 0) {
            return (owedCents + months - 1) / months;
        }
        BigDecimal r = monthlyRate();
        BigDecimal growth = BigDecimal.ONE.add(r).pow(months, MC);
        BigDecimal payment = BigDecimal.valueOf(owedCents).multiply(r, MC).multiply(growth, MC)
                .divide(growth.subtract(BigDecimal.ONE), MC);
        return payment.setScale(0, RoundingMode.UP).longValue();
    }

    /** When the last payment is due under the original terms (the term's last payment), or null if unknown. */
    LocalDate maturity() {
        LocalDate first = firstPaymentDate();
        return first == null || termMonths <= 0 ? null : first.plusMonths(termMonths - 1L);
    }

    /** One row of an amortization schedule. */
    record Payment(int number, LocalDate date, long paymentCents, long interestCents, long principalCents,
                   long balanceCents) {
    }

    /**
     * The payments that pay off owedCents, from firstDate on, monthly, paying
     * paymentCents each time (the last one is just what's left). Stops after
     * 1200 payments if the payment never covers the interest.
     */
    List<Payment> schedule(long owedCents, LocalDate firstDate, long paymentCents) {
        List<Payment> rows = new ArrayList<>();
        long owed = owedCents;
        for (int n = 1; owed > 0 && n <= 1200; n++) {
            long interest = monthlyInterest(owed);
            long pay = Math.min(paymentCents, owed + interest);
            if (pay <= interest && n > 1) {
                break;                                  // the payment doesn't even cover the interest
            }
            long principal = pay - interest;
            owed -= principal;
            rows.add(new Payment(n, firstDate.plusMonths(n - 1), pay, interest, principal, owed));
        }
        return rows;
    }

    private BigDecimal monthlyRate() {
        return BigDecimal.valueOf(rateMilli).divide(BigDecimal.valueOf(100_000L * 12), MC);
    }
}
