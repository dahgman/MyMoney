package com.martinsmayhem.mymoney;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/**
 * The Summary screen (shown instead of a ledger when Summary, above the
 * account tree, is clicked): the net worth, then widgets.
 *
 * Accounts widget: every account, grouped by type like the tree, with its
 * current balance (every transaction dated today or earlier) and a bar for
 * it, from a zero line: blue right for money you have, red left for money
 * owed. The numbers are the bars' labels; hovering a bar shows it too.
 * Double-clicking an account opens its ledger.
 */
final class SummaryPanel extends JPanel {

    /** Bars: a blue / red pair (sign is also shown by direction from the zero line). */
    private static final Color POSITIVE_BAR = new Color(0x3b7dd8);
    private static final Color NEGATIVE_BAR = new Color(0xd64a3f);
    private static final int BAR_THICKNESS = 12;
    private static final int BAR_RADIUS = 4;

    private final AccountRepository accounts;
    private final Consumer<Long> openAccount;
    private final JLabel netWorth = new JLabel(" ");
    private final JLabel asOf = new JLabel(" ");
    private final AccountsModel model = new AccountsModel();
    private final JTable table = new JTable(model);
    private final List<Row> rows = new ArrayList<>();
    private JPanel accountsCard;
    private long maxPositive;
    private long maxNegative;

    /** @param openAccount shows an account's ledger (by id) */
    SummaryPanel(AccountRepository accounts, Consumer<Long> openAccount) {
        super(new BorderLayout());
        this.accounts = accounts;
        this.openAccount = openAccount;
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

        JLabel title = new JLabel("Summary");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 8));
        JLabel netWorthLabel = new JLabel("Net worth");
        netWorth.setFont(netWorth.getFont().deriveFont(Font.BOLD, 40f));
        asOf.setForeground(Color.GRAY);
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.PAGE_AXIS));
        header.setOpaque(false);
        for (Component c : new Component[] {title, Box.createVerticalStrut(10), netWorthLabel, netWorth, asOf,
                Box.createVerticalStrut(14)}) {
            if (c instanceof JComponent j) {
                j.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            header.add(c);
        }

        JPanel widgets = new JPanel();
        widgets.setLayout(new BoxLayout(widgets, BoxLayout.PAGE_AXIS));
        widgets.setOpaque(false);
        accountsCard = accountsWidget();
        widgets.add(accountsCard);
        widgets.add(Box.createVerticalGlue());

        add(header, BorderLayout.NORTH);
        add(widgets, BorderLayout.CENTER);
    }

    /** Re-reads every account's current balance. */
    void refresh() {
        rows.clear();
        long total = 0;
        try {
            List<Account> all = accounts.findAll();
            LocalDate today = LocalDate.now();
            for (AccountType type : AccountType.values()) {
                List<Row> group = new ArrayList<>();
                long groupTotal = 0;
                for (Account a : all) {
                    if (a.type() == type && a.parentId() == null) {
                        long bal = accounts.balanceThrough(a.id(), today);
                        group.add(new Row(Kind.ACCOUNT, a.name(), 1, a.id(), bal));
                        groupTotal += bal;
                        for (Account e : all) {              // its envelopes, indented under it
                            if (e.parentId() != null && e.parentId() == a.id()) {
                                long eb = accounts.balanceThrough(e.id(), today);
                                group.add(new Row(Kind.ACCOUNT, e.name(), 2, e.id(), eb));
                                groupTotal += eb;
                            }
                        }
                    }
                }
                if (!group.isEmpty()) {
                    rows.add(new Row(Kind.HEADING, heading(type), 0, 0, groupTotal));
                    rows.addAll(group);
                    total += groupTotal;
                }
            }
        } catch (SQLException e) {
            netWorth.setText("Unavailable");
            asOf.setText(e.getMessage());
            return;
        }
        rows.add(new Row(Kind.TOTAL, "Net worth", 0, 0, total));
        maxPositive = rows.stream().filter(r -> r.kind == Kind.ACCOUNT).mapToLong(r -> Math.max(0, r.cents))
                .max().orElse(0);
        maxNegative = rows.stream().filter(r -> r.kind == Kind.ACCOUNT).mapToLong(r -> Math.max(0, -r.cents))
                .max().orElse(0);
        netWorth.setText(Money.format(total));
        asOf.setText("All accounts, as of " + LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                + " (transactions dated today or earlier)");
        model.fireTableDataChanged();
        table.setPreferredScrollableViewportSize(table.getPreferredSize());
        accountsCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, accountsCard.getPreferredSize().height));
        revalidate();                                    // (the widget is as tall as its rows, no taller)
        repaint();
    }

    private static String heading(AccountType t) {
        return switch (t) {
            case CHECKING -> "Checking";
            case SAVINGS -> "Savings";
            case CREDIT_CARD -> "Credit Cards";
            case CASH -> "Cash";
            case LOAN -> "Loans";
            case OTHER_ASSET -> "Other Assets";
            case ENVELOPE -> "Envelopes";
        };
    }

    // ----- The Accounts widget -----------------------------------------------------

    private enum Kind { HEADING, ACCOUNT, TOTAL }

    private record Row(Kind kind, String name, int depth, long accountId, long cents) {
    }

    private JPanel accountsWidget() {
        table.setRowHeight(Math.max(22, table.getRowHeight() + 4));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(420);
        table.getColumnModel().getColumn(0).setCellRenderer(textRenderer(SwingConstants.LEADING));
        table.getColumnModel().getColumn(1).setCellRenderer(textRenderer(SwingConstants.RIGHT));
        table.getColumnModel().getColumn(2).setCellRenderer(new BarRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && r >= 0 && rows.get(r).kind == Kind.ACCOUNT) {
                    openAccount.accept(rows.get(r).accountId);   // double-click: its ledger
                }
            }
        });

        JLabel title = new JLabel("Accounts");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3));
        JLabel hint = new JLabel("Current balances. Double-click an account to open its ledger.");
        hint.setForeground(Color.GRAY);
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.setOpaque(false);
        top.add(title, BorderLayout.NORTH);
        top.add(hint, BorderLayout.SOUTH);
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JPanel body = new JPanel(new BorderLayout());
        body.add(table.getTableHeader(), BorderLayout.NORTH);
        body.add(table, BorderLayout.CENTER);

        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground") != null
                        ? UIManager.getColor("Separator.foreground") : Color.GRAY),
                BorderFactory.createEmptyBorder(10, 12, 12, 12)));
        card.add(top, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    /** Names and amounts in the text color; headings and the total bold; envelopes indented. */
    private TableCellRenderer textRenderer(int align) {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                    int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, false, row, column);
                Row r = rows.get(row);
                setHorizontalAlignment(align);
                setFont(t.getFont().deriveFont(r.kind == Kind.ACCOUNT ? Font.PLAIN : Font.BOLD));
                int indent = column == 0 ? 6 + r.depth * 16 : 6;
                setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 8));
                if (r.kind == Kind.TOTAL) {
                    setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
                            BorderFactory.createEmptyBorder(0, indent, 0, 8)));
                }
                return this;
            }
        };
    }

    /** One account's bar, from the zero line; nothing for headings and the total. */
    private final class BarRenderer extends JComponent implements TableCellRenderer {
        private Row row;
        private Color background;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean focus,
                int r, int column) {
            row = rows.get(r);
            background = isSelected ? t.getSelectionBackground() : t.getBackground();
            setToolTipText(row.kind == Kind.ACCOUNT ? row.name + ": " + Money.format(row.cents) : null);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(background);
            g2.fillRect(0, 0, getWidth(), getHeight());
            int pad = 10;
            int width = getWidth() - 2 * pad;
            long span = maxPositive + maxNegative;
            if (width <= 0 || span == 0) {
                g2.dispose();
                return;
            }
            int zero = pad + (int) Math.round(width * (double) maxNegative / span);
            g2.setColor(new Color(128, 128, 128, 140));           // the zero line: a recessive hairline
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(zero, 0, zero, getHeight());
            if (row.kind == Kind.ACCOUNT && row.cents != 0) {
                int len = (int) Math.max(2, Math.round(width * (double) Math.abs(row.cents) / span));
                int y = (getHeight() - BAR_THICKNESS) / 2;
                boolean positive = row.cents > 0;
                g2.setColor(positive ? POSITIVE_BAR : NEGATIVE_BAR);
                g2.fill(bar(positive ? zero : zero - len, y, len, BAR_THICKNESS, positive));
            }
            g2.dispose();
        }

        /** A bar square at the zero line, with a 4px rounded end at its tip. */
        private Path2D bar(int x, int y, int w, int h, boolean roundRight) {
            int r = Math.min(BAR_RADIUS, w);
            Path2D p = new Path2D.Float();
            if (roundRight) {
                p.moveTo(x, y);
                p.lineTo(x + w - r, y);
                p.quadTo(x + w, y, x + w, y + r);
                p.lineTo(x + w, y + h - r);
                p.quadTo(x + w, y + h, x + w - r, y + h);
                p.lineTo(x, y + h);
            } else {
                p.moveTo(x + w, y);
                p.lineTo(x + r, y);
                p.quadTo(x, y, x, y + r);
                p.lineTo(x, y + h - r);
                p.quadTo(x, y + h, x + r, y + h);
                p.lineTo(x + w, y + h);
            }
            p.closePath();
            return p;
        }
    }

    private final class AccountsModel extends AbstractTableModel {
        private final String[] columns = {"Account", "Balance", ""};

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
        public Object getValueAt(int row, int column) {
            Row r = rows.get(row);
            return switch (column) {
                case 0 -> r.name;
                case 1 -> Money.format(r.cents);
                default -> "";
            };
        }
    }

    /** For testing: the account names and balances shown, in order. */
    List<String> shownRows() {
        List<String> list = new ArrayList<>();
        for (Row r : rows) {
            list.add("  ".repeat(r.depth) + r.name + " " + Money.format(r.cents));
        }
        return list;
    }
}
