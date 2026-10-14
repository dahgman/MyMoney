package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Edits one transaction, or enters a new one, in a dialog (used by Reconcile
 * Account, where there is no ledger row to type into). Also lists the
 * transaction's attachments. The status isn't changed here.
 */
final class TransactionDialog extends JDialog {

    private final Account account;
    private final LedgerEntry entry;             // a copy; saved only on OK
    private final boolean isNew;
    private final TransactionRepository transactions;
    private final AttachmentRepository attachments;
    private final List<Path> pendingFiles = new ArrayList<>();   // a new transaction's files, attached on save

    private final DatePicker date;
    private final JTextField number = new JTextField(8);
    private final JComboBox<String> payee;
    private final JComboBox<String> category;
    private final JComboBox<String> tag;
    private final JTextField description = new JTextField(30);
    private final JTextField memo = new JTextField(30);
    private final JTextField payment = new JTextField(10);
    private final JTextField deposit = new JTextField(10);
    private boolean saved;

    /**
     * @param existing the transaction to edit, or null for a new one
     */
    TransactionDialog(Window owner, Account account, LedgerEntry existing,
                      TransactionRepository transactions, AttachmentRepository attachments,
                      Supplier<List<String>> payees, Supplier<List<String>> categories,
                      Supplier<List<String>> tags) {
        super(owner, existing == null ? "New Transaction" : "Edit Transaction", ModalityType.APPLICATION_MODAL);
        this.account = account;
        this.isNew = existing == null;
        this.transactions = transactions;
        this.attachments = attachments;
        if (existing == null) {
            entry = new LedgerEntry();
            entry.date = LocalDate.now();
            entry.category = account.defaultCategory();     // the user can change or clear it
        } else {
            entry = existing.copy();
        }

        date = new DatePicker(entry.date);
        payee = combo(payees.get(), entry.payee);
        category = combo(categories.get(), entry.category);
        tag = combo(tags.get(), entry.tag);
        number.setText(text(entry.checkNumber));
        description.setText(text(entry.description));
        memo.setText(text(entry.memo));
        if (entry.amountCents < 0) {
            payment.setText(Money.format(-entry.amountCents));
        } else if (entry.amountCents > 0) {
            deposit.setText(Money.format(entry.amountCents));
        }
        Money.calculateOnExit(payment);                 // "12.50+3.25" becomes "$15.75"
        Money.calculateOnExit(deposit);
        boolean split = TransactionRepository.SPLIT_LABEL.equals(entry.category);
        if (split) {                                // a split's amount and categories aren't edited here
            category.setEnabled(false);
            payment.setEnabled(false);
            deposit.setEnabled(false);
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        int row = 0;
        row = addRow(form, row, "Date:", date);
        row = addRow(form, row, "Check #:", number);
        row = addRow(form, row, "Payee:", payee);
        row = addRow(form, row, "Category:", category);
        row = addRow(form, row, "Tag:", tag);
        row = addRow(form, row, "Description:", description);
        row = addRow(form, row, "Memo:", memo);
        row = addRow(form, row, "Payment:", payment);
        row = addRow(form, row, "Deposit:", deposit);

        AttachmentsPanel files = new AttachmentsPanel("Attachments", attachmentTarget(), () -> { });
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = 1;
        c.insets = new Insets(8, 0, 0, 0);
        form.add(files, c);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> save());
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

    /** True if OK saved the transaction. */
    boolean wasSaved() {
        return saved;
    }

    /** The saved transaction's id (also for a new one, once saved). */
    long transactionId() {
        return entry.id;
    }

    private void save() {
        try {
            entry.date = date.getDate();
            if (!TransactionRepository.SPLIT_LABEL.equals(entry.category)) {
                String p = payment.getText().trim();
                String d = deposit.getText().trim();
                if (!p.isEmpty() && !d.isEmpty()) {
                    throw new IllegalArgumentException("Enter a payment or a deposit, not both.");
                }
                entry.amountCents = !p.isEmpty() ? -Money.parseTyped(p) : !d.isEmpty() ? Money.parseTyped(d) : 0;
                String cat = comboText(category);
                entry.category = cat.isEmpty() ? null
                        : TransactionRepository.isTransfer(cat) ? cat : CategoryRepository.normalize(cat);
            }
        } catch (IllegalArgumentException ex) {
            error(ex.getMessage());
            return;
        }
        entry.checkNumber = number.getText().trim();
        entry.payee = comboText(payee);
        String t = comboText(tag);
        entry.tag = t.isEmpty() ? null : TagRepository.normalize(t);
        entry.description = description.getText().trim();
        entry.memo = memo.getText().trim();
        try {
            if (isNew) {
                transactions.insert(account.id(), entry);
            } else {
                transactions.update(entry);
            }
        } catch (SQLException ex) {
            error("Could not save the transaction:\n" + ex.getMessage());
            return;
        }
        saved = true;
        for (Path f : pendingFiles) {
            try {
                attachments.addToTransaction(account, entry.id, entry.date, f);
            } catch (SQLException | IOException ex) {
                error("The transaction was saved, but \"" + f.getFileName() + "\" could not be attached:\n"
                        + ex.getMessage());
            }
        }
        dispose();
    }

    /** Saved transaction: files are attached right away. New one: they wait until it is saved. */
    private AttachmentsPanel.Target attachmentTarget() {
        return new AttachmentsPanel.Target() {
            @Override
            public List<AttachmentRepository.Attachment> list() throws SQLException {
                if (!isNew) {
                    return attachments.forTransaction(entry.id);
                }
                List<AttachmentRepository.Attachment> list = new ArrayList<>();
                for (Path f : pendingFiles) {
                    list.add(new AttachmentRepository.Attachment(0, f.getFileName().toString(), f.toString()));
                }
                return list;
            }

            @Override
            public void add(Path file) throws SQLException, IOException {
                if (isNew) {
                    pendingFiles.add(file);
                } else {
                    attachments.addToTransaction(account, entry.id, entry.date, file);
                }
            }

            @Override
            public void remove(AttachmentRepository.Attachment a) throws SQLException, IOException {
                if (isNew) {
                    pendingFiles.removeIf(f -> f.toString().equals(a.path()));
                } else {
                    attachments.remove(a, account);
                }
            }

            @Override
            public Path file(AttachmentRepository.Attachment a) {
                return isNew ? Path.of(a.path()) : attachments.file(a, account);
            }
        };
    }

    private static int addRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_END;
        c.insets = new Insets(2, 0, 2, 6);
        form.add(new JLabel(label), c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
        form.add(field, c);
        return row + 1;
    }

    /** An editable drop-down: pick from the list or type something new. */
    private static JComboBox<String> combo(List<String> items, String value) {
        JComboBox<String> combo = new JComboBox<>(items.toArray(String[]::new));
        combo.setEditable(true);
        combo.setSelectedItem(text(value));
        return combo;
    }

    private static String comboText(JComboBox<String> combo) {
        Object v = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return v == null ? "" : v.toString().trim();
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
    }
}
