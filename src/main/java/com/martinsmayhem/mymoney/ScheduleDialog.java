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
import javax.swing.JCheckBox;
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
    // The amount: fixed, or per weekday (that much for each one in the month).
    private final JRadioButton amountFixed = new JRadioButton("The same each time");
    private final JRadioButton amountPerDay = new JRadioButton("For each");
    private final JComboBox<java.time.DayOfWeek> weekdayCombo = new JComboBox<>(java.time.DayOfWeek.values());
    private final JPanel amountRuleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JCheckBox lastDay = new JCheckBox("On the last day of the month");
    /** Not a paycheck, or which one (Main, Secondary, ...): see Paychecks. */
    private final JComboBox<String> paycheckCombo = new JComboBox<>();
    private final JPanel lastDayRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

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
        AutoComplete payeeComplete = suggest(payeeField, payees);
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
        AutoComplete tagComplete = suggest(tagField, tags);
        // The starting values are set quietly: they shouldn't open the suggestion lists.
        payeeComplete.setTextQuietly(text(editing.payee()));
        categoryComplete.setTextQuietly(text(editing.category()));
        tagComplete.setTextQuietly(text(editing.tag()));
        descriptionField.setText(text(editing.description()));
        memoField.setText(text(editing.memo()));
        if (editing.amountCents() < 0) {
            paymentField.setText(Money.format(-editing.amountCents()));
        } else if (editing.amountCents() > 0) {
            depositField.setText(Money.format(editing.amountCents()));
        }
        paymentField.setHorizontalAlignment(JTextField.RIGHT);
        Money.calculateOnExit(paymentField);            // "12.50+3.25" becomes "$15.75"
        Money.calculateOnExit(depositField);
        depositField.setHorizontalAlignment(JTextField.RIGHT);

        ButtonGroup amountKinds = new ButtonGroup();
        amountKinds.add(amountFixed);
        amountKinds.add(amountPerDay);
        weekdayCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof java.time.DayOfWeek d) {
                    setText(d.name().charAt(0) + d.name().substring(1).toLowerCase());
                }
                return this;
            }
        });
        if (editing.perWeekday() != null) {
            amountPerDay.setSelected(true);
            weekdayCombo.setSelectedItem(editing.weekday());
        } else {
            amountFixed.setSelected(true);
            weekdayCombo.setSelectedItem(java.time.DayOfWeek.TUESDAY);
        }
        amountRuleRow.add(amountFixed);
        amountRuleRow.add(amountPerDay);
        amountRuleRow.add(weekdayCombo);
        amountRuleRow.add(new JLabel("in the month (a weekly service billed monthly)"));

        paycheckCombo.addItem("Not a paycheck");
        for (int rank = 1; rank <= Paychecks.MAX_RANK; rank++) {
            paycheckCombo.addItem(Paychecks.rankName(rank) + " paycheck");
        }
        paycheckCombo.setSelectedIndex(editing.paycheck() == null ? 0 : Math.min(editing.paycheck(), Paychecks.MAX_RANK));
        paycheckCombo.setToolTipText("A paycheck can show what's due before it comes (Preferences > Paychecks)");

        Recurrence r = editing.recurrence();
        startDate = new DatePicker(r.start());
        lastDay.setSelected(r.lastDayOfMonth());
        lastDay.setToolTipText("The 31st, 30th, or 28th or 29th as the month has it, whatever day the first date is");
        lastDayRow.add(lastDay);
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
        row = addRow(form, row, "Amount:", amountRuleRow);
        row = addRow(form, row, "Paycheck:", paycheckCombo);
        row = heading(form, row, "Schedule");
        row = addRow(form, row, "Repeats:", repeatsCombo);
        row = addRow(form, row, "", everyRow);
        row = addRow(form, row, "", secondDayRow);
        row = addRow(form, row, "", lastDayRow);
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
        lastDay.addActionListener(e -> refresh());
        amountFixed.addActionListener(e -> refresh());
        amountPerDay.addActionListener(e -> refresh());
        weekdayCombo.addActionListener(e -> refresh());
        for (JTextField f : new JTextField[] {paymentField, depositField}) {
            f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    javax.swing.SwingUtilities.invokeLater(ScheduleDialog.this::refresh);
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    javax.swing.SwingUtilities.invokeLater(ScheduleDialog.this::refresh);
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                }
            });
        }
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
        int second = unit == Recurrence.Unit.SEMIMONTH ? (Integer) secondDaySpinner.getValue()
                : unit == Recurrence.Unit.MONTH && lastDay.isSelected() ? 31 : 0;
        return new Recurrence(unit, interval, second, start, end, count);
    }

    /** The unit the form's Repeats comes to (Monthly, Every 3 months and Custom months are MONTH). */
    private Recurrence.Unit unit() {
        Recurrence.Preset p = (Recurrence.Preset) repeatsCombo.getSelectedItem();
        return p == Recurrence.Preset.CUSTOM ? (Recurrence.Unit) everyUnit.getSelectedItem() : p.unit;
    }

    /** The amount typed (negative for a payment), or null if there's none yet or it isn't one. */
    private Long typedAmount() {
        try {
            String pay = paymentField.getText().trim();
            String dep = depositField.getText().trim();
            return !pay.isEmpty() ? (Long) (-Money.parseTyped(pay)) : !dep.isEmpty() ? (Long) Money.parseTyped(dep) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The weekday the amount is per (1 = Monday), or null for a fixed amount. */
    private Integer perWeekday() {
        return amountPerDay.isSelected() && unit() == Recurrence.Unit.MONTH
                ? ((java.time.DayOfWeek) weekdayCombo.getSelectedItem()).getValue() : null;
    }

    private void refresh() {
        Recurrence.Preset p = (Recurrence.Preset) repeatsCombo.getSelectedItem();
        boolean monthly = unit() == Recurrence.Unit.MONTH;
        everyRow.setVisible(p == Recurrence.Preset.CUSTOM);
        secondDayRow.setVisible(p == Recurrence.Preset.SEMIMONTHLY);
        lastDayRow.setVisible(monthly);
        // Per weekday counts them in each occurrence's month: monthly schedules only.
        amountPerDay.setEnabled(monthly);
        weekdayCombo.setEnabled(monthly && amountPerDay.isSelected());
        if (!monthly && amountPerDay.isSelected()) {
            amountFixed.setSelected(true);
        }
        endDate.setEnabled(endOn.isSelected());
        countSpinner.setEnabled(endAfter.isSelected());
        Long typed = typedAmount();
        paycheckCombo.setEnabled(typed == null || typed > 0);    // only a deposit is a paycheck
        if (typed != null && typed < 0) {
            paycheckCombo.setSelectedIndex(0);
        }
        try {
            Recurrence r = recurrence();
            List<LocalDate> next = r.datesBetween(LocalDate.now(), LocalDate.now().plusYears(10));
            Integer weekday = perWeekday();
            Long amount = typedAmount();
            if (weekday == null || amount == null) {
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < Math.min(4, next.size()); i++) {
                    s.append(i == 0 ? "" : ", ").append(next.get(i).format(LedgerTableModel.DISPLAY_DATE));
                }
                if (next.size() > 4) {
                    s.append(", ...");
                }
                preview.setText(next.isEmpty() ? "none (the schedule has ended)" : s.toString());
            } else {
                // Each date with its amount: the per-day amount times that month's count.
                ScheduleRepository.Schedule s = new ScheduleRepository.Schedule(0, 0, null, null, null, null, null,
                        amount, r, weekday);
                StringBuilder html = new StringBuilder("<html>");
                for (int i = 0; i < Math.min(4, next.size()); i++) {
                    LocalDate d = next.get(i);
                    int n = ScheduleRepository.Schedule.weekdaysIn(java.time.YearMonth.from(d), s.weekday());
                    html.append(i == 0 ? "" : "<br>").append(d.format(LedgerTableModel.DISPLAY_DATE)).append(" &mdash; ")
                            .append(Money.format(Math.abs(s.amountOn(d)))).append(" (").append(n).append(' ')
                            .append(ScheduleRepository.Schedule.plural(s.weekday())).append(')');
                }
                preview.setText(next.isEmpty() ? "none (the schedule has ended)" : html.append("</html>").toString());
            }
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
            amount = !pay.isEmpty() ? -Money.parseTyped(pay) : !dep.isEmpty() ? Money.parseTyped(dep) : 0;
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
                blankToNull(memoField), amount, r, perWeekday(),
                amount > 0 && paycheckCombo.getSelectedIndex() > 0 ? paycheckCombo.getSelectedIndex() : null);
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
