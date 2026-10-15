package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoanCalculatorTest {

    /** The reference contract: an 84-month auto loan with a 45-day first period. */
    private static LoanCalculator.Terms contract(LoanCalculator.Method method, LoanCalculator.DayCount days) {
        return new LoanCalculator.Terms(new BigDecimal("101209.03"), new BigDecimal("8.14"), 84,
                LocalDate.of(2026, 1, 6), LocalDate.of(2026, 2, 20), LoanCalculator.Frequency.MONTHLY, method, days);
    }

    private static BigDecimal money(String s) {
        return new BigDecimal(s);
    }

    @Test
    void dailyMethodOnTheReferenceContract() {
        LoanCalculator.Result r = LoanCalculator.calculate(
                contract(LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.FIXED_365));
        assertEquals(money("1589.43"), r.regularPayment());
        assertEquals(money("1589.14"), r.finalPayment());
        assertEquals(money("133511.83"), r.totalOfPayments());
        assertEquals(money("32302.80"), r.financeCharge());
        assertEquals(84, r.schedule().size());
        assertEquals(45, r.schedule().get(0).days(), "01/06 to 02/20");
        assertEquals(money("1015.70"), r.schedule().get(0).interest(), "45 days at 8.14% / 365");
        assertEquals(0, r.schedule().get(83).balance().signum(), "paid off exactly");
    }

    /**
     * The contract discloses 1,589.91 (84 equal payments, 133,552.44, finance
     * charge 32,343.41). True daily earnings at 8.14% / 365 with the 45-day
     * first period gives 1,589.43: 0.48 a month short. No day-count, rounding
     * or final-payment convention closes it (actual 365/366 gives 1,589.20,
     * 360 days 1,595.23); the disclosed payments imply a rate of about
     * 8.1494% (Reg Z APR 8.147%), so the lender's note rate or a financed
     * fee differs slightly from what the APR shows. Recorded here, not forced.
     */
    @Test
    void gapToTheContractsDisclosedPayment() {
        LoanCalculator.Result r = LoanCalculator.calculate(
                contract(LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.FIXED_365));
        BigDecimal disclosed = money("1589.91");
        assertEquals(money("-0.48"), r.regularPayment().subtract(disclosed));
        // The contract's own figures agree with each other: 84 x 1,589.91.
        assertEquals(money("133552.44"), disclosed.multiply(BigDecimal.valueOf(84)));
        assertEquals(money("32343.41"), money("133552.44").subtract(money("101209.03")));
        // With the lender's payment, the schedule pays off early: the last payment is smaller.
        LoanCalculator.Result lenders = LoanCalculator.withPayment(r.terms(), disclosed);
        assertTrue(lenders.finalPayment().compareTo(disclosed) < 0);
        assertEquals(0, lenders.schedule().get(lenders.schedule().size() - 1).balance().signum());
    }

    @Test
    void standardAmortizationComesOutLower() {
        LoanCalculator.Result standard = LoanCalculator.calculate(
                contract(LoanCalculator.Method.STANDARD_AMORTIZATION, LoanCalculator.DayCount.FIXED_365));
        LoanCalculator.Result daily = LoanCalculator.calculate(
                contract(LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.FIXED_365));
        assertEquals(money("1584.53"), standard.regularPayment());
        assertEquals(money("4.90"), daily.regularPayment().subtract(standard.regularPayment()),
                "the 45-day first period's extra interest, spread over 84 payments");
        assertEquals(0, standard.schedule().get(83).balance().signum());
    }

    @Test
    void leapYearsUnderActualDayCount() {
        LoanCalculator.Result fixed = LoanCalculator.calculate(
                contract(LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.FIXED_365));
        LoanCalculator.Result actual = LoanCalculator.calculate(
                contract(LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.ACTUAL_365_366));
        assertEquals(money("1589.20"), actual.regularPayment(), "2028 and 2032 days earn 1/366");
        assertTrue(actual.regularPayment().compareTo(fixed.regularPayment()) < 0);
    }

    @Test
    void periodSpanningNewYearIsSplitAtIt() {
        // 2027-12-15 to 2028-01-15: 17 days of 2027 (/365) and 14 of 2028 (/366).
        LoanCalculator.Terms t = new LoanCalculator.Terms(money("36600.00"), money("10"), 1,
                LocalDate.of(2027, 12, 15), LocalDate.of(2028, 1, 15), LoanCalculator.Frequency.MONTHLY,
                LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.ACTUAL_365_366);
        BigDecimal expected = money("36600").multiply(money("0.10")).multiply(
                money("17").divide(money("365"), java.math.MathContext.DECIMAL128)
                        .add(money("14").divide(money("366"), java.math.MathContext.DECIMAL128)))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertEquals(expected, LoanCalculator.calculate(t).schedule().get(0).interest());
    }

    @Test
    void monthEndPaymentDates() {
        LoanCalculator.Frequency m = LoanCalculator.Frequency.MONTHLY;
        LocalDate first = LocalDate.of(2026, 1, 31);
        assertEquals(LocalDate.of(2026, 2, 28), m.due(first, 2), "short month: its last day");
        assertEquals(LocalDate.of(2026, 3, 31), m.due(first, 3), "then back to the 31st");
        assertEquals(LocalDate.of(2026, 4, 30), m.due(first, 4));
        assertEquals(LocalDate.of(2028, 2, 29), m.due(first, 26), "leap February");
    }

    @Test
    void paymentsCoverInterestBeforePrincipal() {
        LoanCalculator.Result r = LoanCalculator.calculate(
                contract(LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.FIXED_365));
        LoanCalculator.Row first = r.schedule().get(0);
        assertEquals(first.payment(), first.interest().add(first.principal()));
        assertEquals(money("101209.03").subtract(first.principal()), first.balance());
        // Every row adds up, and the principal paid is the amount financed.
        BigDecimal principal = BigDecimal.ZERO;
        for (LoanCalculator.Row row : r.schedule()) {
            assertEquals(row.payment(), row.interest().add(row.principal()));
            principal = principal.add(row.principal());
        }
        assertEquals(money("101209.03"), principal);
    }

    @Test
    void shortPaymentLeavesInterestDueNotAddedToPrincipal() {
        LoanCalculator.Terms t = new LoanCalculator.Terms(money("10000.00"), money("12"), 3,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LoanCalculator.Frequency.MONTHLY,
                LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.FIXED_365);
        LoanCalculator.Result r = LoanCalculator.withPayment(t, money("50.00"));   // less than ~101.92 interest
        List<LoanCalculator.Row> rows = r.schedule();
        assertEquals(money("0.00"), rows.get(0).principal());
        assertEquals(money("10000.00"), rows.get(0).balance(), "principal unchanged");
        assertEquals(rows.get(1).interest(), interestOn10000(28), "next period's interest on principal only");
        assertEquals(0, rows.get(2).balance().signum(), "the last payment clears it all");
    }

    private static BigDecimal interestOn10000(int days) {
        return money("10000").multiply(money("0.12")).multiply(BigDecimal.valueOf(days))
                .divide(money("365"), 2, java.math.RoundingMode.HALF_UP);
    }

    @Test
    void zeroRateSplitsPrincipalEvenly() {
        LoanCalculator.Terms t = new LoanCalculator.Terms(money("1000.00"), BigDecimal.ZERO, 3,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LoanCalculator.Frequency.MONTHLY,
                LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.FIXED_365);
        LoanCalculator.Result r = LoanCalculator.calculate(t);
        assertEquals(money("333.34"), r.regularPayment());
        assertEquals(money("333.32"), r.finalPayment());
        assertEquals(money("0.00"), r.financeCharge());
    }

    @Test
    void extraPrincipalPaysOffEarly() {
        LoanCalculator.Terms t = contract(LoanCalculator.Method.DAILY_SIMPLE_INTEREST,
                LoanCalculator.DayCount.FIXED_365);
        LoanCalculator.Result plain = LoanCalculator.calculate(t);
        LoanCalculator.Result extra = LoanCalculator.withPayment(t, plain.regularPayment().add(money("200")));
        assertTrue(extra.schedule().size() < 84, "paid off in " + extra.schedule().size() + " payments");
        assertTrue(extra.financeCharge().compareTo(plain.financeCharge()) < 0, "less interest");
        assertEquals(0, extra.schedule().get(extra.schedule().size() - 1).balance().signum());
    }

    @Test
    void badTermsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LoanCalculator.Terms(money("1000"), money("5"), 12,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), LoanCalculator.Frequency.MONTHLY,
                LoanCalculator.Method.DAILY_SIMPLE_INTEREST, LoanCalculator.DayCount.FIXED_365));
    }
}
