package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Before a bank download (QIF or QFX) is imported: which account it goes
 * into, and whether to match existing transactions and within how many days.
 * The matching choices are saved as preferences (also in Edit > Preferences).
 */
final class BankImportDialog extends JDialog {

    private final AppConfig config;
    private final JComboBox<Account> accountCombo;
    private final JCheckBox matchBox = new JCheckBox("Match existing transactions");
    private final JSpinner daysSpinner;
    private boolean accepted;

    BankImportDialog(Window owner, String fileName, List<DownloadImporter.Download> downloads,
                     List<Account> accounts, Account selected, AppConfig config) {
        super(owner, "Import " + fileName, ModalityType.APPLICATION_MODAL);
        this.config = config;

        accountCombo = new JComboBox<>(accounts.toArray(Account[]::new));
        if (selected != null) {
            accountCombo.setSelectedItem(selected);
        }
        matchBox.setSelected(config.getBoolean(AppConfig.MATCH_DOWNLOADS, true));
        daysSpinner = new JSpinner(new SpinnerNumberModel(
                config.getInt(AppConfig.MATCH_DAYS, AppConfig.DEFAULT_MATCH_DAYS), 0, 60, 1));
        daysSpinner.setEnabled(matchBox.isSelected());
        matchBox.addActionListener(e -> daysSpinner.setEnabled(matchBox.isSelected()));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        int row = 0;
        add(form, row++, new JLabel("File:"), new JLabel(fileName));
        add(form, row++, new JLabel("Transactions:"), new JLabel(summary(downloads)));
        add(form, row++, new JLabel("Import into:"), accountCombo);

        JPanel days = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        days.add(new JLabel("within"));
        days.add(daysSpinner);
        days.add(new JLabel("days"));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(10, 0, 0, 0);
        JPanel matchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        matchRow.add(matchBox);
        matchRow.add(days);
        form.add(matchRow, c);

        JLabel help = new JLabel("<html><div style='width:380px'>A downloaded transaction matches an"
                + " existing one with the same amount, dated within that many days, that isn't already"
                + " reconciled or cleared. A match keeps its payee, gets the bank's description in its"
                + " memo, and is marked Cleared. Anything that doesn't match is added as a new, Cleared"
                + " transaction. Transactions imported before are skipped.</div></html>");
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.insets = new Insets(8, 0, 0, 0);
        form.add(help, c);

        JButton ok = new JButton("Import");
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

    Account account() {
        return (Account) accountCombo.getSelectedItem();
    }

    boolean match() {
        return matchBox.isSelected();
    }

    int days() {
        return (Integer) daysSpinner.getValue();
    }

    private void accept() {
        if (account() == null) {
            JOptionPane.showMessageDialog(this, "Choose the account to import into.", getTitle(),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {                                   // remembered for next time
            config.setBoolean(AppConfig.MATCH_DOWNLOADS, match());
            config.setInt(AppConfig.MATCH_DAYS, days());
        } catch (SQLException ignored) {
            // the import still uses what was chosen here
        }
        accepted = true;
        dispose();
    }

    /** "42, from 09/01/2026 to 09/30/2026" */
    private static String summary(List<DownloadImporter.Download> downloads) {
        if (downloads.isEmpty()) {
            return "none";
        }
        LocalDate first = downloads.stream().map(DownloadImporter.Download::date).min(LocalDate::compareTo).get();
        LocalDate last = downloads.stream().map(DownloadImporter.Download::date).max(LocalDate::compareTo).get();
        return downloads.size() + ", from " + first.format(LedgerTableModel.DISPLAY_DATE)
                + " to " + last.format(LedgerTableModel.DISPLAY_DATE);
    }

    private static void add(JPanel form, int row, JLabel label, java.awt.Component field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_END;
        c.insets = new Insets(2, 0, 2, 8);
        form.add(label, c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        form.add(field, c);
    }
}
