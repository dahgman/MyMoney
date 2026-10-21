package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The Summary screen's paycheck panel (see Paychecks): what's due until the
 * chosen paycheck next comes, or in the pay period it starts (a switch at
 * the top), in every account; the totals; and what its account will hold
 * at the end. Hidden when paychecks are turned off. Double-clicking a
 * transaction opens its account's ledger.
 */
final class PaydayWidget extends JPanel {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE");

    private final Paychecks paychecks;
    private final AccountRepository accounts;
    private final Consumer<Long> openAccount;
    private final JToggleButton untilNext = new JToggleButton(Paychecks.Mode.UNTIL_NEXT.label);
    private final JToggleButton nextPeriod = new JToggleButton(Paychecks.Mode.NEXT_PERIOD.label);
    private final JLabel subtitle = new JLabel(" ");
    private final JLabel totals = new JLabel(" ");
    private final JLabel projection = new JLabel(" ");
    private final List<Paychecks.Upcoming> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JPanel body = new JPanel(new BorderLayout());

    PaydayWidget(Paychecks paychecks, AccountRepository accounts, Consumer<Long> openAccount) {
        super(new BorderLayout());
        this.paychecks = paychecks;
        this.accounts = accounts;
        this.openAccount = openAccount;

        JLabel title = new JLabel("Until payday");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3));
        ButtonGroup modes = new ButtonGroup();
        modes.add(untilNext);
        modes.add(nextPeriod);
        untilNext.addActionListener(e -> choose(Paychecks.Mode.UNTIL_NEXT));
        nextPeriod.addActionListener(e -> choose(Paychecks.Mode.NEXT_PERIOD));
        JPanel switches = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        switches.setOpaque(false);
        switches.add(untilNext);
        switches.add(nextPeriod);
        JPanel head = new JPanel(new BorderLayout(12, 2));
        head.setOpaque(false);
        head.add(title, BorderLayout.WEST);
        head.add(switches, BorderLayout.CENTER);
        head.add(subtitle, BorderLayout.SOUTH);
        subtitle.setForeground(Color.GRAY);
        head.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(Math.max(22, table.getRowHeight() + 4));
        int[] widths = {130, 150, 220, 200, 110};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        DefaultTableCellRenderer money = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                           int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                long cents = rows.get(row).entry().amountCents;
                setText(Money.format(cents));
                if (!selected) {
                    setForeground(cents < 0 ? MainFrame.NEGATIVE : UIManager.getColor("Table.foreground"));
                }
                return this;
            }
        };
        money.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(4).setCellRenderer(money);
        table.getTableHeader().setReorderingAllowed(false);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && r >= 0) {
                    openAccount.accept(rows.get(r).account().id());
                }
            }
        });
        body.setOpaque(false);
        body.add(table.getTableHeader(), BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);
        JPanel foot = new JPanel(new BorderLayout(0, 2));
        foot.setOpaque(false);
        foot.add(totals, BorderLayout.NORTH);
        foot.add(projection, BorderLayout.SOUTH);
        foot.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        totals.setFont(totals.getFont().deriveFont(Font.BOLD));

        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0),
                        BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground") != null
                                ? UIManager.getColor("Separator.foreground") : Color.GRAY)),
                BorderFactory.createEmptyBorder(10, 12, 12, 12)));
        add(head, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);
        setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** The switch at the top: shows that period, and keeps it as the Summary's choice. */
    private void choose(Paychecks.Mode mode) {
        try {
            Paychecks.Settings s = paychecks.settings();
            paychecks.save(new Paychecks.Settings(s.enabled(), s.rank(), mode));
        } catch (SQLException ignored) {
            // shown now; just not kept for next time
        }
        show(mode);
    }

    /** Re-reads the settings and what's due; hidden when paychecks are off. */
    void refresh() {
        try {
            Paychecks.Settings s = paychecks.settings();
            setVisible(s.enabled());
            if (s.enabled()) {
                (s.mode() == Paychecks.Mode.NEXT_PERIOD ? nextPeriod : untilNext).setSelected(true);
                show(s.mode());
            }
        } catch (SQLException e) {
            subtitle.setText("Unavailable: " + e.getMessage());
        }
    }

    private void show(Paychecks.Mode mode) {
        rows.clear();
        totals.setText(" ");
        projection.setText(" ");
        try {
            Paychecks.Settings s = paychecks.settings();
            ScheduleRepository.Schedule p = paychecks.paycheck(s.rank());
            Paychecks.Period period = p == null ? null : Paychecks.period(p, mode, LocalDate.now());
            if (p == null) {
                subtitle.setText("<html>No paycheck chosen. Mark a scheduled deposit as a paycheck (Account &gt;"
                        + " Scheduled Transactions), then choose it in Edit &gt; Preferences &gt; Paychecks.</html>");
            } else if (period == null) {
                subtitle.setText("The " + Paychecks.rankName(s.rank()).toLowerCase() + " paycheck's schedule has ended.");
            } else {
                rows.addAll(paychecks.upcoming(period));
                String fmt = LedgerTableModel.datePattern();
                DateTimeFormatter d = DateTimeFormatter.ofPattern(fmt);
                subtitle.setText(Paychecks.rankName(s.rank()) + " paycheck: " + (p.payee() == null ? "" : p.payee())
                        + " " + Money.format(p.amountOn(period.payday())) + " on "
                        + period.payday().format(DAY) + " " + period.payday().format(d) + "   ·   "
                        + period.from().format(d) + " to " + period.last().format(d));
                long out = rows.stream().mapToLong(u -> Math.min(0, u.entry().amountCents)).sum();
                long in = rows.stream().mapToLong(u -> Math.max(0, u.entry().amountCents)).sum();
                totals.setText(rows.isEmpty() ? "Nothing due." : "Due: " + Money.format(-out) + "     Coming in: "
                        + Money.format(in) + "     Net: " + Money.format(in + out));
                Account into = accounts.findAll().stream().filter(a -> a.id() == p.accountId()).findFirst().orElse(null);
                if (into != null) {
                    long left = accounts.balanceThrough(into.id(), period.last());
                    projection.setText(into.name() + " on " + period.last().format(d) + ", with everything in it: "
                            + Money.format(left));
                    projection.setForeground(left < 0 ? MainFrame.NEGATIVE : UIManager.getColor("Label.foreground"));
                }
            }
        } catch (SQLException e) {
            subtitle.setText("Unavailable: " + e.getMessage());
        }
        body.setVisible(!rows.isEmpty());
        model.fireTableDataChanged();
        table.setPreferredScrollableViewportSize(table.getPreferredSize());
        setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
        revalidate();
        repaint();
    }

    /** For testing: the payees listed, in order. */
    List<String> shownPayees() {
        return rows.stream().map(u -> u.entry().payee).toList();
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Date", "Account", "Payee", "Category", "Amount"};

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Paychecks.Upcoming u = rows.get(r);
            return switch (c) {
                case 0 -> u.entry().date.format(DAY) + " " + u.entry().date.format(LedgerTableModel.DISPLAY_DATE);
                case 1 -> u.account().name();
                case 2 -> u.entry().payee == null ? "" : u.entry().payee;
                case 3 -> u.entry().category == null ? "" : u.entry().category;
                default -> "";
            };
        }
    }
}
