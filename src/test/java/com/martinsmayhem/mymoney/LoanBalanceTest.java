package com.martinsmayhem.mymoney;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A loan's balance is what's left to pay, interest included (as lenders show
 * it), whichever way its interest is charged.
 */
class LoanBalanceTest {

    @TempDir
    Path dir;
    private Database db;
    private AccountRepository accounts;
    private TransactionRepository transactions;

    // Windows won't let @TempDir delete a database file that is still open.
    @AfterEach
    void tearDown() {
        db.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        db = Database.create(dir.resolve("T.MyMoney"), "password1");
        accounts = new AccountRepository(db);
        transactions = new TransactionRepository(db);
    }

    /** The 84-month auto loan: $101,209.03 at 8.14%, made 01/06/2026, first payment 02/20/2026. */
    private long autoLoan(Loan.InterestMethod method) throws Exception {
        long id = accounts.insert("Car", AccountType.LOAN, -10_120_903, null);
        accounts.saveDetails(new AccountRepository.Details(id, "Car", AccountType.LOAN, null, "Credit Union",
                LocalDate.of(2026, 1, 6), -10_120_903, 0, null, null, null, null, null,
                new AccountRepository.LoanTerms(8_140, 10_120_903, 84, null, 0, LocalDate.of(2026, 2, 20), method),
                AccountRepository.CardTerms.NONE));
        transactions.recalculateAllLoans();             // as saving the loan's details does
        return id;
    }

    private long remaining(long id, LocalDate on) throws Exception {
        return -accounts.balanceThrough(id, on);
    }

    @ParameterizedTest
    @EnumSource(Loan.InterestMethod.class)
    void aNewLoanOwesItsPaymentsInterestIncluded(Loan.InterestMethod method) throws Exception {
        long id = autoLoan(method);
        Loan loan = accounts.findLoan(id);
        long remaining = remaining(id, LocalDate.of(2026, 1, 6));
        assertTrue(remaining > 10_120_903 + 3_000_000, method + ": interest is included: " + Money.format(remaining));
        // Everything but the last payment is the regular one; the last is what's left. (On the due
        // dates, Affirm-style, the payment is the standard one, which this loan's 45-day first
        // period outlasts: an 85th, smaller payment finishes it.)
        long regular = loan.regularPayment();
        int payments = method == Loan.InterestMethod.DUE_DATES ? 85 : 84;
        assertTrue(remaining > (payments - 1) * regular && remaining <= payments * regular,
                method + ": " + Money.format(remaining) + " is " + (payments - 1) + " payments of "
                        + Money.format(regular) + " and a last");
        assertEquals(10_120_903, accounts.principalOwed(id, LocalDate.of(2026, 1, 6)), "principal is still the loan");
    }

    @Test
    void dailyLoanOwesWhatTheCalculatorSays() throws Exception {
        long id = autoLoan(Loan.InterestMethod.DAILY);
        LoanCalculator.Result r = LoanCalculator.calculate(accounts.findLoan(id)
                .contract(LoanCalculator.Method.DAILY_SIMPLE_INTEREST));
        assertEquals(r.totalOfPayments().movePointRight(2).longValueExact(), remaining(id, LocalDate.of(2026, 1, 6)),
                "the 45-day first period counts");
        assertEquals(new BigDecimal("133511.83"), r.totalOfPayments());
    }

    @ParameterizedTest
    @EnumSource(Loan.InterestMethod.class)
    void eachPaymentLowersItByItsFullAmount(Loan.InterestMethod method) throws Exception {
        long id = autoLoan(method);
        long before = remaining(id, LocalDate.of(2026, 1, 6));
        long payment = accounts.findLoan(id).regularPayment();
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 2, 20);
        e.amountCents = payment;
        transactions.insert(id, e);
        assertEquals(before - payment, remaining(id, LocalDate.of(2026, 2, 20)), method.toString());
        LedgerEntry paid = transactions.findByAccount(id).stream().filter(x -> x.amountCents == payment)
                .findFirst().orElseThrow();
        assertTrue(paid.interestCents > 0 && paid.netCents() == payment, "interest shown beside it, not taken out");
    }

    @Test
    void affirmViairStillMatchesToTheCent() throws Exception {
        long id = accounts.insert("Affirm - viair", AccountType.LOAN, -80_000, null);
        accounts.saveDetails(new AccountRepository.Details(id, "Affirm - viair", AccountType.LOAN, null, "Affirm",
                LocalDate.of(2026, 7, 6), -80_000, 7_119, null, null, null, null, null,
                new AccountRepository.LoanTerms(35_970, 80_000, 18, null, 0, null, Loan.InterestMethod.DUE_DATES),
                AccountRepository.CardTerms.NONE));
        for (LocalDate d : List.of(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 9), LocalDate.of(2026, 10, 6))) {
            LedgerEntry e = new LedgerEntry();
            e.date = d;
            e.amountCents = 5_816;
            transactions.insert(id, e);
        }
        assertEquals(75_391, remaining(id, LocalDate.of(2026, 10, 6)), "Affirm: remaining $753.91");
        assertEquals(17_448, accounts.loanPaid(id, LocalDate.of(2026, 10, 6))[0], "Affirm: paid to date $174.48");
    }

    /** The ledger as the user sees it, for typing into. */
    private LedgerTableModel ledger(long id) throws Exception {
        LedgerTableModel model = new LedgerTableModel(transactions, m -> {
            throw new AssertionError(m);
        }, q -> true, () -> { });
        model.setAccount(accounts.findAll().stream().filter(a -> a.id() == id).findFirst().orElseThrow());
        return model;
    }

    private static int rowOn(LedgerTableModel model, LocalDate date) {
        for (int r = 0; r < model.getRowCount() - 1; r++) {
            if (model.getValueAt(r, LedgerTableModel.DATE).equals(date.format(LedgerTableModel.DISPLAY_DATE))) {
                return r;
            }
        }
        throw new AssertionError("no row on " + date);
    }

    /** Types into the blank row: date, then one more column. */
    private static void newRow(LedgerTableModel model, LocalDate date, int column, String amount) {
        int draft = model.getRowCount() - 1;
        model.setValueAt(date.format(LedgerTableModel.DISPLAY_DATE), draft, LedgerTableModel.DATE);
        model.setValueAt(amount, model.getRowCount() - 1, column);
    }

    @Test
    void principalOnlyPayment() throws Exception {
        long id = autoLoan(Loan.InterestMethod.DAILY);
        LedgerTableModel model = ledger(id);
        long before = remaining(id, LocalDate.of(2026, 1, 20));
        newRow(model, LocalDate.of(2026, 1, 20), LedgerTableModel.PRINCIPAL, "1000.00");
        int row = rowOn(model, LocalDate.of(2026, 1, 20));
        assertEquals("$1,000.00", model.getValueAt(row, LedgerTableModel.DEPOSIT), "the payment is the principal");
        assertEquals("", model.getValueAt(row, LedgerTableModel.INTEREST), "no interest in it");
        assertEquals("$1,000.00", model.getValueAt(row, LedgerTableModel.PRINCIPAL));
        assertTrue(model.isManualInterest(row));
        assertEquals(10_020_903, accounts.principalOwed(id, LocalDate.of(2026, 1, 20)), "principal down $1,000");
        long after = remaining(id, LocalDate.of(2026, 1, 20));
        assertTrue(before - after > 100_000, "paid $1,000, and the interest still to come fell too: "
                + Money.format(before - after));
        // The interest built up meanwhile is still due with the first regular payment, from 01/06.
        LedgerEntry first = new LedgerEntry();
        first.date = LocalDate.of(2026, 2, 20);
        first.amountCents = accounts.findLoan(id).regularPayment();
        transactions.insert(id, first);
        long firstInterest = transactions.findByAccount(id).stream().filter(x -> x.date.equals(first.date))
                .findFirst().orElseThrow().interestCents;
        long expected = Math.round(10_120_903 * 0.0814 / 365 * 14 + 10_020_903 * 0.0814 / 365 * 31);
        assertEquals(expected, firstInterest, 1, "14 days on the whole loan, then 31 on what's left");
    }

    @Test
    void interestOnlyPayment() throws Exception {
        long id = autoLoan(Loan.InterestMethod.DAILY);
        LedgerTableModel model = ledger(id);
        newRow(model, LocalDate.of(2026, 1, 31), LedgerTableModel.INTEREST, "560.00");
        int row = rowOn(model, LocalDate.of(2026, 1, 31));
        assertEquals("$560.00", model.getValueAt(row, LedgerTableModel.DEPOSIT));
        assertEquals("$560.00", model.getValueAt(row, LedgerTableModel.INTEREST));
        assertEquals("$0.00", model.getValueAt(row, LedgerTableModel.PRINCIPAL));
        assertEquals(10_120_903, accounts.principalOwed(id, LocalDate.of(2026, 1, 31)), "principal unchanged");
    }

    @Test
    void typingOverACalculatedSplitAndClearingIt() throws Exception {
        long id = autoLoan(Loan.InterestMethod.DAILY);
        long payment = accounts.findLoan(id).regularPayment();
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 2, 20);
        e.amountCents = payment;
        transactions.insert(id, e);
        LedgerTableModel model = ledger(id);
        int row = rowOn(model, e.date);
        Object calculated = model.getValueAt(row, LedgerTableModel.INTEREST);
        assertEquals("$1,015.70", calculated, "45 days of interest");

        model.setValueAt("1000.00", row, LedgerTableModel.INTEREST);       // the lender's own figure
        row = rowOn(model, e.date);
        assertEquals("$1,000.00", model.getValueAt(row, LedgerTableModel.INTEREST));
        assertEquals(Money.format(payment - 100_000), model.getValueAt(row, LedgerTableModel.PRINCIPAL),
                "the payment stays; principal is the rest");
        assertEquals(Money.format(payment), model.getValueAt(row, LedgerTableModel.DEPOSIT));

        model.setValueAt("", row, LedgerTableModel.PRINCIPAL);             // cleared: calculated again
        row = rowOn(model, e.date);
        assertEquals(calculated, model.getValueAt(row, LedgerTableModel.INTEREST));
        assertTrue(!model.isManualInterest(row));
    }

    @Test
    void moreThanThePaymentIsRefused() throws Exception {
        long id = autoLoan(Loan.InterestMethod.DAILY);
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 2, 20);
        e.amountCents = 50_000;
        transactions.insert(id, e);
        String[] error = new String[1];
        LedgerTableModel model = new LedgerTableModel(transactions, m -> error[0] = m, q -> true, () -> { });
        model.setAccount(accounts.findAll().stream().filter(a -> a.id() == id).findFirst().orElseThrow());
        model.setValueAt("600.00", rowOn(model, e.date), LedgerTableModel.PRINCIPAL);
        assertTrue(error[0] != null && error[0].contains("more than the payment"), String.valueOf(error[0]));
    }

    @Test
    void notEditableInOtherAccounts() throws Exception {
        long card = accounts.insert("Visa", AccountType.CREDIT_CARD, 0, null);
        LedgerTableModel model = ledger(card);
        assertTrue(!model.isCellEditable(0, LedgerTableModel.INTEREST));
    }

    @Test
    void principalBalanceRunsBesideTheBalance() throws Exception {
        long id = autoLoan(Loan.InterestMethod.DAILY);
        LedgerTableModel model = ledger(id);
        newRow(model, LocalDate.of(2026, 1, 20), LedgerTableModel.PRINCIPAL, "1000.00");   // principal only
        long payment = accounts.findLoan(id).regularPayment();
        LedgerEntry e = new LedgerEntry();
        e.date = LocalDate.of(2026, 2, 20);
        e.amountCents = payment;
        transactions.insert(id, e);
        model.setAccount(accounts.findAll().stream().filter(a -> a.id() == id).findFirst().orElseThrow());

        int charge = 0;                                  // the scheduled interest, on 01/06
        assertEquals("-$101,209.03", model.getValueAt(charge, LedgerTableModel.PRINCIPAL_BALANCE),
                "the scheduled interest isn't principal");
        assertEquals("-$100,209.03", model.getValueAt(rowOn(model, LocalDate.of(2026, 1, 20)),
                LedgerTableModel.PRINCIPAL_BALANCE), "down by the $1,000");
        int feb = rowOn(model, LocalDate.of(2026, 2, 20));
        String principalPart = (String) model.getValueAt(feb, LedgerTableModel.PRINCIPAL);
        long expected = -10_020_903 + Money.parse(principalPart.replace("$", "").replace(",", ""));
        assertEquals(Money.format(expected), model.getValueAt(feb, LedgerTableModel.PRINCIPAL_BALANCE),
                "down by that payment's principal");
        assertEquals(-expected, accounts.principalOwed(id, LocalDate.of(2026, 2, 20)),
                "the same as the top panel's Principal Remaining");
    }

    @Test
    void withoutDatesTheBalanceIsPrincipal() throws Exception {
        long id = accounts.insert("Old loan", AccountType.LOAN, -500_000, null);
        accounts.saveDetails(new AccountRepository.Details(id, "Old loan", AccountType.LOAN, null, null, null,
                -500_000, 0, null, null, null, null, null,
                new AccountRepository.LoanTerms(6_000, 500_000, 60, null, 0, null, Loan.InterestMethod.MONTHLY),
                AccountRepository.CardTerms.NONE));
        transactions.recalculateAllLoans();
        assertEquals(500_000, remaining(id, LocalDate.of(2026, 10, 15)), "no dates, no schedule: principal");
    }
}
