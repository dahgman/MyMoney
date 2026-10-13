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
import java.util.function.Supplier;
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
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/**
 * Enters or edits a scheduled transaction: the transaction itself (account,
 * payee, category, tag, description, memo, amount) and how it repeats.
 * Opened from Scheduled Transactions (New / Edit) and from the ledger's
 * Add to Schedule, which fills it in from a transaction.
 */
final class ScheduleDialog extends JDialog {

    private final ScheduleRepository schedules;
    private final ScheduleRepository.Schedule editing;     // id 0 = a new schedule
    private final List<Account> accounts;

    private final JComboBox<Account> accountCombo;
    private final JTextField payeeField = new JTextField(24);
    private final JTextField categoryField = new JTextField(24);
    private final JTextField tagField = new JTextField(24);
    private final JTextField descriptionField = new JTextField(24);
    private final JTextField memoField = new JTextField(24);
    private final JTextField paymentField = new JTextField(10);
    private final JTextField depositField = new JTextField(10);

    private final JComboBox<Recurrence.Preset> repeatsCombo = new JComboBox<>(Recurrence.Preset.values());
    private final JSpinner everySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
    private final JComboBox<Recurrence.Unit> everyUnit = new JComboBox<>(new Recurrence.Unit[] {
        Recurrence.Unit.DAY, Recurrence.Unit.WEEK, Recurrence.Unit.MONTH, Recurrence.Unit.YEAR});
    private final JSpinner secondDaySpinner = new JSpinner(new SpinnerNumberModel(15, 1, 31, 1));
    private final JPanel everyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel secondDayRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final DatePicker startDate;
    private final JRadioButton endNever = new JRadioButton("Never");
    private final JRadioButton endOn = new JRadioButton("On");
    private final JRadioButton endAfter = new JRadioButton("After");
    private final DatePicker endDate = new DatePicker(null);
    private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(12, 1, 9999, 1));
    private final JLabel preview = new JLabel(" ");
    private final java.util.function.BiFunction<String, Long, String> categoryForPayee;   // (payee, account id)
    private String autoCategory;               // the category last filled in from the payee (null = none)
    private AutoComplete categoryComplete;
    private Long savedId;

    /**
     * @param editing the schedule to edit, or one with id 0 to enter a new one (blank, or filled in
     *                from a transaction)
     */
    ScheduleDialog(Window owner, ScheduleRepository schedules, ScheduleRepository.Schedule editing,
                   List<Account> accounts, Supplier<List<String>> payees, Supplier<List<String>> categories,
                   Supplier<List<String>> tags, java.util.function.BiFunction<String, Long, String> categoryForPayee) {
        super(owner, editing.id() == 0 ? "New Scheduled Transaction" : "Edit Scheduled Transaction",
                ModalityType.APPLICATION_MODAL);
        this.schedules = schedules;
        this.editing = editing;
        this.accounts = accounts;
        this.categoryForPayee = categoryForPayee;

        accountCombo = new JComboBox<>(accounts.toArray(Account[]::new));
        for (Account a : accounts) {
            if (a.id() == editing.accountId()) {
                accountCombo.setSelectedItem(a);
            }
        }
        suggest(payeeField, payees);
        // Typing or picking a payee already used fills in the category it had last time.
        payeeField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                javax.swing.SwingUtilities.invokeLater(ScheduleDialog.this::fillCategoryFromPayee);
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                javax.swing.SwingUtilities.invokeLater(ScheduleDialog.this::fillCategoryFromPayee);
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        categoryComplete = suggest(categoryField, categories);
        suggest(tagField, tags);
        payeeField.setText(text(editing.payee()));
        categoryField.setText(text(editing.category()));
        tagField.setText(text(editing.tag()));
        descriptionField.setText(text(editing.description()));
        memoField.setText(text(editing.memo()));
        if (editing.amountCents() < 0) {
            paymentField.setText(Money.format(-editing.amountCents()));
        } else if (editing.amountCents() > 0) {
            depositField.setText(Money.format(editing.amountCents()));
        }
        paymentField.setHorizontalAlignment(JTextField.RIGHT);
        depositField.setHorizontalAlignment(JTextField.RIGHT);

        Recurrence r = editing.recurrence();
        startDate = new DatePicker(r.start());
        Recurrence.Preset preset = Recurrence.Preset.of(r.unit(), r.interval());
        repeatsCombo.setSelectedItem(preset);
        if (preset == Recurrence.Preset.CUSTOM) {
            everySpinner.setValue(r.interval());
            everyUnit.setSelectedItem(r.unit());
        }
        if (r.unit() == Recurrence.Unit.SEMIMONTH) {
            secondDaySpinner.setValue(r.secondDay());
        }
        ButtonGroup ends = new ButtonGroup();
        ends.add(endNever);
        ends.add(endOn);
        ends.add(endAfter);
        if (r.endDate() != null) {
            endOn.setSelected(true);
            endDate.setDate(r.endDate());
        } else if (r.maxCount() != null) {
            endAfter.setSelected(true);
            countSpinner.setValue(r.maxCount());
        } else {
            endNever.setSelected(true);
        }

        everyRow.add(new JLabel("Every"));
        everyRow.add(everySpinner);
        everyRow.add(everyUnit);
        everyUnit.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Recurrence.Unit u) {
                    setText(u.name().charAt(0) + u.name().substring(1).toLowerCase() + "s");
                }
                return this;
            }
        });
        secondDayRow.add(new JLabel("On the start date's day and on day"));
        secondDayRow.add(secondDaySpinner);
        secondDayRow.add(new JLabel("(31 = the last day)"));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        int row = 0;
        row = heading(form, row, "Transaction");
        row = addRow(form, row, "Account:", accountCombo);
        row = addRow(form, row, "Payee:", payeeField);
        row = addRow(form, row, "Category:", categoryField);
        row = addRow(form, row, "Tag:", tagField);
        row = addRow(form, row, "Description:", descriptionField);
        row = addRow(form, row, "Memo:", memoField);
        JPanel amounts = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        amounts.add(paymentField);
        amounts.add(new JLabel("   Deposit:"));
        amounts.add(depositField);
        row = addRow(form, row, "Payment:", amounts);
        row = heading(form, row, "Schedule");
        row = addRow(form, row, "Repeats:", repeatsCombo);
        row = addRow(form, row, "", everyRow);
        row = addRow(form, row, "", secondDayRow);
        row = addRow(form, row, "First date:", startDate);
        JPanel endRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        endRow.add(endNever);
        endRow.add(endOn);
        endRow.add(endDate);
        endRow.add(endAfter);
        endRow.add(countSpinner);
        endRow.add(new JLabel("times"));
        row = addRow(form, row, "Ends:", endRow);
        row = addRow(form, row, "Next dates:", preview);

        // Keep the rows that apply, and the preview, up to date.
        repeatsCombo.addActionListener(e -> refresh());
        everyUnit.addActionListener(e -> refresh());
        everySpinner.addChangeListener(e -> refresh());
        secondDaySpinner.addChangeListener(e -> refresh());
        countSpinner.addChangeListener(e -> refresh());
        for (JRadioButton b : new JRadioButton[] {endNever, endOn, endAfter}) {
            b.addActionListener(e -> refresh());
        }
        startDate.onChange(this::refresh);
        endDate.onChange(this::refresh);

        JButton save = new JButton("Save");
        save.addActionListener(e -> save());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(save);
        buttons.add(cancel);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        refresh();
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * If the payee has been used before, puts its last category in Category,
     * unless a category has been typed there by hand (one filled in here can
     * be replaced as the payee changes).
     */
    private void fillCategoryFromPayee() {
        String current = categoryField.getText().trim();
        if (!current.isEmpty() && !current.equals(autoCategory)) {
            return;                                     // typed (or loaded) by hand: leave it
        }
        Account account = (Account) accountCombo.getSelectedItem();
        String category = categoryForPayee.apply(payeeField.getText().trim(), account == null ? 0L : account.id());
        if (category != null && account != null && category.equalsIgnoreCase("[" + account.name() + "]")) {
            category = null;                            // a transfer to this same account makes no sense here
        }
        if (category != null) {
            categoryComplete.setTextQuietly(category);  // without opening its suggestion list
            autoCategory = category;
        } else if (current.equals(autoCategory)) {
            categoryComplete.setTextQuietly("");        // the payee changed to one with no category
            autoCategory = null;
        }
    }

    /** The saved schedule's id, or null if it wasn't saved. */
    Long savedId() {
        return savedId;
    }

    // ----- Reading the form ----------------------------------------------------

    /** The schedule part of the form. @throws IllegalArgumentException with a message fit to show */
    private Recurrence recurrence() {
        Recurrence.Preset p = (Recurrence.Preset) repeatsCombo.getSelectedItem();
        Recurrence.Unit unit = p == Recurrence.Preset.CUSTOM ? (Recurrence.Unit) everyUnit.getSelectedItem() : p.unit;
        int interval = p == Recurrence.Preset.CUSTOM ? (Integer) everySpinner.getValue() : p.interval;
        LocalDate start = startDate.getDate();
        LocalDate end = null;
        if (endOn.isSelected()) {
            end = endDate.getDateOrNull();
            if (end == null) {
                throw new IllegalArgumentException("Enter the date the schedule ends.");
            }
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("The end date is before the first date.");
            }
        }
        Integer count = endAfter.isSelected() ? (Integer) countSpinner.getValue() : null;
        return new Recurrence(unit, interval, (Integer) secondDaySpinner.getValue(), start, end, count);
    }

    private void refresh() {
        Recurrence.Preset p = (Recurrence.Preset) repeatsCombo.getSelectedItem();
        everyRow.setVisible(p == Recurrence.Preset.CUSTOM);
        secondDayRow.setVisible(p == Recurrence.Preset.SEMIMONTHLY);
        endDate.setEnabled(endOn.isSelected());
        countSpinner.setEnabled(endAfter.isSelected());
        try {
            Recurrence r = recurrence();
            List<LocalDate> next = r.datesBetween(LocalDate.now(), LocalDate.now().plusYears(10));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < Math.min(4, next.size()); i++) {
                s.append(i == 0 ? "" : ", ").append(next.get(i).format(LedgerTableModel.DISPLAY_DATE));
            }
            if (next.size() > 4) {
                s.append(", ...");
            }
            preview.setText(next.isEmpty() ? "none (the schedule has ended)" : s.toString());
        } catch (IllegalArgumentException e) {
            preview.setText(" ");
        }
        pack();
    }

    private void save() {
        Account account = (Account) accountCombo.getSelectedItem();
        Recurrence r;
        long amount;
        String category = categoryField.getText().trim();
        try {
            if (account == null) {
                throw new IllegalArgumentException("Choose the account.");
            }
            String pay = paymentField.getText().trim();
            String dep = depositField.getText().trim();
            if (!pay.isEmpty() && !dep.isEmpty()) {
                throw new IllegalArgumentException("Enter a payment or a deposit, not both.");
            }
            amount = !pay.isEmpty() ? -Money.parse(pay) : !dep.isEmpty() ? Money.parse(dep) : 0;
            if (amount == 0) {
                throw new IllegalArgumentException("Enter the payment or deposit amount.");
            }
            if (TransactionRepository.SPLIT_LABEL.equals(category)) {
                throw new IllegalArgumentException("Split transactions can't be scheduled yet; choose one category.");
            }
            if (TransactionRepository.isTransfer(category)) {
                String other = category.substring(1, category.length() - 1).trim();
                if (other.equalsIgnoreCase(account.name())) {
                    throw new IllegalArgumentException("An account can't transfer to itself.");
                }
                if (accounts.stream().noneMatch(a -> a.name().equalsIgnoreCase(other))) {
                    throw new IllegalArgumentException("There is no account named \"" + other + "\" to transfer to.");
                }
            } else if (!category.isEmpty()) {
                category = CategoryRepository.normalize(category);
            }
            r = recurrence();
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        String tag = tagField.getText().trim();
        ScheduleRepository.Schedule s = new ScheduleRepository.Schedule(editing.id(), account.id(),
                blankToNull(payeeField), category.isEmpty() ? null : category,
                tag.isEmpty() ? null : TagRepository.normalize(tag), blankToNull(descriptionField),
                blankToNull(memoField), amount, r);
        try {
            savedId = schedules.save(s);
        } catch (SQLException e) {
            error("Could not save the schedule:\n" + e.getMessage());
            return;
        }
        dispose();
    }

    // ----- Helpers ---------------------------------------------------------------

    /** Suggestions that narrow as you type, like the ledger's Category cell. */
    private static AutoComplete suggest(JTextField field, Supplier<List<String>> source) {
        AutoComplete ac = new AutoComplete(field, source);
        ac.reload();
        field.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "pick");
        field.getActionMap().put("pick", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                ac.pickHighlighted();
            }
        });
        field.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "close");
        field.getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                ac.hidePopup();
            }
        });
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                ac.hidePopup();
            }
        });
        return ac;
    }

    private static int heading(JPanel form, int row, String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(row == 0 ? 0 : 10, 0, 4, 0);
        form.add(l, c);
        return row + 1;
    }

    private static int addRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_END;
        c.insets = new Insets(2, 0, 2, 8);
        JLabel l = new JLabel(label);
        form.add(l, c);
        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        c.weightx = 1;
        c.insets = new Insets(2, 0, 2, 0);
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
}
