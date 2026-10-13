package com.martinsmayhem.mymoney;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Account > Scheduled Transactions: every schedule, with New, Edit and Delete.
 * Each row shows the account, payee, amount, how often, the next date and
 * when it ends.
 */
final class ScheduleListDialog extends JDialog {

    private final ScheduleRepository schedules;
    private final AccountRepository accounts;
    private final Function<ScheduleRepository.Schedule, Long> openForm;  // the form; returns the saved id or null
    private final List<ScheduleRepository.Schedule> rows = new ArrayList<>();
    private final Map<Long, String> accountNames = new HashMap<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JButton editButton = new JButton("Edit...");
    private final JButton deleteButton = new JButton("Delete...");
    private boolean changed;

    /**
     * @param openForm opens the schedule form for a schedule (id 0 = new); returns the saved id, or null
     */
    ScheduleListDialog(Window owner, ScheduleRepository schedules, AccountRepository accounts,
                       Function<ScheduleRepository.Schedule, Long> openForm) {
        super(owner, "Scheduled Transactions", ModalityType.APPLICATION_MODAL);
        this.schedules = schedules;
        this.accounts = accounts;
        this.openForm = openForm;

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    edit();
                }
            }
        });
        int[] widths = {130, 170, 90, 170, 95, 130};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(right);
        ColumnFit.install(table);

        JButton newButton = new JButton("New...");
        newButton.addActionListener(e -> add());
        editButton.addActionListener(e -> edit());
        deleteButton.addActionListener(e -> delete());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.add(newButton);
        left.add(editButton);
        left.add(deleteButton);
        JPanel right2 = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right2.add(close);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right2, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8),
                scroll.getBorder()));
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        load(null);
        setSize(840, 420);
        setLocationRelativeTo(owner);
    }

    /** True if a schedule was added, changed or deleted. */
    boolean wasChanged() {
        return changed;
    }

    private void load(Long selectId) {
        rows.clear();
        accountNames.clear();
        try {
            for (Account a : accounts.findAll()) {
                accountNames.put(a.id(), a.name());
            }
            rows.addAll(schedules.findAll());
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not read the schedules:\n" + e.getMessage(), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
        }
        rows.sort((a, b) -> {
            LocalDate x = next(a);
            LocalDate y = next(b);
            if (x == null || y == null) {
                return x == null ? (y == null ? 0 : 1) : -1;   // ended ones last
            }
            return x.compareTo(y);
        });
        model.fireTableDataChanged();
        for (int i = 0; i < rows.size(); i++) {
            if (selectId != null && rows.get(i).id() == selectId) {
                table.setRowSelectionInterval(i, i);
            }
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean any = table.getSelectedRow() >= 0;
        editButton.setEnabled(any);
        deleteButton.setEnabled(any);
    }

    private void add() {
        Long id = openForm.apply(null);
        if (id != null) {
            changed = true;
            load(id);
        }
    }

    private void edit() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        Long id = openForm.apply(rows.get(row));
        if (id != null) {
            changed = true;
            load(id);
        }
    }

    private void delete() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        ScheduleRepository.Schedule s = rows.get(row);
        int upcoming;
        try {
            upcoming = schedules.countUntouchedUpcoming(s.id());
        } catch (SQLException e) {
            upcoming = 0;
        }
        String what = "\"" + (s.payee() == null ? "(no payee)" : s.payee()) + "\" (" + Money.format(s.amountCents())
                + ", " + s.recurrence().describe().toLowerCase() + ")";
        boolean removeUpcoming = false;
        if (upcoming > 0) {
            Object[] options = {"Delete Them Too", "Keep Them", "Cancel"};
            int choice = JOptionPane.showOptionDialog(this, "Delete the schedule " + what + "?\n\n"
                    + "It has " + upcoming + (upcoming == 1 ? " upcoming transaction" : " upcoming transactions")
                    + " in the ledger, still Scheduled. Delete " + (upcoming == 1 ? "it" : "them") + " too?",
                    getTitle(), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (choice != 0 && choice != 1) {
                return;
            }
            removeUpcoming = choice == 0;
        } else if (JOptionPane.showConfirmDialog(this, "Delete the schedule " + what + "?", getTitle(),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            schedules.delete(s.id(), removeUpcoming);
            changed = true;
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Could not delete the schedule:\n" + e.getMessage(), getTitle(),
                    JOptionPane.ERROR_MESSAGE);
        }
        load(null);
    }

    /** The schedule's next date from today, or null if it has ended. */
    private static LocalDate next(ScheduleRepository.Schedule s) {
        return s.recurrence().nextOnOrAfter(LocalDate.now());
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Account", "Payee", "Amount", "Repeats", "Next", "Ends"};

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
            ScheduleRepository.Schedule s = rows.get(row);
            Recurrence r = s.recurrence();
            return switch (column) {
                case 0 -> accountNames.getOrDefault(s.accountId(), "?");
                case 1 -> s.payee() == null ? "" : s.payee();
                case 2 -> Money.format(s.amountCents());
                case 3 -> r.describe();
                case 4 -> {
                    LocalDate n = next(s);
                    yield n == null ? "(ended)" : n.format(LedgerTableModel.DISPLAY_DATE);
                }
                case 5 -> r.endDate() != null ? r.endDate().format(LedgerTableModel.DISPLAY_DATE)
                        : r.maxCount() != null ? "after " + r.maxCount() + " times" : "never";
                default -> "";
            };
        }
    }
}
