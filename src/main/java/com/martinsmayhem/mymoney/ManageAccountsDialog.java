package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

/**
 * Account > Manage Accounts (also Edit Account and New Account): every
 * account in a list on the left; the selected account's details on the
 * right, to edit and save. New Account (the button under the list, or the
 * menu item) empties the form for a new account, which Save creates.
 *
 * Parent Account: a top-level account's "parent" is the data file itself, so
 * the drop-down's first entry is the file's name. Choosing an account there
 * instead makes this one its envelope (sub-account). An account that has
 * envelopes stays top-level.
 */
final class ManageAccountsDialog extends JDialog {

    private final AccountRepository accounts;
    private final AttachmentRepository attachments;
    private final String fileName;

    private final DefaultListModel<Account> listModel = new DefaultListModel<>();
    private final JList<Account> list = new JList<>(listModel);

    private final JTextField nameField = new JTextField(24);
    private final JComboBox<AccountType> typeCombo = new JComboBox<>(AccountType.values());
    private final JComboBox<Object> parentCombo = new JComboBox<>();    // the file name, or an Account
    private final JTextField bankField = new JTextField(24);
    private final DatePicker startDate = new DatePicker(null);
    private final JTextField openingField = new JTextField(12);
    private final JTextField adjustmentField = new JTextField(12);
    private final JTextField accountNumberField = new JTextField(20);
    private final JTextField routingNumberField = new JTextField(12);
    private final JComboBox<String> categoryCombo = new JComboBox<>();
    private final JTextField websiteField = new JTextField(24);
    private final JTextArea commentsArea = new JTextArea(4, 24);

    /** What the form starts as for a new account (id 0 = not saved yet). */
    private static final AccountRepository.Details BLANK = new AccountRepository.Details(0, "",
            AccountType.CHECKING, null, null, null, 0, 0, null, null, null, null, null);

    private final JLabel formTitle = new JLabel(" ");
    private AccountRepository.Details loaded;     // as saved (or BLANK), to notice unsaved changes
    private Long lastSavedId;                      // the account saved last, to select afterwards
    private boolean hasChildren;
    private boolean changed;                       // anything saved while open
    private boolean switching;                     // true while the list selection is put back

    /**
     * @param select   the account to show first (null: the first one)
     * @param startNew true to start with the form empty for a new account
     */
    ManageAccountsDialog(Window owner, AccountRepository accounts, AttachmentRepository attachments,
                         List<String> categoryPaths, Path dataFile, Account select, boolean startNew) {
        super(owner, "Manage Accounts", ModalityType.APPLICATION_MODAL);
        this.accounts = accounts;
        this.attachments = attachments;
        this.fileName = dataFile.getFileName().toString();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                if (value instanceof Account a) {
                    setIcon(AccountIcon.of(a));
                    setText(a.name());
                    setBorder(BorderFactory.createEmptyBorder(1, a.parentId() == null ? 2 : 22, 1, 2));
                }
                return this;
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !switching) {
                selectionChanged();
            }
        });

        categoryCombo.setEditable(true);
        categoryCombo.addItem("");
        for (String c : categoryPaths) {
            categoryCombo.addItem(c);
        }
        commentsArea.setLineWrap(true);
        commentsArea.setWrapStyleWord(true);
        openingField.setHorizontalAlignment(JTextField.RIGHT);
        adjustmentField.setHorizontalAlignment(JTextField.RIGHT);
        parentCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                if (value instanceof Account a) {
                    setIcon(AccountIcon.of(a));
                } else if (value != null) {
                    setText(value + "  (the file: top-level account)");
                }
                return this;
            }
        });
        parentCombo.addActionListener(e -> parentChanged());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        int row = 0;
        row = addRow(form, row, "Account Name:", nameField);
        row = addRow(form, row, "Account Type:", typeCombo);
        row = addRow(form, row, "Parent Account:", parentCombo);
        row = addRow(form, row, "Bank Name:", bankField);
        row = addRow(form, row, "Start Date:", startDate);
        row = addRow(form, row, "Initial Balance:", openingField);
        row = addRow(form, row, "Balance Adjustment:", adjustmentField);
        row = addRow(form, row, "Account Number:", accountNumberField);
        row = addRow(form, row, "Routing Number:", routingNumberField);
        row = addRow(form, row, "Default Category:", categoryCombo);
        row = addRow(form, row, "Website:", websiteField);
        JScrollPane comments = new JScrollPane(commentsArea);
        row = addRow(form, row, "Comments:", comments);
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row;
        filler.weighty = 1;
        form.add(new JPanel(), filler);

        adjustmentField.setToolTipText("Added to the initial balance, without a transaction");
        categoryCombo.setToolTipText("Filled in on every new transaction in this account; it can still be changed");

        JButton save = new JButton("Save");
        save.addActionListener(e -> save());
        JButton revert = new JButton("Revert");
        revert.addActionListener(e -> fill(loaded));
        JPanel formButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        formButtons.add(revert);
        formButtons.add(save);
        formTitle.setFont(formTitle.getFont().deriveFont(java.awt.Font.BOLD, formTitle.getFont().getSize2D() + 2));
        formTitle.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
        JPanel right = new JPanel(new BorderLayout());
        right.add(formTitle, BorderLayout.NORTH);
        right.add(new JScrollPane(form), BorderLayout.CENTER);
        right.add(formButtons, BorderLayout.SOUTH);

        JButton newButton = new JButton("New Account");
        newButton.addActionListener(e -> startNewAccount());
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftButtons.add(newButton);
        left.add(leftButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(220);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> close());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(closeButton);

        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);

        loadList(select == null ? null : select.id());
        if (startNew) {
            startNewAccount();
        }
        setSize(820, 600);
        setLocationRelativeTo(owner);
    }

    /** True if anything was saved. */
    boolean wasChanged() {
        return changed;
    }

    /** The id of the account saved last (a new one, if one was created), or null. */
    Long lastSavedId() {
        return lastSavedId;
    }

    /** Empties the form for a new account (after asking about unsaved changes). */
    private void startNewAccount() {
        if (loaded != null && !resolveUnsaved()) {
            return;
        }
        switching = true;
        list.clearSelection();
        switching = false;
        fill(BLANK);
        nameField.requestFocusInWindow();
    }

    // ----- The list --------------------------------------------------------

    /** Top-level accounts by name, each followed by its envelopes. */
    private void loadList(Long selectId) {
        List<Account> all;
        try {
            all = accounts.findAll();
        } catch (SQLException e) {
            error("Could not read the accounts:\n" + e.getMessage());
            return;
        }
        switching = true;
        listModel.clear();
        for (Account a : all) {
            if (a.parentId() == null) {
                listModel.addElement(a);
                for (Account c : all) {
                    if (Objects.equals(c.parentId(), a.id())) {
                        listModel.addElement(c);
                    }
                }
            }
        }
        for (Account c : all) {                          // envelopes whose parent is missing
            if (c.parentId() != null && !listModel.contains(c)) {
                listModel.addElement(c);
            }
        }
        int index = 0;
        for (int i = 0; i < listModel.size(); i++) {
            if (selectId != null && listModel.get(i).id() == selectId) {
                index = i;
            }
        }
        switching = false;
        if (!listModel.isEmpty()) {
            list.setSelectedIndex(index);               // loads its details
            list.ensureIndexIsVisible(index);
        } else {
            fill(null);
        }
    }

    private void selectionChanged() {
        Account a = list.getSelectedValue();
        if (loaded != null && (a == null || a.id() != loaded.id()) && !resolveUnsaved()) {
            putSelectionBack();
            return;
        }
        try {
            fill(a == null ? null : accounts.findDetails(a.id()));
        } catch (SQLException e) {
            error("Could not read the account:\n" + e.getMessage());
        }
    }

    private void putSelectionBack() {
        switching = true;
        list.clearSelection();                           // a new account isn't in the list
        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.get(i).id() == loaded.id()) {
                list.setSelectedIndex(i);
            }
        }
        switching = false;
    }

    // ----- The form ----------------------------------------------------------

    /** Shows an account's details (or empties the form for null). */
    private void fill(AccountRepository.Details d) {
        loaded = d;
        boolean on = d != null;
        formTitle.setText(d == null ? " " : d.id() == 0 ? "New Account" : d.name());
        for (JComponent c : new JComponent[] {nameField, typeCombo, parentCombo, bankField, startDate, openingField,
                adjustmentField, accountNumberField, routingNumberField, categoryCombo, websiteField, commentsArea}) {
            c.setEnabled(on);
        }
        if (d == null) {
            return;
        }
        try {
            hasChildren = d.id() != 0 && accounts.hasChildren(d.id());
        } catch (SQLException e) {
            hasChildren = true;                          // the safe guess: keep it top-level
        }
        nameField.setText(d.name());
        loadParents(d);
        typeCombo.setSelectedItem(d.type());
        bankField.setText(text(d.bankName()));
        startDate.setDate(d.startDate());
        openingField.setText(Money.format(d.openingBalanceCents()));
        adjustmentField.setText(Money.format(d.balanceAdjustmentCents()));
        accountNumberField.setText(text(d.accountNumber()));
        routingNumberField.setText(text(d.routingNumber()));
        categoryCombo.setSelectedItem(text(d.defaultCategory()));
        websiteField.setText(text(d.website()));
        commentsArea.setText(text(d.comments()));
        commentsArea.setCaretPosition(0);
        parentChanged();
    }

    /** The file name first (top level), then every account this one could belong to. */
    private void loadParents(AccountRepository.Details d) {
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        model.addElement(fileName);
        Object selected = fileName;
        try {
            for (Account a : accounts.findParentCandidates()) {
                if (a.id() != d.id()) {
                    model.addElement(a);
                    if (Objects.equals(d.parentId(), a.id())) {
                        selected = a;
                    }
                }
            }
        } catch (SQLException e) {
            // just the file, then
        }
        parentCombo.setModel(model);
        parentCombo.setSelectedItem(selected);
        parentCombo.setEnabled(!hasChildren);
        parentCombo.setToolTipText(hasChildren
                ? "This account has envelopes, so it stays a top-level account." : null);
    }

    /** An envelope always has a parent, and an account with a parent is always an envelope. */
    private void parentChanged() {
        if (loaded == null) {
            return;
        }
        boolean hasParent = parentCombo.getSelectedItem() instanceof Account;
        if (hasParent) {
            typeCombo.setSelectedItem(AccountType.ENVELOPE);
        } else if (typeCombo.getSelectedItem() == AccountType.ENVELOPE) {
            typeCombo.setSelectedItem(AccountType.CHECKING);
        }
        typeCombo.setEnabled(!hasParent);
    }

    /** The form's values as Details. @throws IllegalArgumentException with a message fit to show */
    private AccountRepository.Details formDetails() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Please enter an account name.");
        }
        Long parentId = parentCombo.getSelectedItem() instanceof Account p ? p.id() : null;
        AccountType type = (AccountType) typeCombo.getSelectedItem();
        if (type == AccountType.ENVELOPE && parentId == null) {
            throw new IllegalArgumentException("An envelope must belong to a parent account.");
        }
        long opening = amount(openingField, "Initial Balance");
        long adjustment = amount(adjustmentField, "Balance Adjustment");
        String category = Objects.toString(categoryCombo.getEditor().getItem(), "").trim();
        if (!category.isEmpty()) {
            category = CategoryRepository.normalize(category);
        }
        return new AccountRepository.Details(loaded.id(), name, type, parentId, blankToNull(bankField),
                startDate.getDateOrNull(), opening, adjustment, blankToNull(accountNumberField),
                blankToNull(routingNumberField), category.isEmpty() ? null : category,
                commentsArea.getText().isBlank() ? null : commentsArea.getText().strip(),
                blankToNull(websiteField));
    }

    private static long amount(JTextField f, String what) {
        String t = f.getText().trim();
        try {
            return t.isEmpty() ? 0 : Money.parse(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + ": " + e.getMessage());
        }
    }

    /** True if the form differs from what was loaded (or can't be read). */
    private boolean isDirty() {
        if (loaded == null) {
            return false;
        }
        if (loaded.id() == 0 && formIsBlank()) {
            return false;                                // a new account nothing was typed into
        }
        try {
            return !formDetails().equals(loaded);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** True if nothing has been typed into the form (for a new account). */
    private boolean formIsBlank() {
        for (JTextField f : new JTextField[] {nameField, bankField, accountNumberField, routingNumberField,
                websiteField}) {
            if (!f.getText().isBlank()) {
                return false;
            }
        }
        try {
            return amount(openingField, "") == 0 && amount(adjustmentField, "") == 0
                    && startDate.getDateOrNull() == null && commentsArea.getText().isBlank()
                    && Objects.toString(categoryCombo.getEditor().getItem(), "").isBlank()
                    && !(parentCombo.getSelectedItem() instanceof Account)
                    && typeCombo.getSelectedItem() == BLANK.type();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Saves the form. Returns false (after saying why) if it couldn't. */
    private boolean save() {
        if (loaded == null) {
            return true;
        }
        AccountRepository.Details d;
        try {
            d = formDetails();
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return false;
        }
        if (d.equals(loaded)) {
            return true;
        }
        boolean isNew = d.id() == 0;
        try {
            if (accounts.nameExists(d.name(), d.id())) {
                error("An account with that name already exists.");
                return false;
            }
            if (isNew) {                                 // create it, then save the rest of its details
                long id = accounts.insert(d.name(), d.type(), d.openingBalanceCents(), d.parentId());
                d = new AccountRepository.Details(id, d.name(), d.type(), d.parentId(), d.bankName(),
                        d.startDate(), d.openingBalanceCents(), d.balanceAdjustmentCents(), d.accountNumber(),
                        d.routingNumber(), d.defaultCategory(), d.comments(), d.website());
            }
            accounts.saveDetails(d);
        } catch (SQLException e) {
            error("Could not save the account:\n" + e.getMessage());
            return false;
        }
        if (!isNew && !d.name().equals(loaded.name())) {
            try {
                attachments.renameAccountFolder(loaded.name(), d.name());   // keep its folder matching
            } catch (SQLException | IOException e) {
                error("The account was renamed, but its attachments folder could not be:\n"
                        + e.getMessage() + "\n\nIts attachments still open from the old folder.");
            }
        }
        changed = true;
        lastSavedId = d.id();
        loaded = null;                                   // saved: nothing unsaved to ask about
        loadList(d.id());                                // names, order and icons may have changed
        return true;
    }

    /** Asks what to do with unsaved changes. False = stay on this account. */
    private boolean resolveUnsaved() {
        if (!isDirty()) {
            return true;
        }
        Object[] options = {"Save", "Discard", "Cancel"};
        String question = loaded.id() == 0 ? "Save the new account?"
                : "Save the changes to \"" + loaded.name() + "\"?";
        int choice = JOptionPane.showOptionDialog(this, question,
                getTitle(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            return save();
        }
        return choice == 1;
    }

    private void close() {
        if (resolveUnsaved()) {
            dispose();
        }
    }

    // ----- Helpers -----------------------------------------------------------

    private static int addRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = field instanceof JScrollPane ? GridBagConstraints.FIRST_LINE_END : GridBagConstraints.LINE_END;
        c.insets = new Insets(3, 0, 3, 8);
        form.add(new JLabel(label), c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(3, 0, 3, 0);
        form.add(field, c);
        return row + 1;
    }

    private static String blankToNull(JTextField f) {
        String t = f.getText().trim();
        return t.isEmpty() ? null : t;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
    }

    /** Category paths for the Default Category drop-down (categories only, no transfers). */
    static List<String> categoriesOnly(List<String> paths) {
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            if (!TransactionRepository.isTransfer(p)) {
                out.add(p);
            }
        }
        return out;
    }
}
