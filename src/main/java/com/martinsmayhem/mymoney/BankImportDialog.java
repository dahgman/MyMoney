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
import java.util.function.Function;
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
 * Before a bank download (QIF or QFX / OFX) is imported: which account it
 * goes into, and whether to match existing transactions and within how many
 * days. The matching choices are saved as preferences (also in Edit >
 * Preferences).
 *
 * If the file names its account (QFX / OFX: account and routing numbers),
 * the account with that number is suggested; New Account... creates one
 * with the file's numbers filled in. Otherwise every account is offered,
 * with Add Account... to make a new one.
 */
final class BankImportDialog extends JDialog {

    /** The account a file says it is for (from OFX); fields may be null. */
    record FileAccount(String accountNumber, String routingNumber, String bankName, AccountType type) {

        static FileAccount of(OfxParser.OfxFile ofx) {
            return new FileAccount(ofx.accountNumber, ofx.routingNumber, ofx.bankName, ofx.accountType);
        }

        boolean hasNumber() {
            return accountNumber != null && !accountNumber.isBlank();
        }
    }

    private final AppConfig config;
    private final AccountRepository accounts;
    private final FileAccount fileAccount;               // null for a QIF file
    private final Function<AccountRepository.Details, Long> createAccount;
    private final JComboBox<Account> accountCombo = new JComboBox<>();
    private final JLabel suggestionLabel = new JLabel(" ");
    private final JCheckBox rememberBox = new JCheckBox();
    private final JCheckBox matchBox = new JCheckBox("Match existing transactions");
    private final JSpinner daysSpinner;
    private Account suggested;
    private boolean accepted;

    /**
     * @param fileAccount   the account the file names, or null (QIF files don't)
     * @param selected      the account selected in the tree (the choice when nothing is suggested)
     * @param createAccount opens the new-account form filled in from a template; returns the new
     *                      account's id, or null if none was created
     */
    BankImportDialog(Window owner, String fileName, List<DownloadImporter.Download> downloads,
                     FileAccount fileAccount, AccountRepository accounts, Account selected, AppConfig config,
                     Function<AccountRepository.Details, Long> createAccount) {
        super(owner, "Import " + fileName, ModalityType.APPLICATION_MODAL);
        this.config = config;
        this.accounts = accounts;
        this.fileAccount = fileAccount;
        this.createAccount = createAccount;
        boolean hasNumber = fileAccount != null && fileAccount.hasNumber();

        try {
            suggested = hasNumber
                    ? accounts.suggestForImport(fileAccount.accountNumber(), fileAccount.routingNumber()) : null;
        } catch (SQLException e) {
            suggested = null;
        }
        loadAccounts(suggested != null ? suggested.id() : selected == null ? null : selected.id());
        accountCombo.addActionListener(e -> accountChanged());

        matchBox.setSelected(config.getBoolean(AppConfig.MATCH_DOWNLOADS, true));
        daysSpinner = new JSpinner(new SpinnerNumberModel(
                config.getInt(AppConfig.MATCH_DAYS, AppConfig.DEFAULT_MATCH_DAYS), 0, 60, 1));
        daysSpinner.setEnabled(matchBox.isSelected());
        matchBox.addActionListener(e -> daysSpinner.setEnabled(matchBox.isSelected()));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        int row = 0;
        row = add(form, row, "File:", new JLabel(fileName));
        row = add(form, row, "Transactions:", new JLabel(summary(downloads)));

        JButton newButton = new JButton(hasNumber ? "New Account..." : "Add Account...");
        newButton.setToolTipText(hasNumber ? "Create an account with this file's account and routing numbers"
                : "Create an account to import into");
        newButton.addActionListener(e -> newAccount());
        JPanel accountRow = new JPanel(new BorderLayout(6, 0));
        accountRow.add(accountCombo, BorderLayout.CENTER);
        accountRow.add(newButton, BorderLayout.EAST);
        row = add(form, row, "Import into:", accountRow);
        row = add(form, row, "", suggestionLabel);
        row = add(form, row, "", rememberBox);
        rememberBox.setSelected(true);

        JPanel matchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        matchRow.add(matchBox);
        matchRow.add(new JLabel(" within "));
        matchRow.add(daysSpinner);
        matchRow.add(new JLabel(" days"));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(10, 0, 0, 0);
        form.add(matchRow, c);

        JLabel help = new JLabel("<html><div style='width:400px'>Next, you review each downloaded"
                + " transaction. Possible matches are existing transactions with the same amount, dated"
                + " within that many days, that aren't already reconciled or cleared; the payee most like"
                + " the bank's name comes first. Accepting a match keeps its payee, puts the bank's memo in"
                + " its memo, and marks it Cleared. Otherwise it can be added as a new, Cleared transaction,"
                + " with the bank's name as the payee (or one you choose) and its memo as the memo."
                + " Transactions imported before are left out.</div></html>");
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.insets = new Insets(8, 0, 0, 0);
        form.add(help, c);

        JButton ok = new JButton("Review...");
        ok.addActionListener(e -> accept());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(ok);
        buttons.add(cancel);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        accountChanged();
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

    // ----- Accounts ----------------------------------------------------------

    private void loadAccounts(Long selectId) {
        accountCombo.removeAllItems();
        try {
            for (Account a : accounts.findAll()) {
                accountCombo.addItem(a);
                if (selectId != null && a.id() == selectId) {
                    accountCombo.setSelectedItem(a);
                }
            }
        } catch (SQLException e) {
            error("Could not read the accounts:\n" + e.getMessage());
        }
    }

    /** Updates the note under the account, and whether to offer remembering the file's number. */
    private void accountChanged() {
        Account a = account();
        boolean hasNumber = fileAccount != null && fileAccount.hasNumber();
        if (!hasNumber) {
            suggestionLabel.setText("This file doesn't say which account it is for. Choose one, or add one.");
        } else if (suggested != null && a != null && a.id() == suggested.id()) {
            suggestionLabel.setText("");                 // the suggestion is chosen: nothing to say
        } else if (suggested != null) {
            suggestionLabel.setText("Suggested account: " + suggested.name() + " (its account number matches).");
        } else {
            suggestionLabel.setText("No account has this account number. Choose one, or create a new account.");
        }
        suggestionLabel.setVisible(!suggestionLabel.getText().isEmpty());

        // Offer to store the file's numbers on an account that has none, so it's suggested next time.
        boolean offer = false;
        if (hasNumber && a != null) {
            try {
                AccountRepository.Details d = accounts.findDetails(a.id());
                offer = d != null && (d.accountNumber() == null || d.accountNumber().isBlank());
            } catch (SQLException e) {
                offer = false;
            }
        }
        rememberBox.setVisible(offer);
        if (offer) {
            rememberBox.setText("Save this file's account number to \"" + a.name()
                    + "\" (so it's suggested next time)");
        }
        pack();
    }

    /** Opens the new-account form (with the file's numbers, if it has them) and selects what it creates. */
    private void newAccount() {
        AccountRepository.Details template = ManageAccountsDialog.BLANK;
        if (fileAccount != null) {
            AccountRepository.Details b = ManageAccountsDialog.BLANK;
            template = new AccountRepository.Details(0, "",
                    fileAccount.type() != null ? fileAccount.type() : b.type(), null, fileAccount.bankName(),
                    null, 0, 0, fileAccount.accountNumber(), fileAccount.routingNumber(), null, null, null,
                    AccountRepository.LoanTerms.NONE, AccountRepository.CardTerms.NONE);
        }
        Long id = createAccount.apply(template);
        if (id != null) {
            loadAccounts(id);
            accountChanged();
        }
    }

    private void accept() {
        Account a = account();
        if (a == null) {
            error("Choose the account to import into, or add one.");
            return;
        }
        if (rememberBox.isVisible() && rememberBox.isSelected()) {
            try {
                accounts.setNumbers(a.id(), fileAccount.accountNumber(), fileAccount.routingNumber());
            } catch (SQLException e) {
                error("The account number could not be saved, but the import will continue:\n" + e.getMessage());
            }
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

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
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

    private static int add(JPanel form, int row, String label, java.awt.Component field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_END;
        c.insets = new Insets(2, 0, 2, 8);
        form.add(new JLabel(label), c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        form.add(field, c);
        return row + 1;
    }
}
