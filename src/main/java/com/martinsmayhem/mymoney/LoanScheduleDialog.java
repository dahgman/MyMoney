package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.time.LocalDate;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
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
 * A loan's amortization schedule: every monthly payment, split into interest
 * and principal, with the balance left after it.
 *
 * Either the original schedule (the amount borrowed, from the origination
 * date) or what's left from today's balance. Extra principal each month
 * shows how much sooner the loan is paid off, and the interest saved.
 */
final class LoanScheduleDialog extends JDialog {

    private final Loan loan;
    private final long owedNow;
    private final JRadioButton original = new JRadioButton("Original schedule");
    private final JRadioButton fromToday = new JRadioButton("From today's balance");
    private final JTextField extraField = new JTextField("0.00", 8);
    private final JLabel summary = new JLabel(" ");
    private final Model model = new Model();
    private List<Loan.Payment> rows = List.of();

    /** @param owedNow what is owed today (positive), or 0 if not known (a new account) */
    LoanScheduleDialog(Window owner, String name, Loan loan, long owedNow) {
        super(owner, "Amortization Schedule" + (name.isEmpty() ? "" : ": " + name), ModalityType.APPLICATION_MODAL);
        this.loan = loan;
        this.owedNow = owedNow;

        JLabel terms = new JLabel(Money.format(loan.originalCents()) + " at " + Loan.formatRate(loan.rateMilli())
                + " for " + loan.termMonths() + " months: " + Money.format(loan.regularPayment()) + " a month"
                + (loan.paymentCents() > 0 ? " (lender's)" : "")
                + (loan.maturity() == null ? "" : ", maturing " + loan.maturity().format(LedgerTableModel.DISPLAY_DATE))
                + (owedNow > 0 ? ".    Owed today: " + Money.format(owedNow) : "."));
        ButtonGroup g = new ButtonGroup();
        g.add(original);
        g.add(fromToday);
        fromToday.setEnabled(owedNow > 0);
        (owedNow > 0 ? fromToday : original).setSelected(true);
        original.addActionListener(e -> recalculate());
        fromToday.addActionListener(e -> recalculate());
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
        JPanel extraRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        extraRow.add(new JLabel("Extra principal each month: $"));
        extraRow.add(extraField);
        extraRow.add(apply);
        JPanel options = new JPanel(new java.awt.GridLayout(2, 1, 0, 4));
        options.add(which);
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
        for (int c = 2; c < 6; c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(right);
        }
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
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
        setSize(760, 560);
        setLocationRelativeTo(owner);
    }

    private void recalculate() {
        long extra;
        try {
            extra = extraField.getText().isBlank() ? 0 : Math.abs(Money.parse(extraField.getText()));
        } catch (IllegalArgumentException e) {
            summary.setText(e.getMessage());
            return;
        }
        long payment = loan.regularPayment();
        long owed;
        LocalDate first;
        if (fromToday.isSelected()) {
            owed = owedNow;
            first = nextPaymentDate();
        } else {
            owed = loan.originalCents();
            first = loan.firstPaymentDate() == null ? LocalDate.now().plusMonths(1) : loan.firstPaymentDate();
        }
        List<Loan.Payment> plain = loan.schedule(owed, first, payment);
        rows = extra == 0 ? plain : loan.schedule(owed, first, payment + extra);
        model.fireTableDataChanged();

        long interest = rows.stream().mapToLong(Loan.Payment::interestCents).sum();
        String text = rows.isEmpty() ? "Nothing is owed."
                : rows.size() + " payments, the last on " + rows.get(rows.size() - 1).date().format(LedgerTableModel.DISPLAY_DATE)
                        + "; total interest " + Money.format(interest) + ".";
        if (extra > 0 && !plain.isEmpty()) {
            long saved = plain.stream().mapToLong(Loan.Payment::interestCents).sum() - interest;
            int sooner = plain.size() - rows.size();
            text += "  With " + Money.format(extra) + " extra a month: paid off " + sooner
                    + (sooner == 1 ? " payment" : " payments") + " sooner, saving " + Money.format(saved) + " in interest.";
        }
        summary.setText(text);
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
        private final String[] columns = {"#", "Date", "Payment", "Interest", "Principal", "Balance"};

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
            Loan.Payment p = rows.get(row);
            return switch (column) {
                case 0 -> String.valueOf(p.number());
                case 1 -> p.date().format(LedgerTableModel.DISPLAY_DATE);
                case 2 -> Money.format(p.paymentCents());
                case 3 -> Money.format(p.interestCents());
                case 4 -> Money.format(p.principalCents());
                case 5 -> Money.format(p.balanceCents());
                default -> "";
            };
        }
    }
}
