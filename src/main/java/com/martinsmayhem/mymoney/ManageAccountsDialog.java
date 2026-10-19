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
import javax.swing.JSpinner;
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

    // Loan accounts only:
    private final JTextField rateField = new JTextField(8);
    private final JComboBox<String> interestMethodCombo = new JComboBox<>(new String[] {
        "Monthly (rate ÷ 12)", "Daily, from the day paid (rate ÷ 365)",
        "Daily, on the due dates (rate ÷ 365; Affirm)"});
    private final JTextField originalField = new JTextField(12);
    private final JSpinner termSpinner = new JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 1200, 1));
    private final JComboBox<String> interestCategoryCombo = new JComboBox<>();
    private final JTextField loanPaymentField = new JTextField(12);
    private final DatePicker firstPaymentPicker = new DatePicker(null);
    private final JLabel loanSummary = new JLabel(" ");
    private final JButton scheduleButton = new JButton("Amortization Schedule...");
    private final List<JComponent> loanOnly = new ArrayList<>();

    // Credit card accounts only:
    private final JTextField aprField = new JTextField(8);
    private final JTextField limitField = new JTextField(12);
    private final JSpinner statementDaySpinner = new JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 31, 1));
    private final JSpinner dueDaySpinner = new JSpinner(new javax.swing.SpinnerNumberModel(0, 0, 31, 1));
    private final JTextField minPercentField = new JTextField(5);
    private final JTextField minFloorField = new JTextField(8);
    private final JLabel cardSummary = new JLabel(" ");
    private final JButton payoffButton = new JButton("Payoff Schedule...");
    private final List<JComponent> cardOnly = new ArrayList<>();
    private final java.util.Map<JComponent, JLabel> labels = new java.util.HashMap<>();

    /** What the form starts as for a new account (id 0 = not saved yet). */
    static final AccountRepository.Details BLANK = new AccountRepository.Details(0, "",
            AccountType.CHECKING, null, null, null, 0, 0, null, null, null, null, null,
            AccountRepository.LoanTerms.NONE, AccountRepository.CardTerms.NONE);

    private final JLabel formTitle = new JLabel(" ");
    private AccountRepository.Details loaded;     // as saved (or BLANK), to notice unsaved changes
    private Long lastSavedId;                      // the account saved last, to select afterwards
    private boolean hasChildren;
    private boolean changed;                       // anything saved while open
    private boolean switching;                     // true while the list selection is put back

    /**
     * @param select   the account to show first (null: the first one)
     * @param startNew to start on a new account: what to fill in (BLANK for nothing,
     *                 or, say, the account number from an imported file); null to not
     */
    ManageAccountsDialog(Window owner, AccountRepository accounts, AttachmentRepository attachments,
                         List<String> categoryPaths, Path dataFile, Account select,
                         AccountRepository.Details startNew) {
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
        AutoComplete.attach(categoryCombo, () -> categoryPaths);           // fills in as you type
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
        JPanel rateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rateRow.add(rateField);
        rateRow.add(new JLabel("% a year"));
        row = addRow(form, row, "Interest Rate:", rateRow);
        row = addRow(form, row, "Interest Charged:", interestMethodCombo);
        row = addRow(form, row, "Original Amount:", originalField);
        JPanel termRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        termRow.add(termSpinner);
        termRow.add(new JLabel("monthly payments"));
        row = addRow(form, row, "Term:", termRow);
        JPanel paymentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        paymentRow.add(loanPaymentField);
        paymentRow.add(new JLabel("the lender's (blank: calculated)"));
        row = addRow(form, row, "Monthly Payment:", paymentRow);
        row = addRow(form, row, "First Payment:", firstPaymentPicker);
        row = addRow(form, row, "Interest Category:", interestCategoryCombo);
        // Summary above the button, so a long summary doesn't widen the form.
        JPanel summaryRow = new JPanel();
        summaryRow.setLayout(new javax.swing.BoxLayout(summaryRow, javax.swing.BoxLayout.PAGE_AXIS));
        loanSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        scheduleButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        summaryRow.add(loanSummary);
        summaryRow.add(javax.swing.Box.createVerticalStrut(4));
        summaryRow.add(scheduleButton);
        row = addRow(form, row, "", summaryRow);
        loanOnly.addAll(List.of(rateRow, interestMethodCombo, originalField, termRow, paymentRow, firstPaymentPicker,
                interestCategoryCombo, summaryRow));

        JPanel aprRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        aprRow.add(aprField);
        aprRow.add(new JLabel("% a year"));
        row = addRow(form, row, "APR:", aprRow);
        row = addRow(form, row, "Credit Limit:", limitField);
        JPanel daysRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        daysRow.add(new JLabel("closes on day"));
        daysRow.add(statementDaySpinner);
        daysRow.add(new JLabel("   due on day"));
        daysRow.add(dueDaySpinner);
        row = addRow(form, row, "Statement:", daysRow);
        JPanel minRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        minRow.add(minPercentField);
        minRow.add(new JLabel("% or $"));
        minRow.add(minFloorField);
        row = addRow(form, row, "Minimum Payment:", minRow);
        JPanel cardSummaryRow = new JPanel();
        cardSummaryRow.setLayout(new javax.swing.BoxLayout(cardSummaryRow, javax.swing.BoxLayout.PAGE_AXIS));
        cardSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        payoffButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardSummaryRow.add(cardSummary);
        cardSummaryRow.add(javax.swing.Box.createVerticalStrut(4));
        cardSummaryRow.add(payoffButton);
        row = addRow(form, row, "", cardSummaryRow);
        cardOnly.addAll(List.of(aprRow, limitField, daysRow, minRow, cardSummaryRow));
        row = addRow(form, row, "Default Category:", categoryCombo);
        row = addRow(form, row, "Website:", websiteField);
        JScrollPane comments = new JScrollPane(commentsArea);
        row = addRow(form, row, "Comments:", comments);
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row;
        filler.weighty = 1;
        form.add(new JPanel(), filler);

        adjustmentField.setToolTipText("Added to the initial balance, without a transaction");
        originalField.setHorizontalAlignment(JTextField.RIGHT);
        rateField.setHorizontalAlignment(JTextField.RIGHT);
        interestCategoryCombo.setEditable(true);
        AutoComplete.attach(interestCategoryCombo, () -> categoryPaths);   // fills in as you type
        interestCategoryCombo.addItem("");
        for (String c : categoryPaths) {
            interestCategoryCombo.addItem(c);
        }
        interestCategoryCombo.setToolTipText("Where the interest part of each payment goes (blank: "
                + Loan.DEFAULT_INTEREST_CATEGORY + ")");
        rateField.setToolTipText("The annual interest rate, like 6.375");
        interestMethodCombo.setToolTipText("<html>How the lender works out the interest in each payment:<br>"
                + "Monthly: a twelfth of the yearly rate each month (many mortgages).<br>"
                + "Daily, from the day paid: for each day since the last payment (many auto and personal loans).<br>"
                + "Daily, on the due dates: for each day from one due date to the next, whenever you pay"
                + " (Affirm and other pay-over-time loans).</html>");
        originalField.setToolTipText("The amount borrowed");
        termSpinner.setToolTipText("How many monthly payments, from the origination date");
        typeCombo.addActionListener(e -> typeChanged());
        for (JTextField f : new JTextField[] {rateField, originalField}) {
            f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    updateLoanSummary();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    updateLoanSummary();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                }
            });
        }
        termSpinner.addChangeListener(e -> updateLoanSummary());
        loanPaymentField.setHorizontalAlignment(JTextField.RIGHT);
        loanPaymentField.setToolTipText("The payment on your loan statement. Lenders' payments often differ a little"
                + " from the standard calculation (daily interest, a first payment ~45 days out, fees).");
        firstPaymentPicker.setToolTipText("When the first payment was due (blank: a month after origination)");
        loanPaymentField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateLoanSummary();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateLoanSummary();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        firstPaymentPicker.onChange(this::updateLoanSummary);
        startDate.onChange(this::updateLoanSummary);
        // A new loan with no initial balance yet owes the original amount.
        originalField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                try {
                    if (amount(openingField, "") == 0 && amount(originalField, "") > 0) {
                        openingField.setText(Money.format(-amount(originalField, "")));
                    }
                } catch (IllegalArgumentException ignored) {
                    // left for Save to report
                }
            }
        });
        scheduleButton.addActionListener(e -> showSchedule());

        limitField.setHorizontalAlignment(JTextField.RIGHT);
        aprField.setHorizontalAlignment(JTextField.RIGHT);
        minPercentField.setHorizontalAlignment(JTextField.RIGHT);
        minFloorField.setHorizontalAlignment(JTextField.RIGHT);
        aprField.setToolTipText("The annual percentage rate on purchases, like 19.99");
        limitField.setToolTipText("The credit limit");
        statementDaySpinner.setToolTipText("The day of the month the statement closes (0 = not set; 31 = the last day)");
        dueDaySpinner.setToolTipText("The day of the month the payment is due (0 = not set; 31 = the last day)");
        minPercentField.setToolTipText("The minimum payment as a percent of the balance, like 2"
                + " (whichever of the two is more is due)");
        minFloorField.setToolTipText("The smallest minimum payment, like 35.00 (whichever of the two is more is due)");
        for (JTextField f : new JTextField[] {aprField, limitField, minPercentField, minFloorField}) {
            f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(javax.swing.event.DocumentEvent e) {
                    updateCardSummary();
                }

                @Override
                public void removeUpdate(javax.swing.event.DocumentEvent e) {
                    updateCardSummary();
                }

                @Override
                public void changedUpdate(javax.swing.event.DocumentEvent e) {
                }
            });
        }
        statementDaySpinner.addChangeListener(e -> updateCardSummary());
        dueDaySpinner.addChangeListener(e -> updateCardSummary());
        payoffButton.addActionListener(e -> showPayoff());
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
        newButton.addActionListener(e -> startNewAccount(BLANK));
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
        if (startNew != null) {
            startNewAccount(startNew);
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

    /** Fills the form for a new account from a template (after asking about unsaved changes). */
    private void startNewAccount(AccountRepository.Details template) {
        if (loaded != null && !resolveUnsaved()) {
            return;
        }
        switching = true;
        list.clearSelection();
        switching = false;
        fill(template);
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
                adjustmentField, accountNumberField, routingNumberField, categoryCombo, websiteField, commentsArea,
                rateField, interestMethodCombo, originalField, termSpinner, interestCategoryCombo, scheduleButton,
                loanPaymentField, firstPaymentPicker,
                aprField, limitField, statementDaySpinner, dueDaySpinner, minPercentField, minFloorField,
                payoffButton}) {
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
        AccountRepository.LoanTerms t = d.loan() == null ? AccountRepository.LoanTerms.NONE : d.loan();
        rateField.setText(t.rateMilli() == 0 ? "" : Loan.formatRate(t.rateMilli()).replace("%", ""));
        originalField.setText(t.originalCents() == 0 ? "" : Money.format(t.originalCents()));
        termSpinner.setValue(t.termMonths());
        interestCategoryCombo.setSelectedItem(text(t.interestCategory()));
        loanPaymentField.setText(t.paymentCents() == 0 ? "" : Money.format(t.paymentCents()));
        firstPaymentPicker.setDate(t.firstPayment());
        interestMethodCombo.setSelectedIndex(t.interestMethod() == null ? 0 : t.interestMethod().ordinal());
        AccountRepository.CardTerms ct = d.card() == null ? AccountRepository.CardTerms.NONE : d.card();
        aprField.setText(ct.aprMilli() == 0 ? "" : Loan.formatRate(ct.aprMilli()).replace("%", ""));
        limitField.setText(ct.limitCents() == 0 ? "" : Money.format(ct.limitCents()));
        statementDaySpinner.setValue(ct.statementDay());
        dueDaySpinner.setValue(ct.dueDay());
        minPercentField.setText(ct.minPercentMilli() == 0 ? "" : Loan.formatRate(ct.minPercentMilli()).replace("%", ""));
        minFloorField.setText(ct.minFloorCents() == 0 ? "" : Money.format(ct.minFloorCents()));
        parentChanged();
        typeChanged();
    }

    /**
     * A loan shows its loan fields, and calls Bank Name the lender, Account
     * Number the loan number and Start Date the origination date. Other
     * accounts don't show the loan fields (and a loan has no routing number).
     */
    private void typeChanged() {
        boolean loan = typeCombo.getSelectedItem() == AccountType.LOAN;
        boolean card = typeCombo.getSelectedItem() == AccountType.CREDIT_CARD;
        labels.get(bankField).setText(loan ? "Lender:" : card ? "Issuer:" : "Bank Name:");
        labels.get(accountNumberField).setText(loan ? "Loan Number:" : card ? "Card Number:" : "Account Number:");
        labels.get(startDate).setText(loan ? "Origination Date:" : card ? "Open Date:" : "Start Date:");
        labels.get(openingField).setText(loan || card ? "Initial Balance (owed):" : "Initial Balance:");
        openingField.setToolTipText(loan ? "What was owed when you started tracking the loan, as a negative amount"
                + " (a new loan: minus the original amount)"
                : card ? "What was owed when you started tracking the card, as a negative amount" : null);
        for (JComponent c : loanOnly) {
            c.setVisible(loan);
            labels.get(c).setVisible(loan);
        }
        for (JComponent c : cardOnly) {
            c.setVisible(card);
            labels.get(c).setVisible(card);
        }
        routingNumberField.setVisible(!loan && !card);
        labels.get(routingNumberField).setVisible(!loan && !card);
        updateLoanSummary();
        updateCardSummary();
        revalidate();
        repaint();
    }

    /** "Monthly payment: $X · Matures MM/DD/YYYY", from the loan fields as typed. */
    private void updateLoanSummary() {
        try {
            Loan l = formLoan();
            long payment = l.regularPayment();
            java.time.LocalDate maturity = l.maturity();
            long calculated = l.calculatedPayment();
            loanSummary.setText(payment == 0 ? "Enter the original amount and term."
                    : "<html>Monthly payment: <b>" + Money.format(payment) + "</b>"
                    + (l.paymentCents() > 0 && calculated > 0 && calculated != payment
                            ? " (the standard calculation gives " + Money.format(calculated) + ")" : "")
                    + (maturity == null ? "" : "<br>Matures " + maturity.format(LedgerTableModel.DISPLAY_DATE))
                    + totals(l, payment)
                    + "</html>");
        } catch (IllegalArgumentException e) {
            loanSummary.setText(" ");
        }
    }

    /**
     * The loan's totals, as summary lines: over its original terms (what the
     * lender's disclosure calls the total of payments and finance charge),
     * paid so far, and what's left to pay at the regular payment.
     */
    private String totals(Loan l, long payment) {
        StringBuilder s = new StringBuilder();
        java.time.LocalDate first = l.firstPaymentDate();
        LoanCalculator.Terms contract = l.contract(l.paymentMethod());
        if (contract != null && contract.method() == LoanCalculator.Method.DAILY_SIMPLE_INTEREST) {
            // Daily interest: the contract worked out from its actual dates.
            LoanCalculator.Result r = LoanCalculator.withPayment(contract, java.math.BigDecimal.valueOf(payment, 2));
            s.append("<br>Original terms: <b>").append(Money.format(r.totalOfPayments().movePointRight(2)
                    .longValueExact())).append("</b> in all, <b>").append(Money.format(r.financeCharge()
                    .movePointRight(2).longValueExact())).append("</b> of it interest");
        } else if (l.originalCents() > 0 && first != null) {
            List<Loan.Payment> all = l.schedule(l.originalCents(), first, payment);
            long total = all.stream().mapToLong(Loan.Payment::paymentCents).sum();
            long interest = all.stream().mapToLong(Loan.Payment::interestCents).sum();
            s.append("<br>Original terms: <b>").append(Money.format(total)).append("</b> in all, <b>")
             .append(Money.format(interest)).append("</b> of it interest");
        }
        if (loaded == null || loaded.id() == 0 || loaded.type() != AccountType.LOAN) {
            return s.toString();                         // nothing paid on a new loan
        }
        try {
            java.time.LocalDate today = java.time.LocalDate.now();
            long[] paid = accounts.loanPaid(loaded.id(), today);
            long owed = accounts.principalOwed(loaded.id(), today);
            s.append("<br>Paid so far: ").append(Money.format(paid[0])).append(", ")
             .append(Money.format(paid[1])).append(" of it interest");
            if (owed > 0) {
                java.time.LocalDate next = first == null ? today.plusMonths(1) : first;
                while (!next.isAfter(today)) {
                    next = next.plusMonths(1);
                }
                List<Loan.Payment> rest = l.schedule(owed, next, payment);
                if (!rest.isEmpty() && rest.get(rest.size() - 1).balanceCents() == 0) {
                    long left = rest.stream().mapToLong(Loan.Payment::paymentCents).sum();
                    long interest = rest.stream().mapToLong(Loan.Payment::interestCents).sum();
                    s.append("<br>Still to pay: about ").append(Money.format(left)).append(", ")
                     .append(Money.format(interest)).append(" of it interest")
                     .append("<br>&nbsp;&nbsp;(").append(rest.size())
                     .append(rest.size() == 1 ? " payment left)" : " payments left)")
                     .append("<br>Total: about <b>").append(Money.format(paid[0] + left)).append("</b>, <b>")
                     .append(Money.format(paid[1] + interest)).append("</b> of it interest");
                }
            }
        } catch (SQLException e) {
            // the totals are a convenience: leave them out
        }
        return s.toString();
    }

    /** The card as the form has it. @throws IllegalArgumentException with a message fit to show */
    private CreditCard formCard() {
        long limit = amount(limitField, "Credit Limit");
        long floor = amount(minFloorField, "Minimum Payment");
        if (limit < 0 || floor < 0) {
            throw new IllegalArgumentException("Credit Limit and Minimum Payment are positive amounts.");
        }
        int minPercent;
        try {
            minPercent = Loan.parseRate(minPercentField.getText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Minimum Payment: \"" + minPercentField.getText()
                    + "\" isn't a percent (like 2).");
        }
        return new CreditCard(loaded == null ? 0 : loaded.id(), Loan.parseRate(aprField.getText()), limit,
                (Integer) statementDaySpinner.getValue(), (Integer) dueDaySpinner.getValue(), minPercent, floor);
    }

    /** What's owed on the card being edited today (positive), or 0 for a new account. */
    private long cardOwedNow() {
        try {
            return loaded != null && loaded.id() != 0
                    ? -accounts.balanceThrough(loaded.id(), java.time.LocalDate.now()) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    /** "Owed $X · Available $Y of $Z (N% used) / Minimum payment $M · Next due MM/DD/YYYY". */
    private void updateCardSummary() {
        try {
            CreditCard c = formCard();
            long owed = cardOwedNow();
            StringBuilder s = new StringBuilder("<html>Owed: <b>" + Money.format(Math.max(0, owed)) + "</b>");
            if (c.limitCents() > 0) {
                s.append("<br>Available: ").append(Money.format(c.available(owed)))
                 .append(" of ").append(Money.format(c.limitCents()))
                 .append(" (").append(c.utilizationPercent(owed)).append("% used)");
            }
            java.time.LocalDate due = CreditCard.nextDay(c.dueDay(), java.time.LocalDate.now());
            if (c.hasMinimumRule() || due != null) {
                s.append("<br>");
                if (c.hasMinimumRule()) {
                    s.append("Minimum payment: ").append(Money.format(c.minimumPayment(owed)));
                }
                if (due != null) {
                    s.append(c.hasMinimumRule() ? " due " : "Next due: ").append(due.format(LedgerTableModel.DISPLAY_DATE));
                }
            }
            cardSummary.setText(s.append("</html>").toString());
        } catch (IllegalArgumentException e) {
            cardSummary.setText(" ");
        }
    }

    /** Shows the payoff schedule for the card as the form has it. */
    private void showPayoff() {
        CreditCard c;
        try {
            c = formCard();
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        long owed = cardOwedNow();
        if (owed <= 0) {
            error("Nothing is owed on this card" + (loaded != null && loaded.id() == 0 ? " yet (save it first)." : "."));
            return;
        }
        new CardPayoffDialog(this, nameField.getText().trim(), c, owed).setVisible(true);
    }

    /** The loan as the form has it. @throws IllegalArgumentException with a message fit to show */
    private Loan formLoan() {
        long original = amount(originalField, "Original Amount");
        if (original < 0) {
            throw new IllegalArgumentException("Original Amount: enter the amount borrowed as a positive number.");
        }
        long payment = amount(loanPaymentField, "Monthly Payment");
        if (payment < 0) {
            throw new IllegalArgumentException("Monthly Payment: enter the payment as a positive number.");
        }
        return new Loan(loaded == null ? 0 : loaded.id(), Loan.parseRate(rateField.getText()), original,
                (Integer) termSpinner.getValue(), startDate.getDateOrNull(),
                Objects.toString(interestCategoryCombo.getEditor().getItem(), "").trim(), payment,
                firstPaymentPicker.getDateOrNull(),
                Loan.InterestMethod.values()[Math.max(0, interestMethodCombo.getSelectedIndex())]);
    }

    /** Shows the amortization schedule for the loan as the form has it. */
    private void showSchedule() {
        Loan l;
        try {
            l = formLoan();
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        if (l.regularPayment() == 0) {
            error("Enter the original amount and the term first.");
            return;
        }
        long owedNow = 0;
        try {
            if (loaded != null && loaded.id() != 0) {
                owedNow = accounts.principalOwed(loaded.id(), java.time.LocalDate.now());
            }
        } catch (SQLException e) {
            owedNow = 0;
        }
        new LoanScheduleDialog(this, nameField.getText().trim(), l, owedNow).setVisible(true);
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
        return formDetails(false);
    }

    /** As formDetails(), but a blank name is allowed if allowBlankName (to compare a new form to its template). */
    private AccountRepository.Details formDetails(boolean allowBlankName) {
        String name = nameField.getText().trim();
        if (name.isEmpty() && !allowBlankName) {
            throw new IllegalArgumentException("Please enter an account name.");
        }
        Long parentId = parentCombo.getSelectedItem() instanceof Account p ? p.id() : null;
        AccountType type = (AccountType) typeCombo.getSelectedItem();
        if (type == AccountType.ENVELOPE && parentId == null && !allowBlankName) {
            throw new IllegalArgumentException("An envelope must belong to a parent account.");
        }
        long opening = amount(openingField, "Initial Balance");
        long adjustment = amount(adjustmentField, "Balance Adjustment");
        AccountRepository.LoanTerms terms = AccountRepository.LoanTerms.NONE;
        AccountRepository.CardTerms cardTerms = AccountRepository.CardTerms.NONE;
        if (type == AccountType.CREDIT_CARD) {
            CreditCard c = formCard();
            cardTerms = new AccountRepository.CardTerms(c.aprMilli(), c.limitCents(), c.statementDay(), c.dueDay(),
                    c.minPercentMilli(), c.minFloorCents());
        }
        if (type == AccountType.LOAN) {
            Loan l = formLoan();
            String cat = l.interestCategory();
            terms = new AccountRepository.LoanTerms(l.rateMilli(), l.originalCents(), l.termMonths(),
                    cat == null || cat.isBlank() ? null : CategoryRepository.normalize(cat), l.paymentCents(),
                    l.firstPayment(), l.interestMethod());
        }
        String category = Objects.toString(categoryCombo.getEditor().getItem(), "").trim();
        if (!category.isEmpty()) {
            category = CategoryRepository.normalize(category);
        }
        return new AccountRepository.Details(loaded.id(), name, type, parentId, blankToNull(bankField),
                startDate.getDateOrNull(), opening, adjustment, blankToNull(accountNumberField),
                blankToNull(routingNumberField), category.isEmpty() ? null : category,
                commentsArea.getText().isBlank() ? null : commentsArea.getText().strip(),
                blankToNull(websiteField), terms, cardTerms);
    }

    private static long amount(JTextField f, String what) {
        String t = f.getText().trim();
        try {
            return t.isEmpty() ? 0 : Money.parseTyped(t);
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
        try {
            return formDetails(true).equals(loaded);    // still just what the new form started with
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
                        d.routingNumber(), d.defaultCategory(), d.comments(), d.website(), d.loan(), d.card());
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

    private int addRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = field instanceof JScrollPane ? GridBagConstraints.FIRST_LINE_END : GridBagConstraints.LINE_END;
        c.insets = new Insets(3, 0, 3, 8);
        JLabel l = new JLabel(label);
        labels.put(field, l);                            // so the label can be renamed or hidden
        form.add(l, c);
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
