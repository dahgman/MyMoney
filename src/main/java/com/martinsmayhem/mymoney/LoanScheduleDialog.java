package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * A loan's amortization schedule: every payment, with the days it covers,
 * split into interest and principal, and the balance left after it.
 *
 * Either the original contract (the amount borrowed, from the contract date
 * to the first payment and on; see LoanCalculator), worked out either way so
 * the two can be compared: standard amortization, or simple interest accrued
 * daily; or what's left from today's balance. Extra principal each month
 * shows how much sooner the loan is paid off, and the interest saved.
 */
final class LoanScheduleDialog extends JDialog {

    private final Loan loan;
    private final long owedNow;
    private final JRadioButton original = new JRadioButton("Original contract");
    private final JRadioButton fromToday = new JRadioButton("From today's balance");
    private final DatePicker contractDate;
    private final DatePicker firstPayment;
    private final JComboBox<LoanCalculator.Method> method = new JComboBox<>(LoanCalculator.Method.values());
    private final JComboBox<LoanCalculator.DayCount> dayCount = new JComboBox<>(LoanCalculator.DayCount.values());
    private final JTextField extraField = new JTextField("0.00", 8);
    private final JLabel summary = new JLabel(" ");
    private final Model model = new Model();
    private List<LoanCalculator.Row> rows = List.of();

    /** @param owedNow what is owed today (positive), or 0 if not known (a new account) */
    LoanScheduleDialog(Window owner, String name, Loan loan, long owedNow) {
        super(owner, "Amortization Schedule" + (name.isEmpty() ? "" : ": " + name), ModalityType.APPLICATION_MODAL);
        this.loan = loan;
        this.owedNow = owedNow;
        LocalDate made = loan.origination() != null ? loan.origination() : LocalDate.now();
        LocalDate first = loan.firstPaymentDate() != null && loan.firstPaymentDate().isAfter(made)
                ? loan.firstPaymentDate() : made.plusMonths(1);
        contractDate = new DatePicker(made);
        firstPayment = new DatePicker(first);
        method.setSelectedItem(loan.paymentMethod());
        dayCount.setToolTipText("The days in a year for the daily rate (APR ÷ days)");

        JLabel terms = new JLabel(Money.format(loan.originalCents()) + " at " + Loan.formatRate(loan.rateMilli())
                + " for " + loan.termMonths() + " monthly payments"
                + (loan.paymentCents() > 0 ? "; the lender's payment " + Money.format(loan.paymentCents()) : "")
                + (owedNow > 0 ? ".    Owed today: " + Money.format(owedNow) : "."));
        ButtonGroup g = new ButtonGroup();
        g.add(original);
        g.add(fromToday);
        fromToday.setEnabled(owedNow > 0);
        original.setSelected(true);
        original.addActionListener(e -> recalculate());
        fromToday.addActionListener(e -> recalculate());
        method.addActionListener(e -> recalculate());
        dayCount.addActionListener(e -> recalculate());
        contractDate.onChange(this::recalculateLater);
        firstPayment.onChange(this::recalculateLater);
        extraField.setHorizontalAlignment(JTextField.RIGHT);
        extraField.addActionListener(e -> recalculate());
        extraField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                recalculate();
            }
        });
        JButton apply = new JButton("Recalculate");
        apply.addActionListener(e -> recalculate());

        JPanel which = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        which.add(original);
        which.add(fromToday);
        JPanel dates = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        dates.add(new JLabel("Contract date:"));
        dates.add(contractDate);
        dates.add(new JLabel("   First payment:"));
        dates.add(firstPayment);
        JPanel how = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        how.add(new JLabel("Interest:"));
        how.add(method);
        how.add(dayCount);
        JPanel extraRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        extraRow.add(new JLabel("Extra principal each month: $"));
        extraRow.add(extraField);
        extraRow.add(apply);
        JPanel options = new JPanel(new GridLayout(4, 1, 0, 4));
        options.add(which);
        options.add(dates);
        options.add(how);
        options.add(extraRow);
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        top.add(terms, BorderLayout.NORTH);
        top.add(options, BorderLayout.CENTER);
        top.add(summary, BorderLayout.SOUTH);

        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = 2; c < model.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(right);
        }
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(2).setPreferredWidth(50);
        ColumnFit.install(table);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(close);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8),
                scroll.getBorder()));
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(apply);
        recalculate();
        setSize(820, 620);
        setLocationRelativeTo(owner);
    }

    /** After a date field's text changes (not while it's still changing). */
    private void recalculateLater() {
        javax.swing.SwingUtilities.invokeLater(this::recalculate);
    }

    private void recalculate() {
        boolean contract = original.isSelected();
        contractDate.setEnabled(contract);
        firstPayment.setEnabled(contract);
        method.setEnabled(contract);
        dayCount.setEnabled(contract && method.getSelectedItem() == LoanCalculator.Method.DAILY_SIMPLE_INTEREST);
        long extra;
        try {
            extra = extraField.getText().isBlank() ? 0 : Math.abs(Money.parseTyped(extraField.getText()));
        } catch (IllegalArgumentException e) {
            summary.setText(e.getMessage());
            return;
        }
        try {
            summary.setText("<html>" + (contract ? contractSchedule(extra) : todaySchedule(extra)) + "</html>");
        } catch (IllegalArgumentException e) {
            rows = List.of();
            summary.setText(e.getMessage());
        }
        model.fireTableDataChanged();
    }

    /** The original contract, worked out the chosen way; returns the summary. */
    private String contractSchedule(long extra) {
        LoanCalculator.Terms t = new LoanCalculator.Terms(BigDecimal.valueOf(loan.originalCents(), 2),
                BigDecimal.valueOf(loan.rateMilli(), 3), loan.termMonths(), contractDate.getDate(),
                firstPayment.getDate(), LoanCalculator.Frequency.MONTHLY,
                (LoanCalculator.Method) method.getSelectedItem(), (LoanCalculator.DayCount) dayCount.getSelectedItem());
        LoanCalculator.Result calc = LoanCalculator.calculate(t);
        // The lender's payment, if entered, is what's paid; else the calculated one.
        BigDecimal regular = loan.paymentCents() > 0 ? BigDecimal.valueOf(loan.paymentCents(), 2) : calc.regularPayment();
        LoanCalculator.Result plain = LoanCalculator.withPayment(t, regular);
        LoanCalculator.Result shown = extra == 0 ? plain
                : LoanCalculator.withPayment(t, regular.add(BigDecimal.valueOf(extra, 2)));
        rows = shown.schedule();

        StringBuilder s = new StringBuilder();
        s.append("Regular payment: <b>").append(money(shown.regularPayment())).append("</b>");
        if (loan.paymentCents() > 0 && calc.regularPayment().compareTo(regular) != 0) {
            s.append(" (the lender's; calculated: ").append(money(calc.regularPayment())).append(")");
        }
        s.append(".   Final payment: ").append(money(shown.finalPayment())).append(" on ")
         .append(rows.get(rows.size() - 1).date().format(LedgerTableModel.DISPLAY_DATE)).append(".<br>")
         .append("Total of payments: <b>").append(money(shown.totalOfPayments())).append("</b>.   Finance charge: <b>")
         .append(money(shown.financeCharge())).append("</b>.");
        // The other method, for comparison.
        LoanCalculator.Method other = t.method() == LoanCalculator.Method.DAILY_SIMPLE_INTEREST
                ? LoanCalculator.Method.STANDARD_AMORTIZATION : LoanCalculator.Method.DAILY_SIMPLE_INTEREST;
        BigDecimal otherPayment = LoanCalculator.solvePayment(t.with(other));
        BigDecimal diff = calc.regularPayment().subtract(otherPayment);
        s.append("<br>").append(other).append(" would give ").append(money(otherPayment)).append(" a month (")
         .append(diff.signum() == 0 ? "the same" : money(diff.abs()) + (diff.signum() > 0 ? " less" : " more"))
         .append(").");
        if (extra > 0) {
            s.append(savings(plain.schedule(), shown.schedule(), extra));
        }
        return s.toString();
    }

    /** From today's balance at the regular payment; returns the summary. */
    private String todaySchedule(long extra) {
        long payment = loan.regularPayment();
        LocalDate first = nextPaymentDate();
        List<LoanCalculator.Row> plain = rows(loan.schedule(owedNow, first, payment), first.minusMonths(1));
        rows = extra == 0 ? plain : rows(loan.schedule(owedNow, first, payment + extra), first.minusMonths(1));
        if (rows.isEmpty()) {
            return "Nothing is owed.";
        }
        BigDecimal interest = rows.stream().map(LoanCalculator.Row::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        String text = rows.size() + " payments, the last on "
                + rows.get(rows.size() - 1).date().format(LedgerTableModel.DISPLAY_DATE)
                + "; total interest " + money(interest) + ".";
        return extra > 0 ? text + savings(plain, rows, extra) : text;
    }

    /** "With $X extra a month: paid off N payments sooner, saving $Y in interest." */
    private static String savings(List<LoanCalculator.Row> plain, List<LoanCalculator.Row> extra, long extraCents) {
        BigDecimal saved = interest(plain).subtract(interest(extra));
        int sooner = plain.size() - extra.size();
        return "<br>With " + Money.format(extraCents) + " extra a month: paid off " + sooner
                + (sooner == 1 ? " payment" : " payments") + " sooner, saving " + money(saved) + " in interest.";
    }

    private static BigDecimal interest(List<LoanCalculator.Row> rows) {
        return rows.stream().map(LoanCalculator.Row::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Loan's own schedule rows (in cents) as calculator rows, with the days each covers. */
    private static List<LoanCalculator.Row> rows(List<Loan.Payment> payments, LocalDate before) {
        List<LoanCalculator.Row> out = new ArrayList<>();
        LocalDate previous = before;
        for (Loan.Payment p : payments) {
            out.add(new LoanCalculator.Row(p.number(), p.date(), ChronoUnit.DAYS.between(previous, p.date()),
                    BigDecimal.valueOf(p.paymentCents(), 2), BigDecimal.valueOf(p.interestCents(), 2),
                    BigDecimal.valueOf(p.principalCents(), 2), BigDecimal.valueOf(p.balanceCents(), 2)));
            previous = p.date();
        }
        return out;
    }

    private static String money(BigDecimal amount) {
        return Money.format(amount.movePointRight(2).longValueExact());
    }

    /** The next monthly payment date after today, on the first payment's day of the month. */
    private LocalDate nextPaymentDate() {
        LocalDate today = LocalDate.now();
        LocalDate first = loan.firstPaymentDate() == null ? today : loan.firstPaymentDate();
        LocalDate d = first;
        for (int n = 1; !d.isAfter(today); n++) {
            d = first.plusMonths(n);
        }
        return d;
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"#", "Date", "Days", "Payment", "Interest", "Principal", "Balance"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            LoanCalculator.Row p = rows.get(row);
            return switch (column) {
                case 0 -> String.valueOf(p.number());
                case 1 -> p.date().format(LedgerTableModel.DISPLAY_DATE);
                case 2 -> String.valueOf(p.days());
                case 3 -> money(p.payment());
                case 4 -> money(p.interest());
                case 5 -> money(p.principal());
                case 6 -> money(p.balance());
                default -> "";
            };
        }
    }
}
