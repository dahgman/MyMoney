package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
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
 * Paying off a credit card's balance: month by month, paying the minimum or
 * a fixed amount, with the interest each month and what's left. Shows how
 * long it takes and the total interest (and, for a fixed amount, how that
 * compares with paying only the minimum). No new charges are assumed.
 */
final class CardPayoffDialog extends JDialog {

    private final CreditCard card;
    private final long owed;
    private final JRadioButton minimum = new JRadioButton("Minimum payment each month");
    private final JRadioButton fixed = new JRadioButton("A fixed amount each month: $");
    private final JTextField fixedField = new JTextField(8);
    private final JLabel summary = new JLabel(" ");
    private final Model model = new Model();
    private List<Loan.Payment> rows = List.of();

    CardPayoffDialog(Window owner, String name, CreditCard card, long owedCents) {
        super(owner, "Payoff Schedule" + (name.isEmpty() ? "" : ": " + name), ModalityType.APPLICATION_MODAL);
        this.card = card;
        this.owed = owedCents;

        JLabel terms = new JLabel("Owed today: " + Money.format(owed) + " at " + Loan.formatRate(card.aprMilli())
                + " APR" + (card.hasMinimumRule() ? ".    Minimum payment now: " + Money.format(card.minimumPayment(owed))
                        : ".") + "    (Assumes no new charges.)");
        ButtonGroup g = new ButtonGroup();
        g.add(minimum);
        g.add(fixed);
        minimum.setEnabled(card.hasMinimumRule());
        minimum.setToolTipText(card.hasMinimumRule() ? null : "Enter the card's minimum payment rule first");
        (card.hasMinimumRule() ? minimum : fixed).setSelected(true);
        long suggested = Math.max(card.minimumPayment(owed) * 2, Math.min(owed, 10_000));
        fixedField.setText(java.math.BigDecimal.valueOf(suggested, 2).toPlainString());
        fixedField.setHorizontalAlignment(JTextField.RIGHT);
        minimum.addActionListener(e -> recalculate());
        fixed.addActionListener(e -> recalculate());
        fixedField.addActionListener(e -> {
            fixed.setSelected(true);
            recalculate();
        });
        JButton apply = new JButton("Recalculate");
        apply.addActionListener(e -> recalculate());

        JPanel minRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        minRow.add(minimum);
        JPanel fixedRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        fixedRow.add(fixed);
        fixedRow.add(fixedField);
        fixedRow.add(apply);
        JPanel options = new JPanel(new GridLayout(2, 1, 0, 4));
        options.add(minRow);
        options.add(fixedRow);
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
        setSize(780, 560);
        setLocationRelativeTo(owner);
    }

    private void recalculate() {
        long amount = 0;
        if (fixed.isSelected()) {
            try {
                amount = Math.abs(Money.parse(fixedField.getText()));
            } catch (IllegalArgumentException e) {
                summary.setText(e.getMessage());
                return;
            }
        }
        LocalDate first = CreditCard.nextDay(card.dueDay(), LocalDate.now().plusDays(1));
        if (first == null) {
            first = LocalDate.now().plusMonths(1);
        }
        rows = card.payoff(owed, first, amount);
        model.fireTableDataChanged();

        boolean paidOff = !rows.isEmpty() && rows.get(rows.size() - 1).balanceCents() == 0;
        long interest = rows.stream().mapToLong(Loan.Payment::interestCents).sum();
        String text;
        if (!paidOff) {
            text = rows.isEmpty() ? "That payment doesn't even cover the interest: the balance would never go down."
                    : "Still not paid off after " + rows.size() + " months: the payment barely covers the interest.";
        } else {
            text = "Paid off in " + months(rows.size()) + " (last payment " + rows.get(rows.size() - 1).date()
                    .format(LedgerTableModel.DISPLAY_DATE) + "), with " + Money.format(interest) + " in interest.";
            if (fixed.isSelected() && card.hasMinimumRule()) {
                List<Loan.Payment> min = card.payoff(owed, first, 0);
                if (!min.isEmpty() && min.get(min.size() - 1).balanceCents() == 0) {
                    long minInterest = min.stream().mapToLong(Loan.Payment::interestCents).sum();
                    text += "  Paying only the minimum: " + months(min.size()) + " and "
                            + Money.format(minInterest) + " in interest.";
                }
            }
        }
        summary.setText("<html>" + text + "</html>");
    }

    /** "3 years 4 months" */
    private static String months(int n) {
        int y = n / 12;
        int m = n % 12;
        String years = y == 0 ? "" : y + (y == 1 ? " year" : " years");
        String months = m == 0 ? "" : m + (m == 1 ? " month" : " months");
        return years.isEmpty() ? months : months.isEmpty() ? years : years + " " + months;
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
