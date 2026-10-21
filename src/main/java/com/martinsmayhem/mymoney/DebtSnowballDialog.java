package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Account > Debt Snowball: the plan for paying off every loan and credit card
 * with one monthly amount (see Snowball). The debts are listed in payoff
 * order with this month's payment and when each is paid off; the months
 * below show the snowball rolling. Apply to Schedules sets each linked
 * scheduled payment to this month's amount.
 */
final class DebtSnowballDialog extends JDialog {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy");
    private static final Color WARNING = new Color(0xE0, 0x3E, 0x2D);

    private final Connection conn;
    private final AccountRepository accounts;
    private final ScheduleRepository schedules;
    private final Snowball.Settings settings;
    private final JTextField budgetField = new JTextField(10);
    private final JComboBox<Snowball.Method> methodCombo = new JComboBox<>(Snowball.Method.values());
    private final JLabel summary = new JLabel(" ");
    private final DebtModel debtModel = new DebtModel();
    private final MonthModel monthModel = new MonthModel();
    private final JTable debtTable = new JTable(debtModel);

    private List<Snowball.Debt> debts = List.of();
    private List<Snowball.Debt> order = List.of();       // the debts in the plan, in payoff order
    private List<Snowball.Debt> rows = List.of();        // order, then the ones left out
    private Snowball.Plan plan;
    private boolean schedulesChanged;
    private boolean loading;
    /** Asks the OK / Cancel question before schedules are changed (replaceable for testing). */
    java.util.function.Predicate<String> confirmApply = message -> JOptionPane.showConfirmDialog(this, message,
            getTitle(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;

    DebtSnowballDialog(Window owner, Connection conn, AccountRepository accounts, ScheduleRepository schedules)
            throws SQLException {
        super(owner, "Debt Snowball", ModalityType.APPLICATION_MODAL);
        this.conn = conn;
        this.accounts = accounts;
        this.schedules = schedules;
        this.settings = Snowball.load(conn);

        loading = true;
        methodCombo.setSelectedItem(settings.method);
        loading = false;
        methodCombo.addActionListener(e -> {
            if (!loading) {
                settings.method = (Snowball.Method) methodCombo.getSelectedItem();
                if (settings.method == Snowball.Method.CUSTOM && settings.customOrder.isEmpty()) {
                    settings.customOrder = ids(order);          // start from the order shown
                }
                recalculate();
            }
        });
        budgetField.setHorizontalAlignment(JTextField.RIGHT);
        budgetField.addActionListener(e -> recalculate());
        budgetField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                recalculate();
            }
        });
        JButton recalc = new JButton("Recalculate");
        recalc.addActionListener(e -> recalculate());

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(new JLabel("Monthly amount for all debts: $"));
        controls.add(budgetField);
        controls.add(new JLabel("   Order:"));
        controls.add(methodCombo);
        controls.add(recalc);
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(controls, BorderLayout.NORTH);
        top.add(summary, BorderLayout.CENTER);

        // The debts, in payoff order.
        debtTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        debtTable.setFillsViewportHeight(true);
        debtTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        int[] widths = {30, 30, 170, 95, 60, 90, 130, 95, 85, 95};
        for (int i = 0; i < widths.length; i++) {
            debtTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        DefaultTableCellRenderer right = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, f, r, c);
                setHorizontalAlignment(c >= 3 && c != 6 ? SwingConstants.RIGHT : SwingConstants.LEADING);
                if (!sel) {
                    boolean in = order.contains(rows.get(r));
                    setForeground(!in ? Color.GRAY : c == 6 && rows.get(r).minimumCents() == 0 ? WARNING
                            : t.getForeground());
                }
                return this;
            }
        };
        for (int c = 1; c < widths.length; c++) {
            debtTable.getColumnModel().getColumn(c).setCellRenderer(right);
        }
        ColumnFit.install(debtTable);
        JButton up = new JButton("Move Up");
        up.addActionListener(e -> move(-1));
        JButton down = new JButton("Move Down");
        down.addActionListener(e -> move(1));
        JLabel tip = new JLabel("Untick a debt to leave it out. Double-click Minimum to set it by hand"
                + " (clear it to go back).");
        JPanel debtButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        debtButtons.add(up);
        debtButtons.add(down);
        debtButtons.add(tip);
        JPanel debtPanel = new JPanel(new BorderLayout(0, 4));
        debtPanel.add(new JScrollPane(debtTable), BorderLayout.CENTER);
        debtPanel.add(debtButtons, BorderLayout.SOUTH);

        // Month by month.
        JTable monthTable = new JTable(monthModel);
        monthTable.setFillsViewportHeight(true);
        DefaultTableCellRenderer money = new DefaultTableCellRenderer();
        money.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = 1; c <= 3; c++) {
            monthTable.getColumnModel().getColumn(c).setCellRenderer(money);
        }
        monthTable.getColumnModel().getColumn(4).setPreferredWidth(320);
        ColumnFit.install(monthTable);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, debtPanel, new JScrollPane(monthTable));
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        JButton apply = new JButton("Apply to Schedules...");
        apply.setToolTipText("Set each debt's linked monthly scheduled payment to this month's amount in the plan");
        apply.addActionListener(e -> applyToSchedules());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new BorderLayout());
        JPanel l = new JPanel(new FlowLayout(FlowLayout.LEFT));
        l.add(apply);
        JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        r.add(close);
        buttons.add(l, BorderLayout.WEST);
        buttons.add(r, BorderLayout.EAST);

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(recalc);

        debts = Snowball.debts(accounts, schedules, settings);
        if (settings.budgetCents <= 0) {
            settings.budgetCents = debts.stream().filter(d -> !settings.excluded.contains(d.account().id()))
                    .mapToLong(Snowball.Debt::minimumCents).sum();
        }
        budgetField.setText(plain(settings.budgetCents));
        recalculate();
        setSize(1000, 680);
        setLocationRelativeTo(owner);
    }

    /** True if Apply changed any schedules. */
    boolean schedulesChanged() {
        return schedulesChanged;
    }

    // ----- Calculating -------------------------------------------------------------

    private void recalculate() {
        try {
            settings.budgetCents = budgetField.getText().isBlank() ? 0 : Math.abs(Money.parseTyped(budgetField.getText()));
        } catch (IllegalArgumentException e) {
            summary.setText(e.getMessage());
            return;
        }
        try {
            debts = Snowball.debts(accounts, schedules, settings);
            Snowball.save(conn, settings);
        } catch (SQLException e) {
            summary.setText("Could not read the debts: " + e.getMessage());
            return;
        }
        order = Snowball.ordered(debts, settings);
        List<Snowball.Debt> all = new ArrayList<>(order);
        for (Snowball.Debt d : debts) {
            if (!order.contains(d)) {
                all.add(d);
            }
        }
        rows = all;
        LocalDate first = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        plan = Snowball.plan(order, settings.budgetCents, true, first);
        Snowball.Plan minimumsOnly = Snowball.plan(order, 0, false, first);
        debtModel.fireTableDataChanged();
        monthModel.fireTableDataChanged();
        summary.setText(summaryText(minimumsOnly));
    }

    private String summaryText(Snowball.Plan minimumsOnly) {
        if (debts.isEmpty()) {
            return "No loan or credit card account owes anything today.";
        }
        if (order.isEmpty()) {
            return "No debts are in the plan: tick at least one, and give it a minimum payment.";
        }
        long minimums = order.stream().mapToLong(Snowball.Debt::minimumCents).sum();
        StringBuilder s = new StringBuilder("<html>");
        if (settings.budgetCents < minimums) {
            s.append("<font color='#e03e2d'>The monthly amount is less than the minimums (")
             .append(Money.format(minimums)).append("): the plan pays the minimums.</font><br>");
        }
        if (!plan.paidOff()) {
            s.append("<b>These payments never pay the debts off</b> (they don't cover the interest).");
        } else {
            s.append("<b>Debt-free in ").append(duration(plan.monthsToDebtFree())).append(" (")
             .append(plan.months().get(plan.months().size() - 1).date().format(MONTH)).append(")</b>, with ")
             .append(Money.format(plan.totalInterest())).append(" in interest.");
            if (minimumsOnly.paidOff()) {
                int sooner = minimumsOnly.monthsToDebtFree() - plan.monthsToDebtFree();
                long saved = minimumsOnly.totalInterest() - plan.totalInterest();
                s.append("<br>Paying only the minimums: ").append(duration(minimumsOnly.monthsToDebtFree()))
                 .append(" and ").append(Money.format(minimumsOnly.totalInterest())).append(" in interest");
                if (sooner > 0 || saved > 0) {
                    s.append(" &mdash; the snowball is ").append(duration(sooner)).append(" sooner and saves ")
                     .append(Money.format(saved)).append(".");
                }
            } else {
                s.append("<br>Paying only the minimums would never pay them all off.");
            }
            s.append("<br>Extra each month beyond the minimums: ")
             .append(Money.format(Math.max(0, settings.budgetCents - minimums))).append(".");
        }
        return s.append("</html>").toString();
    }

    /** Moves the selected debt up or down the payoff order (switching to Custom). */
    private void move(int step) {
        int r = debtTable.getSelectedRow();
        if (r < 0 || r >= order.size()) {
            return;
        }
        int to = r + step;
        if (to < 0 || to >= order.size()) {
            return;
        }
        List<Long> ids = ids(order);
        Long id = ids.remove(r);
        ids.add(to, id);
        settings.customOrder = ids;
        settings.method = Snowball.Method.CUSTOM;
        loading = true;
        methodCombo.setSelectedItem(Snowball.Method.CUSTOM);
        loading = false;
        recalculate();
        debtTable.setRowSelectionInterval(to, to);
    }

    private static List<Long> ids(List<Snowball.Debt> list) {
        List<Long> ids = new ArrayList<>();
        for (Snowball.Debt d : list) {
            ids.add(d.account().id());
        }
        return ids;
    }

    // ----- Applying to schedules ---------------------------------------------------

    /**
     * Sets each debt's linked monthly schedule to this month's plan payment,
     * and offers to end the schedules of debts already paid off. A debt's
     * minimum is remembered first (it would otherwise be read back from the
     * changed schedule, extra and all).
     */
    private void applyToSchedules() {
        recalculate();
        if (plan == null || order.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        List<ScheduleRepository.Schedule> updates = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            for (Snowball.Debt d : order) {
                long pay = plan.outcomes().get(d.account().id()).firstPayment();
                if (d.scheduleId() == null) {
                    skipped.add(d.account().name());
                    continue;
                }
                ScheduleRepository.Schedule s = schedules.find(d.scheduleId());
                if (s == null) {
                    continue;
                }
                // Paid off by this payment: the schedule ends after it.
                Recurrence r = s.recurrence();
                boolean last = plan.outcomes().get(d.account().id()).payoffMonth() == 1;
                LocalDate lastDate = last ? r.nextOnOrAfter(LocalDate.now()) : null;
                if (last && lastDate != null && !lastDate.equals(r.endDate())) {
                    r = new Recurrence(r.unit(), r.interval(), r.secondDay(), r.start(), lastDate, null);
                } else if (-s.amountCents() == pay) {
                    continue;
                }
                lines.add(d.account().name() + ": " + Money.format(-s.amountCents()) + " -> " + Money.format(pay)
                        + (last && lastDate != null ? " on " + lastDate.format(LedgerTableModel.DISPLAY_DATE)
                        + ", the final payment (the schedule then ends)" : ""));
                updates.add(new ScheduleRepository.Schedule(s.id(), s.accountId(), s.payee(), s.category(), s.tag(),
                        s.description(), s.memo(), -pay, r));
            }
            List<ScheduleRepository.Schedule> toEnd = finishedDebtSchedules();
            for (ScheduleRepository.Schedule s : toEnd) {
                lines.add(String.valueOf(s.category()).replaceAll("^\\[|\\]$", "") + ": paid off; its "
                        + Money.format(-s.amountCents()) + " schedule ends");
            }
            if (lines.isEmpty()) {
                JOptionPane.showMessageDialog(this, "The linked schedules already match the plan."
                        + (skipped.isEmpty() ? "" : "\n\nNo monthly linked schedule (link one in Scheduled"
                        + " Transactions): " + String.join(", ", skipped)), getTitle(),
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String message = "Change these scheduled payments?\n\n" + String.join("\n", lines)
                    + (skipped.isEmpty() ? "" : "\n\nNot changed (no monthly linked schedule): "
                    + String.join(", ", skipped))
                    + "\n\nUpcoming payments not yet edited are replaced with the new amounts.";
            if (!confirmApply.test(message)) {
                return;
            }
            for (Snowball.Debt d : order) {                  // keep the true minimums
                if (d.scheduleId() != null) {
                    settings.minimums.putIfAbsent(d.account().id(), d.minimumCents());
                }
            }
            Snowball.save(conn, settings);
            for (ScheduleRepository.Schedule s : updates) {
                schedules.save(s);
            }
            for (ScheduleRepository.Schedule s : toEnd) {
                endSchedule(s);
            }
            schedulesChanged = true;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not change the schedules:\n" + e.getMessage(), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
        }
        recalculate();
    }

    /** Running schedules that pay into a loan or card that's already paid off. */
    private List<ScheduleRepository.Schedule> finishedDebtSchedules() throws SQLException {
        List<ScheduleRepository.Schedule> list = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Account a : accounts.findAll()) {
            if ((a.type() == AccountType.LOAN || a.type() == AccountType.CREDIT_CARD)
                    && accounts.balanceThrough(a.id(), today) >= 0) {
                for (ScheduleRepository.Schedule s : schedules.findAll()) {
                    if (("[" + a.name() + "]").equalsIgnoreCase(String.valueOf(s.category())) && s.amountCents() < 0
                            && s.recurrence().nextOnOrAfter(today) != null) {
                        list.add(s);
                    }
                }
            }
        }
        return list;
    }

    /** Ends a schedule yesterday (its untouched upcoming payments go), or deletes it if it never started. */
    private void endSchedule(ScheduleRepository.Schedule s) throws SQLException {
        Recurrence r = s.recurrence();
        LocalDate yesterday = LocalDate.now().minusDays(1);
        if (yesterday.isBefore(r.start())) {
            schedules.delete(s.id(), true);
            return;
        }
        schedules.save(s.with(new Recurrence(r.unit(), r.interval(), r.secondDay(), r.start(), yesterday, null)));
    }

    // ----- Helpers -------------------------------------------------------------------

    private static String plain(long cents) {
        return java.math.BigDecimal.valueOf(cents, 2).toPlainString();
    }

    /** "2 years 3 months" */
    private static String duration(int months) {
        int y = months / 12;
        int m = months % 12;
        String ys = y == 0 ? "" : y + (y == 1 ? " year" : " years");
        String ms = m == 0 ? "" : m + (m == 1 ? " month" : " months");
        return ys.isEmpty() ? (ms.isEmpty() ? "0 months" : ms) : ms.isEmpty() ? ys : ys + " " + ms;
    }

    private final class DebtModel extends AbstractTableModel {
        private final String[] columns = {"In", "#", "Debt", "Owed", "Rate", "Minimum", "From", "This Month",
            "Paid Off", "Interest"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0 || column == 5;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Snowball.Debt d = rows.get(row);
            int i = order.indexOf(d);
            Snowball.Outcome o = i < 0 || plan == null ? null : plan.outcomes().get(d.account().id());
            return switch (column) {
                case 0 -> !settings.excluded.contains(d.account().id());
                case 1 -> i < 0 ? "" : String.valueOf(i + 1);
                case 2 -> d.account().name();
                case 3 -> Money.format(d.owedCents());
                case 4 -> d.rateMilli() == 0 ? "" : Loan.formatRate(d.rateMilli());
                case 5 -> d.minimumCents() == 0 ? "" : Money.format(d.minimumCents());
                case 6 -> d.minimumSource();
                case 7 -> o == null ? "" : Money.format(o.firstPayment());
                case 8 -> o == null ? "" : o.payoffMonth() == 0 ? "never"
                        : LocalDate.now().withDayOfMonth(1).plusMonths(o.payoffMonth()).format(MONTH);
                case 9 -> o == null ? "" : Money.format(o.interestCents());
                default -> "";
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            long id = rows.get(row).account().id();
            if (column == 0) {
                if (Boolean.TRUE.equals(value)) {
                    settings.excluded.remove(id);
                } else {
                    settings.excluded.add(id);
                }
            } else if (column == 5) {
                String t = value == null ? "" : value.toString().trim();
                if (t.isEmpty()) {
                    settings.minimums.remove(id);            // back to the schedule / loan / card's own
                } else {
                    try {
                        settings.minimums.put(id, Math.abs(Money.parseTyped(t)));
                    } catch (IllegalArgumentException e) {
                        JOptionPane.showMessageDialog(DebtSnowballDialog.this, e.getMessage(), getTitle(),
                                JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }
            }
            javax.swing.SwingUtilities.invokeLater(DebtSnowballDialog.this::recalculate);
        }
    }

    private final class MonthModel extends AbstractTableModel {
        private final String[] columns = {"Month", "Payments", "Interest", "Still Owed", "Paid Off"};

        @Override
        public int getRowCount() {
            return plan == null ? 0 : plan.months().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Snowball.Month m = plan.months().get(row);
            return switch (column) {
                case 0 -> m.date().format(MONTH);
                case 1 -> Money.format(m.paymentCents());
                case 2 -> Money.format(m.interestCents());
                case 3 -> Money.format(m.owedAfterCents());
                case 4 -> String.join(", ", m.paidOff());
                default -> "";
            };
        }
    }
}
