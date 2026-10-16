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
 * the account with that number is suggested. If none has it (or there are
 * no accounts yet), a new account named from the file ("WF Checking 2222":
 * the bank, the account type, the number's last four digits) is chosen,
 * made only if the import goes ahead; New Account... makes one in the
 * account form instead. A file that doesn't name its account (QIF) can go
 * into any account, or a new "Imported Account".
 *
 * Also chosen here: whether the downloaded file is then left, deleted or
 * moved into MyMoney's folder (see ImportFileChoice).
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
    /** The account to make for the import, listed until it's made (id 0); null if none is offered. */
    private final Account newAccount;
    private Account chosen;                              // after accept: the account (made, if new)
    private final ImportFileChoice afterImport;

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
        // No account has the file's number (or there are no accounts yet): offer one made from the file.
        List<Account> existing;
        try {
            existing = accounts.findAll();
        } catch (SQLException e) {
            existing = List.of();
        }
        newAccount = suggested != null ? null
                : new Account(0, genericName(fileAccount, existing), accountType(fileAccount), 0, null, false, null);
        loadAccounts(suggested != null ? Long.valueOf(suggested.id())
                : newAccount != null && (hasNumber || existing.isEmpty()) ? Long.valueOf(0)
                : selected == null ? null : selected.id());
        accountCombo.addActionListener(e -> accountChanged());
        accountCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Object shown = value == newAccount && newAccount != null ? "New account: " + newAccount.name() : value;
                return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
            }
        });
        afterImport = new ImportFileChoice(config.getString(AppConfig.IMPORT_FILE_ACTION, ImportFileChoice.LEAVE));

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

        JLabel afterLabel = new JLabel("Afterwards, the downloaded file is:");
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(10, 0, 0, 0);
        form.add(afterLabel, c);
        c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        form.add(afterImport, c);

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

    /** The account chosen: after Review, a new one made for the import is the real account. */
    Account account() {
        return chosen != null ? chosen : (Account) accountCombo.getSelectedItem();
    }

    /** Whether the account was made for this import (so the tree needs to show it). */
    boolean madeAccount() {
        return chosen != null && newAccount != null && chosen.name().equals(newAccount.name());
    }

    /** What to do with the downloaded file afterwards: "leave", "delete" or "move" (see ImportFileChoice). */
    String fileAction() {
        return afterImport.choice();
    }

    /**
     * A name for an account made from a file: its bank, type and the last four
     * digits of its number ("WF Checking 2222"), or "Imported Account"; with
     * " (2)" and so on if an account has it already.
     */
    static String genericName(FileAccount f, List<Account> existing) {
        String base;
        if (f == null || (!f.hasNumber() && (f.bankName() == null || f.bankName().isBlank()))) {
            base = "Imported Account";
        } else {
            String bank = f.bankName() == null ? "" : f.bankName().trim();
            String digits = f.hasNumber() ? f.accountNumber().replaceAll("\\D", "") : "";
            String last4 = digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
            base = ((bank.isEmpty() ? "" : bank + " ") + accountType(f) + (last4.isEmpty() ? "" : " " + last4)).trim();
        }
        String name = base;
        for (int n = 2; taken(name, existing); n++) {
            name = base + " (" + n + ")";
        }
        return name;
    }

    private static boolean taken(String name, List<Account> existing) {
        return existing.stream().anyMatch(a -> a.name().equalsIgnoreCase(name));
    }

    /** The account type a file says (OFX ACCTTYPE), or Checking. */
    private static AccountType accountType(FileAccount f) {
        return f != null && f.type() != null ? f.type() : AccountType.CHECKING;
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
            if (newAccount != null) {
                accountCombo.addItem(newAccount);       // made only if the import goes ahead
                if (selectId != null && selectId == 0) {
                    accountCombo.setSelectedItem(newAccount);
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
        if (a != null && a == newAccount) {
            suggestionLabel.setText("<html>A new account, \"" + newAccount.name() + "\", is made for this file"
                    + (hasNumber ? ", with its account number" : "") + ".<br>Rename it any time in Manage Accounts.</html>");
        } else if (!hasNumber) {
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
        if (hasNumber && a != null && a != newAccount) {
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
        if (a == newAccount) {
            try {
                a = makeNewAccount();
            } catch (SQLException e) {
                error("Could not create the account:\n" + e.getMessage());
                return;
            }
        }
        chosen = a;
        try {
            config.setString(AppConfig.IMPORT_FILE_ACTION, afterImport.choice());   // the same next time
        } catch (SQLException ignored) {
            // used for this import anyway
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

    /** Makes the account offered for the file: its name, type, bank and numbers. */
    private Account makeNewAccount() throws SQLException {
        long id = accounts.insert(newAccount.name(), newAccount.type(), 0, null);
        if (fileAccount != null && fileAccount.hasNumber()) {
            accounts.setNumbers(id, fileAccount.accountNumber(), fileAccount.routingNumber());
        }
        if (fileAccount != null && fileAccount.bankName() != null && !fileAccount.bankName().isBlank()) {
            AccountRepository.Details d = accounts.findDetails(id);
            accounts.saveDetails(new AccountRepository.Details(d.id(), d.name(), d.type(), d.parentId(),
                    fileAccount.bankName().trim(), d.startDate(), d.openingBalanceCents(), d.balanceAdjustmentCents(),
                    d.accountNumber(), d.routingNumber(), d.defaultCategory(), d.comments(), d.website(), d.loan(),
                    d.card()));
        }
        return accounts.findAll().stream().filter(x -> x.id() == id).findFirst().orElseThrow();
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
