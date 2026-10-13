package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

/**
 * Ties a scheduled payment to the debt it pays: an existing loan or credit
 * card account, or a new one made here from the schedule. The schedule's
 * category becomes that account ("[Honda Credit]"), so each payment is a
 * transfer that lowers the debt's balance (for a loan, with the interest
 * split out), and the debt snowball uses it as the debt's planned payment.
 * Payments already in the ledger aren't changed.
 */
final class LinkDebtDialog extends JDialog {

    private final ScheduleRepository.Schedule schedule;
    private final AccountRepository accounts;
    private final ScheduleRepository schedules;

    private final JRadioButton existing = new JRadioButton("An existing debt account:");
    private final JRadioButton create = new JRadioButton("A new debt account:");
    private final JComboBox<Account> debtCombo = new JComboBox<>();
    private final JTextField nameField = new JTextField(22);
    private final JComboBox<AccountType> typeCombo =
            new JComboBox<>(new AccountType[] {AccountType.LOAN, AccountType.CREDIT_CARD});
    private final JTextField owedField = new JTextField(12);
    private final JTextField rateField = new JTextField(7);
    private final List<JComponent> newFields = new ArrayList<>();
    private Long linkedAccountId;

    LinkDebtDialog(Window owner, ScheduleRepository.Schedule schedule, String fromAccount,
                   AccountRepository accounts, ScheduleRepository schedules) throws SQLException {
        super(owner, "Link to Debt", ModalityType.APPLICATION_MODAL);
        this.schedule = schedule;
        this.accounts = accounts;
        this.schedules = schedules;

        for (Account a : accounts.findAll()) {
            if ((a.type() == AccountType.LOAN || a.type() == AccountType.CREDIT_CARD) && a.id() != schedule.accountId()) {
                debtCombo.addItem(a);
                if (("[" + a.name() + "]").equalsIgnoreCase(String.valueOf(schedule.category()))) {
                    debtCombo.setSelectedItem(a);
                }
            }
        }
        ButtonGroup g = new ButtonGroup();
        g.add(existing);
        g.add(create);
        existing.setEnabled(debtCombo.getItemCount() > 0);
        (debtCombo.getItemCount() > 0 && matchesPayee() ? existing : create).setSelected(true);
        nameField.setText(schedule.payee() == null ? "" : schedule.payee());
        owedField.setHorizontalAlignment(JTextField.RIGHT);
        rateField.setHorizontalAlignment(JTextField.RIGHT);
        existing.addActionListener(e -> updateEnabled());
        create.addActionListener(e -> updateEnabled());

        JLabel what = new JLabel("<html><b>" + (schedule.payee() == null ? "(no payee)" : schedule.payee()) + "</b>: "
                + Money.format(-schedule.amountCents()) + " " + schedule.recurrence().describe().toLowerCase()
                + " from " + fromAccount + "</html>");

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        int row = 0;
        row = span(form, row, what, 0);
        row = span(form, row, new JLabel("Which debt does it pay?"), 10);
        row = span(form, row, existing, 6);
        row = field(form, row, "", debtCombo, null);
        row = span(form, row, create, 8);
        row = field(form, row, "Name:", nameField, newFields);
        row = field(form, row, "Type:", typeCombo, newFields);
        row = field(form, row, "Owed today:", owedField, newFields);
        JPanel rateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rateRow.add(rateField);
        rateRow.add(new JLabel("% a year (interest rate or APR)"));
        row = field(form, row, "Rate:", rateRow, newFields);
        newFields.add(rateField);
        JLabel note = new JLabel("<html><div style='width:380px'>The schedule's category becomes the debt account, so"
                + " each payment lowers its balance (for a loan, the interest is split out to an interest category)."
                + " Payments already in the ledger aren't changed. More details (lender, limit, minimum payment, term)"
                + " can be added later with Account &gt; Edit Account.</div></html>");
        span(form, row, note, 10);

        JButton ok = new JButton("Link");
        ok.addActionListener(e -> link());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        updateEnabled();
        pack();
        setLocationRelativeTo(owner);
    }

    /** The debt account the schedule was linked to, or null if it wasn't. */
    Long linkedAccountId() {
        return linkedAccountId;
    }

    /** True if the selected existing account looks like this schedule's (its category, or same name as the payee). */
    private boolean matchesPayee() {
        Account a = (Account) debtCombo.getSelectedItem();
        return a != null && (("[" + a.name() + "]").equalsIgnoreCase(String.valueOf(schedule.category()))
                || a.name().equalsIgnoreCase(String.valueOf(schedule.payee())));
    }

    private void updateEnabled() {
        debtCombo.setEnabled(existing.isSelected());
        for (JComponent c : newFields) {
            c.setEnabled(create.isSelected());
        }
    }

    private void link() {
        if (schedule.amountCents() >= 0) {
            error("Only payments (money going out) can be linked to a debt.");
            return;
        }
        Account debt;
        try {
            if (existing.isSelected()) {
                debt = (Account) debtCombo.getSelectedItem();
                if (debt == null) {
                    error("Choose the debt account.");
                    return;
                }
            } else {
                debt = createAccount();
                if (debt == null) {
                    return;
                }
            }
            ScheduleRepository.Schedule s = schedule;
            schedules.save(new ScheduleRepository.Schedule(s.id(), s.accountId(), s.payee(), "[" + debt.name() + "]",
                    s.tag(), s.description(), s.memo(), s.amountCents(), s.recurrence()));
        } catch (SQLException e) {
            error("Could not link the schedule:\n" + e.getMessage());
            return;
        }
        linkedAccountId = debt.id();
        dispose();
    }

    /** Makes the new debt account from the form, or returns null (after saying why). */
    private Account createAccount() throws SQLException {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            error("Enter a name for the debt account.");
            return null;
        }
        if (accounts.nameExists(name)) {
            error("An account named \"" + name + "\" already exists. Choose it above, or use another name.");
            return null;
        }
        long owed;
        int rate;
        try {
            owed = owedField.getText().isBlank() ? 0 : Math.abs(Money.parse(owedField.getText()));
            rate = Loan.parseRate(rateField.getText());
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return null;
        }
        AccountType type = (AccountType) typeCombo.getSelectedItem();
        long id = accounts.insert(name, type, -owed, null);          // owed: a negative balance
        accounts.saveDetails(new AccountRepository.Details(id, name, type, null, schedule.payee(), LocalDate.now(),
                -owed, 0, null, null, null, null, null,
                type == AccountType.LOAN ? new AccountRepository.LoanTerms(rate, 0, 0, null,
                        -schedule.amountCents(), null)
                        : AccountRepository.LoanTerms.NONE,
                type == AccountType.CREDIT_CARD ? new AccountRepository.CardTerms(rate, 0, 0, 0, 0, 0)
                        : AccountRepository.CardTerms.NONE));
        for (Account a : accounts.findAll()) {
            if (a.id() == id) {
                return a;
            }
        }
        return null;
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
    }

    private static int span(JPanel form, int row, JComponent c, int top) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.gridwidth = 2;
        g.anchor = GridBagConstraints.LINE_START;
        g.insets = new Insets(top, 0, 2, 0);
        form.add(c, g);
        return row + 1;
    }

    private static int field(JPanel form, int row, String label, JComponent c, List<JComponent> group) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row;
        g.anchor = GridBagConstraints.LINE_END;
        g.insets = new Insets(2, 24, 2, 8);
        JLabel l = new JLabel(label);
        form.add(l, g);
        g = new GridBagConstraints();
        g.gridx = 1;
        g.gridy = row;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.insets = new Insets(2, 0, 2, 0);
        form.add(c, g);
        if (group != null) {
            group.add(c);
            group.add(l);
        }
        return row + 1;
    }
}
