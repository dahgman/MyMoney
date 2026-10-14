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
 * date. With "As of" ticked, every unreconciled transaction dated on or
 * before that date is selected (marked Reconciling) to start with.
 */
final class ReconcileStartDialog extends JDialog {

    private final JTextField endingField = new JTextField(14);
    private final JCheckBox asOfBox = new JCheckBox("As of:");
    private final DatePicker datePicker;
    private boolean accepted;
    private long endingCents;
    private LocalDate statementDate;

    /**
     * @param ending the ending balance to show, or null for none yet
     */
    ReconcileStartDialog(Window owner, Account account, long beginningCents, Long ending, LocalDate date) {
        super(owner, "Reconcile Account: " + account.name(), ModalityType.APPLICATION_MODAL);
        datePicker = new DatePicker(date);

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
        asOfBox.setToolTipText("Select every unreconciled transaction dated on or before this date");
        form.add(asOfBox, c);
        datePicker.setToolTipText("The statement date (also names the statement's attachment folder)");
        c = cell(1, 3);
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(datePicker, c);

        JLabel help = new JLabel("<html><div style='width:420px'>Cleared transactions are always selected to"
                + " start with. Selecting the 'As of' option will also select every unreconciled transaction"
                + " dated on or before the given date and mark it as 'Reconciling'. This often makes"
                + " reconciling easier, but you should still check each transaction against your"
                + " statement.</div></html>");
        c = cell(0, 4);
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
