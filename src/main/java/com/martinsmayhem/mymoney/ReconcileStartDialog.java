package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.LocalDate;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

/**
 * The first step of Reconcile Account: the statement's ending balance and
 * date. The statement's period runs from the day after the account was last
 * reconciled through this date: with "As of" ticked (as it starts), all of
 * its unreconciled transactions are selected (marked Reconciling); without
 * it, just its Cleared ones. Transactions after the date are left as they are.
 */
final class ReconcileStartDialog extends JDialog {

    private final JTextField endingField = new JTextField(14);
    private final JCheckBox asOfBox = new JCheckBox("As of:");
    private final DatePicker datePicker;
    private final DatePicker startPicker;
    private boolean accepted;
    private long endingCents;
    private LocalDate statementDate;
    private LocalDate startDate;

    /**
     * @param ending the ending balance to show, or null for none yet
     * @param periodStart the statement start date to suggest (see ReconcileRepository.statementStart), or null;
     *                    it can be changed
     * @param asOf whether "As of" starts ticked
     */
    ReconcileStartDialog(Window owner, Account account, long beginningCents, Long ending, LocalDate date,
                         LocalDate periodStart, boolean asOf) {
        super(owner, "Reconcile Account: " + account.name(), ModalityType.APPLICATION_MODAL);
        datePicker = new DatePicker(date);
        asOfBox.setSelected(asOf);
        startPicker = new DatePicker(periodStart);

        JLabel title = new JLabel("Reconcile Account: " + account.name(), SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 6));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 12, 0);
        form.add(title, c);

        c = cell(0, 1);
        c.anchor = GridBagConstraints.LINE_START;
        form.add(new JLabel("Beginning Statement Balance:"), c);
        c = cell(1, 1);
        c.anchor = GridBagConstraints.LINE_END;
        form.add(new JLabel(Money.format(beginningCents)), c);

        c = cell(0, 2);
        c.anchor = GridBagConstraints.LINE_END;
        form.add(new JLabel("End Statement Balance:"), c);
        endingField.setHorizontalAlignment(JTextField.RIGHT);
        if (ending != null) {
            endingField.setText(Money.format(ending));
        }
        c = cell(1, 2);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(endingField, c);

        c = cell(0, 3);
        c.anchor = GridBagConstraints.LINE_END;
        form.add(new JLabel("Statement Start Date:"), c);
        c = cell(1, 3);
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        startPicker.setToolTipText("The first day of the statement. Suggested: the day after the last"
                + " reconciliation. Blank: from the first transaction.");
        form.add(startPicker, c);

        c = cell(0, 4);
        c.anchor = GridBagConstraints.LINE_END;
        asOfBox.setToolTipText("Select every unreconciled transaction in the period, through this date");
        form.add(asOfBox, c);
        datePicker.setToolTipText("The statement date (also names the statement's attachment folder)");
        c = cell(1, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(datePicker, c);

        JLabel help = new JLabel("<html><div style='width:420px'>With 'As of' ticked, every unreconciled"
                + " transaction from the statement start date through the statement date is marked"
                + " 'Reconciling'; without it, only the Cleared ones. Each counts on the date the bank posted"
                + " it (its Bank Date), or if that isn't known, its date. Transactions after the statement date"
                + " keep their status. You should still check each one against your statement.</div></html>");
        c = cell(0, 5);
        c.gridwidth = 2;
        c.insets = new Insets(10, 0, 0, 0);
        form.add(help, c);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> accept());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        pack();
        setLocationRelativeTo(owner);
    }

    boolean wasAccepted() {
        return accepted;
    }

    long endingCents() {
        return endingCents;
    }

    LocalDate statementDate() {
        return statementDate;
    }

    /** The statement start date chosen; null (left blank) for from the first transaction. */
    LocalDate startDate() {
        return startDate;
    }

    boolean asOf() {
        return asOfBox.isSelected();
    }

    private void accept() {
        try {
            if (endingField.getText().isBlank()) {
                throw new IllegalArgumentException("Enter the ending balance from your statement.");
            }
            endingCents = Money.parseTyped(endingField.getText());
            statementDate = datePicker.getDate();
            startDate = startPicker.getDateOrNull();
            if (startDate != null && statementDate.isBefore(startDate)) {
                throw new IllegalArgumentException("The statement date can't be before the statement start date ("
                        + startDate.format(LedgerTableModel.DISPLAY_DATE) + ").");
            }
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
            return;
        }
        accepted = true;
        dispose();
    }

    private static GridBagConstraints cell(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.insets = new Insets(3, 0, 3, 8);
        return c;
    }
}
